// The build-machine side of ColdSpot: everything that runs inside Gradle on the developer's machine.
// Included by the main build through pluginManagement { includeBuild("tooling") }, which is how the
// sample app applies the plugin from source. Nothing here may depend on the main build's projects.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google() // AGP's API, compileOnly for the plugin
    }
    versionCatalogs {
        // One catalog for both builds; the main build reads the same file.
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "tooling"
include(":manifest")
include(":diff")
include(":bundle")
include(":plugin")
