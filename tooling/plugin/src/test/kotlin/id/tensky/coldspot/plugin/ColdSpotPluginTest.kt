package id.tensky.coldspot.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The plugin applied to a minimal Android application under Gradle TestKit. Every test reads as **given** an
 * app build script, **when** a task is run, **then** this is what the build types, the configurations and the
 * base look like.
 *
 * Needs an Android SDK, found through `coldspot.test.sdkDir` (set by the build from ANDROID_HOME or
 * local.properties); without one the tests skip themselves and say so.
 */
class ColdSpotPluginTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var project: File

    @Before
    fun fixture() {
        val sdkDir = System.getProperty("coldspot.test.sdkDir").orEmpty()
        assumeTrue("needs an Android SDK: set ANDROID_HOME or sdk.dir in local.properties", sdkDir.isNotEmpty() && File(sdkDir).isDirectory)
        project = tmp.newFolder("fixture")
        File(project, "local.properties").writeText("sdk.dir=$sdkDir\n")
        File(project, "settings.gradle.kts").writeText(RuntimeStub.inSettings(resource("settings.gradle.kts"), tmp.newFolder("repo")))
        File(project, "src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText(resource("AndroidManifest.xml"))
    }

    @Test
    fun `the coverage build type is created debuggable, with debug's application ID and debug as its fallback, and release is left alone`() {
        // given an app that says nothing about build types
        buildScript()

        // when
        val output = run("printBuildTypes").output

        // then a debug-like coverage build type exists with AGP's own coverage off (C1), no suffix of its own, and the others are as AGP made them
        assertContains(output, "coverage: debuggable=true androidTestCoverage=false suffix=null fallbacks=[debug]")
        assertContains(output, "debug: debuggable=true androidTestCoverage=false suffix=null fallbacks=[]")
        assertContains(output, "release: debuggable=false androidTestCoverage=false suffix=null fallbacks=[]")
    }

    @Test
    fun `the coverage build type takes debug's application ID suffix, unless coldSpot asks for another`() {
        // given debug with a suffix of its own
        buildScript(androidExtras = """buildTypes { debug { applicationIdSuffix = ".dbg" } }""")
        val inherited = run("printBuildTypes").output

        // and the same, with a suffix asked of ColdSpot
        buildScript(
            androidExtras = """buildTypes { debug { applicationIdSuffix = ".dbg" } }""",
            coldSpot = """coldSpot { applicationIdSuffix = ".cov" }""",
        )
        val asked = run("printBuildTypes").output

        // then coverage installs as debug does, or as asked
        assertContains(inherited, "coverage: debuggable=true androidTestCoverage=false suffix=.dbg fallbacks=[debug]")
        assertContains(asked, "coverage: debuggable=true androidTestCoverage=false suffix=.cov fallbacks=[debug]")
    }

    @Test
    fun `an existing build type of that name is reused, not replaced, and only gains the fallback`() {
        // given the app's own coverage build type, recognisable by its suffix
        buildScript(androidExtras = """buildTypes { create("coverage") { initWith(getByName("debug")); applicationIdSuffix = ".qa" } }""")

        // when
        val output = run("printBuildTypes").output

        // then it kept its suffix and debug's coverage setting, and debug is among its fallbacks
        assertContains(output, "coverage: debuggable=true androidTestCoverage=false suffix=.qa fallbacks=[debug]")
    }

    @Test
    fun `a build type the module defines with AGP's coverage on, as the testBuildType, is refused with the ways out`() {
        // given the app's own coverage build type with enableAndroidTestCoverage, and device tests targeting it
        buildScript(androidExtras = """buildTypes { create("coverage") { initWith(getByName("debug")); enableAndroidTestCoverage = true } }; testBuildType = "coverage"""")

        // when
        val output = runAndFail("printBuildTypes").output

        // then the build stops while configuring, naming the conflict and the three ways out
        assertContains(output, "ColdSpot: build type 'coverage' is defined by this module with enableAndroidTestCoverage = true and is the testBuildType")
        assertContains(output, "let ColdSpot create its own build type")
        assertContains(output, "set enableAndroidTestCoverage = false on 'coverage'")
        assertContains(output, "make another build type the testBuildType")
        assertFalse(output.contains("Task :printBuildTypes"), "the build should not have reached execution")
    }

    @Test
    fun `a build type the module defines keeps AGP's coverage on when it is not the testBuildType`() {
        // given the same build type, with debug left as the testBuildType
        buildScript(androidExtras = """buildTypes { create("coverage") { initWith(getByName("debug")); enableAndroidTestCoverage = true } }""")

        // when
        val output = run("printBuildTypes").output

        // then the setting is as the module wrote it: AGP instruments only the testBuildType, so nothing conflicts
        assertContains(output, "coverage: debuggable=true androidTestCoverage=true suffix=null fallbacks=[debug]")
    }

    @Test
    fun `a suffix asked of a build type the module defines itself is refused, and the message says what to do`() {
        // given the app's own coverage build type and a suffix in coldSpot { }
        buildScript(
            androidExtras = """buildTypes { create("coverage") { initWith(getByName("debug")) } }""",
            coldSpot = """coldSpot { applicationIdSuffix = ".cov" }""",
        )

        // when
        val output = runAndFail("printBuildTypes").output

        // then the build stops while configuring, naming the setting and the build type
        assertContains(output, "coldSpot { applicationIdSuffix = \".cov\" } cannot apply: build type 'coverage' is defined by this module")
        assertFalse(output.contains("Task :printBuildTypes"), "the build should not have reached execution")
    }

    @Test
    fun `a build type that is not debuggable is refused, and the message says what to do`() {
        // given ColdSpot pointed at a release-like build type
        buildScript(
            androidExtras = """buildTypes { create("staging") { initWith(getByName("release")) } }""",
            coldSpot = """coldSpot { buildTypeName = "staging" }""",
        )

        // when
        val output = runAndFail("printBuildTypes").output

        // then the build stops while configuring, naming the build type and the setting
        assertContains(output, "ColdSpot: build type 'staging' is not debuggable")
        assertContains(output, "coldSpot { buildTypeName")
        assertFalse(output.contains("Task :printBuildTypes"), "the build should not have reached execution")
    }

    @Test
    fun `coverage dependency configurations extend their debug twins, the classpaths do not`() {
        // given two flavors on one dimension, so that flavored configurations exist too
        buildScript(androidExtras = """flavorDimensions += "tier"; productFlavors { create("free") { dimension = "tier" }; create("paid") { dimension = "tier" } }""")

        // when
        val names = "coverageImplementation,coverageRuntimeOnly,coverageCompileOnly,coverageApi,coverageAnnotationProcessor,freeCoverageImplementation,freeCoverageRuntimeOnly,freeCoverageCompileClasspath,freeCoverageRuntimeClasspath"
        val output = run("printConfigurationParents", "-Pnames=$names", "-q").output

        // then every dependency scope of the coverage build type has its debug twin among its parents, resolvable classpaths do not
        for (scope in listOf("Implementation", "RuntimeOnly", "CompileOnly", "Api", "AnnotationProcessor")) {
            assertContains(output, Regex("^coverage$scope <- \\[.*debug$scope.*]$", RegexOption.MULTILINE), "coverage$scope does not extend debug$scope:\n$output")
        }
        assertContains(output, Regex("^freeCoverageImplementation <- \\[.*freeDebugImplementation.*]$", RegexOption.MULTILINE), "the flavored scope was not wired:\n$output")
        assertContains(output, Regex("^freeCoverageRuntimeOnly <- \\[.*freeDebugRuntimeOnly.*]$", RegexOption.MULTILINE), "the flavored scope was not wired:\n$output")
        assertFalse(output.lines().first { it.startsWith("freeCoverageCompileClasspath <-") }.contains("freeDebugCompileClasspath"), "a classpath was extended:\n$output")
        assertFalse(output.lines().first { it.startsWith("freeCoverageRuntimeClasspath <-") }.contains("freeDebugRuntimeClasspath"), "a classpath was extended:\n$output")
    }

    @Test
    fun `exclude adds to the default rules, and setting the property replaces them`() {
        // given nothing said, a pattern added, two added one after the other, and the property set
        buildScript()
        val defaults = run("printExcludes", "-q").output
        buildScript(coldSpot = """coldSpot { exclude("**/generated/**") }""")
        val added = run("printExcludes", "-q").output
        buildScript(coldSpot = """coldSpot { exclude("**/a/**"); exclude("**/b/**", "**/c/**") }""")
        val addedTwice = run("printExcludes", "-q").output
        buildScript(coldSpot = """coldSpot { excludes.set(listOf("**/only/**")); exclude("**/and/**") }""")
        val replaced = run("printExcludes", "-q").output

        // then the defaults stay until they are replaced, and what is added comes after what is there
        val rules = "**/src/test/**, **/src/androidTest/**, **/src/testFixtures/**, **/buildSrc/**, **/build-logic/**"
        assertContains(defaults, "excludes=[$rules]")
        assertContains(added, "excludes=[$rules, **/generated/**]")
        assertContains(addedTwice, "excludes=[$rules, **/a/**, **/b/**, **/c/**]")
        assertContains(replaced, "excludes=[**/only/**, **/and/**]")
    }

    @Test
    fun `-Pcoldspot base overrides the baseRef of the DSL`() {
        // given a base ref in the DSL
        buildScript(coldSpot = """coldSpot { baseRef = "origin/develop" }""")

        // when asked plainly, and again with the property
        val fromDsl = run("printBase", "-q").output
        val fromProperty = run("printBase", "-q", "-Pcoldspot.base=v1.0").output

        // then
        assertContains(fromDsl, "base=origin/develop")
        assertContains(fromProperty, "base=v1.0")
    }

    @Test
    fun `the configuration cache is stored on the first run and reused on the second`() {
        // given the plain app
        buildScript()

        // when run twice with the configuration cache on
        val first = run("printBuildTypes", "--configuration-cache").output
        val second = run("printBuildTypes", "--configuration-cache").output

        // then the second run configured nothing and still knows the build types
        assertContains(first, "Configuration cache entry stored.")
        assertContains(second, "Reusing configuration cache.")
        assertContains(second, "coverage: debuggable=true androidTestCoverage=false suffix=null fallbacks=[debug]")
    }

    /** Writes the fixture's build script with the marked lines filled in. */
    private fun buildScript(androidExtras: String = "", coldSpot: String = "") {
        File(project, "build.gradle.kts").writeText(
            resource("build.gradle.kts").replace("@ANDROID_EXTRAS@", androidExtras).replace("@COLDSPOT@", coldSpot),
        )
    }

    private fun run(vararg args: String): BuildResult = runner(*args).build()

    private fun runAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create().withProjectDir(project).withPluginClasspath().withEnvironment(localEnvironment()).withArguments(*args)

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixture/$name")) { "no fixture resource $name" }.use { it.readBytes().decodeToString() }
}
