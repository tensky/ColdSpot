package id.tensky.coldspot.bundle

import id.tensky.coldspot.diff.FixtureRepo
import id.tensky.coldspot.diff.SourceFile
import id.tensky.coldspot.manifest.BaseSource
import id.tensky.coldspot.manifest.Manifest
import id.tensky.coldspot.manifest.ManifestJson
import id.tensky.coldspot.manifest.ModuleManifest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.security.MessageDigest
import java.util.TreeSet
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What [buildBundle] ships and what it reports, against fixture repositories and class files assembled with
 * ASM, so that every attribute a decision rests on is spelled out in the test that needs it. The one exception
 * is [BlindLines], which needs classes only kotlinc and the Compose compiler know how to produce.
 *
 * Every test reads as **given** a repository and some compiled classes, **when** a bundle is built,
 * **then** these classes shipped, and this is what the manifest and the report say.
 */
@RunWith(Enclosed::class)
class BuildBundleTest {

    /**
     * Given, for every test: a repository with one commit (a `.gitignore`), an empty directory for class
     * files, and an empty output directory. Tests commit the state they start from, then edit.
     */
    abstract class Fixture {
        @get:Rule
        val tmp = TemporaryFolder()

        protected lateinit var repo: FixtureRepo
        protected lateinit var classDir: File
        protected lateinit var outDir: File

        @Before
        fun setUp() {
            repo = FixtureRepo(tmp.newFolder("repo"))
            classDir = tmp.newFolder("classes")
            outDir = tmp.newFolder("out")
            repo.write(".gitignore", "build/\n")
            repo.commitAll("initial")
        }

        @After
        fun tearDown() = repo.close()

        /** Writes a source file of [lines] lines into the working tree and returns a handle to it. */
        protected fun sourceFile(path: String, lines: Int = 20): SourceFile = SourceFile(repo, path, lines, "value").write()

        /**
         * A class file in [classDir]: [name], stamped with [sourceFile], holding one method whose
         * `LineNumberTable` lists [lines]. An empty range leaves the table out altogether, as compilers do
         * for synthetic code.
         */
        protected fun compiledClass(name: String, sourceFile: String?, lines: IntRange): ByteArray {
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, null, "java/lang/Object", null)
            if (sourceFile != null) writer.visitSource(sourceFile, null)
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
                visitCode()
                for (line in lines) {
                    val at = Label()
                    visitLabel(at)
                    visitLineNumber(line, at)
                    visitInsn(Opcodes.NOP)
                }
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            writer.visitEnd()
            return writer.toByteArray().also { classFile(name).writeBytes(it) }
        }

        /** A class kotlinc and the Compose compiler produced for the spike, kept under test resources, copied into [classDir]. */
        protected fun realClass(name: String): ByteArray = resource("/compose/classes/$name.class").also { classFile(name).writeBytes(it) }

        protected fun resource(path: String): ByteArray =
            checkNotNull(javaClass.getResourceAsStream(path)) { "no test resource $path" }.use { it.readBytes() }

        /** Where [classDir] holds the class called [name]. */
        protected fun classFile(name: String): File = File(classDir, "$name.class").apply { parentFile.mkdirs() }

        /** Rewrites [path] with a comment appended to each of [lines]: the smallest edit that changes exactly those. */
        protected fun editLines(path: String, vararg lines: Int) {
            val file = File(repo.dir, path)
            val edited = file.readText().lines().mapIndexed { i, text -> if (i + 1 in lines) "$text // edited" else text }
            file.writeText(edited.joinToString("\n"))
        }

        /**
         * The call under test, as the application module makes it: the bundle of module [MODULE] plus the shared
         * manifest. Against HEAD unless told otherwise, so that a test's edits are the change.
         */
        protected fun build(baseRef: String? = "HEAD", excludes: ExcludeRules = ExcludeRules.DEFAULT, classDirs: List<File> = listOf(classDir)): BundleReport =
            buildBundle(RepositoryChanges.of(repo.dir, baseRef), excludes, MODULE, classDirs, emptyList(), outDir, sharedManifest = true, JACOCO, resetToken = null, coldspotVersion = COLDSPOT)

