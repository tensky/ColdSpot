package id.tensky.coldspot.manifest

/**
 * `coldspot/manifest.json`, written once per APK by the application module: everything about the change that
 * does not depend on which module compiled what. What each module shipped for it is in that module's own
 * [ModuleManifest], next to its class files; [merge] joins the two by file path.
 *
 * Read and written by [ManifestJson]. Property order here is the order in the file. [SCHEMA_VERSION] moves when
 * a reader of the previous shape would misread the new one, and a reader refuses any other.
 */
public data class Manifest(
    public val schemaVersion: Int,
    /** The ColdSpot that built the APK: the plugin's version, which is the runtime's too. For the app to show, and to share. */
    public val coldspotVersion: String,
    public val base: Base,
    public val head: Head,
    public val commits: Commits,
    /** The JaCoCo that instrumented the APK's changed classes: the app must analyse with the same, or its colours are wrong. */
    public val jacoco: Jacoco,
    /**
     * A fresh-session build's token (`-Pcoldspot.freshSession`): a UUID no two builds share. The runtime wipes its saved
     * coverage once when it first sees a token, and remembers it; a build without one (null) never wipes anything.
     */
    public val resetToken: String?,
    /** Every changed source file the build measures, whether or not any module could attribute a class to it. */
    public val files: List<ChangedFile>,
    /** Every changed source file the build leaves out as noise, and why. Never dropped in silence. */
    public val excluded: List<ExcludedFile>,
) {
    /** What the working tree was compared with. */
    public data class Base(
        /** The ref as given, or as the diff module chose it; [source] says which. */
        public val ref: String,
        /** The commit actually compared with: a branch's merge-base with HEAD, anything else as given. */
        public val sha: String,
        public val source: BaseSource,
    )

    public data class Head(
        /** HEAD's commit, or null while HEAD is unborn. */
        public val sha: String?,
        /** The branch HEAD is on, `feature/login`; null while HEAD is detached. */
        public val branch: String?,
        /** Whether anything on disk differs from HEAD, sources or not: the APK is not HEAD's. */
        public val dirty: Boolean,
    )

    /** The commits in the build: HEAD's history back to, and excluding, [Base.sha], in `git log <base>..HEAD` order. */
    public data class Commits(
        /** The newest 50 at most; [total] says how many there are in all, for a "+N more". */
        public val listed: List<Commit>,
        public val total: Int,
    )

    public data class Commit(
        /** The first 7 characters of [sha]. */
        public val shortSha: String,
        public val sha: String,
        /** The first line of the commit message. */
        public val summary: String,
    )

    /**
     * The JaCoCo whose `Instrumenter` placed the probes. Probe placement and class ids must agree between it and
     * the `Analyzer` on the device, so the build forces this version onto the coverage runtime classpath and the
     * app compares [build] with the `JaCoCo.VERSION` of the jacoco-core it runs, showing an error rather than
     * colours when they differ.
     */
    public data class Jacoco(
        /** The Maven version of jacoco-core, `0.8.14`: the coordinate the runtime classpath is forced to. */
        public val version: String,
        /** `org.jacoco.core.JaCoCo.VERSION` of that jacoco-core, the qualified build string, `0.8.14.202510111229`. */
        public val build: String,
    )

    public data class ChangedFile(
        /** Relative to the work tree root, `/`-separated. */
        public val path: String,
        /** 1-based and ascending, in the working-tree version of the file. */
        public val changedLines: List<Int>,
        /** The whole file, as it is on disk. Here and nowhere else: module manifests carry no text. */
        public val text: String,
    )

    /** A changed file an exclude rule matched: not measured, listed so that the app can say so. */
    public data class ExcludedFile(
        public val path: String,
        /** How many lines the change touched, for a "12 lines, excluded". */
        public val changedLines: Int,
        /** The pattern that matched, as written in the rules. */
        public val rule: String,
    )

    public companion object {
        /**
         * 2: `blindLines` added. 3: [commits] added. 4: one manifest per APK plus one per module: the file text
         * and [excluded] live here, the classes and blind lines in [ModuleManifest]. 5: [ModuleManifest.ModuleFile.previews].
         * 6: [jacoco]. 7: [resetToken]. 8: [coldspotVersion] and [Head.branch]. No build time, on purpose: it would
         * change the manifest with every build, and the APK would be packaged again although nothing changed.
         */
        public const val SCHEMA_VERSION: Int = 8
    }
}

/** How the base was arrived at; mirrors the diff module's own enum by name, so that this module needs no git. */
public enum class BaseSource {
    /** Passed in by the caller. */
    EXPLICIT,

    /** Nothing was passed, and the remote's default branch, `origin/HEAD`, exists. */
    ORIGIN_HEAD,

    /** Nothing was passed and there is no `origin/HEAD`: `origin/main`, or failing that `origin/master`. */
    GUESSED,
}

/**
 * `coldspot/<module>/manifest.json`, written by every module the plugin is applied to: which of its compiled
 * classes shipped for which changed file, which changed lines only its unshippable classes hold, and which
 * changed lines are previews. Nothing about the change itself, and no file text: that is the application's
 * [Manifest], once per APK.
 */
public data class ModuleManifest(
    public val schemaVersion: Int,
    /** The module's project path as a folder, `feature/foryou/impl`; the folder this manifest sits in. */
    public val module: String,
    /** Only the changed files this module has something for; in path order. */
    public val files: List<ModuleFile>,
) {
    public data class ModuleFile(
        /** As in [Manifest.ChangedFile.path]. */
        public val path: String,
        /** VM names (`com/acme/FeedKt$lambda$1`) of the classes shipped for this file, ascending. */
        public val classes: List<String>,
        /**
         * Changed lines that run only inside classes this module cannot ship: lambdas Kotlin regenerated from
         * a library's inline function and stamped with the library's file (Finding 4). Their probes fire, but
         * JaCoCo maps no line to them, so a line here must never show as red; amber at most. Ascending, and
         * possibly covered by one of [classes] as well.
         */
        public val blindLines: List<Int>,
        /**
         * Changed lines that belong to Compose previews, which never run in the app: neutral, never red, and
         * shown with the reason. Each entry is one preview's lines; in line order, no line twice.
         */
        public val previews: List<PreviewLines>,
    )

    /** The changed lines one preview holds (its `@Preview` function, or a `PreviewParameterProvider` class), and which. */
    public data class PreviewLines(
        /** Ascending. */
        public val lines: List<Int>,
        /** `@Preview on GreetingPreview`, `@DevicePreviews on ForYouScreenLoading (@Preview via @DevicePreviews)`, `Names implements PreviewParameterProvider`. */
        public val reason: String,
    )
}
