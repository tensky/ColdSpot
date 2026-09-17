package id.tensky.coldspot.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.BuildType
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.TestedExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import id.tensky.coldspot.bundle.ExcludeRules
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.jacoco.core.JaCoCo

/**
 * `io.github.tensky.coldspot`: prepares an Android module for ColdSpot. Applied to the application and to every
 * library module whose changed classes should be measured (decision C1: instrumentation is per module), normally
 * from the team's convention plugin.
 *
 * In an application or library module, the build type named by [ColdSpotExtension.buildTypeName] (`coverage`
 * by default) is made ready once the module's own `android {}` block has run: created from `debug` when the
 * module has none of that name, used as it is otherwise. Coverage is debug plus ColdSpot: a created build type
 * keeps debug's application ID (an application's, with debug's suffix; [ColdSpotExtension.applicationIdSuffix]
 * opts into another), and its dependency configurations extend debug's, so `debugImplementation`, `kspDebug`
 * and the like reach the coverage variant too. In every module the build type falls back to `debug` when a
 * dependency has no build type of that name, so leaving a module out of ColdSpot never breaks the modules that
 * depend on it. AGP's own coverage stays off in the build type ColdSpot creates: ColdSpot instruments what it
 * selects itself, so `testBuildType` is not touched and nobody's instrumented tests change. A build type the
 * module defines keeps its own settings; the one combination that cannot work, AGP's coverage on in the
 * `testBuildType`, is refused with the ways out. Release build types are never touched.
 *
 * Every variant of that build type gets a [ColdSpotBundleTask], `coldSpotBundle<Variant>`, registered as the
 * transform of the module's own classes (`ScopedArtifact.CLASSES`, PROJECT scope): the classes the bundle
 * selects come out instrumented with JaCoCo, every other class unchanged, and the selected classes' input bytes
 * ship as generated assets under `assets/coldspot/<module path>/`, so that modules never collide. The diff
 * itself is made once per build by the shared [ColdSpotDiffService]; each module's task only scans its own
 * classes, with the variant's compile classpath at hand to recognise previews by annotations another module
 * defines. The application module's task also writes `assets/coldspot/manifest.json`, the one description of
 * the change. Registered from the variant API, the transform runs before any `AsmClassVisitorFactory` (Hilt's
 * included), which AGP applies afterwards to its output. An application's coverage variant also gets JaCoCo's
 * agent runtime, at the exact version the instrumenter is, forced onto its runtime classpath, and the shared manifest
 * names that version: the analyser, jacoco-core relocated inside ColdSpot's runtime, is the instrumenter's twin, or
 * says it is not.
 *
 * ColdSpot's runtime, `io.github.tensky.coldspot:runtime` at the plugin's own version, goes onto the classpaths of
 * the application's coverage variant, and nowhere else: not into debug or release, not into library modules, which
 * only contribute classes. With it go the two entry points of [ColdSpotExtension], the bubble (on by default) and
 * the launcher icon (off by default), as resources written over the runtime's own defaults
 * ([ColdSpotEntryPointsTask]).
 *
 * ColdSpot is debug-only tooling: it ships class files and reads JaCoCo's agent inside the running app. An
 * application build type that is not debuggable is refused while the build is being configured, before it is
 * changed. On a project that is neither an application nor a library, only the `coldSpot` extension exists.
 */
