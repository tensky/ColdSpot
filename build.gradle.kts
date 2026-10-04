// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// `./gradlew test` and `./gradlew check` match tasks by name in this build only; the tooling build's
// own aggregate tasks bring its modules along.
val tooling = gradle.includedBuild("tooling")
tasks.register("test") {
    group = "verification"
    description = "Runs the tooling build's tests as well as this build's."
    dependsOn(tooling.task(":test"))
}
tasks.register("check") {
    group = "verification"
    description = "Runs the tooling build's checks as well as this build's."
    dependsOn(tooling.task(":check"))
}
tasks.register("slowTest") {
    group = "verification"
    description = "Runs the tooling build's slow tests, which `test` and `check` leave out."
    dependsOn(tooling.task(":slowTest"))
}

// This build's part of the Maven Central bundle (gradle/publishing.gradle.kts, testbeds/central-bundle.sh): the runtime,
// signed. The tooling build's own publishToCentralStaging stages the rest.
tasks.register("publishToCentralStaging") {
    group = "publishing"
    description = "Publishes the runtime, signed, to <repo root>/build/central-staging for the Maven Central bundle."
    dependsOn(":runtime:publishAllPublicationsToCentralStagingRepository")
}