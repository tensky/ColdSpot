package id.tensky.coldspot.diff

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/** A throwaway git repository, driven entirely through JGit. */
public class FixtureRepo(public val dir: File) : AutoCloseable {
    private val git: Git = Git.init().setDirectory(dir).setInitialBranch("main").call()
    private val author = PersonIdent("ColdSpot Test", "test@coldspot.invalid", Instant.EPOCH, ZoneOffset.UTC)

    public val gitDir: File get() = git.repository.directory

    init {
        // Line endings are part of what is under test, so never inherit a conversion.
        config("core", "autocrlf", "false")
    }

    public fun config(section: String, name: String, value: String, subsection: String? = null) {
        git.repository.config.apply {
            setString(section, subsection, name, value)
            save()
        }
    }

    public fun write(path: String, content: String): File =
        File(dir, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }

    public fun delete(path: String) {
        check(File(dir, path).delete()) { "could not delete $path" }
    }

    public fun move(from: String, to: String) {
        val target = File(dir, to).apply { parentFile.mkdirs() }
        check(File(dir, from).renameTo(target)) { "could not move $from to $to" }
    }

    /** `git add -A && git commit`. */
    public fun commitAll(message: String): RevCommit {
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        return commitStaged(message)
    }

    /** `git commit` of the index as it stands. JGit's `add .` drops a gitlink whose directory is empty, where git's keeps it. */
    public fun commitStaged(message: String): RevCommit =
        git.commit().setMessage(message).setAuthor(author).setCommitter(author).setSign(false).call()

    public fun stage(path: String) {
        git.add().addFilepattern(path).call()
    }

    public fun branch(name: String) {
        git.branchCreate().setName(name).call()
    }

    public fun checkout(name: String) {
        git.checkout().setName(name).call()
    }

    /** `git merge --no-ff <branch>` into the checked-out branch, committed by the fixture's author. Returns the merge commit. */
    public fun merge(branch: String): RevCommit {
        val result = git.merge().include(resolve(branch)).setFastForward(MergeCommand.FastForwardMode.NO_FF).setCommit(false).call()
        check(result.mergeStatus == MergeResult.MergeStatus.MERGED_NOT_COMMITTED) { "merging $branch: ${result.mergeStatus}" }
        return commitStaged("Merge branch '$branch'")
    }

    /** `git checkout --orphan`: the next commit becomes a second root, sharing no history with the first. */
    public fun orphanBranch(name: String) {
        git.checkout().setOrphan(true).setName(name).call()
    }

    public fun tag(name: String, annotated: Boolean = false) {
        val tag = git.tag().setName(name).setAnnotated(annotated).setSigned(false)
        if (annotated) tag.setTagger(author).setMessage(name)
        tag.call()
    }

    /** Points [ref] (e.g. `refs/remotes/origin/main`) at [target], the way a fetch would. */
    public fun updateRef(ref: String, target: ObjectId) {
        git.repository.updateRef(ref).apply {
            setNewObjectId(target)
            forceUpdate()
        }
    }

    /** Makes [ref] a symbolic ref to [target], the way `origin/HEAD` points at the remote's default branch. */
    public fun linkRef(ref: String, target: String) {
        git.repository.updateRef(ref).link(target)
    }

    public fun resolve(revision: String): ObjectId =
        checkNotNull(git.repository.resolve(revision)) { "no such revision: $revision" }

    /** The bytes of the blob [revision] names, e.g. `HEAD:asset.bin`. */
    public fun readBlob(revision: String): ByteArray = git.repository.open(resolve(revision), Constants.OBJ_BLOB).bytes

    /**
     * Records [commit] as a submodule at [path], the way a clone without `--recurse-submodules` has one: a
     * gitlink in the index and an empty directory on disk, no repository behind it.
     */
    public fun addGitlink(path: String, commit: ObjectId) {
        val editor = git.repository.lockDirCache().editor()
        editor.add(object : DirCacheEditor.PathEdit(path) {
            override fun apply(entry: DirCacheEntry) {
                entry.fileMode = FileMode.GITLINK
                entry.setObjectId(commit)
            }
        })
        check(editor.commit()) { "could not write the index" }
        File(dir, path).mkdirs()
    }

