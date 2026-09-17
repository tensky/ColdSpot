package id.tensky.coldspot.plugin

import id.tensky.coldspot.diff.FixtureRepo
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ColdSpot on a Hilt application under Gradle TestKit, shaped like the sample app: `:app` with a
 * `@HiltAndroidApp` application and an `@AndroidEntryPoint` receiver, injecting a class from `:lib`, both
 * modules under Hilt and ColdSpot, in a git repository (diff's [FixtureRepo]) with the base published as
 * `origin/main`.
 *
 * Hilt's aggregating task appends its own javac output to the module's classes (Finding 6: ColdSpot's
 * transform runs before Hilt's). Its input is the module's classes as a compile classpath, which Gradle compares
 * by ABI, so after an edit that changes the ABI it runs again, and then it compiles the application's
 * `_GeneratedInjector` a second time, with `-parameters` where KSP's copy was not: the same class, different
 * bytes, in two class directories. The test makes such an edit, and reports the scenario not occurring as a
 * failed precondition, never as a pass; `testbeds/sample/hilt-incremental.sh` does the same against the sample
 * app, and [ColdSpotBundleTaskTest] makes the two class directories deterministically.
 *
 * Minutes rather than seconds, so a [SlowTest]: `slowTest` runs it, `test` and `check` do not.
 */
@Category(SlowTest::class)
class ColdSpotHiltTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var project: File
    private lateinit var repo: FixtureRepo
    private lateinit var sdkDir: File

    @Before
    fun fixture() {
        val sdk = System.getProperty("coldspot.test.sdkDir").orEmpty()
        assumeTrue("needs an Android SDK: set ANDROID_HOME or sdk.dir in local.properties", sdk.isNotEmpty() && File(sdk).isDirectory)
        sdkDir = File(sdk)
        project = tmp.newFolder("hilt")
        repo = FixtureRepo(project)
        val hilt = System.getProperty("coldspot.test.hiltVersion")
        File(project, "local.properties").writeText("sdk.dir=$sdk\n")
        repo.write(".gitignore", "build/\n.gradle/\nlocal.properties\n")
        repo.write("settings.gradle.kts", RuntimeStub.inSettings(resource("hilt/settings.gradle.kts"), tmp.newFolder("repo")))
        repo.write("app/build.gradle.kts", resource("hilt/build.gradle.kts").replace("@HILT@", hilt))
        repo.write("lib/build.gradle.kts", resource("hilt/lib.gradle.kts").replace("@HILT@", hilt))
        repo.write("app/src/main/AndroidManifest.xml", resource("AndroidManifest.xml"))
        repo.write("app/src/main/kotlin/fixture/app/App.kt", "package fixture.app\n\nimport android.app.Application\nimport dagger.hilt.android.HiltAndroidApp\n\n@HiltAndroidApp\nclass App : Application()\n")
        repo.write(RECEIVER, receiver(extraFunction = false))
        repo.write(GREETER, greeter("hello"))
    }

    @After
    fun closeRepo() = repo.close()

    @Test
    fun `after an edit that changes the ABI, Hilt compiles the application's injector a second time, and the coverage build still succeeds`() {
        // given a first coverage build of the committed base
        val base = repo.commitAll("base")
        repo.updateRef("refs/remotes/origin/main", base)
        run(":app:assembleCoverage")

        // when a top-level function is added to the app module, the injected class edited too, and the build repeats without a clean
        repo.write(RECEIVER, receiver(extraFunction = true))
        repo.write(GREETER, greeter("hello again"))
        val output = run(":app:assembleCoverage", "--info").output

        // then Hilt did compile the injector twice, and the bundle read the first copy and said so
        val duplicate = Regex("ColdSpot: $INJECTOR is compiled differently in .* and .*; the first is what the build uses")
        if (!duplicate.containsMatchIn(output)) {
            fail(
                "PRECONDITION NOT MET: Hilt did not regenerate the class. The incremental build succeeded, but its --info log has no " +
                    "'ColdSpot: $INJECTOR is compiled differently in ...' line, so the scenario this test exists for did not occur and " +
                    "nothing is known about ColdSpot's handling of it. ColdSpot's lines:\n${output.lines().filter { "ColdSpot" in it }.joinToString("\n")}",
            )
        }
        // and the APK holds exactly one copy of it, and the edited classes shipped
        val apk = File(project, "app/build/outputs/apk/coverage/app-coverage.apk")
        assertTrue(apk.isFile, "no APK at $apk")
        assertEquals(1, dexClassCount(apk, "L$INJECTOR;"), "copies of L$INJECTOR; in the APK's dex")
        val entries = ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        assertTrue("assets/coldspot/app/classes/fixture/app/ReceiverKt.class" in entries, "the edited entry point's file did not ship: ${entries.filter { it.startsWith("assets/coldspot/") }}")
        assertTrue("assets/coldspot/lib/classes/fixture/lib/Greeter.class" in entries, "the edited library class did not ship: ${entries.filter { it.startsWith("assets/coldspot/") }}")
    }

    /** How many classes of [descriptor] (`Lfixture/app/App_GeneratedInjector;`) the APK's dex files define, by the SDK's dexdump, exact match. */
    private fun dexClassCount(apk: File, descriptor: String): Int {
        val dexdump = File(sdkDir, "build-tools").listFiles().orEmpty().map { File(it, "dexdump") }.filter { it.canExecute() }.maxByOrNull { it.parentFile.name }
            ?: fail("no dexdump under $sdkDir/build-tools: build-tools are needed to read the APK's dex")
        val dir = tmp.newFolder("dex")
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { Regex("classes\\d*\\.dex").matches(it.name) }.forEach { entry ->
                File(dir, entry.name).outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
            }
        }
        val needle = "Class descriptor  : '$descriptor'"
        return dir.listFiles().orEmpty().sumOf { dex ->
            val process = ProcessBuilder(dexdump.path, dex.path).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text.lineSequence().count { needle in it }
        }
    }

    private fun run(vararg args: String): BuildResult =
        GradleRunner.create().withProjectDir(project).withPluginClasspath().withEnvironment(localEnvironment()).withArguments(*args).build()

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixture/$name")) { "no fixture resource $name" }.use { it.readBytes().decodeToString() }

    private companion object {
        const val GREETER = "lib/src/main/kotlin/fixture/lib/Greeter.kt"
        const val RECEIVER = "app/src/main/kotlin/fixture/app/Receiver.kt"
        const val INJECTOR = "fixture/app/App_GeneratedInjector"

        fun greeter(word: String): String = "package fixture.lib\n\nimport javax.inject.Inject\n\nclass Greeter @Inject constructor() {\n    fun greet(): String = \"$word\"\n}\n"

        /** An entry point, and with [extraFunction] a new top-level function next to it: an ABI change of the app module. */
        fun receiver(extraFunction: Boolean): String = """
            |package fixture.app
            |
            |import android.content.BroadcastReceiver
            |import android.content.Context
            |import android.content.Intent
            |import dagger.hilt.android.AndroidEntryPoint
            |import fixture.lib.Greeter
            |import javax.inject.Inject
            |
            |@AndroidEntryPoint
            |class Receiver : BroadcastReceiver() {
            |    @Inject lateinit var greeter: Greeter
            |
            |    override fun onReceive(context: Context, intent: Intent) {
            |        greeter.greet()
            |    }
            |}
            |${if (extraFunction) "\n/** Added by the test: an edit that changes the module's ABI. */\nfun receiverCheck(): String = \"checked\"\n" else ""}
        """.trimMargin()
    }
}
