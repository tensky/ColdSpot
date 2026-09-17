package id.tensky.coldspot.plugin

import id.tensky.coldspot.diff.FixtureRepo
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The runtime and its entry points, as the plugin wires them, under Gradle TestKit: the three-module fixture of
 * [ColdSpotMultiModuleTest] (`:app` and `:lib` under ColdSpot, `:plain` without), with the runtime's stand-in
 * ([RuntimeStub]: the real manifest and resources) in a repository of its own. Every test reads as **given**
 * the app's `coldSpot { }`, **when** a variant is resolved or built, **then** this is what it holds. The entry
 * points are resources, so what counts is what the APK holds for them: `aapt2 dump resources` is asked.
 */
class ColdSpotRuntimeWiringTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var project: File
    private lateinit var repo: FixtureRepo

    @Before
    fun fixture() {
        val sdkDir = System.getProperty("coldspot.test.sdkDir").orEmpty()
        assumeTrue("needs an Android SDK: set ANDROID_HOME or sdk.dir in local.properties", sdkDir.isNotEmpty() && File(sdkDir).isDirectory)
        project = tmp.newFolder("multi")
        repo = FixtureRepo(project)
        File(project, "local.properties").writeText("sdk.dir=$sdkDir\n")
        repo.write(".gitignore", "build/\n.gradle/\nlocal.properties\n")
        repo.write("settings.gradle.kts", RuntimeStub.inSettings(resource("multi/settings.gradle.kts"), tmp.newFolder("repo")))
        appScript(coldSpot = "")
        repo.write("lib/build.gradle.kts", resource("multi/lib.gradle.kts").replace("@LIB_DEPENDENCIES@", ""))
        repo.write("plain/build.gradle.kts", resource("multi/plain.gradle.kts"))
        repo.write("app/src/main/AndroidManifest.xml", resource("AndroidManifest.xml"))
        repo.write("app/src/main/java/demo/Feature.java", "package demo;\n\npublic class Feature {\n    public static int two() { return 2; }\n}\n")
        repo.write("lib/src/main/java/demo/lib/Lib.java", "package demo.lib;\n\npublic class Lib {\n    public static int two() { return 2; }\n}\n")
        repo.write("plain/src/main/java/demo/plain/Plain.java", "package demo.plain;\n\npublic class Plain {\n    public static int two() { return 2; }\n}\n")
        val base = repo.commitAll("base")
        repo.updateRef("refs/remotes/origin/main", base)
    }

    @After
    fun closeRepo() = repo.close()

    @Test
    fun `the runtime is on the classpaths of the application's coverage variant, at the plugin's version, and on no other`() {
        // given the app and the library under ColdSpot, nothing said in coldSpot { }

        // when every classpath that could hold it is resolved
        fun classpath(module: String, configuration: String) = run(":$module:dependencies", "--configuration", configuration, "-q").output

        // then the app's coverage variant compiles against it and packages it, and it resolved
        for (configuration in listOf("coverageCompileClasspath", "coverageRuntimeClasspath")) {
            val report = classpath("app", configuration)
            assertContains(report, RuntimeStub.coordinates, message = "app $configuration")
            assertFalse(report.lines().any { RuntimeStub.GROUP in it && "FAILED" in it }, "the runtime did not resolve on app $configuration:\n$report")
        }
        // and debug and release are as they were
        for (configuration in listOf("debugCompileClasspath", "debugRuntimeClasspath", "releaseCompileClasspath", "releaseRuntimeClasspath")) {
            assertFalse(classpath("app", configuration).contains(RuntimeStub.GROUP), "the runtime reached app $configuration")
        }
        // and a library module under ColdSpot only contributes classes: no runtime in any of its variants
        for (configuration in listOf("coverageCompileClasspath", "coverageRuntimeClasspath", "debugRuntimeClasspath", "releaseRuntimeClasspath")) {
            assertFalse(classpath("lib", configuration).contains(RuntimeStub.GROUP), "the runtime reached lib $configuration")
        }
    }

    @Test
    fun `the coverage APK carries the runtime's resources and components, the debug APK nothing of it`() {
        // given the same build

        // when both variants are built
        run(":app:assembleCoverage", ":app:assembleDebug", ":app:printCoverageManifest", ":app:printDebugManifest")

        // then the runtime's resources are in the coverage APK alone
        fun runtimeResources(variant: String) = ZipFile(File(project, "app/build/outputs/apk/$variant/app-$variant.apk")).use { apk ->
            apk.entries().asSequence().map { it.name }.filter { "coldspot_" in it }.toList()
        }
        assertTrue(runtimeResources("coverage").any { it.endsWith("coldspot_launcher.xml") }, "the coverage APK has none of the runtime's resources: ${runtimeResources("coverage")}")
        // among them ColdSpot's launcher icon, both ways: adaptive from API 26, and a picture for every density before
        val icons = runtimeResources("coverage").filter { "/coldspot_launcher." in it }
        assertTrue(icons.any { it.startsWith("res/mipmap-anydpi-v26/") && it.endsWith(".xml") }, "no adaptive launcher icon: $icons")
        for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            assertTrue(icons.any { it.startsWith("res/mipmap-$density") && it.endsWith(".png") }, "no $density launcher icon: $icons")
        }
        assertEquals(emptyList(), runtimeResources("debug"), "the debug APK carries the runtime's resources")
        // and so are its components
        assertContains(manifest("coverage"), "id.tensky.coldspot.runtime.ColdSpotActivity")
        assertContains(manifest("coverage"), "fixture.app.coldspot.HIDE_BUBBLE")
        assertFalse(manifest("debug").contains("coldspot", ignoreCase = true), "the debug manifest mentions ColdSpot:\n${manifest("debug")}")
    }

    @Test
    fun `with nothing said, the launcher alias is disabled and the bubble is on`() {
        // given no coldSpot { } at all

        // when
        run(":app:assembleCoverage", ":app:printCoverageManifest")

        // then DECISIONS.md "Entry points": bubble on, launcher icon off, as resources of the APK
        assertEquals("false", boolInApk("coverage", "coldspot_launcher_icon"))
        assertEquals("true", boolInApk("coverage", "coldspot_bubble"))
        // and the alias is enabled by that resource, not by anything written into the manifest
        assertContains(launcherAlias(manifest("coverage")), "android:enabled=\"@bool/coldspot_launcher_icon\"")
    }

    @Test
    fun `the launcher alias and the bubble follow coldSpot`() {
        // given the opposite of the defaults, which are also the opposite of each other
        appScript(coldSpot = "coldSpot { launcherIcon = true; bubble = false }")

        // when
        run(":app:assembleCoverage", ":app:assembleDebug", ":app:printCoverageManifest")

        // then the APK's resources say what the DSL said, each setting by itself, over the runtime's own defaults
        assertEquals("true", boolInApk("coverage", "coldspot_launcher_icon"))
        assertEquals("false", boolInApk("coverage", "coldspot_bubble"))
        // and the alias is a launcher entry labelled ColdSpot with an icon of its own, pointing at ColdSpot's screen
        val alias = launcherAlias(manifest("coverage"))
        assertContains(alias, "android:enabled=\"@bool/coldspot_launcher_icon\"")
        assertContains(alias, "android:targetActivity=\"id.tensky.coldspot.runtime.ColdSpotActivity\"")
        assertContains(alias, "android:label=\"@string/coldspot_name\"")
        assertContains(alias, "android:icon=\"@mipmap/coldspot_launcher\"")
        assertContains(alias, "android.intent.category.LAUNCHER")
        assertContains(File(System.getProperty("coldspot.test.runtimeMain"), "res/values/coldspot_strings.xml").readText(), "<string name=\"coldspot_name\">ColdSpot</string>")
        // and debug, which has no runtime, has neither resource
        assertEquals(null, boolInApk("debug", "coldspot_launcher_icon"))
    }

    @Test
    fun `an app without ColdSpot's plugin merges the runtime as it is, with the runtime's own defaults`() {
        // given an application that depends on the runtime by hand and knows nothing of the plugin
        repo.write(
            "app/build.gradle.kts",
            """
            plugins { id("com.android.application") }
            android {
                namespace = "fixture.app"
                compileSdk { version = release(36) }
                defaultConfig { minSdk = 24 }
            }
            dependencies { implementation("${RuntimeStub.coordinates}") }
            """.trimIndent() + "\n",
        )
        repo.write("lib/build.gradle.kts", resource("multi/plain.gradle.kts").replace("fixture.plain", "fixture.lib"))

        // when
        run(":app:assembleDebug")

        // then the manifests merged, nothing asking for a placeholder, and the entry points are the defaults
        assertEquals("true", boolInApk("debug", "coldspot_bubble"))
        assertEquals("false", boolInApk("debug", "coldspot_launcher_icon"))
    }

    /** The app's build script with [coldSpot] and tasks that copy each variant's merged manifest to `build/manifest-<variant>.xml`. */
    private fun appScript(coldSpot: String) {
        repo.write("app/build.gradle.kts", resource("multi/app.gradle.kts").replace("@COLDSPOT@", "$coldSpot\n$PRINT_MANIFEST_TASKS"))
    }

    private fun manifest(variant: String): String = File(project, "app/build/manifest-$variant.xml").readText()

    /** The whole `<activity-alias>` element of ColdSpot's launcher entry. */
    private fun launcherAlias(manifest: String): String =
        checkNotNull(Regex("<activity-alias\\b[^>]*ColdSpotLauncher.*?</activity-alias>", RegexOption.DOT_MATCHES_ALL).find(manifest)) { "no launcher alias in:\n$manifest" }.value

    /** What the [variant]'s APK holds for the bool resource [name], as `aapt2 dump resources` prints it; null when it has none. */
    private fun boolInApk(variant: String, name: String): String? {
        val sdk = File(System.getProperty("coldspot.test.sdkDir"))
        val aapt2 = checkNotNull(File(sdk, "build-tools").listFiles()?.map { File(it, "aapt2") }?.filter { it.canExecute() }?.maxByOrNull { it.parentFile.name }) { "no aapt2 under $sdk/build-tools" }
        val apk = File(project, "app/build/outputs/apk/$variant/app-$variant.apk")
        assertTrue(apk.isFile, "no APK at $apk")
        val process = ProcessBuilder(aapt2.path, "dump", "resources", apk.path).redirectErrorStream(true).start()
        val dump = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "aapt2 dump resources failed: $dump" }
        // "resource 0x7f040001 bool/coldspot_bubble" and, on the next line, "() true"
        return Regex("bool/${Regex.escape(name)}\\s*\\n\\s*\\(\\) (true|false)").find(dump)?.groupValues?.get(1)
    }

    private fun run(vararg args: String): BuildResult =
        GradleRunner.create().withProjectDir(project).withPluginClasspath().withEnvironment(localEnvironment()).withArguments(*args).build()

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixture/$name")) { "no fixture resource $name" }.use { it.readBytes().decodeToString() }

    private companion object {
        /** `printCoverageManifest`, `printDebugManifest`, ...: the merged manifest AGP's public artifact API hands out, copied where the test reads it. */
        val PRINT_MANIFEST_TASKS = """
            |androidComponents {
            |    onVariants { variant ->
            |        val merged = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
            |        val copy = layout.buildDirectory.file("manifest-${'$'}{variant.name}.xml")
            |        tasks.register("print${'$'}{variant.name.replaceFirstChar { it.uppercase() }}Manifest") {
            |            inputs.file(merged)
            |            outputs.file(copy)
            |            doLast { copy.get().asFile.writeText(merged.get().asFile.readText()) }
            |        }
            |    }
            |}
        """.trimMargin()
    }
}
