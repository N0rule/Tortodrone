plugins { java }

repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":gameplay"))
    implementation(project(":protocol"))
    implementation(project(":world"))
    implementation(project(":content"))
    implementation(project(":core"))
    implementation("org.jline:jline:4.4.7:jdk11") { isTransitive = false }
}

// Documented edge for this project. `implementation` is not re-exported, so javac
// rejects an undeclared edge too; this verifies the declared graph at configuration
// time and keeps the check visible as a task in every build.
val expectedProjectDependencies: Set<String> = setOf(":gameplay", ":protocol", ":world", ":content", ":core")
val declaredProjectDependencies: Set<String> =
    configurations.getByName("implementation").dependencies
        .withType(org.gradle.api.artifacts.ProjectDependency::class.java)
        .map { it.path }.toSet()
if (declaredProjectDependencies != expectedProjectDependencies)
    throw GradleException(":server declares $declaredProjectDependencies; expected $expectedProjectDependencies")
val checkServerWebBoundary = tasks.register("checkServerWebBoundary") {
    group = "verification"
    description = "Verify the server package depends on the WebUI only through its server-owned seam."
    val sources = layout.projectDirectory.dir("src/main/java/org/tortodrone/server")
    inputs.dir(sources)
    doLast {
        val offenders = sources.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { it.readText().contains("org.tortodrone.web") }
            .map { it.relativeTo(sources.asFile).path }
            .sorted().toList()
        if (offenders.isNotEmpty())
            throw GradleException(":server must not reference org.tortodrone.web directly: $offenders")
    }
}
tasks.register("checkModuleGraph") {
    group = "verification"
    description = "Verify this project declares only its documented project dependencies."
    dependsOn(checkServerWebBoundary)
}
