package id.tensky.coldspot.plugin.tools

import org.jacoco.core.analysis.Analyzer
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.data.ExecutionDataReader
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.TreeMap
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Re-runs the device's analysis on the laptop and diffs the two. The Logcat dump holds what the sample's
 * CoverageDebug logged: the agent's execution data (`EXEC i/n <base64>` chunks) and one `LINE <class> <line>
 * <covered>/<total>` row per executable line, plus `CLASS ... noMatch=<bool>`. The APK holds the shipped bytes
 * under `assets/coldspot/<module>/classes/`. Same JaCoCo, same bytes, same execution data: the rows must match.
 *
 *     ./gradlew :tooling:plugin:checkDeviceRun -q -Plogcat=<dump> -Papk=<apk>
 *
 * Prints every difference and exits 1 if there is one.
 */
public fun main(args: Array<String>) {
    val logcat = File(args[0]).readLines()
    val apk = File(args[1])

    val exec = executionData(logcat)
    val store = ExecutionDataStore()
    ExecutionDataReader(ByteArrayInputStream(exec)).apply {
        setExecutionDataVisitor(store)
        setSessionInfoVisitor(SessionInfoStore())
        read()
    }

    val laptopLines = TreeMap<String, String>()
    val laptopNoMatch = TreeMap<String, Boolean>()
    ZipFile(apk).use { zip ->
        for (entry in zip.entries().asSequence().filter { it.name.startsWith("assets/coldspot/") && it.name.endsWith(".class") }) {
            val builder = CoverageBuilder()
            Analyzer(store, builder).analyzeClass(zip.getInputStream(entry).use { it.readBytes() }, entry.name)
            for (cls in builder.classes) {
                // As the runtime does (FINDINGS.md Finding 5): a class with no line information, such as a companion
                // object whose one method JaCoCo filters, is skipped altogether, its noMatch included.
                if (cls.firstLine < 0) continue
                laptopNoMatch[cls.name] = cls.isNoMatch
                for (line in cls.firstLine..cls.lastLine) {
                    val counter = cls.getLine(line).instructionCounter
                    if (counter.totalCount > 0) laptopLines["${cls.name} $line"] = "${counter.coveredCount}/${counter.totalCount}"
                }
            }
        }
    }

    val deviceLines = TreeMap<String, String>()
    val deviceNoMatch = TreeMap<String, Boolean>()
    for (line in logcat) {
        LINE_ROW.find(line)?.let { deviceLines["${it.groupValues[1]} ${it.groupValues[2]}"] = it.groupValues[3] }
        CLASS_ROW.find(line)?.let { deviceNoMatch[it.groupValues[1]] = it.groupValues[2].toBoolean() }
    }

    var differences = 0
    for (key in (laptopLines.keys + deviceLines.keys).toSortedSet()) {
        val laptop = laptopLines[key]
        val device = deviceLines[key]
        if (laptop != device) {
            differences++
            println("$key: device ${device ?: "-"}, laptop ${laptop ?: "-"}")
        }
    }
    for (key in (laptopNoMatch.keys + deviceNoMatch.keys).toSortedSet()) {
        if (laptopNoMatch[key] != deviceNoMatch[key]) {
            differences++
            println("$key: noMatch device ${deviceNoMatch[key] ?: "-"}, laptop ${laptopNoMatch[key] ?: "-"}")
        }
    }
    println(
        "${exec.size} bytes of execution data, ${laptopNoMatch.size} classes on the laptop / ${deviceNoMatch.size} on the device, " +
            "${laptopLines.size} lines / ${deviceLines.size}: ${if (differences == 0) "identical" else "$differences differences"}",
    )
    if (differences > 0) exitProcess(1)
}

/** The `EXEC i/n <chunk>` lines joined in order and decoded. */
private fun executionData(logcat: List<String>): ByteArray {
    val chunks = TreeMap<Int, String>()
    var expected = 0
    for (line in logcat) {
        val match = EXEC_ROW.find(line) ?: continue
        chunks[match.groupValues[1].toInt()] = match.groupValues[3]
        expected = match.groupValues[2].toInt()
    }
    require(chunks.isNotEmpty()) { "no EXEC lines in the dump" }
    require(chunks.size == expected) { "found ${chunks.size} of $expected EXEC chunks" }
    return Base64.getDecoder().decode(chunks.values.joinToString(""))
}

private val EXEC_ROW = Regex("""ColdSpot.*\bEXEC (\d+)/(\d+) (\S+)""")
private val LINE_ROW = Regex("""ColdSpot.*\bLINE (\S+) (\d+) (\d+/\d+)""")
private val CLASS_ROW = Regex("""ColdSpot.*\bCLASS (\S+) .*noMatch=(true|false)""")
