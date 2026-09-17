package id.tensky.coldspot.manifest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real manifests, as kotlinx-serialization wrote them for the sample app before the switch
 * (`src/test/resources/golden`, listed in `index.txt`): read → write must give the very same text, and
 * read → write → read the same manifest. What the shared manifests embed is the changed files' full text, so
 * this covers what real Kotlin sources throw at the codec.
 */
class GoldenManifestTest {
    private val paths = resource("index.txt").lines().filter { it.isNotBlank() }

    @Test
    fun `the golden set is what it should be`() {
        // then there is the sample's shared manifest, and the module manifest of each of its two modules
        assertTrue("sample/manifest.json" in paths, "no shared manifest among $paths")
        assertTrue("sample/app/manifest.json" in paths && "sample/feature/manifest.json" in paths, "no module manifests among $paths")
    }

    @Test
    fun `every shared manifest reads, and writes back byte for byte`() {
        for (path in paths.filter { it.endsWith("/manifest.json") && it.count { c -> c == '/' } == 1 }) {
            // given
            val text = resource(path)

            // when
            val manifest = ManifestJson.read(text)

            // then
            assertEquals(text, ManifestJson.write(manifest), path)
            assertEquals(manifest, ManifestJson.read(ManifestJson.write(manifest)), path)
            assertEquals(Manifest.SCHEMA_VERSION, manifest.schemaVersion, path)
        }
    }

    @Test
    fun `every module manifest reads, and writes back byte for byte`() {
        val modules = paths.filter { it.count { c -> c == '/' } > 1 }
        assertTrue(modules.size >= 2, "only ${modules.size} module manifests")
        for (path in modules) {
            // given
            val text = resource(path)

            // when
            val manifest = ManifestJson.readModule(text)

            // then
            assertEquals(text, ManifestJson.write(manifest), path)
            assertEquals(manifest, ManifestJson.readModule(ManifestJson.write(manifest)), path)
            assertEquals(path.removePrefix("sample/").removeSuffix("/manifest.json"), manifest.module, path)
        }
    }

    @Test
    fun `the shared manifests hold text worth the escaping`() {
        // then the sample's embedded sources carry quotes, backslashes, dollar signs and non-ASCII characters
        val sample = ManifestJson.read(resource("sample/manifest.json"))
        val texts = sample.files.joinToString("\n") { it.text }
        assertTrue('"' in texts && '\\' in texts && '$' in texts, "no quotes, backslashes or dollars in the sample's sources")
        assertTrue(texts.any { it.code > 0x7f }, "no non-ASCII character in the sample's sources")
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream("/golden/$path")) { "no golden resource $path" }.use { it.readBytes().decodeToString() }
}
