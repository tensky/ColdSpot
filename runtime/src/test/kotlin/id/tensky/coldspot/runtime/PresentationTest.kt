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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the overview says. Every test reads as **given** a report of the analysis, **when** it is laid out for the
 * screen, **then** these are the words, the markers and the order.
 */
class PresentationTest {
    @Test
    fun `every line state has a marker and a label of its own`() {
        // when
        val markers = LineState.values().associateWith { Marker.of(it) }

        // then none stands for two states, blind is not partly executed, and no label is another's
        assertEquals(LineState.values().size, markers.values.toSet().size)
        assertEquals(Marker.values().size, Marker.values().map { it.label }.toSet().size)
        assertEquals(
            mapOf(
                LineState.EXECUTED to "Executed",
                LineState.PARTIAL to "Partly executed",
                LineState.NOT_EXECUTED to "Not executed",
                LineState.BLIND to "Can't be measured",
                LineState.NO_CODE to "No code",
                LineState.PREVIEW to "Preview",
                LineState.ERROR to "Error",
            ),
            markers.mapValues { it.value.label },
        )
    }

    @Test
    fun `a tapped line says what it did, with the instructions or the reason`() {
        assertEquals("15 of 15 instructions executed", explain(LineState.EXECUTED, 15 to 15, null))
        assertEquals("12 of 15 instructions executed", explain(LineState.PARTIAL, 12 to 15, null))
        assertEquals("0 of 15 instructions executed", explain(LineState.NOT_EXECUTED, 0 to 15, null))
        assertEquals("0 of 1 instruction executed", explain(LineState.NOT_EXECUTED, 0 to 1, null))
        assertEquals("No code on this line", explain(LineState.NO_CODE, null, null))
        assertEquals("Preview, never runs in the app: @Preview on GreetingPreview", explain(LineState.PREVIEW, 0 to 4, "@Preview on GreetingPreview"))
        assertEquals(
            "Can't be measured: code inside an inline lambda, which cannot be traced back to this line. It may have run, or not.",
            explain(LineState.BLIND, null, null),
        )
        // a blind line other classes hold too says how those did, and claims nothing for the rest
        assertEquals(
            "Can't be measured: code inside an inline lambda, which cannot be traced back to this line. It may have run, or not. " +
                "Of what can be measured here, 3 of 7 instructions executed.",
            explain(LineState.BLIND, 3 to 7, null),
        )
        assertEquals(
            "Error: the class that ran is not the class this build shipped (fx/Stale), so this line cannot be coloured. Build and install again.",
            explain(LineState.ERROR, 2 to 9, "fx/Stale"),
        )
    }

    @Test
    fun `files come worst first, and among equals the one with more left to execute`() {
        // given one file of every status, two partly executed and two never executed, in path order
        val files = listOf(
            counted("a/Fully.kt", executed = 3, not = 0),
            file("b/InError.kt", mapOf(1 to LineState.ERROR, 2 to LineState.EXECUTED)),
            counted("c/NeverSmall.kt", executed = 0, not = 2),
            counted("d/NeverLarge.kt", executed = 0, not = 9),
            file("e/Neutral.kt", mapOf(1 to LineState.NO_CODE, 2 to LineState.PREVIEW)),
            counted("f/PartlyAlmost.kt", executed = 9, not = 1),
            counted("g/PartlyHardly.kt", executed = 1, not = 9),
            counted("h/PartlyHardlyToo.kt", executed = 11, not = 9),
            notMeasurable("i/NotMeasurable.kt"),
            excluded("j/Excluded.kt", "**/j/**"),
        )

        // when
        val order = worstFirst(files.shuffled(java.util.Random(7))).map { it.path }

        // then in error, never, partly, fully, nothing to execute; more unexecuted lines first, then by path; no row for the rest
        assertEquals(
            listOf("b/InError.kt", "d/NeverLarge.kt", "c/NeverSmall.kt", "g/PartlyHardly.kt", "h/PartlyHardlyToo.kt", "f/PartlyAlmost.kt", "a/Fully.kt", "e/Neutral.kt"),
            order,
        )
    }

