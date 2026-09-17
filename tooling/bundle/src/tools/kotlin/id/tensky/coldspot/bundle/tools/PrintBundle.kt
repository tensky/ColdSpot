package id.tensky.coldspot.bundle.tools

import id.tensky.coldspot.bundle.ExcludeRules
import id.tensky.coldspot.bundle.RepositoryChanges
import id.tensky.coldspot.bundle.buildBundle
import id.tensky.coldspot.manifest.Manifest
import java.io.File
import kotlin.system.exitProcess

/**
 * Builds a bundle by hand and prints what went into it, for checking the module against a real repository:
 *
 *     ./gradlew :tooling:bundle:printBundle -q -Pclasses=<dir>[,<dir>] [-Pclasspath=<jar or dir>[,...]] [-Pdir=<repo>] [-Pbase=<ref>] [-Pout=<dir>]
 *
 * [args] are the repository, the base ref (empty to let the module choose), the class directories separated
 * by commas, the output directory, and the compile classpath entries separated by commas (empty for none: then
 * only the module's own annotations tell previews), in that order. The bundle is cut as an application module's
 * would be, under `<out>/coldspot/`: the shared manifest plus a module folder called `root`, with the default
 * excludes.
 */
public fun main(args: Array<String>) {
    val classDirs = args[2].split(',').filter { it.isNotBlank() }.map(::File)
    if (classDirs.isEmpty()) {
        System.err.println("pass -Pclasses=<compile output dir>[,<dir>], e.g. app/build/tmp/kotlin-classes/debug")
        exitProcess(2)
    }
    val coldspotDir = File(args[3], "coldspot")
    val classpath = args.getOrElse(4) { "" }.split(',').filter { it.isNotBlank() }.map(::File)

    val changes = RepositoryChanges.of(File(args[0]), args[1].ifEmpty { null })
    // No instrumenter here, so no JaCoCo to name: the plugin fills this in from the jacoco-core it instruments with.
    val report = buildBundle(changes, ExcludeRules.DEFAULT, "root", classDirs, classpath, coldspotDir, sharedManifest = true, Manifest.Jacoco("none", "none"), resetToken = null, coldspotVersion = "none")

    for ((file, classes) in report.classesByFile) println("$file: $classes")
    for (file in report.filesWithoutClasses) println("$file: no class matched")
    for ((file, lines) in report.blindLinesByFile) println("$file: blind $lines")
    for ((file, previews) in report.previewLinesByFile) for (it in previews) println("$file: preview ${it.lines} (${it.reason})")
    for (it in report.ambiguous) println("ambiguous: ${it.className} (${it.sourceFile}) could be any of ${it.candidates}")
    for (it in report.excluded) println("excluded: ${it.path} (${it.changedLines} lines) by ${it.rule}")
    for (it in report.conflicts) println("compiled twice: ${it.className}, read from ${it.kept}, not ${it.dropped}")
    println("-> ${File(coldspotDir, "manifest.json")}, ${report.classesByFile.values.sumOf { it.size }} class files under root/classes")
}
