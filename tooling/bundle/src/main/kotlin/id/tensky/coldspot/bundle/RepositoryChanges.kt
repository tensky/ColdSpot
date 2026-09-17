package id.tensky.coldspot.bundle

import id.tensky.coldspot.diff.hasUncommittedChanges
import id.tensky.coldspot.diff.workingTreeDiff
import id.tensky.coldspot.manifest.BaseSource
import id.tensky.coldspot.manifest.Manifest
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Everything a build learns from git, once: the diff, what it was measured between, and the source files of the
 * work tree. Every module's bundle is cut from the same instance (the plugin keeps one per build in a shared
 * service), so the repository is opened, diffed and scanned once, however many modules there are. Immutable,
 * and safe to read from several tasks at a time.
 *
 * [changedLines] holds every changed file, noise included: which of them are excluded is a per-module question
 * answered by [ExcludeRules] when the bundle is cut, see [buildBundle].
 */
public class RepositoryChanges(
    /** The root of the working tree; every path here is relative to it, `/`-separated. */
    public val workTree: File,
    public val base: Manifest.Base,
    public val head: Manifest.Head,
    public val commits: Manifest.Commits,
    /** As the diff module reports them: changed file to its changed lines, both ascending. */
    public val changedLines: Map<String, Set<Int>>,
) {
    /**
     * Every Kotlin and Java file under [workTree], `.git` and symlinks left alone. Consulted by class selection
     * only when a class's package points away from every changed file, hence walked on first use, and once.
     */
    public val sourceFiles: Set<String> by lazy {
        workTree.walkTopDown()
            .onEnter { it.name != ".git" && !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .mapTo(HashSet()) { it.relativeTo(workTree).invariantSeparatorsPath }
    }

    public companion object {
        /**
         * Diffs the working tree of the repository containing [repoDir] against [baseRef], or against the base the
         * diff module chooses when that is null (`origin/HEAD`, then `origin/main`, then `origin/master`; never a
         * local branch, and on CI, [onCi], none: the base must be given), and asks it whether anything on disk
         * differs from HEAD.
         *
         * @throws IllegalArgumentException for whatever the diff module refuses, including no base at all
         * @throws IOException when the repository or a changed source file cannot be read
         */
        @Throws(IOException::class)
        public fun of(repoDir: File, baseRef: String?, onCi: Boolean = false): RepositoryChanges {
            val diff = workingTreeDiff(repoDir, baseRef, onCi)
            return RepositoryChanges(
                workTree = diff.workTree,
                base = Manifest.Base(diff.base.ref, diff.base.commit, BaseSource.valueOf(diff.base.source.name)), // the same names, in a module without git
                head = Manifest.Head(sha = diff.headCommit, branch = diff.headBranch, dirty = hasUncommittedChanges(repoDir)),
                commits = Manifest.Commits(diff.commits.listed.map { Manifest.Commit(it.shortSha, it.sha, it.summary) }, diff.commits.total),
                changedLines = diff.changedLines,
            )
        }
    }
}
