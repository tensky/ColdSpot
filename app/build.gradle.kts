import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("io.github.tensky.coldspot") // from the tooling build, see settings.gradle.kts
}

android {
    namespace = "id.tensky.coldspot"
    compileSdk {
        // 37: androidx.core 1.19 and lifecycle 2.11 refuse anything older (checkDebugAarMetadata).
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "id.tensky.coldspot"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// ColdSpot's plugin adds its runtime to the coverage variant by coordinates, io.github.tensky.coldspot:runtime. Here
// the runtime is a project of this very build, and Gradle puts projects in the place of coordinates only across
// included builds, never inside one build (tried: the dependency fails to resolve). So this build says where it is;
// a build that includes this one needs no such rule, and a team's build resolves the coordinates from a repository.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.tensky.coldspot:runtime")).using(project(":runtime"))
    }
}

dependencies {
    implementation(project(":feature"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ---- States a healthy build never has, for the device checks and the screenshots: -Pcoldspot.sample=<state> ----
//   stale      the bytes that ship for the feature module's Stale class are not the bytes that run
//   mismatch   the manifest names another JaCoCo than the one in the app
//   empty      nothing changed: no file in any manifest, no class shipped
// Done to the bundle once ColdSpot's task has written it, which is the sample faking what ColdSpot itself never writes.
//   missing-at-startup    ColdSpot's runtime without a class it needs to start (CoverageStore, where Runtime refers
//                         to it): a NoClassDefFoundError while the app starts, as a build that lost part of ColdSpot
//                         throws. Without a bubble, ColdSpot's screen is reached by its launcher icon, on in this state.
//   missing-in-analysis   the same for a class the analysis needs (the relocated JaCoCo's Analyzer, where Analysis.kt
//                         refers to it): a NoClassDefFoundError on ColdSpot's background thread, as it reads the
//                         build's manifests at startup, and at every analysis.
// Done to the runtime's classes as the app is built (MissingClass): ColdSpot's own code is not changed for them.
val sampleState = providers.gradleProperty("coldspot.sample").orElse("")
val missingClasses = mapOf(
    "missing-at-startup" to ("id.tensky.coldspot.runtime.Runtime" to "id/tensky/coldspot/runtime/CoverageStore"),
    "missing-in-analysis" to ("id.tensky.coldspot.runtime.AnalysisKt" to "id/tensky/coldspot/shaded/org/jacoco/core/analysis/Analyzer"),
)

/** In one class, every reference to [Params.missing] made a reference to a class that is nowhere: `<missing>$Missing`. */
abstract class MissingClass : AsmClassVisitorFactory<MissingClass.Params> {
    interface Params : InstrumentationParameters {
        /** The class whose references change, as `id.tensky.Foo`. */
        @get:Input
        val inClass: Property<String>

        /** The class it can no longer find, as `id/tensky/Bar`. */
        @get:Input
        val missing: Property<String>
    }

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val missing = parameters.get().missing.get()
        return ClassRemapper(nextClassVisitor, SimpleRemapper(instrumentationContext.apiVersion.get(), missing, "$missing\$Missing"))
    }

    override fun isInstrumentable(classData: ClassData): Boolean = classData.className == parameters.get().inClass.get()
}

androidComponents {
    onVariants(selector().withBuildType("coverage")) { variant ->
        val state = sampleState.get()
        if (state.startsWith("missing-")) {
            val (inClass, missing) = missingClasses[state] ?: throw GradleException("-Pcoldspot.sample=$state: one of stale, mismatch, empty, ${missingClasses.keys.joinToString()}")
            // ALL: the runtime is a dependency of the app, not its own classes
            variant.instrumentation.transformClassesWith(MissingClass::class.java, InstrumentationScope.ALL) { params ->
                params.inClass.set(inClass)
                params.missing.set(missing)
            }
        }
    }
}
coldSpot {
    launcherIcon = sampleState.map { it == "missing-at-startup" }
}
tasks.withType<id.tensky.coldspot.plugin.ColdSpotBundleTask>().configureEach {
    val state = sampleState
    val missingStates = missingClasses.keys.toList() // copies: the action must not capture the script
    inputs.property("coldspot.sample", state)
    doLast {
        val coldspot = (this as id.tensky.coldspot.plugin.ColdSpotBundleTask).outputDir.get().asFile.resolve("coldspot")
        val codec = id.tensky.coldspot.manifest.ManifestJson
        when (state.get()) {
            "" -> Unit
            "stale" -> coldspot.walkTopDown().filter { it.name == "Stale.class" }.forEach { file ->
                // same length, another constant: a class of the same name and shape with another id
                val bytes = file.readBytes()
                val marker = "stale-marker-A".toByteArray()
                val at = (0..bytes.size - marker.size).first { i -> marker.indices.all { bytes[i + it] == marker[it] } }
                bytes[at + marker.size - 1] = 'B'.code.toByte()
                file.writeBytes(bytes)
            }
            "mismatch" -> coldspot.resolve("manifest.json").takeIf { it.isFile }?.let { file ->
                val manifest = codec.read(file.readText())
                file.writeText(codec.write(manifest.copy(jacoco = manifest.jacoco.copy(build = "0.0.0.another"))))
            }
            "empty" -> coldspot.walkTopDown().filter { it.name == "manifest.json" }.toList().forEach { file ->
                if (file.parentFile == coldspot) {
                    val manifest = codec.read(file.readText())
                    file.writeText(codec.write(manifest.copy(files = emptyList(), excluded = emptyList())))
                } else {
                    file.writeText(codec.write(codec.readModule(file.readText()).copy(files = emptyList())))
                    file.parentFile.resolve("classes").deleteRecursively()
                }
            }
            in missingStates -> Unit // done to the runtime's classes (MissingClass)
            else -> throw GradleException("-Pcoldspot.sample=${state.get()}: one of stale, mismatch, empty, ${missingStates.joinToString()}")
        }
    }
}
