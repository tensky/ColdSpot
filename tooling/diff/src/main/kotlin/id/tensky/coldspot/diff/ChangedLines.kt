package id.tensky.coldspot.diff

import org.eclipse.jgit.diff.ContentSource
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm
import org.eclipse.jgit.diff.DiffConfig
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.diff.RenameDetector
import org.eclipse.jgit.errors.AmbiguousObjectException
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.LargeObjectException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.IndexDiff
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import org.eclipse.jgit.treewalk.filter.AndTreeFilter
import org.eclipse.jgit.treewalk.filter.OrTreeFilter
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.io.IOException
import java.util.TreeMap
import java.util.TreeSet

/**
 * Lines of Kotlin/Java source that were added or modified between [baseRef] and the
 * WORKING TREE of the repository containing [repoDir].
 *
 * This is the two-dot `git diff <base>`, not `base...HEAD`: the APK is built from whatever
 * is on disk, so uncommitted edits count and untracked files count as if `git add -N`'d.
 * Git-ignored files are skipped unless the base commit or HEAD tracks them. The index is
 * never read, so its format, lock state and staging area are irrelevant.
 *
 * When [baseRef] is a branch name (local or remote-tracking) the comparison starts from its
 * merge-base with HEAD, so commits that landed on that branch after the fork point are not
 * mistaken for local changes; `@{upstream}` and `<branch>@{upstream}` count as branch names.
 * Of several merge-bases (criss-cross merges) the first is used, as by `git diff base...HEAD`.
 * Anything else (SHA, tag, `HEAD~2`, `main~1`) is used as given, as is a branch while HEAD
 * is still unborn. A tag named like a branch wins, as in git.
 *
 * A renamed file reports only the lines edited relative to its old content. A rename that
 * goes undetected (under 50% similar, or more than 1000 x 1000 deleted-times-added source
 * files left to compare once files that kept their content or their name are paired up)
 * degrades to a new file with every line reported, never to fewer lines. So does a second
 * file resembling the same deleted one: one is the rename, the rest are new code.
 *
 * Line terminators are not content: CRLF, CR and LF all end a line, exactly as javac and
 * kotlinc count them, and a line differing only in its terminator is unchanged (a final
 * newline going missing still counts, as in git). JGit cannot be relied on to know the
 * checkout's EOL conversion (.gitattributes is not consulted on this path, a system-level
 * core.autocrlf is invisible without git on PATH), and getting it wrong would report every
 * line of every file. All other whitespace edits are reported.
 *
 * Absent from the result: files with nothing added or modified (deleted, pure removals,
 * pure renames), non-regular files (symlinks), and content that cannot be numbered as lines
 * (a NUL in the first 8000 bytes, which includes UTF-16, or over 50 MB).
 *
 * Known gaps: sources inside submodules or nested repositories are never seen. In a sparse
 * checkout, files outside the cone look deleted and can be picked as the rename source of
 * an unrelated new file.
 *
 * Call this at execution time, never while Gradle is configuring: opening a repository
 * makes JGit look for the system gitconfig by running `git` if it finds one on PATH (once
 * per JVM, and harmless if absent). Nothing is written to the home directory, which costs
 * one filesystem measurement per JVM; see [keepJGitOutOfTheHomeDirectory].
 *
 * @param repoDir the work tree root or any directory below it; the whole work tree is diffed either way
 * @return work-tree-relative `/`-separated path to 1-based line numbers in the working-tree
 *   version of the file; both sorted
 * A shallow clone is diffed only when the history this needs is all there: from HEAD, and from
 * a branch base, down to the commit compared with, on every way down. Where a shallow commit
 * cuts it on the way, the merge-base could lie behind the cut, and so could commits in
 * between; where HEAD's history reaches a root some other way while the history below that
 * commit is cut, the root may be the base's own. Both are refused ([SHALLOW_CLONE]), saying
 * where the history is cut and that more of it is the fix ([FULL_HISTORY]): fetching a branch
 * again never deepens what a clone has already cut. A clone cut below that commit is diffed
 * as any other.
 *
 * @throws IllegalArgumentException if [repoDir] is not inside the work tree of a non-bare git
 *   repository, or [baseRef] does not resolve to a commit ([NoSuchRevisionException] when it
 *   names nothing at all), or it names a branch with no merge-base with HEAD (unrelated
 *   history), or a shallow clone lacks the history between them
 * @throws java.io.IOException if the repository or a changed source file cannot be read, which
 *   includes objects a partial clone has not fetched yet: JGit cannot fetch them on demand
 */
@Throws(IOException::class)
public fun changedLines(repoDir: File, baseRef: String): Map<String, Set<Int>> =
    workingTreeDiff(repoDir, baseRef).changedLines

