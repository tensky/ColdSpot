import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.zip.ZipFile

// The manifest: the JSON the build writes into the APK and the runtime reads on the device, with its codec and
// the rule that joins the modules' manifests. Consumed by the plugin here and by the Android runtime in the
// main build (through included-build substitution of the coordinates below), so it depends on nothing but the
// Kotlin standard library, at the oldest version and language level the runtime is allowed to bring into a
// team's app (DECISIONS.md, "Runtime compatibility"), and `checkRuntimeClasspath` keeps it that way.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    signing
}
apply(from = "../ide-test-events.gradle.kts") // this module's test events for Android Studio, see there

// The one dependency, at the version the runtime is allowed to bring into a team's app.
val STDLIB_VERSION = "2.0.21"

group = "io.github.tensky.coldspot"
version = libs.versions.coldspot.get()

kotlin {
    explicitApi()
    coreLibrariesVersion = STDLIB_VERSION
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// kotlin-stdlib declares org.jetbrains:annotations (a 12 KB jar of annotation types) as a dependency of its own;
// nothing here needs it at run time, and the runtime classpath is to be the standard library alone. Only the
// runtime classpath: the Kotlin compiler's own classpath needs those annotations to generate nullability metadata.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

/** Fails `check` when anything but kotlin-stdlib [STDLIB_VERSION] is on the runtime classpath. */
val checkRuntimeClasspath by tasks.registering {
    group = "verification"
    description = "Fails when the runtime classpath holds anything but kotlin-stdlib $STDLIB_VERSION."
    // Locals, not script properties, so that the configuration cache can store the task; the graph walk is a
    // provider Gradle resolves lazily and serialises by value.
    val expected = listOf("org.jetbrains.kotlin:kotlin-stdlib:$STDLIB_VERSION")
    val components = configurations.runtimeClasspath.get().incoming.resolutionResult.rootComponent.map { root ->
        val seen = LinkedHashSet<String>()
        fun visit(component: ResolvedComponentResult) {
            for (dependency in component.dependencies) {
                if (dependency is ResolvedDependencyResult && seen.add(dependency.selected.id.displayName)) visit(dependency.selected)
            }
        }
        visit(root)
        seen.sorted()
    }
    doLast {
        val actual = components.get()
        check(actual == expected) {
            "the manifest module's runtime classpath must be exactly $expected, but resolves to $actual: it is consumed by the Android runtime and may bring nothing else into a team's app"
        }
    }
}

// ColdSpot's license inside the jar, under META-INF/coldspot/, where no other library puts anything of its own.
tasks.processResources {
    from(layout.settingsDirectory.file("../LICENSE")) { into("META-INF/coldspot") }
}

/** Fails `check` when the jar lacks ColdSpot's LICENSE at META-INF/coldspot/LICENSE, as the repository has it. */
val checkJar by tasks.registering {
    group = "verification"
    description = "Fails when the manifest jar lacks ColdSpot's LICENSE at META-INF/coldspot/LICENSE."
    val jar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jar)
    val license = layout.settingsDirectory.file("../LICENSE")
    inputs.file(license)
    doLast {
        ZipFile(jar.get().asFile).use { zip ->
            val licenses = zip.entries().asSequence().filter { it.name == "META-INF/coldspot/LICENSE" }.toList()
            check(licenses.size == 1 && zip.getInputStream(licenses.single()).readBytes().contentEquals(license.asFile.readBytes())) {
                "the manifest jar must hold ColdSpot's LICENSE once, at META-INF/coldspot/LICENSE, as the repository's: it holds ${licenses.size}"
            }
        }
    }
}

tasks.check {
    dependsOn(checkRuntimeClasspath, checkJar)
}

// io.github.tensky.coldspot:manifest, with its sources and javadoc (see gradle/publishing.gradle.kts).
java {
    withSourcesJar()
    withJavadocJar() // javadoc finds nothing in Kotlin sources: the jar holds a README pointing to the project instead
}
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "manifest"
            pom {
                name.set("ColdSpot Manifest")
                description.set("Manifest model and JSON codec shared by ColdSpot's Gradle plugin and runtime.")
            }
        }
    }
}
apply(from = layout.settingsDirectory.file("../gradle/publishing.gradle.kts"))

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