    @Test
    fun `a file row has its marker, its count, its name apart from its directory, and its module`() {
        // given a file of :feature:foryou:impl, 18 of 20 lines executed with a blind line among the rest, and one with nothing to execute
        val states = (1..18).associateWith { LineState.EXECUTED } + mapOf(19 to LineState.NOT_EXECUTED, 20 to LineState.BLIND, 21 to LineState.NO_CODE, 22 to LineState.PREVIEW)
        val overview = overview(
            report(listOf(file("feature/foryou/impl/src/main/kotlin/ForYouScreen.kt", states, modules = listOf("feature/foryou/impl")), file("Root.kt", mapOf(1 to LineState.NO_CODE), modules = listOf("root")))),
            installedAt = 1_000,
            formatTime = time,
        ) as Overview.Ready

        // then previews and lines without code count for nothing, the blind line counts as one still to execute
        assertEquals(
            listOf(
                FileRow("feature/foryou/impl/src/main/kotlin/ForYouScreen.kt", "ForYouScreen.kt", "feature/foryou/impl/src/main/kotlin/", ":feature:foryou:impl", FileMarker.PARTIAL, "18/20", "Partly executed: 18 of 20 changed lines executed"),
                FileRow("Root.kt", "Root.kt", "", ":", FileMarker.NEUTRAL, "–", "Nothing to execute"),
            ),
            overview.files,
        )
        assertEquals("18 / 20 changed lines executed (90%)", overview.total)
    }

    @Test
    fun `the total rounds down, so that 100 percent is every line, and says so when there is nothing to execute`() {
        assertEquals("199 / 200 changed lines executed (99%)", totalOf(listOf(counted("A.kt", 199, 1))))
        assertEquals("1 / 1 changed lines executed (100%)", totalOf(listOf(counted("A.kt", 1, 0))))
        assertEquals("0 / 3 changed lines executed (0%)", totalOf(listOf(counted("A.kt", 0, 3))))
        assertEquals("No changed lines to execute", totalOf(listOf(file("A.kt", mapOf(1 to LineState.NO_CODE)), excluded("B.kt", "**"))))
    }

    @Test
    fun `the header says what was built from what, and how the base came about when nobody set it`() {
        // given a base that was set, one taken from origin-HEAD, and one guessed
        fun header(build: BuildInfo, since: Long? = 2_000) = (overview(report(listOf(counted("A.kt", 1, 0)), build, since), installedAt = 1_000, formatTime = time) as Overview.Ready).header
        val set = header(build(BaseSource.EXPLICIT, "origin/develop"))
        val default = header(build(BaseSource.ORIGIN_HEAD, "origin/HEAD"))
        val guessed = header(build(BaseSource.GUESSED, "origin/master"))

        // then
        assertEquals("origin/develop @ eb2505f", set.base)
        assertNull(set.baseNote)
        assertEquals("No base was set: origin/HEAD, the remote's default branch, was used.", default.baseNote)
        assertEquals("Guessed: no base was set and the repository has no origin/HEAD, so origin/master was used. Set coldSpot { baseRef } to be sure.", guessed.baseNote)
        assertEquals("feature/login @ 90550f1", set.head)
        assertEquals("time(1000)", set.installedAt)
        assertEquals("time(2000)", set.collectingSince)
        assertEquals("ColdSpot 1.2.3", set.version)
        assertFalse(set.uncommitted)
        // and a head that is detached, or has no commit yet, or holds uncommitted changes, says so
        assertEquals("detached HEAD @ 90550f1", header(build(branch = null)).head)
        assertEquals("fresh, no commit yet", header(build(branch = "fresh", sha = null)).head)
        assertTrue(header(build(dirty = true)).uncommitted)
        assertNull(header(build(), since = null).collectingSince)
    }

    @Test
    fun `the commits are listed newest first as the manifest has them, and what it left out is counted`() {
        // given 52 commits, of which the manifest lists two
        val some = (overview(report(listOf(counted("A.kt", 1, 0)), build(commits = listOf("second thing", "first thing"), total = 52)), 0, time) as Overview.Ready).header
        val one = (overview(report(listOf(counted("A.kt", 1, 0)), build(commits = listOf("only"))), 0, time) as Overview.Ready).header
        val none = (overview(report(listOf(counted("A.kt", 1, 0)), build(commits = emptyList())), 0, time) as Overview.Ready).header

        // then
        assertEquals("52 commits", some.commitsTitle)
        assertEquals(listOf("c0ffee0 second thing", "c0ffee1 first thing"), some.commits)
        assertEquals(50, some.moreCommits)
        assertEquals("1 commit", one.commitsTitle)
        assertEquals(0, one.moreCommits)
        assertEquals("No commits since the base", none.commitsTitle)
    }