public class ColdSpotPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(EXTENSION_NAME, ColdSpotExtension::class.java)
        extension.buildTypeName.convention(DEFAULT_BUILD_TYPE)
        extension.excludes.convention(ExcludeRules.DEFAULT_PATTERNS)
        extension.bubble.convention(true)
        extension.launcherIcon.convention(false)
        // One per build, whichever module registers it first; the tasks find it by name.
        project.gradle.sharedServices.registerIfAbsent(ColdSpotDiffService.NAME, ColdSpotDiffService::class.java) {}

        project.pluginManager.withPlugin(ANDROID_APPLICATION) {
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            // After the module's build script has had its say, before AGP locks the DSL: the one moment a plugin
            // may still add a build type.
            components.finalizeDsl { android ->
                val name = extension.buildTypeName.get()
                prepareApplicationBuildType(android, name, extension.applicationIdSuffix.orNull)
                extendDebugConfigurations(project, name)
            }
            project.afterEvaluate { extendDebugConfigurationsOnceMore(project, extension.buildTypeName.get()) }
            // Not a selector on the build type: its name is not final while this plugin is being applied.
            components.onVariants(components.selector().all()) { variant ->
                if (variant.buildType != extension.buildTypeName.get()) return@onVariants
                registerBundleTask(project, variant, extension, sharedManifest = true)
                addJacocoRuntime(project, variant)
                addAgentProperties(project, variant)
                addColdSpotRuntime(project, variant, extension)
            }
        }
        project.pluginManager.withPlugin(ANDROID_LIBRARY) {
            val components = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
            components.finalizeDsl { android ->
                val name = extension.buildTypeName.get()
                prepareBuildType(android, android.buildTypes, name)
                extendDebugConfigurations(project, name)
            }
            project.afterEvaluate { extendDebugConfigurationsOnceMore(project, extension.buildTypeName.get()) }
            components.onVariants(components.selector().all()) { variant ->
                if (variant.buildType == extension.buildTypeName.get()) registerBundleTask(project, variant, extension, sharedManifest = false)
            }
        }
    }

    private fun prepareApplicationBuildType(android: ApplicationExtension, name: String, applicationIdSuffix: String?) {
        val (buildType, created) = prepareBuildType(android, android.buildTypes, name)
        if (!buildType.isDebuggable) {
            throw GradleException(
                "ColdSpot: build type '$name' is not debuggable. ColdSpot is debug-only tooling (it ships class files and " +
                    "reads JaCoCo's agent inside the app), so its build type needs isDebuggable = true. Make '$name' " +
                    "debuggable, or point coldSpot { buildTypeName = \"...\" } at a build type that is.",
            )
        }
        when {
            !created -> if (applicationIdSuffix != null) {
                throw GradleException(
                    "ColdSpot: coldSpot { applicationIdSuffix = \"$applicationIdSuffix\" } cannot apply: build type '$name' is " +
                        "defined by this module, and ColdSpot never overrides a build type it did not create. Set applicationIdSuffix " +
                        "on that build type itself, or drop it from coldSpot { }.",
                )
            }
            // Otherwise debug's application ID, which initWith copied along with the suffix, so that everything keyed
            // on it (Firebase, Sign-In, Maps keys, push) keeps working.
            applicationIdSuffix != null -> buildType.applicationIdSuffix = applicationIdSuffix
        }
    }

    /**
     * The build type called [name], created from `debug` if the module has none, with `debug` among its fallbacks
     * either way, so that a dependency left out of ColdSpot still resolves. Says whether it was created.
     *
     * AGP's own coverage is switched off in the build type ColdSpot creates (C1): ColdSpot instruments what it
     * selects itself. A build type the module defines keeps every setting of its own, but one with
     * `enableAndroidTestCoverage` on that is also the `testBuildType` is refused: for that build type alone AGP
     * runs its JaCoCo after every scoped-artifact transform, so over ColdSpot's probes, and JaCoCo refuses
     * classes that already carry probes. With another `testBuildType` the flag is inert and left alone.
     */
    private fun <T : BuildType> prepareBuildType(android: TestedExtension, buildTypes: NamedDomainObjectContainer<T>, name: String): Pair<T, Boolean> {
        val existing = buildTypes.findByName(name)
        val buildType = existing ?: buildTypes.create(name).apply {
            initWith(buildTypes.getByName(DEBUG))
            enableAndroidTestCoverage = false
        }
        if (existing != null && existing.enableAndroidTestCoverage && android.testBuildType == name) {
            throw GradleException(
                "ColdSpot: build type '$name' is defined by this module with enableAndroidTestCoverage = true and is the " +
                    "testBuildType, so AGP would run its own JaCoCo over the classes ColdSpot already instrumented, and JaCoCo " +
                    "refuses instrumented input (\"Cannot process instrumented class\"). Choose one: let ColdSpot create its own " +
                    "build type (drop '$name' from buildTypes, or point coldSpot { buildTypeName = \"...\" } at a name this module " +
                    "does not define); set enableAndroidTestCoverage = false on '$name'; or make another build type the testBuildType.",
            )
        }
        if (DEBUG !in buildType.matchingFallbacks) buildType.matchingFallbacks += DEBUG
        return buildType to (existing == null)
    }

    /**
     * Coverage = debug + ColdSpot: every dependency configuration of the coverage build type extends its debug twin
     * (`coverageImplementation` extends `debugImplementation`, `demoCoverageRuntimeOnly` extends `demoDebugRuntimeOnly`,
     * `kspCoverage` extends `kspDebug`), because `initWith` copies a build type's properties, never its dependencies.
     * Wired as configurations appear, whichever of a pair comes second; only dependency scopes, never the resolvable
     * classpaths or the consumable elements AGP derives from them.
     */
    private fun extendDebugConfigurations(project: Project, buildType: String) {
        project.configurations.configureEach { added -> wireToDebugTwin(project, added, buildType) }
    }

    /**
     * The same, over every configuration, once the project is evaluated. A configuration's role is set after it is
     * created, which is when `configureEach` sees it, and AGP's variant configurations (`freeCoverageImplementation`)
     * pass for resolvable and consumable at that moment; by now every role is final.
     */
    private fun extendDebugConfigurationsOnceMore(project: Project, buildType: String) {
        project.configurations.forEach { wireToDebugTwin(project, it, buildType) }
    }

    private fun wireToDebugTwin(project: Project, configuration: Configuration, buildType: String) {
        val configurations = project.configurations
        val debugTwin = twinConfigurationName(configuration.name, buildType, DEBUG)
        if (debugTwin != null && configuration.isDependencyScope()) configurations.findByName(debugTwin)?.let { configuration.extendsFrom(it) }
        val coverageTwin = twinConfigurationName(configuration.name, DEBUG, buildType)
        if (coverageTwin != null) configurations.findByName(coverageTwin)?.takeIf { it.isDependencyScope() }?.extendsFrom(configuration)
    }

    /** AGP's buckets can be neither resolved nor consumed; kapt's and KSP's are resolved by their own tasks, but never consumed. */
    private fun Configuration.isDependencyScope(): Boolean =
        !isCanBeConsumed && (!isCanBeResolved || name.startsWith(KSP_PREFIX) || name.startsWith(KAPT_PREFIX))

    private fun registerBundleTask(project: Project, variant: Variant, extension: ColdSpotExtension, sharedManifest: Boolean) {
        val bundle = project.tasks.register(bundleTaskName(variant.name), ColdSpotBundleTask::class.java) { task ->
            task.description =
                "Instruments the changed classes of ${variant.name} and bundles their original bytes as assets, from the build's one diff."
            task.repoDir.set(project.layout.settingsDirectory)
            task.baseRef.set(extension.effectiveBaseRef)
            task.onCi.set(project.providers.environmentVariable(CI_VARIABLE).map { it == "true" }.orElse(false))
            task.moduleFolder.set(moduleFolder(project.path))
            task.excludes.set(extension.excludes)
            task.sharedManifest.set(sharedManifest)
            task.compileClasspath.from(variant.compileClasspath)
            task.jacocoVersion.set(jacocoMavenVersion())
            task.jacocoBuild.set(JaCoCo.VERSION)
            task.coldspotVersion.set(pluginVersion())
            task.freshSession.set(project.providers.gradleProperty(FRESH_SESSION_PROPERTY).map { true }.orElse(false))
        }
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .use(bundle)
            .toTransform(ScopedArtifact.CLASSES, ColdSpotBundleTask::classJars, ColdSpotBundleTask::classDirs, ColdSpotBundleTask::transformedJar)
        val assets = checkNotNull(variant.sources.assets) { "ColdSpot: variant ${variant.name} has no assets to add the bundle to" }
        assets.addGeneratedSourceDirectory(bundle, ColdSpotBundleTask::outputDir)
    }

    /**
     * JaCoCo's agent runtime on the coverage variant's runtime classpath, packaged only in that APK: what the
     * instrumented classes call, by name. At the instrumenter's version whatever else asks for another, as AGP does for
     * its own agent (`TaskManager.handleJacocoDependencies`): Gradle would otherwise let a newer JaCoCo from any
     * dependency win. ColdSpot's runtime depends on the agent too; jacoco-core the app never needs, the analyser being
     * relocated inside the runtime.
     */
    private fun addJacocoRuntime(project: Project, variant: Variant) {
        val agent = "org.jacoco:org.jacoco.agent:${jacocoMavenVersion()}:runtime"
        variant.runtimeConfiguration.dependencies.add(project.dependencies.create(agent))
        variant.runtimeConfiguration.resolutionStrategy.force(agent)
    }

    /**
     * ColdSpot's runtime on the coverage variant's classpaths, compile too, so that the app's coverage source set can
     * call `ColdSpot.open(context)`, and the entry points as resources of the variant, which win over the defaults
     * the runtime ships: see [ColdSpotEntryPointsTask].
     */
    private fun addColdSpotRuntime(project: Project, variant: Variant, extension: ColdSpotExtension) {
        val runtime = "$RUNTIME_MODULE:${pluginVersion()}"
        variant.compileConfiguration.dependencies.add(project.dependencies.create(runtime))
        variant.runtimeConfiguration.dependencies.add(project.dependencies.create(runtime))
        val entryPoints = project.tasks.register("coldSpotEntryPoints" + variant.name.replaceFirstChar { it.uppercase() }, ColdSpotEntryPointsTask::class.java) { task ->
            task.description = "Writes coldSpot { bubble; launcherIcon } as resources of ${variant.name}, over the defaults of ColdSpot's runtime."
            task.bubble.set(extension.bubble)
            task.launcherIcon.set(extension.launcherIcon)
        }
        val resources = checkNotNull(variant.sources.res) { "ColdSpot: variant ${variant.name} has no Android resources to add the entry points to" }
        resources.addGeneratedSourceDirectory(entryPoints, ColdSpotEntryPointsTask::outputDir)
    }

    /** This plugin's version, from the resource its build writes: the runtime's version too. */
    private fun pluginVersion(): String = versionProperty("version")

    /** A value of the `version.properties` resource the plugin's build writes. */
    private fun versionProperty(key: String): String {
        val properties = ColdSpotPlugin::class.java.getResourceAsStream("version.properties")
        val value = properties?.use { java.util.Properties().apply { load(it) }.getProperty(key) }
        return checkNotNull(value) { "ColdSpot: the plugin's version.properties has no $key" }
    }

    /** The `jacoco-agent.properties` resource the offline runtime needs at its first probe; see [ColdSpotAgentPropertiesTask]. */
    private fun addAgentProperties(project: Project, variant: Variant) {
        val properties = project.tasks.register("coldSpotAgentProperties" + variant.name.replaceFirstChar { it.uppercase() }, ColdSpotAgentPropertiesTask::class.java) {
            it.description = "Writes the jacoco-agent.properties the ${variant.name} APK carries for JaCoCo's offline runtime."
        }
        val resources = checkNotNull(variant.sources.resources) { "ColdSpot: variant ${variant.name} has no Java resources to add jacoco-agent.properties to" }
        resources.addGeneratedSourceDirectory(properties, ColdSpotAgentPropertiesTask::outputDir)
    }

    /**
     * The Maven version of the jacoco-core the instrumenter comes from, as the plugin's build wrote it: [JaCoCo.VERSION]
     * is the qualified build version (`0.8.14.202510111229`), which is not a coordinate, and the shaded jar keeps no
     * pom.properties of jacoco-core's. It must be the start of [JaCoCo.VERSION], or the build packed another JaCoCo.
     */
    private fun jacocoMavenVersion(): String {
        val version = versionProperty("jacoco")
        check(JaCoCo.VERSION.startsWith("$version.")) { "ColdSpot: the plugin was built for jacoco-core $version, but carries JaCoCo ${JaCoCo.VERSION}" }
        return version
    }

    /** `coldSpotBundleCoverage`, `coldSpotBundleFreeCoverage`: AGP's own verb-then-variant shape. */
    private fun bundleTaskName(variantName: String): String = "coldSpotBundle" + variantName.replaceFirstChar { it.uppercase() }

    /** `:feature` -> `feature`, `:lib:core` -> `lib/core`, the root project -> `root`. */
    private fun moduleFolder(projectPath: String): String = projectPath.removePrefix(":").replace(':', '/').ifEmpty { "root" }

    private companion object {
        const val EXTENSION_NAME = "coldSpot"
        const val DEFAULT_BUILD_TYPE = "coverage"
        const val DEBUG = "debug"
        const val KSP_PREFIX = "ksp"
        const val FRESH_SESSION_PROPERTY = "coldspot.freshSession"

        /** Set to `true` by GitHub Actions, GitLab CI and most others: a build on CI (DECISIONS.md "Base resolution on CI"). */
        const val CI_VARIABLE = "CI"
        const val KAPT_PREFIX = "kapt"
        const val RUNTIME_MODULE = "io.github.tensky.coldspot:runtime"
        const val ANDROID_APPLICATION = "com.android.application"
        const val ANDROID_LIBRARY = "com.android.library"
    }
}
