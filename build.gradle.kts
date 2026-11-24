import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.nio.file.Files

plugins {
    java
    id("com.gradleup.shadow") version "9.3.2"
    id("com.diffplug.spotless") version "8.12.0"
}

version = providers.fileContents(layout.projectDirectory.file("VERSION")).asText.get().trim()
repositories { mavenCentral() }

// Gradle keeps the conventional build/ scratch tree; the runnable deployment (JAR, prepared
// content, WebUI and its runtime data/) goes to dist/. Override the deployment with
// -PdeploymentOutput=<path> or TORTODRONE_DEPLOYMENT.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}
val launcher25 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

dependencies {
    // The root project is the packaging/composition :app module.
    implementation(project(":core"))
    implementation(project(":content"))
    implementation(project(":protocol"))
    implementation(project(":world"))
    implementation(project(":gameplay"))
    implementation(project(":server"))
    implementation("org.yaml:snakeyaml:2.5")
    // JLine's aggregate classifier already contains its providers/native libraries.
    implementation("org.jline:jline:4.4.7:jdk11") { isTransitive = false }
    // Production uses java.sql and loads the driver at runtime.
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0") { isTransitive = false }
}

/**
 * Documented module graph. Each project validates its exact edges and `implementation`
 * is not re-exported, so javac rejects an undeclared edge too. The :app composition
 * project depends on all six modules and exposes this aggregate verification task.
 */
val moduleProjects = listOf(":core", ":content", ":protocol", ":world", ":gameplay", ":server")
val appDeclaredProjectDependencies: Set<String> =
    configurations.getByName("implementation").dependencies
        .withType(ProjectDependency::class.java)
        .map { it.path }.toSet()
if (appDeclaredProjectDependencies != moduleProjects.toSet())
    throw GradleException(":app must depend on every module; declares $appDeclaredProjectDependencies; expected $moduleProjects")
val checkModuleGraph = tasks.register("checkModuleGraph") {
    group = "verification"
    description = "Verify the extracted projects declare only the documented dependency graph."
    dependsOn(moduleProjects.map { "$it:checkModuleGraph" })
}

