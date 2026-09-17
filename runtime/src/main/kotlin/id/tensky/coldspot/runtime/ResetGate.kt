package id.tensky.coldspot.runtime

import java.io.File

/**
 * `-Pcoldspot.freshSession`: the build writes a token into the shared manifest, and the first launch that sees
 * it wipes the saved coverage, once. The token seen last is kept in [file]; a build without a token, the normal
 * case, never wipes, and the token it does not carry stays remembered for the next fresh-session build.
 */
public class ResetGate(private val file: File) {
    /** Whether [token] asks for a wipe: present, and not the one wiped for already. */
    public fun shouldReset(token: String?): Boolean = token != null && token != lastSeen()

    /** Remembers [token] as wiped for, so that the next launch of the same build keeps its coverage. */
    public fun remember(token: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(token)
        temp.renameTo(file)
    }

    private fun lastSeen(): String? = if (file.isFile) file.readText() else null
}
