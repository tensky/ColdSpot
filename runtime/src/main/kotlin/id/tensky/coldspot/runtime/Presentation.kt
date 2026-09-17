package id.tensky.coldspot.runtime

import id.tensky.coldspot.manifest.BaseSource

// What the screens say, worked out without Android so that it can be tested on the JVM: which marker a line gets
// and what it says when tapped, the order of the files, the header's sentences. The views only lay this out.
// The copy says "executed", never "tested".

/** What a changed line did: one marker each, told apart by shape and by words, never by colour alone. */
internal enum class Marker(val label: String) {
    EXECUTED("Executed"),
    PARTIAL("Partly executed"),
    NOT_EXECUTED("Not executed"),

    /** Amber like [PARTIAL], and a marker of its own: nothing is known about it, which is not "some of it ran". */
    BLIND("Can't be measured"),
    NO_CODE("No code"),
    PREVIEW("Preview"),
    ERROR("Error");

    companion object {
        fun of(state: LineState): Marker = when (state) {
            LineState.EXECUTED -> EXECUTED
            LineState.PARTIAL -> PARTIAL
            LineState.NOT_EXECUTED -> NOT_EXECUTED
            LineState.BLIND -> BLIND
            LineState.NO_CODE -> NO_CODE
            LineState.PREVIEW -> PREVIEW
            LineState.ERROR -> ERROR
        }
    }
}

/** What a file's changed lines did together (DECISIONS.md "File status"), worst first. */
internal enum class FileMarker(val label: String) {
    ERROR("Error"),
    NOT_EXECUTED("Never executed"),
    PARTIAL("Partly executed"),
    EXECUTED("Fully executed"),
    NEUTRAL("Nothing to execute");

    companion object {
        /** Null for a file that has no row of its own: not measurable, or excluded. */
        fun of(status: FileStatus): FileMarker? = when (status) {
            FileStatus.ERROR -> ERROR
            FileStatus.NOT_EXECUTED -> NOT_EXECUTED
            FileStatus.PARTIAL -> PARTIAL
            FileStatus.EXECUTED -> EXECUTED
            FileStatus.NEUTRAL -> NEUTRAL
            FileStatus.NOT_MEASURABLE, FileStatus.EXCLUDED -> null
        }
    }
}

/** What tapping a changed line says: its state in a sentence, with the numbers or the reason behind it. */
internal fun explain(state: LineState, instructions: Pair<Int, Int>?, reason: String?): String = when (state) {
    LineState.EXECUTED, LineState.PARTIAL, LineState.NOT_EXECUTED -> executedOf(instructions ?: (0 to 0))
    LineState.BLIND ->
        "Can't be measured: code inside an inline lambda, which cannot be traced back to this line. It may have run, or not." +
            (instructions?.let { " Of what can be measured here, ${executedOf(it)}." } ?: "")
    LineState.NO_CODE -> "No code on this line"
    LineState.PREVIEW -> "Preview, never runs in the app" + (reason?.let { ": $it" } ?: "")
    LineState.ERROR ->
        "Error: the class that ran is not the class this build shipped" + (reason?.let { " ($it)" } ?: "") +
            ", so this line cannot be coloured. Build and install again."
}

private fun executedOf(instructions: Pair<Int, Int>): String =
    "${instructions.first} of ${instructions.second} ${if (instructions.second == 1) "instruction" else "instructions"} executed"

/**
 * The files that have a row, worst first: in error, never executed, partly executed, fully executed, nothing to
 * execute; among equals the one with more lines left to execute first, then by path.
 */
internal fun worstFirst(files: List<FileReport>): List<FileReport> =
    files.filter { FileMarker.of(it.status) != null }
        .sortedWith(compareBy<FileReport> { FileMarker.of(it.status)!!.ordinal }.thenByDescending { it.executable - it.executed }.thenBy { it.path })

/** `18 / 24 changed lines executed (75%)`, the percentage rounded down: 100% is every line and nothing less. */
internal fun totalOf(files: List<FileReport>): String {
    val executable = files.sumOf { it.executable }
    val executed = files.sumOf { it.executed }
    return if (executable == 0) "No changed lines to execute" else "$executed / $executable changed lines executed (${executed * 100 / executable}%)"
}

/** `:feature:foryou:impl` for the module folder `feature/foryou/impl`; several modules side by side. */
internal fun moduleLabel(modules: List<String>): String = modules.joinToString(", ") { if (it == "root") ":" else ":" + it.replace('/', ':') }

internal fun fileName(path: String): String = path.substringAfterLast('/')

/** The directory with its closing slash, to stand dimmed before the name; empty for a file at the root. */
internal fun directory(path: String): String = path.substring(0, path.lastIndexOf('/') + 1)

