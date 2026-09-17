package id.tensky.coldspot.manifest

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The codec on its own: what it writes for every kind of character, that reading gives back what was written,
 * what it says about text it refuses, and what it ignores. Every test reads as **given** a manifest or a text,
 * **when** written or read, **then** this comes out.
 */
class ManifestJsonTest {

    @Test
    fun `every class of character survives a write and a read, as text and as path`() {
        // given source text with quotes, backslashes, every control character, tabs, newlines, DEL, Latin, CJK, emoji and a lone surrogate
        val nasty = buildString {
            append("quote\" backslash\\ slash/ tab\t newline\n cr\r bs\b ff\u000c ")
            for (c in 0..0x1f) append(c.toChar())
            append(" del\u007f é ✓ 中文 😀 \uD83D\uDE00 \u2028 \u2029 lone\uD83Dsurrogate \uFEFF end")
        }
        val manifest = manifest(files = listOf(Manifest.ChangedFile(nasty, listOf(1, 2), nasty)))

        // when
        val text = ManifestJson.write(manifest)
        val back = ManifestJson.read(text)

        // then
        assertEquals(manifest, back)
    }

    @Test
    fun `random strings over the whole of Unicode, surrogate pairs included, survive a write and a read`() {
        // given 300 strings of random code points, including supplementary ones, and every ASCII control character often
        val random = Random(20260927)
        repeat(300) {
            val s = buildString {
                repeat(random.nextInt(0, 40)) {
                    val codePoint = when (random.nextInt(4)) {
                        0 -> random.nextInt(0, 0x20) // controls
                        1 -> random.nextInt(0x20, 0x80) // ASCII, quotes and backslashes among them
                        2 -> random.nextInt(0x80, 0xD800) // the BMP below the surrogates
                        else -> random.nextInt(0x10000, 0x10FFFF + 1) // supplementary: surrogate pairs
                    }
                    appendCodePoint(codePoint)
                }
            }
            val manifest = manifest(files = listOf(Manifest.ChangedFile(s, emptyList(), s)), commit = s)

            // when
            val back = ManifestJson.read(ManifestJson.write(manifest))

            // then
            assertEquals(manifest, back, "round trip of ${s.map { it.code.toString(16) }}")
        }
    }

    @Test
    fun `strings are escaped the way kotlinx-serialization escaped them, and no more`() {
        // given the characters with a short escape, the other controls, and a few that need none
        val text = "q\" b\\ n\n r\r t\t bs\b ff\u000c nul\u0000 us\u001f del\u007f é 😀 /"
        val manifest = manifest(files = listOf(Manifest.ChangedFile("p", emptyList(), text)))

        // when
        val written = ManifestJson.write(manifest)

        // then
        assertContains(written, """"text": "q\" b\\ n\n r\r t\t bs\b ff\f nul\u0000 us\u001f del""" + "\u007f é 😀 /\"")
    }

    @Test
    fun `a lone surrogate is written as an escape, since no encoding could carry it raw`() {
        // given a high surrogate with no low one after it
        val manifest = manifest(files = listOf(Manifest.ChangedFile("p", emptyList(), "x\uD83Dy")))

        // when
        val written = ManifestJson.write(manifest)

        // then it is escaped, and reads back as it was
        assertContains(written, "\"x\\ud83dy\"")
        assertEquals(manifest, ManifestJson.read(written))
    }

    @Test
    fun `the output is deterministic and has no trailing newline`() {
        // given
        val manifest = manifest(files = listOf(Manifest.ChangedFile("a", listOf(1), "x")), excluded = listOf(Manifest.ExcludedFile("b", 2, "**/b")))

        // when
        val once = ManifestJson.write(manifest)
        val twice = ManifestJson.write(manifest)

        // then
        assertEquals(once, twice)
        assertEquals('}', once.last())
        assertEquals(once, ManifestJson.write(ManifestJson.read(once)))
    }

    @Test
    fun `empty collections and a null head are written as kotlinx-serialization wrote them`() {
        // given nothing changed, nothing listed, and an unborn HEAD on a branch
        val manifest = manifest(files = emptyList(), excluded = emptyList(), listed = emptyList()).copy(head = Manifest.Head(null, "fresh", dirty = false))

        // when
        val written = ManifestJson.write(manifest)

        // then
        assertContains(written, "\"sha\": null,\n        \"branch\": \"fresh\",\n        \"dirty\": false")
        assertContains(written, "\"listed\": [],\n        \"total\": 0")
        assertContains(written, "\"files\": [],\n    \"excluded\": []\n}")
        assertEquals(manifest, ManifestJson.read(written))
    }

    @Test
    fun `module manifests round trip too, previews and blind lines included`() {
        // given
        val module = ModuleManifest(
            Manifest.SCHEMA_VERSION, "feature/foryou/impl",
            listOf(
                ModuleManifest.ModuleFile("a/B.kt", listOf("a/BKt", "a/BKt\$lambda\$1"), listOf(3, 9), listOf(ModuleManifest.PreviewLines(listOf(20, 21), "@Preview on P"))),
                ModuleManifest.ModuleFile("a/C.kt", emptyList(), listOf(4), emptyList()),
            ),
        )

        // when
        val back = ManifestJson.readModule(ManifestJson.write(module))

        // then
        assertEquals(module, back)
    }

