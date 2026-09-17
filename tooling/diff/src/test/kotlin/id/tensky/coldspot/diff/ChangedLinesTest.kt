package id.tensky.coldspot.diff

import id.tensky.coldspot.diff.Eol.CRLF
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FEATURE_KT = "app/src/main/java/demo/Feature.kt"
private const val LEGACY_JAVA = "app/src/main/java/demo/Legacy.java"
private const val RENAMED_KT = "app/src/main/java/demo/Renamed.kt"

/** The output of `git <args>` run in [workTree], or null without git on PATH. git failing fails the test. */
private fun git(workTree: File, vararg args: String): String? {
    val process = try {
        ProcessBuilder("git", *args).directory(workTree).redirectErrorStream(true).start()
    } catch (e: IOException) {
        return null
    }
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    return output
}

/** What `git status --porcelain` finds in [workTree], or null without git on PATH. */
private fun gitStatusSaysDirty(workTree: File): Boolean? = git(workTree, "status", "--porcelain")?.isNotBlank()

/** The SHAs `git log --format=%H <base>..HEAD` prints in [workTree], newest first, or null without git on PATH. */
private fun gitLogSays(workTree: File, base: String): List<String>? =
    git(workTree, "log", "--format=%H", "$base..HEAD")?.lines()?.filter { it.isNotEmpty() }

/** What `git branch --show-current` prints in [workTree], nothing while HEAD is detached; null without git on PATH. */
private fun gitSaysBranch(workTree: File): String? = git(workTree, "branch", "--show-current")?.trim()

/**
 * What [changedLines] reports, against repositories built from scratch with JGit.
 *
 * Every test reads as **given** a repository in some state, **when** a build asks what changed since a
 * base ref, **then** these are the lines it would colour. The shared given is in [Fixture]; the groups
 * below are the questions a reader is likely to arrive with.
 */
@RunWith(Enclosed::class)
class ChangedLinesTest {

    /**
     * Given, for every test: a repository whose only commit is tagged `base` and holds [featureKt],
     * [legacyJava] and the XML and Gradle files of a small Android module, with `build/` ignored.
     */
    abstract class Fixture {
        @get:Rule
        val tmp = TemporaryFolder()

        internal lateinit var repo: FixtureRepo

        /** 20 lines of `val valueN = N`, committed in `base`. */
        internal lateinit var featureKt: SourceFile

        /** 20 lines of `val fieldN = N`, committed in `base`. */
        internal lateinit var legacyJava: SourceFile

        @Before
        fun commitTheBase() {
            repo = FixtureRepo(tmp.newFolder("repo"))
            featureKt = sourceFile(FEATURE_KT)
            legacyJava = sourceFile(LEGACY_JAVA, prefix = "field")
            repo.write("app/src/main/AndroidManifest.xml", "<manifest/>\n")
            repo.write("app/build.gradle", "// groovy\n")
            repo.write("build.gradle.kts", "// kotlin dsl\n")
            repo.write(".gitignore", "build/\n")
            repo.commitAll("base")
            repo.tag("base")
        }

        @After
        fun closeRepo() = repo.close()

        /** Writes a source file of [lines] lines into the working tree and returns a handle to it. */
        internal fun sourceFile(path: String, lines: Int = 20, prefix: String = "value"): SourceFile =
            SourceFile(repo, path, lines, prefix).write()

        /** The call under test: the lines a build would colour, asked for from [callFrom]. */
        internal fun changedLinesSince(baseRef: String = "base", callFrom: File = repo.dir): Map<String, Set<Int>> =
            changedLines(callFrom, baseRef)
    }

    /** The seven cases the module was asked for, in order; 3b is the uncommitted variant of 3. */
    class RequiredCases : Fixture() {

        @Test
        fun `1 - a new file reports every line`() {
            // given a five-line file that the base commit does not have
            val added = sourceFile("app/src/main/java/demo/Added.kt", lines = 5)
            repo.commitAll("add a file")

            // when
            val changed = changedLinesSince()

            // then all of it is new code
            assertEquals(mapOf(added.path to (1..5).toSet()), changed)
        }

        @Test
        fun `2 - a modified file reports only the lines that changed`() {
            // given lines 4 and 17 edited, and one line inserted after line 9
            featureKt.edit(4, 17, insertingAfter = 9)
            repo.commitAll("edit")

            // when
            val changed = changedLinesSince()

            // then line 4, the inserted line 10, and old line 17 at the number it now has
            assertEquals(mapOf(featureKt.path to setOf(4, 10, 18)), changed)
        }

        @Test
        fun `3 - a committed rename with a small edit reports only the edited line`() {
            // given the file moved to another name and its line 7 edited
            featureKt.moveTo(RENAMED_KT).edit(7)
            repo.commitAll("rename and edit")

            // when
            val changed = changedLinesSince()

            // then only that line, rather than all twenty of a seemingly new file
            assertEquals(mapOf(RENAMED_KT to setOf(7)), changed)
        }

        @Test
        fun `3b - an uncommitted rename with a small edit reports only the edited line`() {
            // given the same move and edit left in the working tree, where the new side exists
            // only on disk, which is what JGit's stock rename detection cannot read
            featureKt.moveTo(RENAMED_KT).edit(7)

            // when
            val changed = changedLinesSince()

            // then still only that line
            assertEquals(mapOf(RENAMED_KT to setOf(7)), changed)
        }

        @Test
        fun `4 - a whitespace-only change is reported`() {
            // given line 3 respaced and line 11 indented, with nothing else touched
            featureKt.write(
                featureKt.contentReplacing(
                    3 to "val value3  =  3\t",
                    11 to "    val value11 = 11",
                ),
            )
            repo.commitAll("reformat")

            // when
            val changed = changedLinesSince()

            // then both lines: whether reformatting matters is the caller's call, not this module's
            assertEquals(mapOf(featureKt.path to setOf(3, 11)), changed)
        }

        @Test
        fun `5 - a deleted file is absent`() {
            // given the file deleted, and the deletion committed
            featureKt.delete()
            repo.commitAll("delete")

            // when
            val changed = changedLinesSince()

            // then nothing at all: a deleted line has no new side to colour
            assertEquals(emptyMap(), changed)
        }

        @Test
        fun `6 - xml and gradle changes are absent`() {
            // given every non-source file edited, plus names that merely look like sources
            repo.write("app/src/main/AndroidManifest.xml", "<manifest package=\"demo\"/>\n")
            repo.write("app/build.gradle", "// groovy, edited\n")
            repo.write("build.gradle.kts", "// kotlin dsl, edited\n")
            repo.write("settings.gradle.kts", "// untracked kotlin dsl\n")
            repo.write("app/src/main/res/values/strings.xml", "<resources/>\n")
            for (nearMiss in listOf("tools/racket", "tools/notes.mkt", "tools/Thing.kt.bak", "tools/Thing.javax")) {
                repo.write(nearMiss, "not source\n")
            }
            repo.commitAll("non-source edits")

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(emptyMap(), changed)
        }

        @Test
        fun `7 - an untracked kt file is present`() {
            // given a file that was never added to git
            val untracked = sourceFile("app/src/main/java/demo/Untracked.kt", lines = 3)

            // when
            val changed = changedLinesSince()

            // then it counts in full, as if it had been `git add -N`'d
            assertEquals(mapOf(untracked.path to (1..3).toSet()), changed)
        }
    }

    /** The comparison runs base-to-working-tree, because that is what the APK is built from. */
    class TwoDotSemantics : Fixture() {

        @Test
        fun `committed, staged and unstaged edits all count`() {
            // given one edit committed, one staged, and one left on disk
            featureKt.edit(2)
            repo.commitAll("committed edit")
            legacyJava.edit(5)
            repo.stage(legacyJava.path)
            featureKt.edit(2, 12)

            // when the base is the tag, and again when it is HEAD
            val sinceBase = changedLinesSince("base")
            val sinceHead = changedLinesSince("HEAD")

            // then the tag sees all three, and HEAD no longer sees the edit it already contains
            assertEquals(mapOf(legacyJava.path to setOf(5), featureKt.path to setOf(2, 12)), sinceBase)
            assertEquals(mapOf(legacyJava.path to setOf(5), featureKt.path to setOf(12)), sinceHead)
        }

        @Test
        fun `the working tree wins when a committed edit is undone on disk`() {
            // given an edit that was committed and then reverted in the working tree
            featureKt.edit(2)
            repo.commitAll("committed edit")
            featureKt.revert()

            // when
            val changed = changedLinesSince()

            // then nothing: the file that will be built is the base's
            assertEquals(emptyMap(), changed)
        }

        @Test
        fun `a file deleted from disk but not from git is absent`() {
            // given the file removed from the working tree only
            legacyJava.delete()

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(emptyMap(), changed)
        }
    }

    /** Which commit the comparison starts from, given a branch, a tag, a SHA or `@{upstream}`. */
    class BaseRefResolution : Fixture() {

        /**
         * `main` gains an edit on line 9 of [legacyJava] after `feature` forks and edits line 15 of
         * [featureKt]. Leaves `feature` checked out and returns main's tip.
         */
        private fun divergeMainFromFeature(): RevCommit {
            repo.branch("feature")
            legacyJava.edit(9)
            val mainTip = repo.commitAll("main moves on")
            repo.checkout("feature")
            featureKt.edit(15)
            repo.commitAll("feature work")
            return mainTip
        }

        @Test
        fun `a branch base starts from its merge-base with HEAD`() {
            // given main edited line 9 of Legacy.java after feature forked and edited line 15 of Feature.kt
            divergeMainFromFeature()

            // when
            val changed = changedLinesSince("main")
            val fullyQualified = changedLinesSince("refs/heads/main")

            // then only feature's own edit: main's line 9 is someone else's work, not a local change
            assertEquals(mapOf(featureKt.path to setOf(15)), changed)
            assertEquals(mapOf(featureKt.path to setOf(15)), fullyQualified)
        }

