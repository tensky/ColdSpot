package id.tensky.coldspot.runtime

import id.tensky.coldspot.manifest.Manifest
import id.tensky.coldspot.manifest.MergedManifest
import id.tensky.coldspot.manifest.ShippedClass
import id.tensky.coldspot.shaded.org.jacoco.core.JaCoCo
import id.tensky.coldspot.shaded.org.jacoco.core.analysis.Analyzer
import id.tensky.coldspot.shaded.org.jacoco.core.analysis.CoverageBuilder
import id.tensky.coldspot.shaded.org.jacoco.core.analysis.IClassCoverage
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataStore

/** What a changed line did. The colour rule (FINDINGS.md): instructions only, never JaCoCo's branch-aware status. */
public enum class LineState {
    /** Every instruction of the line ran: green. */
    EXECUTED,

    /** Some instructions ran: amber. */
    PARTIAL,

    /** No instruction ran: red. */
    NOT_EXECUTED,

    /** Held only by a class that cannot ship (Finding 4): amber, never red or green. */
    BLIND,

    /** No instruction of any shipped class: nothing to colour. */
    NO_CODE,

    /** A Compose preview's line: never runs in the app, neutral. */
    PREVIEW,

    /** Covered by a class whose shipped bytes are not the bytes that ran (`isNoMatch`): an error, never red. */
    ERROR,
}

/** A changed file, by DECISIONS.md "File status". */
public enum class FileStatus {
    /** Green: every executable changed line executed. */
    EXECUTED,

    /** Red: no executable changed line executed. */
    NOT_EXECUTED,

    /** Yellow: anything in between, or any amber line. */
    PARTIAL,

    /** No executable changed lines. */
    NEUTRAL,

    /** A stale class covers one of its lines. */
    ERROR,

    /** No module shipped a class for it: its lines cannot be coloured. */
    NOT_MEASURABLE,

    /** Left out by an exclude rule. */
    EXCLUDED,
}

public data class FileReport(
    public val path: String,
    public val status: FileStatus,
    /** Every changed line and what it did; empty for excluded and not-measurable files. */
    public val lines: Map<Int, LineState>,
    /** For "18/20 lines": executed over executable (executed, partial, not executed and blind lines). */
    public val executed: Int,
    public val executable: Int,
    /** The exclude rule, for an excluded file. */
    public val excludedBy: String? = null,
    /** The modules that shipped a class for the file, in name order: none for a file that is not measurable or excluded. */
    public val modules: List<String> = emptyList(),
    /** The file as it was built, for showing the changed lines in place; null for a file only a module manifest names. */
    public val text: String? = null,
    /** Changed line to instructions executed and in all, summed over the classes that hold it; lines with code only. */
    public val instructions: Map<Int, Pair<Int, Int>> = emptyMap(),
    /** Changed line to what explains its state beyond the state itself: the preview it is in, the stale class that holds it. */
    public val reasons: Map<Int, String> = emptyMap(),
)

/** What was built: the shared manifest's account of the change, for the screen's header. */
public data class BuildInfo(
    public val coldspotVersion: String,
    public val base: Manifest.Base,
    public val head: Manifest.Head,
    public val commits: Manifest.Commits,
)

/** One shipped class as JaCoCo analysed it, for the debug dump and the stale-class errors. */
public data class ClassResult(
    public val shipped: ShippedClass,
    public val id: Long,
    public val noMatch: Boolean,
    public val firstLine: Int,
    public val lastLine: Int,
    /** Line to covered and total instructions, lines with instructions only. */
    public val lines: Map<Int, Pair<Int, Int>>,
)

public data class Report(
    public val files: List<FileReport>,
    /** How many files are in each status. */
    public val filesByStatus: Map<FileStatus, Int>,
    /** How many changed lines are in each state, over every measured file. */
    public val linesByState: Map<LineState, Int>,
    /** The earliest session start in the saved coverage, epoch millis; null when nothing has run yet. */
    public val collectingSince: Long?,
    /** From the merge and the analysis: things that do not add up but leave the report usable. */
    public val warnings: List<String>,
    /** Things that make (parts of) the report untrustworthy; with [jacocoMismatch] nothing is coloured at all. */
    public val errors: List<String>,
    /** Whether the manifest's JaCoCo is not the one in the app: then [files] is empty. */
    public val jacocoMismatch: Boolean,
    public val classes: List<ClassResult>,
    public val analysisMillis: Long,
    /** What was built; null without a manifest to say. */
    public val build: BuildInfo? = null,
    /**
     * The shipped classes that are not the classes that ran (`isNoMatch`): their lines are in error, never red.
     * Not among [errors]: the screens name them apart, after the errors and before the warnings.
     */
    public val staleClasses: List<ShippedClass> = emptyList(),
    /**
     * Why coverage could not be saved when ColdSpot last tried; null once a save succeeds. The report then holds what
     * the running app has gathered, and none of it outlives the app until a save succeeds.
     */
    public val saveError: String? = null,
)

