package id.tensky.coldspot.runtime

import id.tensky.coldspot.manifest.Manifest
import id.tensky.coldspot.manifest.ModuleManifest
import id.tensky.coldspot.manifest.ShippedClass
import id.tensky.coldspot.runtime.Fixtures.Branching
import id.tensky.coldspot.runtime.Fixtures.Plain
import id.tensky.coldspot.shaded.org.jacoco.core.JaCoCo
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataStore
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The colour rule on real JaCoCo data (FINDINGS.md: instruction counters only) and the file rule (DECISIONS.md).
 * Every test reads as **given** classes of a changed file and what ran, **when** analysed, **then** these states.
 */
class AnalysisTest {
    private val file = "app/src/main/java/fx/A.kt"

    @Test
    fun `the analyser is jacoco-core relocated inside the runtime, and still knows its own version`() {
        // JaCoCo.VERSION is read from a resource bundle named after JaCoCo's package: moved with the classes, and named
        // by its new name in the relocated bytecode, or the class does not even initialise
        assertEquals("id.tensky.coldspot.shaded.org.jacoco.core.JaCoCo", JaCoCo::class.java.name)
        val version = System.getProperty("coldspot.test.jacocoVersion")
        assertTrue(JaCoCo.VERSION.startsWith("$version."), "the relocated JaCoCo says it is ${JaCoCo.VERSION}, not $version")
    }

