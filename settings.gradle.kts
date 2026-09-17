pluginManagement {
    // The build-machine side (diff, bundle, plugin) is its own build, so that the sample app can apply the
    // plugin from source: an included build cannot depend on this build's projects, but this build can use
    // its plugins and libraries.
    includeBuild("tooling")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The tooling build once more, outside pluginManagement: that makes its libraries substitutable, so that the
// runtime can depend on io.github.tensky.coldspot:manifest and get tooling's :manifest project.
includeBuild("tooling")

rootProject.name = "ColdSpot"
include(":app")
include(":feature")
include(":runtime")
// jacoco-core and ASM relocated, as the runtime compiles against them and packs them into its AAR (4d).
include(":jacoco-shaded")
 