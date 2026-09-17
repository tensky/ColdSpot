package id.tensky.coldspot.runtime

import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionData
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataWriter
import id.tensky.coldspot.shaded.org.jacoco.core.data.SessionInfo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * JaCoCo's agent before and after it starts. With offline instrumentation the agent starts when the first
 * instrumented class initialises, which in an app whose changed classes load late is long after ColdSpot.
 * [FakeRt] stands in for `org.jacoco.agent.rt.RT`: a static `getAgent()` that throws JaCoCo's own
 * `IllegalStateException` until told the agent has started.
 */
class RtAgentTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun notStarted() {
        FakeRt.running = null
        FakeRt.failure = null
    }

    @Test
    fun `an agent that has not started yet has no data and nothing to reset, and is asked again the next time`() {
        // given ColdSpot up before any instrumented class
        val agent = RtAgent.of(FakeRt::class.java)

        // when asked before the agent has started: no data, no failure
        assertContentEquals(ByteArray(0), agent.executionData())
        agent.reset()

        // and when the first instrumented class has started it
        val started = FakeAgent(byteArrayOf(1, 2, 3))
        FakeRt.running = started

        // then its data is what ColdSpot reads, and a reset reaches it
        assertContentEquals(byteArrayOf(1, 2, 3), agent.executionData())
        agent.reset()
        assertEquals(1, started.resets)
    }

    @Test
    fun `saving before the agent has started keeps what earlier launches saved`() {
        // given coverage from an earlier launch
        val earlier = ByteArrayOutputStream().also { out ->
            ExecutionDataWriter(out).apply {
                visitSessionInfo(SessionInfo("earlier", 1_000, 1_000))
                visitClassExecution(ExecutionData(0x1234L, "fx/A", booleanArrayOf(true, false)))
            }
        }.toByteArray()
        val dir = File(tmp.root, "coldspot")
        CoverageStore(dir, "p").save(earlier) { true }

        // when this launch saves before any instrumented class ran
        val store = CoverageStore(dir, "p").apply { load() }
        val saved = store.save(RtAgent.of(FakeRt::class.java).executionData()) { true }

        // then the earlier coverage is all there, and so is the moment it began
        assertContentEquals(booleanArrayOf(true, false), saved.get(0x1234L)?.probes)
        assertEquals(1_000L, store.collectingSince())
    }

    @Test
    fun `any other failure of the agent is a failure, not an agent that has not started`() {
        FakeRt.failure = UnsupportedOperationException("broken")
        val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> { RtAgent.of(FakeRt::class.java).executionData() }
        assertEquals("broken", failure.cause?.message)
    }

    @Test
    fun `this JVM carries no agent, which is what a build without ColdSpot looks like`() {
        assertNull(RtAgent.find())
    }

    /** `RT`: the static method JaCoCo has, behaving as JaCoCo's does. */
    object FakeRt {
        /** Not `agent`: its getter would be a second `getAgent()`, and reflection would find that one. */
        @Volatile
        var running: FakeAgent? = null

        @Volatile
        var failure: RuntimeException? = null

        @JvmStatic
        fun getAgent(): Any {
            failure?.let { throw it }
            return running ?: throw IllegalStateException("JaCoCo agent not started.")
        }
    }

    /** `IAgent`, the two methods ColdSpot calls. */
    class FakeAgent(private val data: ByteArray) {
        var resets = 0

        @Suppress("UNUSED_PARAMETER")
        fun getExecutionData(reset: Boolean): ByteArray = data

        fun reset() {
            resets++
        }
    }
}
