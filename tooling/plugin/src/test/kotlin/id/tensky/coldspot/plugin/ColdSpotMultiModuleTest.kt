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
 * ColdSpot across modules, under Gradle TestKit: `:app` (ColdSpot) depends on `:lib` (ColdSpot), which depends
 * on `:plain` (no ColdSpot, so no coverage build type). One git repository holds all three (diff's [FixtureRepo]),
 * with a Java class per module committed as the base, published as `origin/main`, and edited since. Every test
 * reads as **given** that repository, **when** the app's coverage variant is built, **then** this is what happens.
 */
class ColdSpotMultiModuleTest {
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
        repo.write("app/build.gradle.kts", resource("multi/app.gradle.kts").replace("@COLDSPOT@", ""))
        repo.write("lib/build.gradle.kts", resource("multi/lib.gradle.kts").replace("@LIB_DEPENDENCIES@", ""))
        repo.write("plain/build.gradle.kts", resource("multi/plain.gradle.kts"))
        repo.write("app/src/main/AndroidManifest.xml", resource("AndroidManifest.xml"))
        repo.write(APP_FEATURE, javaClass("demo", "Feature", value = 2))
        repo.write(APP_TEST, javaClass("demo", "FeatureTest", value = 2))
        repo.write(LIB_CLASS, javaClass("demo.lib", "Lib", value = 2))
        repo.write(PLAIN_CLASS, javaClass("demo.plain", "Plain", value = 2))
    }

    @After
    fun closeRepo() = repo.close()

    @Test
    fun `a module left out of ColdSpot does not break the coverage variant of the modules depending on it`() {
        // given :lib's coverage variant needing :plain, which has only debug and release
        commitBaseAndEdit()

        // when
        val output = run(":app:assembleCoverage").output

        // then it resolved to :plain's debug, and :plain shipped nothing
        assertContains(output, "Task :lib:coldSpotBundleCoverage")
        assertContains(output, "Task :plain:compileDebugJavaWithJavac")
        assertFalse(output.contains(":plain:coldSpotBundle"), ":plain has no ColdSpot, yet a bundle task ran there")
        val assets = coldspotAssetsIn(apk())
        assertFalse(assets.keys.any { it.startsWith("assets/coldspot/plain/") }, "plain shipped something: ${assets.keys}")
    }

    @Test
    fun `the diff runs once, the app writes the one shared manifest, and each module ships only its own classes`() {
        // given three edited files: one per ColdSpot module, and a test source in the app
        commitBaseAndEdit()

        // when
        val output = run(":app:assembleCoverage", "--info").output

        // then git was asked once for the whole build
        assertEquals(1, Regex("ColdSpot: diffed ").findAll(output).count(), "the diff did not run exactly once:\n${output.lines().filter { "ColdSpot: diffed" in it }}")

        // and the shared manifest, the app's, describes the change: both measured files with their text, the test file excluded with its rule
        val assets = coldspotAssetsIn(apk())
        val shared = checkNotNull(assets["assets/coldspot/manifest.json"]) { "no shared manifest among ${assets.keys}" }.decodeToString()
        assertContains(shared, "\"schemaVersion\": 8")
        assertContains(shared, "\"coldspotVersion\": \"${RuntimeStub.version}\"", message = "the plugin's own version, which is the runtime's")
        assertContains(shared, "\"branch\": \"main\"")
        assertContains(shared, "\"path\": \"$APP_FEATURE\"")
        assertContains(shared, "\"path\": \"$LIB_CLASS\"")
        assertContains(shared, "public static int two() { return 20; }", message = "the shared manifest carries no file text")
        assertContains(shared, "\"path\": \"$APP_TEST\"")
        assertContains(shared, "\"rule\": \"**/src/test/**\"")
        assertFalse(Regex("\"path\": \"$APP_TEST\"[^}]*\"text\"").containsMatchIn(shared), "the excluded test source was measured")

        // and each module's manifest names only its classes, with no text of its own
        val app = checkNotNull(assets["assets/coldspot/app/manifest.json"]).decodeToString()
        val lib = checkNotNull(assets["assets/coldspot/lib/manifest.json"]).decodeToString()
        assertContains(app, "\"demo/Feature\"")
        assertFalse(app.contains("demo/lib/Lib"), "the app's manifest lists :lib's class")
        assertContains(lib, "\"demo/lib/Lib\"")
        assertFalse(lib.contains("demo/Feature"), ":lib's manifest lists the app's class")
        assertFalse(app.contains("\"text\"") || lib.contains("\"text\""), "a module manifest carries file text")
        assertTrue("assets/coldspot/app/classes/demo/Feature.class" in assets, "the app's class did not ship")
        assertTrue("assets/coldspot/lib/classes/demo/lib/Lib.class" in assets, ":lib's class did not ship")
        assertFalse(assets.keys.any { it.startsWith("assets/coldspot/app/classes/demo/lib/") }, "the app shipped :lib's class")
    }

    @Test
    fun `a function under a multipreview from another module is disclosed as preview lines, the method next to it measured`() {
        // given @ThemePreviews in :lib, annotated with (a homonym of) Compose's @Preview, and the app's Feature using it on a
        // method whose body spans lines 8-11, next to a plain method on line 6; both edited
        repo.write(LIB_PREVIEW, "package androidx.compose.ui.tooling.preview;\n\npublic @interface Preview {}\n")
        repo.write(LIB_THEME_PREVIEWS, "package demo.lib;\n\n@androidx.compose.ui.tooling.preview.Preview\npublic @interface ThemePreviews {}\n")
        repo.write(APP_FEATURE, featureWithPreview(two = 2, label = "p"))
        val base = repo.commitAll("base")
        repo.updateRef("refs/remotes/origin/main", base)
        repo.write(APP_FEATURE, featureWithPreview(two = 20, label = "q"))

        // when
        run(":app:assembleCoverage")

        // then the app's manifest lists line 10 under the preview, with the annotation found on the compile classpath, and line 6 stays measured
        val app = checkNotNull(coldspotAssetsIn(apk())["assets/coldspot/app/manifest.json"]).decodeToString()
        assertContains(app, "\"demo/Feature\"")
        assertContains(app, "\"reason\": \"@ThemePreviews on preview (@Preview via @ThemePreviews)\"")
        assertTrue(Regex("\"lines\": \\[\\s*10\\s*]").containsMatchIn(app), "line 10 is not the preview's:\n$app")
        assertFalse(Regex("\"lines\": \\[[^]]*\\b6\\b").containsMatchIn(app), "line 6, the plain method's, was made a preview line:\n$app")
    }

    @Test
    fun `a library pulling another JaCoCo agent does not change the one the coverage variant runs, and the manifest names it`() {
        // given :lib depending on a newer JaCoCo agent than the instrumenter's, which Gradle would otherwise let win
        val ours = System.getProperty("coldspot.test.jacocoVersion")
        assertTrue(OTHER_JACOCO != ours, "the fixture's other JaCoCo, $OTHER_JACOCO, must differ from the instrumenter's, $ours: pick another")
        repo.write(
            "lib/build.gradle.kts",
            resource("multi/lib.gradle.kts").replace("@LIB_DEPENDENCIES@", "implementation(\"org.jacoco:org.jacoco.agent:$OTHER_JACOCO:runtime\")"),
        )
        repo.write("app/build.gradle.kts", resource("multi/app.gradle.kts").replace("@COLDSPOT@", PRINT_JACOCO_TASK))
        commitBaseAndEdit()

        // when the app's coverage runtime classpath is resolved, and the coverage APK built
        val resolved = run(":app:printJacoco", "-q").output
        run(":app:assembleCoverage")

        // then the agent resolves to the instrumenter's version, the other one nowhere; and jacoco-core is not there at
        // all: the analyser the app needs is relocated inside ColdSpot's runtime (4d)
        assertContains(resolved, "org.jacoco:org.jacoco.agent:$ours")
        assertFalse(resolved.contains(OTHER_JACOCO), "the library's JaCoCo won on the coverage runtime classpath:\n$resolved")
        assertFalse(resolved.contains("org.jacoco:org.jacoco.core"), "ColdSpot put jacoco-core on the app's coverage runtime classpath:\n$resolved")
        // and the shared manifest names that version for the app to check against
        val shared = checkNotNull(coldspotAssetsIn(apk())["assets/coldspot/manifest.json"]).decodeToString()
        assertContains(shared, "\"jacoco\": {")
        assertContains(shared, "\"version\": \"$ours\"")
        assertTrue(Regex("\"build\": \"${Regex.escape(ours)}\\.\\d+\"").containsMatchIn(shared), "the manifest's jacoco.build is not $ours's qualified JaCoCo.VERSION:\n$shared")
    }

    /** Commits everything as the base, publishes it as `origin/main`, then edits line 4 of the app's, the lib's and the test's class. */
    private fun commitBaseAndEdit() {
        val base = repo.commitAll("base")
        repo.updateRef("refs/remotes/origin/main", base)
        repo.write(APP_FEATURE, javaClass("demo", "Feature", value = 20))
        repo.write(APP_TEST, javaClass("demo", "FeatureTest", value = 20))
        repo.write(LIB_CLASS, javaClass("demo.lib", "Lib", value = 20))
    }

    private fun apk(): File = File(project, "app/build/outputs/apk/coverage/app-coverage.apk")

    /** The APK's entries under `assets/coldspot/`, name to bytes. */
    private fun coldspotAssetsIn(apk: File): Map<String, ByteArray> {
        assertTrue(apk.isFile, "no APK at $apk")
        return ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.startsWith("assets/coldspot/") }.associate { it.name to zip.getInputStream(it).readBytes() }
        }
    }

    private fun run(vararg args: String): BuildResult =
        GradleRunner.create().withProjectDir(project).withPluginClasspath().withEnvironment(localEnvironment()).withArguments(*args).build()

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixture/$name")) { "no fixture resource $name" }.use { it.readBytes().decodeToString() }

    private companion object {
        const val APP_FEATURE = "app/src/main/java/demo/Feature.java"
        const val APP_TEST = "app/src/test/java/demo/FeatureTest.java"
        const val LIB_CLASS = "lib/src/main/java/demo/lib/Lib.java"
        const val PLAIN_CLASS = "plain/src/main/java/demo/plain/Plain.java"
        /** A JaCoCo newer than the instrumenter's, so that plain conflict resolution would pick it. */
        const val OTHER_JACOCO = "0.8.15"

        /** Prints every `org.jacoco` module on the app's coverage runtime classpath, at the version resolution settled on. */
        val PRINT_JACOCO_TASK = """
            |tasks.register("printJacoco") {
            |    val runtime = configurations.getByName("coverageRuntimeClasspath")
            |    doLast { runtime.incoming.resolutionResult.allComponents.map { it.id.displayName }.filter { it.startsWith("org.jacoco:") }.sorted().forEach(::println) }
            |}
        """.trimMargin()

        const val LIB_PREVIEW = "lib/src/main/java/androidx/compose/ui/tooling/preview/Preview.java"
        const val LIB_THEME_PREVIEWS = "lib/src/main/java/demo/lib/ThemePreviews.java"

        /** Eleven lines: a plain method on line 6, a preview method on 8-11 with a lambda in its body on 9 and [label] on 10. */
        fun featureWithPreview(two: Int, label: String): String = """
            |package demo;
            |
            |import demo.lib.ThemePreviews;
            |
            |public class Feature {
            |    public static int two() { return $two; }
            |    @ThemePreviews
            |    public static String preview() {
            |        Runnable body = () -> System.out.println("preview");
            |        return "$label" + body;
            |    }
            |}
            |
        """.trimMargin()

        /** Six lines; line 4 is the one the tests edit. */
        fun javaClass(pkg: String, name: String, value: Int): String = """
            |package $pkg;
            |
            |public class $name {
            |    public static int two() { return $value; }
            |    public static int three() { return 3; }
            |}
            |
        """.trimMargin()
    }
}
