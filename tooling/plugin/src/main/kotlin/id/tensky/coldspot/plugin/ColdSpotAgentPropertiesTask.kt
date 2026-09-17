package id.tensky.coldspot.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes the `jacoco-agent.properties` the APK must carry for offline instrumentation: JaCoCo's runtime reads it
 * from the classpath when the first probe fires, and without it opens `jacoco.exec` in the working directory,
 * which on Android is `/` and read-only, so the very first instrumented class fails to initialise. `output=none`
 * keeps the execution data in memory, where the app reads it through the agent's API (FINDINGS Finding 1).
 * AGP's own coverage does the same (`JacocoPropertiesTask`).
 */
@DisableCachingByDefault(because = "A few bytes; writing them is cheaper than a cache lookup")
public abstract class ColdSpotAgentPropertiesTask : DefaultTask() {
    /** A generated Java resources root holding `jacoco-agent.properties`. */
    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    @TaskAction
    public fun write() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        root.resolve("jacoco-agent.properties").writeText("output=none\n")
    }
}
