package id.tensky.coldspot.plugin

import id.tensky.coldspot.bundle.ExcludeRules
import id.tensky.coldspot.manifest.Manifest
import id.tensky.coldspot.bundle.buildBundle
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import java.io.File
import java.util.TreeMap
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The transform of a module's own classes for one variant, and the bundle the app reads at run time.
 *
 * What changed comes from the build's one [ColdSpotDiffService], which diffs the working tree against the base
 * once for every module; this task only scans its own classes and lets the bundle select the ones the change
 * touches. Those come out of [transformedJar] instrumented by JaCoCo's [Instrumenter] (offline access, as AGP's
 * own coverage would do), every other class and file unchanged. The selected classes' input bytes B, exactly
 * what the instrumenter read, ship as generated assets: `coldspot/<module>/manifest.json` and
 * `coldspot/<module>/classes/<vm/Name>.class` under [outputDir], and, in the application module alone,
 * `coldspot/manifest.json` describing the change as a whole ([sharedManifest]).
 *
 * The task always runs: what changed is a question for git, and Gradle cannot know when git's answer changes.
 * Its outputs are deterministic (no timestamps, entries in name order, bytes copied as they are), so when nothing
 * changed since the last build the tasks downstream, from dexing to packaging, stay up to date.
 *
 * All git work happens at execution time, in the service, never while the build is being configured.
 */
@DisableCachingByDefault(because = "The result depends on the git working tree, which Gradle cannot see")
public abstract class ColdSpotBundleTask : DefaultTask() {
    /** The build's diff, shared with every other module's task; see [ColdSpotDiffService]. */
    @get:ServiceReference(ColdSpotDiffService.NAME)
    public abstract val diffService: Property<ColdSpotDiffService>

    /** A directory inside the repository, the build's root; the work tree is found from it. Not an input: git state is what is always re-read. */
    @get:Internal
    public abstract val repoDir: DirectoryProperty

    /** The base ref, or absent to let the diff module choose one; see [ColdSpotExtension.effectiveBaseRef]. */
    @get:Input
    @get:Optional
    public abstract val baseRef: Property<String>

    /**
     * Whether the build runs on CI, the `CI` environment variable being `true` (DECISIONS.md): then the base must be
     * given, `-Pcoldspot.base` or `coldSpot { baseRef }`, and neither `origin/HEAD` nor a guess stands in. From a
     * provider of the environment, so that a configuration cached without it is not reused with it.
     */
    @get:Input
    public abstract val onCi: Property<Boolean>

    /** Where under `assets/coldspot/` this module's bundle goes: its project path as a folder. */
    @get:Input
    public abstract val moduleFolder: Property<String>

    /** The exclude patterns, defaults included; see [ColdSpotExtension.excludes]. */
    @get:Input
    public abstract val excludes: ListProperty<String>

    /** Whether this task also writes `coldspot/manifest.json`, the one manifest of the whole change: the application module's does. */
    @get:Input
    public abstract val sharedManifest: Property<Boolean>

    /** The Maven version of the JaCoCo whose instrumenter this task uses, `0.8.14`; see [Manifest.Jacoco]. */
    @get:Input
    public abstract val jacocoVersion: Property<String>

    /** `JaCoCo.VERSION` of that JaCoCo, the qualified build string the app compares with its own jacoco-core's. */
    @get:Input
    public abstract val jacocoBuild: Property<String>

    /**
     * `-Pcoldspot.freshSession`: the shared manifest gets a token no other build has, and the app wipes its saved
     * coverage once when it first sees it. Made at execution time, so that a cached configuration cannot repeat it.
     */
    @get:Input
    public abstract val freshSession: Property<Boolean>

    /** The plugin's own version, for the shared manifest to name; see [Manifest.coldspotVersion]. */
    @get:Input
    public abstract val coldspotVersion: Property<String>

    /** The module's classes as directories, as AGP hands them to a transform. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val classDirs: ListProperty<Directory>

    /** The module's classes as jars (the R class, an earlier transform's output). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val classJars: ListProperty<RegularFile>

    /**
     * The variant's compile classpath, where the bundle looks up annotation classes and supertypes the module
     * does not hold, to tell previews: a team's multipreview annotation lives in a shared module.
     */
    @get:Classpath
    public abstract val compileClasspath: ConfigurableFileCollection

