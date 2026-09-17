package id.tensky.coldspot.bundle

/**
 * A class file's `SourceDebugExtension` (a JSR-045 "SMAP"), as kotlinc writes it: which source line each of
 * the class's line numbers really comes from, once inline functions and lambdas have been folded in.
 *
 * Only the default stratum is read. kotlinc's second, `KotlinDebug`, maps the other way round. From a real
 * one, for a lambda regenerated from Compose's inline `items`:
 * ```
 * SMAP
 * LazyDsl.kt
 * Kotlin
 * *S Kotlin
 * *F
 * + 1 LazyDsl.kt
 * androidx/compose/foundation/lazy/LazyDslKt$items$4
 * + 2 Feed.kt
 * id/tensky/coldspotspike/FeedKt
 * *L
 * 1#1,523:1
 * 27#2:524
 * 28#2:531
 * *E
 * ```
 * A `*F` entry is `id name`, or `+ id name` followed by a path line, which kotlinc fills with the VM name of
 * the file's facade class. A `*L` entry is `inputStart[#fileId][,repeat]:outputStart[,increment]`, each of
 * the `repeat` input lines taking `increment` output lines; a missing file id repeats the previous entry's.
 */
internal class Smap(val files: Map<Int, SmapFile>, private val ranges: List<LineRange>) {
    /** The source line that output line [line] came from, or null when the SMAP does not say. */
    fun sourceOf(line: Int): SourceLine? {
        for (range in ranges) range.sourceOf(line)?.let { return it }
        return null
    }
}

internal class SmapFile(val id: Int, val name: String, val path: String?) {
    /** The package of the class kotlinc named for this file, as a directory: `com/acme`; empty without a path. */
    val packageDir: String get() = path?.substringBeforeLast('/', "") ?: ""
}

internal class SourceLine(val fileId: Int, val line: Int)

internal class LineRange(val inputStart: Int, val fileId: Int, val repeat: Int, val outputStart: Int, val increment: Int) {
    fun sourceOf(line: Int): SourceLine? {
        val offset = line - outputStart
        if (offset < 0 || offset >= repeat * increment) return null
        return SourceLine(fileId, inputStart + offset / increment)
    }
}

/** @throws IllegalArgumentException when [text] does not begin like an SMAP */
internal fun parseSmap(text: String): Smap {
    val lines = text.lines()
    require(lines.size >= 3 && lines[0] == "SMAP") { "not an SMAP: begins with '${lines.firstOrNull()}'" }
    val defaultStratum = lines[2]

    val files = LinkedHashMap<Int, SmapFile>()
    val ranges = ArrayList<LineRange>()
    var stratum: String? = null
    var section = ""
    var fileId = 1
    var i = 3
    while (i < lines.size) {
        val line = lines[i++]
        when {
            line == "*E" -> break
            line.startsWith("*S ") -> {
                stratum = line.substring(3).trim()
                section = ""
            }
            line.startsWith("*") -> section = line // *F, *L, or a section with nothing for us
            stratum != defaultStratum -> continue
            section == "*F" -> {
                val entry = line.removePrefix("+ ")
                val id = entry.substringBefore(' ').toInt()
                val path = if (line.startsWith("+ ")) lines[i++] else null
                files[id] = SmapFile(id, entry.substringAfter(' '), path)
            }
            section == "*L" -> {
                val input = line.substringBefore(':')
                val output = line.substringAfter(':')
                if ('#' in input) fileId = input.substringAfter('#').substringBefore(',').toInt()
                ranges += LineRange(
                    inputStart = input.substringBefore('#').substringBefore(',').toInt(),
                    fileId = fileId,
                    repeat = input.substringAfter(',', "").ifEmpty { "1" }.toInt(),
                    outputStart = output.substringBefore(',').toInt(),
                    increment = output.substringAfter(',', "").ifEmpty { "1" }.toInt(),
                )
            }
        }
    }
    return Smap(files, ranges)
}