/**
 * [changedLines] together with what was compared and the commits in between, for a [baseRef] that may be
 * left out. Then the base is the remote's default branch, `origin/HEAD`, or failing that `origin/main` or
 * `origin/master`, and an error naming the setting if the repository has none of them. A local `main` or
 * `master` is never used in their place: left behind by a stale checkout, it puts the merge-base too far
 * back and shows other people's merged work as changed. Everything said about [changedLines] applies.
 *
 * [onCi], a build on CI (DECISIONS.md: the `CI` environment variable is `true`), takes no base it was not
 * given: neither `origin/HEAD` nor a guess, which a checkout made for a change meant for another branch would
 * get wrong without anyone looking. Without [baseRef] it is refused, with the fix.
 */
@Throws(IOException::class)
public fun workingTreeDiff(repoDir: File, baseRef: String?, onCi: Boolean = false): WorkingTreeDiff = openRepository(repoDir).use { repo ->
    val workTree = workTreeContaining(repoDir, repo)
    val shallow = repo.objectDatabase.shallowCommits
    val chosen = chooseBase(repo, baseRef, shallow, onCi)
    val base = resolveBase(repo, chosen.ref, shallow)
    repo.newObjectReader().use { reader ->
        try {
            val result = TreeMap<String, Set<Int>>()
            for (entry in detectRenames(reader, workTree, scan(repo, reader, base))) {
                if (entry.changeType == ChangeType.DELETE) continue
                val lines = newSideLines(entry, reader, workTree)
                if (lines.isNotEmpty()) result[entry.newPath] = lines
            }
            WorkingTreeDiff(workTree, ResolvedBase(chosen.ref, base.commit.name, chosen.source), base.head?.name, headBranch(repo), result, commitsSince(repo, base))
        } catch (e: MissingObjectException) {
            throw explained(e, repo, base)
        }
    }
}

/**
 * Whether anything on disk differs from HEAD: a tracked file edited, deleted or given another mode, a change
 * staged, or a file added that .gitignore does not cover. Every file counts, not only sources: an APK built
 * from this working tree is not HEAD's when a layout XML changed.
 *
 * The answer is `git status`'s, from JGit's [IndexDiff]: HEAD, the index and the working tree compared
 * together. That is what makes a tracked symlink, a submodule left uninitialised and a file kept as a
 * pointer by a `filter=lfs` attribute read as unchanged, as they do to git, and an exec-bit change count
 * only when `core.fileMode` says so. The index is read, never written: nothing refreshes its stat cache, and
 * no lock file appears. Where that cache still matches a file, the file is not hashed, so a clean tree costs
 * a stat of everything rather than a hash of everything.
 */
@Throws(IOException::class)
public fun hasUncommittedChanges(repoDir: File): Boolean = openRepository(repoDir).use { repo ->
    workTreeContaining(repoDir, repo)
    // With HEAD unborn, IndexDiff compares against an empty tree, so whatever is on disk is uncommitted.
    val diff = IndexDiff(repo, Constants.HEAD, FileTreeIterator(repo))
    diff.diff()
    diff.added.isNotEmpty() || diff.changed.isNotEmpty() || diff.removed.isNotEmpty() || diff.missing.isNotEmpty() ||
        diff.modified.isNotEmpty() || diff.untracked.isNotEmpty() || diff.conflicting.isNotEmpty()
}

/** The outcome of [workingTreeDiff]: the changed lines, and the two ends they were measured between. */
public class WorkingTreeDiff(
    /** The root of the working tree; the keys of [changedLines] are relative to it. */
    public val workTree: File,
    public val base: ResolvedBase,
    /** HEAD's commit, or null while HEAD is unborn. */
    public val headCommit: String?,
    /**
     * The branch HEAD is on, as `git branch --show-current` prints it (`feature/login`, no `refs/heads/`), also
     * while that branch is unborn; null while HEAD is detached.
     */
    public val headBranch: String?,
    /** As returned by [changedLines]. */
    public val changedLines: Map<String, Set<Int>>,
    /** The commits between the two: HEAD's history back to, and excluding, [base]'s commit. */
    public val commits: Commits,
)

/** What the working tree was compared with. */
public class ResolvedBase(
    /** The ref as given, or as chosen; [source] says which. */
    public val ref: String,
    /** The commit actually compared with: a branch's merge-base with HEAD, anything else as given. */
    public val commit: String,
    public val source: BaseSource,
)

/**
 * The commits in the build: those reachable from HEAD but not from the base commit, the set `git log <base>..HEAD`
 * prints, in its order (newest commit time first, a parent never before its child). Empty while HEAD is unborn,
 * or is the base itself.
 */
public class Commits(
    /** The first [MAX_LISTED_COMMITS] at most, so that a build with a stale base does not carry its whole history. */
    public val listed: List<Commit>,
    /** How many there are in all, listed or not, for a "+N more". */
    public val total: Int,
)

/** One commit in the build. */
public class Commit(
    /** The first [SHORT_SHA_LENGTH] characters of [sha]. */
    public val shortSha: String,
    public val sha: String,
    /** The first line of the commit message. */
    public val summary: String,
)

