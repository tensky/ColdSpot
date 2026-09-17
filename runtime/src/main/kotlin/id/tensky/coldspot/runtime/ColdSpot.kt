package id.tensky.coldspot.runtime

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import id.tensky.coldspot.manifest.MergedManifest
import id.tensky.coldspot.manifest.ShippedClass
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataStore
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * ColdSpot inside the running app. Installed by [ColdSpotProvider] before `Application.onCreate`; from then on
 * it saves coverage (every 10 s while an activity is started, when the app goes to the background, on memory
 * pressure and on a crash), answers [analyze], and shows its bubble in the app's activities. Everything that
 * touches disk or JaCoCo runs on one background thread; results come back on the main thread. Without JaCoCo's
 * agent in the APK, a build without ColdSpot, it logs once and stays idle: no bubble, and [open] shows a screen
 * that says so. Nothing it does crashes the app (Guard.kt): a startup that fails leaves it idle the same way, and
 * its screen says why.
 */
public object ColdSpot {
    internal const val TAG = "ColdSpot"

    @Volatile
    private var runtime: Runtime? = null

    /** Why [install] failed, when it did: what the screen says instead of "no agent". */
    @Volatile
    private var startupError: String? = null

    /** Whether the agent was found and the runtime is collecting. */
    public val isActive: Boolean get() = runtime != null

    /** A clean start: the agent's probes cleared, every saved coverage file deleted, the baseline forgotten. */
    public fun reset() {
        guarded("resetting") { runtime?.reset() ?: Log.i(TAG, "reset asked, but ColdSpot is not running") }
    }

    /**
     * Saves, analyses off the main thread, and hands [callback] the [Report] on the main thread. A failure is a report
     * too, one with nothing but errors in it; [callback] is the caller's own code, and what it throws is its own.
     */
    public fun analyze(callback: (Report) -> Unit) {
        val running = runtime
        if (running == null) {
            val why = startupError?.let { "ColdSpot could not start: $it" } ?: "ColdSpot is not running: no JaCoCo agent in this build"
            callback(failedReport(listOf(why) + Problems.all(), null))
            return
        }
        val failed = logged("starting the analysis", { e -> failedReport(listOf("The analysis failed: ${describe(e)}") + Problems.all(), null) }) {
            running.analyze(callback)
            null
        }
        if (failed != null) callback(failed)
    }

    /** Analyses and logs the debug dump (`EXEC`, `CLASS`, `LINE`, `DONE` lines) that `checkDeviceRun` reads. */
    public fun dump() {
        guarded("the dump") { runtime?.dump() ?: Log.i(TAG, "dump asked, but ColdSpot is not running") }
    }