        /** The shared manifest, `manifest.json` at the root. */
        protected fun manifest(): Manifest = ManifestJson.read(File(outDir, "manifest.json").readText())

        /** The module's own manifest, `<module>/manifest.json`. */
        protected fun moduleManifest(): ModuleManifest = ManifestJson.readModule(File(outDir, "$MODULE/manifest.json").readText())

        /** VM names of the class files written under `<module>/classes/`, ascending. */
        protected fun shipped(): List<String> {
            val root = File(outDir, "$MODULE/classes")
            return root.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class") }
                .sorted()
                .toList()
        }

        protected companion object {
            const val MODULE = "mod"

            /** What the plugin would pass: the instrumenter's jacoco-core, by Maven version and by `JaCoCo.VERSION`. */
            val JACOCO = Manifest.Jacoco(version = "0.8.14", build = "0.8.14.202510111229")

            /** And its own version. */
            const val COLDSPOT = "9.9.9"
        }
    }

    /** Which compiled classes are attributed to which changed file. */
    class ClassSelection : Fixture() {

        @Test
        fun `1 - two Utils files in different packages, only one changed, ships only that one's class`() {
            // given Utils.kt in packages a and b, both compiled, and only a's edited
            val a = sourceFile("lib/src/main/kotlin/com/a/Utils.kt")
            sourceFile("lib/src/main/kotlin/com/b/Utils.kt")
            repo.commitAll("both")
            a.edit(3)
            compiledClass("com/a/UtilsKt", "Utils.kt", 1..5)
            compiledClass("com/b/UtilsKt", "Utils.kt", 1..5)

            // when
            val report = build()

            // then b's class stays home: its package points at a file that exists and did not change
            assertEquals(mapOf(a.path to listOf("com/a/UtilsKt")), report.classesByFile)
            assertEquals(listOf("com/a/UtilsKt"), shipped())
            assertEquals(emptyList(), report.ambiguous)
        }

        @Test
        fun `2 - a package that differs from the directory is matched by file name, when that name is unique`() {
            // given a file in lib/src whose class kotlinc put in package com.x, the only changed Helpers.kt
            val helpers = sourceFile("lib/src/Helpers.kt")
            repo.commitAll("add")
            helpers.edit(2)
            compiledClass("com/x/HelpersKt", "Helpers.kt", 1..5)

            // when
            val report = build()

            // then nothing ends in com/x/Helpers.kt, so the name alone attributes it
            assertEquals(mapOf(helpers.path to listOf("com/x/HelpersKt")), report.classesByFile)
            assertEquals(listOf("com/x/HelpersKt"), shipped())
        }

        @Test
        fun `3 - the same, with two changed files of that name, is ambiguous and ships nothing`() {
            // given two changed Helpers.kt, neither where package com.x would put one
            val one = sourceFile("lib/src/Helpers.kt")
            val two = sourceFile("other/Helpers.kt")
            repo.commitAll("add")
            one.edit(2)
            two.edit(2)
            compiledClass("com/x/HelpersKt", "Helpers.kt", 1..5)

            // when
            val report = build()

            // then the class is reported rather than guessed at, and both files are left without classes
            assertEquals(listOf(AmbiguousClass("com/x/HelpersKt", "Helpers.kt", listOf(one.path, two.path))), report.ambiguous)
            assertEquals(emptyMap(), report.classesByFile)
            assertEquals(emptyList(), shipped())
            assertEquals(listOf(one.path, two.path), report.filesWithoutClasses)
        }

        @Test
        fun `4 - a class without a LineNumberTable is skipped`() {
            // given a changed file and a class of it whose methods carry no line numbers
            val utils = sourceFile("lib/src/main/kotlin/com/a/Utils.kt")
            repo.commitAll("add")
            utils.edit(3)
            compiledClass("com/a/UtilsKt\$WhenMappings", "Utils.kt", IntRange.EMPTY)

            // when
            val report = build()

            // then it is neither shipped nor reported: it could only add noise
            assertEquals(emptyList(), shipped())
            assertEquals(emptyList(), report.ambiguous)
            assertEquals(listOf(utils.path), report.filesWithoutClasses)
        }

        @Test
        fun `5 - a class whose lines the change does not touch is skipped`() {
            // given line 3 edited and a class of that file covering lines 10 to 12
            val utils = sourceFile("lib/src/main/kotlin/com/a/Utils.kt")
            repo.commitAll("add")
            utils.edit(3)
            compiledClass("com/a/UtilsKt", "Utils.kt", 10..12)

            // when
            val report = build()

            // then
            assertEquals(emptyList(), shipped())
            assertEquals(listOf(utils.path), report.filesWithoutClasses)
        }

        @Test
        fun `6 - a class whose SourceFile names a file outside the diff is skipped`() {
            // given a changed file and a lambda regenerated from a Compose inline function, stamped LazyDsl.kt
            val utils = sourceFile("lib/src/main/kotlin/com/a/Utils.kt")
            repo.commitAll("add")
            utils.edit(3)
            compiledClass("com/a/UtilsKt\$lambda\$1", "LazyDsl.kt", 1..50)

            // when
            val report = build()

            // then there is nothing to attribute it to, and it is not an ambiguity either
            assertEquals(emptyList(), shipped())
            assertEquals(emptyList(), report.ambiguous)
        }

        @Test
        fun `a class without a SourceFile at all is skipped`() {
            // given a changed file and a class of it compiled without debug attributes
            val utils = sourceFile("lib/src/main/kotlin/com/a/Utils.kt")
            repo.commitAll("add")
            utils.edit(3)
            compiledClass("com/a/UtilsKt", null, 1..5)

            // when
            val report = build()

            // then
            assertEquals(emptyList(), shipped())
            assertEquals(emptyList(), report.ambiguous)
        }

        @Test
        fun `the package matches whole directories, not the tail end of one`() {
            // given changed Utils.kt under xcom/a and under com/b, and a class in package com.a
            val xcom = sourceFile("lib/xcom/a/Utils.kt")
            val comB = sourceFile("lib/com/b/Utils.kt")
            repo.commitAll("add")
            xcom.edit(3)
            comB.edit(3)
            compiledClass("com/a/UtilsKt", "Utils.kt", 1..5)

            // when
            val report = build()

            // then com/a/Utils.kt is not the tail of xcom/a/Utils.kt: the package fits nowhere, two names remain
            assertEquals(listOf(AmbiguousClass("com/a/UtilsKt", "Utils.kt", listOf(comB.path, xcom.path))), report.ambiguous)
            assertEquals(emptyList(), shipped())
        }

        @Test
        fun `an ambiguous class the change never touches is simply skipped`() {
            // given two changed Helpers.kt, edited on line 2, and a same-named class covering lines 10 to 12
            val one = sourceFile("lib/src/Helpers.kt")
            val two = sourceFile("other/Helpers.kt")
            repo.commitAll("add")
            one.edit(2)
            two.edit(2)
            compiledClass("com/x/HelpersKt", "Helpers.kt", 10..12)

            // when
            val report = build()

            // then whichever file it belongs to, no changed line is its: nothing to ship, nothing to warn about
            assertEquals(emptyList(), report.ambiguous)
            assertEquals(emptyList(), shipped())
        }

        @Test
        fun `every class of a file ships, and only those the change touches`() {
            // given Feed.kt edited on lines 3 and 40, and three classes stamped with it
            val feed = sourceFile("app/src/main/java/demo/Feed.kt", lines = 50)
            repo.commitAll("add")
            feed.edit(3, 40)
            compiledClass("demo/FeedKt", "Feed.kt", 1..10)
            compiledClass("demo/FeedKt\$lambda\$1", "Feed.kt", 38..42)
            compiledClass("demo/FeedKt\$lambda\$2", "Feed.kt", 20..25)

            // when
            val report = build()

            // then the facade and the first lambda ship, the second lambda does not
            assertEquals(mapOf(feed.path to listOf("demo/FeedKt", "demo/FeedKt\$lambda\$1")), report.classesByFile)
            assertEquals(listOf("demo/FeedKt", "demo/FeedKt\$lambda\$1"), shipped())
        }
    }

    /**
     * Finding 4: a lambda passed to a library's inline function is regenerated as a class stamped with the
     * library's `SourceFile`, so it can be neither matched to the changed file nor shipped, and JaCoCo on the
     * device maps no line to it. Its SMAP still says which lines it came from. The classes here are the ones
     * kotlinc 2.2.10 and the Compose compiler produced for the spike's Feed.kt (`src/test/resources/compose`),
     * where lines 26-28 are `items(visible) { item -> FeedRow(text = item, onClick = { ... }) }`.
     */
    class BlindLines : Fixture() {
        private val feed = "app/src/main/java/id/tensky/coldspotspike/Feed.kt"
        private val itemsLambda = "id/tensky/coldspotspike/$INLINED_ITEMS_LAMBDA"
        private val onClickLambda = "id/tensky/coldspotspike/FeedKt\$Feed\$1\$2\$1\$1\$1\$1"

        /** Feed.kt committed [at] some path, and every class compiled from it in [classDir]. */
        private fun commitFeed(at: String = feed) {
            repo.write(at, resource("/compose/Feed.kt").decodeToString())
            repo.commitAll("feed")
            for (name in compiledFromFeed) realClass(name)
        }

        @Test
        fun `the call site inside an inlined Compose lambda is blind, and the lambda's class does not ship`() {
            // given lines 27 and 28 edited: the FeedRow call inside `items { }`, and the lambda's closing brace
            commitFeed()
            editLines(feed, 27, 28)

            // when
            val report = build()

            // then the LazyDsl.kt-stamped class's SMAP points at both lines, and only the onClick lambda ships
            assertEquals(listOf(27, 28), moduleManifest().files.single().blindLines)
            assertEquals(mapOf(feed to listOf(onClickLambda)), report.classesByFile)
            assertEquals(listOf(onClickLambda), shipped())
        }

        @Test
        fun `a blind line is recorded even though a shipped class covers it too`() {
            // given line 27 edited, which the onClick lambda's own LineNumberTable also has
            commitFeed()
            editLines(feed, 27)

            // when
            val report = build()

            // then both are true of it, and the app gets to decide what that means
            assertEquals(mapOf(feed to listOf(27)), report.blindLinesByFile)
            assertEquals(mapOf(feed to listOf(onClickLambda)), report.classesByFile)
        }

        @Test
        fun `only changed lines are blind`() {
            // given only line 28 edited, which no shipped class has
            commitFeed()
            editLines(feed, 28)

            // when
            val report = build()

            // then 28 is blind and 27 is not, and the file has no classes yet is not simply red: the module lists it for its blind line
            assertEquals(listOf(ModuleManifest.ModuleFile(feed, classes = emptyList(), blindLines = listOf(28), previews = emptyList())), moduleManifest().files)
            assertEquals(listOf(feed), report.filesWithoutClasses)
        }

        @Test
        fun `a changed line outside any inlined lambda is not blind`() {
            // given line 21 edited, `var onlyA by remember { ... }`, which FeedKt itself has
            commitFeed()
            editLines(feed, 21)

            // when
            val report = build()

            // then
            assertEquals(emptyMap(), report.blindLinesByFile)
            assertEquals(mapOf(feed to listOf("id/tensky/coldspotspike/FeedKt")), report.classesByFile)
        }

        @Test
        fun `an SMAP file that could be either of two changed files records nothing`() {
            // given Feed.kt changed at two paths, neither where package id.tensky.coldspotspike would put it
            commitFeed(at = "x/Feed.kt")
            repo.write("y/Feed.kt", resource("/compose/Feed.kt").decodeToString())
            repo.commitAll("twice")
            editLines("x/Feed.kt", 27)
            editLines("y/Feed.kt", 27)

            // when
            val report = build()

            // then the SMAP's Feed.kt is placed by the same rules as a SourceFile: never a guess
            assertEquals(emptyMap(), report.blindLinesByFile)
            assertEquals(emptyList(), shipped())
            assertEquals(listOf(AmbiguousClass(onClickLambda, "Feed.kt", listOf("x/Feed.kt", "y/Feed.kt"))), report.ambiguous)
        }

        private companion object {
            val compiledFromFeed = listOf(
                "id/tensky/coldspotspike/FeedKt",
                "id/tensky/coldspotspike/ComposableSingletons\$FeedKt",
                "id/tensky/coldspotspike/FeedKt\$Feed\$1\$2\$1\$1\$1\$1",
                "id/tensky/coldspotspike/FeedKt\$Feed\$lambda\$10\$lambda\$9\$lambda\$8\$\$inlined\$items\$default\$1",
                "id/tensky/coldspotspike/FeedKt\$Feed\$lambda\$10\$lambda\$9\$lambda\$8\$\$inlined\$items\$default\$2",
                "id/tensky/coldspotspike/FeedKt\$Feed\$lambda\$10\$lambda\$9\$lambda\$8\$\$inlined\$items\$default\$3",
                "id/tensky/coldspotspike/FeedKt\$Feed\$lambda\$10\$lambda\$9\$lambda\$8\$\$inlined\$items\$default\$4",
            )
        }
    }

    /** What lands in the output directory. */
    class Output : Fixture() {

        @Test
        fun `7 - class bytes are copied byte for byte`() {
            // given a class kotlinc compiled, placed where its package says, and its file edited on a line it has
            val original = Compiled::class.java.getResourceAsStream("Compiled.class")!!.readBytes()
            classFile("id/tensky/coldspot/bundle/Compiled").writeBytes(original)
            val lines = lineNumbersOf(original)
            val source = sourceFile("src/id/tensky/coldspot/bundle/Compiled.kt", lines = lines.last())
            repo.commitAll("add")
            source.edit(lines.first())

            // when
            val report = build()

            // then
            assertEquals(mapOf(source.path to listOf("id/tensky/coldspot/bundle/Compiled")), report.classesByFile)
            val copy = File(outDir, "$MODULE/classes/id/tensky/coldspot/bundle/Compiled.class").readBytes()
            assertEquals(sha256(original), sha256(copy))
        }

        @Test
        fun `the manifest describes the base, the head, the instrumenter's JaCoCo and every changed file`() {
            // given a tagged base, a committed edit to Feed.kt line 3, an uncommitted one to line 5, and its class
            val feed = sourceFile("app/src/main/java/demo/Feed.kt")
            repo.commitAll("add")
            repo.tag("base")
            feed.edit(3)
            repo.commitAll("committed edit")
            feed.edit(3, 5)
            compiledClass("demo/FeedKt", "Feed.kt", 1..10)

            // when
            build("base")

            // then the shared manifest describes the change, with the file's text, and the module's names the class
            val manifest = manifest()
            assertEquals(8, Manifest.SCHEMA_VERSION, "ColdSpot's version and the head's branch joined the shared manifest: the shape changed")
            assertEquals(COLDSPOT, manifest.coldspotVersion, "the ColdSpot that built, for the app to show")
            assertEquals(Manifest.SCHEMA_VERSION, manifest.schemaVersion)
            assertEquals(Manifest.Base("base", repo.resolve("base").name, BaseSource.EXPLICIT), manifest.base)
            val committed = repo.resolve("HEAD").name
            assertEquals(Manifest.Head(committed, "main", dirty = true), manifest.head)
            assertEquals(Manifest.Commits(listOf(Manifest.Commit(committed.take(7), committed, "committed edit")), total = 1), manifest.commits)
            assertEquals(JACOCO, manifest.jacoco, "the instrumenter's JaCoCo, for the app to check its own against")
            assertEquals(listOf(Manifest.ChangedFile(feed.path, listOf(3, 5), feed.file.readText())), manifest.files)
            assertEquals(emptyList(), manifest.excluded)
            val module = moduleManifest()
            assertEquals(Manifest.SCHEMA_VERSION, module.schemaVersion)
            assertEquals(MODULE, module.module)
            assertEquals(listOf(ModuleManifest.ModuleFile(feed.path, listOf("demo/FeedKt"), blindLines = emptyList(), previews = emptyList())), module.files)
        }

        @Test
        fun `a changed file no class matched is in the shared manifest all the same, in no module's, and reported`() {
            // given an edited file and no compiled classes at all
            val feed = sourceFile("app/src/main/java/demo/Feed.kt")
            repo.commitAll("add")
            feed.edit(3)

            // when
            val report = build()

            // then
            assertEquals(listOf(feed.path), report.filesWithoutClasses)
            assertEquals(emptyMap(), report.classesByFile)
            assertEquals(listOf(Manifest.ChangedFile(feed.path, listOf(3), feed.file.readText())), manifest().files)
            assertEquals(emptyList(), moduleManifest().files)
        }

        @Test
        fun `a library module writes its own manifest and classes, and no shared manifest`() {
            // given an edited file and its class
            val feed = sourceFile("app/src/main/java/demo/Feed.kt")
            repo.commitAll("add")
            feed.edit(3)
            compiledClass("demo/FeedKt", "Feed.kt", 1..10)

            // when built as a library module would
            buildBundle(RepositoryChanges.of(repo.dir, "HEAD"), ExcludeRules.DEFAULT, "lib/core", listOf(classDir), emptyList(), outDir, sharedManifest = false, JACOCO, resetToken = null, coldspotVersion = COLDSPOT)

            // then
            assertEquals(false, File(outDir, "manifest.json").exists(), "a library wrote the shared manifest")
            val module: ModuleManifest = ManifestJson.readModule(File(outDir, "lib/core/manifest.json").readText())
            assertEquals(listOf(ModuleManifest.ModuleFile(feed.path, listOf("demo/FeedKt"), emptyList(), emptyList())), module.files)
            assertEquals(true, File(outDir, "lib/core/classes/demo/FeedKt.class").isFile)
        }

        @Test
        fun `a previous bundle's classes are replaced, not added to`() {
            // given a bundle built for an edit to Feed.kt, then that edit undone and Other.kt edited instead
            val feed = sourceFile("app/src/main/java/demo/Feed.kt")
            val other = sourceFile("app/src/main/java/demo/Other.kt")
            repo.commitAll("add")
            compiledClass("demo/FeedKt", "Feed.kt", 1..10)
            compiledClass("demo/OtherKt", "Other.kt", 1..10)
            feed.edit(3)
            build()
            feed.revert()
            other.edit(3)

            // when
            build()

            // then only Other's class is on disk
            assertEquals(listOf("demo/OtherKt"), shipped())
            assertEquals(listOf(other.path), manifest().files.map { it.path })
        }

        @Test
        fun `a class the change touches, compiled differently in two directories, is refused`() {
            // given UtilsKt compiled into two directories with different line tables, and line 3, which both have, edited
            val utils = sourceFile("lib/src/main/kotlin/com/a/Utils.kt")
            repo.commitAll("add")
            utils.edit(3)
            val otherDir = tmp.newFolder("classes2")
            compiledClass("com/a/UtilsKt", "Utils.kt", 1..5)
            classFile("com/a/UtilsKt").copyTo(File(otherDir, "com/a/UtilsKt.class").apply { parentFile.mkdirs() })
            compiledClass("com/a/UtilsKt", "Utils.kt", 1..6)

            // when
            val error = assertFailsWith<IllegalStateException> { build(classDirs = listOf(classDir, otherDir)) }

            // then the message names the class, both files and the way out
            assertContains(error.message!!, "com/a/UtilsKt is compiled differently in ${classFile("com/a/UtilsKt")} and ${File(otherDir, "com/a/UtilsKt.class")}")
            assertContains(error.message!!, "the change touches it")
            assertContains(error.message!!, "clean build")
        }

        @Test
        fun `a class the change does not touch, compiled differently in two directories, is read from the first and reported`() {
            // given UtilsKt in the first directory with lines 1-5 and in the second with lines 1-20, and line 20 edited:
            // touched by the second copy only, the one Hilt's aggregating task would append
            val utils = sourceFile("lib/src/main/kotlin/com/a/Utils.kt")
            repo.commitAll("add")
            utils.edit(20)
            val otherDir = tmp.newFolder("classes2")
            compiledClass("com/a/UtilsKt", "Utils.kt", 1..20)
            classFile("com/a/UtilsKt").copyTo(File(otherDir, "com/a/UtilsKt.class").apply { parentFile.mkdirs() })
            compiledClass("com/a/UtilsKt", "Utils.kt", 1..5) // classDir, the first, now holds this one

            // when
            val report = build(classDirs = listOf(classDir, otherDir))

            // then the first copy decided: nothing shipped, and the duplicate is on the report for the build to log
            assertEquals(emptyList(), shipped())
            assertEquals(listOf(utils.path), report.filesWithoutClasses)
            assertEquals(listOf(ConflictingClass("com/a/UtilsKt", kept = classFile("com/a/UtilsKt"), dropped = File(otherDir, "com/a/UtilsKt.class"))), report.conflicts)
        }

        @Test
        fun `the same class compiled identically in two directories is no conflict`() {
            // given the very same bytes in both directories
            val utils = sourceFile("lib/src/main/kotlin/com/a/Utils.kt")
            repo.commitAll("add")
            utils.edit(3)
            val otherDir = tmp.newFolder("classes2")
            compiledClass("com/a/UtilsKt", "Utils.kt", 1..5)
            classFile("com/a/UtilsKt").copyTo(File(otherDir, "com/a/UtilsKt.class").apply { parentFile.mkdirs() })

            // when
            val report = build(classDirs = listOf(classDir, otherDir))

            // then
            assertEquals(emptyList(), report.conflicts)
            assertEquals(listOf("com/a/UtilsKt"), shipped())
        }
    }

    /** Noise: changed files the rules leave out, never in silence. */
    class Exclusion : Fixture() {

        @Test
        fun `a changed test source is excluded by default, listed with its rule, and gets no classes`() {
            // given an edited unit test and a class compiled from it
            val test = sourceFile("app/src/test/java/demo/FeatureTest.kt")
            val feature = sourceFile("app/src/main/java/demo/Feature.kt")
            repo.commitAll("add")
            test.edit(3, 4)
            feature.edit(3)
            compiledClass("demo/FeatureTestKt", "FeatureTest.kt", 1..10)
            compiledClass("demo/FeatureKt", "Feature.kt", 1..10)

            // when
            val report = build()

            // then the test file is neither measured nor shipped, and the shared manifest says why
            val excluded = listOf(Manifest.ExcludedFile(test.path, changedLines = 2, rule = "**/src/test/**"))
            assertEquals(excluded, manifest().excluded)
            assertEquals(excluded, report.excluded)
            assertEquals(listOf(feature.path), manifest().files.map { it.path })
            assertEquals(listOf("demo/FeatureKt"), shipped())
            assertEquals(listOf(feature.path), report.classesByFile.keys.toList())
            assertEquals(emptyList(), report.filesWithoutClasses, "an excluded file is not a file without classes")
        }

        @Test
        fun `a rule of the caller's is charged by name, alongside the defaults`() {
            // given a generated file and a test file, both edited
            val generated = sourceFile("app/src/main/java/demo/GeneratedThing.kt")
            val test = sourceFile("app/src/test/java/demo/FeatureTest.kt")
            repo.commitAll("add")
            generated.edit(2)
            test.edit(2)

            // when
            build(excludes = ExcludeRules(ExcludeRules.DEFAULT_PATTERNS + "**/Generated*.kt"))

            // then
            assertEquals(
                listOf(
                    Manifest.ExcludedFile(generated.path, 1, "**/Generated*.kt"),
                    Manifest.ExcludedFile(test.path, 1, "**/src/test/**"),
                ),
                manifest().excluded,
            )
            assertEquals(emptyList(), manifest().files)
        }

        @Test
        fun `with no rules at all, nothing is excluded`() {
            // given an edited unit test
            val test = sourceFile("app/src/test/java/demo/FeatureTest.kt")
            repo.commitAll("add")
            test.edit(3)

            // when
            build(excludes = ExcludeRules(emptyList()))

            // then
            assertEquals(emptyList(), manifest().excluded)
            assertEquals(listOf(test.path), manifest().files.map { it.path })
        }
    }

    /** The base is the diff module's to choose; the manifest records it, and whether the tree is HEAD's. */
    class BaseAndHead : Fixture() {

        @Test
        fun `the manifest records the base the diff module chose`() {
            // given no base ref, and origin/HEAD pointing at origin/main at the initial commit, with a commit since
            val initial = repo.resolve("HEAD")
            repo.updateRef("refs/remotes/origin/main", initial)
            repo.linkRef("refs/remotes/origin/HEAD", "refs/remotes/origin/main")
            val feed = sourceFile("demo/Feed.kt", lines = 3)
            repo.commitAll("feature work")

            // when
            build(baseRef = null)

            // then
            assertEquals(Manifest.Base("origin/HEAD", initial.name, BaseSource.ORIGIN_HEAD), manifest().base)
            assertEquals(listOf(feed.path), manifest().files.map { it.path })
        }

        @Test
        fun `dirty means anything on disk differs from HEAD, sources or not`() {
            // given a base tag, a committed source edit since, and nothing uncommitted
            val feed = sourceFile("demo/Feed.kt")
            repo.write("app/src/main/res/layout/main.xml", "<layout/>\n")
            repo.commitAll("add")
            repo.tag("base")
            feed.edit(3)
            repo.commitAll("committed edit")

            // when built clean, and again once the layout XML has an uncommitted edit
            build("base")
            val clean = manifest().head
            repo.write("app/src/main/res/layout/main.xml", "<layout android:id=\"@+id/main\"/>\n")
            build("base")
            val withLayoutEdit = manifest().head

            // then an APK built from the second tree is not HEAD's, though the diff of sources is the same
            assertEquals(Manifest.Head(repo.resolve("HEAD").name, "main", dirty = false), clean)
            assertEquals(Manifest.Head(repo.resolve("HEAD").name, "main", dirty = true), withLayoutEdit)
        }

        @Test
        fun `the commits in the build are copied as the diff module lists them, cut at 50 and counted in full`() {
            // given 51 commits since the base
            repo.tag("base")
            val commits = (1..51).map { n ->
                repo.write("notes.txt", "$n\n")
                repo.commitAll("commit $n")
            }

            // when
            build("base")

            // then the newest 50, newest first, and the true count
            val inBuild = manifest().commits
            assertEquals(commits.asReversed().take(50).map { Manifest.Commit(it.name.take(7), it.name, it.shortMessage) }, inBuild.listed)
            assertEquals(51, inBuild.total)
        }
    }
}

/** Every line in every method's `LineNumberTable` of a class file, ascending. */
private fun lineNumbersOf(classBytes: ByteArray): List<Int> {
    val lines = TreeSet<Int>()
    ClassReader(classBytes).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitLineNumber(line: Int, start: Label) {
                        lines += line
                    }
                }
        },
        ClassReader.SKIP_FRAMES,
    )
    return lines.toList()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