/**
 * The analysis, off the main thread: every shipped class through JaCoCo's [Analyzer] against [store], then the
 * changed lines coloured by summing instructions across all the classes of a file (a line can live in several).
 *
 * States, in order of precedence: [LineState.PREVIEW] for a line a preview holds; [LineState.ERROR] for a line
 * a stale class covers; [LineState.BLIND] for a blind line; then the counters. A class whose bytes are not the
 * ones that ran (`isNoMatch`) is stale, its lines in error; a class with no line information is skipped; a class the agent never
 * saw simply has nothing covered (Finding 2). Before any of it, the manifest's JaCoCo must be this one
 * ([JaCoCo.VERSION]): otherwise probe placement may differ, and no colour is honest.
 */
public fun analyze(
    manifest: MergedManifest,
    store: ExecutionDataStore,
    classBytes: (ShippedClass) -> ByteArray?,
    collectingSince: Long?,
): Report {
    val started = System.currentTimeMillis()
    val warnings = manifest.warnings.toMutableList()
    val errors = ArrayList<String>()
    val build = BuildInfo(manifest.coldspotVersion, manifest.base, manifest.head, manifest.commits)
    if (manifest.jacoco.build != JaCoCo.VERSION) {
        errors += "The build instrumented with JaCoCo ${manifest.jacoco.build} but the app runs JaCoCo ${JaCoCo.VERSION}: probes may not line up, so nothing is coloured. Rebuild so that both match."
        return Report(emptyList(), emptyMap(), emptyMap(), collectingSince, warnings, errors, jacocoMismatch = true, classes = emptyList(), analysisMillis = System.currentTimeMillis() - started, build = build)
    }

    val classes = ArrayList<ClassResult>()
    val stale = ArrayList<ShippedClass>()
    val byFile = HashMap<String, FileAccumulator>()
    for (file in manifest.files) {
        val accumulator = FileAccumulator()
        byFile[file.path] = accumulator
        for (shipped in file.classes) {
            val bytes = classBytes(shipped)
            if (bytes == null) {
                errors += "${shipped.module}/${shipped.name}: the class file is missing from the APK's assets"
                continue
            }
            val coverage = analyzeClass(store, bytes, shipped.name) ?: continue
            if (coverage.firstLine < 0) continue
            val lines = LinkedHashMap<Int, Pair<Int, Int>>()
            for (line in coverage.firstLine..coverage.lastLine) {
                val counter = coverage.getLine(line).instructionCounter
                if (counter.totalCount > 0) lines[line] = counter.coveredCount to counter.totalCount
            }
            classes += ClassResult(shipped, coverage.id, coverage.isNoMatch, coverage.firstLine, coverage.lastLine, lines)
            if (coverage.isNoMatch) {
                stale += shipped
                for (line in lines.keys) accumulator.stale.getOrPut(line) { ArrayList() } += shipped.name
            } else {
                for ((line, counts) in lines) accumulator.add(line, counts.first, counts.second)
            }
        }
    }

    val files = ArrayList<FileReport>()
    for (file in manifest.files) {
        if (!file.measurable) {
            files += FileReport(file.path, FileStatus.NOT_MEASURABLE, emptyMap(), 0, 0, text = file.text)
            continue
        }
        val accumulator = byFile.getValue(file.path)
        val previewOf = HashMap<Int, String>()
        for (preview in file.previews) for (line in preview.lines) if (line !in previewOf) previewOf[line] = preview.reason // not putIfAbsent: API 24
        val blind = file.blindLines.toHashSet()
        val states = LinkedHashMap<Int, LineState>()
        val reasons = LinkedHashMap<Int, String>()
        for (line in file.changedLines) {
            val counts = accumulator.counts[line]
            val staleHere = accumulator.stale[line]
            states[line] = when {
                line in previewOf -> LineState.PREVIEW.also { reasons[line] = previewOf.getValue(line) }
                staleHere != null -> LineState.ERROR.also { reasons[line] = staleHere.sorted().joinToString() }
                line in blind -> LineState.BLIND
                counts == null -> LineState.NO_CODE
                counts.first == counts.second -> LineState.EXECUTED
                counts.first == 0 -> LineState.NOT_EXECUTED
                else -> LineState.PARTIAL
            }
        }
        files += fileReport(file.path, states).copy(
            modules = file.classes.map { it.module }.distinct().sorted(),
            text = file.text,
            instructions = accumulator.counts.filterKeys { it in states },
            reasons = reasons,
        )
    }
    for (excluded in manifest.excluded) files += FileReport(excluded.path, FileStatus.EXCLUDED, emptyMap(), 0, 0, excludedBy = excluded.rule)
    files.sortBy { it.path }

    return Report(
        files = files,
        filesByStatus = FileStatus.values().associateWith { status -> files.count { it.status == status } }.filterValues { it > 0 },
        linesByState = LineState.values().associateWith { state -> files.sumOf { f -> f.lines.count { it.value == state } } }.filterValues { it > 0 },
        collectingSince = collectingSince,
        warnings = warnings,
        errors = errors,
        jacocoMismatch = false,
        classes = classes,
        analysisMillis = System.currentTimeMillis() - started,
        build = build,
        staleClasses = stale,
    )
}

