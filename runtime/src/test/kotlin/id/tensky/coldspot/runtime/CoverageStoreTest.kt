package id.tensky.coldspot.runtime

import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionData
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataReader
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataStore
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataWriter
import id.tensky.coldspot.shaded.org.jacoco.core.data.SessionInfo
import id.tensky.coldspot.shaded.org.jacoco.core.data.SessionInfoStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Coverage across launches and builds (DECISIONS.md "Coverage across builds"), with agent data made by hand in
 * JaCoCo's own format. Every test reads as **given** what was saved and what the agent has, **when** saved,
 * **then** this is in the file.
 */
class CoverageStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    private fun store(process: String = "app") = CoverageStore(dir, process).also { it.load() }

    @org.junit.Before
    fun setUp() {
        dir = File(tmp.root, "coldspot")
    }

    @Test
    fun `a save writes baseline plus the agent's data, merged by class id, and the next launch loads it back`() {
        // given a first launch that saw A's first probe, and a second launch whose agent saw A's second probe and B
        val first = store()
        first.save(agentData(session("s1", 1_000), data(ID_A, "fx/A", true, false)), shipped = { true })
        val second = store()

        // when
        val kept = second.save(agentData(session("s2", 2_000), data(ID_A, "fx/A", false, true), data(ID_B, "fx/B", true)), shipped = { true })

        // then
        assertContentEquals(booleanArrayOf(true, true), kept.get(ID_A).probes, "A's probes were not merged")
        assertContentEquals(booleanArrayOf(true), kept.get(ID_B).probes)
        val reloaded = store()
        assertContentEquals(booleanArrayOf(true, true), reloaded.baseline().get(ID_A).probes)
        assertEquals(1_000, reloaded.collectingSince(), "collecting since the earliest session on record")
    }

    @Test
    fun `carry-forward by class id keeps an unchanged class, starts a changed one fresh, and prunes the rest`() {
        // given a saved file with A (unchanged since), B (changed since: the new build ships id B2) and C (gone)
        store().save(agentData(session("s1", 1_000), data(ID_A, "fx/A", true), data(ID_B, "fx/B", true), data(ID_C, "fx/C", true)), shipped = { true })
        val next = store()

        // when the new build saves what its agent saw: the new B only
        val kept = next.save(agentData(session("s2", 2_000), data(ID_B2, "fx/B", false, true)), shipped = { it == ID_A || it == ID_B2 })

        // then A carried over, B started from the new build's probes alone, old B and C are gone
        assertEquals(setOf(ID_A, ID_B2), kept.contents.map { it.id }.toSet())
        assertContentEquals(booleanArrayOf(true), kept.get(ID_A).probes)
        assertContentEquals(booleanArrayOf(false, true), kept.get(ID_B2).probes)
        assertNull(kept.get(ID_B))
        assertNull(kept.get(ID_C))
        val onDisk = read(File(dir, "cov-app.exec"))
        assertEquals(setOf(ID_A, ID_B2), onDisk.contents.map { it.id }.toSet())
    }

    @Test
    fun `sessions accumulate, and collecting since is the earliest of them`() {
        // given three launches
        store().save(agentData(session("s1", 3_000), data(ID_A, "fx/A", true)), shipped = { true })
        store().save(agentData(session("s2", 1_000), data(ID_A, "fx/A", true)), shipped = { true })

        // when
        val third = store()

        // then
        assertEquals(1_000, third.collectingSince())
        assertNull(CoverageStore(File(tmp.root, "empty"), "app").also { it.load() }.collectingSince())
    }

    @Test
    fun `collecting since is known right after the first save, and a session saved ten times is recorded once`() {
        // given a fresh store, nothing saved yet
        val store = store()
        assertNull(store.collectingSince())

        // when the same launch saves again and again
        repeat(10) { store.save(agentData(session("s1", 5_000), data(ID_A, "fx/A", true)), shipped = { true }) }

        // then the session is on record once, from the first save on
        assertEquals(5_000, store.collectingSince())
        val infos = SessionInfoStore()
        File(dir, "cov-app.exec").inputStream().use { ExecutionDataReader(it).apply { setExecutionDataVisitor(ExecutionDataStore()); setSessionInfoVisitor(infos); read() } }
        assertEquals(1, infos.infos.size, "the session was written once per save")
    }

    @Test
    fun `delete all removes every process file and forgets the baseline`() {
        // given files for two processes
        store("app").save(agentData(session("s", 1), data(ID_A, "fx/A", true)), shipped = { true })
        store("app:remote").save(agentData(session("s", 1), data(ID_B, "fx/B", true)), shipped = { true })
        val app = store("app")

        // when
        app.deleteAll()

        // then
        assertEquals(emptyList(), dir.listFiles()?.filter { it.name.startsWith("cov-") })
        assertTrue(app.baseline().contents.isEmpty())
        assertNull(app.collectingSince())
        assertTrue(store("app").baseline().contents.isEmpty())
    }

    @Test
    fun `the file name is one per process, and the write leaves no temporary file behind`() {
        // when
        store("app:remote").save(agentData(session("s", 1), data(ID_A, "fx/A", true)), shipped = { true })

        // then
        assertEquals(listOf("cov-app_remote.exec"), dir.list()!!.sorted())
        assertFalse(dir.list()!!.any { it.endsWith(".tmp") })
    }

    @Test
    fun `a file that is not coverage data is an empty baseline, not a crash, and is moved aside rather than written over`() {
        // given garbage where the file should be
        dir.mkdirs()
        File(dir, "cov-app.exec").writeBytes(byteArrayOf(1, 2, 3))

        // when
        val store = store()

        // then
        assertTrue(store.baseline().contents.isEmpty())
        store.save(agentData(session("s", 1), data(ID_A, "fx/A", true)), shipped = { true })
        assertEquals(setOf(ID_A), read(File(dir, "cov-app.exec")).contents.map { it.id }.toSet())
        assertContentEquals(byteArrayOf(1, 2, 3), File(dir, "cov-app.exec.unreadable").readBytes(), "the garbage was not kept aside")
    }

    @Test
    fun `a file cut short keeps what can be read of it, and is moved aside rather than written over`() {
        // given a file whose last class was cut off half way, as a power loss can leave it
        store().save(agentData(session("s1", 1_000), data(ID_A, "fx/A", true, false), data(ID_B, "fx/B", true)), shipped = { true })
        val whole = File(dir, "cov-app.exec").readBytes()
        val cut = whole.copyOf(whole.size - 3)
        File(dir, "cov-app.exec").writeBytes(cut)

        // when the next launch saves
        val kept = CoverageStore(dir, "app").save(agentData(session("s2", 2_000), data(ID_A, "fx/A", false, true)), shipped = { true })

        // then A, read before the cut, is merged; B, cut off, is gone; the cut file itself is kept aside
        assertContentEquals(booleanArrayOf(true, true), kept.get(ID_A).probes, "what could be read of the file was dropped")
        assertNull(kept.get(ID_B))
        assertEquals(1_000, store().collectingSince(), "the session read before the cut was dropped")
        assertContentEquals(cut, File(dir, "cov-app.exec.unreadable").readBytes(), "the cut file was not kept aside")
    }

    @Test
    fun `a file that cannot be read is never written over, and every save fails until it can be read`() {
        // given a first launch's file, which the next launch cannot read
        store().save(agentData(session("s1", 1_000), data(ID_A, "fx/A", true, false)), shipped = { true })
        val file = File(dir, "cov-app.exec")
        val before = file.readBytes()
        assertTrue(file.setReadable(false), "cannot take the read permission away on this machine")
        val next = CoverageStore(dir, "app")
        try {
            // when it loads, and when it saves
            assertFailsWith<IOException> { next.load() }
            val failure = assertFailsWith<IOException> { next.save(agentData(session("s2", 2_000), data(ID_A, "fx/A", false, true)), shipped = { true }) }
            assertTrue(failure.message!!.startsWith("the saved coverage cannot be read ("), "a reason the overview can show: ${failure.message}")
        } finally {
            file.setReadable(true)
        }

        // then the file is as it was, and once it can be read, the next save keeps what it holds
        assertContentEquals(before, file.readBytes(), "a save wrote over a file it could not read")
        assertFalse(File(dir, "cov-app.exec.unreadable").exists(), "a file that could not be read was taken for garbage")
        val kept = next.save(agentData(session("s2", 2_000), data(ID_A, "fx/A", false, true)), shipped = { true })
        assertContentEquals(booleanArrayOf(true, true), kept.get(ID_A).probes)
    }

    @Test
    fun `when saving fails the analysis has what the app ran, and the baseline when it could be read, and writes nothing`() {
        // given a first launch's file, which the next launch cannot read
        store().save(agentData(session("s1", 1_000), data(ID_A, "fx/A", true, false), data(ID_B, "fx/B", true)), shipped = { true })
        val file = File(dir, "cov-app.exec")
        val before = file.readBytes()
        assertTrue(file.setReadable(false), "cannot take the read permission away on this machine")
        val agent = agentData(session("s2", 2_000), data(ID_A, "fx/A", false, true), data(ID_C, "fx/C", true), data(ID_B2, "fx/B", true))
        val unreadable = CoverageStore(dir, "app")
        val data = try {
            assertFailsWith<IOException> { unreadable.saveForAnalysis(agent, setOf(ID_A, ID_B), setOf("fx/A", "fx/B")) }
            // when analysed without saving
            unreadable.unsavedForAnalysis(agent, setOf(ID_A, ID_B), setOf("fx/A", "fx/B"))
        } finally {
            file.setReadable(true)
        }

        // then what this process ran, shipped ids only, plus the class it ran under another id (stale), and nothing written
        assertContentEquals(booleanArrayOf(false, true), data.get(ID_A).probes, "the unreadable baseline was taken for part of the data")
        assertNull(data.get(ID_C), "an id no shipped class has")
        assertContentEquals(booleanArrayOf(true), data.get(ID_B2).probes, "what makes fx/B stale is gone")
        assertContentEquals(before, file.readBytes(), "the file was written")
        // and with a baseline that could be read, the baseline is in it too, and still nothing is written
        val readable = CoverageStore(dir, "app").also { it.load() }
        val merged = readable.unsavedForAnalysis(agent, setOf(ID_A, ID_B), setOf("fx/A", "fx/B"))
        assertContentEquals(booleanArrayOf(true, true), merged.get(ID_A).probes)
        assertContentEquals(before, file.readBytes(), "the file was written")
    }

    @Test
    fun `a save before the baseline was loaded, as on a crash early in the launch, keeps what earlier launches saved`() {
        // given two earlier launches' coverage in the file
        store().save(agentData(session("s1", 1_000), data(ID_A, "fx/A", true, false), data(ID_B, "fx/B", true)), shipped = { true })
        store().save(agentData(session("s2", 2_000), data(ID_C, "fx/C", true)), shipped = { true })
        val launch = CoverageStore(dir, "app") // load() not called: the background thread has not got to it

        // when this launch saves
        val kept = launch.save(agentData(session("s3", 3_000), data(ID_A, "fx/A", false, true)), shipped = { true })

        // then nothing an earlier launch saved is lost, in what the save returns and in the file
        assertEquals(setOf(ID_A, ID_B, ID_C), kept.contents.map { it.id }.toSet(), "the save lost earlier launches' classes")
        assertContentEquals(booleanArrayOf(true, true), kept.get(ID_A).probes)
        val onDisk = read(File(dir, "cov-app.exec"))
        assertEquals(setOf(ID_A, ID_B, ID_C), onDisk.contents.map { it.id }.toSet(), "the file lost earlier launches' classes")
        assertEquals(1_000, store().collectingSince(), "the file lost earlier launches' sessions")
        assertFalse(launch.load(), "the save left the baseline to be loaded again")
    }

    @Test
    fun `a crash while the background thread is loading the file waits for the load, and keeps what earlier launches saved`() {
        // given a first launch's file, and the next launch's background thread in the middle of loading it
        store().save(agentData(session("s1", 1_000), data(ID_A, "fx/A", true, false), data(ID_B, "fx/B", true)), shipped = { true })
        val file = File(dir, "cov-app.exec")
        val before = file.readBytes()
        val launch = CoverageStore(dir, "app")
        var saved: ExecutionDataStore? = null
        val crash = Thread { saved = launch.save(agentData(session("s2", 2_000), data(ID_A, "fx/A", false, true)), shipped = { true }) }
        synchronized(launch) { // what the background thread holds while it loads
            // when the app crashes on another thread, which saves
            crash.start()

            // then the save waits, and writes nothing meanwhile
            awaitBlocked(crash)
            assertContentEquals(before, file.readBytes(), "the crash's save wrote while the file was being loaded")
            launch.load()
        }
        crash.join(10_000)
        assertContentEquals(booleanArrayOf(true, true), saved!!.get(ID_A).probes)
        assertEquals(setOf(ID_A, ID_B), read(file).contents.map { it.id }.toSet(), "the crash's save lost the first launch's classes")
    }

    @Test
    fun `a save waits for another under way on another thread, so that the two never write the file at once`() {
        // given a first launch's file, and a periodic save of the next launch paused half way, choosing what to keep
        store().save(agentData(session("s1", 1_000), data(ID_A, "fx/A", true)), shipped = { true })
        val launch = CoverageStore(dir, "app")
        val halfWay = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val periodic = Thread {
            launch.save(agentData(session("s2", 2_000), data(ID_B, "fx/B", true)), shipped = { halfWay.countDown(); goOn.await(); true })
        }
        periodic.start()
        assertTrue(halfWay.await(10, TimeUnit.SECONDS))

        // when a crash saves on another thread
        val crash = Thread { launch.save(agentData(session("s2", 2_000), data(ID_B, "fx/B", true), data(ID_C, "fx/C", true)), shipped = { true }) }
        crash.start()

        // then it waits for the periodic save to finish, and the file ends with everything
        awaitBlocked(crash)
        goOn.countDown()
        periodic.join(10_000)
        crash.join(10_000)
        assertEquals(setOf(ID_A, ID_B, ID_C), read(File(dir, "cov-app.exec")).contents.map { it.id }.toSet())
    }

    /** Waits until [thread] waits for a monitor; fails when it ends, or runs on, instead. */
    private fun awaitBlocked(thread: Thread) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (thread.state != Thread.State.BLOCKED) {
            if (thread.state == Thread.State.TERMINATED) fail("the save went ahead instead of waiting for the store")
            if (System.nanoTime() > until) fail("the save neither waited for the store nor ended: ${thread.state}")
            Thread.sleep(1)
        }
    }

    private fun data(id: Long, name: String, vararg probes: Boolean) = ExecutionData(id, name, probes)

    private fun session(id: String, start: Long) = SessionInfo(id, start, start + 1)

    /** What the agent's `getExecutionData(false)` returns: session info, then the classes. */
    private fun agentData(session: SessionInfo, vararg data: ExecutionData): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ExecutionDataWriter(out)
        writer.visitSessionInfo(session)
        for (d in data) writer.visitClassExecution(d)
        return out.toByteArray()
    }

    private fun read(file: File): ExecutionDataStore {
        val store = ExecutionDataStore()
        file.inputStream().use { ExecutionDataReader(it).apply { setExecutionDataVisitor(store); setSessionInfoVisitor(SessionInfoStore()); read() } }
        return store
    }

    private companion object {
        const val ID_A = 1L
        const val ID_B = 2L
        const val ID_B2 = 22L
        const val ID_C = 3L
    }
}
