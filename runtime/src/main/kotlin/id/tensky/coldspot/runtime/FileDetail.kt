package id.tensky.coldspot.runtime

/** One row of a file's screen. */
internal sealed class DetailRow {
    /**
     * A line of the file. A changed line has a [marker] and an [explanation], shown when it is tapped; a line of
     * context has neither.
     */
    data class Line(val number: Int, val text: String, val marker: Marker?, val explanation: String?) : DetailRow()

    /** The unchanged lines between two hunks, or before the first, or after the last: not shown, counted. */
    data class Gap(val lines: Int) : DetailRow() {
        val text: String get() = "⋯ ${count(lines, "unchanged line")}"
    }
}

/** A changed file's own screen. */
internal data class FileDetail(
    val path: String,
    val name: String,
    val directory: String,
    /** `:feature:foryou:impl`; empty when no module shipped a class for it. */
    val module: String,
    /** Null for a file that is not measurable. */
    val marker: FileMarker?,
    /** `Partly executed: 18 of 20 changed lines executed`. */
    val status: String,
    /**
     * The changed lines still to look at, listed at the top (DECISIONS.md "Dev page structure"): `Not executed: lines
     * 40–58, 72`, and `Can't be measured: line 20` when there are blind lines. Empty when there is a [note] instead.
     */
    val summary: List<String>,
    /** [summary] as TalkBack says it: `lines 40 to 58, 72`, a dash being no word. */
    val summaryDescription: String,
    /** The changed lines in their hunks; empty when there is a [note] instead. */
    val rows: List<DetailRow>,
    /** Why there is nothing to show, when there is nothing. */
    val note: String?,
)

/**
 * [file]'s changed lines as hunks: every changed line with [context] lines of the file before and after it,
 * hunks that touch or overlap joined, and what lies between them counted, not shown.
 */
internal fun fileDetail(file: FileReport, context: Int = CONTEXT_LINES): FileDetail {
    val marker = FileMarker.of(file.status)
    val status = when {
        marker == null -> if (file.status == FileStatus.EXCLUDED) "Excluded by the rule ${file.excludedBy}" else "Not measurable"
        file.executable == 0 -> marker.label
        else -> "${marker.label}: ${file.executed} of ${count(file.executable, "changed line")} executed"
    }
    val text = file.text
    val note = when {
        text == null ->
            "There is nothing to show of this file: ${moduleLabel(file.modules).ifEmpty { "a module" }} shipped classes for it, but the app's manifest " +
                "has it neither as changed nor as excluded, so its text and its changed lines are unknown. The modules were not built from the same change; see the warning on the overview."
        file.lines.isEmpty() -> "No changed line of this file is measured."
        else -> null
    }
    return FileDetail(
        path = file.path,
        name = fileName(file.path),
        directory = directory(file.path),
        module = moduleLabel(file.modules),
        marker = marker,
        status = status,
        summary = if (note == null) summary(file, spoken = false) else emptyList(),
        summaryDescription = if (note == null) summary(file, spoken = true).joinToString(". ") else "",
        rows = if (note == null) hunks(lines(text!!), file, context) else emptyList(),
        note = note,
    )
}

/** `Not executed: …`, always, and `Can't be measured: …` when [file] has blind lines. */
private fun summary(file: FileReport, spoken: Boolean): List<String> {
    fun linesIn(state: LineState) = file.lines.filterValues { it == state }.keys.sorted()
    val blind = linesIn(LineState.BLIND)
    return listOfNotNull(
        "Not executed: ${lineList(linesIn(LineState.NOT_EXECUTED), spoken)}",
        if (blind.isEmpty()) null else "Can't be measured: ${lineList(blind, spoken)}",
    )
}

/**
 * Ascending [lines] as the summary lists them: `lines 40–58, 72`, `line 72`, `none`; [spoken], `lines 40 to 58, 72`.
 * As many ranges as the share summary lists, then `+3 more`.
 */
internal fun lineList(lines: List<Int>, spoken: Boolean = false): String {
    if (lines.isEmpty()) return "none"
    val ranges = lineRanges(lines)
    val limit = ShareLimits().rangesPerList
    val listed = ranges.take(limit).joinToString(", ") { (first, last) -> if (first == last) "$first" else if (spoken) "$first to $last" else "$first–$last" }
    val more = ranges.size - limit
    val tail = if (more <= 0) "" else if (spoken) ", and $more more" else ", +$more more"
    return (if (lines.size == 1) "line " else "lines ") + listed + tail
}

/** The lines of [text] as the compilers and the diff count them: ended by LF, CR LF or a lone CR; a last line without an end counts. */
internal fun lines(text: String): List<String> {
    val lines = ArrayList<String>()
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\n' || c == '\r') {
            lines += text.substring(start, i)
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            start = i + 1
        }
        i++
    }
    if (start < text.length) lines += text.substring(start)
    return lines
}

private fun hunks(lines: List<String>, file: FileReport, context: Int): List<DetailRow> {
    // A changed line beyond the end of the text would be a manifest that contradicts itself: left out rather than invented.
    val changed = file.lines.keys.filter { it in 1..lines.size }.sorted()
    val rows = ArrayList<DetailRow>()
    var shown = 0 // the last line that has a row, or is counted in a gap
    var i = 0
    while (i < changed.size) {
        val first = maxOf(1, changed[i] - context)
        var last = changed[i] + context
        // the next changed line joins this hunk when its context reaches into this one's; one that begins right after follows with no gap
        while (i + 1 < changed.size && changed[i + 1] - context <= last) {
            i++
            last = changed[i] + context
        }
        last = minOf(last, lines.size)
        if (first > shown + 1) rows += DetailRow.Gap(first - shown - 1)
        for (number in first..last) {
            val state = file.lines[number]
            rows += DetailRow.Line(
                number = number,
                text = lines[number - 1],
                marker = state?.let(Marker::of),
                explanation = state?.let { explain(it, file.instructions[number], file.reasons[number]) },
            )
        }
        shown = last
        i++
    }
    if (shown < lines.size) rows += DetailRow.Gap(lines.size - shown)
    return rows
}

internal const val CONTEXT_LINES = 3
