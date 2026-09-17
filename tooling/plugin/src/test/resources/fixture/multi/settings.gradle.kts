pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // ColdSpot's runtime, which the plugin adds to the application's coverage variant: the tests' stand-in (RuntimeStub).
        maven {
            url = uri("@RUNTIME_REPO@")
            content { includeGroup("io.github.tensky.coldspot") }
        }
    }
}
rootProject.name = "multi"
include(":app")
include(":lib")
include(":plain")
