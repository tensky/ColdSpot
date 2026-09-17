package id.tensky.coldspot.bundle

import id.tensky.coldspot.manifest.Manifest
import id.tensky.coldspot.manifest.ManifestJson
import id.tensky.coldspot.manifest.ModuleManifest
import java.io.File
import java.io.IOException

/**
 * Cuts one module's bundle from [changes]: under `[coldspotDir]/[module]/`, the original bytes of every compiled
 * class of the module that the change touches, as `classes/<vm/Name>.class`, and `manifest.json`, described by
 * [ModuleManifest]. With [sharedManifest], also `[coldspotDir]/manifest.json`, the [Manifest] of the whole
 * change, which the application module alone writes, naming [coldspotVersion], the ColdSpot that builds, and
 * [jacoco], the JaCoCo whose instrumenter the build uses, and carrying [resetToken] when this is a fresh-session
 * build (see [Manifest.resetToken]). Everything written here is replaced on every call; nothing else under
 * [coldspotDir] is touched, so modules never disturb each other's folders.
 *
 * Changed files that [excludes] match are noise: they get no classes, no blind lines, and no entry in the module
 * manifest, but they are never dropped in silence. The shared manifest lists each with the rule that matched,
 * and so does the [BundleReport].
 *
 * [classDirs] are compile outputs, Kotlin's and Java's, walked for `.class` files in that order. Their
 * `SourceFile`, `LineNumberTable` and SMAP attributes decide which ship and which lines are blind, and their
 * annotations which changed lines are Compose previews ([PreviewFinder]), with [classpath], the module's compile
 * classpath, consulted for annotation classes and supertypes the module does not hold. A class two directories
 * hold compiled differently ([ConflictingClass]) is read from the first, as the transform ships it, and reported;
 * only when it is a class the change touches, which the bundle would ship, is that an error: there is then no
 * telling which compilation the app is meant to run. The [BundleReport] says what did not ship, and why.
 * Nothing is logged.
 *
 * @throws IllegalStateException when a class the change touches is compiled differently in two of [classDirs],
 *   or a class carries an SMAP that cannot be read
 * @throws IOException when a changed source file or a class file cannot be read
 */
@Throws(IOException::class)
public fun buildBundle(
    changes: RepositoryChanges,
    excludes: ExcludeRules,
    module: String,
    classDirs: List<File>,
    classpath: List<File>,
    coldspotDir: File,
    sharedManifest: Boolean,
    jacoco: Manifest.Jacoco,
    resetToken: String?,
    coldspotVersion: String,
): BundleReport {
    val excluded = ArrayList<Manifest.ExcludedFile>()
    val measured = LinkedHashMap<String, Set<Int>>()
    for ((path, lines) in changes.changedLines) {
        val rule = excludes.matching(path)
        if (rule == null) measured[path] = lines else excluded += Manifest.ExcludedFile(path, lines.size, rule)
    }

    val (classes, conflicts) = readClasses(classDirs)
    val selection = selectClasses(measured, classes, lazy { changes.sourceFiles })
    val shipped = selection.classesByFile.values.flatten().mapTo(HashSet()) { it.name }
    conflicts.firstOrNull { it.className in shipped }?.let { conflict ->
        throw IllegalStateException(
            "${conflict.className} is compiled differently in ${conflict.kept} and ${conflict.dropped}, and the change touches it: " +
                "ColdSpot cannot tell which of the two the app is meant to run. Have the build compile it once (a clean build " +
                "usually does), or leave its file out with coldSpot { exclude(...) }.",
        )
    }
    val previewsByFile = ClassLookup(classpath).use { lookup ->
        val finder = PreviewFinder(classes.associateBy { it.name }, lookup)
        selection.classesOfFile.mapValues { (path, placed) -> previewLines(measured.getValue(path), placed.flatMap(finder::previewRangesOf)) }
            .filterValues { it.isNotEmpty() }
    }
    val moduleFiles = measured.keys.mapNotNull { path ->
        val shipped = selection.classesByFile[path]?.map { it.name } ?: emptyList()
        val blind = selection.blindLinesByFile[path]?.toList() ?: emptyList()
        val previews = previewsByFile[path] ?: emptyList()
        if (shipped.isEmpty() && blind.isEmpty() && previews.isEmpty()) null else ModuleManifest.ModuleFile(path, shipped, blind, previews)
    }
    val moduleDir = File(coldspotDir, module)
    writeModule(moduleDir, ModuleManifest(Manifest.SCHEMA_VERSION, module, moduleFiles), selection.classesByFile.values.flatten())

    if (sharedManifest) {
        val files = measured.map { (path, lines) -> Manifest.ChangedFile(path, lines.toList(), File(changes.workTree, path).readText()) }
        coldspotDir.mkdirs()
        File(coldspotDir, MANIFEST).writeText(ManifestJson.write(Manifest(Manifest.SCHEMA_VERSION, coldspotVersion, changes.base, changes.head, changes.commits, jacoco, resetToken, files, excluded)))
    }

    return BundleReport(
        classesByFile = selection.classesByFile.mapValues { (_, classes) -> classes.map { it.name } },
        ambiguous = selection.ambiguous,
        filesWithoutClasses = measured.keys.filter { it !in selection.classesByFile },
        blindLinesByFile = selection.blindLinesByFile.mapValues { (_, lines) -> lines.toList() },
        previewLinesByFile = previewsByFile,
        excluded = excluded,
        conflicts = conflicts,
    )
}

