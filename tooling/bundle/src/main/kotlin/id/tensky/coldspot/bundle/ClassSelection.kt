package id.tensky.coldspot.bundle

import java.util.TreeSet

/** Which compiled classes go with which changed file, which could not be placed, and which lines are blind. */
internal class Selection(
    /** Changed file → its classes that the change touches, by name. Files that got none are absent. */
    val classesByFile: Map<String, List<CompiledClass>>,
    /** Changed file → every class placed with it, touched by the change or not, by name: where its previews are looked for. */
    val classesOfFile: Map<String, List<CompiledClass>>,
    val ambiguous: List<AmbiguousClass>,
    /** Changed file → its changed lines that run only inside classes that cannot ship; see [blindLinesOf]. */
    val blindLinesByFile: Map<String, Set<Int>>,
)

/**
 * Attributes classes to changed files by `SourceFile` and `LineNumberTable` alone, never by deriving a class
 * name from a file name: that would miss lambdas, file facades, coroutine state machines and Compose synthetics.
 *
 * `SourceFile` is a bare file name, so the package is what places it: a class belongs to the changed file whose
 * path ends in `<package dir>/<SourceFile>`. Kotlin lets package and directory disagree, so when no changed file
 * ends that way, and no file in the work tree does either, the name alone decides, provided it decides: with
 * several changed files of that name the class is [Selection.ambiguous] and left out. If the package does point
 * at an existing file, the class is that unchanged file's and stays home.
 *
 * A class is kept only if the change touches lines it has. Without a `LineNumberTable` it could only add noise
 * and is dropped, as is a class the change does not touch under any candidate, which needs no placing at all.
 *
 * A class whose `SourceFile` the diff does not know at all is a library's, or, per Finding 4, a lambda that
 * Kotlin regenerated from a library's inline function and stamped with the library's file: `LazyDsl.kt` for
 * Compose's `items { }`. Such a class cannot ship, but its SMAP still says which changed lines it holds, and
 * those are the file's [Selection.blindLinesByFile].
 *
 * [sourceFiles] is every Kotlin and Java file in the work tree, consulted only when a package points away from
 * every changed file.
 */
internal fun selectClasses(
    changedLines: Map<String, Set<Int>>,
    classes: List<CompiledClass>,
    sourceFiles: Lazy<Set<String>>,
): Selection {
    val changed = ChangedFiles(changedLines, sourceFiles)
    val matched = HashMap<String, MutableList<CompiledClass>>()
    val placed = HashMap<String, MutableList<CompiledClass>>()
    val ambiguous = ArrayList<AmbiguousClass>()
    val blind = HashMap<String, TreeSet<Int>>()

    for (cls in classes) {
        val sourceFile = cls.sourceFile ?: continue
        if (cls.lines.isEmpty()) continue
        if (!changed.hasName(sourceFile)) {
            for ((file, lines) in blindLinesOf(cls, changed)) blind.getOrPut(file) { TreeSet() } += lines
            continue
        }

        val candidates = changed.candidatesFor(sourceFile, cls.packageDir)
        if (candidates.size == 1) placed.getOrPut(candidates.single()) { ArrayList() } += cls
        val touched = candidates.filter { file -> cls.lines.any { it in changedLines.getValue(file) } }
        when {
            touched.isEmpty() -> continue
            candidates.size == 1 -> matched.getOrPut(candidates.single()) { ArrayList() } += cls
            else -> ambiguous += AmbiguousClass(cls.name, sourceFile, candidates)
        }
    }
    return Selection(
        classesByFile = matched.mapValues { (_, list) -> list.sortedBy { it.name } }.toSortedMap(),
        classesOfFile = placed.mapValues { (_, list) -> list.sortedBy { it.name } }.toSortedMap(),
        ambiguous = ambiguous.sortedBy { it.className },
        blindLinesByFile = blind.toSortedMap(),
    )
}

/** The changed files, and where a source file that a class names sits among them. */
private class ChangedFiles(val lines: Map<String, Set<Int>>, private val sourceFiles: Lazy<Set<String>>) {
    private val byName = lines.keys.groupBy { it.substringAfterLast('/') }

    fun hasName(name: String): Boolean = name in byName

    /**
     * The changed files that a file called [name], compiled into package [packageDir], could be: those whose
     * path ends in `<packageDir>/<name>`; none, if a file in the work tree ends that way, since the class is
     * that unchanged file's; else every changed file of that name, Kotlin letting package and directory disagree.
     */
    fun candidatesFor(name: String, packageDir: String): List<String> {
        val sameName = byName[name] ?: return emptyList()
        val wherePackageSays = if (packageDir.isEmpty()) name else "$packageDir/$name"
        val byPackage = sameName.filter { it.endsWithPath(wherePackageSays) }
        if (byPackage.isNotEmpty()) return byPackage
        if (sourceFiles.value.any { it.endsWithPath(wherePackageSays) }) return emptyList()
        return sameName
    }
}

/**
 * The changed lines that [cls]'s code comes from, by its SMAP: each line number the class has is traced to the
 * file and line it was inlined from, that file is placed among the changed files by the same rules as a
 * `SourceFile` (several candidates: none, never a guess), and the line is kept if it is one of the file's
 * changed lines. Lines a shipped class also covers are kept too; the app decides what that means.
 */
private fun blindLinesOf(cls: CompiledClass, changed: ChangedFiles): Map<String, Set<Int>> {
    val smap = cls.parsedSmap ?: return emptyMap()

    val placed = HashMap<Int, String?>()
    val result = HashMap<String, TreeSet<Int>>()
    for (outputLine in cls.lines) {
        val source = smap.sourceOf(outputLine) ?: continue
        val file = placed.getOrPut(source.fileId) {
            smap.files[source.fileId]?.let { changed.candidatesFor(it.name, it.packageDir).singleOrNull() }
        } ?: continue
        if (source.line in changed.lines.getValue(file)) result.getOrPut(file) { TreeSet() } += source.line
    }
    return result
}

/** Whether the path ends with [tail] on a directory boundary: `lib/xcom/a/Utils.kt` does not end with `com/a/Utils.kt`. */
private fun String.endsWithPath(tail: String): Boolean = this == tail || endsWith("/$tail")
