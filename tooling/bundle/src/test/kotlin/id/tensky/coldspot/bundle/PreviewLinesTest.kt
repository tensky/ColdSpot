package id.tensky.coldspot.bundle

import id.tensky.coldspot.manifest.ModuleManifest
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

/**
 * Preview lines: changed lines that Compose previews hold, which never run in the app and must be neutral,
 * never red, with the reason. Against class files assembled with ASM, so that every annotation, line table
 * and SMAP a verdict rests on is spelled out. Every test reads as **given** a changed file and its classes
 * (and what the compile classpath holds), **when** the bundle is built, **then** these lines are previews.
 */
class PreviewLinesTest : BuildBundleTest.Fixture() {
    private val greeting = "app/src/main/java/demo/Greeting.kt"

    @Test
    fun `a @Preview function's changed lines are preview lines, and the composable next to it stays measured`() {
        // given Greeting on lines 3-6 and GreetingPreview, annotated @Preview, on 10-14; both edited
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 4, 12)
        classWith("demo/GreetingKt", "Greeting.kt", Method("Greeting", 3..6), Method("GreetingPreview", 10..14, PREVIEW))

        // when
        val report = build()

        // then line 12 is the preview's, line 4 is not, and the class ships as before
        val previews = listOf(ModuleManifest.PreviewLines(listOf(12), "@Preview on GreetingPreview"))
        assertEquals(listOf(ModuleManifest.ModuleFile(greeting, listOf("demo/GreetingKt"), emptyList(), previews)), moduleManifest().files)
        assertEquals(mapOf(greeting to previews), report.previewLinesByFile)
    }

    @Test
    fun `several @Preview on one function, which arrive as a Preview Container, count as one`() {
        // given a function with two @Preview, which kotlinc stores as @Preview.Container
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 12)
        classWith("demo/GreetingKt", "Greeting.kt", Method("GreetingPreview", 10..14, PREVIEW_CONTAINER))

        // when
        build()

        // then
        assertEquals(listOf(ModuleManifest.PreviewLines(listOf(12), "@Preview on GreetingPreview")), moduleManifest().files.single().previews)
    }

    @Test
    fun `a multipreview annotation from the compile classpath counts, from a directory or a jar`() {
        // given @ThemePreviews, itself annotated @Preview, compiled into another module, and a function carrying it
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 12)
        val classpathDir = tmp.newFolder("classpath")
        annotationType("demo/ui/ThemePreviews", listOf(PREVIEW_CONTAINER), into = classpathDir)
        classWith("demo/GreetingKt", "Greeting.kt", Method("GreetingThemePreview", 10..14, "demo/ui/ThemePreviews", visible = true))

        // when built with the directory on the classpath, then with the same classes in a jar
        build(classpath = listOf(classpathDir))
        val fromDir = moduleManifest().files.single().previews
        build(classpath = listOf(jarOf(classpathDir)))
        val fromJar = moduleManifest().files.single().previews

        // then
        val expected = listOf(ModuleManifest.PreviewLines(listOf(12), "@ThemePreviews on GreetingThemePreview (@Preview via @ThemePreviews)"))
        assertEquals(expected, fromDir)
        assertEquals(expected, fromJar)
    }

    @Test
    fun `a multipreview annotated with another multipreview counts, and the reason names the chain`() {
        // given @AllPreviews annotated @ThemePreviews annotated @Preview, across the classpath
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 12)
        val classpathDir = tmp.newFolder("classpath")
        annotationType("demo/ui/ThemePreviews", listOf(PREVIEW_CONTAINER), into = classpathDir)
        annotationType("demo/ui/AllPreviews", listOf("demo/ui/ThemePreviews"), visible = true, into = classpathDir)
        classWith("demo/GreetingKt", "Greeting.kt", Method("GreetingPreview", 10..14, "demo/ui/AllPreviews", visible = true))

        // when
        build(classpath = listOf(classpathDir))

        // then
        assertEquals(
            listOf(ModuleManifest.PreviewLines(listOf(12), "@AllPreviews on GreetingPreview (@Preview via @AllPreviews, @ThemePreviews)")),
            moduleManifest().files.single().previews,
        )
    }

    @Test
    fun `a multipreview the module defines itself is found among its own classes, with no classpath at all`() {
        // given @ThemePreviews compiled next to the function that uses it
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 12)
        annotationType("demo/ThemePreviews", listOf(PREVIEW), into = classDir)
        classWith("demo/GreetingKt", "Greeting.kt", Method("GreetingPreview", 10..14, "demo/ThemePreviews", visible = true))

        // when
        build(classpath = emptyList())

        // then
        assertEquals(listOf(ModuleManifest.PreviewLines(listOf(12), "@ThemePreviews on GreetingPreview (@Preview via @ThemePreviews)")), moduleManifest().files.single().previews)
    }

    @Test
    fun `an annotation that leads to no preview, or that is nowhere to be found, makes none`() {
        // given @Composable, not on the classpath, and @Marker, on it but annotated with nothing of note
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 4, 12)
        val classpathDir = tmp.newFolder("classpath")
        annotationType("demo/ui/Marker", listOf("java/lang/annotation/Documented"), visible = true, into = classpathDir)
        classWith("demo/GreetingKt", "Greeting.kt", Method("Greeting", 3..6, "androidx/compose/runtime/Composable"), Method("Marked", 10..14, "demo/ui/Marker", visible = true))

        // when
        val report = build(classpath = listOf(classpathDir))

        // then
        assertEquals(emptyList(), moduleManifest().files.single().previews)
        assertEquals(emptyMap(), report.previewLinesByFile)
    }

    @Test
    fun `annotations that annotate each other are cut, not followed for ever, and the way round the cycle still counts`() {
        // given @A annotated @B and @Preview, @B annotated @A: a cycle with a preview on it; and @C, @D annotating each other with none
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 4, 8, 12, 16)
        val classpathDir = tmp.newFolder("classpath")
        annotationType("demo/ui/A", listOf("demo/ui/B", PREVIEW), visible = true, into = classpathDir)
        annotationType("demo/ui/B", listOf("demo/ui/A"), visible = true, into = classpathDir)
        annotationType("demo/ui/C", listOf("demo/ui/D"), visible = true, into = classpathDir)
        annotationType("demo/ui/D", listOf("demo/ui/C"), visible = true, into = classpathDir)
        classWith(
            "demo/GreetingKt", "Greeting.kt",
            // A is met first, and looks into B before it finds @Preview: B's way back into A is cut there, which must not count as B's verdict
            Method("ViaA", 3..5, "demo/ui/A", visible = true),
            Method("ViaB", 7..9, "demo/ui/B", visible = true),
            Method("ViaC", 11..13, "demo/ui/C", visible = true),
            Method("Plain", 15..17),
        )

        // when
        build(classpath = listOf(classpathDir))

        // then both ways round the preview cycle count, the other cycle and the plain function do not
        assertEquals(
            listOf(
                ModuleManifest.PreviewLines(listOf(4), "@A on ViaA (@Preview via @A)"),
                ModuleManifest.PreviewLines(listOf(8), "@B on ViaB (@Preview via @B, @A)"),
            ),
            moduleManifest().files.single().previews,
        )
    }

    @Test
    fun `a lambda compiled into another class is a preview line when it sits inside the preview function`() {
        // given GreetingPreview on 10-14, whose own lines are the signature, the call and the closing brace, and its body
        // lambda compiled into GreetingKt$GreetingPreview$1 with lines 12-13; only the lambda edited
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 13)
        classWith("demo/GreetingKt", "Greeting.kt", Method("GreetingPreview", listOf(10, 11, 14), PREVIEW))
        classWith("demo/GreetingKt\$GreetingPreview\$1", "Greeting.kt", Method("invoke", 12..13))

        // when
        val report = build()

        // then the line is the preview's by its range, though the annotated class itself is untouched and does not ship
        assertEquals(listOf(ModuleManifest.PreviewLines(listOf(13), "@Preview on GreetingPreview")), moduleManifest().files.single().previews)
        assertEquals(mapOf(greeting to listOf("demo/GreetingKt\$GreetingPreview\$1")), report.classesByFile)
    }

    @Test
    fun `code inlined into a preview, which the SMAP numbers past the file, does not stretch its range`() {
        // given a preview on lines 10-11 whose inlined code sits on 21-22, mapped by the SMAP to Other.kt; lines 11 and 15 edited
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 11, 15)
        val smap = "SMAP\nGreeting.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Greeting.kt\ndemo/GreetingKt\n+ 2 Other.kt\ndemo/OtherKt\n*L\n1#1,20:1\n5#2,3:21\n*E\n"
        classWith("demo/GreetingKt", "Greeting.kt", Method("GreetingPreview", listOf(10, 11, 21, 22), PREVIEW), Method("Other", 15..16), smap = smap)

        // when
        build()

        // then the preview reaches to line 11, not to 22
        assertEquals(listOf(ModuleManifest.PreviewLines(listOf(11), "@Preview on GreetingPreview")), moduleManifest().files.single().previews)
    }

    @Test
    fun `a PreviewParameterProvider is preview-only over its own lines, directly or through a supertype on the classpath`() {
        // given Names implementing the interface itself, MoreNames extending a provider from another module, and Greeting between them
        sourceFile(greeting)
        repo.commitAll("add")
        editLines(greeting, 4, 8, 12)
        val classpathDir = tmp.newFolder("classpath")
        classWith("demo/ui/BaseProvider", "BaseProvider.kt", Method("<init>", 1..1), interfaces = listOf(PREVIEW_PARAMETER_PROVIDER), into = classpathDir)
        classWith("demo/Names", "Greeting.kt", Method("<init>", 3..3), Method("getValues", 4..5), interfaces = listOf(PREVIEW_PARAMETER_PROVIDER))
        classWith("demo/GreetingKt", "Greeting.kt", Method("Greeting", 7..9))
        classWith("demo/MoreNames", "Greeting.kt", Method("<init>", 11..11), Method("getValues", 12..13), superName = "demo/ui/BaseProvider")

        // when
        build(classpath = listOf(classpathDir))

        // then
        assertEquals(
            listOf(
                ModuleManifest.PreviewLines(listOf(4), "Names implements PreviewParameterProvider"),
                ModuleManifest.PreviewLines(listOf(12), "MoreNames implements PreviewParameterProvider"),
            ),
            moduleManifest().files.single().previews,
        )
    }

    /** The call under test with a compile classpath; see [BuildBundleTest.Fixture.build]. */
    private fun build(classpath: List<File> = emptyList()): BundleReport =
        buildBundle(RepositoryChanges.of(repo.dir, "HEAD"), ExcludeRules.DEFAULT, MODULE, listOf(classDir), classpath, outDir, sharedManifest = true, JACOCO, resetToken = null, coldspotVersion = "0.0.0")

    /** A method with its own `LineNumberTable` and, optionally, one annotation, visible (runtime retention) or not (binary). */
    private class Method(val name: String, val lines: List<Int>, val annotation: String? = null, val visible: Boolean = false) {
        constructor(name: String, lines: IntRange, annotation: String? = null, visible: Boolean = false) : this(name, lines.toList(), annotation, visible)
    }

    /** A class of [methods], stamped with [sourceFile] (and [smap]), written under [into]. */
    private fun classWith(
        name: String,
        sourceFile: String,
        vararg methods: Method,
        interfaces: List<String> = emptyList(),
        superName: String = "java/lang/Object",
        smap: String? = null,
        into: File = classDir,
    ) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, null, superName, interfaces.toTypedArray())
        writer.visitSource(sourceFile, smap)
        for (method in methods) {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, method.name, "()V", null, null).apply {
                method.annotation?.let { visitAnnotation("L$it;", method.visible).visitEnd() }
                visitCode()
                for (line in method.lines) {
                    val at = Label()
                    visitLabel(at)
                    visitLineNumber(line, at)
                    visitInsn(Opcodes.NOP)
                }
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        File(into, "$name.class").apply { parentFile.mkdirs() }.writeBytes(writer.toByteArray())
    }

    /** An annotation type carrying [metaAnnotations], visible or not, written under [into]. */
    private fun annotationType(name: String, metaAnnotations: List<String>, visible: Boolean = false, into: File) {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ANNOTATION or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            name, null, "java/lang/Object", arrayOf("java/lang/annotation/Annotation"),
        )
        for (meta in metaAnnotations) writer.visitAnnotation("L$meta;", visible).visitEnd()
        writer.visitEnd()
        File(into, "$name.class").apply { parentFile.mkdirs() }.writeBytes(writer.toByteArray())
    }

    /** Every file under [dir] in a jar next to it. */
    private fun jarOf(dir: File): File {
        val jar = File(dir.parentFile, "${dir.name}.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            for (file in dir.walkTopDown().filter { it.isFile }) {
                zip.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
        return jar
    }

    private companion object {
        const val PREVIEW = "androidx/compose/ui/tooling/preview/Preview"
        const val PREVIEW_CONTAINER = "androidx/compose/ui/tooling/preview/Preview\$Container"
        const val PREVIEW_PARAMETER_PROVIDER = "androidx/compose/ui/tooling/preview/PreviewParameterProvider"
    }
}