        @Test
        fun `a remote-tracking branch base starts from its merge-base with HEAD`() {
            // given main's tip also published as origin/main
            val mainTip = divergeMainFromFeature()
            repo.updateRef("refs/remotes/origin/main", mainTip)

            // when
            val changed = changedLinesSince("origin/main")

            // then
            assertEquals(mapOf(featureKt.path to setOf(15)), changed)
        }

        @Test
        fun `upstream follows the branch's tracking config, whatever the remote is called`() {
            // given feature tracks main on a remote that is not called origin, which is the only
            // remote JGit's own @{upstream} knows: left to it, the base would be feature itself
            val mainTip = divergeMainFromFeature()
            repo.updateRef("refs/remotes/fork/main", mainTip)
            repo.config("remote", "url", "https://example.invalid/fork.git", subsection = "fork")
            repo.config("remote", "fetch", "+refs/heads/*:refs/remotes/fork/*", subsection = "fork")
            repo.config("branch", "remote", "fork", subsection = "feature")
            repo.config("branch", "merge", "refs/heads/main", subsection = "feature")

            // when each spelling git accepts is used as the base
            // then each starts from the merge-base with main
            for (baseRef in listOf("@{upstream}", "feature@{U}", "HEAD@{upstream}")) {
                assertEquals(mapOf(featureKt.path to setOf(15)), changedLinesSince(baseRef), baseRef)
            }
        }

        @Test
        fun `an upstream that is not configured, or not the whole ref, is refused rather than guessed`() {
            // given a branch with no tracking configuration at all
            divergeMainFromFeature()

            // when any of them is used as the base
            // then it is refused, rather than quietly becoming the branch itself and reporting nothing
            for (baseRef in listOf("@{upstream}", "feature@{u}", "base@{upstream}", "@{upstream}~1", "@{push}")) {
                assertFailsWith<IllegalArgumentException>("baseRef '$baseRef'") { changedLinesSince(baseRef) }
            }
        }

        @Test
        fun `a branch base works from a detached HEAD`() {
            // given feature's tip checked out by SHA
            divergeMainFromFeature()
            repo.checkout(repo.resolve("HEAD").name)

            // when
            val changed = changedLinesSince("main")

            // then
            assertEquals(mapOf(featureKt.path to setOf(15)), changed)
        }

        @Test
        fun `a base that is not a branch is used as given`() {
            // given main's tip reachable as a lightweight tag, an annotated tag and a SHA
            val mainTip = divergeMainFromFeature()
            repo.checkout("main")
            repo.tag("main-tip")
            repo.tag("main-tip-annotated", annotated = true)
            repo.checkout("feature")

            // when each is used as the base
            // then each diffs against that tip, so main's own line 9 differs from the working tree too
            val againstTheTip = mapOf(legacyJava.path to setOf(9), featureKt.path to setOf(15))
            for (baseRef in listOf("main-tip", "main-tip-annotated", mainTip.name, "main~0")) {
                assertEquals(againstTheTip, changedLinesSince(baseRef), baseRef)
            }
        }

        @Test
        fun `a tag named like a branch wins, as in git`() {
            // given a tag called `main` on the base commit, beside the branch of the same name
            val mainTip = divergeMainFromFeature()
            repo.updateRef("refs/tags/main", repo.resolve("base"))

            // when
            val changed = changedLinesSince("main")
            val theBranch = changedLinesSince("refs/heads/main")

            // then `main` means the tag, used as given, while the branch is still reachable in full
            assertEquals(mapOf(featureKt.path to setOf(15)), changed)
            assertEquals(mapOf(featureKt.path to setOf(15)), theBranch)
            assertEquals(mainTip, repo.resolve("refs/heads/main"))
        }

        @Test
        fun `a branch that is ahead of HEAD resolves to HEAD`() {
            // given feature ahead of main, and an uncommitted edit on main
            repo.branch("feature")
            repo.checkout("feature")
            featureKt.edit(1)
            repo.commitAll("feature work")
            repo.checkout("main")
            legacyJava.edit(3)

            // when
            val changed = changedLinesSince("feature")

            // then the merge-base is HEAD itself, so only the uncommitted edit counts
            assertEquals(mapOf(legacyJava.path to setOf(3)), changed)
        }

        @Test
        fun `a branch base is used as given while HEAD is unborn`() {
            // given an orphan branch checked out, with no commit on it yet
            repo.orphanBranch("fresh")
            featureKt.edit(6)

            // when
            val diff = workingTreeDiff(repo.dir, "main")

            // then main's tip is the base, there being no history to find a merge-base in, and HEAD is no commit
            assertEquals(mapOf(featureKt.path to setOf(6)), diff.changedLines)
            assertEquals(repo.resolve("main").name, diff.base.commit)
            assertNull(diff.headCommit)
        }

        @Test
        fun `the diff says which two commits it compared`() {
            // given main moved on after feature forked, and an uncommitted edit on top
            val mainTip = divergeMainFromFeature()
            featureKt.edit(2)

            // when asked with the branch name, and again with that branch's tip as a SHA
            val fromTheBranch = workingTreeDiff(repo.dir, "main")
            val fromTheSha = workingTreeDiff(repo.dir, mainTip.name)

            // then the branch was compared from the fork point and the SHA as given, both against this HEAD
            assertEquals(repo.resolve("base").name, fromTheBranch.base.commit)
            assertEquals(BaseSource.EXPLICIT, fromTheBranch.base.source)
            assertEquals(mainTip.name, fromTheSha.base.commit)
            assertEquals(repo.resolve("HEAD").name, fromTheBranch.headCommit)
            assertEquals(repo.dir.canonicalFile, fromTheBranch.workTree.canonicalFile)
            assertEquals(changedLinesSince("main"), fromTheBranch.changedLines)
        }

        @Test
        fun `a base that names nothing is refused as exactly that`() {
            // given no remote at all, so origin/HEAD is as unknown as a typo

            // then both are the specific refusal a caller can act on, not just an argument error
            assertFailsWith<NoSuchRevisionException> { changedLinesSince("no-such-ref") }
            assertFailsWith<NoSuchRevisionException> { changedLinesSince("origin/HEAD") }
        }

        @Test
        fun `with no base ref, the remote's default branch is used`() {
            // given origin/HEAD pointing at origin/main, which is at main's tip, beyond the fork point
            val mainTip = divergeMainFromFeature()
            repo.updateRef("refs/remotes/origin/main", mainTip)
            repo.linkRef("refs/remotes/origin/HEAD", "refs/remotes/origin/main")

            // when
            val diff = workingTreeDiff(repo.dir, baseRef = null)

            // then the base is origin/HEAD, resolved through its merge-base with HEAD like any branch
            assertEquals("origin/HEAD", diff.base.ref)
            assertEquals(BaseSource.ORIGIN_HEAD, diff.base.source)
            assertEquals(repo.resolve("base").name, diff.base.commit)
            assertEquals(mapOf(featureKt.path to setOf(15)), diff.changedLines)
        }

        @Test
        fun `with no base ref and no origin HEAD, origin's main is guessed, then origin's master`() {
            // given only refs/remotes/origin/master, at main's tip
            val mainTip = divergeMainFromFeature()
            repo.updateRef("refs/remotes/origin/master", mainTip)

            // when, and again once origin/main exists as well
            val master = workingTreeDiff(repo.dir, baseRef = null)
            repo.updateRef("refs/remotes/origin/main", mainTip)
            val main = workingTreeDiff(repo.dir, baseRef = null)

            // then
            assertEquals("origin/master", master.base.ref)
            assertEquals(BaseSource.GUESSED, master.base.source)
            assertEquals(repo.resolve("base").name, master.base.commit)
            assertEquals("origin/main", main.base.ref)
            assertEquals(BaseSource.GUESSED, main.base.source)
        }

        @Test
        fun `a local main is never guessed, and the refusal names the setting to use`() {
            // given a local main and no remote at all, which is this fixture's own shape, as a single-branch clone has

            // when
            val error = assertFailsWith<IllegalArgumentException> { workingTreeDiff(repo.dir, baseRef = null) }

            // then a stale local branch would put the merge-base too far back, so it is not an option; the way out,
            // for a build and for CI, is
            assertContains(error.message!!, "coldSpot { baseRef")
            assertContains(error.message!!, "fetch the branch to compare with, and pass it as -Pcoldspot.base=origin/<target>")
            assertFalse(error.message!!.contains("shallow"), "not a shallow clone")
        }

        @Test
        fun `a remote branch this clone never fetched is refused with the fetch that brings it`() {
            // given no origin/develop, as in a single-branch clone of another branch

            // when
            val error = assertFailsWith<NoSuchRevisionException> { changedLinesSince("origin/develop") }

            // then
            assertEquals(
                "'origin/develop' does not resolve to a commit in this clone: fetch it first " +
                    "(git fetch origin +refs/heads/develop:refs/remotes/origin/develop), or pass the base to compare with as -Pcoldspot.base=origin/<target>",
                error.message,
            )
            // and a name that is not a remote branch gets the way out without the command
            assertEquals(
                "'no-such-ref' does not resolve to a commit in this clone: fetch it first, or pass the base to compare with as -Pcoldspot.base=origin/<target>",
                assertFailsWith<NoSuchRevisionException> { changedLinesSince("no-such-ref") }.message,
            )
        }

        @Test
        fun `a branch with unrelated history is refused`() {
            // given a second root commit that shares nothing with main
            repo.orphanBranch("unrelated")
            repo.commitAll("second root")
            repo.checkout("main")

            // when
            val error = assertFailsWith<IllegalArgumentException> { changedLinesSince("unrelated") }

            // then
            assertContains(error.message!!, "shares no history")
        }

