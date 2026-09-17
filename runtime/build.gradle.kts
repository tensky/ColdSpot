import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject

// The on-device side: reads JaCoCo's agent, keeps coverage across builds, analyses the shipped classes. Goes into
// a team's coverage build and nothing else, so it brings exactly what DECISIONS.md "Runtime compatibility" allows:
// kotlin-stdlib 2.0.21 (language and API level 2.0), JaCoCo's agent runtime, and the manifest module. jacoco-core and
// its ASM, the analyser, are inside the AAR, relocated under id.tensky.coldspot.shaded (:jacoco-shaded, 4d). No
// AndroidX, no coroutines, no kotlinx; `checkRuntimeClasspath` and `checkAar` fail the build otherwise.
plugins {
    alias(libs.plugins.android.library) // AGP 9's built-in Kotlin, as the sample uses it
    `maven-publish`
    signing
}

val stdlibVersion = "2.0.21"

// The coordinates the plugin asks for when it adds the runtime to an application's coverage variant, at the
// plugin's own version. While developing, this project takes their place: in a build that includes this one by
// included-build substitution, in the sample app, a project of this same build, by the rule in its build file.
group = "io.github.tensky.coldspot"
version = libs.versions.coldspot.get()

android {
    namespace = "id.tensky.coldspot.runtime"
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        minSdk = 21
    }
    resourcePrefix = "coldspot_"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    explicitApi()
    coreLibrariesVersion = stdlibVersion
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

/**
 * jacoco-core with its ASM, relocated (:jacoco-shaded), resolved from that project's shaded variant alone. The runtime
 * compiles against it and its unit tests run it, and its classes and resources become the library's own (below).
 */
val shadedJacoco: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

/** The relocated jar's classes, or everything else in it, unpacked for the library to take as its own. */
abstract class UnpackShadedJacoco : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: ConfigurableFileCollection

    @get:Input
    abstract val classes: Property<Boolean>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun unpack() {
        val wantClasses = classes.get()
        fileSystem.sync {
            from(archives.zipTree(jar.singleFile)) {
                if (wantClasses) include("**/*.class") else exclude("**/*.class", "META-INF/MANIFEST.MF")
            }
            into(output)
        }
    }
}
val unpackShadedClasses by tasks.registering(UnpackShadedJacoco::class) {
    jar.from(shadedJacoco)
    classes.set(true)
    output.set(layout.buildDirectory.dir("shadedJacoco/classes"))
}
val unpackShadedResources by tasks.registering(UnpackShadedJacoco::class) {
    jar.from(shadedJacoco)
    classes.set(false)
    output.set(layout.buildDirectory.dir("shadedJacoco/resources"))
}

// The relocated classes appended to the library's own, as a transform would add them, and its resources (JaCoCo's
// version bundle, the notices) as a generated folder of the library's Java resources. In the AAR both are in
// classes.jar, and an app that depends on this project gets them as this project's, with no file dependency to resolve
// (an app's build logic need not expect one: Google's oss-licenses plugin cannot store it in its configuration cache).
// Not ScopedArtifact.JAVA_RES for the resources: that reaches the AAR but not an app depending on the project, and
// JaCoCo cannot initialise without its bundle.
androidComponents {
    onVariants { variant ->
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(unpackShadedClasses).toAppend(ScopedArtifact.CLASSES, UnpackShadedJacoco::output)
        checkNotNull(variant.sources.resources) { "the runtime's ${variant.name} has no Java resources" }
            .addGeneratedSourceDirectory(unpackShadedResources, UnpackShadedJacoco::output)
    }
}

dependencies {
    // tooling's :manifest by included-build substitution while developing; io.github.tensky.coldspot:manifest published
    implementation("io.github.tensky.coldspot:manifest:${libs.versions.coldspot.get()}")
    shadedJacoco(project(":jacoco-shaded"))
    // The Analyzer, relocated: compiled against, and the library's own classes by the append above; never a dependency.
    compileOnly(files(shadedJacoco))
    testImplementation(files(shadedJacoco))
    // What the instrumented classes call by name, and what ColdSpot reads by reflection: never relocated, never compiled
    // against. The plugin adds it as well, and forces its version to the instrumenter's.
    runtimeOnly(variantOf(libs.jacoco.agent) { classifier("runtime") })

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.asm) // fixture classes assembled with exact line tables
}

// The unit tests stand in for the agent (FakeRt), and check what a build without one looks like: the real agent, a
// dependency of this library, stays off their classpath.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.jacoco", module = "org.jacoco.agent")
}

