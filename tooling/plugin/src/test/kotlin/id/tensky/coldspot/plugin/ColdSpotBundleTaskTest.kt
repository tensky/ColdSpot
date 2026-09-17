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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bundle task under Gradle TestKit: a minimal Android application that is also a git repository (diff's
 * [FixtureRepo]), with one Java class committed as the base, published as `origin/main`, and edited since.
 * Every test reads as **given** that repository and build script, **when** a variant is built, **then** this
 * is what its assets hold.
 */
class ColdSpotBundleTaskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var project: File
    private lateinit var repo: FixtureRepo

    @Before
    fun fixture() {
        val sdkDir = System.getProperty("coldspot.test.sdkDir").orEmpty()
        assumeTrue("needs an Android SDK: set ANDROID_HOME or sdk.dir in local.properties", sdkDir.isNotEmpty() && File(sdkDir).isDirectory)
        project = tmp.newFolder("fixture")
        repo = FixtureRepo(project)
        File(project, "local.properties").writeText("sdk.dir=$sdkDir\n")
        repo.write(".gitignore", "build/\n.gradle/\nlocal.properties\n")
        repo.write("settings.gradle.kts", RuntimeStub.inSettings(resource("settings.gradle.kts"), tmp.newFolder("repo")))
        repo.write("src/main/AndroidManifest.xml", resource("AndroidManifest.xml"))
        repo.write(FEATURE_JAVA, featureSource(two = 2))
    }

    @After
    fun closeRepo() = repo.close()

    @Test
    fun `the coverage APK carries the manifests, the very bytes the instrumenter read, and debug's dependencies`() {
        // given the base published as origin/main, line 4 of Feature.java edited since, a debug-only jar dependency,
        // and a changed file the build script excludes by name
        writeExtraJar()
        buildScript(androidExtras = """dependencies { "debugImplementation"(files("extra.jar")) }""", coldSpot = """coldSpot { exclude("**/Noise*.java") }""")
        repo.write(NOISE_JAVA, "package demo;\n\npublic class Noise {\n    public static int n() { return 1; }\n}\n")
        commitBaseAndEdit()
        repo.write(NOISE_JAVA, "package demo;\n\npublic class Noise {\n    public static int n() { return 2; }\n}\n")

        // when
        run("assembleCoverage")

        // then the APK's assets/coldspot/ holds the shared manifest naming the file with its text, and the excluded one with its rule
        val apk = File(project, "build/outputs/apk/coverage/fixture-coverage.apk")
        val assets = coldspotAssetsIn(apk)
        val shared = checkNotNull(assets["assets/coldspot/manifest.json"]) { "no shared manifest among ${assets.keys}" }.decodeToString()
        assertContains(shared, "\"path\": \"$FEATURE_JAVA\"")
        assertContains(shared, "return 20;", message = "the changed file's text is not in the shared manifest")
        assertContains(shared, "\"path\": \"$NOISE_JAVA\"")
        assertContains(shared, "\"rule\": \"**/Noise*.java\"")
        // and names the JaCoCo that instrumented, the plugin's own
        assertContains(shared, "\"version\": \"${System.getProperty("coldspot.test.jacocoVersion")}\"")
        // and the module's manifest names its class, as javac wrote it, nothing for the excluded file
        val module = checkNotNull(assets["assets/coldspot/root/manifest.json"]) { "no module manifest among ${assets.keys}" }.decodeToString()
        assertContains(module, "\"demo/Feature\"")
        assertFalse(module.contains("Noise"), "the excluded file reached the module manifest")
        assertFalse(assets.containsKey("assets/coldspot/root/classes/demo/Noise.class"), "the excluded file's class shipped")
        val shipped = checkNotNull(assets["assets/coldspot/root/classes/demo/Feature.class"]) { "class not shipped: ${assets.keys}" }
        assertContentEquals(compiledFeatureClass().readBytes(), shipped, "the shipped bytes are not the compiler's")
        // and the class the rest of the build saw, the transform's output, is that class with probes in it
        val transformed = transformedClass("demo/Feature.class")
        assertFalse(transformed.contentEquals(shipped), "the transform left the selected class alone")
        assertContains(String(transformed, Charsets.ISO_8859_1), "\$jacocoInit", message = "the transformed class carries no JaCoCo probes")
        assertFalse(String(shipped, Charsets.ISO_8859_1).contains("\$jacocoInit"), "the shipped bytes carry probes")
        // and what debug alone depended on is in the coverage APK too
        assertTrue(ZipFile(apk).use { it.getEntry("extra/marker.txt") != null }, "debugImplementation's jar did not reach the coverage APK")
    }

    @Test
    fun `debug and release carry no coldspot assets, and get no bundle task`() {
        // given the same repository
        buildScript()
        commitBaseAndEdit()

        // when
        val output = run("mergeDebugAssets", "mergeReleaseAssets").output

        // then
        assertFalse(output.contains("coldSpotBundle"), "a bundle task ran for another build type")
        assertEquals(emptyList(), mergedColdspotAssets("debug"))
        assertEquals(emptyList(), mergedColdspotAssets("release"))
    }

    @Test
    fun `two product flavors get a bundle each`() {
        // given two flavors on one dimension
        buildScript(androidExtras = """flavorDimensions += "tier"; productFlavors { create("free") { dimension = "tier" }; create("paid") { dimension = "tier" } }""")
        commitBaseAndEdit()

        // when
        val output = run("mergeFreeCoverageAssets", "mergePaidCoverageAssets").output

        // then each variant's merged assets hold the shared manifest and the module's
        assertContains(output, "Task :coldSpotBundleFreeCoverage")
        assertContains(output, "Task :coldSpotBundlePaidCoverage")
        for (variant in listOf("freeCoverage", "paidCoverage")) {
            val manifests = mergedColdspotAssets(variant).filter { it.name == "manifest.json" }.map { it.invariantSeparatorsPath.substringAfter("/coldspot/") }.sorted()
            assertEquals(listOf("manifest.json", "root/manifest.json"), manifests, "manifests of $variant")
        }
    }

    @Test
    fun `a second build with nothing changed leaves merging and packaging up to date`() {
        // given a first build already done
        buildScript()
        commitBaseAndEdit()
        run("assembleCoverage")

        // when nothing changed and the same build runs again
        val output = run("assembleCoverage").output

        // then the bundle task ran, as it always does, but produced the same bytes, so nothing downstream did
        assertTrue(Regex("^> Task :coldSpotBundleCoverage$", RegexOption.MULTILINE).containsMatchIn(output), "the bundle task did not run:\n$output")
        assertContains(output, "> Task :mergeCoverageAssets UP-TO-DATE")
        assertContains(output, "> Task :packageCoverage UP-TO-DATE")
    }

    @Test
    fun `the configuration cache is stored on the first run and reused on the second`() {
        // given the same repository
        buildScript()
        commitBaseAndEdit()

        // when run twice with the configuration cache on
        val first = run("mergeCoverageAssets", "--configuration-cache").output
        val second = run("mergeCoverageAssets", "--configuration-cache").output

        // then the second run configured nothing and the bundle task still ran
        assertContains(first, "Configuration cache entry stored.")
        assertContains(second, "Reusing configuration cache.")
        assertTrue(Regex("^> Task :coldSpotBundleCoverage$", RegexOption.MULTILINE).containsMatchIn(second), "the bundle task did not run:\n$second")
    }

    @Test
    fun `AGP's own JaCoCo over ColdSpot's transform fails, and AGP instruments only the testBuildType`() {
        // given the module's own coverage build type as the testBuildType, with AGP's coverage switched back on after
        // ColdSpot's finalizeDsl ran (the script's callback is registered later, so it has the last word)
        val ownType = """buildTypes { create("coverage") { initWith(getByName("debug")) } }"""
        val coverageOn = """androidComponents { finalizeDsl { it.buildTypes.getByName("coverage").enableAndroidTestCoverage = true } }"""
        buildScript(androidExtras = "$ownType; testBuildType = \"coverage\"", coldSpot = coverageOn)
        commitBaseAndEdit()

        // when
        val failed = runAndFail("assembleCoverage", "--stacktrace").output

        // then AGP's jacoco task runs after the scoped-artifact transforms, sees ColdSpot's probes, and JaCoCo refuses
        // instrumented input: the conflict ColdSpot refuses while configuring
        assertContains(failed, "Task :jacocoCoverage FAILED")
        assertContains(failed, "Cannot process instrumented class")

        // and with debug left as the testBuildType, the same build type builds: AGP instruments only the testBuildType
        buildScript(androidExtras = ownType, coldSpot = coverageOn)
        val built = run("assembleCoverage").output
        assertFalse(built.contains("Task :jacocoCoverage"), "AGP's jacoco task ran for a build type that is not the testBuildType:\n$built")
    }

    @Test
    fun `a class the change does not touch, compiled a second time by a task appended to the module's classes, is read from the first and logged`() {
        // given Helper.java compiled by AGP's javac and again, with -parameters, by a task appended to CLASSES as Hilt's
        // aggregating task is; Feature.java edited, Helper.java not
        repo.write(HELPER_JAVA, HELPER_SOURCE)
        buildScript(coldSpot = compiledTwice(HELPER_JAVA))
        commitBaseAndEdit()

        // when
        val output = run("assembleCoverage", "--info").output

        // then the build succeeds, the duplicate is on the info log with both files and which one counts, and Feature shipped
        assertContains(output, Regex("ColdSpot: demo/Helper is compiled differently in .* and .*; the first is what the build uses"), "the duplicate was not logged:\n${output.lines().filter { "ColdSpot" in it }}")
        val assets = coldspotAssetsIn(File(project, "build/outputs/apk/coverage/fixture-coverage.apk"))
        assertTrue("assets/coldspot/root/classes/demo/Feature.class" in assets, "the edited class did not ship: ${assets.keys}")
    }

    @Test
    fun `a class the change touches, compiled a second time, fails the build with the way out`() {
        // given the same second compilation over Feature.java, the file the change touches
        buildScript(coldSpot = compiledTwice(FEATURE_JAVA))
        repo.write(FEATURE_JAVA, featureWithParameter(two = 2))
        val base = repo.commitAll("base")
        repo.updateRef("refs/remotes/origin/main", base)
        repo.write(FEATURE_JAVA, featureWithParameter(two = 20))

        // when
        val output = runAndFail("assembleCoverage").output

        // then
        assertContains(output, "demo/Feature is compiled differently in")
        assertContains(output, "the change touches it")
        assertContains(output, "clean build")
    }

    @Test
    fun `a fresh-session build writes a token no other build has, a normal build writes none`() {
        // given the repository
        buildScript()
        commitBaseAndEdit()

        // when built twice as a fresh session, and once normally
        run("assembleCoverage", "-Pcoldspot.freshSession")
        val first = sharedManifestToken()
        run("assembleCoverage", "-Pcoldspot.freshSession")
        val second = sharedManifestToken()
        run("assembleCoverage")
        val normal = sharedManifestToken()

        // then
        assertTrue(first != null && Regex("[0-9a-f-]{36}").matches(first), "not a UUID: $first")
        assertTrue(second != null && second != first, "the second fresh-session build reused the token $first")
        assertEquals(null, normal, "a normal build carries a token")
    }

    /** The `resetToken` of the shared manifest in the coverage APK: the UUID, or null for `null`. */
    private fun sharedManifestToken(): String? {
        val shared = checkNotNull(coldspotAssetsIn(File(project, "build/outputs/apk/coverage/fixture-coverage.apk"))["assets/coldspot/manifest.json"]).decodeToString()
        val match = checkNotNull(Regex("\"resetToken\": (null|\"[^\"]*\")").find(shared)) { "no resetToken in the shared manifest:\n$shared" }
        return match.groupValues[1].takeUnless { it == "null" }?.trim('"')
    }

    @Test
    fun `without an origin remote or a baseRef the build fails naming the setting`() {
        // given a repository nobody published, and no baseRef
        buildScript()
        commitBaseAndEdit(publishOrigin = false)

        // when
        val output = runAndFail("mergeCoverageAssets").output

        // then the diff module's refusal reaches the build output
        assertContains(output, "coldSpot { baseRef")
        assertContains(output, "origin/HEAD, origin/main or origin/master")
    }

    @Test
    fun `on CI a base nobody gave fails the build with the fix, off CI it is guessed, and a cached configuration follows CI`() {
        // given origin/main published and no base given, built with the configuration cache on, off CI first
        buildScript()
        commitBaseAndEdit()
        val offCi = run("mergeCoverageAssets", "--configuration-cache").output

        // when the same build runs on CI, then on CI with the base given
        val onCi = runner("mergeCoverageAssets", "--configuration-cache").withEnvironment(ciEnvironment()).buildAndFail().output
        val given = runner("mergeCoverageAssets", "--configuration-cache", "-Pcoldspot.base=origin/main").withEnvironment(ciEnvironment()).build().output

        // then off CI origin/main is guessed, and the build says so; on CI the configuration cached off CI is not
        // reused as it was, and the build fails with the fix; given, the base is taken as it is
        assertContains(offCi, "comparing with origin/main, a guess")
        assertContains(offCi, "Configuration cache entry stored.")
        assertContains(onCi, "No base was given, and on CI (CI=true) ColdSpot takes none it is not given, neither origin/HEAD nor a guess")
        assertContains(onCi, "-Pcoldspot.base=origin/<target>, or set coldSpot { baseRef")
        assertFalse("a guess" in given, "the base given on CI was taken for a guess:\n$given")
        assertEquals("origin/main", Regex("\"ref\": \"([^\"]*)\"").find(sharedManifest())?.groupValues?.get(1))
        assertEquals("EXPLICIT", Regex("\"source\": \"([^\"]*)\"").find(sharedManifest())?.groupValues?.get(1))
    }

    private fun sharedManifest(): String =
        mergedColdspotAssets("coverage").single { it.name == "manifest.json" && it.parentFile.name == "coldspot" }.readText()

    /** Commits everything as the base, publishes it as `origin/main` unless told not to, then edits line 4 of Feature.java. */
    private fun commitBaseAndEdit(publishOrigin: Boolean = true) {
        val base = repo.commitAll("base")
        if (publishOrigin) repo.updateRef("refs/remotes/origin/main", base)
        repo.write(FEATURE_JAVA, featureSource(two = 20))
    }

    private fun buildScript(androidExtras: String = "", coldSpot: String = "") {
        repo.write("build.gradle.kts", resource("build.gradle.kts").replace("@ANDROID_EXTRAS@", androidExtras).replace("@COLDSPOT@", coldSpot))
    }

    /** A jar with one Java resource in it, for a `debugImplementation` file dependency to carry into an APK. */
    private fun writeExtraJar() {
        ZipOutputStream(File(project, "extra.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("extra/marker.txt"))
            zip.write("marker".toByteArray())
            zip.closeEntry()
        }
    }

    /** The APK's entries under `assets/coldspot/`, name to bytes. */
    private fun coldspotAssetsIn(apk: File): Map<String, ByteArray> {
        assertTrue(apk.isFile, "no APK at $apk")
        return ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.startsWith("assets/coldspot/") }.associate { it.name to zip.getInputStream(it).readBytes() }
        }
    }

    /** Files under `coldspot/` in the merged assets of [variant], whatever AGP's merge task is called. */
    private fun mergedColdspotAssets(variant: String): List<File> =
        File(project, "build/intermediates/assets/$variant").walkTopDown().filter { it.isFile && "/coldspot/" in it.invariantSeparatorsPath }.toList()

    /** javac's own output for Feature, the artifact the task was given. */
    private fun compiledFeatureClass(): File =
        File(project, "build/intermediates/javac").walkTopDown().single { it.name == "Feature.class" && "/coverage/" in it.invariantSeparatorsPath }

    /** [entry] of the transform's output jar, the classes the rest of the build (and the dex) was made from. */
    private fun transformedClass(entry: String): ByteArray {
        val jar = File(project, "build/intermediates/classes/coverage").walkTopDown()
            .single { it.name == "classes.jar" && "/coldSpotBundleCoverage/" in it.invariantSeparatorsPath }
        return ZipFile(jar).use { zip -> zip.getInputStream(checkNotNull(zip.getEntry(entry)) { "no $entry in $jar" }).use { it.readBytes() } }
    }

    private fun run(vararg args: String): BuildResult = runner(*args).build()

    private fun runAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create().withProjectDir(project).withPluginClasspath().withEnvironment(localEnvironment()).withArguments(*args)

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixture/$name")) { "no fixture resource $name" }.use { it.readBytes().decodeToString() }

    private companion object {
        const val FEATURE_JAVA = "src/main/java/demo/Feature.java"
        const val NOISE_JAVA = "src/main/java/demo/Noise.java"
        const val HELPER_JAVA = "src/main/java/demo/Helper.java"

        /** A method with a parameter, so that javac's `-parameters` changes the bytes. */
        const val HELPER_SOURCE = "package demo;\n\npublic class Helper {\n    public static int twice(int x) { return x * 2; }\n}\n"

        /** Like [featureSource], with a parameter on `two`, for the same reason. */
        fun featureWithParameter(two: Int): String = "package demo;\n\npublic class Feature {\n    public static int two(int x) { return $two + x; }\n}\n"

        /**
         * Build script lines that compile [source] a second time, with `-parameters`, and append the output to the
         * module's classes the way Hilt's aggregating task does (`toAppend(ScopedArtifact.CLASSES)`).
         */
        fun compiledTwice(source: String): String = """
            |val compiledTwice = tasks.register<JavaCompile>("compileTwice") {
            |    source("$source")
            |    classpath = files()
            |    destinationDirectory.set(layout.buildDirectory.dir("twice"))
            |    options.compilerArgs.add("-parameters")
            |}
            |androidComponents.onVariants(androidComponents.selector().withBuildType("coverage")) { variant ->
            |    variant.artifacts.forScope(com.android.build.api.variant.ScopedArtifacts.Scope.PROJECT)
            |        .use(compiledTwice)
            |        .toAppend(com.android.build.api.artifact.ScopedArtifact.CLASSES, JavaCompile::getDestinationDirectory)
            |}
        """.trimMargin()

        /** Six lines; line 4 is the one the tests edit. */
        fun featureSource(two: Int): String = """
            |package demo;
            |
            |public class Feature {
            |    public static int two() { return $two; }
            |    public static int three() { return 3; }
            |}
            |
        """.trimMargin()
    }
}