        @Test
        fun `a merge-base cut off by a shallow clone is refused with a way out`() {
            // given both tips grafted parentless, the way `git clone --depth 1` leaves them
            val mainTip = divergeMainFromFeature()
            val featureTip = repo.commitAll("feature tip")
            File(repo.gitDir, "shallow").writeText("${mainTip.name}\n${featureTip.name}\n")

            // when
            val error = assertFailsWith<IllegalArgumentException> { changedLinesSince("main") }

            // then the message says where each side is cut, and the fix: more history, since both branches are there already
            assertEquals(
                "$SHALLOW_CLONE: HEAD and 'main' meet nowhere in the history this clone has: HEAD's history stops at " +
                    "${featureTip.name.take(7)}, and the history of 'main' at ${mainTip.name.take(7)}. $FULL_HISTORY",
                error.message,
            )
            assertFalse(error.message!!.contains("base branch"), "fetching main again would not deepen it")
        }

        @Test
        fun `a base that is not a usable revision is refused`() {
            // given a configured remote, so that `@{upstream}` reaches JGit's own parser
            repo.config("remote", "url", "https://example.invalid/repo.git", subsection = "origin")
            repo.config("remote", "fetch", "+refs/heads/*:refs/remotes/origin/*", subsection = "origin")

            // when any of them is used as the base
            // then it is an argument error, not a JGit exception and not an empty result
            val unusable = listOf(
                "no-such-ref", "", " ", " main", "a..b", "../../outside",
                "base^{tree}", "HEAD:app", // a tree and a blob
                "0123456789abcdef0123456789abcdef01234567", // well formed, but no such object
                "main@{upstream}", // no upstream configured; left to JGit, a NullPointerException
            )
            for (baseRef in unusable) {
                assertFailsWith<IllegalArgumentException>("baseRef '$baseRef'") { changedLinesSince(baseRef) }
            }
        }
    }

    /**
     * Shallow clones, the way CI checks out: diffed when the history the diff needs is all there, refused with the
     * way out when a cut could hide the merge-base or commits in between, never answered from whatever history is
     * left. A cut is written into `.git/shallow` as `git clone --depth` and `git fetch --depth` write it; git, where
     * it is on PATH, reads the same file, and is asked what it makes of each history.
     */
    class ShallowClones : Fixture() {
        /** The history cut at [commits]: their parents never fetched. */
        private fun cutAt(vararg commits: ObjectId) = File(repo.gitDir, "shallow").writeText(commits.joinToString("") { "${it.name}\n" })

        /** What git names as the merge-base of [a] and [b], or null without git on PATH. */
        private fun gitMergeBase(a: String, b: String): String? = git(repo.dir, "merge-base", a, b)?.trim()

        /**
         * feature forks from base and commits f1 and f2, main commits m1, feature merges main and becomes HEAD (x),
         * and main goes on to m2. Their merge-base is m1: feature holds it through the merge.
         */
        private inner class MergedMain {
            val m1: RevCommit
            val f1: RevCommit
            val x: RevCommit
            val m2: RevCommit

            init {
                repo.branch("feature")
                legacyJava.edit(3)
                m1 = repo.commitAll("m1")
                repo.checkout("feature")
                featureKt.edit(5)
                f1 = repo.commitAll("f1")
                featureKt.edit(6)
                repo.commitAll("f2")
                x = repo.merge("main")
                repo.checkout("main")
                legacyJava.edit(4)
                m2 = repo.commitAll("m2")
                repo.checkout("feature")
            }
        }

        @Test
        fun `a clone cut below the merge-base is diffed exactly as a full one`() {
            // given the history cut at base, below the merge-base m1, as a deep enough `--depth` leaves it
            val history = MergedMain()
            val full = workingTreeDiff(repo.dir, "main")
            cutAt(repo.resolve("base"))

            // when
            val cut = workingTreeDiff(repo.dir, "main")

            // then the same merge-base, the same lines, the same commits, which git lists too
            assertEquals(history.m1.name, cut.base.commit)
            assertEquals(full.base.commit, cut.base.commit)
            assertEquals(full.changedLines, cut.changedLines)
            assertEquals(full.commits.listed.map { it.sha }, cut.commits.listed.map { it.sha })
            gitLogSays(repo.dir, "main")?.let { assertEquals(it, cut.commits.listed.map { commit -> commit.sha }, "git log disagrees") }
            gitMergeBase("main", "HEAD")?.let { assertEquals(history.m1.name, it, "git finds another merge-base") }
        }

        @Test
        fun `a clone cut at the merge-base itself is diffed, when HEAD's history reaches it on every way down`() {
            // given main at m1, feature forked there with f1 and f2 as HEAD, main gone on to m2, and m1 parentless
            legacyJava.edit(3)
            val m1 = repo.commitAll("m1")
            repo.branch("feature")
            legacyJava.edit(4)
            repo.commitAll("m2")
            repo.checkout("feature")
            featureKt.edit(5)
            val f1 = repo.commitAll("f1")
            featureKt.edit(6)
            val f2 = repo.commitAll("f2")
            cutAt(m1)

            // when
            val diff = workingTreeDiff(repo.dir, "main")

            // then nothing beyond m1 is needed, and the commits are the ones git lists
            assertEquals(m1.name, diff.base.commit)
            assertEquals(listOf(f2.name, f1.name), diff.commits.listed.map { it.sha })
            gitLogSays(repo.dir, "main")?.let { assertEquals(it, diff.commits.listed.map { commit -> commit.sha }, "git log disagrees") }
        }

        @Test
        fun `a clone cut at the merge-base, which HEAD's history passes another way, is refused, where git lists main's commits as the build's`() {
            // given m1 parentless, and HEAD reaching base through f1 as well: base is main's, but nothing left in
            // this clone shows that it lies below m1
            val history = MergedMain()
            cutAt(history.m1)

            // when
            val error = assertFailsWith<IllegalArgumentException> { workingTreeDiff(repo.dir, "main") }

            // then it is refused at base, which may be main's own: the history below the merge-base is cut at m1
            val base = repo.resolve("base").name.take(7)
            assertEquals(
                "$SHALLOW_CLONE: HEAD's history also reaches $base, a root commit, without passing its merge-base with 'main', " +
                    "and the history below that stops at ${history.m1.name.take(7)}, so whether $base is the base's own cannot be known. $FULL_HISTORY",
                error.message,
            )
            // where git, reading the same cut history, lists base among the build's commits
            gitLogSays(repo.dir, "main")?.let { assertContains(it, repo.resolve("base").name, "git no longer lists base: the danger this guards against is gone") }
        }

        @Test
        fun `a cut on HEAD's side above the merge-base is refused, and fetching the base branch will not do`() {
            // given HEAD's history cut at f1: what lay behind it could be a better merge-base, or more commits
            val history = MergedMain()
            cutAt(history.f1)

            // when
            val error = assertFailsWith<IllegalArgumentException> { workingTreeDiff(repo.dir, "main") }

            // then it says so, and the one fix: more of HEAD's history, which no fetch of main would bring
            assertEquals(
                "$SHALLOW_CLONE: HEAD's history stops at ${history.f1.name.take(7)} before it reaches its merge-base with 'main', " +
                    "so neither that merge-base nor the commits in between can be known. $FULL_HISTORY",
                error.message,
            )
        }

        @Test
        fun `a cut on the base's side that hides the merge-base is refused, where git takes an older one`() {
            // given main: base, m1, m2, and m3 merging a side branch forked from base; feature forked at m1 and is HEAD.
            // Their merge-base is m1, which main reaches only through m2; with m2 cut, the only common commit left
            // in sight is base, further back
            repo.branch("side")
            legacyJava.edit(3)
            val m1 = repo.commitAll("m1")
            repo.branch("feature")
            legacyJava.edit(4)
            val m2 = repo.commitAll("m2")
            repo.checkout("side")
            featureKt.edit(19)
            repo.commitAll("b1")
            repo.checkout("main")
            repo.merge("side")
            repo.checkout("feature")
            featureKt.edit(7)
            repo.commitAll("f1")
            val full = workingTreeDiff(repo.dir, "main")
            cutAt(m2)

            // when
            val error = assertFailsWith<IllegalArgumentException> { workingTreeDiff(repo.dir, "main") }

            // then it is refused on the base's side: main is there, cut, and more of its history is the fix
            assertEquals(m1.name, full.base.commit)
            assertEquals(
                "$SHALLOW_CLONE: the history of 'main' stops at ${m2.name.take(7)} before it reaches its merge-base with HEAD, " +
                    "so the merge-base found may not be the real one. $FULL_HISTORY",
                error.message,
            )
            // where git, reading the same cut history, takes base: a diff from there would show main's m1 as this build's own
            gitMergeBase("main", "HEAD")?.let { assertEquals(repo.resolve("base").name, it, "git no longer takes the older commit: the danger this guards against is gone") }
        }

        @Test
        fun `a commit given as the base needs the history in between as well`() {
            // given m1's SHA as the base, compared with as it is, and the history cut at f1, between HEAD and m1
            val history = MergedMain()
            cutAt(history.f1)

            // when
            val error = assertFailsWith<IllegalArgumentException> { workingTreeDiff(repo.dir, history.m1.name) }

            // then the commits in between cannot all be known
            assertEquals(
                "$SHALLOW_CLONE: HEAD's history stops at ${history.f1.name.take(7)} before it reaches '${history.m1.name}', so the " +
                    "commits in between cannot all be known. $FULL_HISTORY",
                error.message,
            )
            // and cut below it, it is diffed, with the commits git lists
            cutAt(repo.resolve("base"))
            val diff = workingTreeDiff(repo.dir, history.m1.name)
            assertEquals(history.m1.name, diff.base.commit)
            gitLogSays(repo.dir, history.m1.name)?.let { assertEquals(it, diff.commits.listed.map { commit -> commit.sha }, "git log disagrees") }
        }

