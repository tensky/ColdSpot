package id.tensky.coldspot.plugin

import id.tensky.coldspot.bundle.ExcludeRules
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import javax.inject.Inject

/** The `coldSpot { }` block of a module's build script, or of the convention plugin that applies ColdSpot to every module. */
public abstract class ColdSpotExtension @Inject constructor(providers: ProviderFactory) {
    /**
     * The git ref the working tree is diffed from: a branch (compared from its merge-base with HEAD), a tag, a SHA.
     * Optional: left unset, the diff module chooses `origin/HEAD`, then `origin/main`, then `origin/master`, and
     * fails naming this setting when the repository has none of them. `-Pcoldspot.base=<ref>` on the command line
     * overrides whatever is set here; see [effectiveBaseRef].
     */
    public abstract val baseRef: Property<String>

    /**
     * The build type ColdSpot instruments and ships its bundle with. Created from `debug` when the module has no
     * build type of that name; must be debuggable either way. Default `coverage`.
     */
    public abstract val buildTypeName: Property<String>

    /**
     * An application ID suffix for the coverage build type ColdSpot creates. Unset by default: the coverage build
     * then carries debug's application ID, and everything keyed on it (Firebase, Sign-In, Maps keys, push) keeps
     * working. Set it to install coverage builds next to debug ones. It cannot be combined with a build type the
     * module defines itself: that build type's own settings stand, and the build says so.
     */
    public abstract val applicationIdSuffix: Property<String>

    /**
     * Changed files that are noise, as Ant-style globs over work-tree-relative paths (`**` crosses directories, a
     * leading `**` matches at any depth). Defaults to [ExcludeRules.DEFAULT_PATTERNS]: test source sets, test
     * fixtures, `buildSrc`, `build-logic`. [exclude] adds to them; setting the property replaces them. An excluded
     * changed file is not measured, but the manifest lists it with the rule that matched, so nothing disappears.
     */
    public abstract val excludes: ListProperty<String>

    /**
     * The floating bubble inside the app that opens ColdSpot: on by default. It needs no permission and shows on the
     * app's own screens only; it can still be hidden while the app runs. Read in the application module; a library
     * module's setting has nothing to apply to.
     */
    public abstract val bubble: Property<Boolean>

    /**
     * A launcher icon of its own for ColdSpot, labelled "ColdSpot", next to the app's: off by default. Read in the
     * application module; a library module's setting has nothing to apply to.
     */
    public abstract val launcherIcon: Property<Boolean>

    /** Adds [patterns] to [excludes], the defaults included while nothing replaced them. */
    public fun exclude(vararg patterns: String) {
        // Not addAll: added to a property that holds nothing but its convention, the patterns would take the
        // convention's place, and the defaults would be gone with the first pattern a build adds.
        excludes.set(excludes.get() + patterns)
    }

    /** What the build actually diffs from: `-Pcoldspot.base` when given, otherwise [baseRef]; absent when neither is set. */
    public val effectiveBaseRef: Provider<String> = providers.gradleProperty(BASE_PROPERTY).orElse(baseRef)

    private companion object {
        const val BASE_PROPERTY = "coldspot.base"
    }
}
