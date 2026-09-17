package id.tensky.coldspot.manifest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The merge rule, as the runtime applies it. Every test reads as **given** manifests, **when** merged, **then** this is the result. */
class MergeTest {

    @Test
    fun `files are joined by path, classes remember their module, blind lines and previews are unions`() {
        // given a file two modules ship classes for, with overlapping blind lines and previews
        val shared = shared(
            Manifest.ChangedFile("a/B.kt", listOf(3, 9, 20), "text of B"),
            Manifest.ChangedFile("a/C.kt", listOf(4), "text of C"),
        )
        val app = module("app", ModuleManifest.ModuleFile("a/B.kt", listOf("a/BKt"), listOf(9), listOf(preview(20, "@Preview on P"))))
        val lib = module("lib/core", ModuleManifest.ModuleFile("a/B.kt", listOf("a/BKt\$lambda\$1", "a/BKt"), listOf(3, 9), listOf(preview(20, "@Preview on P"), preview(30, "@Preview on Q"))))

        // when
        val merged = merge(shared, listOf(lib, app))

        // then
        val b = merged.files.first { it.path == "a/B.kt" }
        assertEquals(listOf(3, 9, 20), b.changedLines)
        assertEquals("text of B", b.text)
        assertEquals(listOf(ShippedClass("app", "a/BKt"), ShippedClass("lib/core", "a/BKt"), ShippedClass("lib/core", "a/BKt\$lambda\$1")), b.classes)
        assertEquals(listOf(3, 9), b.blindLines)
        assertEquals(listOf(preview(20, "@Preview on P"), preview(30, "@Preview on Q")), b.previews)
        assertTrue(b.measurable)
    }

    @Test
    fun `a file nobody ships a class for is kept and not measurable`() {
        // given a changed file no module has anything for, and one a module has only blind lines for
        val shared = shared(Manifest.ChangedFile("plain/Model.kt", listOf(5), "x"), Manifest.ChangedFile("a/Feed.kt", listOf(27), "y"))
        val app = module("app", ModuleManifest.ModuleFile("a/Feed.kt", emptyList(), listOf(27), emptyList()))

        // when
        val merged = merge(shared, listOf(app))

        // then
        val model = merged.files.first { it.path == "plain/Model.kt" }
        assertFalse(model.measurable)
        assertEquals(listOf(5), model.changedLines)
        val feed = merged.files.first { it.path == "a/Feed.kt" }
        assertFalse(feed.measurable)
        assertEquals(listOf(27), feed.blindLines)
    }

    @Test
    fun `a file only a module names is kept, without text or changed lines`() {
        // given a module shipping a class for a file the shared manifest does not list
        val shared = shared(Manifest.ChangedFile("a/B.kt", listOf(1), "b"))
        val lib = module("lib", ModuleManifest.ModuleFile("z/Extra.kt", listOf("z/ExtraKt"), emptyList(), emptyList()))

        // when
        val merged = merge(shared, listOf(lib))

        // then
        val extra = merged.files.first { it.path == "z/Extra.kt" }
        assertNull(extra.text)
        assertEquals(emptyList(), extra.changedLines)
        assertEquals(listOf(ShippedClass("lib", "z/ExtraKt")), extra.classes)
        assertTrue(extra.measurable)
        // and the merge says so
        assertEquals(listOf(":lib ships classes for z/Extra.kt, which the app's manifest has neither as changed nor as excluded: the modules were not built from the same change."), merged.warnings)
    }