        @Test
        fun `with no base given and none to guess, a shallow clone says so first`() {
            // given a depth-1 clone: HEAD parentless, no remote-tracking branch at all
            cutAt(repo.resolve("HEAD"))

            // when
            val error = assertFailsWith<IllegalArgumentException> { workingTreeDiff(repo.dir, baseRef = null) }

            // then what is missing, where HEAD's history stops, and what fixes both: the history, with a branch to compare with
            assertEquals(
                "$SHALLOW_CLONE: no base was given, this clone has no origin/HEAD, origin/main or origin/master to compare with, " +
                    "and HEAD's history stops at ${repo.resolve("HEAD").name.take(7)}. Fetch the full history with the branch to " +
                    "compare with (e.g. fetch-depth: 0), and pass that branch as -Pcoldspot.base=origin/<target>, or set " +
                    "coldSpot { baseRef = \"<ref>\" }.",
                error.message,
            )
        }

        @Test
        fun `a base missing from a shallow clone is refused as missing, with the way out`() {
            // given a depth-1 clone that fetched nothing but HEAD
            cutAt(repo.resolve("HEAD"))

            // when
            val error = assertFailsWith<NoSuchRevisionException> { changedLinesSince("origin/main") }

            // then: HEAD's history is cut as well, so main alone would not do; the full history with main does
            assertEquals(
                "$SHALLOW_CLONE: 'origin/main' is not in this clone, and HEAD's history stops at ${repo.resolve("HEAD").name.take(7)}. " +
                    "Fetch the full history with that branch (e.g. fetch-depth: 0; with git itself, git fetch --unshallow " +
                    "origin +refs/heads/main:refs/remotes/origin/main).",
                error.message,
            )
        }

        @Test
        fun `a root HEAD's history reaches besides the merge-base is the build's own, when nothing below the merge-base is cut`() {
            // given feature forked from base, with an unrelated history merged in (a root of its own), main gone on to m1,
            // and the clone cut on another branch only: the history below the merge-base is whole
            repo.branch("other")
            repo.branch("feature")
            legacyJava.edit(3)
            val m1 = repo.commitAll("m1")
            repo.orphanBranch("unrelated")
            listOf(FEATURE_KT, LEGACY_JAVA, "app/src/main/AndroidManifest.xml", "app/build.gradle", "build.gradle.kts", ".gitignore").forEach(repo::delete)
            val other = sourceFile("other/src/main/java/demo/Other.kt", lines = 3)
            val root = repo.commitAll("an unrelated root")
            repo.checkout("feature")
            featureKt.edit(5)
            repo.commitAll("f1")
            repo.merge("unrelated")
            repo.checkout("other")
            legacyJava.edit(9)
            val otherTip = repo.commitAll("o1")
            repo.checkout("feature")
            cutAt(otherTip)

            // when
            val diff = workingTreeDiff(repo.dir, "main")

            // then diffed, the root's file among the changes and its commit among the build's, as git lists them
            assertEquals(repo.resolve("base").name, diff.base.commit)
            assertEquals(setOf(featureKt.path, other.path), diff.changedLines.keys)
            assertContains(diff.commits.listed.map { it.sha }, root.name)
            gitLogSays(repo.dir, "main")?.let { assertEquals(it, diff.commits.listed.map { commit -> commit.sha }, "git log disagrees") }
            assertFalse(m1.name in diff.commits.listed.map { it.sha })
        }
    }

    /**
     * On CI (DECISIONS.md: the `CI` environment variable is `true`) the base is the one given, or none: `origin/HEAD`
     * and the guesses, which the tests above show standing in locally, are refused, with the fix.
     */
    class OnCi : Fixture() {
        private val ciRefusal =
            "No base was given, and on CI (CI=true) ColdSpot takes none it is not given, neither origin/HEAD nor a guess: " +
                "pass the branch the change is for as -Pcoldspot.base=origin/<target>, or set coldSpot { baseRef = \"<ref>\" }."

        @Test
        fun `a base nobody gave is refused, whether origin main would be guessed or origin HEAD taken`() {
            // given main published as origin/main, feature checked out with an edit of its own
            repo.updateRef("refs/remotes/origin/main", repo.resolve("main"))
            repo.branch("feature")
            repo.checkout("feature")
            featureKt.edit(6)
            repo.commitAll("feature work")

            // when origin/main would be guessed, and once origin/HEAD points at it
            val guessing = assertFailsWith<IllegalArgumentException> { workingTreeDiff(repo.dir, baseRef = null, onCi = true) }
            repo.linkRef("refs/remotes/origin/HEAD", "refs/remotes/origin/main")
            val takingOriginHead = assertFailsWith<IllegalArgumentException> { workingTreeDiff(repo.dir, baseRef = null, onCi = true) }

            // then both are refused with the fix, where a build off CI takes origin/HEAD
            assertEquals(ciRefusal, guessing.message)
            assertEquals(ciRefusal, takingOriginHead.message)
            assertEquals(BaseSource.ORIGIN_HEAD, workingTreeDiff(repo.dir, baseRef = null).base.source)
        }

        @Test
        fun `a base given is compared with as anywhere`() {
            // given feature forked from main, which has moved on
            repo.branch("feature")
            legacyJava.edit(9)
            repo.commitAll("main moves on")
            repo.checkout("feature")
            featureKt.edit(15)
            repo.commitAll("feature work")

            // when
            val onCi = workingTreeDiff(repo.dir, "main", onCi = true)
            val local = workingTreeDiff(repo.dir, "main")

            // then the same comparison, from the same merge-base
            assertEquals(local.base.commit, onCi.base.commit)
            assertEquals(BaseSource.EXPLICIT, onCi.base.source)
            assertEquals(mapOf(featureKt.path to setOf(15)), onCi.changedLines)
            assertEquals(local.changedLines, onCi.changedLines)
        }

        @Test
        fun `a shallow clone with no base given is told both fixes at once`() {
            // given actions/checkout's default: HEAD alone, parentless, and nothing else fetched
            File(repo.gitDir, "shallow").writeText("${repo.resolve("HEAD").name}\n")

            // when
            val error = assertFailsWith<IllegalArgumentException> { workingTreeDiff(repo.dir, baseRef = null, onCi = true) }

            // then the base to pass, and the history to fetch, in one go: a CI run is too slow to learn them one by one
            assertEquals(
                "$ciRefusal The clone is shallow as well, HEAD's history stopping at ${repo.resolve("HEAD").name.take(7)}: fetch " +
                    "the full history too (e.g. fetch-depth: 0).",
                error.message,
            )
        }
    }

    /** Finding the repository, and refusing to guess when the one found is not the one meant. */
    class RepositoryDiscovery : Fixture() {

        @Test
        fun `paths stay relative to the work tree root when called from a subdirectory`() {
            // given an edit, and a caller somewhere below the root
            featureKt.edit(6)

            // when
            val changed = changedLinesSince(callFrom = File(repo.dir, "app/src/main"))

            // then the key is still the path from the work tree root
            assertEquals(mapOf(featureKt.path to setOf(6)), changed)
        }

        @Test
        fun `a work tree recorded under another spelling of the same path is accepted`() {
            // given core.worktree holding the canonical path, as a caller reaching it through a symlink sees
            repo.config("core", "worktree", repo.dir.canonicalPath)
            featureKt.edit(6)

            // when
            val changed = changedLinesSince(callFrom = File(repo.dir, "app"))

            // then
            assertEquals(mapOf(featureKt.path to setOf(6)), changed)
        }

        @Test
        fun `a work tree recorded somewhere else is refused rather than diffed as empty`() {
            // given the shape a moved linked worktree, or a stale core.worktree, leaves behind
            repo.config("core", "worktree", tmp.newFolder("elsewhere").path)

            // when
            val error = assertFailsWith<IllegalArgumentException> { changedLinesSince() }

            // then
            assertContains(error.message!!, "is not inside")
        }

        @Test
        fun `a directory outside any repository is refused`() {
            // given a directory with no repository above it
            val plain = tmp.newFolder("plain")
            assumeTrue("java.io.tmpdir is itself inside a checkout", FileRepositoryBuilder().findGitDir(plain).gitDir == null)

            // when
            val error = assertFailsWith<IllegalArgumentException> { changedLines(plain, "HEAD") }

            // then it says what is missing, and what to build from instead
            assertContains(error.message!!, "No git repository at or above")
            assertContains(error.message!!, "a source archive or a copy without .git has none. Build from a git clone, with its history (in CI: fetch-depth: 0)")
        }

        @Test
        fun `a bare repository is refused`() {
            // given a clone with no working tree to diff
            val bare = tmp.newFolder("bare.git")
            Git.cloneRepository().setBare(true).setURI(repo.dir.toURI().toString()).setDirectory(bare).call().close()

            // when
            val error = assertFailsWith<IllegalArgumentException> { changedLines(bare, "HEAD") }

            // then
            assertContains(error.message!!, "bare")
        }

        @Test
        fun `an object a partial clone never fetched fails with a way out`() {
            // given the base version of an edited file missing from the object database
            repo.config("remote", "promisor", "true", subsection = "origin")
            featureKt.edit(2)
            repo.forget("base:${featureKt.path}")

            // when
            val error = assertFailsWith<IOException> { changedLinesSince() }

            // then the message says what happened, how to check out instead, and which git command fetches what is missing
            assertContains(error.message!!, "partial clone")
            assertContains(error.message!!, "Clone without --filter (in CI: no `filter` on the checkout)")
            assertContains(error.message!!, "git diff ${repo.resolve("base").name} -- ")
        }
    }

    /** The branch HEAD is on, for the app's header: `git branch --show-current`, which git itself confirms when it is on PATH. */
    class HeadBranch : Fixture() {

        /** Asserts that git names the same branch, or none: git is the reference. */
        private fun assertAgreesWithGit(branch: String?) {
            val says = gitSaysBranch(repo.dir) ?: return
            assertEquals(says, branch.orEmpty(), "git branch --show-current disagrees, so the expectation itself is wrong")
        }