internal fun count(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

/** The screen's header: what was built, from what, and since when it has been watched. */
internal data class Header(
    /** `origin/main @ eb2505f`. */
    val base: String,
    /** How the base came about when nobody set it, and from where; null when it was set. */
    val baseNote: String?,
    /** `feature/login @ 90550f1`, `detached HEAD @ 90550f1`, `fresh, no commit yet`. */
    val head: String,
    /** The build holds changes no commit has: the APK is not HEAD's. */
    val uncommitted: Boolean,
    val installedAt: String,
    /** Null when nothing has been collected yet. */
    val collectingSince: String?,
    /** `4 commits`, to unfold. */
    val commitsTitle: String,
    /** `90550f1 Update Hilt Test`, newest first, as many as the manifest lists. */
    val commits: List<String>,
    /** How many more there are than [commits] lists. */
    val moreCommits: Int,
    /** `ColdSpot 0.1.0`. */
    val version: String,
)

/** Under "Coverage can't be saved": what that means, and the way out. */
internal const val SAVE_ERROR_DETAIL =
    "Until a save succeeds, what the app executes is lost when it stops. The saved file is never written over; Reset deletes it."

internal data class Banner(val kind: Kind, val title: String, val details: List<String>) {
    /** In the order they are shown. */
    enum class Kind { ERROR, STALE, WARNING }
}

internal data class FileRow(
    val path: String,
    val name: String,
    val directory: String,
    /** `:feature:foryou:impl`; empty when no module shipped a class for it. */
    val module: String,
    val marker: FileMarker,
    /** `18/20`: changed lines executed over those that can be; `–` when none can. */
    val count: String,
    /** For a screen reader, marker and count in words. */
    val description: String,
)

/** A collapsed list under the files: what was left out, each with the reason. */
internal data class Section(val title: String, val items: List<Item>) {
    data class Item(val path: String, val reason: String)
}

internal sealed class Overview {
    /** Nothing can be shown but the reason: no bundle in the APK, no agent, or another JaCoCo than the build's. */
    data class Failed(val title: String, val messages: List<String>, val header: Header?) : Overview()

    /** A build with nothing changed against its base. */
    data class Empty(val header: Header, val message: String) : Overview()

    data class Ready(
        val header: Header,
        val banners: List<Banner>,
        val total: String,
        val files: List<FileRow>,
        val notMeasurable: Section,
        val excluded: Section,
    ) : Overview()
}

/**
 * The overview of [report]. [installedAt] is when the package was last installed, which stands in for a build
 * time: the manifest carries none, or every build would change it and be packaged again for nothing.
 */
internal fun overview(report: Report, installedAt: Long, formatTime: (Long) -> String): Overview {
    val build = report.build
    val header = build?.let { header(it, report.collectingSince, installedAt, formatTime) }
    if (build == null || header == null) return Overview.Failed("ColdSpot has nothing to show", report.errors.ifEmpty { listOf("This build carries no ColdSpot bundle.") }, null)
    if (report.jacocoMismatch) return Overview.Failed("Nothing can be coloured", report.errors, header)
    if (report.files.isEmpty() && report.errors.isEmpty() && report.warnings.isEmpty()) {
        return Overview.Empty(header, "No changed lines against ${header.base}. Nothing to execute: what was built is what the base has.")
    }

    val banners = ArrayList<Banner>()
    report.saveError?.let { reason -> banners += Banner(Banner.Kind.ERROR, "Coverage can't be saved: $reason", listOf(SAVE_ERROR_DETAIL)) }
    if (report.errors.isNotEmpty()) banners += Banner(Banner.Kind.ERROR, count(report.errors.size, "error"), report.errors)
    if (report.staleClasses.isNotEmpty()) {
        banners += Banner(
            Banner.Kind.STALE,
            count(report.staleClasses.size, "stale class", "stale classes") + ": what ran is not what this build shipped. Their lines are in error; build and install again.",
            report.staleClasses.map { "${moduleLabel(listOf(it.module))} ${it.name.replace('/', '.')}" },
        )
    }
    if (report.warnings.isNotEmpty()) banners += Banner(Banner.Kind.WARNING, count(report.warnings.size, "warning"), report.warnings)

    val rows = worstFirst(report.files).map { file ->
        val marker = FileMarker.of(file.status)!!
        FileRow(
            path = file.path,
            name = fileName(file.path),
            directory = directory(file.path),
            module = moduleLabel(file.modules),
            marker = marker,
            count = if (file.executable == 0) "–" else "${file.executed}/${file.executable}",
            description = marker.label + if (file.executable == 0) "" else ": ${file.executed} of ${count(file.executable, "changed line")} executed",
        )
    }
    val notMeasurable = report.files.filter { it.status == FileStatus.NOT_MEASURABLE }
    val excluded = report.files.filter { it.status == FileStatus.EXCLUDED }
    return Overview.Ready(
        header = header,
        banners = banners,
        total = totalOf(report.files),
        files = rows,
        notMeasurable = Section(
            "Not measurable (${notMeasurable.size})",
            notMeasurable.map { Section.Item(it.path, "No module shipped a class for it: a plain Kotlin/JVM module, or a module without ColdSpot.") },
        ),
        excluded = Section("Excluded (${excluded.size})", excluded.map { Section.Item(it.path, "Left out by the rule ${it.excludedBy}") }),
    )
}

internal fun header(build: BuildInfo, collectingSince: Long?, installedAt: Long, formatTime: (Long) -> String): Header {
    val base = "${build.base.ref} @ ${short(build.base.sha)}"
    val head = build.head
    return Header(
        base = base,
        baseNote = when (build.base.source) {
            BaseSource.EXPLICIT -> null
            BaseSource.ORIGIN_HEAD -> "No base was set: origin/HEAD, the remote's default branch, was used."
            BaseSource.GUESSED -> "Guessed: no base was set and the repository has no origin/HEAD, so ${build.base.ref} was used. Set coldSpot { baseRef } to be sure."
        },
        head = when {
            head.sha == null -> "${head.branch ?: "HEAD"}, no commit yet"
            head.branch == null -> "detached HEAD @ ${short(head.sha!!)}"
            else -> "${head.branch} @ ${short(head.sha!!)}"
        },
        uncommitted = head.dirty,
        installedAt = formatTime(installedAt),
        collectingSince = collectingSince?.let(formatTime),
        commitsTitle = if (build.commits.total == 0) "No commits since the base" else count(build.commits.total, "commit"),
        commits = build.commits.listed.map { "${it.shortSha} ${it.summary}" },
        moreCommits = build.commits.total - build.commits.listed.size,
        version = "ColdSpot ${build.coldspotVersion}",
    )
}

private fun short(sha: String): String = sha.take(SHORT_SHA)

/** As long as the manifest's own short SHAs. */
internal const val SHORT_SHA = 7

/** One row of the overview's list. The list is flat: what unfolds adds rows under its own. */
internal sealed class OverviewItem {
    data class Head(val header: Header) : OverviewItem()

    /** A row that unfolds what is under it: the commits, a section. Not [enabled] when there is nothing under it. */
    data class Toggle(val key: String, val title: String, val expanded: Boolean, val enabled: Boolean) : OverviewItem()
    data class Commit(val text: String) : OverviewItem()
    data class BannerRow(val banner: Banner) : OverviewItem()
    data class Total(val text: String) : OverviewItem()
    data class File(val row: FileRow) : OverviewItem()
    data class Left(val item: Section.Item) : OverviewItem()

    /** The empty state and the error state: a title and what there is to say. */
    data class Message(val failed: Boolean, val title: String, val messages: List<String>) : OverviewItem()

    /** The bubble switch and the reset, below everything. */
    object Actions : OverviewItem()
}

internal const val COMMITS = "commits"
internal const val NOT_MEASURABLE = "not measurable"
internal const val EXCLUDED = "excluded"

/**
 * [overview] as the rows of its list, with the rows of whatever is in [expanded] unfolded. An error that leaves
 * nothing to colour is all there is, with the actions under it: no header, no files.
 */
internal fun overviewItems(overview: Overview, expanded: Set<String>): List<OverviewItem> {
    val items = ArrayList<OverviewItem>()
    fun head(header: Header) {
        items += OverviewItem.Head(header)
        items += OverviewItem.Toggle(COMMITS, header.commitsTitle, COMMITS in expanded, enabled = header.commits.isNotEmpty())
        if (COMMITS in expanded) {
            header.commits.forEach { items += OverviewItem.Commit(it) }
            if (header.moreCommits > 0) items += OverviewItem.Commit("+${header.moreCommits} more")
        }
    }
    fun section(key: String, section: Section) {
        items += OverviewItem.Toggle(key, section.title, key in expanded, enabled = section.items.isNotEmpty())
        if (key in expanded) section.items.forEach { items += OverviewItem.Left(it) }
    }
    when (overview) {
        is Overview.Failed -> items += OverviewItem.Message(failed = true, overview.title, overview.messages)
        is Overview.Empty -> {
            head(overview.header)
            items += OverviewItem.Message(failed = false, "No changes", listOf(overview.message))
        }
        is Overview.Ready -> {
            head(overview.header)
            overview.banners.forEach { items += OverviewItem.BannerRow(it) }
            items += OverviewItem.Total(overview.total)
            overview.files.forEach { items += OverviewItem.File(it) }
            section(NOT_MEASURABLE, overview.notMeasurable)
            section(EXCLUDED, overview.excluded)
        }
    }
    items += OverviewItem.Actions
    return items
}
