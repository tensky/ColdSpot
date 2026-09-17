package id.tensky.coldspot.bundle

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.TreeSet

/** A class file as the compiler left it: the attributes selection and preview detection rest on, and the bytes to ship. */
internal class CompiledClass(
    /** The VM name, e.g. `com/acme/FeedKt$lambda$1`. */
    val name: String,
    /** The `SourceFile` attribute, a bare file name such as `Feed.kt`; null when the compiler left it out. */
    val sourceFile: String?,
    /** The `SourceDebugExtension` attribute, kotlinc's SMAP (see [parseSmap]); null when there is none. */
    val smap: String?,
    /** Every line any method's `LineNumberTable` mentions; empty when no method has one. */
    val lines: Set<Int>,
    /** The class's own annotations, visible and invisible, as VM names (`androidx/compose/ui/tooling/preview/Preview`). */
    val annotations: List<String>,
    /** The superclass's VM name; null for `java/lang/Object` itself. */
    val superName: String?,
    /** The VM names of the interfaces the class declares. */
    val interfaces: List<String>,
    /** Every method, in class-file order. */
    val methods: List<CompiledMethod>,
    val bytes: ByteArray,
    val origin: File,
) {
    /** The package as a directory, `com/acme`; empty in the default package. */
    val packageDir: String get() = name.substringBeforeLast('/', "")

    /** The SMAP parsed, once; a class carrying one that cannot be read is an error naming the file. */
    val parsedSmap: Smap? by lazy {
        smap?.let { text ->
            try {
                parseSmap(text)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("$origin: ${e.message}", e)
            }
        }
    }
}

/** One method of a [CompiledClass]: the lines its `LineNumberTable` mentions and the annotations on it, visible and invisible. */
internal class CompiledMethod(val name: String, val lines: Set<Int>, val annotations: List<String>)

/** What [readClasses] found: one [CompiledClass] per name, and the names it met compiled differently more than once. */
internal data class ReadClasses(val classes: List<CompiledClass>, val conflicts: List<ConflictingClass>)

/**
 * A class found in two class directories with different bytes: the build compiled it twice. Hilt's aggregating
 * task does that to the application's `_GeneratedInjector` after an incremental edit (its javac runs with
 * `-parameters`, KSP's without), and AGP appends its output to the module's classes all the same. The copy from
 * the first directory is the one the bundle reads and the transform ships; [dropped] is ignored.
 */
public data class ConflictingClass(
    /** The VM name. */
    public val className: String,
    /** The class file read: the first directory's. */
    public val kept: File,
    /** The class file ignored. */
    public val dropped: File,
)

/**
 * Every `.class` under [classDirs], in directory order, the first directory holding a name winning: the same
 * order the transform writes its jar in, so what the bundle reads is what the APK runs. A class compiled into
 * two of them is unremarkable while the bytes agree, and a [ReadClasses.conflicts] entry otherwise; whether
 * that matters is for the caller to decide, by whether the class is one it ships.
 */
internal fun readClasses(classDirs: List<File>): ReadClasses {
    val byName = LinkedHashMap<String, CompiledClass>()
    val conflicts = ArrayList<ConflictingClass>()
    for (dir in classDirs) {
        for (file in dir.walkTopDown().filter { it.isFile && it.extension == "class" }) {
            val cls = readClass(file)
            val first = byName.putIfAbsent(cls.name, cls) ?: continue
            if (!first.bytes.contentEquals(cls.bytes)) conflicts += ConflictingClass(cls.name, kept = first.origin, dropped = cls.origin)
        }
    }
    return ReadClasses(byName.values.toList(), conflicts)
}

private fun readClass(file: File): CompiledClass {
    val bytes = file.readBytes()
    var name = ""
    var superName: String? = null
    var interfaces = emptyList<String>()
    var sourceFile: String? = null
    var smap: String? = null
    val lines = TreeSet<Int>()
    val annotations = ArrayList<String>()
    val methods = ArrayList<CompiledMethod>()
    val reader = object : ClassVisitor(Opcodes.ASM9) {
        override fun visit(version: Int, access: Int, className: String, signature: String?, superClass: String?, itfs: Array<String>?) {
            name = className
            superName = superClass
            interfaces = itfs?.toList() ?: emptyList()
        }

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
            smap = debug
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            annotations += vmNameOf(descriptor)
            return null
        }

        override fun visitMethod(access: Int, methodName: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
            val methodLines = TreeSet<Int>()
            val methodAnnotations = ArrayList<String>()
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                    methodAnnotations += vmNameOf(descriptor)
                    return null
                }

                override fun visitLineNumber(line: Int, start: Label) {
                    methodLines += line
                    lines += line
                }

                override fun visitEnd() {
                    methods += CompiledMethod(methodName, methodLines, methodAnnotations)
                }
            }
        }
    }
    ClassReader(bytes).accept(reader, ClassReader.SKIP_FRAMES)
    return CompiledClass(name, sourceFile, smap, lines, annotations, superName, interfaces, methods, bytes, file)
}

/** `Lcom/acme/Thing;` → `com/acme/Thing`. */
internal fun vmNameOf(descriptor: String): String = descriptor.removePrefix("L").removeSuffix(";")