    @Test
    fun `exclusion wins - a file the app excludes is only excluded, whatever modules ship for it, and a warning names them`() {
        // given the app excluding a file that two modules, built with other exclude rules, ship classes, blind lines and previews for
        val tag = "core/designsystem/Tag.kt"
        val shared = shared(Manifest.ChangedFile("a/B.kt", listOf(1), "b")).copy(excluded = listOf(Manifest.ExcludedFile(tag, 4, "**/designsystem/Tag.kt")))
        val design = module("core/designsystem", ModuleManifest.ModuleFile(tag, listOf("core/TagKt"), listOf(7), listOf(preview(9, "@Preview on TagPreview"))))
        val ui = module("core/ui", ModuleManifest.ModuleFile(tag, listOf("core/TagKt\$1"), emptyList(), emptyList()))
        val app = module("app", ModuleManifest.ModuleFile("a/B.kt", listOf("a/BKt"), emptyList(), emptyList()))

        // when
        val merged = merge(shared, listOf(ui, design, app))

        // then it is excluded, with the app's rule, and nothing else: no file of its own, no class shipped for it
        assertEquals(listOf("a/B.kt"), merged.files.map { it.path }, "an excluded file is also among the files")
        assertEquals(shared.excluded, merged.excluded)
        assertTrue(merged.files.none { file -> file.classes.any { it.name.startsWith("core/TagKt") } }, "a class shipped for an excluded file is kept")
        // and one warning, naming both modules, says what happened and what to do
        assertEquals(
            listOf(
                ":core:designsystem and :core:ui ship classes for $tag, which the app excludes (**/designsystem/Tag.kt): they are ignored, " +
                    "and the file shows as excluded. Set the exclude rules once, in the convention plugin that applies ColdSpot, so that " +
                    "every module excludes the same files.",
            ),
            merged.warnings,
        )
    }

    @Test
    fun `the version, base, head, commits, jacoco and the excluded files come from the shared manifest, once`() {
        // given
        val shared = shared().copy(excluded = listOf(Manifest.ExcludedFile("t/T.kt", 2, "**/src/test/**")))

        // when
        val merged = merge(shared, listOf(module("app"), module("lib")))

        // then
        assertEquals("4.5.6", merged.coldspotVersion)
        assertEquals("feature/x", merged.head.branch)
        assertEquals(shared.base, merged.base)
        assertEquals(shared.head, merged.head)
        assertEquals(shared.commits, merged.commits)
        assertEquals(shared.jacoco, merged.jacoco)
        assertEquals(shared.excluded, merged.excluded)
        assertEquals(emptyList(), merged.warnings)
    }

    @Test
    fun `the result is in path order whatever order the manifests came in, and the same for any module order`() {
        // given files and modules out of order
        val shared = shared(Manifest.ChangedFile("b", listOf(1), "b"), Manifest.ChangedFile("a", listOf(1), "a"))
        val m1 = module("lib", ModuleManifest.ModuleFile("b", listOf("BKt"), emptyList(), emptyList()), ModuleManifest.ModuleFile("c", listOf("CKt"), emptyList(), emptyList()))
        val m2 = module("app", ModuleManifest.ModuleFile("a", listOf("AKt"), emptyList(), emptyList()), ModuleManifest.ModuleFile("b", listOf("BKt2"), emptyList(), emptyList()))

        // when
        val oneWay = merge(shared, listOf(m1, m2))
        val otherWay = merge(shared, listOf(m2, m1))

        // then
        assertEquals(listOf("a", "b", "c"), oneWay.files.map { it.path })
        assertEquals(oneWay, otherWay)
        assertEquals(listOf(ShippedClass("app", "BKt2"), ShippedClass("lib", "BKt")), oneWay.files[1].classes)
    }

    private companion object {
        fun shared(vararg files: Manifest.ChangedFile): Manifest = Manifest(
            Manifest.SCHEMA_VERSION,
            "4.5.6",
            Manifest.Base("origin/HEAD", "0123", BaseSource.ORIGIN_HEAD),
            Manifest.Head("4567", "feature/x", dirty = true),
            Manifest.Commits(listOf(Manifest.Commit("4567", "4567", "work")), 1),
            Manifest.Jacoco("0.8.14", "0.8.14.202510111229"),
            null,
            files.toList(),
            emptyList(),
        )

        fun module(name: String, vararg files: ModuleManifest.ModuleFile): ModuleManifest = ModuleManifest(Manifest.SCHEMA_VERSION, name, files.toList())

        fun preview(line: Int, reason: String): ModuleManifest.PreviewLines = ModuleManifest.PreviewLines(listOf(line), reason)
    }
}
