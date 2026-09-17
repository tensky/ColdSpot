package id.tensky.coldspot.runtime

import id.tensky.coldspot.manifest.BaseSource
import id.tensky.coldspot.manifest.ShippedClass
import id.tensky.coldspot.runtime.Reports.build
import id.tensky.coldspot.runtime.Reports.counted
import id.tensky.coldspot.runtime.Reports.excluded
import id.tensky.coldspot.runtime.Reports.file
import id.tensky.coldspot.runtime.Reports.notMeasurable
import id.tensky.coldspot.runtime.Reports.report
import id.tensky.coldspot.runtime.Reports.time
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The summary that is shared (DECISIONS.md "Share summary"), as golden texts. Every test reads as **given** a
 * report, **when** shared, **then** this is the text, to the character.
 */
class ShareTextTest {
    private val pixel = SharedFrom("Google Pixel 8", "Android 15 (API 35)", installedAt = 1_000)

    @Test
    fun `every section in the order DECISIONS gives, the files worst first, what is left to execute by line`() {
        // given a file of every status, with previews, blind lines and partly executed lines among them, and everything there is to warn of
        val partly = file(
            "feature/src/Feed.kt",
            mapOf(10 to LineState.EXECUTED, 11 to LineState.EXECUTED, 12 to LineState.NOT_EXECUTED, 15 to LineState.NOT_EXECUTED, 16 to LineState.NOT_EXECUTED, 20 to LineState.PARTIAL, 27 to LineState.BLIND, 28 to LineState.BLIND, 40 to LineState.PREVIEW, 41 to LineState.PREVIEW, 50 to LineState.NO_CODE),
            modules = listOf("feature"),
            text = "SECRET-SOURCE-OF-FEED\n",
        )
        val report = report(
            listOf(
                counted("app/src/Fully.kt", executed = 5, not = 0),
                partly,
                counted("app/src/Never.kt", executed = 0, not = 4),
                file("app/src/Stale.kt", mapOf(3 to LineState.ERROR, 4 to LineState.ERROR, 9 to LineState.EXECUTED)),
                file("app/src/Previews.kt", mapOf(1 to LineState.PREVIEW, 5 to LineState.NO_CODE)),
                notMeasurable("core/model/Topic.kt"),
                excluded("build-logic/Plugin.kt", "**/build-logic/**"),
            ),
            build(BaseSource.GUESSED, "origin/main", dirty = true),
            warnings = listOf(":lib ships classes for lib/Orphan.kt, which the app excludes (**/Orphan.kt)"),
            errors = listOf("app/fx/Gone: the class file is missing from the APK's assets"),
            stale = listOf(ShippedClass("app", "fx/Stale")),
        )

        // when
        val text = shareText(report, pixel, time)

        // then
        assertEquals(
            """
            **ColdSpot summary**
            - Branch: `feature/login`
            - Base `origin/main` @ `eb2505f` → head `90550f1`
            - Guessed: no base was set and the repository has no origin/HEAD, so origin/main was used. Set coldSpot { baseRef } to be sure.
            - **Uncommitted changes** were part of the build
            - Google Pixel 8, Android 15 (API 35)
            - Installed time(1000); collecting since time(2000)

            **Changed lines executed:** 8 / 18 (44%)

            **In error (1 file)**
            - `app/src/Stale.kt` 1/1; in error: 3–4

            **Never executed (1 file)**
            - `app/src/Never.kt` 0/4; not executed: 1–4

            **Partly executed (1 file)**
            - `feature/src/Feed.kt` 2/8; not executed: 12, 15–16; partly executed: 20; can't be measured: 27–28

            **Fully executed (1 file)**
            - `app/src/Fully.kt` 5/5

            **Not measurable (1)**
            - `core/model/Topic.kt`

            **Previews (2)**
            - `app/src/Previews.kt`: 1
            - `feature/src/Feed.kt`: 40–41

            **Excluded (1)**
            - `build-logic/Plugin.kt` by `**/build-logic/**`

            **Errors (2)**
            - app/fx/Gone: the class file is missing from the APK's assets
            - stale class `fx.Stale` in :app: what ran is not what the build shipped

            **Warnings (1)**
            - :lib ships classes for lib/Orphan.kt, which the app excludes (**/Orphan.kt)

            ColdSpot 1.2.3
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `what there is nothing of is said to be none, never left out`() {
        // given one fully executed file, a base that was set, a clean head without a branch, and nothing collected yet
        val text = shareText(report(listOf(counted("A.kt", 2, 0)), build(branch = null), collectingSince = null), pixel, time)

        // then
        assertEquals(
            """
            **ColdSpot summary**
            - Branch: detached HEAD
            - Base `origin/develop` @ `eb2505f` → head `90550f1`
            - Google Pixel 8, Android 15 (API 35)
            - Installed time(1000); collecting since nothing yet

            **Changed lines executed:** 2 / 2 (100%)

            **Fully executed (1 file)**
            - `A.kt` 2/2

            **Not measurable:** none

            **Previews:** none

            **Excluded:** none

            **Errors:** none

            **Warnings:** none

            ColdSpot 1.2.3
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `long lists are cut off with how many more there are`() {
        // given more files, more ranges and more warnings than the limits allow
        val scattered = file("Scattered.kt", (1..9 step 2).associateWith { LineState.NOT_EXECUTED } + (20..22).associateWith { LineState.EXECUTED })
        val report = report(
            (1..5).map { counted("never/N$it.kt", 0, it) } + scattered + (1..4).map { notMeasurable("plain/P$it.kt") },
            warnings = (1..5).map { "warning $it" },
        )

        // when
        val text = shareText(report, pixel, time, ShareLimits(filesPerGroup = 3, rangesPerList = 2, itemsPerSection = 3))

        // then the worst are what is kept, and the count is of what is not shown
        assertContains(text, "**Never executed (5 files)**\n- `never/N5.kt` 0/5; not executed: 1–5\n- `never/N4.kt` 0/4; not executed: 1–4\n- `never/N3.kt` 0/3; not executed: 1–3\n- +2 more\n")
        assertContains(text, "- `Scattered.kt` 3/8; not executed: 1, 3, +3 more\n")
        assertContains(text, "**Not measurable (4)**\n- `plain/P1.kt`\n- `plain/P2.kt`\n- `plain/P3.kt`\n- +1 more\n")
        assertContains(text, "**Warnings (5)**\n- warning 1\n- warning 2\n- warning 3\n- +2 more\n")
        // and a list as long as its limit is not cut
        assertFalse(shareText(report, pixel, time, ShareLimits(filesPerGroup = 5, rangesPerList = 5, itemsPerSection = 5)).contains("more"))
    }

    @Test
    fun `another JaCoCo or no bundle shares what is wrong, and no file`() {
        // given a report that coloured nothing, and one without a manifest
        val mismatch = shareText(report(emptyList(), errors = listOf("The build instrumented with JaCoCo 1 but the app runs JaCoCo 2"), jacocoMismatch = true), pixel, time)
        val noBundle = shareText(report(emptyList(), build = null, collectingSince = null, errors = listOf("no coldspot/manifest.json in the APK's assets")), pixel, time)

        // then neither claims a total: what was measured is unknown, which is not "nothing to execute"
        assertContains(mismatch, "**Changed lines executed:** unknown, nothing could be measured (see Errors)\n")
        assertContains(mismatch, "**Errors (1)**\n- The build instrumented with JaCoCo 1 but the app runs JaCoCo 2\n")
        assertFalse(mismatch.contains("files)**") || mismatch.contains("file)**"), mismatch)
        assertEquals(
            """
            **ColdSpot summary**
            - Google Pixel 8, Android 15 (API 35)

            **Changed lines executed:** unknown, nothing could be measured (see Errors)

            **Errors (1)**
            - no coldspot/manifest.json in the APK's assets

            **Warnings:** none

            ColdSpot (version unknown)
            """.trimIndent(),
            noBundle,
        )
    }

    @Test
    fun `paths and line numbers only, never a line of source`() {
        // given files whose every line of text is marked, measured or not
        val marked = (1..30).joinToString("\n") { "val leak$it = \"SOURCE-$it\"" }
        val report = report(
            listOf(
                file("a/Measured.kt", (1..30).associateWith { LineState.values()[it % LineState.values().size] }, text = marked, reasons = mapOf(1 to "@Preview on P")),
                notMeasurable("b/Plain.kt").copy(text = marked),
                excluded("c/Excluded.kt", "**/c/**").copy(text = marked),
            ),
        )

        // when
        val text = shareText(report, pixel, time)

        // then nothing of the text is in it
        assertFalse(text.contains("SOURCE"), text)
        assertFalse(text.contains("leak"), text)
        assertFalse(Regex("\\bval\\b").containsMatchIn(text), text)
        // and it is Markdown-lite: bold, inline code and simple bullets, no header, no table, no nested bullet
        for (line in text.lines()) {
            assertTrue(line.isEmpty() || line.startsWith("- ") || line.startsWith("**") || line.startsWith("ColdSpot "), "neither a bullet nor bold nor the version: '$line'")
            assertFalse(line.startsWith("#") || line.contains("|") || line.startsWith(" "), "a header, a table or a nested bullet: '$line'")
        }
    }

    @Test
    fun `coverage that cannot be saved is the first of the errors`() {
        // given a report whose last save failed, with another error
        val text = shareText(report(listOf(counted("A.kt", 1, 1)), errors = listOf("app/fx/Gone: the class file is missing from the APK's assets"), saveError = "the saved coverage cannot be read (EACCES)"), pixel, time)

        // then
        assertContains(text, "**Errors (2)**\n- Coverage can't be saved: the saved coverage cannot be read (EACCES)\n- app/fx/Gone: the class file is missing from the APK's assets\n")
    }

    @Test
    fun `it says executed, never tested`() {
        val text = shareText(report(listOf(counted("A.kt", 1, 1), notMeasurable("B.kt")), warnings = listOf("w")), pixel, time)
        assertContains(text, "executed")
        assertFalse(text.contains("tested", ignoreCase = true), text)
    }

    @Test
    fun `a build with nothing to execute says so, and the total rounds down`() {
        assertContains(shareText(report(listOf(file("A.kt", mapOf(1 to LineState.NO_CODE, 2 to LineState.PREVIEW)))), pixel, time), "**Changed lines executed:** none to execute\n")
        assertContains(shareText(report(listOf(counted("A.kt", 199, 1))), pixel, time), "**Changed lines executed:** 199 / 200 (99%)\n")
    }

    @Test
    fun `lines that follow each other are one range`() {
        assertEquals(listOf(1 to 1, 3 to 5, 9 to 10), lineRanges(listOf(1, 3, 4, 5, 9, 10)))
        assertEquals(emptyList(), lineRanges(emptyList()))
    }
}
