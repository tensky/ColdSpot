package id.tensky.coldspot.runtime

import id.tensky.coldspot.runtime.Reports.excluded
import id.tensky.coldspot.runtime.Reports.file
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A file's own screen. Every test reads as **given** a file of so many lines with these changed, **when** laid
 * out, **then** these are its rows: `12` a line of context, `12!` a changed line, `~5` five lines not shown.
 */
class FileDetailTest {
    /** A file of [lines] lines, `line 1` to `line N`, with [changed] executed. */
    private fun rowsOf(lines: Int, vararg changed: Int, context: Int? = null, end: String = "\n"): List<String> {
        val file = file("a/A.kt", changed.associate { it to LineState.EXECUTED }, text = (1..lines).joinToString("") { "line $it$end" })
        // without a context asked for, the screen's own
        return (if (context == null) fileDetail(file) else fileDetail(file, context)).rows.map(::brief)
    }

    private fun brief(row: DetailRow): String = when (row) {
        is DetailRow.Gap -> "~${row.lines}"
        is DetailRow.Line -> "${row.number}${if (row.marker != null) "!" else ""}"
    }

    @Test
    fun `a changed line comes with three lines before and after, and the rest is counted`() {
        assertEquals(listOf("~6", "7", "8", "9", "10!", "11", "12", "13", "~7"), rowsOf(20, 10))
    }

    @Test
    fun `context stops at the file's first and last line, with no gap where nothing is left out`() {
        assertEquals(listOf("1", "2!", "3", "4", "5", "~15"), rowsOf(20, 2))
        assertEquals(listOf("~15", "16", "17", "18", "19!", "20"), rowsOf(20, 19))
        assertEquals(listOf("1!", "2", "3"), rowsOf(3, 1))
        assertEquals(listOf("1!"), rowsOf(1, 1))
    }

    @Test
    fun `hunks whose context overlaps or touches are one hunk, and a single line between two is a gap of one`() {
        // 5 and 9: their context overlaps; 5 and 11: by line 8 alone; 5 and 12: 8 and 9 touch; 5 and 13: line 9 lies between
        assertEquals(listOf("~1", "2", "3", "4", "5!", "6", "7", "8", "9!", "10", "11", "12", "~8"), rowsOf(20, 5, 9))
        assertEquals(listOf("~1", "2", "3", "4", "5!", "6", "7", "8", "9", "10", "11!", "12", "13", "14", "~6"), rowsOf(20, 5, 11))
        assertEquals(listOf("~1", "2", "3", "4", "5!", "6", "7", "8", "9", "10", "11", "12!", "13", "14", "15", "~5"), rowsOf(20, 5, 12))
        assertEquals(listOf("~1", "2", "3", "4", "5!", "6", "7", "8", "~1", "10", "11", "12", "13!", "14", "15", "16", "~4"), rowsOf(20, 5, 13))
        // consecutive changed lines are one hunk with one context
        assertEquals(listOf("~1", "2", "3", "4", "5!", "6!", "7!", "8", "9", "10", "~10"), rowsOf(20, 5, 6, 7))
    }

    @Test
    fun `the context is as wide as asked, none at all included`() {
        assertEquals(listOf("~9", "10!", "~10"), rowsOf(20, 10, context = 0))
        assertEquals(listOf("~8", "9", "10!", "11", "~9"), rowsOf(20, 10, context = 1))
    }

    @Test
    fun `a gap says how many lines it stands for`() {
        assertEquals("⋯ 12 unchanged lines", DetailRow.Gap(12).text)
        assertEquals("⋯ 1 unchanged line", DetailRow.Gap(1).text)
    }

    @Test
    fun `lines end as the compilers end them, and a last line without an end counts`() {
        assertEquals(listOf("a", "b", "", "c"), lines("a\nb\n\nc\n"))
        assertEquals(listOf("a", "b", "c"), lines("a\r\nb\rc"))
        assertEquals(listOf("a", "", "b"), lines("a\n\r\nb"))
        assertEquals(emptyList(), lines(""))
        // and the rows number them alike, whatever ends them
        assertEquals(rowsOf(20, 10), rowsOf(20, 10, end = "\r\n"))
        assertEquals(rowsOf(20, 10), rowsOf(20, 10, end = "\r"))
    }

