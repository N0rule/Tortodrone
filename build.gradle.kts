import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

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
    // JLine's aggregate classifier already contains its providers/native libraries.
    implementation("org.jline:jline:4.4.7:jdk11") { isTransitive = false }
    // Production uses java.sql and loads the driver at runtime.
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0") { isTransitive = false }
}

tasks.processResources {
    from("VERSION") { rename { "version.txt" } }
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.jar { enabled = false }

val publicOutput = layout.projectDirectory.dir("build")
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
val adminJar = tasks.register<ShadowJar>("adminJar") {
    executableJar("tortodrone-admin.jar", "org.tortodrone.admin.AdminApplication")
}

// Compile the existing Java preparation tool and its standalone regression together.
val buildTools = sourceSets.create("buildTools") {
    java.setSrcDirs(listOf("scripts"))
    java.include("BuildTools.java", "BuildToolsTest.java")
    resources.setSrcDirs(emptyList<String>())
}
fun preparation(name: String, command: String) = tasks.register<JavaExec>(name) {
    group = "build setup"
    description = "Verify local map checksums and copy missing $command files; preserve existing files."
    classpath = buildTools.runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("BuildTools")
    workingDir = projectDir
    args(command, providers.gradleProperty("defaultsOutput").getOrElse(
        publicOutput.dir(if (command == "maps") "defaults/maps" else "defaults").asFile.absolutePath))
    // Always recheck source integrity and fill missing files; never claim ownership of defaults.
}
val prepareDefaults = preparation("prepareDefaults", "defaults")
preparation("prepareMaps", "maps")
val buildServer = tasks.register("buildServer") {
    group = "build"
    description = "Build the executable server JAR and prepare external defaults."
    dependsOn(serverJar, prepareDefaults)
}
val buildAdmin = tasks.register("buildAdmin") {
    group = "build"
    description = "Build the executable local admin JAR."
    dependsOn(adminJar)
}
tasks.assemble { dependsOn(buildServer, buildAdmin) }

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
packagedRun("runAdmin", adminJar, "org.tortodrone.admin.AdminApplication", buildAdmin, projectDir)

// The test suite consists of standalone main programs, each in a fresh JVM.
// Use packaged code rather than main output so tests exercise the shipped resources/driver.
fun standalone(name: String, entryPoint: String, arguments: List<String> = emptyList()) =
    tasks.register<JavaExec>(name) {
        dependsOn(buildServer, buildAdmin, tasks.testClasses)
        javaLauncher.set(launcher25)
        classpath = files(serverJar.flatMap { it.archiveFile }, sourceSets.test.get().output)
        mainClass.set(entryPoint)
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        workingDir = projectDir
        args(arguments)
    }
val unitPrograms = fileTree("src/test/java") {
    include("**/*Test.java")
    exclude("org/tortodrone/integration/**")
}.files.sortedBy { it.path }.map { source ->
    val entryPoint = source.relativeTo(file("src/test/java")).invariantSeparatorsPath
        .removeSuffix(".java").replace('/', '.')
    standalone("unit${entryPoint.removePrefix("org.tortodrone.").replace('.', '_')}", entryPoint)
}
val testUnit = tasks.register("testUnit") {
    group = "verification"
    description = "Run all standalone unit test programs."
    dependsOn(unitPrograms)
}
val serverPath = publicOutput.file("tortodrone.jar").asFile.absolutePath
val adminPath = publicOutput.file("tortodrone-admin.jar").asFile.absolutePath
val integrationPrograms = listOf(
    standalone("integrationServer", "org.tortodrone.integration.ServerIntegrationTest", listOf(serverPath)),
    standalone("integrationAdmin", "org.tortodrone.integration.AdminIntegrationTest", listOf(serverPath, adminPath)),
    standalone("integrationConsole", "org.tortodrone.integration.ConsoleIntegrationTest", listOf(serverPath)),
    standalone("integrationMaps", "org.tortodrone.integration.MapPreparationIntegrationTest")
)
val testBuildTools = tasks.register<JavaExec>("testBuildTools") {
    javaLauncher.set(launcher25)
    classpath = buildTools.runtimeClasspath
    mainClass.set("BuildToolsTest")
    workingDir = projectDir
}
val testIntegration = tasks.register("testIntegration") {
    group = "verification"
    description = "Run isolated packaged application, map and download regressions."
    dependsOn(integrationPrograms, testBuildTools)
    mustRunAfter(testUnit)
}
// There are no JUnit classes. Keep Gradle's conventional test/check/build entry points useful.
tasks.test {
    enabled = false
    dependsOn(testUnit, testIntegration)
}
tasks.register<JavaExec>("benchmark") {
    group = "benchmark"
    description = "Run the standalone load benchmark; pass options with --args."
    dependsOn(buildServer, buildAdmin, tasks.testClasses)
    javaLauncher.set(launcher25)
    classpath = files(serverJar.flatMap { it.archiveFile }, sourceSets.test.get().output)
    mainClass.set("org.tortodrone.benchmark.LoadBenchmark")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = projectDir
}

tasks.clean {
    delete(serverJar.flatMap { it.archiveFile }, adminJar.flatMap { it.archiveFile })
    delete(publicOutput.file("tortodrone.jar.tmp"), publicOutput.file("tortodrone-admin.jar.tmp"))
    // Legacy Make scratch only. Preserve build/data, build/defaults, build/deps and unknown files.
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