/** How many of [Commits] are listed one by one. */
internal const val MAX_LISTED_COMMITS: Int = 50

/** The length of an abbreviated commit SHA: git's own floor for `%h`. */
public const val SHORT_SHA_LENGTH: Int = 7

/** How a base ref came about. */
public enum class BaseSource {
    /** Passed in by the caller. */
    EXPLICIT,

    /** Nothing was passed, and the remote's default branch, `origin/HEAD`, exists. */
    ORIGIN_HEAD,

    /** Nothing was passed and there is no `origin/HEAD`: `origin/main`, or failing that `origin/master`. */
    GUESSED,
}

/** The base ref names nothing in the repository: no branch, tag, commit or other object by that name. */
public class NoSuchRevisionException(message: String) : IllegalArgumentException(message)

/** How every refusal of a shallow clone begins; what follows says where the history is cut, then the fix. */
public const val SHALLOW_CLONE: String = "shallow clone detected"

/**
 * The fix for a history cut short: more of it. Fetching a branch again never deepens what a clone has already cut,
 * and the base branch is not all that can be short: in actions/checkout's default checkout, HEAD's own history is.
 */
public const val FULL_HISTORY: String = "Fetch the full history (e.g. fetch-depth: 0; with git itself, git fetch --unshallow)."

private fun openRepository(repoDir: File): Repository {
    keepJGitOutOfTheHomeDirectory()
    // Absolute, because discovery climbs parentFile, and File("app").parentFile is null.
    val builder = FileRepositoryBuilder().findGitDir(repoDir.absoluteFile)
    requireNotNull(builder.gitDir) {
        "No git repository at or above $repoDir: ColdSpot compares the working tree with the git history, and a source " +
            "archive or a copy without .git has none. Build from a git clone, with its history (in CI: fetch-depth: 0)"
    }
    return builder.build()
}

private class ChosenBase(val ref: String, val source: BaseSource)

private val REMOTE_GUESSES = listOf("origin/main", "origin/master")

private fun chooseBase(repo: Repository, baseRef: String?, shallow: Set<ObjectId>, onCi: Boolean): ChosenBase {
    if (baseRef != null) return ChosenBase(baseRef, BaseSource.EXPLICIT)
    // Asked only for a refusal, whose fix depends on it.
    fun headCut() = repo.resolve(Constants.HEAD)?.let { firstCut(repo, it, null, shallow) }
    if (onCi) {
        throw IllegalArgumentException(
            "No base was given, and on CI (CI=true) ColdSpot takes none it is not given, neither origin/HEAD nor a guess: " +
                "pass the branch the change is for as -Pcoldspot.base=origin/<target>, or set coldSpot { baseRef = \"<ref>\" }." +
                headCut()?.let { " The clone is shallow as well, HEAD's history stopping at ${short(it)}: fetch the full history too (e.g. fetch-depth: 0)." }.orEmpty(),
        )
    }
    if (repo.namesCommit("origin/HEAD")) return ChosenBase("origin/HEAD", BaseSource.ORIGIN_HEAD)
    for (guess in REMOTE_GUESSES) if (repo.namesCommit(guess)) return ChosenBase(guess, BaseSource.GUESSED)
    val headCut = headCut()
    throw IllegalArgumentException(
        if (headCut != null) {
            "$SHALLOW_CLONE: no base was given, this clone has no origin/HEAD, origin/main or origin/master to compare with, " +
                "and HEAD's history stops at ${short(headCut)}. Fetch the full history with the branch to compare with " +
                "(e.g. fetch-depth: 0), and pass that branch as -Pcoldspot.base=origin/<target>, or set coldSpot { baseRef = \"<ref>\" }."
        } else {
            "No base ref was given, and this clone has no origin/HEAD, origin/main or origin/master to compare with (a " +
                "single-branch clone has only its own branch): fetch the branch to compare with, and pass it as " +
                "-Pcoldspot.base=origin/<target>, or set one with coldSpot { baseRef = \"<ref>\" }"
        },
    )
}

private fun Repository.namesCommit(ref: String): Boolean =
    try {
        resolve("$ref^{commit}") != null
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        false
    }

/** Minimum similarity, in percent, for a deleted and an added file to count as a rename: git's default. JGit's own is 60. */
internal const val RENAME_SCORE: Int = 50

/**
 * Once the deleted-times-added source files left to compare exceed this squared, inexact rename detection
 * is skipped. git's default and git's rule; JGit's own rule gives up when either side alone exceeds it.
 */
internal const val RENAME_LIMIT: Int = 1000

/** git's bar for pairing on the file name alone: halfway between the rename score and identical. */
private const val SAME_NAME_SCORE: Int = (RENAME_SCORE + 100) / 2

/** Rounds of re-offering COPY destinations to unmatched deletions; each round settles at least one. */
private const val COPY_RETRY_ROUNDS = 20

