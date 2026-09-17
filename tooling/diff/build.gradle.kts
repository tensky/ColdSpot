plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures` // FixtureRepo and friends, for other modules' tests as well as this one's
}
apply(from = "../ide-test-events.gradle.kts") // this module's test events for Android Studio, see there

kotlin {
    explicitApi()
    // JGit 7 ships Java 17 bytecode, and AGP 9 already requires a 17+ Gradle JVM,
    // so 17 is the floor for anything that will run inside the build.
    jvmToolchain(17)
    // Language and API level 2.2, Gradle 9's embedded Kotlin: whatever Kotlin compiles this, every Gradle 9 can load it.
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

// Committed, but not part of the library: programs for checking the module against a real repository by hand.
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    implementation(libs.jgit)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)

    testFixturesApi(libs.jgit) // FixtureRepo hands out JGit's ObjectId and RevCommit

    // Only so JGit's logging does not greet every run with "No SLF4J providers were found".
    tools.runtimeOnlyConfigurationName("org.slf4j:slf4j-nop:2.0.18")
}

configurations[tools.implementationConfigurationName].extendsFrom(configurations.implementation.get())

tasks.test {
    // JGit reads ~/.gitconfig and the system gitconfig (spawning `git` to find the latter), so a developer's
    // commit.gpgsign, core.hooksPath, core.autocrlf or global excludes would otherwise reach the fixture
    // repos. It also writes ~/.config/jgit. Point all of that at a throwaway home instead.
    val home = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { home.mkdirs() }
    systemProperty("user.home", home.path)
    environment("HOME", home.path) // what JGit consults first on Windows
    environment("XDG_CONFIG_HOME", home.resolve(".config").path)
    environment("GIT_CONFIG_NOSYSTEM", "1")

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * `./gradlew :tooling:diff:printDiff -q [-Pbase=<ref>] [-Pdir=<path>]`. Without `-Pbase` the module chooses the base
 * the way the plugin will (origin/HEAD, origin/main, origin/master). Relative paths are taken from the repository root.
 */
tasks.register<JavaExec>("printDiff") {
    group = "verification"
    description = "Prints the changed lines this module reports for a repository, one file per line."
    mainClass.set("id.tensky.coldspot.diff.tools.PrintDiffKt")
    classpath = tools.runtimeClasspath
    // The repository root is one above this included build.
    val repositoryRoot = rootProject.projectDir.parentFile
    workingDir = repositoryRoot
    val baseRef = providers.gradleProperty("base").orElse("")
    val repoDir = providers.gradleProperty("dir").orElse(repositoryRoot.absolutePath)
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider { listOf(baseRef.get(), repoDir.get()) })
}
