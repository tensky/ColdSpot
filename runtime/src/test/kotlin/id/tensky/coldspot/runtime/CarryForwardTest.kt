package id.tensky.coldspot.runtime

import id.tensky.coldspot.manifest.Manifest
import id.tensky.coldspot.manifest.ModuleManifest
import id.tensky.coldspot.manifest.ShippedClass
import id.tensky.coldspot.runtime.Fixtures.Plain
import id.tensky.coldspot.runtime.LineState.ERROR
import id.tensky.coldspot.runtime.LineState.EXECUTED
import id.tensky.coldspot.runtime.LineState.NOT_EXECUTED
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage carried across builds (Finding 9) meets the stale-bytes check. JaCoCo calls a class stale when the data
 * it is given holds nothing under the shipped class's id but something under its name, whatever the id; so what an
 * earlier build saved under an old id must be gone before the analysis, or every class changed since, and not run
 * since, would be an error. Only the current process running bytes that are not the shipped ones may make a class
 * stale.
 *
 * Builds and launches as the runtime lives them. A [Build] is what an APK ships: its manifest, its class bytes, and
 * the ids and names the runtime reads from them. A [Launch] is a process of it, with an agent of its own, which loads
 * what earlier launches saved and analyses as the runtime does, through [CoverageStore.saveForAnalysis]. Greeter is
 * the class a change touches; Pricing, in another file, no build changes. Every test reads as **given** the builds
 * and launches so far, **when** a launch analyses, **then** these are the states.
 */
class CarryForwardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Greeter as the first build compiled it, and as the second compiled it after an edit: one name, two ids. */
    private val greeterBefore = Fixtures.classBytes(GREETER, "Greeter.kt", listOf(Plain(10), Plain(11)))
    private val greeterAfter = Fixtures.classBytes(GREETER, "Greeter.kt", listOf(Plain(10), Plain(11), Plain(12)))

    /** The same bytes in every build, so the same id. */
    private val pricing = Fixtures.classBytes(PRICING, "Pricing.kt", listOf(Plain(30)))

    private val first = Build(greeterBefore, changedLines = listOf(10, 11))
    private val second = Build(greeterAfter, changedLines = listOf(10, 11, 12))

    @Test
    fun `a class changed since the last build, and not run since, is not executed, never an error, and run, it is executed`() {
        // given the first build's launch, which ran Greeter and Pricing and saved them
        Launch(first).apply {
            run(GREETER)
            run(PRICING)
            assertEquals(listOf(EXECUTED, EXECUTED), analyse().states(GREETER_FILE, 10, 11))
        }

        // when the second build, Greeter edited, is launched, and nothing of it has run yet
        val launch = Launch(second)
        val notRun = launch.analyse()

        // then what the first build saved under Greeter's old id was in the file this launch loaded, to be misread...
        assertNotNull(launch.loaded.get(idOf(greeterBefore)), "the old id must be in what the launch loaded, or this test proves nothing")
        // ...and Greeter is not executed, not an error, while Pricing, unchanged, carries its coverage forward
        assertEquals(listOf(NOT_EXECUTED, NOT_EXECUTED, NOT_EXECUTED), notRun.states(GREETER_FILE, 10, 11, 12))
        assertEquals(FileStatus.NOT_EXECUTED, notRun.file(GREETER_FILE).status)
        assertEquals(listOf(EXECUTED), notRun.states(PRICING_FILE, 30), "an unchanged class keeps its coverage across the build")
        assertNothingStale(notRun)

        // and when Greeter runs in this process, it is executed
        launch.run(GREETER)
        val run = launch.analyse()
        assertEquals(listOf(EXECUTED, EXECUTED, EXECUTED), run.states(GREETER_FILE, 10, 11, 12))
        assertNothingStale(run)

        // and the launch after, which does not run it, carries that forward under Greeter's new id
        val next = Launch(second).analyse()
        assertEquals(listOf(EXECUTED, EXECUTED, EXECUTED), next.states(GREETER_FILE, 10, 11, 12))
        assertNothingStale(next)
    }

    @Test
    fun `only this process running bytes that are not the shipped ones makes a class stale, and that is never saved`() {
        // given the first build's launch, which ran Greeter and saved it, and the second build installed
        Launch(first).apply { run(GREETER); analyse() }

        // when a launch of the second build runs Greeter as the first build compiled it: the bytes that run are not the shipped ones
        val stale = Launch(second).apply { run(GREETER, bytes = greeterBefore) }.analyse()

        // then Greeter is in error, never red, and named as the stale class
        assertEquals(listOf(ERROR, ERROR, ERROR), stale.states(GREETER_FILE, 10, 11, 12))
        assertEquals(listOf(ShippedClass("app", GREETER)), stale.staleClasses)

        // and what ran under the old id was not saved, so the launch after, which runs nothing, has nothing stale
        val nextLaunch = Launch(second)
        assertNull(nextLaunch.loaded.get(idOf(greeterBefore)), "what ran under the old id was saved")
        val next = nextLaunch.analyse()
        assertEquals(listOf(NOT_EXECUTED, NOT_EXECUTED, NOT_EXECUTED), next.states(GREETER_FILE, 10, 11, 12))
        assertNothingStale(next)
    }

    @Test
    fun `an old id still in the file, because a save kept everything before the shipped classes were known, is dropped by the analysis all the same`() {
        // given the first build's launch, which ran Greeter and saved it, and a launch of the second build whose one
        // save came before it knew its shipped classes, as the crash handler's may, and so kept every id
        Launch(first).apply { run(GREETER); analyse() }
        Launch(second).store.save(Fixtures.execData(ExecutionDataStore())) { true }

        // when the next launch analyses without having run Greeter
        val launch = Launch(second)
        val report = launch.analyse()

        // then the old id was still in the file, and Greeter is not executed all the same, not an error
        assertNotNull(launch.loaded.get(idOf(greeterBefore)), "the save that kept everything must have left the old id, or this test proves nothing")
        assertEquals(listOf(NOT_EXECUTED, NOT_EXECUTED, NOT_EXECUTED), report.states(GREETER_FILE, 10, 11, 12))
        assertNothingStale(report)
    }

    /** What an APK ships, and what the runtime reads from it (`Runtime.loadManifest`): the shipped classes' ids and names. */
    private inner class Build(greeter: ByteArray, changedLines: List<Int>) {
        val classes = mapOf(GREETER to greeter, PRICING to pricing)
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(GREETER_FILE, changedLines, ""), Manifest.ChangedFile(PRICING_FILE, listOf(30), "")),
            Fixtures.module(
                "app",
                ModuleManifest.ModuleFile(GREETER_FILE, listOf(GREETER), emptyList(), emptyList()),
                ModuleManifest.ModuleFile(PRICING_FILE, listOf(PRICING), emptyList(), emptyList()),
            ),
        )
        val ids = classes.map { (name, bytes) -> classId(bytes, name)!! }.toSet()
        val names = classes.keys
    }

    /** A process of [build]: it loads what every earlier launch saved, in the one file they share, and has an agent of its own. */
    private inner class Launch(private val build: Build) {
        val store = CoverageStore(File(tmp.root, "coldspot"), "app").apply { load() }

        /** What earlier launches had saved when this one began. */
        val loaded: ExecutionDataStore = store.baseline()

        /** The classes this process has run so far, name to the bytes that ran: its agent's data is theirs, and nothing else. */
        private val ran = LinkedHashMap<String, ByteArray>()

        fun run(name: String, bytes: ByteArray = build.classes.getValue(name)) {
            ran[name] = bytes
        }

        /** An analysis as the runtime makes one: saved for the analysis, with this process's agent data, then analysed. */
        fun analyse(): Report {
            val agentData = Fixtures.execData(Fixtures.execute(ran, calls = ran.keys.associateWith { true }))
            return Fixtures.analyze(build.manifest, store.saveForAnalysis(agentData, build.ids, build.names), build.classes)
        }
    }

    private fun Report.file(path: String): FileReport = files.single { it.path == path }

    private fun Report.states(path: String, vararg lines: Int): List<LineState?> = lines.map { file(path).lines[it] }

    private fun idOf(bytes: ByteArray): Long = classId(bytes, GREETER)!!

    private fun assertNothingStale(report: Report) {
        assertEquals(emptyList(), report.staleClasses, "a stale class where none ran")
        assertTrue(report.classes.none { it.noMatch }, "noMatch: ${report.classes.filter { it.noMatch }.map { it.shipped }}")
        assertTrue(report.files.none { it.status == FileStatus.ERROR }, "a file in error: ${report.files.filter { it.status == FileStatus.ERROR }.map { it.path }}")
        assertEquals(emptyList(), report.errors)
    }

    private companion object {
        const val GREETER = "fx/Greeter"
        const val GREETER_FILE = "app/src/main/java/fx/Greeter.kt"
        const val PRICING = "fx/Pricing"
        const val PRICING_FILE = "app/src/main/java/fx/Pricing.kt"
    }
}