    /** Removes an object from the object database, the way a partial clone never had it. */
    public fun forget(revision: String) {
        val name = resolve(revision).name
        val loose = File(gitDir, "objects/${name.take(2)}/${name.drop(2)}")
        loose.setWritable(true)
        check(loose.delete()) { "$revision is not a loose object" }
    }

    override fun close(): Unit = git.close()
}

/**
 * A source file in the working tree, made of [lineCount] lines of `val <prefix>N = N`, so that a line's
 * number, its content, and how similar two files are can all be read off a test at a glance.
 *
 * Nothing here stages or commits; use [FixtureRepo.commitAll] for that.
 */
public class SourceFile(
    private val repo: FixtureRepo,
    public val path: String,
    private val lineCount: Int,
    private val prefix: String,
) {
    public val file: File get() = File(repo.dir, path)

    /** The pristine content: [lineCount] untouched lines. */
    public val original: String get() = contentWith()

    /**
     * The whole file, with each of [editedLines] given a new value and, when [insertingAfter] names a
     * line, one extra line just after it.
     */
    public fun contentWith(vararg editedLines: Int, insertingAfter: Int = NO_LINE): String = buildString {
        for (n in 1..lineCount) {
            append(if (n in editedLines) declaration(prefix, n, value = n * 100) else declaration(prefix, n))
            if (n == insertingAfter) append("val inserted = 0\n")
        }
    }

    /** The whole file, with [lines] holding other declarations of the same length: genuinely different code. */
    public fun contentRewriting(lines: IntRange): String =
        (1..lineCount).joinToString("") { n -> if (n in lines) rewritten(n) else declaration(prefix, n) }

    /** The whole file, with the lines named in [replacements] written out exactly as given. */
    public fun contentReplacing(vararg replacements: Pair<Int, String>): String {
        val byLine = replacements.toMap()
        return (1..lineCount).joinToString("") { n -> byLine[n]?.let { "$it\n" } ?: declaration(prefix, n) }
    }

    /** The whole file, minus [lines]. */
    public fun contentWithout(vararg lines: Int): String =
        (1..lineCount).filterNot { it in lines }.joinToString("") { declaration(prefix, it) }

    /** Writes [content] to this path. */
    public fun write(content: String = original): SourceFile = apply { repo.write(path, content) }

    /** Writes the file out with each of [lines] edited, in [eol] line endings. */
    public fun edit(vararg lines: Int, insertingAfter: Int = NO_LINE, eol: Eol = Eol.LF): SourceFile =
        write(eol.applyTo(contentWith(*lines, insertingAfter = insertingAfter)))

    /** Writes the file out unchanged, but in [eol] line endings. */
    public fun rewrite(eol: Eol): SourceFile = write(eol.applyTo(original))

    /** Writes the pristine content back, discarding whatever edits the file holds. */
    public fun revert(): SourceFile = write()

    public fun delete(): Unit = repo.delete(path)

    /** Moves the file within the working tree and returns a handle to it at [newPath]. */
    public fun moveTo(newPath: String): SourceFile {
        repo.move(path, newPath)
        return SourceFile(repo, newPath, lineCount, prefix)
    }

    /**
     * A line of altogether different code, weighing exactly what the line it replaces weighs: JGit
     * scores similarity by bytes, and these fixtures sit a few points either side of its thresholds.
     */
    private fun rewritten(n: Int): String = "var other$n = $n\n".also {
        check(it.length == declaration(prefix, n).length) { "a '$prefix' line is not the same length, so the score would shift" }
    }

    private companion object {
        const val NO_LINE = 0
    }
}

/** The line terminator a checkout uses. */
public enum class Eol(private val terminator: String) {
    LF("\n"),
    CRLF("\r\n"),
    ;

    public fun applyTo(content: String): String = content.replace("\n", terminator)
}

/** One declaration line, the shape most fixture content is made of. */
public fun declaration(prefix: String, number: Int, value: Int = number): String = "val $prefix$number = $value\n"

/** [count] declaration lines, for bulk fixtures that do not need a [SourceFile] handle each. */
public fun source(count: Int, prefix: String = "value"): String =
    (1..count).joinToString("") { declaration(prefix, it) }
