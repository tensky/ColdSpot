package id.tensky.coldspot.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes `coldSpot { bubble; launcherIcon }` as the two bool resources ColdSpot's runtime ships defaults for,
 * `coldspot_bubble` and `coldspot_launcher_icon`, into a generated resource directory of the application's
 * coverage variant: an app's resources win over a library's of the same name, so these are the values the APK
 * ends up with. The runtime's manifest enables its launcher alias by the second, and its bubble reads the first.
 *
 * Resources, not `resValues`: that build feature is off by default (AGP 9.3.2), and a plugin should not need a
 * team to switch it on. Not manifest placeholders either: a library whose manifest needs placeholders cannot be
 * merged by anything but the plugin that fills them in.
 */
@CacheableTask
public abstract class ColdSpotEntryPointsTask : DefaultTask() {
    /** [ColdSpotExtension.bubble]. */
    @get:Input
    public abstract val bubble: Property<Boolean>

    /** [ColdSpotExtension.launcherIcon]. */
    @get:Input
    public abstract val launcherIcon: Property<Boolean>

    /** A generated Android resources root holding `values/coldspot_config.xml`. */
    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    @TaskAction
    public fun write() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val values = root.resolve("values").apply { mkdirs() }
        values.resolve("coldspot_config.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- Written by ColdSpot's plugin from coldSpot { bubble; launcherIcon }. -->
            <resources>
                <bool name="coldspot_bubble">${bubble.get()}</bool>
                <bool name="coldspot_launcher_icon">${launcherIcon.get()}</bool>
            </resources>
            """.trimIndent() + "\n",
        )
    }
}
