import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
    signing
    alias(libs.plugins.shadow)
}
apply(from = "../ide-test-events.gradle.kts") // this module's test events for Android Studio, see there

// io.github.tensky.coldspot:plugin, with the marker io.github.tensky.coldspot:io.github.tensky.coldspot.gradle.plugin.
group = "io.github.tensky.coldspot"
// ColdSpot's one version: the plugin adds the runtime to the app at exactly this version.
version = libs.versions.coldspot.get()

kotlin {
    explicitApi()
    // Same floor as diff and bundle: this runs inside a Gradle JVM that AGP 9 already requires to be 17+.
    jvmToolchain(17)
    // Language and API level 2.2, Gradle 9's embedded Kotlin: whatever Kotlin compiles this, every Gradle 9 can load it.
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

// The version again, as a resource the plugin reads at run time: under TestKit and in an included build it is
// loaded from class directories, where no jar manifest could tell it. With it, the Maven version of the jacoco-core
// the plugin instruments with, which its shaded jar no longer carries a pom.properties for.
val generateVersionResource by tasks.registering {
    description = "Writes the plugin's version, and its JaCoCo's, into a resource for the plugin to read."
    val version = project.version.toString()
    val jacoco = libs.versions.jacoco.get()
    val dir = layout.buildDirectory.dir("generated/coldspotVersion")
    inputs.property("version", version)
    inputs.property("jacoco", jacoco)
    outputs.dir(dir)
    doLast {
        val file = dir.get().file("id/tensky/coldspot/plugin/version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$version\njacoco=$jacoco\n")
    }
}
sourceSets.main {
    resources.srcDir(generateVersionResource)
}

// The plugin's description, the same in its declaration and in its POM.
val pluginDescription =
    "Diffs your branch against a base, instruments the changed classes and ships them for ColdSpot's in-app view of executed lines."

gradlePlugin {
    plugins {
        create("coldspot") {
            id = "io.github.tensky.coldspot"
            implementationClass = "id.tensky.coldspot.plugin.ColdSpotPlugin"
            displayName = "ColdSpot"
            description = pluginDescription
        }
    }
}

/**
 * TestKit loads the plugin under test above the fixture's script classloader, which is where AGP would be if
 * the fixture requested it by version, and a plugin cannot see classes loaded below it. So AGP rides along on
 * the plugin classpath and the fixture applies `com.android.application` from there, which puts both in one
 * classloader, as a real build's `plugins {}` block does.
 */
val testKitAgp: Configuration by configurations.creating

// Committed, but not part of the plugin: a program for checking a device run against the laptop by hand.
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    implementation(project(":diff"))
    implementation(project(":bundle"))
    // The instrumenter itself, at the version the coverage variant's agent is forced to; shaded, see below.
    implementation(libs.jacoco.core)
    // jacoco-core's ASM held to one version, asm's: the one bundle uses, and the one the runtime analyses with.
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    // The plugin only reacts to com.android.application when the build applies it, so AGP's API is compile-only:
    // at run time the applying build's own AGP is on the classpath.
    compileOnly(libs.android.gradle.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
    testImplementation(testFixtures(project(":diff"))) // FixtureRepo: git repositories for the TestKit fixtures
    testKitAgp(libs.android.gradle)
    // For the Hilt fixture: AGP's built-in Kotlin, KSP and Hilt's plugin, in the one classloader with AGP as a
    // build's plugins {} block would have them. Test-time only; the plugin itself depends on none of them.
    testKitAgp(libs.kotlin.gradle.plugin)
    testKitAgp(libs.ksp.gradle.plugin)
    testKitAgp(libs.hilt.gradle.plugin)
}

tasks.pluginUnderTestMetadata {
    // The plugin as a build gets it, the shaded jar (see Shading below), with AGP next to it.
    pluginClasspath.setFrom(tasks.shadowJar, testKitAgp)
}

configurations[tools.implementationConfigurationName].extendsFrom(configurations.implementation.get())

/** `./gradlew :tooling:plugin:checkDeviceRun -q -Plogcat=<dump> -Papk=<apk>`: the device's analysis, redone here and diffed. */
tasks.register<JavaExec>("checkDeviceRun") {
    group = "verification"
    description = "Re-runs a device's JaCoCo analysis on the laptop from a Logcat dump and an APK, and diffs the two."
    mainClass.set("id.tensky.coldspot.plugin.tools.CheckDeviceRunKt")
    classpath = tools.runtimeClasspath
    workingDir = rootProject.projectDir.parentFile
    val logcat = providers.gradleProperty("logcat").orElse("")
    val apk = providers.gradleProperty("apk").orElse("")
    argumentProviders.add(org.gradle.process.CommandLineArgumentProvider { listOf(logcat.get(), apk.get()) })
}

tasks.withType<Test>().configureEach {
    // The TestKit fixtures are Android projects, which need an SDK: ANDROID_HOME, or sdk.dir from the repository's
    // local.properties. The tests skip themselves, saying so, when neither is there.
    val localProperties = rootProject.projectDir.parentFile.resolve("local.properties")
    val sdkDir = providers.environmentVariable("ANDROID_HOME").orElse(
        providers.fileContents(layout.projectDirectory.file(localProperties.absolutePath)).asText.map { text ->
            Properties().apply { load(text.reader()) }.getProperty("sdk.dir") ?: ""
        },
    ).orElse("")
    systemProperty("coldspot.test.sdkDir", sdkDir.get())
    // The Hilt fixture's runtime and compiler, at the version of the plugin on the TestKit classpath.
    systemProperty("coldspot.test.hiltVersion", libs.versions.hilt.get())
    // The JaCoCo the plugin instruments with, for the tests to expect on classpaths and in manifests.
    systemProperty("coldspot.test.jacocoVersion", libs.versions.jacoco.get())
    // The runtime the plugin adds to an application: its version, and its manifest and resources, which live in the
    // main build, out of this build's reach as a dependency. The tests pack them into a stand-in AAR (RuntimeStub),
    // so they are inputs here: a change to the runtime's manifest runs the plugin's tests again.
    val runtimeMain = rootProject.projectDir.parentFile.resolve("runtime/src/main")
    systemProperty("coldspot.test.version", libs.versions.coldspot.get())
    systemProperty("coldspot.test.runtimeMain", runtimeMain.absolutePath)
    inputs.files(fileTree(runtimeMain) { include("AndroidManifest.xml", "res/**") }).withPropertyName("runtimeManifestAndResources").withPathSensitivity(PathSensitivity.RELATIVE)

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// Tests in the SlowTest category (the Hilt fixture: Kotlin, KSP and Hilt under TestKit, minutes rather than
// seconds) stay out of `test` and therefore of `check`; `slowTest` runs them, and testbeds/release-check.sh calls it.
val slowTestCategory = "id.tensky.coldspot.plugin.SlowTest"

tasks.test {
    useJUnit { excludeCategories(slowTestCategory) }
}

tasks.register<Test>("slowTest") {
    group = "verification"
    description = "Runs the tests too slow for `test`: the Hilt fixture under TestKit."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnit { includeCategories(slowTestCategory) }
}

// ---- Shading and publishing (4d) --------------------------------------------------------------------------------
// JGit with what it pulls in (JavaEWAH, commons-codec), ASM and jacoco-core go into the plugin's jar relocated under
// id.tensky.coldspot.shaded, so that they never meet another copy on a build's classpath: AGP brings its own ASM,
// other plugins their own JGit. What Gradle gives every plugin stays out: kotlin-stdlib, which the plugin compiles
// against at API level 2.2, and slf4j, through which JGit logs into Gradle's log. diff, bundle and manifest,
// ColdSpot's own, go in as they are.
val shadedPrefix = "id.tensky.coldspot.shaded"
val relocated = listOf("org.eclipse.jgit", "com.googlecode.javaewah", "org.apache.commons.codec", "org.objectweb.asm", "org.jacoco.core")

tasks.shadowJar {
    archiveClassifier.set("")
    for (pkg in relocated) relocate(pkg, "$shadedPrefix.$pkg")
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("org.jetbrains:annotations"))
        exclude(dependency("org.slf4j:.*"))
    }
    mergeServiceFiles()
    // The signatures of jars taken apart (JGit's), module descriptors, Maven metadata, and the libraries' own license
    // files: META-INF/coldspot/THIRD-PARTY-NOTICES.txt covers every one of them instead.
    exclude(
        "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC",
        "module-info.class", "META-INF/versions/*/module-info.class", "META-INF/maven/**",
        "META-INF/LICENSE*", "META-INF/NOTICE*", "about.html", "OSGI-INF/**",
    )
}
// The plain jar is only a step towards the shaded one; its own name keeps the two apart.
tasks.jar {
    archiveClassifier.set("plain")
}
// ColdSpot's license inside the jar, under META-INF/coldspot/, where no other library puts anything of its own.
tasks.processResources {
    from(layout.settingsDirectory.file("../LICENSE")) { into("META-INF/coldspot") }
}

