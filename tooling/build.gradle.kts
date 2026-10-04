plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

// Task-name matching stops at the boundary of an included build, so `./gradlew test` at the root would
// not reach the modules here. These lifecycle tasks gather every module's, for the main build to hook into.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests of every tooling module."
    dependsOn(subprojects.map { "${it.path}:test" })
}
tasks.register("check") {
    group = "verification"
    description = "Runs the checks of every tooling module."
    dependsOn(subprojects.map { "${it.path}:check" })
}
tasks.register("slowTest") {
    group = "verification"
    description = "Runs the tests too slow for `test`; see the plugin module."
    dependsOn(":plugin:slowTest")
}

// This build's part of the Maven Central bundle (gradle/publishing.gradle.kts, testbeds/central-bundle.sh): the plugin,
// its marker and the manifest, signed. The main build's own publishToCentralStaging stages the runtime.
tasks.register("publishToCentralStaging") {
    group = "publishing"
    description = "Publishes the plugin, its marker and the manifest, signed, to <repo root>/build/central-staging."
    dependsOn(":plugin:publishAllPublicationsToCentralStagingRepository", ":manifest:publishAllPublicationsToCentralStagingRepository")
}