/** Same ceiling as JGit's own diff: anything larger is treated like binary content. */
private const val MAX_FILE_BYTES = 50 shl 20

/** How far git looks for a NUL before calling content binary. */
private const val BINARY_PROBE_BYTES = 8000

private const val NUL: Byte = 0
private const val CR: Byte = '\r'.code.toByte()
private const val LF: Byte = '\n'.code.toByte()

private val SOURCE_FILES: TreeFilter =
    OrTreeFilter.create(PathSuffixFilter.create(".kt"), PathSuffixFilter.create(".java"))

// Pinned, like the rename settings: diff.algorithm, diff.renameLimit and friends in the developer's
// gitconfig must not change which lines a build reports. (JGit also rejects values git accepts,
// e.g. diff.algorithm=patience, so reading them would turn a dotfile into a build failure.)
private val DIFF: DiffAlgorithm = DiffAlgorithm.getAlgorithm(SupportedAlgorithm.HISTOGRAM)

/**
 * JGit measures a filesystem's timestamp resolution the first time it touches it, and persists the
 * answer to `$XDG_CONFIG_HOME/jgit/config`: `FS.saveToConfig` asks `SystemReader.getJGitConfig()` for
 * that file and calls `save()` on it. A build has no business writing to anyone's home directory, so
 * this reader hands out the same file with `save()` doing nothing. It is still read, so a config
 * another tool left there is honoured.
 *
 * Nothing else is stubbed. The user's `~/.gitconfig` and the system config are the default reader's,
 * deliberately: `core.excludesFile` is how a global gitignore reaches the untracked files this module
 * filters, and faking either would quietly change which lines a build reports.
 *
 * The price is that the measurement is never cached, so every JVM repeats it.
 */
private class ReadOnlyJGitConfig(delegate: SystemReader) : SystemReader.Delegate(delegate) {
    override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig {
        val onDisk = super.openJGitConfig(parent, fs)
        return object : FileBasedConfig(parent, onDisk.file, fs) {
            override fun save() = Unit
        }
    }
}

private val systemReader = Any()

/**
 * Installs [ReadOnlyJGitConfig] before the first JGit call. Testing the instance rather than a flag
 * keeps this idempotent without ever wrapping a wrapper, and puts it back if something else replaced
 * the reader in the meantime. Note that the reader is JVM-wide: every other JGit user in this
 * classloader gets it too, which is the point, since they would write to the same file.
 */
private fun keepJGitOutOfTheHomeDirectory(): Unit = synchronized(systemReader) {
    val current = SystemReader.getInstance()
    if (current !is ReadOnlyJGitConfig) SystemReader.setInstance(ReadOnlyJGitConfig(current))
}

private fun workTreeContaining(repoDir: File, repo: Repository): File {
    require(!repo.isBare) { "${repo.directory} is a bare repository, there is no working tree to diff" }
    // JGit takes the work tree from core.worktree or a linked worktree's back-pointer. If the checkout
    // was moved since, that is some other directory, and diffing it would quietly report nothing.
    require(repoDir.canonicalFile.startsWith(repo.workTree.canonicalFile)) {
        "$repoDir is not inside ${repo.workTree}, the work tree recorded in ${repo.directory} (moved checkout? try `git worktree repair`)"
    }
    return repo.workTree
}

/** The local branch HEAD points at, by name; null when HEAD holds a commit itself (detached) or points elsewhere. */
private fun headBranch(repo: Repository): String? {
    val head = repo.exactRef(Constants.HEAD) ?: return null
    if (!head.isSymbolic) return null
    return head.target.name.takeIf { it.startsWith(Constants.R_HEADS) }?.removePrefix(Constants.R_HEADS)
}

/** The commit to diff from, and HEAD while there is one. */
private class Base(val commit: RevCommit, val head: RevCommit?) {
    val tree: ObjectId get() = commit.tree.id

    /** HEAD's tree, for tracked files the base tree lacks; null when it would be the base tree anyway. */
    val headTree: ObjectId? get() = head?.takeUnless { it == commit }?.tree?.id
}

private fun resolveBase(repo: Repository, baseRef: String, shallow: Set<ObjectId>): Base = RevWalk(repo).use { walk ->
    val ref = expandUpstream(repo, baseRef)
    val base = walk.parseCommit(resolveCommit(repo, ref, shallow))
    val head = repo.resolve(Constants.HEAD)?.let(walk::parseCommit)
    if (head == null || head == base) return@use Base(base, head)
    val branch = namesBranch(repo, ref)
    val from = if (!branch) {
        base
    } else {
        walk.revFilter = RevFilter.MERGE_BASE
        walk.markStart(base)
        walk.markStart(head)
        // A criss-cross history has several. Taking the first is what git does; diffing against each and
        // merging the results would instead report as ours whatever the other side changed between them.
        walk.next() ?: throw IllegalArgumentException(nowhere(repo, baseRef, head, base, shallow))
    }
    if (shallow.isNotEmpty()) provable(repo, baseRef, head, base, from, branch, shallow)
    Base(from, head)
}

