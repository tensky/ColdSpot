package id.tensky.coldspot.plugin

import id.tensky.coldspot.bundle.RepositoryChanges
import id.tensky.coldspot.manifest.BaseSource
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place a build asks git what changed. Every module's bundle task holds a reference to this service and
 * asks it for the [RepositoryChanges] of its repository and base; the first task to ask pays for the diff, the
 * others get the same instance back. A build service lives for one build invocation, so the next build asks git
 * afresh, as it must: Gradle cannot see the working tree change.
 *
 * Keyed by repository, base ref and whether the build runs on CI, so that modules disagreeing on `coldSpot { baseRef }`
 * each get what they asked for rather than whichever asked first. In practice the key is one.
 *
 * A base nobody gave, where there is no `origin/HEAD` either, is a guess (`origin/main`, then `origin/master`): right
 * for a branch meant for main, wrong for one meant for another branch. The manifest and the app's header say it was
 * guessed, and the build warns. On CI, where nobody looks at a header, the diff module refuses any base not given.
 */
public abstract class ColdSpotDiffService : BuildService<BuildServiceParameters.None> {
    private val changes = ConcurrentHashMap<Key, RepositoryChanges>()

    /**
     * The changes of the repository containing [repoDir] against [baseRef] (null: the diff module's choice), computed
     * once; [onCi], a build on CI, takes no base it was not given.
     */
    public fun changes(repoDir: File, baseRef: String?, onCi: Boolean): RepositoryChanges =
        changes.computeIfAbsent(Key(repoDir.canonicalPath, baseRef, onCi)) {
            val started = System.nanoTime()
            RepositoryChanges.of(repoDir, baseRef, onCi).also {
                val millis = (System.nanoTime() - started) / 1_000_000
                LOGGER.info("ColdSpot: diffed ${it.workTree} against ${it.base.ref} (${it.base.sha.take(7)}) in $millis ms: ${it.changedLines.size} changed files")
                if (it.base.source == BaseSource.GUESSED) {
                    LOGGER.warn(
                        "ColdSpot: no base was given and there is no origin/HEAD: comparing with ${it.base.ref}, a guess. Pass " +
                            "-Pcoldspot.base=<ref>, or set coldSpot { baseRef }, to compare with another branch.",
                    )
                }
            }
        }

    private data class Key(val repoDir: String, val baseRef: String?, val onCi: Boolean)

    public companion object {
        /** The name the service is registered under, for `@ServiceReference`. */
        public const val NAME: String = "coldSpotDiff"

        private val LOGGER = Logging.getLogger(ColdSpotDiffService::class.java)
    }
}