/**
 * [kept], what is saved and carried forward, with what [ranInThisProcess] ran under a shipped class's name and
 * another id added to it: those are the stale classes, shipped bytes that are not the bytes that ran. JaCoCo's
 * [Analyzer] finds a class stale (`isNoMatch`) when the data it is given holds nothing under the shipped class's id
 * but something under its name, whatever the id (0.8.14, `Analyzer.createAnalyzingVisitor`), so what reaches the
 * analysis under a shipped name decides it. Saving drops these entries, as it drops every id no shipped class has,
 * which keeps an edited class from carrying its old coverage; the analysis must see them all the same, or a stale
 * class would pass for one that never ran and show red instead of an error.
 *
 * [ranInThisProcess] is this process's agent data and nothing else. What an earlier launch saved under an old id is
 * an earlier build's coverage: taken for a stale class, it would put every class changed since, and not run since,
 * in error instead of "not executed". [CoverageStore.saveForAnalysis] is the one caller.
 */
internal fun withStaleClasses(kept: ExecutionDataStore, ranInThisProcess: ExecutionDataStore, shippedIds: Set<Long>, shippedNames: Set<String>): ExecutionDataStore {
    val result = ExecutionDataStore().also { kept.accept(it) }
    for (data in ranInThisProcess.contents) if (data.id !in shippedIds && data.name in shippedNames) result.put(data)
    return result
}

/** DECISIONS.md "File status", over the changed lines' states. */
internal fun fileReport(path: String, states: Map<Int, LineState>): FileReport {
    val executable = states.values.count { it == LineState.EXECUTED || it == LineState.PARTIAL || it == LineState.NOT_EXECUTED || it == LineState.BLIND }
    val executed = states.values.count { it == LineState.EXECUTED }
    val status = when {
        states.values.any { it == LineState.ERROR } -> FileStatus.ERROR
        executable == 0 -> FileStatus.NEUTRAL
        executed == executable -> FileStatus.EXECUTED
        states.values.none { it == LineState.EXECUTED || it == LineState.PARTIAL || it == LineState.BLIND } -> FileStatus.NOT_EXECUTED
        else -> FileStatus.PARTIAL
    }
    return FileReport(path, status, states, executed, executable)
}

/** JaCoCo's analysis of one class, or null when JaCoCo cannot read the bytes. */
private fun analyzeClass(store: ExecutionDataStore, bytes: ByteArray, name: String): IClassCoverage? {
    val builder = CoverageBuilder()
    try {
        Analyzer(store, builder).analyzeClass(bytes, name)
    } catch (e: java.io.IOException) {
        return null
    }
    return builder.classes.firstOrNull()
}

/** The JaCoCo class ids of [bytes], for carry-forward: what an unchanged class keeps its coverage by. */
public fun classId(bytes: ByteArray, name: String): Long? = analyzeClass(ExecutionDataStore(), bytes, name)?.id

private class FileAccumulator {
    val counts = HashMap<Int, Pair<Int, Int>>()

    /** Line to the stale classes that hold it. */
    val stale = HashMap<Int, MutableList<String>>()

    fun add(line: Int, covered: Int, total: Int) {
        val before = counts[line] ?: (0 to 0)
        counts[line] = (before.first + covered) to (before.second + total)
    }
}
