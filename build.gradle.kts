import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.nio.file.Files

plugins {
    java
    id("com.gradleup.shadow") version "9.3.2"
}

version = providers.fileContents(layout.projectDirectory.file("VERSION")).asText.get().trim()
repositories { mavenCentral() }

// Generated Gradle outputs live in a dedicated root tree; deployment data stays under build/.
layout.buildDirectory.set(layout.projectDirectory.dir(".build"))
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}
val launcher25 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

dependencies {
    implementation("org.yaml:snakeyaml:2.5")
    // JLine's aggregate classifier already contains its providers/native libraries.
    implementation("org.jline:jline:4.4.7:jdk11") { isTransitive = false }
    // Production uses java.sql and loads the driver at runtime.
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0") { isTransitive = false }
}

tasks.processResources {
    from("VERSION") { rename { "version.txt" } }
    from(listOf("LICENSE", "NOTICE")) { into("META-INF/tortodrone") }
    from("scripts/compat/WebUiAssetsExport.java") { into("tools") }
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.jar { enabled = false }

// Allow disposable builds beside an existing deployment, without modifying its runtime state.
val publicOutput = layout.projectDirectory.dir(providers.gradleProperty("deploymentOutput").getOrElse("build"))
fun ShadowJar.executableJar(filename: String, entryPoint: String) {
    archiveFileName.set(filename)
    destinationDirectory.set(publicOutput)
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    manifest.attributes("Main-Class" to entryPoint, "Enable-Native-Access" to "ALL-UNNAMED")
    // Ordinary duplicates keep the first resource; providers must all reach the merger.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/INDEX.LIST",
        "module-info.class", "META-INF/versions/**/module-info.class")
    failOnDuplicateEntries.set(true)
}
val serverJar = tasks.named<ShadowJar>("shadowJar") {
    executableJar("tortodrone.jar", "org.tortodrone.server.Server")
}


// Compile the existing Java preparation tool and its standalone regression together.
val buildTools = sourceSets.create("buildTools") {
    java.setSrcDirs(listOf("scripts"))
    java.include("BuildTools.java", "BuildToolsTest.java", "StockTools.java", "ProcessCleanupTest.java",
        "compat/CheckClientWorld.java", "compat/CheckCombatRendering.java")
    resources.setSrcDirs(emptyList<String>())
}
buildTools.compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
buildTools.runtimeClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()

fun preparation(name: String, command: String) = tasks.register<JavaExec>(name) {
    group = "build setup"
    description = "Prepare unified content and configuration; preserve administrator edits."
    classpath = buildTools.runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("BuildTools")
    workingDir = projectDir
    val output = if (command == "maps") providers.gradleProperty("mapsOutput").orElse(
        providers.gradleProperty("defaultsOutput")).getOrElse(publicOutput.dir("content/maps").asFile.absolutePath)
    else providers.gradleProperty("contentOutput").orElse(
        providers.gradleProperty("defaultsOutput")).getOrElse(publicOutput.dir("content").asFile.absolutePath)
    args(command, output)
    // Always recheck source integrity and fill missing files; never claim ownership of defaults.
}
val validateContent = tasks.register<JavaExec>("validateContent") {
    group = "verification"
    description = "Validate the selected source content snapshot without touching deployment data."
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("org.tortodrone.resources.ContentManager")
    workingDir = projectDir
    args(projectDir.absolutePath)
}
val prepareContent = preparation("prepareContent", "content").apply { configure { dependsOn(validateContent) } }
preparation("prepareMaps", "maps")
// Rebuild modern packs only from local hash-verified captured binaries. Publication is
// explicit; neither a normal build nor a failed export may restore historical scenes.
val prepareHaven = tasks.register<Exec>("prepareHaven") {
    group = "build setup"
    description = "Rebuild modern Haven into a fresh staging directory using local captured scenes and stock configurations."
    workingDir = projectDir
    dependsOn(serverJar)
    val gameDirectory = providers.gradleProperty("gameDir").getOrElse("")
    val capturedScenes = providers.gradleProperty("capturedScenes").getOrElse("")
    val output = providers.gradleProperty("havenOutput").getOrElse(publicOutput.dir("haven-export").asFile.absolutePath)
    commandLine(launcher25.get().executablePath.asFile.absolutePath,"scripts/StockTools.java",
        "haven",gameDirectory,capturedScenes,output)
    doFirst {
        if(gameDirectory.isBlank() || capturedScenes.isBlank())
            throw GradleException("Specify -PgameDir and -PcapturedScenes (verified local <scene-id>.dat files); output must be fresh")
    }
}
val buildWebui = tasks.register<Sync>("buildWebui") {
    // Locally extracted artwork is not a build input and must survive frontend updates.
    preserve { include("assets/game/**", "client-artwork.skipped") }
    val installedManifest = publicOutput.file("webui/assets/game/manifest.json").asFile
    from("src/webui") {
        eachFile {
            if (path.startsWith("assets/game/") && installedManifest.isFile) exclude()
        }
    }
    // Optional prepared local artwork. Ordinary clean builds retain source-owned fallbacks.
    val preparedAssets = providers.gradleProperty("webuiAssets").getOrElse("local/webui-assets-optimized")
    if (file(preparedAssets).isDirectory) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(preparedAssets) { into("assets/game") }
    }
    into(publicOutput.dir("webui"))
}
val buildServer = tasks.register("buildServer") {
    group = "build"
    description = "Build the executable server JAR and prepare unified content."
    dependsOn(serverJar, prepareContent, buildWebui)
}
tasks.assemble { dependsOn(buildServer) }
// Keep the conventional build task focused on deliverables. Regression programs are
// explicit opt-in tasks, so `build` must not pull in test/check dependencies.
tasks.named("build") {
    description = "Assemble the server, WebUI and prepared content; no tests."
    setDependsOn(listOf(tasks.assemble))
}
fun packagedRun(name: String, jar: TaskProvider<ShadowJar>, entryPoint: String, prerequisite: TaskProvider<Task>, workingDirectory: java.io.File) =
    tasks.register<JavaExec>(name) {
        group = "application"
        description = "Run $entryPoint from its packaged JAR (data stays beside the JAR)."
        dependsOn(prerequisite)
        javaLauncher.set(launcher25)
        classpath = files(jar.flatMap { it.archiveFile })
        mainClass.set(entryPoint)
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        workingDir = workingDirectory
        standardInput = System.`in`
    }
