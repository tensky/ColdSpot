package id.tensky.coldspot.bundle

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Compose previews never run in the app, so their lines would stay red for ever: they are neutral, and the
 * manifest says why. This finds them in a file's compiled classes.
 *
 * A method is a preview when it carries `@Preview` (or `@Preview.Container`, several of them) itself, or any
 * annotation whose class is, through any number of steps, annotated with one: a multipreview such as an app's
 * own `@DevicePreviews`. Annotation classes are found among the module's own classes first, then on the compile
 * classpath, since a team's multipreviews live in a shared module. Both visible and invisible annotations
 * count: `@Preview` has binary retention, multipreviews usually runtime. Only annotations actually met on a
 * method are resolved, each once, and a cycle among annotation classes is cut rather than followed.
 *
 * A preview's lines are its method's own, first to last, from the `LineNumberTable` with inlined code
 * left out by the SMAP: everything lexically inside, lambdas included, whichever class they compiled into.
 * A class implementing `PreviewParameterProvider`, directly or through its supertypes, is preview-only too,
 * over its own lines.
 */
internal class PreviewFinder(private val own: Map<String, CompiledClass>, private val classpath: ClassLookup) {
    private val summaries = HashMap<String, ClassSummary?>()
    private val chains = HashMap<String, List<String>?>()
    private val providers = HashMap<String, Boolean>()

    /** The preview ranges of [cls], in line order: each method's, then the class's own if it is a parameter provider. */
    fun previewRangesOf(cls: CompiledClass): List<PreviewRange> {
        val ranges = ArrayList<PreviewRange>()
        for (method in cls.methods) {
            val chain = method.annotations.firstNotNullOfOrNull { annotation -> chainOf(annotation, emptySet())?.let { annotation to it } } ?: continue
            val lines = ownLines(cls, method.lines)
            if (lines.isEmpty()) continue
            val (annotation, via) = chain
            val reason = if (via.isEmpty()) "@Preview on ${method.name}" else "@${simpleName(annotation)} on ${method.name} (@Preview via ${via.joinToString { "@" + simpleName(it) }})"
            ranges += PreviewRange(lines.min()..lines.max(), reason)
        }
        if (implementsProvider(cls.name, emptySet())) {
            val lines = ownLines(cls, cls.lines)
            if (lines.isNotEmpty()) ranges += PreviewRange(lines.min()..lines.max(), "${simpleName(cls.name)} implements PreviewParameterProvider")
        }
        return ranges.sortedBy { it.lines.first }
    }

    /**
     * The multipreview annotations from [annotation] down to the one carrying `@Preview`, empty when [annotation]
     * is `@Preview` itself; null when it leads to no preview. Remembered once known for sure: a verdict reached
     * only by cutting a cycle is not, so that the other way round the cycle can still be found.
     */
    private fun chainOf(annotation: String, visiting: Set<String>): List<String>? {
        if (annotation == PREVIEW || annotation == PREVIEW_CONTAINER) return emptyList()
        if (annotation in chains) return chains[annotation]
        if (annotation in visiting) return null // a cycle: cut here, and not remembered
        val summary = summaryOf(annotation)
        if (summary == null) {
            chains[annotation] = null // not on the classpath: nothing to follow
            return null
        }
        var definite = true
        for (meta in summary.annotations) {
            val below = chainOf(meta, visiting + annotation)
            if (below != null) return (listOf(annotation) + below).also { chains[annotation] = it }
            if (meta !in chains) definite = false // its null came from a cut cycle
        }
        if (definite) chains[annotation] = null
        return null
    }

    private fun implementsProvider(className: String, visiting: Set<String>): Boolean {
        providers[className]?.let { return it }
        if (className in visiting) return false
        val summary = summaryOf(className) ?: return false.also { providers[className] = false }
        val supertypes = summary.interfaces + listOfNotNull(summary.superName)
        val result = PREVIEW_PARAMETER_PROVIDER in supertypes || supertypes.any { implementsProvider(it, visiting + className) }
        providers[className] = result
        return result
    }

    private fun summaryOf(className: String): ClassSummary? {
        if (className in summaries) return summaries[className]
        val summary = own[className]?.let { ClassSummary(it.annotations, it.superName, it.interfaces) }
            ?: classpath.find(className)?.let(::summarise)
        summaries[className] = summary
        return summary
    }

    /**
     * [lines] that are the class's own source lines: with an SMAP, those it maps to the same line of the class's
     * own file, which leaves out code inlined from elsewhere, numbered past the end of the file; without one, all.
     */
    private fun ownLines(cls: CompiledClass, lines: Set<Int>): Set<Int> {
        val smap = cls.parsedSmap ?: return lines
        return lines.filterTo(LinkedHashSet()) { line ->
            val source = smap.sourceOf(line) ?: return@filterTo true
            source.line == line && smap.files[source.fileId]?.name == cls.sourceFile
        }
    }

    private fun simpleName(vmName: String): String = vmName.substringAfterLast('/').substringAfterLast('$')

    private companion object {
        const val PREVIEW = "androidx/compose/ui/tooling/preview/Preview"
        const val PREVIEW_CONTAINER = "androidx/compose/ui/tooling/preview/Preview\$Container"
        const val PREVIEW_PARAMETER_PROVIDER = "androidx/compose/ui/tooling/preview/PreviewParameterProvider"
    }
}

/** Lines of a changed file that a preview holds, first to last, and the preview that does. */
internal class PreviewRange(val lines: IntRange, val reason: String)

/** What preview detection needs of a class it does not ship: its annotations and supertypes. */
private class ClassSummary(val annotations: List<String>, val superName: String?, val interfaces: List<String>)

private fun summarise(bytes: ByteArray): ClassSummary {
    val annotations = ArrayList<String>()
    var superName: String? = null
    var interfaces = emptyList<String>()
    val reader = object : ClassVisitor(Opcodes.ASM9) {
        override fun visit(version: Int, access: Int, name: String, signature: String?, superClass: String?, itfs: Array<String>?) {
            superName = superClass
            interfaces = itfs?.toList() ?: emptyList()
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            annotations += vmNameOf(descriptor)
            return null
        }
    }
    ClassReader(bytes).accept(reader, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return ClassSummary(annotations, superName, interfaces)
}