    @Test
    fun `banners come errors first, then stale classes, then warnings, each with everything it has to say`() {
        // given all three
        val overview = overview(
            report(
                listOf(file("A.kt", mapOf(1 to LineState.ERROR))),
                warnings = listOf("w1", "w2"),
                errors = listOf("app/fx/Gone: the class file is missing from the APK's assets"),
                stale = listOf(ShippedClass("feature/impl", "fx/Stale\$Inner")),
            ),
            0, time,
        ) as Overview.Ready

        // then
        assertEquals(listOf(Banner.Kind.ERROR, Banner.Kind.STALE, Banner.Kind.WARNING), overview.banners.map { it.kind })
        assertEquals(listOf("1 error", "1 stale class: what ran is not what this build shipped. Their lines are in error; build and install again.", "2 warnings"), overview.banners.map { it.title })
        assertEquals(listOf(listOf("app/fx/Gone: the class file is missing from the APK's assets"), listOf(":feature:impl fx.Stale\$Inner"), listOf("w1", "w2")), overview.banners.map { it.details })
        // and a report with none of them has no banner
        assertEquals(emptyList(), (overview(report(listOf(counted("A.kt", 1, 0))), 0, time) as Overview.Ready).banners)
    }

    @Test
    fun `coverage that cannot be saved is the first banner, with what that means and the way out`() {
        // given a report whose last save failed, among other errors
        val reason = "the saved coverage cannot be read (open failed: EACCES (Permission denied))"
        val overview = overview(report(listOf(counted("A.kt", 1, 1)), errors = listOf("e1"), saveError = reason), 0, time) as Overview.Ready

        // then it comes first, says why, and says what it means
        assertEquals(listOf(Banner.Kind.ERROR, Banner.Kind.ERROR), overview.banners.map { it.kind })
        assertEquals("Coverage can't be saved: $reason", overview.banners.first().title)
        assertEquals(listOf("Until a save succeeds, what the app executes is lost when it stops. The saved file is never written over; Reset deletes it."), overview.banners.first().details)
        // and once a save succeeds there is no such banner
        assertEquals(listOf("1 error"), (overview(report(listOf(counted("A.kt", 1, 1)), errors = listOf("e1")), 0, time) as Overview.Ready).banners.map { it.title })
    }

    @Test
    fun `what is left out has its section, with how many and why`() {
        // given two files nobody shipped a class for and one excluded, next to one that is measured
        val overview = overview(report(listOf(counted("A.kt", 1, 0), notMeasurable("core/model/Topic.kt"), notMeasurable("core/model/User.kt"), excluded("build-logic/Plugin.kt", "**/build-logic/**"))), 0, time) as Overview.Ready

        // then they have no row among the files, and count for nothing in the total
        assertEquals(listOf("A.kt"), overview.files.map { it.path })
        assertEquals("1 / 1 changed lines executed (100%)", overview.total)
        assertEquals("Not measurable (2)", overview.notMeasurable.title)
        assertEquals(listOf("core/model/Topic.kt", "core/model/User.kt"), overview.notMeasurable.items.map { it.path })
        assertEquals("No module shipped a class for it: a plain Kotlin/JVM module, or a module without ColdSpot.", overview.notMeasurable.items.first().reason)
        assertEquals("Excluded (1)", overview.excluded.title)
        assertEquals(Section.Item("build-logic/Plugin.kt", "Left out by the rule **/build-logic/**"), overview.excluded.items.single())
    }