/**
 * No merge-base in a cut history: where each side stops. With neither cut, the two histories are unrelated, as they
 * would be in a full clone.
 */
private fun nowhere(repo: Repository, baseRef: String, head: RevCommit, base: RevCommit, shallow: Set<ObjectId>): String {
    val headCut = firstCut(repo, head, null, shallow)
    val baseCut = firstCut(repo, base, null, shallow)
    val where = listOfNotNull(headCut?.let { "HEAD's history stops at ${short(it)}" }, baseCut?.let { "the history of '$baseRef' at ${short(it)}" })
    if (where.isEmpty()) return "'$baseRef' shares no history with HEAD, so there is no merge-base to diff from"
    return "$SHALLOW_CLONE: HEAD and '$baseRef' meet nowhere in the history this clone has: ${where.joinToString(", and ")}. $FULL_HISTORY"
}

/**
 * In a cut history, [from] is only known to be the commit to compare with, and HEAD's history down to it only known to
 * be the build's commits, when nothing on the way is missing. So, on HEAD's side, the history must reach [from] on
 * every way down: a cut on the way could hide a better merge-base or commits of the build, and a root reached another
 * way, while the history below [from] is cut, may be the base's own and would be taken for the build's. On a branch's
 * side, nothing down to the merge-base may be cut. Throws, saying where, otherwise.
 */
private fun provable(repo: Repository, baseRef: String, head: RevCommit, base: RevCommit, from: RevCommit, branch: Boolean, shallow: Set<ObjectId>) {
    val headSide = bottoms(repo, head, from)
    headSide.firstOrNull { it in shallow }?.let { cut ->
        throw IllegalArgumentException(
            if (branch) {
                "$SHALLOW_CLONE: HEAD's history stops at ${short(cut)} before it reaches its merge-base with '$baseRef', so " +
                    "neither that merge-base nor the commits in between can be known. $FULL_HISTORY"
            } else {
                "$SHALLOW_CLONE: HEAD's history stops at ${short(cut)} before it reaches '$baseRef', so the commits in " +
                    "between cannot all be known. $FULL_HISTORY"
            },
        )
    }
    val root = headSide.firstOrNull()
    val below = root?.let { firstCut(repo, from, null, shallow) }
    if (root != null && below != null) {
        val what = if (branch) "its merge-base with '$baseRef'" else "'$baseRef'"
        throw IllegalArgumentException(
            "$SHALLOW_CLONE: HEAD's history also reaches ${short(root)}, a root commit, without passing $what, and the " +
                "history below that stops at ${short(below)}, so whether ${short(root)} is the base's own cannot be known. $FULL_HISTORY",
        )
    }
    if (!branch) return
    bottoms(repo, base, from).firstOrNull { it in shallow }?.let { cut ->
        throw IllegalArgumentException(
            "$SHALLOW_CLONE: the history of '$baseRef' stops at ${short(cut)} before it reaches its merge-base with HEAD, " +
                "so the merge-base found may not be the real one. $FULL_HISTORY",
        )
    }
}

/** The commits [tip]'s history reaches before [stop] (all of it without one) that have no parents in this clone: its cuts, and its roots. */
private fun bottoms(repo: Repository, tip: RevCommit, stop: RevCommit?): List<ObjectId> = RevWalk(repo).use { walk ->
    walk.markStart(walk.parseCommit(tip))
    stop?.let { walk.markUninteresting(walk.parseCommit(it)) }
    walk.filter { it.parentCount == 0 }.map { it.toObjectId() }
}

/** The first commit [tip]'s history is cut at before [stop] (anywhere without one), if any: a walk that stops there. */
private fun firstCut(repo: Repository, tip: ObjectId, stop: RevCommit?, shallow: Set<ObjectId>): ObjectId? {
    if (shallow.isEmpty()) return null
    return RevWalk(repo).use { walk ->
        walk.markStart(walk.parseCommit(tip))
        stop?.let { walk.markUninteresting(walk.parseCommit(it)) }
        walk.firstOrNull { it in shallow }?.toObjectId()
    }
}

private fun short(id: ObjectId): String = id.name.take(SHORT_SHA_LENGTH)

/** `git log <base>..HEAD`: HEAD's history with the base commit's taken out, in RevWalk's default order, which is git's. */
private fun commitsSince(repo: Repository, base: Base): Commits {
    // Nothing is committed on an unborn branch, so nothing is in the build.
    val head = base.head ?: return Commits(emptyList(), total = 0)
    return RevWalk(repo).use { walk ->
        walk.markStart(walk.parseCommit(head))
        walk.markUninteresting(walk.parseCommit(base.commit))
        val listed = ArrayList<Commit>(MAX_LISTED_COMMITS)
        var total = 0
        for (commit in walk) {
            if (total++ < MAX_LISTED_COMMITS) {
                listed += Commit(commit.name.take(SHORT_SHA_LENGTH), commit.name, commit.fullMessage.lineSequence().first())
            }
        }
        Commits(listed, total)
    }
}