    /**
     * Opens ColdSpot's screen: for a team's own debug menu, and what the bubble and the launcher icon do. From an
     * activity it opens on top of it, and back returns there; from any other context it opens as a task of its own.
     */
    public fun open(context: Context) {
        guarded("opening ColdSpot's screen") {
            val intent = Intent(context, ColdSpotActivity::class.java)
            if (activityOf(context) == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Shows or hides the bubble in every activity, from any thread. It overrides the build's `coldSpot { bubble }`
     * and is remembered across launches, until the next call, the `HIDE_BUBBLE` / `SHOW_BUBBLE` broadcast, the switch
     * on ColdSpot's screen, or the next install of a build, which starts from what that build says. Showing also
     * ends a "Hide until restart".
     */
    public fun setBubbleVisible(visible: Boolean) {
        setBubbleVisible(visible, null)
    }

    internal fun setBubbleVisible(visible: Boolean, done: Runnable?) {
        val running = runtime
        if (running == null) {
            Log.i(TAG, "bubble ${if (visible) "shown" else "hidden"} asked, but ColdSpot is not running")
            done?.run()
            return
        }
        running.bubble.setVisible(visible, done)
    }

    /** The bubble, for ColdSpot's own screen; null while ColdSpot is not running. */
    internal val bubble: Bubble? get() = runtime?.bubble

    /** The report of the analysis that ran last, for the file screen to show a file of; null before the first. Main thread. */
    internal var lastReport: Report? = null

    /**
     * When the package was installed last, epoch millis: "installed at" on the screens, and what stands in for a
     * build time, which the manifest leaves out on purpose.
     */
    internal fun installedAt(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    }

    private tailrec fun activityOf(context: Context?): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> activityOf(context.baseContext)
        else -> null
    }

    internal fun install(context: Context) {
        if (runtime != null) return
        val app = context.applicationContext as? Application
        if (app == null) {
            Log.w(TAG, "no Application context; ColdSpot stays idle")
            return
        }
        // Idle, as without an agent, and the screen says why. Whatever start() registered before it failed stays
        // registered, and guarded.
        logged("starting ColdSpot", { e -> startupError = describe(e) }) {
            val agent = RtAgent.find()
            if (agent == null) {
                Log.i(TAG, "no JaCoCo agent in this build; ColdSpot stays idle")
                return
            }
            runtime = Runtime(app, agent).also { it.start() }
        }
    }
}

/** The running instance: one per process. */
internal class Runtime(private val app: Application, private val agent: Agent) {
    private val dir = File(app.noBackupFilesDir, "coldspot")
    private val store = CoverageStore(dir, processName(app))
    private val gate = ResetGate(File(dir, "reset-token"))
    private val assets = Assets(app)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ColdSpot").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    val bubble = Bubble(app, executor, main)

    @Volatile
    private var manifest: MergedManifest? = null

    @Volatile
    private var shippedIds: Set<Long>? = null

    @Volatile
    private var shippedNames: Set<String>? = null

    /** Why the last save failed, until one succeeds: the overview says so ([Report.saveError]). */
    @Volatile
    private var saveFailure: String? = null

    /** A save that keeps failing, as one whose file cannot be read does every ten seconds: logged in full once. */
    private val saveFailures = RecurringFailure()
    private var startedActivities = 0
    private val periodicSave = object : Runnable {
        override fun run() {
            guarded("the periodic save") {
                if (startedActivities > 0) {
                    save("periodic")
                    main.postDelayed(this, SAVE_INTERVAL_MS)
                }
            }
        }
    }

    /** Registers the listeners on the main thread and does everything else in the background: no disk I/O here. */
    fun start() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                guarded("the periodic save") { if (startedActivities++ == 0) main.postDelayed(periodicSave, SAVE_INTERVAL_MS) }
            }

            override fun onActivityStopped(activity: Activity) {
                guarded("saving as the app goes to the background") {
                    if (--startedActivities == 0) {
                        main.removeCallbacks(periodicSave)
                        save("background")
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = guarded("saving on memory pressure") { save("trim memory $level") }
            override fun onLowMemory() = guarded("saving on memory pressure") { save("low memory") }
            override fun onConfigurationChanged(newConfig: Configuration) {}
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // On the crashing thread, and possibly before the background thread has loaded the saved file: the save
            // loads it first, here, or waits for the load under way (CoverageStore). Whatever it throws, a
            // VirtualMachineError too, the app's own handler gets the app's crash next: the process is ending anyway.
            try {
                Log.w(ColdSpot.TAG, "crash in thread ${thread.name}: saving the coverage first")
                saveNow("crash")
            } catch (e: Throwable) {
                Log.w(ColdSpot.TAG, "could not save on crash", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
        bubble.start()
        execute("loading the saved coverage and the build's manifests") {
            try {
                val started = SystemClock.elapsedRealtime()
                if (store.load()) Log.i(ColdSpot.TAG, "saved coverage loaded: ${SystemClock.elapsedRealtime() - started} ms")
            } catch (e: IOException) {
                // The file stays as it is; every save fails until it can be read, or until a reset deletes it.
                Log.w(ColdSpot.TAG, "the saved coverage cannot be read; nothing is saved until it can be", e)
            }
            val loaded = loadManifest()
            val token = loaded?.let { assets.sharedManifest()?.resetToken }
            if (gate.shouldReset(token)) {
                // Only what was saved before this build goes; the probes this launch has fired so far are the fresh
                // session's own and stay (the app has been running while this thread caught up).
                Log.i(ColdSpot.TAG, "fresh-session build: saved coverage wiped once")
                store.deleteAll()
                gate.remember(token!!)
            }
            Log.i(ColdSpot.TAG, "collecting; ${shippedIds?.size ?: 0} shipped classes, since ${store.collectingSince()}")
        }
    }

    /**
     * [block] on the background thread, [guarded]: an exception that left a task there would end the thread, and
     * with it the app, as any thread's uncaught exception does on Android.
     */
    private fun execute(what: String, block: () -> Unit) {
        executor.execute { guarded(what, block) }
    }

    fun reset() {
        execute("resetting") {
            agent.reset()
            store.deleteAll()
            Log.i(ColdSpot.TAG, "reset: probes cleared, saved coverage deleted")
        }
    }

    /** The report of a failed analysis is the error state; not a problem to list besides ([logged], not [guarded]). */
    fun analyze(callback: (Report) -> Unit) {
        execute("the analysis") {
            val report = logged("the analysis", { e -> failedReport(listOf("The analysis failed: ${describe(e)}") + Problems.all(), collectingSince()) }) { analyzeNow() }
            main.post {
                ColdSpot.lastReport = report
                callback(report)
            }
        }
    }

    /** The dump's report is the one the overview would show, a failed analysis's too: `ERROR` lines, then `DONE`. */
    fun dump() {
        execute("the dump") {
            val report = logged("the analysis", { e -> failedReport(listOf("The analysis failed: ${describe(e)}") + Problems.all(), collectingSince()) }) { dumpNow() }
            report?.saveError?.let { Log.w(ColdSpot.TAG, "ERROR Coverage can't be saved: $it") }
            for (cls in report?.classes.orEmpty()) {
                Log.i(ColdSpot.TAG, "CLASS ${cls.shipped.name} module=${cls.shipped.module} id=${java.lang.Long.toHexString(cls.id)} noMatch=${cls.noMatch} lines=${cls.firstLine}..${cls.lastLine}")
                for ((line, counts) in cls.lines) Log.i(ColdSpot.TAG, "LINE ${cls.shipped.name} $line ${counts.first}/${counts.second}")
            }
            for (error in report?.errors.orEmpty()) Log.w(ColdSpot.TAG, "ERROR $error")
            for (stale in report?.staleClasses.orEmpty()) Log.w(ColdSpot.TAG, "STALE ${stale.module}/${stale.name}: the shipped class is not the class that ran")
            Log.i(ColdSpot.TAG, "SINCE ${report?.collectingSince ?: collectingSince() ?: "none"}")
            Log.i(ColdSpot.TAG, "DONE ${report?.classes?.size ?: 0} classes, ${report?.analysisMillis ?: 0} ms")
        }
    }

    /** The `EXEC` lines, and the report to log after them; null without a manifest to analyse against. */
    private fun dumpNow(): Report? {
        val manifest = loadManifest()
        val data = savedAndStale("dump")
        val bytes = ByteArrayOutputStream().also { out -> data.accept(ExecutionDataWriter(out)) }.toByteArray()
        val chunks = Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(3_000)
        chunks.forEachIndexed { i, chunk -> Log.i(ColdSpot.TAG, "EXEC ${i + 1}/${chunks.size} $chunk") }
        return if (manifest != null) analyzed(manifest, data) else null
    }

    private fun analyzeNow(): Report {
        val manifest = loadManifest()
            ?: return failedReport(listOf("no coldspot/manifest.json in the APK's assets: this is not a ColdSpot coverage build") + Problems.all(), collectingSince())
        return analyzed(manifest, savedAndStale("analysis"))
    }

    /** The analysis of [data], with what went wrong besides: the last save's failure, and the [Problems] caught so far. */
    private fun analyzed(manifest: MergedManifest, data: ExecutionDataStore): Report {
        val report = analyze(manifest, data, assets::classBytes, store.collectingSince())
        return report.copy(errors = report.errors + Problems.all(), saveError = saveFailure)
    }

    /** For a report that failed: the store may be what failed. */
    private fun collectingSince(): Long? = catching({ store.collectingSince() }) { null }

    /**
     * What an analysis works from: [CoverageStore.saveForAnalysis], which saves and adds what this process ran under a
     * shipped class's name and another id. Without a manifest there is nothing to analyse, and this is an ordinary save.
     */
    private fun savedAndStale(reason: String): ExecutionDataStore {
        val ids = shippedIds
        val names = shippedNames
        if (ids == null || names == null) return saveNow(reason)
        val agentData = agent.executionData()
        return try {
            store.saveForAnalysis(agentData, ids, names).also { data ->
                saved()
                Log.d(ColdSpot.TAG, "saved on $reason: ${data.contents.size} classes for the analysis")
            }
        } catch (e: IOException) {
            // Shown all the same, from what the process has, and said: an overview that fails because a file does is none.
            saveFailed("saving on $reason, for an analysis of what the app has, unsaved", e)
            store.unsavedForAnalysis(agentData, ids, names)
        }
    }

    /** A failed save is said by the save banner ([saveFailure]), which the next save that succeeds takes back: logged, not [guarded]. */
    private fun save(reason: String) {
        execute("saving on $reason") {
            catching<Unit>({ saveNow(reason) }) { e -> saveFailed("saving on $reason", e) }
        }
    }

    /**
     * [saveFailure] for the banner, and the log: the stack trace the first time a save fails this way, a line each time
     * it does again. The banner is there either way, until a save succeeds ([saved]).
     */
    private fun saveFailed(what: String, e: Throwable) {
        val reason = if (e is IOException) e.message ?: e.javaClass.simpleName else describe(e)
        saveFailure = reason
        if (saveFailures.isNew(reason)) {
            Log.e(ColdSpot.TAG, "$what failed: $reason; the app goes on without it, and a save that fails the same way is logged in one line from now on", e)
        } else {
            Log.w(ColdSpot.TAG, "$what failed again: $reason")
        }
    }

    private fun saved() {
        saveFailure = null
        saveFailures.reset()
    }

    /**
     * Baseline plus the agent's data, written now, on the calling thread; the shipped-class filter once it is known.
     * The store loads the baseline first when nothing has yet, so this is safe from the first moment on, a crash included.
     */
    private fun saveNow(reason: String): ExecutionDataStore {
        val ids = shippedIds
        val kept = catching({ store.save(agent.executionData()) { id -> ids == null || id in ids } }) { e ->
            saveFailure = if (e is IOException) e.message ?: e.javaClass.simpleName else describe(e)
            throw e
        }
        saved()
        Log.d(ColdSpot.TAG, "saved on $reason: ${kept.contents.size} classes")
        return kept
    }

    /** The manifests, read from the assets once, and the shipped classes' ids with them. */
    private fun loadManifest(): MergedManifest? {
        manifest?.let { return it }
        val merged = assets.mergedManifest() ?: return null
        val ids = HashSet<Long>()
        val names = HashSet<String>()
        for (file in merged.files) for (shipped in file.classes) {
            names += shipped.name
            val bytes = assets.classBytes(shipped) ?: continue
            classId(bytes, shipped.name)?.let(ids::add)
        }
        shippedIds = ids
        shippedNames = names
        manifest = merged
        return merged
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 10_000L

        fun processName(app: Application): String =
            if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else try {
                File("/proc/self/cmdline").readText().trim { it == '\u0000' || it.isWhitespace() }
            } catch (e: Exception) {
                app.packageName
            }
    }
}

/** A report with nothing in it but [errors]: ColdSpot not running, no manifest, or an analysis that failed. */
internal fun failedReport(errors: List<String>, collectingSince: Long?): Report =
    Report(emptyList(), emptyMap(), emptyMap(), collectingSince, emptyList(), errors, jacocoMismatch = false, classes = emptyList(), analysisMillis = 0)

/** The bundle in the APK's assets: the shared manifest, every module's, and the shipped class files. */
internal class Assets(private val context: Context) {
    private val assets get() = context.assets

    fun sharedManifest() = try {
        assets.open("$ROOT/manifest.json").use { id.tensky.coldspot.manifest.ManifestJson.read(it.readBytes().decodeToString()) }
    } catch (e: java.io.IOException) {
        null
    }

    fun mergedManifest(): MergedManifest? {
        val shared = sharedManifest() ?: return null
        val modules = modulesUnder(ROOT, "").map { module ->
            assets.open("$ROOT/$module/manifest.json").use { id.tensky.coldspot.manifest.ManifestJson.readModule(it.readBytes().decodeToString()) }
        }
        return id.tensky.coldspot.manifest.merge(shared, modules)
    }

    fun classBytes(shipped: ShippedClass): ByteArray? = try {
        assets.open("$ROOT/${shipped.module}/classes/${shipped.name}.class").use { it.readBytes() }
    } catch (e: java.io.IOException) {
        null
    }

    /** Module folders under [dir], however deep: those holding a module manifest; the shared manifest at the root is not one. */
    private fun modulesUnder(dir: String, prefix: String): List<String> {
        val names = assets.list(dir).orEmpty()
        if (prefix.isNotEmpty() && "manifest.json" in names) return listOf(prefix.removeSuffix("/"))
        return names.filter { '.' !in it }.flatMap { modulesUnder("$dir/$it", "$prefix$it/") }
    }

    private companion object {
        const val ROOT = "coldspot"
    }
}