java {
    withSourcesJar()
    withJavadocJar() // javadoc finds nothing in Kotlin sources: the jar holds a README pointing to the project instead
}
tasks.named<Jar>("sourcesJar") {
    // Everything of ColdSpot's own that is in the shaded jar: diff, bundle and manifest too.
    for (module in listOf("diff", "bundle", "manifest")) from(layout.projectDirectory.dir("../$module/src/main/kotlin"))
}

// The java component publishes Shadow's variant alone, the shaded jar with no dependencies; its plain variants would
// list every library that jar already holds. java-gradle-plugin publishes it as pluginMaven, with the plugin marker.
(components["java"] as AdhocComponentWithVariants).apply {
    withVariantsFromConfiguration(configurations.apiElements.get()) { skip() }
    withVariantsFromConfiguration(configurations.runtimeElements.get()) { skip() }
}
publishing.publications.withType<MavenPublication>().matching { it.name == "pluginMaven" }.configureEach {
    pom {
        name.set("ColdSpot Gradle Plugin")
        description.set(pluginDescription)
    }
}
apply(from = layout.settingsDirectory.file("../gradle/publishing.gradle.kts"))

/** Fails `check` when THIRD-PARTY-NOTICES.txt does not name every library the shaded jar holds, at its version. */
val checkNotices by tasks.registering {
    group = "verification"
    description = "Fails when the plugin's third-party notices miss a library its shaded jar holds."
    val notices = layout.projectDirectory.file("src/main/resources/META-INF/coldspot/THIRD-PARTY-NOTICES.txt")
    inputs.file(notices)
    val shaded = configurations.runtimeClasspath.get().incoming.resolutionResult.rootComponent.map { root ->
        val seen = LinkedHashSet<String>()
        fun visit(component: ResolvedComponentResult) {
            for (dependency in component.dependencies) {
                if (dependency is ResolvedDependencyResult && seen.add(dependency.selected.id.displayName)) visit(dependency.selected)
            }
        }
        visit(root)
        seen.filter { !it.startsWith("project ") && !it.startsWith("org.jetbrains") && !it.startsWith("org.slf4j:") }.sorted()
    }
    val jacoco = libs.versions.jacoco.get()
    inputs.property("jacoco", jacoco)
    doLast {
        val text = notices.asFile.readText()
        val missing = shaded.get().filter { it !in text }
        check(missing.isEmpty()) { "THIRD-PARTY-NOTICES.txt does not name ${missing.joinToString()}: the shaded jar holds them" }
        // jacoco-core is EPL-2.0: distributed in object code, its entry says where its source is, and that it was relocated.
        val entry = text.split(Regex("\n\\s*\n")).firstOrNull { "org.jacoco:org.jacoco.core:$jacoco" in it }.orEmpty().replace(Regex("\\s+"), " ")
        val unsaid = listOf(
            "Its source code is available under the EPL-2.0",
            "https://github.com/jacoco/jacoco/tree/v$jacoco",
            "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.core/$jacoco/org.jacoco.core-$jacoco-sources.jar",
            "relocated under id.tensky.coldspot.shaded",
        ).filter { it !in entry }
        check(unsaid.isEmpty()) {
            "THIRD-PARTY-NOTICES.txt: the entry of jacoco-core $jacoco (EPL-2.0) must say where its source is and that its classes are relocated; it lacks: ${unsaid.joinToString(" | ")}"
        }
    }
}