private val UPSTREAM_SUFFIX = Regex("""(.*)@\{(?:u|upstream)\}""", RegexOption.IGNORE_CASE)

/**
 * `@{upstream}` expanded from the branch's tracking config. Repository.resolve() has its own idea of it:
 * the remote is always called origin, and failing that the answer is the branch itself (so the diff is
 * silently empty) or a NullPointerException.
 */
private fun expandUpstream(repo: Repository, baseRef: String): String {
    val match = UPSTREAM_SUFFIX.matchEntire(baseRef)
    if (match == null) {
        // '@{upstream}~2' and the like would still reach resolve().
        require(!baseRef.contains("@{u", ignoreCase = true) && !baseRef.contains("@{push", ignoreCase = true)) {
            "'$baseRef': @{upstream} is only understood at the very end, and @{push} not at all"
        }
        return baseRef
    }
    val of = match.groupValues[1]
    val branch = if (of.isEmpty()) repo.fullBranch else findRef(repo, of)?.leaf?.name
    require(branch != null && branch.startsWith(Constants.R_HEADS)) {
        "'$baseRef': ${of.ifEmpty { "HEAD" }} is not a local branch, so it has no upstream"
    }
    val shortName = Repository.shortenRefName(branch)
    return BranchConfig(repo.config, shortName).trackingBranch
        ?: throw IllegalArgumentException("'$baseRef': branch $shortName has no upstream configured")
}

private fun resolveCommit(repo: Repository, baseRef: String, shallow: Set<ObjectId>): ObjectId {
    val id = try {
        repo.resolve("$baseRef^{commit}")
    } catch (e: AmbiguousObjectException) {
        throw IllegalArgumentException("'$baseRef' is an ambiguous abbreviation", e)
    } catch (e: IncorrectObjectTypeException) {
        throw IllegalArgumentException("'$baseRef' is not a commit", e)
    } catch (e: MissingObjectException) {
        throw IllegalArgumentException("'$baseRef' names an object this repository does not have", e)
    } catch (e: RuntimeException) {
        // RevisionSyntaxException mostly, but resolve() is not above a bare NullPointerException either.
        throw IllegalArgumentException("'$baseRef' is not a revision JGit can resolve", e)
    }
    if (id != null) return id
    val branch = REMOTE_BRANCH.matchEntire(baseRef)?.groupValues?.get(1)
    // Missing, and HEAD's own history cut: the branch alone would not be enough, more history is.
    val headCut = repo.resolve(Constants.HEAD)?.let { firstCut(repo, it, null, shallow) }
    throw NoSuchRevisionException(
        if (headCut != null) {
            "$SHALLOW_CLONE: '$baseRef' is not in this clone, and HEAD's history stops at ${short(headCut)}. Fetch the full " +
                "history with " + (branch?.let { "that branch (e.g. fetch-depth: 0; with git itself, git fetch --unshallow origin +refs/heads/$it:refs/remotes/origin/$it)." } ?: "the commit it names (e.g. fetch-depth: 0).")
        } else {
            "'$baseRef' does not resolve to a commit in this clone: fetch it first" +
                branch?.let { " (git fetch origin +refs/heads/$it:refs/remotes/origin/$it)" }.orEmpty() +
                ", or pass the base to compare with as -Pcoldspot.base=origin/<target>"
        },
    )
}

/** `origin/<branch>`: a remote-tracking branch a single-branch or shallow clone may not have fetched. */
private val REMOTE_BRANCH = Regex("""origin/(?!HEAD$)([^\s:~^]+)""")

private fun namesBranch(repo: Repository, baseRef: String): Boolean {
    // findRef() searches in the same order as resolve(), so a tag shadowing a branch is a tag to both.
    val name = findRef(repo, baseRef)?.name ?: return false
    return name.startsWith(Constants.R_HEADS) || name.startsWith(Constants.R_REMOTES)
}

private fun findRef(repo: Repository, name: String): Ref? =
    // The guard resolve() applies itself: findRef() turns its argument into a path under .git unchecked,
    // so '../../x' would read outside the repository.
    if (Repository.isValidRefName("x/$name")) repo.findRef(name) else null

/** JGit cannot fetch what a partial clone left out; say so, and how to get it, instead of just "Missing blob". */
private fun explained(e: MissingObjectException, repo: Repository, base: Base): IOException {
    val partialClone = repo.config.getSubsections("remote").any { repo.config.getBoolean("remote", it, "promisor", false) }
    if (!partialClone) return e
    return IOException(
        "${e.message}: this is a partial clone, and only git itself can fetch objects on demand; ColdSpot never " +
            "fetches. Clone without --filter (in CI: no `filter` on the checkout), or run " +
            "`git diff ${base.commit.name} -- \"*.kt\" \"*.java\"` once before the build: it fetches the ones needed here.",
        e,
    )
}

