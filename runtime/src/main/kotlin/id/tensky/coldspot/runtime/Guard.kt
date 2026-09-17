package id.tensky.coldspot.runtime

import android.util.Log

/*
 * ColdSpot runs inside the host app's process, and its own code never takes that process down (DECISIONS.md "Runtime
 * compatibility"). Every way into that code is guarded: the startup provider, every task on its background thread
 * (loading, saving, the analysis, the dump, a reset), the callbacks it registers with the app (activity lifecycle,
 * memory pressure), its broadcast receivers, the bubble, its public API, and its screens. What such code throws is
 * logged, and shown: by the state it belongs to (a startup that failed, an analysis that failed, a save that failed,
 * each where the overview already says so), or else among the overview's errors ([Problems]). A VirtualMachineError
 * (out of memory, a stack overflow) is the exception, and is thrown on: it is the whole process's, not ColdSpot's.
 * Nor is the app's own code guarded, a callback it hands [ColdSpot.analyze] for one: its failures are its own.
 */

/** [block]'s result, or [onError]'s when it throws anything but a [VirtualMachineError], which is thrown on. */
internal inline fun <T> catching(block: () -> T, onError: (Throwable) -> T): T =
    try {
        block()
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        onError(e)
    }

/** [catching], with what [block] threw logged: for a failure the screens show where it happened. */
internal inline fun <T> logged(what: String, fallback: (Throwable) -> T, block: () -> T): T =
    catching(block) { e ->
        Log.e(ColdSpot.TAG, "$what failed; the app goes on without it", e)
        fallback(e)
    }

/** [logged], and recorded for the overview to list among its errors ([Problems]): for a failure with no state of its own. */
internal inline fun <T> guarded(what: String, fallback: (Throwable) -> T, block: () -> T): T =
    logged(what, { e -> Problems.record(what, e); fallback(e) }, block)

/** [guarded] for code that returns nothing. */
internal inline fun guarded(what: String, block: () -> Unit): Unit = guarded(what, {}, block)

/**
 * A failure that keeps coming back, a save that fails every ten seconds say: its stack trace belongs in the log once,
 * then a line each time it fails the same way again. Another failure, or one after a success, is new.
 */
internal class RecurringFailure {
    private var last: String? = null

    /** Whether [reason] is not the failure seen last; from now on it is. */
    @Synchronized
    fun isNew(reason: String): Boolean = (reason != last).also { last = reason }

    /** A success: whatever fails next is new. */
    @Synchronized
    fun reset() {
        last = null
    }
}

/** What ColdSpot caught in its own code in this process, the latest [KEPT], oldest first: the overview shows them. */
internal object Problems {
    private const val KEPT = 5
    private val recorded = ArrayList<String>()

    fun record(what: String, error: Throwable) {
        synchronized(recorded) {
            recorded += "${what.replaceFirstChar { it.uppercase() }} failed: ${describe(error)}"
            while (recorded.size > KEPT) recorded.removeAt(0)
        }
    }

    fun all(): List<String> = synchronized(recorded) { recorded.toList() }

    /** For tests: a process starts with none. */
    fun clear() {
        synchronized(recorded) { recorded.clear() }
    }
}

/**
 * [error] in a line: `NoClassDefFoundError: Failed resolution of: Lid/tensky/Foo;`. One without a message of its own
 * says what its root cause says, as an ExceptionInInitializerError does: `ExceptionInInitializerError, caused by
 * MissingResourceException: Can't find bundle for base name ...`. The whole chain is in the log.
 */
internal fun describe(error: Throwable): String {
    fun one(e: Throwable) = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    if (error.message != null) return one(error)
    // at most a few links: a chain can loop, since only a throwable's own cause is refused as itself
    val cause = generateSequence(error.cause) { it.cause }.take(MAX_CAUSES).lastOrNull() ?: return one(error)
    return "${one(error)}, caused by ${one(cause)}"
}

private const val MAX_CAUSES = 16