    @Test
    fun `every line state, from real probes`() {
        // given A with an executed line, a half-executed branch, a never-executed line after a return... and B that never ran,
        // plus a line with no code, a preview line, and a blind line
        val a = Fixtures.classBytes("fx/A", "A.kt", listOf(Plain(10), Branching(11), Plain(12)))
        val b = Fixtures.classBytes("fx/B", "A.kt", listOf(Plain(20)))
        val classes = mapOf("fx/A" to a, "fx/B" to b)
        val store = Fixtures.execute(classes, calls = mapOf("fx/A" to false))
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(10, 11, 12, 13, 14, 15, 20), "")),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/A", "fx/B"), blindLines = listOf(15), previews = listOf(ModuleManifest.PreviewLines(listOf(14), "@Preview on P")))),
        )

        // when
        val report = Fixtures.analyze(manifest, store, classes)

        // then
        val states = report.files.single().lines
        assertEquals(LineState.EXECUTED, states[10])
        assertEquals(LineState.PARTIAL, states[11], "the branch skipped half its instructions")
        assertEquals(LineState.EXECUTED, states[12])
        assertEquals(LineState.NO_CODE, states[13])
        assertEquals(LineState.PREVIEW, states[14])
        assertEquals(LineState.BLIND, states[15])
        assertEquals(LineState.NOT_EXECUTED, states[20], "B never ran: absent execution data is never executed")
        assertEquals(FileStatus.PARTIAL, report.files.single().status)
        assertEquals(mapOf(LineState.EXECUTED to 2, LineState.PARTIAL to 1, LineState.NO_CODE to 1, LineState.PREVIEW to 1, LineState.BLIND to 1, LineState.NOT_EXECUTED to 1), report.linesByState)
        assertEquals(emptyList(), report.errors)
        assertEquals(2, report.classes.size)
        // and what the screens say of each line: how many instructions of how many, and the reason where the state has one
        val instructions = report.files.single().instructions
        assertEquals(instructions.getValue(10).second, instructions.getValue(10).first, "line 10 ran whole")
        assertTrue(instructions.getValue(11).first in 1 until instructions.getValue(11).second, "line 11 ran in part: ${instructions[11]}")
        assertEquals(0, instructions.getValue(20).first)
        assertTrue(instructions.getValue(20).second > 0)
        assertEquals(setOf(10, 11, 12, 20), instructions.keys, "lines with code, changed lines only")
        assertEquals(mapOf(14 to "@Preview on P"), report.files.single().reasons)
        assertEquals(listOf("app"), report.files.single().modules)
        assertEquals("", report.files.single().text)
        assertEquals(BuildInfo(manifest.coldspotVersion, manifest.base, manifest.head, manifest.commits), report.build)
    }

    @Test
    fun `a file says which modules shipped its classes, each once, and carries its text`() {
        // given a file with two classes of :lib:core and one of :app, one of them with code on line 40 too, which did not change
        val classes = mapOf("fx/A" to Fixtures.classBytes("fx/A", "A.kt", listOf(Plain(10))), "fx/B" to Fixtures.classBytes("fx/B", "A.kt", listOf(Plain(11))), "fx/C" to Fixtures.classBytes("fx/C", "A.kt", listOf(Plain(12), Plain(40))))
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(10, 11, 12), "the text\n"), Manifest.ChangedFile("plain/P.kt", listOf(1), "plain text\n")),
            Fixtures.module("lib/core", ModuleManifest.ModuleFile(file, listOf("fx/B", "fx/C"), emptyList(), emptyList())),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/A"), emptyList(), emptyList())),
        )

        // when
        val report = analyze(manifest, ExecutionDataStore(), { shipped -> classes[shipped.name] }, null)

        // then
        val measured = report.files.first { it.path == file }
        assertEquals(listOf("app", "lib/core"), measured.modules)
        assertEquals("the text\n", measured.text)
        assertEquals(setOf(10, 11, 12), measured.instructions.keys, "the instructions of changed lines, and of no other")
        // and a file nobody shipped a class for has no module, and its text all the same
        val plain = report.files.first { it.path == "plain/P.kt" }
        assertEquals(FileStatus.NOT_MEASURABLE, plain.status)
        assertEquals(emptyList(), plain.modules)
        assertEquals("plain text\n", plain.text)
    }

    @Test
    fun `a line that lives in two classes is summed across them`() {
        // given line 30 with instructions in A (ran) and in B (never ran)
        val a = Fixtures.classBytes("fx/A", "A.kt", listOf(Plain(30)))
        val b = Fixtures.classBytes("fx/B", "A.kt", listOf(Plain(30)))
        val classes = mapOf("fx/A" to a, "fx/B" to b)
        val store = Fixtures.execute(classes, calls = mapOf("fx/A" to true))
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(30), "")),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/A", "fx/B"), emptyList(), emptyList())),
        )

        // when
        val report = Fixtures.analyze(manifest, store, classes)

        // then A alone would be green; over both classes half the instructions ran
        assertEquals(LineState.PARTIAL, report.files.single().lines[30])
        val inA = report.classes.first { it.shipped.name == "fx/A" }.lines.getValue(30)
        val inB = report.classes.first { it.shipped.name == "fx/B" }.lines.getValue(30)
        assertEquals(inA.second, inA.first, "A ran every instruction of line 30")
        assertEquals(0, inB.first, "B ran none")
        assertEquals(inA.second, inB.second, "the same code in both")
    }

    @Test
    fun `a blind line is amber whether it ran or not, a preview line neutral whatever ran on it`() {
        // given line 10 executed and line 20 never, both blind; line 12 executed and a preview
        val a = Fixtures.classBytes("fx/A", "A.kt", listOf(Plain(10), Plain(12)))
        val b = Fixtures.classBytes("fx/B", "A.kt", listOf(Plain(20)))
        val classes = mapOf("fx/A" to a, "fx/B" to b)
        val store = Fixtures.execute(classes, calls = mapOf("fx/A" to true))
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(10, 12, 20), "")),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/A", "fx/B"), blindLines = listOf(10, 20), previews = listOf(ModuleManifest.PreviewLines(listOf(12), "@Preview on P")))),
        )

        // when
        val states = Fixtures.analyze(manifest, store, classes).files.single().lines

        // then
        assertEquals(LineState.BLIND, states[10], "an executed blind line is still not green")
        assertEquals(LineState.BLIND, states[20], "a never-executed blind line is not red")
        assertEquals(LineState.PREVIEW, states[12])
    }

    @Test
    fun `a class whose shipped bytes are not the bytes that ran puts its lines in error`() {
        // given S as it ran (probes under its id) and S as shipped, a different compilation of the same name
        val ran = Fixtures.classBytes("fx/S", "A.kt", listOf(Plain(10), Plain(11)))
        val shipped = Fixtures.classBytes("fx/S", "A.kt", listOf(Plain(10), Plain(11), Plain(12)))
        val store = Fixtures.execute(mapOf("fx/S" to ran), calls = mapOf("fx/S" to true))
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(10, 11, 12, 13), "")),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/S"), emptyList(), emptyList())),
        )

        // when
        val report = Fixtures.analyze(manifest, store, mapOf("fx/S" to shipped))

        // then the lines the stale class holds are errors, never red, the file is in error, and the report says why
        val states = report.files.single().lines
        assertEquals(LineState.ERROR, states[10])
        assertEquals(LineState.ERROR, states[12])
        assertEquals(LineState.NO_CODE, states[13])
        assertEquals(FileStatus.ERROR, report.files.single().status)
        assertTrue(report.classes.single().noMatch)
        // named as a stale class, apart from the errors, and as the reason of each of its lines
        assertEquals(listOf(ShippedClass("app", "fx/S")), report.staleClasses)
        assertEquals(emptyList(), report.errors)
        assertEquals(mapOf(10 to "fx/S", 11 to "fx/S", 12 to "fx/S"), report.files.single().reasons)
    }

    @Test
    fun `a stale class is seen although saving drops what ran under another id`() {
        // given S as it ran in this launch and S as shipped; T, which this launch has not run; and U, which ran but is not shipped
        val ran = Fixtures.classBytes("fx/S", "A.kt", listOf(Plain(10), Plain(11)))
        val shipped = Fixtures.classBytes("fx/S", "A.kt", listOf(Plain(10), Plain(11), Plain(12)))
        val shippedT = Fixtures.classBytes("fx/T", "A.kt", listOf(Plain(20)))
        val u = Fixtures.classBytes("fx/U", "U.kt", listOf(Plain(1)))
        val ranHere = Fixtures.execute(mapOf("fx/S" to ran, "fx/U" to u), calls = mapOf("fx/S" to true, "fx/U" to true))
        val ids = setOf(classId(shipped, "fx/S")!!, classId(shippedT, "fx/T")!!)
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(10, 20), "")),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/S", "fx/T"), emptyList(), emptyList())),
        )

        // when what is saved (nothing: no id of this launch is a shipped one) goes to the analysis as it is, and with the stale classes
        val saved = ExecutionDataStore()
        val asSaved = Fixtures.analyze(manifest, saved, mapOf("fx/S" to shipped, "fx/T" to shippedT))
        val forAnalysis = withStaleClasses(saved, ranHere, ids, setOf("fx/S", "fx/T"))
        val withStale = Fixtures.analyze(manifest, forAnalysis, mapOf("fx/S" to shipped, "fx/T" to shippedT))

        // then without them the stale class passes for one that never ran, red; with them it is an error, and T is simply not executed
        assertEquals(LineState.NOT_EXECUTED, asSaved.files.single().lines[10], "this is the false red the stale classes are added against")
        assertEquals(LineState.ERROR, withStale.files.single().lines[10])
        assertEquals(LineState.NOT_EXECUTED, withStale.files.single().lines[20])
        assertEquals(listOf(ShippedClass("app", "fx/S")), withStale.staleClasses)
        assertEquals(listOf("fx/S"), forAnalysis.contents.map { it.name }, "only what ran under a shipped name is added: U is nobody's stale class")
        assertEquals(0, saved.contents.size, "the saved data itself is left as it was")
    }

    @Test
    fun `a class without line numbers is skipped, a class the agent never saw is simply not executed`() {
        // given N without a LineNumberTable and B that never ran
        val n = Fixtures.classBytes("fx/N", "A.kt", listOf(Plain(10)), lineNumbers = false)
        val b = Fixtures.classBytes("fx/B", "A.kt", listOf(Plain(20)))
        val classes = mapOf("fx/N" to n, "fx/B" to b)
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(10, 20), "")),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/B", "fx/N"), emptyList(), emptyList())),
        )

        // when analysed with no execution data at all
        val report = Fixtures.analyze(manifest, ExecutionDataStore(), classes)

        // then
        assertEquals(listOf("fx/B"), report.classes.map { it.shipped.name })
        assertEquals(LineState.NO_CODE, report.files.single().lines[10])
        assertEquals(LineState.NOT_EXECUTED, report.files.single().lines[20])
        assertEquals(FileStatus.NOT_EXECUTED, report.files.single().status)
    }

    @Test
    fun `not measurable, excluded and warned files are reported, never coloured`() {
        // given a file nobody shipped a class for, an excluded file, and a module naming a file the app does not
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile("plain/Model.kt", listOf(5), ""), excluded = listOf(Manifest.ExcludedFile("t/T.kt", 3, "**/src/test/**"))),
            Fixtures.module("lib", ModuleManifest.ModuleFile("z/Extra.kt", listOf("z/ExtraKt"), emptyList(), emptyList())),
        )

        // when
        val report = Fixtures.analyze(manifest, ExecutionDataStore(), mapOf("z/ExtraKt" to Fixtures.classBytes("z/ExtraKt", "Extra.kt", listOf(Plain(1)))))

        // then
        val byPath = report.files.associateBy { it.path }
        assertEquals(FileStatus.NOT_MEASURABLE, byPath.getValue("plain/Model.kt").status)
        assertEquals(FileStatus.EXCLUDED, byPath.getValue("t/T.kt").status)
        assertEquals("**/src/test/**", byPath.getValue("t/T.kt").excludedBy)
        assertEquals(FileStatus.NEUTRAL, byPath.getValue("z/Extra.kt").status, "no changed lines are known for it")
        assertEquals(1, report.warnings.size)
        assertContains(report.warnings.single(), ":lib ships classes for z/Extra.kt, which the app's manifest has neither as changed nor as excluded")
        assertEquals(mapOf(FileStatus.NOT_MEASURABLE to 1, FileStatus.EXCLUDED to 1, FileStatus.NEUTRAL to 1), report.filesByStatus)
    }

    @Test
    fun `a file the app excludes is excluded only, even when a module ships a class for it and that class ran`() {
        // given the app excluding Tag.kt, and a module built with other exclude rules shipping, and running, its class
        val tag = Fixtures.classBytes("d/TagKt", "Tag.kt", listOf(Plain(3), Plain(4)))
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(10), ""), excluded = listOf(Manifest.ExcludedFile("d/Tag.kt", 2, "**/Tag.kt"))),
            Fixtures.module("core/designsystem", ModuleManifest.ModuleFile("d/Tag.kt", listOf("d/TagKt"), listOf(4), emptyList())),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/A"), emptyList(), emptyList())),
        )
        val a = Fixtures.classBytes("fx/A", "A.kt", listOf(Plain(10)))
        val ran = Fixtures.execute(mapOf("d/TagKt" to tag, "fx/A" to a), calls = mapOf("d/TagKt" to true, "fx/A" to true))

        // when
        val report = Fixtures.analyze(manifest, ran, mapOf("d/TagKt" to tag, "fx/A" to a))

        // then it is one row, excluded by the app's rule, and none of the module's data counts
        assertEquals(listOf(FileStatus.EXCLUDED), report.files.filter { it.path == "d/Tag.kt" }.map { it.status }, "the excluded file is not one excluded row")
        assertEquals("**/Tag.kt", report.files.single { it.path == "d/Tag.kt" }.excludedBy)
        assertEquals(listOf("fx/A"), report.classes.map { it.shipped.name }, "the excluded file's class was analysed")
        assertEquals(mapOf(FileStatus.EXECUTED to 1, FileStatus.EXCLUDED to 1), report.filesByStatus)
        assertEquals(1, report.linesByState.values.sum(), "a line of the excluded file was counted")
        assertContains(report.warnings.single(), ":core:designsystem ships classes for d/Tag.kt, which the app excludes (**/Tag.kt)")
    }

    @Test
    fun `a manifest from another JaCoCo colours nothing and says so`() {
        // given a manifest whose instrumenter was a JaCoCo this one is not
        val a = Fixtures.classBytes("fx/A", "A.kt", listOf(Plain(10)))
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(10), ""), jacocoBuild = "0.8.99.202601010000"),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/A"), emptyList(), emptyList())),
        )

        // when
        val report = Fixtures.analyze(manifest, Fixtures.execute(mapOf("fx/A" to a), mapOf("fx/A" to true)), mapOf("fx/A" to a))

        // then
        assertTrue(report.jacocoMismatch)
        assertEquals(emptyList(), report.files)
        assertEquals(emptyList(), report.classes)
        assertContains(report.errors.single(), "0.8.99.202601010000")
        assertContains(report.errors.single(), "nothing is coloured")
    }

    @Test
    fun `a shipped class missing from the assets is an error, not a crash`() {
        // given the manifest naming a class the assets do not hold
        val manifest = Fixtures.merged(
            Fixtures.shared(Manifest.ChangedFile(file, listOf(10), "")),
            Fixtures.module("app", ModuleManifest.ModuleFile(file, listOf("fx/Gone"), emptyList(), emptyList())),
        )

        // when
        val report = Fixtures.analyze(manifest, ExecutionDataStore(), emptyMap())

        // then
        assertContains(report.errors.single(), "fx/Gone")
        assertEquals(LineState.NO_CODE, report.files.single().lines[10])
    }

    @Test
    fun `the file rule, over every mix of line states`() {
        fun status(vararg states: LineState): FileStatus = fileReport("f", states.withIndex().associate { (i, s) -> i + 1 to s }).status

        // then, per DECISIONS.md "File status"
        assertEquals(FileStatus.EXECUTED, status(LineState.EXECUTED, LineState.EXECUTED, LineState.NO_CODE))
        assertEquals(FileStatus.NOT_EXECUTED, status(LineState.NOT_EXECUTED, LineState.NOT_EXECUTED, LineState.PREVIEW))
        assertEquals(FileStatus.PARTIAL, status(LineState.EXECUTED, LineState.NOT_EXECUTED))
        assertEquals(FileStatus.PARTIAL, status(LineState.PARTIAL))
        assertEquals(FileStatus.PARTIAL, status(LineState.EXECUTED, LineState.BLIND), "any amber line makes the file yellow")
        assertEquals(FileStatus.PARTIAL, status(LineState.NOT_EXECUTED, LineState.BLIND), "a blind line is amber, so the file is not red")
        assertEquals(FileStatus.NEUTRAL, status(LineState.NO_CODE, LineState.PREVIEW))
        assertEquals(FileStatus.NEUTRAL, status())
        assertEquals(FileStatus.ERROR, status(LineState.EXECUTED, LineState.ERROR))
        // and the counts behind "18/20 lines"
        val report = fileReport("f", mapOf(1 to LineState.EXECUTED, 2 to LineState.PARTIAL, 3 to LineState.NOT_EXECUTED, 4 to LineState.BLIND, 5 to LineState.NO_CODE, 6 to LineState.PREVIEW))
        assertEquals(1, report.executed)
        assertEquals(4, report.executable)
    }
}
