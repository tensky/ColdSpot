package id.tensky.coldspot.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ColdSpot's runtime for the TestKit fixtures. The plugin adds `io.github.tensky.coldspot:runtime` at its own
 * version to an application's coverage variant, so every fixture that resolves that variant needs the module to
 * exist; the runtime itself is a project of the main build, which this build cannot depend on. This is its
 * stand-in: a Maven repository in a folder, holding an AAR packed from the runtime's real manifest and real
 * resources (`coldspot.test.runtimeMain`, set by the build) and no classes. What the tests then see of the
 * launcher alias and the bubble's meta-data is what the runtime's own manifest says.
 */
internal object RuntimeStub {
    const val GROUP = "io.github.tensky.coldspot"
    const val NAME = "runtime"
    private const val NAMESPACE = "id.tensky.coldspot.runtime"

    /** The plugin's version, which is the runtime's. */
    val version: String get() = System.getProperty("coldspot.test.version")

    /** `io.github.tensky.coldspot:runtime:<version>`, as dependency reports print it. */
    val coordinates: String get() = "$GROUP:$NAME:$version"

    /** [settings] with its `@RUNTIME_REPO@` pointing at a repository made in [dir]. */
    fun inSettings(settings: String, dir: File): String {
        check("@RUNTIME_REPO@" in settings) { "the fixture's settings have no @RUNTIME_REPO@ to fill in" }
        return settings.replace("@RUNTIME_REPO@", repository(dir).toURI().toString())
    }

    /** Makes the repository in [dir] and returns it. */
    fun repository(dir: File): File {
        val main = File(System.getProperty("coldspot.test.runtimeMain"))
        val manifest = File(main, "AndroidManifest.xml")
        check(manifest.isFile) { "no runtime manifest at $manifest" }
        val module = File(dir, "${GROUP.replace('.', '/')}/$NAME/$version").apply { mkdirs() }
        File(module, "$NAME-$version.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$GROUP</groupId>
              <artifactId>$NAME</artifactId>
              <version>$version</version>
              <packaging>aar</packaging>
            </project>
            """.trimIndent() + "\n",
        )
        ZipOutputStream(File(module, "$NAME-$version.aar").outputStream()).use { aar ->
            aar.entry("AndroidManifest.xml", packaged(manifest.readText()).toByteArray())
            aar.entry("classes.jar", emptyJar())
            aar.entry("R.txt", ByteArray(0))
            val res = File(main, "res")
            res.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { aar.entry("res/${it.relativeTo(res).invariantSeparatorsPath}", it.readBytes()) }
        }
        return dir
    }

    /** The source manifest as AGP packs it into an AAR: with the library's namespace as its package, and its minSdk. */
    private fun packaged(source: String): String {
        val root = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">"
        check(source.split(root).size == 2) { "the runtime's manifest no longer opens with $root" }
        return source.replace(root, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$NAMESPACE\">\n    <uses-sdk android:minSdkVersion=\"21\" />")
    }

    private fun emptyJar(): ByteArray = java.io.ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
