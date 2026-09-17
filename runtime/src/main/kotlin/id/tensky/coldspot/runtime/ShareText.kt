package id.tensky.coldspot.runtime

/** The device the summary was taken on, in the words it goes out with. */
internal data class SharedFrom(
    /** `Google Pixel 8`. */
    val device: String,
    /** `Android 15 (API 35)`. */
    val android: String,
    /** When the package was installed, epoch millis: what stands in for a build time. */
    val installedAt: Long,
)

/** How long the lists of a summary may get before they end in "+N more". */
internal data class ShareLimits(val filesPerGroup: Int = 15, val rangesPerList: Int = 12, val itemsPerSection: Int = 10)

/**
 * The summary that is shared (DECISIONS.md "Share summary"): Markdown-lite, which is bold, inline code and
 * simple bullets and nothing else, so that it renders in a merge request and reads as plain text anywhere.
 * In the order DECISIONS.md gives: the header (branch, base and head, the uncommitted warning, the device, when it
 * was installed, since when coverage has been collected); the total; the files, worst first, with the line ranges
 * still to execute, "not executed" and "can't be measured" apart; then what is always said, even when it is "none":
 * not measurable, previews, excluded, errors, warnings; and ColdSpot's version. Files in error come before the
 * rest, as on the overview. Paths and line numbers only, never a line of source.
 */
internal fun shareText(report: Report, from: SharedFrom, formatTime: (Long) -> String, limits: ShareLimits = ShareLimits()): String = buildString {
    val build = report.build
    val measured = !report.jacocoMismatch && build != null
    append("**ColdSpot summary**\n")
    if (build != null) {
        val header = header(build, report.collectingSince, from.installedAt, formatTime)
        append("- Branch: ${build.head.branch?.let { "`$it`" } ?: "detached HEAD"}\n")
        append("- Base `${build.base.ref}` @ `${build.base.sha.take(7)}` → head ${build.head.sha?.let { "`${it.take(7)}`" } ?: "without a commit"}\n")
        if (header.baseNote != null) append("- ${header.baseNote}\n")
        if (build.head.dirty) append("- **Uncommitted changes** were part of the build\n")
        append("- ${from.device}, ${from.android}\n")
        append("- Installed ${header.installedAt}; collecting since ${header.collectingSince ?: "nothing yet"}\n")
    } else {
        append("- ${from.device}, ${from.android}\n")
    }

    append("\n**Changed lines executed:** ${if (measured) totalOfShared(report.files) else "unknown, nothing could be measured (see Errors)"}\n")
    if (measured) {
        val rows = worstFirst(report.files)
        group("In error", rows.filter { it.status == FileStatus.ERROR }, limits, withRanges = true)
        group("Never executed", rows.filter { it.status == FileStatus.NOT_EXECUTED }, limits, withRanges = true)
        group("Partly executed", rows.filter { it.status == FileStatus.PARTIAL }, limits, withRanges = true)
        group("Fully executed", rows.filter { it.status == FileStatus.EXECUTED }, limits, withRanges = false)

        val byPath = report.files.sortedBy { it.path }
        section("Not measurable", byPath.filter { it.status == FileStatus.NOT_MEASURABLE }.map { "`${it.path}`" }, limits)
        section("Previews", byPath.mapNotNull { file -> ranges(file, LineState.PREVIEW, limits)?.let { "`${file.path}`: $it" } }, limits)
        section("Excluded", byPath.filter { it.status == FileStatus.EXCLUDED }.map { "`${it.path}` by `${it.excludedBy}`" }, limits)
    }
    section("Errors", listOfNotNull(report.saveError?.let { "Coverage can't be saved: $it" }) + report.errors + report.staleClasses.map { "stale class `${it.name.replace('/', '.')}` in ${moduleLabel(listOf(it.module))}: what ran is not what the build shipped" }, limits)
    section("Warnings", report.warnings, limits)
    append("\nColdSpot ${build?.coldspotVersion ?: "(version unknown)"}")
}

/** `8 / 18 (44%)`, rounded down as on the overview; `none to execute` when no changed line has code. */
private fun totalOfShared(files: List<FileReport>): String {
    val executable = files.sumOf { it.executable }
    val executed = files.sumOf { it.executed }
    return if (executable == 0) "none to execute" else "$executed / $executable (${executed * 100 / executable}%)"
}

/** `**Partly executed (2 files)**` and a bullet per file: path, count, and the ranges still to execute. */
private fun StringBuilder.group(title: String, files: List<FileReport>, limits: ShareLimits, withRanges: Boolean) {
    if (files.isEmpty()) return
    append("\n**$title (${count(files.size, "file")})**\n")
    for (file in files.take(limits.filesPerGroup)) {
        append("- `${file.path}` ${file.executed}/${file.executable}")
        if (withRanges) {
            ranges(file, LineState.NOT_EXECUTED, limits)?.let { append("; not executed: $it") }
            ranges(file, LineState.PARTIAL, limits)?.let { append("; partly executed: $it") }
            ranges(file, LineState.BLIND, limits)?.let { append("; can't be measured: $it") }
            ranges(file, LineState.ERROR, limits)?.let { append("; in error: $it") }
        }
        append('\n')
    }
    if (files.size > limits.filesPerGroup) append("- +${files.size - limits.filesPerGroup} more\n")
}

/** `**Excluded (2)**` and its bullets, or `**Excluded:** none`: always said. */
private fun StringBuilder.section(title: String, items: List<String>, limits: ShareLimits) {
    if (items.isEmpty()) {
        append("\n**$title:** none\n")
        return
    }
    append("\n**$title (${items.size})**\n")
    for (item in items.take(limits.itemsPerSection)) append("- $item\n")
    if (items.size > limits.itemsPerSection) append("- +${items.size - limits.itemsPerSection} more\n")
}

/** The lines of [file] in [state] as ranges, `12, 15–16, 40`; null when it has none. */
private fun ranges(file: FileReport, state: LineState, limits: ShareLimits): String? {
    val lines = file.lines.filterValues { it == state }.keys.sorted()
    if (lines.isEmpty()) return null
    val ranges = lineRanges(lines)
    val listed = ranges.take(limits.rangesPerList).joinToString(", ") { (first, last) -> if (first == last) "$first" else "$first–$last" }
    return if (ranges.size > limits.rangesPerList) "$listed, +${ranges.size - limits.rangesPerList} more" else listed
}

/** Ascending [lines] as the runs of consecutive numbers they form. */
internal fun lineRanges(lines: List<Int>): List<Pair<Int, Int>> {
    val ranges = ArrayList<Pair<Int, Int>>()
    for (line in lines) {
        val last = ranges.lastOrNull()
        if (last != null && last.second + 1 == line) ranges[ranges.size - 1] = last.first to line else ranges += line to line
    }
    return ranges
}