    @Test
    fun `a changed line has its marker, its text and what it says when tapped, a line of context only its text`() {
        // given a file with a line of every state
        val states = mapOf(2 to LineState.EXECUTED, 3 to LineState.PARTIAL, 4 to LineState.NOT_EXECUTED, 5 to LineState.BLIND, 6 to LineState.NO_CODE, 7 to LineState.PREVIEW, 8 to LineState.ERROR)
        val detail = fileDetail(
            file(
                "feature/src/Showcase.kt", states, modules = listOf("feature"),
                text = (1..9).joinToString("\n") { "    code of line $it" },
                instructions = mapOf(2 to (4 to 4), 3 to (3 to 8), 4 to (0 to 5), 8 to (1 to 2)),
                reasons = mapOf(7 to "@Preview on ShowcasePreview", 8 to "fx/Stale"),
            ),
        )

        // then
        assertEquals(
            listOf(
                DetailRow.Line(1, "    code of line 1", null, null),
                DetailRow.Line(2, "    code of line 2", Marker.EXECUTED, "4 of 4 instructions executed"),
                DetailRow.Line(3, "    code of line 3", Marker.PARTIAL, "3 of 8 instructions executed"),
                DetailRow.Line(4, "    code of line 4", Marker.NOT_EXECUTED, "0 of 5 instructions executed"),
                DetailRow.Line(5, "    code of line 5", Marker.BLIND, "Can't be measured: code inside an inline lambda, which cannot be traced back to this line. It may have run, or not."),
                DetailRow.Line(6, "    code of line 6", Marker.NO_CODE, "No code on this line"),
                DetailRow.Line(7, "    code of line 7", Marker.PREVIEW, "Preview, never runs in the app: @Preview on ShowcasePreview"),
                DetailRow.Line(8, "    code of line 8", Marker.ERROR, "Error: the class that ran is not the class this build shipped (fx/Stale), so this line cannot be coloured. Build and install again."),
                DetailRow.Line(9, "    code of line 9", null, null),
            ),
            detail.rows,
        )
        assertEquals("Showcase.kt", detail.name)
        assertEquals("feature/src/", detail.directory)
        assertEquals(":feature", detail.module)
        assertEquals(FileMarker.ERROR, detail.marker)
        assertEquals("Error: 1 of 4 changed lines executed", detail.status)
        assertNull(detail.note)
    }

    @Test
    fun `a file only a module names has no text to show, and says why instead`() {
        // given a file the app's manifest has neither as changed nor as excluded: classes shipped, no text, no changed lines
        val detail = fileDetail(file("lib/src/Orphan.kt", emptyMap(), modules = listOf("lib/core"), text = null))

        // then
        assertEquals(emptyList(), detail.rows)
        assertEquals(
            "There is nothing to show of this file: :lib:core shipped classes for it, but the app's manifest has it neither as changed nor as " +
                "excluded, so its text and its changed lines are unknown. The modules were not built from the same change; see the warning on the overview.",
            detail.note,
        )
        assertEquals("Nothing to execute", detail.status)
        // and a file that is left out, or has no measured line, says that
        assertEquals("Excluded by the rule **/gen/**", fileDetail(excluded("gen/G.kt", "**/gen/**").copy(text = "x\n")).status)
        assertEquals("No changed line of this file is measured.", fileDetail(file("a/A.kt", emptyMap(), text = "x\n")).note)
    }

    @Test
    fun `the summary at the top lists the lines not executed, and those that can't be measured when there are any, as ranges`() {
        // given a file with every kind of line: not executed at 40 to 58 and 72, blind at 20, and some of each other kind
        val states = (40..58).associateWith { LineState.NOT_EXECUTED } + mapOf(
            72 to LineState.NOT_EXECUTED, 20 to LineState.BLIND, 10 to LineState.EXECUTED, 11 to LineState.PARTIAL,
            12 to LineState.NO_CODE, 13 to LineState.PREVIEW, 14 to LineState.ERROR,
        )
        val detail = fileDetail(file("a/A.kt", states, text = (1..80).joinToString("") { "line $it\n" }))

        // then those two kinds alone, as ranges, and as TalkBack says them
        assertEquals(listOf("Not executed: lines 40–58, 72", "Can't be measured: line 20"), detail.summary)
        assertEquals("Not executed: lines 40 to 58, 72. Can't be measured: line 20", detail.summaryDescription)
        // without a blind line, no line about them; with nothing left not executed, "none"; a single line is "line"
        assertEquals(listOf("Not executed: line 17"), fileDetail(file("a/A.kt", mapOf(17 to LineState.NOT_EXECUTED, 18 to LineState.EXECUTED), text = "x\n".repeat(20))).summary)
        assertEquals(listOf("Not executed: none"), fileDetail(file("a/A.kt", mapOf(3 to LineState.EXECUTED, 4 to LineState.PARTIAL), text = "x\n".repeat(5))).summary)
        assertEquals(listOf("Not executed: none", "Can't be measured: lines 2–3"), fileDetail(file("a/A.kt", mapOf(2 to LineState.BLIND, 3 to LineState.BLIND), text = "x\n".repeat(5))).summary)
        // and a file with nothing to show has no summary either
        assertEquals(emptyList(), fileDetail(file("lib/Orphan.kt", emptyMap(), text = null)).summary)
        assertEquals("", fileDetail(file("lib/Orphan.kt", emptyMap(), text = null)).summaryDescription)
    }

    @Test
    fun `a long summary lists as many ranges as the share summary does, and says how many more there are`() {
        // given 15 lines not executed, none next to another: 15 ranges
        val lines = (1..15).map { it * 2 }
        val detail = fileDetail(file("a/A.kt", lines.associateWith { LineState.NOT_EXECUTED }, text = "x\n".repeat(40)))

        // then twelve, and three more
        assertEquals(listOf("Not executed: lines 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, +3 more"), detail.summary)
        assertEquals("Not executed: lines 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, and 3 more", detail.summaryDescription)
    }

    @Test
    fun `a changed line the text does not have is left out, not made up`() {
        // a line just beyond the end, and one far beyond it, where a gap would have to be invented to reach it
        assertEquals(listOf("1", "2", "3!"), rowsOf(3, 3, 9))
        assertEquals(listOf("1", "2", "3!"), rowsOf(3, 3, 50))
        assertEquals(listOf("~3"), rowsOf(3, 50))
    }
}
