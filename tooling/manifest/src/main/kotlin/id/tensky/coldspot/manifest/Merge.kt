package id.tensky.coldspot.manifest

/**
 * What the runtime works from: the shared [Manifest] joined with every module's [ModuleManifest] by file path,
 * see [merge]. Files are in path order, classes in module then name order, whatever order the manifests came in.
 */
public data class MergedManifest(
    public val coldspotVersion: String,
    public val base: Manifest.Base,
    public val head: Manifest.Head,
    public val commits: Manifest.Commits,
    public val jacoco: Manifest.Jacoco,
    public val files: List<MergedFile>,
    public val excluded: List<Manifest.ExcludedFile>,
    /** What does not add up between the manifests, for the app to show: the build is still usable. */
    public val warnings: List<String>,
)

public data class MergedFile(
    public val path: String,
    /** As the shared manifest has them; empty for a file only a module manifest names. */
    public val changedLines: List<Int>,
    /** As the shared manifest has it; null for a file only a module manifest names. */
    public val text: String?,
    /** Every class any module shipped for this file, each with the module whose `classes/` folder holds it. */
    public val classes: List<ShippedClass>,
    /** The union of the modules' blind lines, ascending. */
    public val blindLines: List<Int>,
    /** The union of the modules' previews, in line order. */
    public val previews: List<ModuleManifest.PreviewLines>,
) {
    /** A file nobody shipped a class for cannot be coloured: "not measurable", neutral, never red. */
    public val measurable: Boolean get() = classes.isNotEmpty()
}

/** A shipped class: `coldspot/<module>/classes/<name>.class` in the APK's assets. */
public data class ShippedClass(public val module: String, public val name: String)

/**
 * Joins [shared] with [modules] by file path: files are the union of both sides, each file's classes the union
 * of what every module shipped for it (remembering which module), its blind lines and previews the unions of
 * theirs. The version, base, head, commits, JaCoCo and the excluded files are the shared manifest's, read once. A file no
 * module shipped a class for is kept, [MergedFile.measurable] false.
 *
 * Exclusion wins (DECISIONS.md "Noise"): a file the shared manifest excludes is excluded and nothing else, whatever
 * a module shipped for it. That module's classes, blind lines and previews for it are ignored, and a
 * [MergedManifest.warnings] entry names the module: it was built with other exclude rules than the app.
 *
 * A file a module names that the shared manifest has neither as changed nor as excluded is kept, with no text and no
 * changed lines, so that nothing shipped goes unseen, and a warning says so: the modules were not built from the same
 * change.
 */
public fun merge(shared: Manifest, modules: List<ModuleManifest>): MergedManifest {
    val excluded = shared.excluded.associateBy { it.path }
    val excludedButShipped = HashMap<String, MutableSet<String>>()
    val classes = HashMap<String, MutableSet<ShippedClass>>()
    val blind = HashMap<String, MutableSet<Int>>()
    val previews = HashMap<String, MutableSet<ModuleManifest.PreviewLines>>()
    for (module in modules) {
        for (file in module.files) {
            if (file.path in excluded) {
                excludedButShipped.getOrPut(file.path) { HashSet() } += module.module
                continue
            }
            classes.getOrPut(file.path) { HashSet() } += file.classes.map { ShippedClass(module.module, it) }
            blind.getOrPut(file.path) { HashSet() } += file.blindLines
            previews.getOrPut(file.path) { HashSet() } += file.previews
        }
    }
    val fromShared = shared.files.associateBy { it.path }
    val paths = (fromShared.keys + classes.keys).sorted()
    val files = paths.map { path ->
        val changed = fromShared[path]
        MergedFile(
            path = path,
            changedLines = changed?.changedLines ?: emptyList(),
            text = changed?.text,
            classes = classes[path].orEmpty().sortedWith(compareBy({ it.module }, { it.name })),
            blindLines = blind[path].orEmpty().sorted(),
            previews = previews[path].orEmpty().sortedWith(compareBy({ it.lines.firstOrNull() ?: 0 }, { it.reason })),
        )
    }
    val warnings = excludedButShipped.keys.sorted().map { path ->
        "${shipping(excludedButShipped.getValue(path))} classes for $path, which the app excludes (${excluded.getValue(path).rule}): " +
            "they are ignored, and the file shows as excluded. Set the exclude rules once, in the convention plugin that applies " +
            "ColdSpot, so that every module excludes the same files."
    } + paths.filter { it !in fromShared }.map { path ->
        val names = modules.filter { m -> m.files.any { it.path == path } }.map { it.module }
        "${shipping(names)} classes for $path, which the app's manifest has neither as changed nor as excluded: the modules " +
            "were not built from the same change."
    }
    return MergedManifest(shared.coldspotVersion, shared.base, shared.head, shared.commits, shared.jacoco, files, shared.excluded, warnings)
}

/** `:core:ui ships`, `:a and :b ship`, `:a, :b and :c ship`: modules by their project paths, in order. */
private fun shipping(modules: Collection<String>): String {
    val paths = modules.sorted().map { ":" + it.replace('/', ':') }
    val listed = if (paths.size == 1) paths.single() else paths.dropLast(1).joinToString() + " and " + paths.last()
    return if (paths.size == 1) "$listed ships" else "$listed ship"
}
