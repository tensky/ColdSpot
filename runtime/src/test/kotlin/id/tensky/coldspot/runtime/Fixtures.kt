package id.tensky.coldspot.runtime

import id.tensky.coldspot.manifest.BaseSource
import id.tensky.coldspot.manifest.Manifest
import id.tensky.coldspot.manifest.MergedManifest
import id.tensky.coldspot.manifest.ModuleManifest
import id.tensky.coldspot.manifest.ShippedClass
import id.tensky.coldspot.manifest.merge
import id.tensky.coldspot.shaded.org.jacoco.core.JaCoCo
import id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataStore
import id.tensky.coldspot.shaded.org.jacoco.core.data.SessionInfoStore
import id.tensky.coldspot.shaded.org.jacoco.core.instr.Instrumenter
import id.tensky.coldspot.shaded.org.jacoco.core.runtime.LoggerRuntime
import id.tensky.coldspot.shaded.org.jacoco.core.runtime.RuntimeData
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

/**
 * Real JaCoCo over classes assembled with ASM, so that every line's instructions are known: the tests instrument
 * with JaCoCo's [Instrumenter] over a [LoggerRuntime], run the classes, and collect execution data the way the
 * agent would, through public API only. Not a line of Android.
 */
internal object Fixtures {
    /** A line of a method: [Plain] runs whole; [Branching] takes `flag` and skips half of its instructions when it is false. */
    sealed class Line(val number: Int)
    class Plain(number: Int) : Line(number)
    class Branching(number: Int) : Line(number)

    /**
     * A class with one `public static void run(boolean flag)` whose `LineNumberTable` holds exactly [lines], in
     * order; stamped with [sourceFile]. With [lineNumbers] false the table is left out altogether.
     */
    fun classBytes(name: String, sourceFile: String, lines: List<Line>, lineNumbers: Boolean = true): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, null, "java/lang/Object", null)
        writer.visitSource(sourceFile, null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(Z)V", null, null).apply {
            visitCode()
            for (line in lines) {
                val at = Label()
                visitLabel(at)
                if (lineNumbers) visitLineNumber(line.number, at)
                when (line) {
                    is Plain -> {
                        visitInsn(Opcodes.ICONST_1)
                        visitInsn(Opcodes.POP)
                    }
                    is Branching -> {
                        val skip = Label()
                        visitVarInsn(Opcodes.ILOAD, 0)
                        visitJumpInsn(Opcodes.IFEQ, skip)
                        visitInsn(Opcodes.ICONST_2)
                        visitInsn(Opcodes.POP)
                        visitLabel(skip)
                    }
                }
            }
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** Runs [classes] (name to bytes) instrumented, calling `run(flag)` on those in [calls], and collects the probes. */
    fun execute(classes: Map<String, ByteArray>, calls: Map<String, Boolean>): ExecutionDataStore {
        val runtime = LoggerRuntime()
        val data = RuntimeData()
        runtime.startup(data)
        val instrumenter = Instrumenter(runtime)
        val loader = object : ClassLoader(Fixtures::class.java.classLoader) {
            val defined = HashMap<String, Class<*>>()
            fun define(name: String, bytes: ByteArray): Class<*> = defined.getOrPut(name) { defineClass(name.replace('/', '.'), bytes, 0, bytes.size) }
        }
        for ((name, bytes) in classes) loader.define(name, instrumenter.instrument(bytes, name))
        for ((name, flag) in calls) loader.define(name, classes.getValue(name)).getMethod("run", Boolean::class.javaPrimitiveType).invoke(null, flag)
        val store = ExecutionDataStore()
        data.collect(store, SessionInfoStore(), false)
        runtime.shutdown()
        return store
    }

    fun shared(vararg files: Manifest.ChangedFile, excluded: List<Manifest.ExcludedFile> = emptyList(), jacocoBuild: String = JaCoCo.VERSION, resetToken: String? = null): Manifest = Manifest(
        Manifest.SCHEMA_VERSION,
        "0.1.0",
        Manifest.Base("origin/main", "0123", BaseSource.EXPLICIT),
        Manifest.Head("4567", "feature/x", dirty = false),
        Manifest.Commits(emptyList(), 0),
        Manifest.Jacoco("0.8.14", jacocoBuild),
        resetToken,
        files.toList(),
        excluded,
    )

    fun module(name: String, vararg files: ModuleManifest.ModuleFile): ModuleManifest = ModuleManifest(Manifest.SCHEMA_VERSION, name, files.toList())

    fun merged(shared: Manifest, vararg modules: ModuleManifest): MergedManifest = merge(shared, modules.toList())

    /** [store] as the agent hands its data over: JaCoCo's exec format, one session and the classes. */
    fun execData(store: ExecutionDataStore): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        val writer = id.tensky.coldspot.shaded.org.jacoco.core.data.ExecutionDataWriter(out)
        writer.visitSessionInfo(id.tensky.coldspot.shaded.org.jacoco.core.data.SessionInfo("launch", 1_000, 1_000))
        store.accept(writer)
    }.toByteArray()

    /** Analyses with [classes] as the shipped bytes, by module `app`. */
    fun analyze(manifest: MergedManifest, store: ExecutionDataStore, classes: Map<String, ByteArray>, since: Long? = null): Report =
        analyze(manifest, store, { shipped: ShippedClass -> classes[shipped.name] }, since)
}