        @Test
        fun `the branch is named as git names it, slashes and all, without refs-heads`() {
            // given the fixture's main, and then a branch with a path for a name
            val onMain = workingTreeDiff(repo.dir, "base").headBranch
            assertAgreesWithGit(onMain)
            repo.branch("feature/login-form")
            repo.checkout("feature/login-form")

            // when
            val onFeature = workingTreeDiff(repo.dir, "base").headBranch

            // then
            assertEquals("main", onMain)
            assertEquals("feature/login-form", onFeature)
            assertAgreesWithGit(onFeature)
        }

        @Test
        fun `a detached HEAD is on no branch, whatever branches point at its commit`() {
            // given main's tip checked out by SHA: main and the tag base both name that commit
            repo.checkout(repo.resolve("HEAD").name)

            // when
            val diff = workingTreeDiff(repo.dir, "base")

            // then there is a commit, and no branch
            assertNull(diff.headBranch)
            assertEquals(repo.resolve("HEAD").name, diff.headCommit)
            assertAgreesWithGit(diff.headBranch)
        }

        @Test
        fun `a branch without a commit yet is a branch all the same`() {
            // given an orphan branch checked out, unborn
            repo.orphanBranch("fresh")

            // when
            val diff = workingTreeDiff(repo.dir, "main")

            // then HEAD is on it, with no commit
            assertEquals("fresh", diff.headBranch)
            assertNull(diff.headCommit)
            assertAgreesWithGit(diff.headBranch)
        }

        @Test
        fun `a tag of the branch's name does not pass for the branch`() {
            // given HEAD detached at a commit that a tag called like a branch points at
            repo.tag("release")
            repo.checkout("refs/tags/release")

            // when
            val diff = workingTreeDiff(repo.dir, "base")

            // then
            assertNull(diff.headBranch)
            assertAgreesWithGit(diff.headBranch)
        }
    }

    /** The commits in the build: `git log <base>..HEAD`, which git itself confirms when it is on PATH. */
    class CommitsInBuild : Fixture() {

        /** Asserts that git lists the same commits in the same order, and counts as many: git log is the reference. */
        private fun assertAgreesWithGitLog(commits: Commits, base: String) {
            val log = gitLogSays(repo.dir, base) ?: return
            assertEquals(log.take(MAX_LISTED_COMMITS), commits.listed.map { it.sha }, "git log disagrees, so the expectation itself is wrong")
            assertEquals(log.size, commits.total, "git log counts differently")
        }

        @Test
        fun `every commit since the base is listed, newest first as git log prints them`() {
            // given three commits on top of the base, the middle one with a body under its first line
            featureKt.edit(1)
            val first = repo.commitAll("first")
            featureKt.edit(2)
            val second = repo.commitAll("second\n\nA body that is no part of the summary.")
            featureKt.edit(3)
            val third = repo.commitAll("third")

            // when
            val commits = workingTreeDiff(repo.dir, "base").commits

            // then
            assertEquals(listOf(third, second, first).map { it.name }, commits.listed.map { it.sha })
            assertEquals(listOf("third", "second", "first"), commits.listed.map { it.summary })
            assertEquals(listOf(third, second, first).map { it.name.take(7) }, commits.listed.map { it.shortSha })
            assertEquals(3, commits.total)
            assertAgreesWithGitLog(commits, "base")
        }

        @Test
        fun `a base that is HEAD itself leaves no commit in the build`() {
            // given an uncommitted edit and nothing else
            featureKt.edit(4)

            // when
            val commits = workingTreeDiff(repo.dir, "HEAD").commits

            // then
            assertEquals(emptyList(), commits.listed)
            assertEquals(0, commits.total)
            assertAgreesWithGitLog(commits, "HEAD")
        }

        @Test
        fun `with main merged into the branch, main's commits are not in the build, the merge is`() {
            // given feature forked from base, main moved on and got published as origin/main, and feature
            // merged main and went on
            repo.branch("feature")
            legacyJava.edit(9)
            val mainWork = repo.commitAll("main moves on")
            repo.updateRef("refs/remotes/origin/main", mainWork)
            repo.checkout("feature")
            featureKt.edit(15)
            val featureWork = repo.commitAll("feature work")
            val merge = repo.merge("main")
            featureKt.edit(16)
            val moreWork = repo.commitAll("more feature work")

            // when the base is the module's to choose: origin/main, an ancestor of HEAD since the merge
            val diff = workingTreeDiff(repo.dir, baseRef = null)

            // then main's commit is upstream work, not something this branch adds; the merge itself is
            assertEquals("origin/main", diff.base.ref)
            assertEquals(mainWork.name, diff.base.commit)
            assertEquals(listOf(moreWork, merge, featureWork).map { it.name }, diff.commits.listed.map { it.sha })
            assertEquals(3, diff.commits.total)
            assertAgreesWithGitLog(diff.commits, "origin/main")
        }

        @Test
        fun `with another feature branch merged in, its commits are in the build too`() {
            // given two branches off the base, each with a commit of its own, and feature merging other before going on
            repo.branch("feature")
            repo.branch("other")
            repo.checkout("other")
            legacyJava.edit(9)
            val otherWork = repo.commitAll("other work")
            repo.checkout("feature")
            featureKt.edit(15)
            val featureWork = repo.commitAll("feature work")
            val merge = repo.merge("other")
            featureKt.edit(16)
            val moreWork = repo.commitAll("more feature work")

            // when
            val commits = workingTreeDiff(repo.dir, "base").commits

            // then what the merge brought in is in the build as well, not only the first-parent line of history
            assertEquals(listOf(moreWork, merge, featureWork, otherWork).map { it.name }, commits.listed.map { it.sha })
            assertEquals(4, commits.total)
            assertAgreesWithGitLog(commits, "base")
        }

        @Test
        fun `the list stops at 50, the total does not`() {
            // given 60 commits on top of the base
            val commits = (1..60).map { n ->
                repo.write("notes.txt", "$n\n")
                repo.commitAll("commit $n")
            }

            // when
            val inBuild = workingTreeDiff(repo.dir, "base").commits

            // then the newest 50 are listed, and the total says what was left out
            assertEquals(commits.asReversed().take(50).map { it.name }, inBuild.listed.map { it.sha })
            assertEquals(60, inBuild.total)
            assertAgreesWithGitLog(inBuild, "base")
        }
    }

    /**
     * Whether the build is HEAD's: any file counts, the index is read but never written, and where the working
     * tree needs interpreting (links, submodules, filters, line endings) the answer is `git status`'s.
     */
    class UncommittedChanges : Fixture() {

        /**
         * Asserts the verdict, and that `git status` gives the same one when git is on PATH: git is the
         * reference for what counts as a change. It runs after the call under test, as it refreshes the index.
         */
        private fun assertVerdict(dirty: Boolean, what: String) {
            assertEquals(dirty, hasUncommittedChanges(repo.dir), what)
            gitStatusSaysDirty(repo.dir)?.let { assertEquals(dirty, it, "git status disagrees, so the expectation itself is wrong: $what") }
        }

        @Test
        fun `a clean working tree has none`() {
            assertFalse(hasUncommittedChanges(repo.dir))
        }

        @Test
        fun `any file that differs counts, not only a source file`() {
            // given an edited manifest XML and nothing else
            repo.write("app/src/main/AndroidManifest.xml", "<manifest package=\"demo\"/>\n")

            // then the APK built from this tree would not be HEAD's
            assertTrue(hasUncommittedChanges(repo.dir))
        }

        @Test
        fun `an untracked file counts, an ignored one does not`() {
            // given a file under the ignored build directory
            sourceFile("app/build/generated/Generated.kt", lines = 2)
            assertFalse(hasUncommittedChanges(repo.dir))

            // and then a file git would list as untracked
            repo.write("notes.txt", "todo\n")
            assertTrue(hasUncommittedChanges(repo.dir))
        }

        @Test
        fun `a deleted file counts, and so does a staged edit`() {
            featureKt.delete()
            assertTrue(hasUncommittedChanges(repo.dir))

            featureKt.revert()
            legacyJava.edit(5)
            repo.stage(legacyJava.path)
            assertTrue(hasUncommittedChanges(repo.dir), "what is on disk is what gets built, staged or not")
        }

        @Test
        fun `the executable bit alone counts only when core fileMode says so`() {
            // given only the mode of a tracked file changed
            assumeTrue("needs a filesystem with an executable bit", !featureKt.file.canExecute() && featureKt.file.setExecutable(true))

            // then git's core.fileMode decides, as it does for git status
            repo.config("core", "filemode", "false")
            assertFalse(hasUncommittedChanges(repo.dir))
            repo.config("core", "filemode", "true")
            assertTrue(hasUncommittedChanges(repo.dir))
        }

        @Test
        fun `a tracked symlink in a clean tree is not a change`() {
            // given a committed symlink, such as AGENT.md -> AGENTS.md
            try {
                Files.createSymbolicLink(File(repo.dir, "app/Alias.kt").toPath(), File("src/main/java/demo/Feature.kt").toPath())
            } catch (e: IOException) {
                assumeNoException("needs a filesystem with symlinks", e)
            } catch (e: UnsupportedOperationException) {
                assumeNoException("needs a filesystem with symlinks", e)
            }
            repo.commitAll("link")

            // then, as to git status; JGit's idEqual, which never hashes a working-tree symlink, must not decide it
            assertVerdict(dirty = false, "a symlink still pointing where it did when committed")
        }