    @Test
    fun `another schemaVersion is refused, saying which two disagree`() {
        // given a shared manifest and a module manifest of the next schema
        val shared = ManifestJson.write(manifest()).replace("\"schemaVersion\": 8", "\"schemaVersion\": 9")
        val module = ManifestJson.write(ModuleManifest(8, "app", emptyList())).replace("\"schemaVersion\": 8", "\"schemaVersion\": 9")

        // when
        val sharedError = assertFailsWith<ManifestFormatException> { ManifestJson.read(shared) }
        val moduleError = assertFailsWith<ManifestFormatException> { ManifestJson.readModule(module) }

        // then
        for (error in listOf(sharedError, moduleError)) {
            assertContains(error.message!!, "schemaVersion 9")
            assertContains(error.message!!, "reads schemaVersion 8")
            assertContains(error.message!!, "different ColdSpot versions")
        }
    }

    @Test
    fun `fields this schema does not know are ignored, at every level`() {
        // given a manifest with extra fields at the root, in an object and in an array element
        val plain = ManifestJson.write(manifest(files = listOf(Manifest.ChangedFile("a", listOf(1), "x"))))
        val extended = plain
            .replace("\"schemaVersion\": 8,", "\"schemaVersion\": 8,\n    \"future\": {\"nested\": [1, 2, {\"deep\": null}]},")
            .replace("\"dirty\": false", "\"dirty\": false,\n        \"upstream\": \"origin/main\"")
            .replace("\"text\": \"x\"", "\"text\": \"x\",\n            \"hash\": \"abc\"")

        // when
        val fromPlain = ManifestJson.read(plain)
        val fromExtended = ManifestJson.read(extended)

        // then
        assertEquals(fromPlain, fromExtended)
    }

    @Test
    fun `malformed JSON is refused with the line and column`() {
        // given texts broken in different ways, each with where the reader should stop
        val cases = listOf(
            "{\n  \"schemaVersion\": 6,\n" to Triple(3, 1, "expected a string key, found the end of the input"),
            "{\"schemaVersion\": 6 \"base\": {}}" to Triple(1, 21, "expected ',' or '}' after the value of \"schemaVersion\""),
            "{\"schemaVersion\": 6, \"s\": \"bad \\x escape\"}" to Triple(1, 32, "unknown escape \\x"),
            "{\"schemaVersion\": 6, \"s\": \"never ends}" to Triple(1, 27, "never ends"),
            "{\"schemaVersion\": 6, \"s\": \"raw\ttab\"}" to Triple(1, 31, "control character (U+0009) must be escaped"),
            "{\"schemaVersion\": 6} trailing" to Triple(1, 22, "nothing may follow the value"),
            "{\"schemaVersion\": 6, \"schemaVersion\": 6}" to Triple(1, 22, "appears twice"),
            "[1, 2]" to Triple(1, 1, "a manifest is a JSON object, found an array"),
            "{\"schemaVersion\": 6, \"n\": 01}" to Triple(1, 28, "expected ',' or '}'"),
            "{\"schemaVersion\": 6, \"s\": \"\\u12\"}" to Triple(1, 28, "\\u needs four hex digits"),
        )
        for ((text, expected) in cases) {
            val (line, column, message) = expected

            // when
            val error = assertFailsWith<ManifestFormatException>("no error for $text") { ManifestJson.read(text) }

            // then
            assertEquals(line, error.line, "line for $text: ${error.message}")
            assertEquals(column, error.column, "column for $text: ${error.message}")
            assertContains(error.message!!, message, message = "message for $text")
            assertContains(error.message!!, "line $line, column $column")
        }
    }

    @Test
    fun `a manifest of the wrong shape is refused naming the field`() {
        // given a well-formed manifest with a field missing, and one of the wrong type
        val good = ManifestJson.write(manifest(files = listOf(Manifest.ChangedFile("a", listOf(1), "x"))))
        val missing = good.replace("\"dirty\": false", "\"clean\": true")
        val wrongType = good.replace("\"changedLines\": [\n                1\n            ]", "\"changedLines\": [\n                \"1\"\n            ]")
        val wrongEnum = good.replace("\"source\": \"EXPLICIT\"", "\"source\": \"MAGIC\"")

        // when
        val missingError = assertFailsWith<ManifestFormatException> { ManifestJson.read(missing) }
        val typeError = assertFailsWith<ManifestFormatException> { ManifestJson.read(wrongType) }
        val enumError = assertFailsWith<ManifestFormatException> { ManifestJson.read(wrongEnum) }

        // then
        assertEquals("manifest.head: missing \"dirty\"", missingError.message)
        assertNull(missingError.line)
        assertEquals("manifest.files[0].changedLines[0]: expected an integer, found a string", typeError.message)
        assertEquals("manifest.base.source: \"MAGIC\" is not one of EXPLICIT, ORIGIN_HEAD, GUESSED", enumError.message)
    }

    private companion object {
        fun manifest(
            files: List<Manifest.ChangedFile> = emptyList(),
            excluded: List<Manifest.ExcludedFile> = emptyList(),
            listed: List<Manifest.Commit> = listOf(Manifest.Commit("abcdef0", "abcdef0123", "first")),
            commit: String = "first",
            resetToken: String? = null,
        ): Manifest = Manifest(
            schemaVersion = Manifest.SCHEMA_VERSION,
            coldspotVersion = "1.2.3-rc1", // not the golden manifests' version: a reader that made it up would be found out
            base = Manifest.Base("origin/main", "0123456789abcdef", BaseSource.EXPLICIT),
            head = Manifest.Head("fedcba9876543210", "feature/login", dirty = false),
            commits = Manifest.Commits(listed.map { it.copy(summary = commit) }, listed.size),
            jacoco = Manifest.Jacoco("0.8.14", "0.8.14.202510111229"),
            resetToken = resetToken,
            files = files,
            excluded = excluded,
        )
    }
}