packagedRun("runServer", serverJar, "org.tortodrone.server.Server", buildServer, publicOutput.asFile)

// Bound regression JVMs, whose RSA/PBKDF2 work otherwise crowds out local socket deadlines.
// A shared service also enforces this limit when the configuration cache is reused.
abstract class RegressionExecution : BuildService<BuildServiceParameters.None>
val regressionExecution = gradle.sharedServices.registerIfAbsent("regressionExecution", RegressionExecution::class) {
    maxParallelUsages.set(1)
}

// Integration programs use separate JVMs; unit programs share one sequential runner.
// Use packaged code rather than main output so tests exercise the shipped resources/driver.
fun standalone(name: String, entryPoint: String, arguments: List<String> = emptyList()) =
    tasks.register<JavaExec>(name) {
        usesService(regressionExecution)
        dependsOn(buildServer, tasks.testClasses)
        javaLauncher.set(launcher25)
        classpath = files(serverJar.flatMap { it.archiveFile }, sourceSets.test.get().output)
        mainClass.set(entryPoint)
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        workingDir = projectDir
        args(arguments)
    }
val unitProgramNames = fileTree("src/test/java") {
    include("**/*Test.java")
    exclude("org/tortodrone/integration/**")
}.files.sortedBy { it.path }.map { source ->
    source.relativeTo(file("src/test/java")).invariantSeparatorsPath
        .removeSuffix(".java").replace('/', '.')
}
val requestedUnitTests = providers.gradleProperty("unitTests").orNull
val selectedUnitPrograms = requestedUnitTests?.split(',')?.map { requested ->
    val name = requested.trim()
    val matches = unitProgramNames.filter { it == name || it.substringAfterLast('.') == name }
    if (name.isEmpty() || matches.size != 1)
        throw GradleException("Unknown or ambiguous unit test '$name'; use a test class name, separated by commas")
    matches.single()
}?.distinct() ?: unitProgramNames
val testUnit = tasks.register<JavaExec>("testUnit") {
    group = "verification"
    description = "Run standalone unit programs; select classes with -PunitTests=A,B."
    usesService(regressionExecution)
    dependsOn(buildServer, tasks.testClasses)
    javaLauncher.set(launcher25)
    classpath = files(serverJar.flatMap { it.archiveFile }, sourceSets.test.get().output)
    mainClass.set("org.tortodrone.support.StandaloneTestSuite")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = projectDir
    args(selectedUnitPrograms)
}
val serverPath = publicOutput.file("tortodrone.jar").asFile.absolutePath
val integrationPrograms = listOf(
    standalone("integrationServer", "org.tortodrone.integration.ServerIntegrationTest", listOf(serverPath)),
    standalone("integrationAuthentication", "org.tortodrone.integration.AuthenticationIntegrationTest", listOf(serverPath)),
    standalone("integrationWebUi", "org.tortodrone.integration.WebUiSetupIntegrationTest", listOf(serverPath)),
    standalone("integrationConsole", "org.tortodrone.integration.ConsoleIntegrationTest", listOf(serverPath)),
    standalone("integrationMaps", "org.tortodrone.integration.MapPreparationIntegrationTest").apply { configure {
        dependsOn(tasks.named("buildToolsClasses"))
        classpath += buildTools.output
    } }
)
val testBuildTools = tasks.register<JavaExec>("testBuildTools") {
    usesService(regressionExecution)
    javaLauncher.set(launcher25)
    classpath = buildTools.runtimeClasspath
    mainClass.set("BuildToolsTest")
    workingDir = projectDir
}
val testProcessCleanup = tasks.register<JavaExec>("testProcessCleanup") {
    usesService(regressionExecution)
    javaLauncher.set(launcher25)
    classpath = buildTools.runtimeClasspath
    mainClass.set("ProcessCleanupTest")
    workingDir = projectDir
}
val testIntegration = tasks.register("testIntegration") {
    group = "verification"
    description = "Run isolated packaged application, map and download regressions."
    dependsOn(integrationPrograms, testBuildTools, testProcessCleanup)
    mustRunAfter(testUnit)
}
// There are no JUnit classes. `build` must stay test-free; tests use explicit task names.
tasks.test {
    enabled = false
    description = "Disabled. Use testUnit, testIntegration or testAll."
}
tasks.register("testAll") {
    group = "verification"
    description = "Run all standalone unit and packaged integration test suites."
    dependsOn(testUnit, testIntegration)
}
tasks.register<JavaExec>("benchmark") {
    group = "benchmark"
    description = "Run the standalone load benchmark; pass options with --args."
    dependsOn(buildServer, tasks.testClasses)
    javaLauncher.set(launcher25)
    classpath = files(serverJar.flatMap { it.archiveFile }, sourceSets.test.get().output)
    mainClass.set("org.tortodrone.benchmark.LoadBenchmark")
    systemProperty("tortodrone.serverJar", serverPath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = projectDir
}
tasks.register<JavaExec>("benchmarkScenes") {
    group = "benchmark"
    description = "Compare scene hot-path CPU/allocation without sockets; optional --args=10000 iterations."
    dependsOn(buildServer, tasks.testClasses)
    javaLauncher.set(launcher25)
    classpath = files(serverJar.flatMap { it.archiveFile }, sourceSets.test.get().output)
    mainClass.set("org.tortodrone.benchmark.SceneBenchmark")
    workingDir = projectDir
}

tasks.clean {
    delete(serverJar.flatMap { it.archiveFile }, publicOutput.file("tortodrone-admin.jar"))
    delete(publicOutput.file("tortodrone.jar.tmp"), publicOutput.file("tortodrone-admin.jar.tmp"))
    // Preserve runtime data, versioned content, dependencies and unknown files.
    delete(publicOutput.dir(".build"))
    // Gradle verification reports use this fixed path even with a custom buildDirectory.
    delete(publicOutput.dir("reports/dependency-verification"))
}
tasks.wrapper {
    gradleVersion = "9.5.1"
    networkTimeout.set(60_000)
    retries.set(2)
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f"
}

// Publish clean defaults, not the operator's deployment (which may contain private
// configuration, locally extracted artwork, skip decisions or unfinished exports).
val portableZip = tasks.register<Zip>("portableZip") {
    group = "distribution"
    dependsOn(buildServer)
    archiveFileName.set("Tortodrone-v${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(serverJar.flatMap { it.archiveFile })
    from("src/webui") { into("webui") }
    from("config") { into("config") }
    from("content") { into("content") }
    from("plugins") { into("plugins") }
    from("VERSION")
    from("README.md") {
        filter { line: String -> line.replace("](LICENSE)", "](docs/LICENSE)").replace("href=\"LICENSE\"", "href=\"docs/LICENSE\"")
            .replace("](NOTICE)", "](docs/NOTICE)").replace("](CONTRIBUTING.md)", "](docs/CONTRIBUTING.md)")
            .replace("](src/main/resources/THIRD-PARTY.md)", "](docs/THIRD-PARTY.md)") }
    }
    from("docs") {
        into("docs")
        filesMatching("provenance.md") {
            filter { line: String -> line.replace("](../LICENSE)", "](LICENSE)").replace("](../NOTICE)", "](NOTICE)")
                .replace("](../src/main/resources/THIRD-PARTY.md)", "](THIRD-PARTY.md)")
                .replace("](../src/main/resources/licenses)", "](licenses)") }
        }
    }
    from(listOf("LICENSE", "NOTICE")) { into("docs") }
    from("CONTRIBUTING.md") {
        into("docs")
        filter { line: String -> line.replace("](docs/", "](") }
    }
    from("src/main/resources/licenses") { into("docs/licenses") }
    from("src/main/resources/THIRD-PARTY.md") { into("docs") }
    eachFile {
        var source: File? = file
        while (source != null) {
            if (Files.isSymbolicLink(source.toPath()))
                throw GradleException("Refusing to package a symbolic link: $path")
            source = source.parentFile
        }
    }
}
val integrationPackaging = standalone("integrationPackaging", "org.tortodrone.integration.PackagingIntegrationTest",
    listOf(portableZip.get().archiveFile.get().asFile.absolutePath)).apply {
    configure { dependsOn(portableZip) }
}
testIntegration.configure { dependsOn(integrationPackaging) }