tasks.processResources {
    from("VERSION") { rename { "version.txt" } }
    // One editable server template: the deployment's config/server.yml is also the
    // packaged default used to seed a missing configuration.
    from("config/server.yml")
    from(listOf("LICENSE", "NOTICE")) { into("META-INF/tortodrone") }
    from("scripts/compat/WebUiAssetsExport.java") { into("tools") }
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.jar { enabled = false }

// Allow disposable builds beside an existing deployment, without modifying its runtime state.
val publicOutput = layout.projectDirectory.dir(
    providers.gradleProperty("deploymentOutput")
        .orElse(providers.environmentVariable("TORTODRONE_DEPLOYMENT"))
        .getOrElse("dist"))
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
// Validate the documented module graph against packaged bytecode, not only imports:
// javac cannot see a fully-qualified reference that no project edge declares.
val checkBytecodeDependencies = tasks.register("checkBytecodeDependencies") {
    group = "verification"
    description = "Verify the packaged bytecode respects the documented module edges."
    val packaged = serverJar.flatMap { it.archiveFile }
    dependsOn(serverJar)
    inputs.file(packaged)
    doLast {
        val process = ProcessBuilder("jdeps", "--multi-release", "25", "--ignore-missing-deps",
            "-verbose:package", packaged.get().asFile.absolutePath)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val status = process.waitFor()
        if (status != 0) throw GradleException("jdeps failed with exit $status:\n$output")
        val pattern = Regex("""^\s*(org\.tortodrone[\w.]*)\s+->\s+(org\.tortodrone[\w.]*)\s""")
        val edges = output.lineSequence().mapNotNull { line ->
            pattern.find(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }.toSet()
        fun module(name: String): String? = when {
            name == "org.tortodrone.core" || name.startsWith("org.tortodrone.core.") -> "core"
            name == "org.tortodrone.protocol" || name.startsWith("org.tortodrone.protocol.") -> "protocol"
            name == "org.tortodrone.resources" || name.startsWith("org.tortodrone.resources.") -> "content"
            name == "org.tortodrone.world" || name.startsWith("org.tortodrone.world.") -> "world"
            name == "org.tortodrone.account" || name.startsWith("org.tortodrone.account.") ||
                name == "org.tortodrone.character" || name.startsWith("org.tortodrone.character.") ||
                name == "org.tortodrone.storage" || name.startsWith("org.tortodrone.storage.") ||
                name == "org.tortodrone.town" || name.startsWith("org.tortodrone.town.") -> "gameplay"
            name == "org.tortodrone.server" || name.startsWith("org.tortodrone.server.") ||
                name == "org.tortodrone.web" || name.startsWith("org.tortodrone.web.") -> "server"
            else -> null
        }
        val allowed = mapOf(
            "core" to emptySet(),
            "content" to setOf("core"),
            "protocol" to setOf("core"),
            "world" to setOf("content", "core"),
            "gameplay" to setOf("world", "content", "core"),
            "server" to setOf("gameplay", "protocol", "world", "content", "core"))
        val violations = edges.mapNotNull { (from, to) ->
            val source = module(from) ?: return@mapNotNull null
            val target = module(to) ?: return@mapNotNull null
            if (source == target || target in allowed.getValue(source)) null
            else "$source -> $target ($from -> $to)"
        }.toSortedSet()
        if (violations.isNotEmpty())
            throw GradleException("Packaged bytecode has forbidden module edges: $violations")
    }
}
tasks.named("checkModuleGraph") { dependsOn(checkBytecodeDependencies) }


// Compile the existing Java preparation tool and its standalone regression together.
val buildTools = sourceSets.create("buildTools") {
    java.setSrcDirs(listOf("scripts"))
    java.include("build/BuildTools.java", "build/BuildToolsTest.java", "build/ContentAudit.java",
        "build/ContentManifest.java", "build/StockTools.java", "build/ProcessCleanupTest.java",
        "build/CollisionPackExport.java", "build/MapCompiler.java",
        "compat/CheckClientWorld.java", "compat/CheckCombatRendering.java", "compat/ClientCheckHarness.java")
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
    // Never claim ownership of the defaults it fills, but do declare what it reads and
    // writes: without inputs/outputs this ran on every Java task (20s each) even when
    // no content byte had changed. Gradle's output snapshot still notices a hand-edited
    // or missing deployment file, so the "fill missing files" contract holds.
    inputs.dir("content")
    inputs.property("command", command)
    outputs.dir(output)
    outputs.cacheIf { false }
}
val validateContent = tasks.register<JavaExec>("validateContent") {
    group = "verification"
    description = "Validate the selected source content snapshot without touching deployment data."
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("org.tortodrone.resources.ContentManager")
    workingDir = projectDir
    args(projectDir.absolutePath)
    // Content validation reads the source tree and writes nothing, so its result is a
    // stamp: 17s on every Java task was the single largest cost in the gate.
    val stamp = layout.buildDirectory.file("content-validation.stamp")
    inputs.dir("content")
    inputs.dir("config")
    outputs.file(stamp)
    outputs.cacheIf { false }
    doLast {
        val file = stamp.get().asFile
        file.parentFile.mkdirs()
        file.writeText("validated\n")
    }
}
// Fast content guard: fail on exact-duplicate text files that are not accepted in the allowlist.
val checkContentDuplication = tasks.register<JavaExec>("checkContentDuplication") {
    group = "verification"
    description = "Fail on unlisted exact-duplicate content files; see scripts/content/duplicate-allowlist.txt."
    classpath = buildTools.runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("ContentAudit")
    workingDir = projectDir
    args("check")
    // Same treatment as validateContent: this reads the content tree and writes nothing,
    // so an unchanged tree should not pay for it on every Java task.
    val stamp = layout.buildDirectory.file("content-duplication.stamp")
    inputs.dir("content")
    inputs.file("scripts/content/duplicate-allowlist.txt")
    outputs.file(stamp)
    outputs.cacheIf { false }
    doLast {
        val file = stamp.get().asFile
        file.parentFile.mkdirs()
        file.writeText("checked\n")
    }
}
val reportContentDuplication = tasks.register<JavaExec>("reportContentDuplication") {
    group = "verification"
    description = "Report repeated content lines and large identical YAML subtrees; never rewrites files."
    classpath = buildTools.runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("ContentAudit")
    workingDir = projectDir
    args("report")
}
// Maintainer-only: republish content digests after a reviewed content change.
val refreshContentManifest = tasks.register<JavaExec>("refreshContentManifest") {
    group = "build setup"
    description = "Recompute content/_internal/default-source.properties; keep owned.* upgrade history."
    dependsOn(validateContent)
    classpath = buildTools.runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("ContentManifest")
    workingDir = projectDir
}
val prepareContent = preparation("prepareContent", "content").apply { configure {
    dependsOn(validateContent, checkContentDuplication)
} }
preparation("prepareMaps", "maps")
// Regenerate exact collision.bin from the pinned native static collision of every map that has it.
val compileMapsFromSource = tasks.register<JavaExec>("compileMapsFromSource") {
    group = "build setup"
    description = "Compile map-sources/<name>/scene.dat + map.yml into the deployment; development only."
    dependsOn(prepareContent, serverJar)
    classpath = buildTools.runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("MapCompiler")
    workingDir = projectDir
    val gameDirectory = providers.gradleProperty("gameDir").getOrElse("")
    val replace = providers.gradleProperty("replaceMaps").isPresent
    val only = providers.gradleProperty("only").orNull
    args = buildList {
        add(gameDirectory); add("map-sources"); add(publicOutput.asFile.absolutePath)
        if (replace) add("--replace")
        if (only != null) { add("--only"); add(only) }
    }
}
val generateNativeCollision = tasks.register<JavaExec>("generateNativeCollision") {
    group = "build setup"
    description = "Derive static collision and collision.bin from each map's native scene.dat; never changes portals."
    classpath = buildTools.runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("StockTools")
    workingDir = projectDir
    val gameDirectory = providers.gradleProperty("gameDir").getOrElse("-")
    args = listOf("collision", gameDirectory, "content/maps", "--force")
}
val generateCollision = tasks.register<JavaExec>("generateCollision") {
    group = "build setup"
    description = "Compile exact collision.bin from native static-collision.properties; idempotent."
    classpath = buildTools.runtimeClasspath
    javaLauncher.set(launcher25)
    mainClass.set("CollisionPackExport")
    workingDir = projectDir
    args("content/maps")
}
// Rebuild modern packs only from local hash-verified captured binaries. Publication is
// explicit; neither a normal build nor a failed export may restore historical scenes.
val buildWebui = tasks.register<Sync>("buildWebui") {
    // Locally extracted artwork is not a build input and must survive frontend updates.
    preserve { include("assets/game/**", "client-artwork.skipped") }
    val installedManifest = publicOutput.file("webui/assets/game/manifest.json").asFile
    from("frontend") {
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
    dependsOn(checkModuleGraph, serverJar, prepareContent, buildWebui)
}
tasks.assemble { dependsOn(buildServer) }
// Keep the conventional build task focused on deliverables. Regression programs are
// explicit opt-in tasks, so `build` must not pull in test/check dependencies.
tasks.named("build") {
    description = "Assemble the server, WebUI and prepared content; no tests."
    setDependsOn(listOf(tasks.assemble, "spotlessCheck"))
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

// Test fixtures copy content trees; keep their temporary files under the build directory so a
// small tmpfs cannot fail a suite, and `clean` reclaims whatever a failed program leaves behind.
val testTempDirectory = layout.buildDirectory.dir("test-tmp").get().asFile
fun JavaExec.useBuildTemp() {
    val temporary = testTempDirectory
    doFirst { temporary.mkdirs() }
    systemProperty("java.io.tmpdir", temporary.absolutePath)
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
        useBuildTemp()
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
// 268 standalone programs in one JVM is the gate's wall time: a measured full run takes
// 1883 s of program time (31m23s, wall 31m26s on an i5-8250U). `-PunitShard=i/n` slices
// that into separate runners instead of paying the sum on one machine.
// Shards are balanced by *time*, not by count, because the tail is a few simulations:
// ContentMigrationTest (348 s) and SpatialIndexDifferentialTest (319 s) alone are 35% of
// the gate, and the ten slowest programs are 53%. Assigning every fourth program by index
// gives bins of 242/778/322/540 s; packing longest-first into the lightest bin gives four
// bins of 471 s each, which is the floor set by "one program cannot be split".
// Weights come from `scripts/tests/unit-timings.properties` (checked in, so CI and fresh
// checkouts balance without a prior run), overridden by build/unit-timings.properties once
// a local run has recorded fresher numbers. Refresh the checked-in file after re-running
// `./gradlew testUnit`: it is written on every run.
val unitTimings = layout.buildDirectory.file("unit-timings.properties")
fun parseUnitTimings(file: File): Map<String, Long> =
    if (!file.isFile) {
        emptyMap()
    } else {
        file.readLines()
            .mapNotNull { line ->
                val parts = line.split('=')
                if (parts.size == 2) parts[0].trim() to parts[1].trim().toLongOrNull() else null
            }
            .filter { (name, nanos) -> name.isNotEmpty() && nanos != null && nanos > 0 }
            .associate { (name, nanos) -> name to nanos!! }
    }
// Fresh numbers win per program, missing names fall back to the checked-in baseline, so a
// focused run (`-PunitTests=WebUiTest`) records one program without degrading the rest.
val recordedUnitTimings: Map<String, Long> =
    parseUnitTimings(layout.projectDirectory.file("scripts/tests/unit-timings.properties").asFile) +
        parseUnitTimings(unitTimings.get().asFile)
val requestedShard = providers.gradleProperty("unitShard").orNull
val shardSelection = requestedShard?.split('/')?.map { it.trim().toIntOrNull() }?.let { parts ->
    val index = parts.getOrNull(0)
    val count = parts.getOrNull(1)
    if (index == null || count == null || count < 1 || index < 1 || index > count)
        throw GradleException("Use -PunitShard=<1..count>/<count>, for example -PunitShard=2/4")
    index - 1 to count
} ?: (0 to 1)
val testUnit = tasks.register<JavaExec>("testUnit") {
    group = "verification"
    description = "Run standalone unit programs; select classes with -PunitTests=A,B and slice with -PunitShard=i/n."
    usesService(regressionExecution)
    dependsOn(buildServer, tasks.testClasses)
    javaLauncher.set(launcher25)
    classpath = files(serverJar.flatMap { it.archiveFile }, sourceSets.test.get().output)
    mainClass.set("org.tortodrone.support.StandaloneTestSuite")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = projectDir
    useBuildTemp()
    val (shardIndex, shardCount) = shardSelection
    val timings = recordedUnitTimings
    val programs =
        when {
            shardCount == 1 -> selectedUnitPrograms
            timings.isEmpty() ->
                // No timing record yet: fall back to index modulo, which is at least
                // deterministic and loses nothing.
                selectedUnitPrograms.filterIndexed { position, _ -> position % shardCount == shardIndex }
            else -> {
                // Two passes, because a record is always incomplete: a program that has
                // never been timed must not be treated as free, or one bin takes all of
                // them. The untimed ones spread round-robin first; then the timed ones
                // go longest-first into the lightest bin, so the shard walls land within
                // one slow program of each other.
                val bins = List(shardCount) { mutableListOf<String>() }
                val loads = LongArray(shardCount)
                val unknown = selectedUnitPrograms.filter { timings[it] == null }
                val known = selectedUnitPrograms.filter { timings[it] != null }
                unknown.forEachIndexed { position, name -> bins[position % shardCount].add(name) }
                for (name in known.sortedByDescending { timings[it] ?: 0L }) {
                    val lightest = loads.indices.minBy { loads[it] }
                    bins[lightest].add(name)
                    loads[lightest] += timings[name] ?: 0L
                }
                bins[shardIndex].sorted()
            }
        }
    if (programs.isEmpty())
        throw GradleException("Shard ${shardIndex + 1}/$shardCount selects no unit program")
    inputs.property("programs", programs)
    // The shard is reported by the suite itself: a doFirst closure here captured script
    // scope and invalidated the configuration cache.
    systemProperty("unitShard", "${shardIndex + 1}/$shardCount")
    systemProperty("unitPrograms", selectedUnitPrograms.size.toString())
    // Where the suite records durations: only a whole-suite run writes it, so a shard
    // cannot change the selection of another shard.
    systemProperty("unitTimings", unitTimings.get().asFile.absolutePath)
    systemProperty(
        "unitRecordTimings",
        (shardCount == 1 && selectedUnitPrograms.size == unitProgramNames.size).toString()
    )
    args(programs)
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
    useBuildTemp()
}
val testProcessCleanup = tasks.register<JavaExec>("testProcessCleanup") {
    usesService(regressionExecution)
    javaLauncher.set(launcher25)
    classpath = buildTools.runtimeClasspath
    mainClass.set("ProcessCleanupTest")
    workingDir = projectDir
    useBuildTemp()
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
    useBuildTemp()
}
tasks.register<JavaExec>("benchmarkScenes") {
    group = "benchmark"
    description = "Compare scene hot-path CPU/allocation without sockets; optional --args=10000 iterations."
    dependsOn(buildServer, tasks.testClasses)
    javaLauncher.set(launcher25)
    classpath = files(serverJar.flatMap { it.archiveFile }, sourceSets.test.get().output)
    mainClass.set("org.tortodrone.benchmark.SceneBenchmark")
    workingDir = projectDir
    useBuildTemp()
}

tasks.clean {
    delete(serverJar.flatMap { it.archiveFile }, publicOutput.file("tortodrone-admin.jar"))
    delete(publicOutput.file("tortodrone.jar.tmp"), publicOutput.file("tortodrone-admin.jar.tmp"))
    // Preserve runtime data, versioned content, dependencies and unknown files.
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
    from("frontend") { into("webui") }
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

// One Java style for modules, tests and repository tools; palantir-java-format is javac-based, so
// it cannot change program meaning. Frontend assets keep their hand-written style.
spotless {
    java {
        target("modules/*/src/main/java/**/*.java", "src/test/java/**/*.java", "scripts/**/*.java")
        palantirJavaFormat("2.103.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
