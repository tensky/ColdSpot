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