/** Fails `check` when the shaded jar holds a class outside id/tensky/coldspot, or lacks its notices. */
val checkShadedJar by tasks.registering {
    group = "verification"
    description = "Fails when the plugin's shaded jar holds a class that is neither ColdSpot's nor relocated under $shadedPrefix."
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jar)
    val license = layout.settingsDirectory.file("../LICENSE")
    inputs.file(license)
    doLast {
        ZipFile(jar.get().asFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val foreign = names.filter { it.endsWith(".class") && !it.startsWith("id/tensky/coldspot/") }
            check(foreign.isEmpty()) { "the shaded jar holds classes outside id/tensky/coldspot: ${foreign.take(10)}" }
            check("META-INF/coldspot/THIRD-PARTY-NOTICES.txt" in names) { "the shaded jar has no META-INF/coldspot/THIRD-PARTY-NOTICES.txt" }
            check("META-INF/gradle-plugins/io.github.tensky.coldspot.properties" in names) { "the shaded jar has no plugin descriptor" }
            val licenses = zip.entries().asSequence().filter { it.name == "META-INF/coldspot/LICENSE" }.toList()
            check(licenses.size == 1 && zip.getInputStream(licenses.single()).readBytes().contentEquals(license.asFile.readBytes())) {
                "the shaded jar must hold ColdSpot's LICENSE once, at META-INF/coldspot/LICENSE, as the repository's: it holds ${licenses.size}"
            }
        }
    }
}

tasks.check {
    dependsOn(checkNotices, checkShadedJar)
}