        @Test
        fun `a submodule left uninitialised is not a change`() {
            // given a submodule recorded in HEAD the way a clone without --recurse-submodules leaves it:
            // a gitlink, a .gitmodules entry, and an empty directory with no repository behind it
            val subCommit = ObjectId.fromString("0123456789abcdef0123456789abcdef01234567")
            repo.write(".gitmodules", "[submodule \"sub\"]\n\tpath = sub\n\turl = ../sub\n")
            repo.stage(".gitmodules")
            repo.addGitlink("sub", subCommit)
            repo.commitStaged("submodule")
            assertEquals(subCommit, repo.resolve("HEAD:sub"), "the fixture lost its gitlink")

            // then, as to git status
            assertVerdict(dirty = false, "a submodule nobody has initialised")
        }

        @Test
        fun `a file kept as an LFS pointer is not a change`() {
            // given git-lfs's configuration, with a clean filter that turns the bytes on disk into their pointer
            // the way `git-lfs clean` would, so that the pointer is what gets committed
            assumeTrue("needs a POSIX shell to run the filter", File("/bin/sh").canExecute())
            val pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:${"ab".repeat(32)}\nsize 4096\n"
            val toPointer = pointer.lines().filter { it.isNotEmpty() }.joinToString("; ", prefix = "cat >/dev/null; ") { "echo $it" }
            repo.config("filter", "clean", toPointer, subsection = "lfs")
            repo.config("filter", "smudge", "cat", subsection = "lfs")
            repo.config("filter", "required", "true", subsection = "lfs")
            repo.write(".gitattributes", "*.bin filter=lfs diff=lfs merge=lfs -text\n")
            File(repo.dir, "asset.bin").writeBytes(ByteArray(4096) { it.toByte() })
            repo.commitAll("asset")
            assertEquals(pointer, repo.readBlob("HEAD:asset.bin").decodeToString(), "the fixture did not commit a pointer")

            // then, as to git status: the filter is run again and its output compared, not the bytes on disk
            assertVerdict(dirty = false, "the real bytes behind a committed LFS pointer")
        }

        @Test
        fun `a CRLF copy of an LF blob under autocrlf is a change, as it is to git status`() {
            // given a file committed with LF well before core.autocrlf=true was set, then re-saved with CRLF.
            // git status decides on the size alone and reports a modification without looking inside, even
            // though `git add` would find nothing to stage; IndexDiff does the same. Ageing the file keeps the
            // index entry from being smudged as racy, which would make both of them look inside instead.
            featureKt.file.setLastModified(System.currentTimeMillis() - 3_600_000)
            repo.stage(featureKt.path)
            repo.config("core", "autocrlf", "true")
            featureKt.rewrite(eol = CRLF)

            // then, as to git status
            assertVerdict(dirty = true, "a CRLF working copy of an LF blob under core.autocrlf=true")
        }

        @Test
        fun `the index is read but left exactly as it was`() {
            // given an index written by the fixture's last commit, a change on disk to find, and a clean file whose
            // stat no longer matches its index entry: the case in which git status itself rewrites the index
            val index = File(repo.gitDir, "index")
            val before = index.readBytes()
            val modifiedAt = Files.getLastModifiedTime(index.toPath())
            featureKt.edit(3)
            legacyJava.file.setLastModified(System.currentTimeMillis() - 60_000)

            // when
            assertTrue(hasUncommittedChanges(repo.dir))

            // then not a byte of it changed, nor was it rewritten in place, nor locked
            assertTrue(before.contentEquals(index.readBytes()), "the index was rewritten")
            assertEquals(modifiedAt, Files.getLastModifiedTime(index.toPath()), "the index was written in place")
            assertFalse(File(repo.gitDir, "index.lock").exists(), "a lock file was left behind")
        }
    }

    /** JGit's own housekeeping must not reach the developer's home directory. */
    class HomeDirectory : Fixture() {

        @After
        fun restoreTheSystemReader() = SystemReader.setInstance(null)

        @Test(timeout = 180_000)
        fun `a first call in a fresh JVM writes nothing into the home directory`() {
            // given an empty home directory, and a JVM that has never measured a filesystem before
            val home = tmp.newFolder("home")
            featureKt.edit(3)

            // when
            val output = firstCallInAFreshJvm(repo.dir, home)

            // then no jgit config was saved, and nothing else was written there either
            val written = home.walkTopDown().filter { it.isFile }.map { it.relativeTo(home).path }.toList()
            assertTrue(written.isEmpty(), "wrote $written into the home directory (the call itself: $output)")
        }

        @Test
        fun `an existing jgit config is read, and saving it leaves the file alone`() {
            // given a jgit config that already holds a measurement
            val jgitConfig = tmp.newFile("jgit-config")
            jgitConfig.writeText("[filesystem \"probe\"]\n\ttimestampResolution = 42 nanoseconds\n")
            val onDisk = jgitConfig.readText()
            SystemReader.setInstance(JGitConfigAt(jgitConfig, SystemReader.getInstance()))

            // when the module has run, and JGit then persists a measurement the way FS.saveToConfig does
            changedLinesSince()
            val config = SystemReader.getInstance().getJGitConfig()
            val readBack = config.getString("filesystem", "probe", "timestampResolution")
            config.setString("filesystem", "probe", "timestampResolution", "1 seconds")
            config.save()

            // then the file was read, and the save was a no-op
            assertEquals("42 nanoseconds", readBack)
            assertEquals(onDisk, jgitConfig.readText())
        }

        @Test
        fun `a global gitignore named by the user's config still excludes untracked files`() {
            // given a user config pointing core.excludesFile at a global gitignore, as a developer's would
            val globalIgnore = tmp.newFile("global-gitignore")
            globalIgnore.writeText("*.tmp.kt\n")
            val userConfig = tmp.newFile("user-gitconfig")
            userConfig.writeText("[core]\n\texcludesFile = ${globalIgnore.path}\n")
            SystemReader.setInstance(UserConfigAt(userConfig, SystemReader.getInstance()))

            // and two untracked files, one of which that gitignore covers
            sourceFile("app/src/main/java/demo/Scratch.tmp.kt", lines = 3)
            val kept = sourceFile("app/src/main/java/demo/Kept.kt", lines = 2)

            // then only the other one is reported: the module stubs JGit's own config, nobody else's
            assertEquals(mapOf(kept.path to (1..2).toSet()), changedLinesSince())
        }

        /**
         * A reader whose JGit config is [file], somewhere disposable. Overriding `getenv` would not do:
         * the default reader resolves `$XDG_CONFIG_HOME` by calling its own `getenv`, never a wrapper's.
         */
        private class JGitConfigAt(private val file: File, delegate: SystemReader) : SystemReader.Delegate(delegate) {
            override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig = FileBasedConfig(parent, file, fs)
        }

        /** A reader whose user config is [file], standing in for a developer's `~/.gitconfig`. */
        private class UserConfigAt(private val file: File, delegate: SystemReader) : SystemReader.Delegate(delegate) {
            override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig = FileBasedConfig(parent, file, fs)
        }
    }

    /** .gitignore covers untracked files only, which without an index means "not in the base or HEAD". */
    class IgnoreRules : Fixture() {

        @Test
        fun `an ignored untracked source is absent`() {
            // given a generated file under the ignored build directory
            sourceFile("app/build/generated/Generated.kt", lines = 4)

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(emptyMap(), changed)
        }

        @Test
        fun `a tracked source under an ignored directory is still diffed, its untracked siblings are not`() {
            // given a committed file whose directory was ignored afterwards, then edited
            val tracked = sourceFile("legacy/Tracked.java", lines = 10)
            repo.commitAll("track a file")
            repo.write(".gitignore", "build/\nlegacy/\n")
            repo.commitAll("then ignore its directory")
            tracked.edit(4)

            // and a new file beside it that the same rule covers
            sourceFile("legacy/Untracked.java", lines = 3)

            // when
            val changed = changedLinesSince("HEAD")

            // then the tracked file is diffed, and only it
            assertEquals(mapOf(tracked.path to setOf(4)), changed)
        }

        @Test
        fun `a source committed since the base is diffed even where gitignore now covers it`() {
            // given a file committed after the base, under a directory that is ignored by now
            val committed = sourceFile("gen/Committed.kt", lines = 3)
            repo.commitAll("track a file")
            repo.write(".gitignore", "build/\ngen/\n")
            repo.commitAll("then ignore its directory")
            sourceFile("gen/Untracked.kt", lines = 3)

            // when
            val changed = changedLinesSince()

            // then all of it is new relative to the base, and its untracked neighbour still is not
            assertEquals(mapOf(committed.path to (1..3).toSet()), changed)
        }

        @Test
        fun `a source committed since a branch base is diffed even where gitignore now covers it`() {
            // given the same, on a branch, where the base is reached through a merge-base
            repo.branch("feature")
            repo.checkout("feature")
            val committed = sourceFile("gen/Committed.kt", lines = 3)
            repo.commitAll("track a file")
            repo.write(".gitignore", "build/\ngen/\n")
            repo.commitAll("then ignore its directory")

            // when
            val changed = changedLinesSince("main")

            // then
            assertEquals(mapOf(committed.path to (1..3).toSet()), changed)
        }

        @Test
        fun `an untracked source ignored by a file pattern is absent`() {
            // given a pattern that ignores a file rather than a directory
            repo.write(".gitignore", "build/\nScratch*.kt\n")
            sourceFile("app/src/main/java/demo/Scratch1.kt", lines = 3)
            val kept = sourceFile("app/src/main/java/demo/Kept.kt", lines = 2)

            // when
            val changed = changedLinesSince()

            // then only the file the pattern misses
            assertEquals(mapOf(kept.path to (1..2).toSet()), changed)
        }
    }

    /** Which lines end up in the result, and which files are paired with one that went away. */
    class ChangedLineRules : Fixture() {

        @Test
        fun `a pure line removal leaves the file absent`() {
            // given line 8 removed and nothing else touched
            featureKt.write(featureKt.contentWithout(8))

            // when
            val changed = changedLinesSince()

            // then nothing: a removal leaves no new line to colour
            assertEquals(emptyMap(), changed)
        }