/**
 * The allowlist: what a consumer's runtime classpath gets from this module. Fails `check` on anything else: a
 * transitive AndroidX, a kotlinx library, a second JaCoCo.
 */
val checkRuntimeClasspath by tasks.registering {
    group = "verification"
    description = "Fails when the library's runtime classpath holds anything beyond kotlin-stdlib $stdlibVersion, JaCoCo's agent runtime, and the manifest module."
    // Locals, not script properties, so that the configuration cache can store the task.
    val stdlib = stdlibVersion
    val jacoco = libs.versions.jacoco.get()
    val allowed = mapOf(
        "org.jetbrains.kotlin:kotlin-stdlib" to stdlib,
        "org.jetbrains:annotations" to null, // kotlin-stdlib's own declared dependency
        "org.jacoco:org.jacoco.agent" to jacoco,
        "project :tooling:manifest" to null,
    )
    val components = configurations.named("releaseRuntimeClasspath").get().incoming.resolutionResult.rootComponent.map { root ->
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
        val problems = actual.mapNotNull { id ->
            val module = id.substringBeforeLast(':').takeUnless { id.startsWith("project ") } ?: id
            val version = id.substringAfterLast(':')
            when {
                module !in allowed -> "$id is not allowed"
                allowed[module] != null && allowed[module] != version -> "$id must be version ${allowed[module]}"
                else -> null
            }
        }
        check(problems.isEmpty()) {
            "the runtime's classpath must hold only kotlin-stdlib $stdlib, JaCoCo's agent runtime $jacoco, and the manifest module; it resolves to $actual:\n  ${problems.joinToString("\n  ")}"
        }
        check(actual.any { it.startsWith("org.jacoco:org.jacoco.agent:") } && actual.any { it.startsWith("org.jetbrains.kotlin:kotlin-stdlib:") } && "project :tooling:manifest" in actual) {
            "the runtime's classpath lost one of its own dependencies: $actual"
        }
    }
}

/**
 * Fails `check` when the release AAR holds a jacoco-core or ASM class outside id/tensky/coldspot/shaded (in its own
 * classes.jar or in a local jar under libs/), or lacks the relocated analyser or the third-party notices.
 */
val checkAar by tasks.registering {
    group = "verification"
    description = "Fails when the release AAR holds jacoco-core or ASM unrelocated, or lacks the relocated copy or its notices."
    val aar = layout.buildDirectory.file("outputs/aar/runtime-release.aar")
    dependsOn("bundleReleaseAar")
    inputs.file(aar)
    doLast {
        val names = ArrayList<String>()
        ZipFile(aar.get().asFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name == "classes.jar" || (entry.name.startsWith("libs/") && entry.name.endsWith(".jar"))) {
                    ZipInputStream(zip.getInputStream(entry)).use { jar ->
                        generateSequence { jar.nextEntry }.forEach { names += "${entry.name}!/${it.name}" }
                    }
                } else {
                    names += entry.name
                }
            }
        }
        val unrelocated = names.filter { name -> name.substringAfter("!/").let { it.startsWith("org/jacoco/core/") || it.startsWith("org/objectweb/asm/") } }
        check(unrelocated.isEmpty()) { "the AAR holds jacoco-core or ASM outside id/tensky/coldspot/shaded: ${unrelocated.take(10)}" }
        check(names.any { it.endsWith("!/id/tensky/coldspot/shaded/org/jacoco/core/analysis/Analyzer.class") }) { "the AAR lacks the relocated Analyzer: ${names.filter { "libs/" in it }.take(5)}" }
        check(names.any { it.endsWith("!/id/tensky/coldspot/shaded/org/jacoco/core/jacoco.properties") }) { "the AAR lacks JaCoCo's relocated version resource" }
        check(names.any { it.endsWith("!/META-INF/coldspot/THIRD-PARTY-NOTICES.txt") }) { "the AAR lacks META-INF/coldspot/THIRD-PARTY-NOTICES.txt" }
    }
}

tasks.named("check") {
    dependsOn(checkRuntimeClasspath, checkAar)
}

// io.github.tensky.coldspot:runtime, the release AAR with its sources and javadoc (see gradle/publishing.gradle.kts).
android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "runtime"
                pom {
                    name.set("ColdSpot runtime")
                    description.set("The on-device side of ColdSpot: reads JaCoCo's agent, keeps coverage across builds, and shows which changed lines executed.")
                }
            }
        }
    }
}
apply(from = layout.settingsDirectory.file("gradle/publishing.gradle.kts"))

tasks.withType<Test>().configureEach {
    // The JaCoCo the relocated copy must say it is.
    systemProperty("coldspot.test.jacocoVersion", libs.versions.jacoco.get())
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