    @Test
    fun `nothing changed is an empty state, another JaCoCo or no bundle an error state that colours nothing`() {
        // given a build with no changes, one instrumented by another JaCoCo, and an app without a bundle
        val empty = overview(report(emptyList(), build(ref = "origin/main")), 0, time)
        val mismatch = overview(report(emptyList(), errors = listOf("The build instrumented with JaCoCo 1 but the app runs JaCoCo 2"), jacocoMismatch = true), 0, time)
        val noBundle = overview(report(emptyList(), build = null, errors = listOf("no coldspot/manifest.json in the APK's assets")), 0, time)
        val nothingSaid = overview(report(emptyList(), build = null), 0, time)

        // then
        assertEquals(Overview.Empty::class, empty::class)
        assertEquals("No changed lines against origin/main @ eb2505f. Nothing to execute: what was built is what the base has.", (empty as Overview.Empty).message)
        assertEquals("Nothing can be coloured", (mismatch as Overview.Failed).title)
        assertEquals(listOf("The build instrumented with JaCoCo 1 but the app runs JaCoCo 2"), mismatch.messages)
        assertEquals("ColdSpot has nothing to show", (noBundle as Overview.Failed).title)
        assertEquals(listOf("no coldspot/manifest.json in the APK's assets"), noBundle.messages)
        assertEquals(listOf("This build carries no ColdSpot bundle."), (nothingSaid as Overview.Failed).messages)
        // and a build whose only changes are left out is not empty: they are there to be seen; nor is one with nothing but a warning
        assertEquals(Overview.Ready::class, overview(report(listOf(excluded("B.kt", "**"))), 0, time)::class)
        assertEquals(listOf(Banner.Kind.WARNING), (overview(report(emptyList(), warnings = listOf("w")), 0, time) as Overview.Ready).banners.map { it.kind })
        assertEquals(listOf(Banner.Kind.ERROR), (overview(report(emptyList(), errors = listOf("e")), 0, time) as Overview.Ready).banners.map { it.kind })
    }

    @Test
    fun `the list is flat, and what unfolds adds its rows under its own`() {
        // given two commits of 52, a banner, two files, one file not measurable and none excluded
        val overview = overview(
            report(listOf(counted("A.kt", 1, 0), counted("B.kt", 0, 1), notMeasurable("C.kt")), build(total = 52), warnings = listOf("w")),
            0, time,
        )

        // when everything is folded, and when the commits and the sections are unfolded
        val folded = overviewItems(overview, emptySet()).map(::brief)
        val unfolded = overviewItems(overview, setOf(COMMITS, NOT_MEASURABLE, EXCLUDED)).map(::brief)

        // then
        assertEquals(listOf("head", "+52 commits", "banner WARNING", "total", "file B.kt", "file A.kt", "+Not measurable (1)", " Excluded (0)", "actions"), folded)
        assertEquals(
            listOf("head", "-52 commits", "commit c0ffee0 second thing", "commit c0ffee1 first thing", "commit +50 more", "banner WARNING", "total", "file B.kt", "file A.kt", "-Not measurable (1)", "left C.kt", " Excluded (0)", "actions"),
            unfolded,
        )
        // and the empty and the error state keep the actions, the switch that brings the bubble back among them
        assertEquals(listOf("head", "+2 commits", "message No changes", "actions"), overviewItems(overview(report(emptyList()), 0, time), emptySet()).map(::brief))
        assertEquals(listOf("failed Nothing can be coloured", "actions"), overviewItems(overview(report(emptyList(), errors = listOf("e"), jacocoMismatch = true), 0, time), setOf(COMMITS)).map(::brief))
    }

    /** A row in a word or two; a toggle with `+` when it can unfold, `-` when it is unfolded, a blank when there is nothing under it. */
    private fun brief(item: OverviewItem): String = when (item) {
        is OverviewItem.Head -> "head"
        is OverviewItem.Toggle -> (if (!item.enabled) " " else if (item.expanded) "-" else "+") + item.title
        is OverviewItem.Commit -> "commit ${item.text}"
        is OverviewItem.BannerRow -> "banner ${item.banner.kind}"
        is OverviewItem.Total -> "total"
        is OverviewItem.File -> "file ${item.row.path}"
        is OverviewItem.Left -> "left ${item.item.path}"
        is OverviewItem.Message -> (if (item.failed) "failed " else "message ") + item.title
        is OverviewItem.Actions -> "actions"
    }
}
