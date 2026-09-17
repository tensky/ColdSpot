package id.tensky.coldspot.runtime

import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataReader
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataStore
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataWriter
import id.tensky.coldspot.shaded.org.jacoco.core.data.SessionInfo
import id.tensky.coldspot.shaded.org.jacoco.core.data.SessionInfoStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Coverage kept across launches and builds (DECISIONS.md "Coverage across builds"): one file per process, in
 * JaCoCo's exec format, under [dir]. What was saved before this launch is the baseline, read from the file once, by
 * [load] or by whichever [save] comes first; every save writes baseline plus the agent's current data, merged by class
 * id, so that nothing a previous launch saw is lost and nothing is counted twice.
 *
 * No save writes before the baseline is read. A crash saves on the crashing thread, and can do so before the
 * background thread has loaded the file: written then, the file would hold this launch alone. Everything that reads
 * or writes the file holds the store's monitor, so a save waits for a load or another save under way on another
 * thread, and loads the file itself when nothing has. A file that cannot be read is never written over: the load
 * throws, and so does every save, until it can be read. A file that is not coverage data, or is cut short, is moved
 * aside to `.unreadable`, and what could be read of it is the baseline.
 *
 * Carry-forward by class id: JaCoCo's id is a hash of the class bytes, so an unchanged class keeps its coverage
 * across rebuilds and a changed class, with a new id, starts fresh of itself. Entries whose id no shipped class
 * of this build has are dropped at save, which is how the file stays the size of the change. An analysis gets its
 * data from [saveForAnalysis] and from nothing else, the baseline least of all: see there why.
 *
 * Writes go to a temporary file first and are renamed into place, so a crash mid-write leaves the previous file.
 */
public class CoverageStore(private val dir: File, processName: String) {
    private val file = File(dir, "cov-${processName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.exec")
    private val unreadable = File(dir, "${file.name}.unreadable")

    // The three below are the store's monitor's.
    private var baseline = ExecutionDataStore()
    private var sessions = SessionInfoStore()
    private var loaded = false

    /**
     * Reads this process's file into the baseline, unless something has already: true when this call read it. No
     * file is an empty baseline. A file that cannot be read throws and leaves the store unloaded, the file as it is:
     * the next load or save tries again.
     */
    @Throws(IOException::class)
    public fun load(): Boolean {
        synchronized(this) {
            if (loaded) return false
            val store = ExecutionDataStore()
            val infos = SessionInfoStore()
            if (file.isFile) {
                val bytes = try {
                    file.readBytes()
                } catch (e: IOException) {
                    throw IOException("the saved coverage cannot be read (${reason(e)})", e)
                }
                try {
                    ExecutionDataReader(ByteArrayInputStream(bytes)).apply {
                        setExecutionDataVisitor(store)
                        setSessionInfoVisitor(infos)
                        read()
                    }
                } catch (e: Exception) {
                    // Not coverage data, or cut short (the reader throws IOException, and on some garbage a runtime
                    // exception, such as a negative array size): what was read before the fault stays, and the file
                    // goes aside rather than under the next save.
                    if (!file.renameTo(unreadable)) throw IOException("cannot move the unreadable $file aside", e)
                }
            }
            baseline = store
            sessions = infos
            loaded = true
            return true
        }
    }

