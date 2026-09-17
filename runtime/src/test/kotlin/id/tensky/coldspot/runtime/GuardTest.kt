package id.tensky.coldspot.runtime

import org.junit.After
import org.junit.Test
import java.io.IOException
import java.util.MissingResourceException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ColdSpot's own code never crashes the app (DECISIONS.md "Runtime compatibility", Guard.kt): what it throws becomes an
 * error the screen shows, a VirtualMachineError alone goes on. Every test reads as **given** what ColdSpot's code
 * throws, **when** caught, **then** this is what is thrown on, or said.
 */
class GuardTest {
    @After
    fun noProblemsLeft() = Problems.clear()

    @Test
    fun `a missing class, a class that cannot initialise, and anything else ColdSpot throws are caught`() {
        // given what a build missing part of ColdSpot, or ColdSpot's own bug, throws
        val thrown = listOf(
            NoClassDefFoundError("Failed resolution of: Lid/tensky/coldspot/runtime/CoverageStore\$Missing;"),
            ExceptionInInitializerError(MissingResourceException("Can't find bundle", "jacoco", "")),
            UnsatisfiedLinkError("no native part"),
            AssertionError("a check of ColdSpot's own"),
            IllegalStateException("a bug"),
            IOException("a file"),
        )

        // when each is thrown by guarded code, then each is caught and handed on, as it was
        for (error in thrown) assertSame(error, catching({ throw error }) { it })
    }

    @Test
    fun `a VirtualMachineError is thrown on, never caught`() {
        // given an app out of memory, or out of stack, while ColdSpot's code runs
        // when, then it is the process's, and goes on to the app's own handler
        assertFailsWith<OutOfMemoryError> { catching({ throw OutOfMemoryError("Failed to allocate") }) { "caught" } }
        assertFailsWith<StackOverflowError> { catching({ throw StackOverflowError() }) { "caught" } }
        assertFailsWith<InternalError> { catching({ throw InternalError("the VM") }) { "caught" } }
    }

    @Test
    fun `what failed says what was thrown, and the root cause when the error says nothing itself`() {
        // given a missing class, a class whose initialiser failed, a chain without a message, and one that loops
        val missing = NoClassDefFoundError("Failed resolution of: Lid/tensky/coldspot/shaded/org/jacoco/core/JaCoCo\$Missing;")
            .apply { initCause(ClassNotFoundException("Didn't find class on path: DexPathList[[zip file \"/data/app/base.apk\"]]")) }
        val uninitialised = ExceptionInInitializerError(IllegalStateException(MissingResourceException("Can't find bundle for base name id.tensky.coldspot.shaded.org.jacoco.core.jacoco", "", "")))
        val first = RuntimeException(null as String?)
        val second = RuntimeException(null as String?, first)
        first.initCause(second)

        // then the message, and the dex path noise of the cause only in the log
        assertEquals("NoClassDefFoundError: Failed resolution of: Lid/tensky/coldspot/shaded/org/jacoco/core/JaCoCo\$Missing;", describe(missing))
        assertEquals(
            "ExceptionInInitializerError, caused by MissingResourceException: Can't find bundle for base name id.tensky.coldspot.shaded.org.jacoco.core.jacoco",
            describe(uninitialised),
        )
        assertEquals("RuntimeException, caused by RuntimeException", describe(first))
    }

    @Test
    fun `a failure with no state of its own is listed among the errors, the latest five`() {
        // given seven failures of ColdSpot's code, the bubble's among them
        Problems.record("showing the bubble", NoClassDefFoundError("Failed resolution of: Lid/tensky/coldspot/runtime/BubbleView;"))
        for (i in 2..7) Problems.record("the dump broadcast $i", IllegalStateException("bug $i"))

        // then the latest five, oldest first, each saying what failed and why
        assertEquals((3..7).map { "The dump broadcast $it failed: IllegalStateException: bug $it" }, Problems.all())
        Problems.clear()
        Problems.record("showing the bubble", NoClassDefFoundError("Failed resolution of: Lid/tensky/coldspot/runtime/BubbleView;"))
        assertEquals(listOf("Showing the bubble failed: NoClassDefFoundError: Failed resolution of: Lid/tensky/coldspot/runtime/BubbleView;"), Problems.all())
    }

    @Test
    fun `a failure that keeps coming back is new once, until another failure or a success`() {
        // given the saves of an app whose saved file cannot be read, failing every ten seconds
        val saves = RecurringFailure()
        val unreadable = "the saved coverage cannot be read (cov-app.exec: open failed: EACCES (Permission denied))"

        // then the first is new, and its stack trace goes to the log; the same failure again is not
        assertTrue(saves.isNew(unreadable))
        assertFalse(saves.isNew(unreadable))
        assertFalse(saves.isNew(unreadable))
        // another failure is new, and so is the first after a save that succeeded
        assertTrue(saves.isNew("the saved coverage cannot be written (no space left)"))
        assertTrue(saves.isNew(unreadable))
        saves.reset()
        assertTrue(saves.isNew(unreadable))
    }

    @Test
    fun `a startup or an analysis that failed is the error state, with what failed, and the actions under it`() {
        // given the reports ColdSpot makes when it could not start, and when its analysis failed
        val startup = failedReport(listOf("ColdSpot could not start: NoClassDefFoundError: Failed resolution of: Lid/tensky/coldspot/runtime/CoverageStore\$Missing;"), null)
        val analysis = failedReport(listOf("The analysis failed: NoClassDefFoundError: x", "Showing the bubble failed: IllegalStateException: y"), 1_000)

        // when shown
        val startupItems = overviewItems(overview(startup, 0, Reports.time), emptySet())
        val analysisItems = overviewItems(overview(analysis, 0, Reports.time), emptySet())

        // then the error state, which colours nothing, says it all, and the bubble switch and the reset are still there
        assertEquals(
            listOf(OverviewItem.Message(failed = true, "ColdSpot has nothing to show", listOf("ColdSpot could not start: NoClassDefFoundError: Failed resolution of: Lid/tensky/coldspot/runtime/CoverageStore\$Missing;")), OverviewItem.Actions),
            startupItems,
        )
        assertEquals(
            listOf(OverviewItem.Message(failed = true, "ColdSpot has nothing to show", listOf("The analysis failed: NoClassDefFoundError: x", "Showing the bubble failed: IllegalStateException: y")), OverviewItem.Actions),
            analysisItems,
        )
    }
}
