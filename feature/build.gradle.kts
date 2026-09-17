plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("io.github.tensky.coldspot") // from the tooling build, see settings.gradle.kts
}

android {
    namespace = "id.tensky.coldspot.feature"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview) // @Preview, for the multipreview the app uses
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

// ---- States a healthy build never has, for the device checks and the screenshots: -Pcoldspot.sample=<state> ----
//   stale      the bytes that ship for the feature module's Stale class are not the bytes that run
//   mismatch   the manifest names another JaCoCo than the one in the app
//   empty      nothing changed: no file in any manifest, no class shipped
// Done to the bundle once ColdSpot's task has written it, which is the sample faking what ColdSpot itself never writes.
// missing-at-startup and missing-in-analysis are the app's to do (app/build.gradle.kts).
val sampleState = providers.gradleProperty("coldspot.sample").orElse("")
tasks.withType<id.tensky.coldspot.plugin.ColdSpotBundleTask>().configureEach {
    val state = sampleState
    inputs.property("coldspot.sample", state)
    doLast {
        val coldspot =
            (this as id.tensky.coldspot.plugin.ColdSpotBundleTask).outputDir.get().asFile.resolve("coldspot")
        val codec = id.tensky.coldspot.manifest.ManifestJson
        when (state.get()) {
            "" -> Unit
            "stale" -> coldspot.walkTopDown().filter { it.name == "Stale.class" }.forEach { file ->
                // same length, another constant: a class of the same name and shape with another id
                val bytes = file.readBytes()
                val marker = "stale-marker-A".toByteArray()
                val at =
                    (0..bytes.size - marker.size).first { i -> marker.indices.all { bytes[i + it] == marker[it] } }
                bytes[at + marker.size - 1] = 'B'.code.toByte()
                file.writeBytes(bytes)
            }

            "mismatch" -> coldspot.resolve("manifest.json").takeIf { it.isFile }?.let { file ->
                val manifest = codec.read(file.readText())
                file.writeText(codec.write(manifest.copy(jacoco = manifest.jacoco.copy(build = "0.0.0.another"))))
            }

            "empty" -> coldspot.walkTopDown().filter { it.name == "manifest.json" }.toList()
                .forEach { file ->
                    if (file.parentFile == coldspot) {
                        val manifest = codec.read(file.readText())
                        file.writeText(
                            codec.write(
                                manifest.copy(
                                    files = emptyList(),
                                    excluded = emptyList()
                                )
                            )
                        )
                    } else {
                        file.writeText(
                            codec.write(
                                codec.readModule(file.readText()).copy(files = emptyList())
                            )
                        )
                        file.parentFile.resolve("classes").deleteRecursively()
                    }
                }

            "missing-at-startup", "missing-in-analysis" -> Unit
            else -> throw GradleException("-Pcoldspot.sample=${state.get()}: one of stale, mismatch, empty, missing-at-startup, missing-in-analysis")
        }
    }
}