    /**
     * Writes baseline plus [agentData] (the agent's `executionData`), merged by id, keeping only classes [shipped]
     * says this build has. Loads the baseline first, on the calling thread, if nothing has loaded it yet; throws,
     * writing nothing, if it cannot be read. Returns the store that was written.
     */
    @Throws(IOException::class)
    public fun save(agentData: ByteArray, shipped: (Long) -> Boolean): ExecutionDataStore = synchronized(this) {
        load()
        val merged = ExecutionDataStore()
        baseline.accept(merged)
        val known = LinkedHashMap<String, SessionInfo>()
        for (info in sessions.infos) known[info.id] = info
        val fromAgent = SessionInfoStore()
        ExecutionDataReader(ByteArrayInputStream(agentData)).apply {
            setExecutionDataVisitor(merged)
            setSessionInfoVisitor(fromAgent)
            read()
        }
        // The agent reports its one session on every save: recorded once, so that the file does not grow with saves.
        for (info in fromAgent.infos) if (info.id !in known) known[info.id] = info // not Map.putIfAbsent: API 24
        val kept = ExecutionDataStore()
        for (data in merged.contents) if (shipped(data.id)) kept.put(data)

        dir.mkdirs()
        val temp = File(dir, "${file.name}.tmp")
        try {
            temp.outputStream().buffered().use { output ->
                val writer = ExecutionDataWriter(output)
                for (info in known.values) writer.visitSessionInfo(info)
                kept.accept(writer)
                output.flush()
            }
        } catch (e: IOException) {
            throw IOException("the saved coverage cannot be written (${reason(e)})", e)
        }
        if (!temp.renameTo(file)) throw IOException("the saved coverage cannot be written (cannot move $temp over $file)")
        sessions = SessionInfoStore().also { store -> known.values.forEach(store::visitSessionInfo) }
        kept
    }

    /**
     * What an analysis works from, this build's shipped classes known: [save]d, which keeps [shippedIds] alone and so
     * drops whatever an earlier build left under an id no shipped class has; plus what [agentData], this process's
     * agent, ran under a shipped class's name and another id, which makes that class stale ([withStaleClasses]).
     * Nothing loaded from the file can make a class stale: a class changed since the last build, and not run since,
     * is "not executed", and only this process running bytes that are not the shipped ones is an error.
     */
    @Throws(IOException::class)
    public fun saveForAnalysis(agentData: ByteArray, shippedIds: Set<Long>, shippedNames: Set<String>): ExecutionDataStore {
        val kept = save(agentData) { it in shippedIds }
        return withStaleClasses(kept, executionDataOf(agentData), shippedIds, shippedNames)
    }

    /** What went wrong, for the overview: the file by its name, not its whole path. */
    private fun reason(e: IOException): String = (e.message ?: e.javaClass.simpleName).replace(file.path, file.name).replace(dir.path, dir.name)

    /**
     * What an analysis works from when [saveForAnalysis] fails: the same data, written nowhere. The baseline is in it
     * when it could be loaded, and missing when it could not, the file being unreadable; what this process ran is
     * always in it, and makes a class stale exactly as there.
     */
    public fun unsavedForAnalysis(agentData: ByteArray, shippedIds: Set<Long>, shippedNames: Set<String>): ExecutionDataStore {
        val ranHere = executionDataOf(agentData)
        val merged = ExecutionDataStore()
        synchronized(this) { if (loaded) baseline.accept(merged) }
        ranHere.accept(merged)
        val kept = ExecutionDataStore()
        for (data in merged.contents) if (data.id in shippedIds) kept.put(data)
        return withStaleClasses(kept, ranHere, shippedIds, shippedNames)
    }

    /**
     * The baseline as loaded, empty before a load: what earlier launches saved, ids no shipped class has any more
     * included. Never for an analysis: an old id under a shipped class's name would make that class stale. See
     * [saveForAnalysis].
     */
    public fun baseline(): ExecutionDataStore = synchronized(this) { ExecutionDataStore().also { baseline.accept(it) } }

    /** The earliest session start on record, the moment "collecting since"; null before anything was saved or ran. */
    public fun collectingSince(): Long? = synchronized(this) { sessions.infos.minOfOrNull { it.startTimeStamp } }

    /**
     * Deletes every coverage file of every process, one set aside as unreadable included, and forgets the baseline: a
     * clean start, from which a save starts with nothing to load.
     */
    public fun deleteAll() {
        synchronized(this) {
            dir.listFiles()?.filter { it.name.startsWith("cov-") }?.forEach { it.delete() }
            baseline = ExecutionDataStore()
            sessions = SessionInfoStore()
            loaded = true
        }
    }
}

/** The execution data in [bytes], JaCoCo's exec format; the sessions are left out. */
internal fun executionDataOf(bytes: ByteArray): ExecutionDataStore = ExecutionDataStore().also { store ->
    ExecutionDataReader(ByteArrayInputStream(bytes)).apply {
        setExecutionDataVisitor(store)
        setSessionInfoVisitor(SessionInfoStore())
        read()
    }
}