        @Test
        fun `a pure rename is absent`() {
            // given the file moved with its content untouched
            featureKt.moveTo(RENAMED_KT)

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(emptyMap(), changed)
        }

        @Test
        fun `a rewritten file under a new name is a new file`() {
            // given the old file deleted and an unrelated one added
            featureKt.delete()
            val rewrite = sourceFile("app/src/main/java/demo/Rewrite.kt", lines = 6, prefix = "different")

            // when
            val changed = changedLinesSince()

            // then every line, because nothing pairs it with the file that went away
            assertEquals(mapOf(rewrite.path to (1..6).toSet()), changed)
        }

        @Test
        fun `a file under 50 percent similar to a deleted one is a new file`() {
            // given 11 of the 20 lines replaced by other declarations of the same length: 42% kept, by bytes
            val mostlyNew = "app/src/main/java/demo/Mostly.kt"
            featureKt.delete()
            repo.write(mostlyNew, featureKt.contentRewriting(10..20))

            // when
            val changed = changedLinesSince()

            // then it is not a rename, so all of it counts as new code
            assertEquals(mapOf(mostlyNew to (1..20).toSet()), changed)
        }

        @Test
        fun `a file just over 50 percent similar to a deleted one is a rename, as it is to git`() {
            // given 9 of the 20 lines replaced: 52% kept by bytes, which JGit's own bar of 60 would refuse
            val mostlyKept = "app/src/main/java/demo/Mostly.kt"
            featureKt.delete()
            repo.write(mostlyKept, featureKt.contentRewriting(12..20))

            // when
            val changed = changedLinesSince()

            // then only the rewritten lines
            assertEquals(mapOf(mostlyKept to (12..20).toSet()), changed)
        }

        @Test
        fun `a second file resembling a deleted one is a new file, as in git`() {
            // given the file deleted and two look-alikes added: a close one and a weaker one
            val variant = "app/src/main/java/demo/Variant.kt"
            featureKt.delete()
            repo.write(RENAMED_KT, featureKt.contentWith(7))
            repo.write(variant, featureKt.contentRewriting(2..6))

            // when
            val changed = changedLinesSince()

            // then the closest is the rename, and the other is new code rather than a JGit copy of it
            assertEquals(mapOf(RENAMED_KT to setOf(7), variant to (1..20).toSet()), changed)
        }

        @Test
        fun `a java to kotlin conversion keeping most lines is a rename`() {
            // given Legacy.java renamed to Legacy.kt with one line edited
            val converted = legacyJava.moveTo("app/src/main/java/demo/Legacy.kt").edit(1)

            // when
            val changed = changedLinesSince()

            // then only the edited line
            assertEquals(mapOf(converted.path to setOf(1)), changed)
        }

        @Test
        fun `removing the trailing newline reports the last line, as git does`() {
            // given the file's final newline dropped
            featureKt.write(featureKt.original.trimEnd('\n'))

            // when
            val changed = changedLinesSince()

            // then the line that lost it
            assertEquals(mapOf(featureKt.path to setOf(20)), changed)
        }

        @Test
        fun `paths and line numbers come back in ascending order`() {
            // given edits made in no particular order, in files added in no particular order
            featureKt.edit(17, 2)
            for (path in listOf("zeta/Z.kt", "alpha/A.kt", "mid/M.java", "beta/B.kt")) sourceFile(path, lines = 1)

            // when
            val changed = changedLinesSince()

            // then both the paths and each file's lines come back sorted
            assertEquals(
                listOf("alpha/A.kt", featureKt.path, "beta/B.kt", "mid/M.java", "zeta/Z.kt"),
                changed.keys.toList(),
            )
            assertEquals(listOf(2, 17), changed.getValue(featureKt.path).toList())
        }

        @Test
        fun `an executable source is diffed like any other`() {
            // given a new and an edited source, both with the executable bit set
            val script = sourceFile("app/src/main/java/demo/Script.kt", lines = 2)
            featureKt.edit(6)
            assumeTrue(
                "needs a filesystem with an executable bit",
                script.file.setExecutable(true) && featureKt.file.setExecutable(true),
            )

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(mapOf(featureKt.path to setOf(6), script.path to (1..2).toSet()), changed)
        }

        @Test
        fun `the executable bit alone is not a change`() {
            // given only the file mode changed
            assumeTrue(
                "needs a filesystem with an executable bit",
                !featureKt.file.canExecute() && featureKt.file.setExecutable(true),
            )

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(emptyMap(), changed)
        }

        @Test
        fun `a symlinked source is absent`() {
            // given a symlink whose name looks like a Kotlin file
            val link = File(repo.dir, "app/src/main/java/demo/Alias.kt").toPath()
            try {
                Files.createSymbolicLink(link, featureKt.file.toPath())
            } catch (e: IOException) {
                assumeNoException("needs a filesystem with symlinks", e)
            } catch (e: UnsupportedOperationException) {
                assumeNoException("needs a filesystem with symlinks", e)
            }

            // when
            val changed = changedLinesSince()

            // then the target's lines are not reported under the link's name
            assertEquals(emptyMap(), changed)
        }
    }

    /** Line endings, content that cannot be numbered as lines, and content that cannot be read. */
    class FileContent : Fixture() {

        @Test
        fun `a CRLF working copy of an LF blob reports only the real edit`() {
            // given both files re-saved with CRLF endings, one of them also edited
            legacyJava.rewrite(eol = CRLF)
            featureKt.edit(13, eol = CRLF)

            // when, under each value of core.autocrlf the checkout might carry
            val settings = listOf("false", "input", "true")
            val changed = settings.associateWith { autocrlf ->
                repo.config("core", "autocrlf", autocrlf)
                changedLinesSince()
            }

            // then the line endings are never the difference
            assertEquals(settings.associateWith { mapOf(featureKt.path to setOf(13)) }, changed)
        }

        @Test
        fun `a CRLF blob under autocrlf reports only the real edit`() {
            // given a repository committed with CRLF, checked out the way Git for Windows does
            legacyJava.rewrite(eol = CRLF)
            featureKt.rewrite(eol = CRLF)
            repo.commitAll("CRLF committed as is")
            repo.config("core", "autocrlf", "true")
            featureKt.edit(13, eol = CRLF)

            // when
            val changed = changedLinesSince("HEAD")

            // then
            assertEquals(mapOf(featureKt.path to setOf(13)), changed)
        }

        @Test
        fun `a CRLF working copy mandated by gitattributes reports only the real edit`() {
            // given .gitattributes asking for CRLF in the working tree, which JGit does not honour here
            repo.write(".gitattributes", "*.kt text eol=crlf\n*.java text eol=crlf\n")
            repo.commitAll("attributes")
            legacyJava.rewrite(eol = CRLF)
            featureKt.edit(13, eol = CRLF)

            // when
            val changed = changedLinesSince("HEAD")

            // then
            assertEquals(mapOf(featureKt.path to setOf(13)), changed)
        }

        @Test
        fun `a text=auto attribute over a CRLF working copy reports only the real edit`() {
            // given LF committed under `* text=auto`, with core.autocrlf off so that only the attribute
            // could ask for a conversion, and the checkout re-saved with CRLF plus one real edit
            repo.write(".gitattributes", "* text=auto\n")
            repo.commitAll("attributes")
            repo.config("core", "autocrlf", "false")
            legacyJava.rewrite(eol = CRLF)
            featureKt.edit(13, eol = CRLF)

            // when
            val changed = changedLinesSince("HEAD")

            // then
            assertEquals(mapOf(featureKt.path to setOf(13)), changed)
        }

        @Test
        fun `a rename with an edit and a line-ending conversion reports only the edited line`() {
            // given the file moved, edited, and re-saved with CRLF endings all at once
            featureKt.moveTo(RENAMED_KT).edit(7, eol = CRLF)

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(mapOf(RENAMED_KT to setOf(7)), changed)
        }

        @Test
        fun `a lone CR ends a line, as it does for the compilers`() {
            // given committed files with classic-Mac and mixed endings, then one line edited in each.
            // JGit's own diff calls a lone CR binary and reports nothing, and git numbers by LF alone,
            // where javac and kotlinc - and so JaCoCo - count the CR as a line break.
            featureKt.write("val first = 1\nval second = 2\rval third = 3\nval last = 4\n")
            legacyJava.write("val a = 1\rval b = 2\rval c = 3\r")
            repo.commitAll("classic Mac line endings")
            featureKt.write("val first = 1\nval second = 2\rval third = 3\nval last = 400\n")
            legacyJava.write("val a = 1\rval b = 200\rval c = 3\r")

            // when
            val changed = changedLinesSince("HEAD")

            // then the fourth line of one file and the second of the other
            assertEquals(mapOf(legacyJava.path to setOf(2), featureKt.path to setOf(4)), changed)
        }

        @Test
        fun `a UTF-16 source is absent`() {
            // given one edited file and one new file saved as UTF-16, which is full of NUL bytes
            featureKt.file.writeText(featureKt.contentWith(2), Charsets.UTF_16)
            val wide = sourceFile("app/src/main/java/demo/Wide.kt", lines = 3)
            wide.file.writeText(wide.original, Charsets.UTF_16LE)

            // when
            val changed = changedLinesSince()

            // then neither can be numbered as lines, so neither is reported
            assertEquals(emptyMap(), changed)
        }

        @Test
        fun `a NUL within the first 8000 bytes makes a source binary, as in git`() {
            // given a NUL as the 7999th byte of one file and as the 8001st of the other
            val padding = "// " + "x".repeat(7994) + "\n" // 7998 bytes
            featureKt.write(padding + "\u0000\n")
            legacyJava.write(padding + "\n\n\u0000\n")

            // when
            val changed = changedLinesSince()

            // then only the file whose NUL falls outside the window git inspects, with all of its lines
            assertEquals(mapOf(legacyJava.path to (1..4).toSet()), changed)
        }

