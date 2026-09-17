package id.tensky.coldspot.bundle

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Class files by VM name from a compile classpath: directories and jars, searched in order, the first hit
 * winning as a compiler's would. Jars are opened on the first lookup that reaches them and kept open until
 * [close], so that the many lookups of one bundle open each jar once. Every lookup is remembered, misses
 * included. An entry that is neither a directory nor a zip holds no classes.
 */
internal class ClassLookup(entries: List<File>) : Closeable {
    private val entries = entries.filter { it.exists() }
    private val jars = HashMap<File, ZipFile?>()
    private val found = HashMap<String, ByteArray?>()

    /** The bytes of the class called [vmName] (`com/acme/Thing`), or null when the classpath has none. */
    fun find(vmName: String): ByteArray? {
        if (vmName in found) return found[vmName]
        return locate(vmName).also { found[vmName] = it }
    }

    private fun locate(vmName: String): ByteArray? {
        val path = "$vmName.class"
        for (entry in entries) {
            if (entry.isDirectory) {
                val file = File(entry, path)
                if (file.isFile) return file.readBytes()
            } else {
                val zip = zipOf(entry) ?: continue
                val zipEntry = zip.getEntry(path) ?: continue
                return zip.getInputStream(zipEntry).use { it.readBytes() }
            }
        }
        return null
    }

    private fun zipOf(file: File): ZipFile? {
        if (file in jars) return jars[file]
        val zip = try {
            ZipFile(file)
        } catch (e: IOException) {
            null
        }
        jars[file] = zip
        return zip
    }

    override fun close() {
        jars.values.forEach { it?.close() }
        jars.clear()
    }
}
