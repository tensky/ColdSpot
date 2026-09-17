plugins {
    alias(libs.plugins.kotlin.jvm)
}
apply(from = "../ide-test-events.gradle.kts") // this module's test events for Android Studio, see there

kotlin {
    explicitApi()
    // Same floor as diff: JGit 7 and AGP 9 both need 17, and this runs inside the build.
    jvmToolchain(17)
    // Language and API level 2.2, Gradle 9's embedded Kotlin: whatever Kotlin compiles this, every Gradle 9 can load it.
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

// Committed, but not part of the library: a program for running the module against a real repository by hand.
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    api(project(":manifest")) // the manifests this module writes, and their codec
    api(project(":diff")) // RepositoryChanges is built from what diff reports
    implementation(libs.asm)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":diff")))

    // Only so JGit's logging does not greet every run with "No SLF4J providers were found".
    tools.runtimeOnlyConfigurationName("org.slf4j:slf4j-nop:2.0.18")
}

configurations[tools.implementationConfigurationName].extendsFrom(configurations.implementation.get())

tasks.test {
    // As in diff: keep the developer's own git and JGit configuration out of the fixture repositories.
    val home = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { home.mkdirs() }
    systemProperty("user.home", home.path)
    environment("HOME", home.path)
    environment("XDG_CONFIG_HOME", home.resolve(".config").path)
    environment("GIT_CONFIG_NOSYSTEM", "1")

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * `./gradlew :tooling:bundle:printBundle -q -Pclasses=<dir>[,<dir>] [-Pclasspath=<jar or dir>[,...]] [-Pdir=<repo>] [-Pbase=<ref>] [-Pout=<dir>]`
 * Defaults: this repository, the base chosen the way the plugin will choose it, `tooling/bundle/build/printBundle`.
 * Relative paths are taken from the repository root, not from this module's directory.
 */
tasks.register<JavaExec>("printBundle") {
    group = "verification"
    description = "Builds a bundle for a repository by hand and prints what went into it."
    mainClass.set("id.tensky.coldspot.bundle.tools.PrintBundleKt")
    classpath = tools.runtimeClasspath
    // The repository root is one above this included build.
    val repositoryRoot = rootProject.projectDir.parentFile
    workingDir = repositoryRoot
    val repoDir = providers.gradleProperty("dir").orElse(repositoryRoot.absolutePath)
    val baseRef = providers.gradleProperty("base").orElse("")
    val classDirs = providers.gradleProperty("classes").orElse("")
    val outDir = providers.gradleProperty("out").orElse(layout.buildDirectory.dir("printBundle").get().asFile.absolutePath)
    val classpath = providers.gradleProperty("classpath").orElse("")
    argumentProviders.add(
        org.gradle.process.CommandLineArgumentProvider { listOf(repoDir.get(), baseRef.get(), classDirs.get(), outDir.get(), classpath.get()) },
    )
}