private fun scan(repo: Repository, reader: ObjectReader, from: Base): List<DiffEntry> =
    TreeWalk(repo, reader).use { walk ->
        val base = walk.addTree(from.tree)
        // Left to itself the iterator presents an ignored directory as empty, tracked files and all.
        // TrackedOrNotIgnored still keeps the walk out of ignored directories the base knows nothing of.
        val work = walk.addTree(FileTreeIterator(repo).apply { setWalkIgnoredDirectories(true) })
        walk.isRecursive = true
        // Order matters: ANY_DIFF has to hash working-tree content, so it must only ever see source files.
        // The iterator is deliberately not tied to a DirCacheIterator: that is what would make JGit run
        // filter.<name>.clean commands while hashing.
        val notIgnored = TrackedOrNotIgnored(base, work, reader, from.headTree)
        walk.filter = AndTreeFilter.create(arrayOf(SOURCE_FILES, notIgnored, TreeFilter.ANY_DIFF))
        // Symlinks and gitlinks carry no source lines, and must not become rename candidates read through the link.
        DiffEntry.scan(walk).filter { it.oldMode.isFileOrMissing() && it.newMode.isFileOrMissing() }
    }

private fun FileMode.isFileOrMissing(): Boolean =
    this == FileMode.MISSING || (bits and FileMode.TYPE_MASK) == FileMode.TYPE_FILE

/**
 * NotIgnoredFilter, except that a tracked path is never treated as ignored: .gitignore only applies
 * to untracked files. Without the index, "tracked" means present in the base tree or in HEAD, so a
 * `git add -f`'d file only counts once it is committed.
 */
private class TrackedOrNotIgnored(
    private val base: Int,
    private val work: Int,
    private val reader: ObjectReader,
    private val headTree: ObjectId?,
) : TreeFilter() {
    override fun include(walker: TreeWalk): Boolean {
        if (walker.getRawMode(base) != FileMode.MISSING.bits) return true
        val entry = walker.getTree(work, WorkingTreeIterator::class.java)
        if (entry == null || !entry.isEntryIgnored) return true
        // Only ignored paths the base lacks get this far: a handful of build directories, not every file.
        return headTree != null && TreeWalk.forPath(reader, walker.pathString, headTree).use { it != null }
    }

    override fun shouldBeRecursive(): Boolean = false

    override fun clone(): TreeFilter = this
}

private fun detectRenames(reader: ObjectReader, workTree: File, entries: List<DiffEntry>): List<DiffEntry> =
    RenameMatcher(reader, workTree).match(entries)

/**
 * RenameDetector, driven in git's order: files that merely moved (same blob) first, then same-named files
 * on a higher bar, then the full similarity matrix. Left to run in one go, the matrix alone re-hashes every
 * added file once per deleted one (a thousand moved files: seconds), gives up on all of them past the limit,
 * and lets a same-named look-alike outbid a byte-identical twin elsewhere.
 */
private class RenameMatcher(reader: ObjectReader, workTree: File) {
    private val detector = RenameDetector(reader, Config().get(DiffConfig.KEY))

    // Not compute(reader, pm), and not DiffFormatter.setDetectRenames: both look for the NEW side in the
    // object database, where working-tree content does not exist, and quietly pair nothing.
    private val source = ContentSource.Pair(ContentSource.create(reader), WorkTreeSource(workTree))

    fun match(entries: List<DiffEntry>): List<DiffEntry> {
        val moved = detect(entries, matrix = false)
        val named = sameNamePairs(moved)
        return untangleCopies(detect(named), entries)
    }

    /** One detection pass. Pairs already made pass through untouched. */
    private fun detect(candidates: List<DiffEntry>, score: Int = RENAME_SCORE, matrix: Boolean = true): List<DiffEntry> {
        val adds = candidates.count { it.changeType == ChangeType.ADD }.toLong()
        val deletes = candidates.count { it.changeType == ChangeType.DELETE }.toLong()
        detector.reset()
        detector.renameScore = score
        detector.renameLimit = when {
            !matrix -> -1 // exact matches only
            adds * deletes <= RENAME_LIMIT.toLong() * RENAME_LIMIT -> 0 // no limit
            else -> RENAME_LIMIT // one side exceeds it, so JGit gives up, as git would
        }
        detector.addAll(candidates)
        return detector.compute(source, NullProgressMonitor.INSTANCE)
    }

