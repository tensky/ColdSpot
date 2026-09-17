package id.tensky.coldspot.runtime

import java.lang.reflect.InvocationTargetException

/**
 * JaCoCo's agent as this runtime needs it: the probes fired so far, in JaCoCo's exec format, and a reset.
 * Behind an interface so that the analysis and the store can be driven by test data; the real one, [RtAgent],
 * reaches the agent by reflection (Finding 1: never `$jacocoData`, never a compile dependency on the agent jar).
 */
public interface Agent {
    /** The session info and the execution data of every class that ran probes so far, as `ExecutionDataWriter` writes them. */
    public fun executionData(): ByteArray

    /** Clears every probe in memory; the next [executionData] starts from nothing. */
    public fun reset()
}

/**
 * `org.jacoco.agent.rt.RT.getAgent()`, reached by reflection; null when the APK carries no agent, which is a build
 * without ColdSpot.
 *
 * With offline instrumentation nobody starts the agent: it starts itself when the first instrumented class
 * initialises, and until then `getAgent()` throws `IllegalStateException("JaCoCo agent not started.")`. ColdSpot
 * starts before the application does, and in an app whose changed classes load late that is well before the
 * agent. Not started is therefore not an error: nothing instrumented has run, so there is no execution data yet
 * and nothing to reset (Finding 2: absent is never executed). Every call asks again until the agent is there.
 */
internal object RtAgent {
    fun find(): Agent? {
        val rt = try {
            Class.forName("org.jacoco.agent.rt.RT")
        } catch (e: ClassNotFoundException) {
            return null
        } catch (e: LinkageError) {
            return null
        }
        return of(rt)
    }

    /** The agent behind [rt], a class with JaCoCo's static `getAgent()`. */
    fun of(rt: Class<*>): Agent {
        val getAgent = rt.getMethod("getAgent")
        return object : Agent {
            @Volatile
            private var started: Any? = null

            private fun agent(): Any? = started ?: try {
                getAgent.invoke(null).also { started = it }
            } catch (e: InvocationTargetException) {
                if (e.cause is IllegalStateException) null else throw e
            }

            override fun executionData(): ByteArray {
                val agent = agent() ?: return ByteArray(0)
                return agent.javaClass.getMethod("getExecutionData", Boolean::class.javaPrimitiveType).invoke(agent, false) as ByteArray
            }

            override fun reset() {
                val agent = agent() ?: return
                agent.javaClass.getMethod("reset").invoke(agent)
            }
        }
    }
}