        @Test
        fun `a NUL beyond the first 8000 bytes does not make a source binary, as in git`() {
            // given a long committed file whose only NUL sits past that window
            val strayNul = "// stray \u0000 in a comment\n"
            val long = sourceFile("app/src/main/java/demo/Long.kt", lines = 600)
            val committed = long.original + strayNul
            check(committed.indexOf('\u0000') > 8000) { "the NUL has to fall outside the first 8000 bytes" }
            long.write(committed)
            repo.commitAll("long file")

            // and line 5 of it edited
            long.write(long.contentWith(5) + strayNul)

            // when
            val changed = changedLinesSince("HEAD")

            // then only that line
            assertEquals(mapOf(long.path to setOf(5)), changed)
        }

        @Test
        fun `a binary old side makes every line of the new side new`() {
            // given a file committed as UTF-16 and then saved as ordinary UTF-8
            featureKt.file.writeText(featureKt.original, Charsets.UTF_16)
            repo.commitAll("committed as UTF-16")
            featureKt.revert()

            // when
            val changed = changedLinesSince("HEAD")

            // then there is nothing to compare against, so the whole file counts
            assertEquals(mapOf(featureKt.path to (1..20).toSet()), changed)
        }

        @Test
        fun `an unreadable source fails loudly, naming the file`() {
            // given a source file the build cannot read
            val locked = sourceFile("app/src/main/java/demo/Locked.kt", lines = 3)
            assumeTrue("needs a filesystem with permissions", locked.file.setReadable(false) && !locked.file.canRead())

            // when
            val error = assertFailsWith<IOException> { changedLinesSince() }

            // then it says which file, rather than quietly reporting nothing for it
            assertContains(error.message!!, "Locked.kt")
        }

        @Test
        fun `a source over 50 MB is absent rather than an error`() {
            // given a file past the size ceiling
            val line = "// " + "x".repeat(1020) + "\n" // 1 KiB
            File(repo.dir, "app/src/main/java/demo/Huge.kt").bufferedWriter().use { out ->
                repeat(50 * 1024 + 1) { out.write(line) }
            }

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(emptyMap(), changed)
        }
    }

    /** The developer's git configuration must not change what a build reports, nor break it. */
    class GitConfigIndependence : Fixture() {

        @Test
        fun `the diff algorithm is histogram whatever git config says`() {
            // given content where the two algorithms disagree: Myers would say 1, 2 and 4
            val ambiguous = "app/src/main/java/demo/Ambiguous.kt"
            repo.write(ambiguous, listOf("a", "a", "c", "c", "a", "a").joinToString("") { "$it\n" })
            repo.commitAll("before")
            repo.config("diff", "algorithm", "myers")
            repo.write(ambiguous, listOf("b", "c", "c", "b", "c", "a").joinToString("") { "$it\n" })

            // when
            val changed = changedLinesSince("HEAD")

            // then the answer is the same on every machine
            assertEquals(mapOf(ambiguous to setOf(1, 4, 5)), changed)
        }

        @Test
        fun `diff settings in git config neither break nor change the result`() {
            // given values git accepts and JGit does not, and a rename limit of one
            repo.config("diff", "algorithm", "patience") // JGit's own DiffFormatter throws on this
            repo.config("diff", "renames", "sometimes") // not even valid for git
            repo.config("diff", "renameLimit", "1")

            // and two renames with one edit each
            val renamed = featureKt.moveTo(RENAMED_KT).edit(7)
            val moved = legacyJava.moveTo("app/src/main/java/demo/Moved.java").edit(8)

            // when
            val changed = changedLinesSince()

            // then both are still paired with the files they came from
            assertEquals(mapOf(moved.path to setOf(8), renamed.path to setOf(7)), changed)
        }
    }

    /** Pairing a file on disk with the one it came from: the seams are JGit's, the order is git's. */
    class RenameDetection : Fixture() {

        /** A file whose first line is its package declaration, so that moving it edits exactly line 1. */
        private fun packageFile(pkg: String, name: String): String = "package $pkg.pkg\n" + source(30, prefix = name)

        @Test(timeout = 120_000)
        fun `a package move far beyond the rename limit reports one line per file, and quickly`() {
            // given every file moved to another package under the same name, with its package line edited.
            // Same size, same directory, is the worst case for a similarity matrix: JGit on its own would
            // either spend minutes here or, past its limit, give up and call every line new.
            val names = (1..RENAME_LIMIT + 200).map { "File$it" }
            for (name in names) repo.write("old/pkg/$name.kt", packageFile("old", name))
            repo.commitAll("before the move")
            for (name in names) {
                repo.move("old/pkg/$name.kt", "fresh/pkg/$name.kt")
                repo.write("fresh/pkg/$name.kt", packageFile("fresh", name))
            }

            // when
            val changed = changedLinesSince("HEAD")

            // then each file reports its package line and nothing else
            assertEquals(names.associate { "fresh/pkg/$it.kt" to setOf(1) }, changed)
        }

        @Test(timeout = 120_000)
        fun `a few hundred files renamed as well as moved are still paired up`() {
            // given files that changed both their directory and their name, so only content can pair them
            val names = (1..300).map { "File$it" }
            for (name in names) repo.write("old/pkg/$name.kt", packageFile("old", name))
            repo.commitAll("before the move")
            for (name in names) {
                repo.move("old/pkg/$name.kt", "fresh/pkg/${name}Impl.kt")
                repo.write("fresh/pkg/${name}Impl.kt", packageFile("fresh", name))
            }

            // when
            val changed = changedLinesSince("HEAD")

            // then again one line each
            assertEquals(names.associate { "fresh/pkg/${it}Impl.kt" to setOf(1) }, changed)
        }

        @Test
        fun `a rename is still paired with exactly the rename limit of candidates`() {
            // given the rename sitting among as many new files as the limit allows
            val renamed = featureKt.moveTo(RENAMED_KT).edit(7)
            repeat(RENAME_LIMIT - 1) { repo.write("bulk/Bulk$it.kt", "class Bulk$it\n") }

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(setOf(7), changed[renamed.path])
        }

        @Test
        fun `a rename is still paired among far more than the rename limit of brand-new files`() {
            // given more new files than JGit alone would tolerate: it counts each side, where git
            // and this module count the pairs actually left to compare
            val renamed = featureKt.moveTo(RENAMED_KT).edit(7)
            repeat(RENAME_LIMIT + 1) { repo.write("bulk/Bulk$it.kt", "class Bulk$it\n") }

            // when
            val changed = changedLinesSince()

            // then
            assertEquals(setOf(7), changed[renamed.path])
        }

        @Test
        fun `beyond the rename limit a rename degrades to all lines, never fewer`() {
            // given 1001 deletions and 1001 additions, counting the rename itself: 1,002,001 pairs to
            // compare, which is what tips it past the million pairs the limit allows
            repeat(RENAME_LIMIT) { repo.write("old/Old$it.kt", "class Old$it\n") }
            repo.commitAll("before")
            repeat(RENAME_LIMIT) {
                repo.delete("old/Old$it.kt")
                repo.write("fresh/New$it.kt", "class New$it\n")
            }
            val renamed = featureKt.moveTo(RENAMED_KT).edit(7)

            // when
            val changed = changedLinesSince("HEAD")

            // then the pairing is missed in the safe direction: every line, rather than none
            assertEquals((1..20).toSet(), changed[renamed.path])
        }

        @Test
        fun `identical files moved together are all renames`() {
            // given two pairs of byte-identical files, which JGit pairs by handing the second of each
            // pair to the first one's source as a copy
            val consts = listOf("m1", "m2").map { sourceFile("$it/src/Consts.kt", lines = 40) }
            val twins = listOf("A", "B").map { sourceFile("x/$it.kt", lines = 30, prefix = "twin") }
            repo.commitAll("before")

            // and each pair moved elsewhere, the first pair also given the same edit
            val moved = consts.map { it.moveTo(it.path.replace("/src/", "/core/")).edit(7) }
            twins.forEachIndexed { i, twin -> twin.moveTo("y/Twin$i.kt") }

            // when
            val changed = changedLinesSince("HEAD")

            // then every file keeps its own source: the edit shows, and the pure moves report nothing
            assertEquals(moved.associate { it.path to setOf(7) }, changed)
        }

        @Test
        fun `a moved file keeps its source when a look-alike takes its old name`() {
            // given the file moved away and a 70%-similar newcomer under its old name. Pairing on the
            // name alone has to clear 75%, where pairing on content anywhere needs 50%.
            val lookAlike = "app/src/main/java/other/Feature.kt"
            val renamed = featureKt.moveTo(RENAMED_KT)
            repo.write(lookAlike, featureKt.contentRewriting(1..6))

            // when the moved file is still byte-identical, and again once it too is edited
            val beforeTheEdit = changedLinesSince()
            renamed.edit(7)
            val afterTheEdit = changedLinesSince()

            // then the newcomer is new code both times, and the move reports only what it edited
            assertEquals(mapOf(lookAlike to (1..20).toSet()), beforeTheEdit)
            assertEquals(mapOf(renamed.path to setOf(7), lookAlike to (1..20).toSet()), afterTheEdit)
        }

        @Test
        fun `a same-named file that is not the same file is left to the similarity matrix`() {
            // given demo/Feature.kt moved to demo/Renamed.kt while an unrelated other/Feature.kt appears
            val unrelated = "app/src/main/java/other/Feature.kt"
            val renamed = featureKt.moveTo(RENAMED_KT).edit(7)
            repo.write(unrelated, source(6, prefix = "unrelated"))

            // when
            val changed = changedLinesSince()

            // then the shared name counts for nothing: the old code is found at its new path
            assertEquals(mapOf(renamed.path to setOf(7), unrelated to (1..6).toSet()), changed)
        }
    }
}
