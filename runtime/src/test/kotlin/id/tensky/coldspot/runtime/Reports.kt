package id.tensky.coldspot.runtime

import id.tensky.coldspot.manifest.BaseSource
import id.tensky.coldspot.manifest.Manifest
import id.tensky.coldspot.manifest.ShippedClass

/** Reports as the analysis makes them, put together by hand for the tests of what the screens and the summary say. */
internal object Reports {
    const val BASE_SHA = "eb2505f6edf6187d9f2a3e17401cfa6ec7c38942"
    const val HEAD_SHA = "90550f19b443c9e2cc5539ff18db97ced77a6bd0"

    val time: (Long) -> String = { "time($it)" }

    fun build(
        source: BaseSource = BaseSource.EXPLICIT,
        ref: String = "origin/develop",
        branch: String? = "feature/login",
        sha: String? = HEAD_SHA,
        dirty: Boolean = false,
        commits: List<String> = listOf("second thing", "first thing"),
        total: Int = commits.size,
    ): BuildInfo = BuildInfo(
        coldspotVersion = "1.2.3",
        base = Manifest.Base(ref, BASE_SHA, source),
        head = Manifest.Head(sha, branch, dirty),
        commits = Manifest.Commits(commits.mapIndexed { i, summary -> Manifest.Commit("c0ffee$i", "c0ffee$i" + "0".repeat(33), summary) }, total),
    )

    /** A measured file: its changed lines' states, and whatever else the screens show of it. */
    fun file(
        path: String,
        states: Map<Int, LineState>,
        modules: List<String> = listOf("app"),
        text: String? = null,
        instructions: Map<Int, Pair<Int, Int>> = emptyMap(),
        reasons: Map<Int, String> = emptyMap(),
    ): FileReport = fileReport(path, states).copy(modules = modules, text = text, instructions = instructions, reasons = reasons)

    /** [executed] lines executed and [not] not, from line 1 on. */
    fun counted(path: String, executed: Int, not: Int, modules: List<String> = listOf("app")): FileReport =
        file(path, (1..executed).associateWith { LineState.EXECUTED } + (executed + 1..executed + not).associateWith { LineState.NOT_EXECUTED }, modules)

    fun notMeasurable(path: String): FileReport = FileReport(path, FileStatus.NOT_MEASURABLE, emptyMap(), 0, 0, text = "whatever\n")

    fun excluded(path: String, rule: String): FileReport = FileReport(path, FileStatus.EXCLUDED, emptyMap(), 0, 0, excludedBy = rule)

    fun report(
        files: List<FileReport>,
        build: BuildInfo? = build(),
        collectingSince: Long? = 2_000,
        warnings: List<String> = emptyList(),
        errors: List<String> = emptyList(),
        stale: List<ShippedClass> = emptyList(),
        jacocoMismatch: Boolean = false,
        saveError: String? = null,
    ): Report = Report(
        files = files,
        filesByStatus = files.groupingBy { it.status }.eachCount(),
        linesByState = files.flatMap { it.lines.values }.groupingBy { it }.eachCount(),
        collectingSince = collectingSince,
        warnings = warnings,
        errors = errors,
        jacocoMismatch = jacocoMismatch,
        classes = emptyList(),
        analysisMillis = 0,
        build = build,
        staleClasses = stale,
        saveError = saveError,
    )
}