/** The changed lines that fall in a preview's range, grouped by preview in line order; a line in two ranges goes to the first. */
private fun previewLines(changedLines: Set<Int>, ranges: List<PreviewRange>): List<ModuleManifest.PreviewLines> {
    val byRange = LinkedHashMap<PreviewRange, MutableList<Int>>()
    for (line in changedLines.sorted()) {
        val range = ranges.firstOrNull { line in it.lines } ?: continue
        byRange.getOrPut(range) { ArrayList() } += line
    }
    return byRange.map { (range, lines) -> ModuleManifest.PreviewLines(lines, range.reason) }
}

/** What [buildBundle] made of the classes it was given, for the build to relay however it sees fit. */
public class BundleReport(
    /** Changed file → the VM names of the classes shipped for it, both ascending. Files that got none are absent. */
    public val classesByFile: Map<String, List<String>>,
    /** Classes the change touches that could belong to more than one changed file, and were therefore left out. */
    public val ambiguous: List<AmbiguousClass>,
    /** Measured changed files no shipped class was attributed to: their lines cannot be coloured, blind ones aside. */
    public val filesWithoutClasses: List<String>,
    /** Changed file → its lines that run only inside classes that cannot ship; see [ModuleManifest.ModuleFile.blindLines]. */
    public val blindLinesByFile: Map<String, List<Int>>,
    /** Changed file → its lines that are previews, by preview; see [ModuleManifest.ModuleFile.previews]. Files with none are absent. */
    public val previewLinesByFile: Map<String, List<ModuleManifest.PreviewLines>>,
    /** Changed files the rules left out, each with the rule; the same list the shared manifest carries. */
    public val excluded: List<Manifest.ExcludedFile>,
    /** Classes two class directories hold compiled differently, read from the first; none of them shipped, or the bundle would have failed. */
    public val conflicts: List<ConflictingClass>,
)

/** A class whose `SourceFile` names several changed files and whose package settles on none of them. */
public data class AmbiguousClass(
    /** The VM name. */
    public val className: String,
    public val sourceFile: String,
    /** The changed files it could belong to, in path order. */
    public val candidates: List<String>,
)

/** The file name of both manifests, at `coldspot/` and at `coldspot/<module>/`. */
public const val MANIFEST: String = "manifest.json"

private fun writeModule(moduleDir: File, manifest: ModuleManifest, classes: List<CompiledClass>) {
    val classesDir = File(moduleDir, "classes")
    classesDir.deleteRecursively()
    for (cls in classes) {
        File(classesDir, "${cls.name}.class").apply {
            parentFile.mkdirs()
            writeBytes(cls.bytes)
        }
    }
    moduleDir.mkdirs()
    File(moduleDir, MANIFEST).writeText(ManifestJson.write(manifest))
}
