package id.tensky.coldspot.diff.tools

import id.tensky.coldspot.diff.SHORT_SHA_LENGTH
import id.tensky.coldspot.diff.workingTreeDiff
import java.io.File

/**
 * Prints what the diff module reports for a repository, one line per file, so the answer can be checked
 * against `git diff` by hand:
 *
 *     ./gradlew :tooling:diff:printDiff -q [-Pbase=<ref>] [-Pdir=<path>]
 *
 * [args] are the base ref, empty to let the module choose it the way the plugin will, and the directory to
 * ask about. The base actually used, and HEAD with the number of commits since the base, go to stderr, so
 * that stdout stays the raw result.
 */
public fun main(args: Array<String>) {
    val baseRef = args.getOrElse(0) { "" }.ifEmpty { null }
    val repoDir = File(args.getOrElse(1) { "." })

    val diff = workingTreeDiff(repoDir, baseRef)

    System.err.println("base: ${diff.base.ref} (${diff.base.source}) = ${diff.base.commit}")
    System.err.println("head: ${diff.headCommit?.take(SHORT_SHA_LENGTH) ?: "unborn"}, ${diff.commits.total} commits since base")
    for ((path, lines) in diff.changedLines) {
        println("$path: $lines")
    }
}
