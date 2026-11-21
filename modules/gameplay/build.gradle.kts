plugins { java }

repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":world"))
    implementation(project(":content"))
    implementation(project(":core"))
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0") { isTransitive = false }
}

// Documented edge for this project. `implementation` is not re-exported, so javac
// rejects an undeclared edge too; this verifies the declared graph at configuration
// time and keeps the check visible as a task in every build.
val expectedProjectDependencies: Set<String> = setOf(":world", ":content", ":core")
val declaredProjectDependencies: Set<String> =
    configurations.getByName("implementation").dependencies
        .withType(org.gradle.api.artifacts.ProjectDependency::class.java)
        .map { it.path }.toSet()
if (declaredProjectDependencies != expectedProjectDependencies)
    throw GradleException(":gameplay declares $declaredProjectDependencies; expected $expectedProjectDependencies")
tasks.register("checkModuleGraph") {
    group = "verification"
    description = "Verify this project declares only its documented project dependencies."
}