    private fun sameNamePairs(entries: List<DiffEntry>): List<DiffEntry> {
        val added = entries.filter { it.changeType == ChangeType.ADD }.groupBy { it.newPath.substringAfterLast('/') }
        val deleted = entries.filter { it.changeType == ChangeType.DELETE }.groupBy { it.oldPath.substringAfterLast('/') }
        val renames = ArrayList<DiffEntry>()
        val settled = HashSet<DiffEntry>() // DiffEntry has identity equality, which is what is wanted
        for ((name, adds) in added) {
            val pair = listOf(deleted[name]?.singleOrNull() ?: continue, adds.singleOrNull() ?: continue)
            val rename = detect(pair, SAME_NAME_SCORE).singleOrNull() ?: continue
            renames += rename
            settled += pair
        }
        return renames + entries.filterNot { it in settled }
    }

    /**
     * JGit lets a source that already found its rename claim further look-alikes as COPY, and never offers
     * them to the sources still unmatched; git skips a used source instead. Offer the copied files to the
     * leftover deletions again. Twins that were all renamed and edited settle one per round, hence the cap.
     */
    private fun untangleCopies(paired: List<DiffEntry>, originals: List<DiffEntry>): List<DiffEntry> {
        val addedByPath = originals.filter { it.changeType == ChangeType.ADD }.associateBy { it.newPath }
        var current = paired
        repeat(COPY_RETRY_ROUNDS) {
            val copies = current.filter { it.changeType == ChangeType.COPY }
            val unmatched = current.filter { it.changeType == ChangeType.DELETE }
            if (copies.isEmpty() || unmatched.isEmpty()) return current
            val retry = detect(unmatched + copies.map { addedByPath.getValue(it.newPath) })
            if (retry.none { it.changeType == ChangeType.RENAME }) return current
            // By identity: the detector retypes the deletions it pairs, so they no longer read as DELETE.
            val replaced = (copies + unmatched).toHashSet()
            current = current.filterNot { it in replaced } + retry
        }
        return current
    }
}

/**
 * The working tree as RenameDetector's NEW side. It asks for every added file once per deleted
 * candidate, and JGit's own ContentSource.create(WorkingTreeIterator) answers each time by re-walking
 * the tree from the root and re-reading the file: minutes for a few hundred moved files. Raw bytes
 * will do, since the similarity score already ignores the CR of a CRLF.
 */
private class WorkTreeSource(private val workTree: File) : ContentSource() {
    private val contents = HashMap<String, ByteArray>()

    override fun size(path: String, id: ObjectId): Long = File(workTree, path).length()

    override fun open(path: String, id: ObjectId): ObjectLoader =
        ObjectLoader.SmallObject(Constants.OBJ_BLOB, contents.getOrPut(path) { File(workTree, path).readBytes() })
}

private fun newSideLines(entry: DiffEntry, reader: ObjectReader, workTree: File): Set<Int> {
    // RenameDetector pairs one deleted file with every added file that resembles it: the best match is the
    // RENAME, the others come back as COPY. To `git diff -M` those are plain additions, and a second class
    // holding the same code is indeed new code to exercise.
    val added = entry.changeType == ChangeType.ADD || entry.changeType == ChangeType.COPY
    // Same blob on both sides: an exec-bit flip or a pure rename. (Not for additions, where an unreadable
    // file scans as the zero id on both sides and must fail loudly below instead.)
    if (!added && entry.oldId == entry.newId) return emptySet()

    val new = text(File(workTree, entry.newPath)) ?: return emptySet()
    // An old side that cannot be diffed makes every new line new, rather than none.
    val old = (if (added) null else text(reader, entry.oldId.toObjectId())) ?: RawText.EMPTY_TEXT

    val lines = TreeSet<Int>()
    for (edit in DIFF.diff(RawTextComparator.DEFAULT, old, new)) {
        // Edit regions are 0-based and end-exclusive; a DELETE has an empty B region and adds nothing.
        for (line in edit.beginB + 1..edit.endB) lines += line
    }
    return lines
}

private fun text(file: File): RawText? =
    if (file.length() > MAX_FILE_BYTES) null else text(file.readBytes())

private fun text(reader: ObjectReader, blob: ObjectId): RawText? =
    try {
        text(reader.open(blob, Constants.OBJ_BLOB).getCachedBytes(MAX_FILE_BYTES))
    } catch (e: LargeObjectException) {
        null
    }

/** Null when [bytes] cannot be numbered as lines of source. */
private fun text(bytes: ByteArray): RawText? {
    for (i in 0 until minOf(bytes.size, BINARY_PROBE_BYTES)) {
        if (bytes[i] == NUL) return null
    }
    return RawText(withLfLineEndings(bytes))
}

/** CRLF and lone CR become LF, so that RawText's LF-delimited lines are the compiler's lines. */
private fun withLfLineEndings(bytes: ByteArray): ByteArray {
    if (CR !in bytes) return bytes
    val out = ByteArray(bytes.size)
    var length = 0
    var i = 0
    while (i < bytes.size) {
        val byte = bytes[i++]
        if (byte == CR) {
            if (i < bytes.size && bytes[i] == LF) i++
            out[length++] = LF
        } else {
            out[length++] = byte
        }
    }
    return out.copyOf(length)
}