    /** Every input class and file, the selected classes instrumented: what the rest of the build sees as the module's classes. */
    @get:OutputFile
    public abstract val transformedJar: RegularFileProperty

    /** The generated assets root; the bundle goes into its `coldspot/` folder. */
    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    public fun bundle() {
        val changes = diffService.get().changes(repoDir.get().asFile, baseRef.orNull, onCi.get())

        // Jars are unpacked so that the bundle's selection and the transform see one shape of input.
        val unpacked = temporaryDir.resolve("jars").also { it.deleteRecursively() }
        val dirs = classDirs.get().map { it.asFile } + classJars.get().mapIndexed { i, jar -> unpack(jar.asFile, unpacked.resolve(i.toString())) }

        val root = outputDir.get().asFile
        root.deleteRecursively()
        val report = buildBundle(
            changes, ExcludeRules(excludes.get()), moduleFolder.get(), dirs, compileClasspath.files.toList(), root.resolve(ASSETS_FOLDER),
            sharedManifest.get(), Manifest.Jacoco(jacocoVersion.get(), jacocoBuild.get()),
            resetToken = if (freshSession.get()) UUID.randomUUID().toString() else null,
            coldspotVersion = coldspotVersion.get(),
        )
        val selected = report.classesByFile.values.flatten().toHashSet()

        writeTransformedJar(transformedJar.get().asFile, dirs, selected)
        for (conflict in report.conflicts) {
            logger.info("ColdSpot: ${conflict.className} is compiled differently in ${conflict.kept} and ${conflict.dropped}; the first is what the build uses")
        }
        logger.info(
            "ColdSpot: ${selected.size} classes instrumented and bundled for ${report.classesByFile.size} changed files; " +
                "${report.filesWithoutClasses.size} changed files without a class here, ${report.ambiguous.size} ambiguous classes left out, " +
                "${report.excluded.size} changed files excluded, preview lines in ${report.previewLinesByFile.size} files, " +
                "${report.conflicts.size} classes compiled twice",
        )
    }

    private fun unpack(jar: File, into: File): File {
        ZipFile(jar).use { zip ->
            for (entry in zip.entries().asSequence().filterNot { it.isDirectory }) {
                val target = into.resolve(entry.name)
                check(target.canonicalPath.startsWith(into.canonicalPath + File.separator)) { "$jar: entry ${entry.name} escapes its directory" }
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { it.copyTo(target.outputStream()) }
            }
        }
        return into
    }

    /**
     * Every file of [dirs], by name order, the first occurrence of a path winning, as the bundle read them; [selected]
     * classes instrumented.
     */
    private fun writeTransformedJar(jar: File, dirs: List<File>, selected: Set<String>) {
        val files = TreeMap<String, File>()
        for (dir in dirs) {
            dir.walkTopDown().filter { it.isFile }.forEach { file -> files.putIfAbsent(file.relativeTo(dir).invariantSeparatorsPath, file) }
        }
        val instrumenter = Instrumenter(OfflineInstrumentationAccessGenerator())
        jar.parentFile.mkdirs()
        ZipOutputStream(jar.outputStream().buffered()).use { out ->
            for ((path, file) in files) {
                val bytes = file.readBytes()
                val vmName = path.removeSuffix(CLASS_SUFFIX)
                val content = if (path.endsWith(CLASS_SUFFIX) && vmName in selected) instrumenter.instrument(bytes, vmName) else bytes
                out.putNextEntry(ZipEntry(path).apply { time = FIXED_ENTRY_TIME })
                out.write(content)
                out.closeEntry()
            }
        }
    }

    public companion object {
        /** The folder under `assets/` that holds every module's bundle and the shared manifest. */
        public const val ASSETS_FOLDER: String = "coldspot"

        private const val CLASS_SUFFIX = ".class"

        /** 1980-01-01, the earliest a zip entry can carry: the output must not change with the clock. */
        private const val FIXED_ENTRY_TIME: Long = 315_532_800_000L
    }
}
