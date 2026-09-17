package id.tensky.coldspot.diff

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Calls [changedLines] once and prints how long it took. Started in a JVM of its own, because HOME
 * cannot be changed in-process and because JGit measures a filesystem only once per JVM, so anything
 * about the very first call has to be asked somewhere nothing has asked before.
 *
 * `args` are the repository directory and, optionally, the base ref.
 */
object FreshJvm {
    @JvmStatic
    fun main(args: Array<String>) {
        val startedAt = System.nanoTime()
        val changed = changedLines(File(args[0]), args.getOrElse(1) { "HEAD" })
        println("${(System.nanoTime() - startedAt) / 1_000_000} ms, ${changed.size} files")
    }
}

/** Runs [FreshJvm] against [repo] with [home] standing in for the whole of the user's home. */
internal fun firstCallInAFreshJvm(repo: File, home: File): String {
    val java = File(System.getProperty("java.home"), "bin/java")
    val jvm = ProcessBuilder(
        java.path,
        "-Duser.home=${home.path}",
        "-cp", System.getProperty("java.class.path"),
        "id.tensky.coldspot.diff.FreshJvm",
        repo.path,
    ).redirectErrorStream(true)
    jvm.environment()["HOME"] = home.path
    jvm.environment()["XDG_CONFIG_HOME"] = File(home, ".config").path

    val process = jvm.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(2, TimeUnit.MINUTES)) { "the forked JVM never finished" }
    check(process.exitValue() == 0) { "the forked JVM failed:\n$output" }
    return output.trim()
}
