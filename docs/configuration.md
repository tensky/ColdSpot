# Configuration

Every setting ColdSpot has, where to put it, and every way to reach ColdSpot from outside the app.

- [Where the settings go](#where-the-settings-go)
- [The coldSpot block](#the-coldspot-block)
- [The coverage build type](#the-coverage-build-type)
- [Exclude rules](#exclude-rules)
- [Entry points](#entry-points)
- [Gradle properties and environment](#gradle-properties-and-environment)
- [Runtime API](#runtime-api)
- [adb broadcasts](#adb-broadcasts)
- [What the plugin adds to a module](#what-the-plugin-adds-to-a-module)

## Where the settings go

Every module that applies ColdSpot has its own `coldSpot` settings, so where a setting is written
decides which modules get it.

| settings | where | why |
|---|---|---|
| `baseRef`, `buildTypeName`, `excludes` / `exclude(...)` | the convention plugins that apply ColdSpot, so that every module gets the same values | each module diffs, builds and excludes by its own values |
| `applicationIdSuffix`, `bubble`, `launcherIcon` | the application module | only the application module reads them |

**In a module's `build.gradle.kts`**, write the `coldSpot { }` block. It is there whether the module
applies ColdSpot itself or a convention plugin does:

```kotlin
// app/build.gradle.kts
coldSpot {
    applicationIdSuffix = ".coverage"
    launcherIcon = true
}
```

**In a convention plugin written as a Kotlin class**, there is no `coldSpot { }` block. Configure
the extension by its type, right after applying the plugin, in the convention plugin for application
modules and in the one for Android library modules:

```kotlin
import id.tensky.coldspot.plugin.ColdSpotExtension

apply(plugin = "io.github.tensky.coldspot")
extensions.configure<ColdSpotExtension> {
    baseRef.set("origin/develop")
    exclude("**/generated/**")
}
```

`baseRef = "origin/develop"` compiles there too, with `import org.gradle.kotlin.dsl.assign`.

**In a convention plugin written as a script** (`*.gradle.kts`) that has ColdSpot in its own
`plugins { }` block, the `coldSpot { }` block works as it does in a module.

**The base alone** needs no convention plugin: `coldspot.base=<ref>` in `gradle.properties` sets it
for every module. See [Gradle properties and environment](#gradle-properties-and-environment).

## The coldSpot block

| setting | default | what it does |
|---|---|---|
| [`baseRef`](#baseref) | unset: `origin/HEAD`, then `origin/main`, then `origin/master` | the git ref the working tree is diffed from |
| `buildTypeName` | `"coverage"` | the build type ColdSpot instruments, the same name in every module: see [The coverage build type](#the-coverage-build-type) |
| `applicationIdSuffix` | unset: debug's application ID | a suffix for the coverage build type ColdSpot creates, to install coverage builds next to debug ones |
| `excludes` / `exclude(...)` | the [default rules](#default-rules) | changed files that are noise: see [Exclude rules](#exclude-rules) |
| `bubble` | `true` | the floating bubble that opens ColdSpot: see [Entry points](#entry-points) |
| `launcherIcon` | `false` | a launcher icon of ColdSpot's own: see [Entry points](#entry-points) |

Set only what differs from the default.

### baseRef

- **It takes** a branch (compared from its merge-base with HEAD), a tag or a commit SHA.
  `<branch>@{upstream}` or `@{u}` works at the very end of the ref; `@{push}` does not.
- **Left unset**, ColdSpot takes `origin/HEAD`, then `origin/main`, then `origin/master`, and fails,
  naming this setting, when the repository has none of them. It never guesses a local branch.
- **A base nobody set is disclosed.** The app's header says so ("No base was set: origin/HEAD, the
  remote's default branch, was used", or "Guessed: ..."), and a guess (`origin/main` or
  `origin/master`) also warns in the build log.
- **When the `CI` environment variable is `true`**, the base must be given, here or with
  `-Pcoldspot.base`, or the build fails. See [CI builds (not yet confirmed)](ci.md).
- **`-Pcoldspot.base=<ref>`** overrides whatever is set here.

## The coverage build type

Every module that applies ColdSpot gets the build type named by `buildTypeName`, `coverage` unless
set. ColdSpot exists in that build type only: an app built as `debug` has no bubble, no ColdSpot
screen, and collects nothing.

Pick the coverage variant under Build Variants in Android Studio and run it, or:

```
./gradlew :app:installCoverage                               # :app:installDemoCoverage with a product flavor "demo"
./gradlew :app:assembleCoverage -Pcoldspot.base=origin/develop
```

A product flavor pairs with it as it does with `debug`: flavors `demo` and `prod` give the variants
`demoCoverage` and `prodCoverage`.

What ColdSpot does with the build type:

- **Creates it from `debug`** when the module has none of that name: debuggable, with debug's
  signing and application ID. Everything keyed on that ID (Firebase, Sign-In, Maps keys, push) keeps
  working, and a coverage build and a debug build replace each other on a device.
- **Installs next to debug instead, with `applicationIdSuffix`.** Whatever is keyed on the
  application ID then needs an entry for the new one.
- **Gives it debug's dependencies.** Every dependency configuration extends its debug twin:
  `coverageImplementation` extends `debugImplementation`, `kspCoverage` extends `kspDebug`, and so
  on.
- **Falls back to `debug`** for a module that has no such build type, so a module left out of
  ColdSpot still builds. Its changed files show as "not measurable".
- **Keeps AGP's own coverage off** in the build type it creates, and leaves `testBuildType` alone,
  so nobody's instrumented tests change.
- **Uses a build type the module already defines as it is**, adding only `debug` as a fallback.
- **Never touches release build types.** Debug and release builds contain nothing of ColdSpot.

What it refuses, while the build is configured and before anything is changed:

- **A build type that is not debuggable**, in the application module. See
  [Troubleshooting](troubleshooting.md#build-type-not-debuggable).
- **Your own build type with `enableAndroidTestCoverage = true` that is also the `testBuildType`.**
  AGP would run its own JaCoCo over the classes ColdSpot already instrumented, which JaCoCo refuses
  ("Cannot process instrumented class"). With another `testBuildType` the flag is inert. See
  [Troubleshooting](troubleshooting.md#enableandroidtestcoverage-and-testbuildtype).
- **`applicationIdSuffix` with a build type the module defines itself.** Set the suffix on that
  build type instead. See
  [Troubleshooting](troubleshooting.md#applicationidsuffix-on-your-own-build-type).

## Exclude rules

Changed files that match an exclude rule are not measured: no classes ship for them and no probes go
in. They are never dropped in silence: the app lists each one under "Excluded", with the rule that
matched.

### Syntax

Rules are Ant-style globs, matched against the whole path relative to the repository root,
`/`-separated. The first matching rule, in the order given, is the one reported.

| pattern | matches |
|---|---|
| `*` | any characters within one path segment |
| `?` | one character within one path segment |
| `**` | any characters, across segments |
| `**/` at the start | the root as well as any depth: `**/src/test/**` matches `src/test/A.kt` and `app/src/test/A.kt` |

### Default rules

```
**/src/test/**            unit test source sets
**/src/androidTest/**     instrumented test source sets
**/src/testFixtures/**    test fixtures
**/buildSrc/**            the build's own code
**/build-logic/**         the build's own code, convention-plugin style
```

### Adding and replacing

`exclude(...)` adds rules to the defaults. Setting `excludes` replaces them.

```kotlin
coldSpot {
    exclude("**/generated/**", "**/*Fixtures.kt")
    // excludes.set(listOf("**/src/test/**"))   // replaces the defaults
}
```

### When modules disagree

Set the rules once, in the convention plugins that apply ColdSpot, so that every module excludes the
same files. Where they differ, the app's rules win: a file the app excludes shows under Excluded and
nowhere else, even when a module ships classes for it, and a warning on the overview names the
module. See [Troubleshooting](troubleshooting.md#warnings-on-the-overview).

## Entry points

Three ways to open ColdSpot. The first two are set in the application module:

```kotlin
coldSpot {
    bubble = true          // the default
    launcherIcon = false   // the default
}
```

- **The bubble** floats on the app's own screens and needs no permission. `bubble = false` leaves it
  out of the build. How it behaves is in [Using ColdSpot](using.md#the-bubble).
- **The launcher icon** is a second icon, labelled "ColdSpot", next to the app's.
- **`ColdSpot.open(context)`** is always there, for a debug menu of your own: see
  [Runtime API](#runtime-api).

<!-- SCREENSHOT: docs/images/launcher-icon.png — the launcher with ColdSpot's icon next to the app's, launcherIcon = true -->

The bubble can also be hidden or shown while the app runs, in three ways:

- the "Show bubble" switch on ColdSpot's screen;
- [`ColdSpot.setBubbleVisible`](#runtime-api);
- the [HIDE_BUBBLE and SHOW_BUBBLE broadcasts](#adb-broadcasts).

Each is remembered across launches, but only by the install it was said to. The next build installed
starts from what that build says, so nobody is locked out by a bubble hidden once. Showing also ends
a "Hide until restart".

## Gradle properties and environment

| name | default | what it does |
|---|---|---|
| `-Pcoldspot.base=<ref>` | unset | overrides [`baseRef`](#baseref) in every module, for this build. As `coldspot.base=<ref>` in `gradle.properties`, for every build |
| `-Pcoldspot.freshSession` | unset | the first launch of this build wipes the saved coverage, once. Any value turns it on, `false` included |
| `CI` (environment variable) | unset | `true`, as GitHub Actions, GitLab CI and most others set it, means the base must be given: no `origin/HEAD`, no guess. It is a configuration-cache input |

```
./gradlew :app:assembleCoverage -Pcoldspot.base=origin/develop
./gradlew :app:assembleCoverage -Pcoldspot.freshSession
```

A fresh-session build logs `fresh-session build: saved coverage wiped once` on the launch that
wipes. A build without the property never wipes.

## Runtime API

ColdSpot's runtime is on the coverage variant's classpath only, so code that calls it belongs in
that variant's source set: `src/coverage/`, or `src/<flavor>Coverage/`. Everything is on the
`ColdSpot` object, in `id.tensky.coldspot.runtime`:

| call | what it does |
|---|---|
| `ColdSpot.open(context)` | opens ColdSpot's screen, as the bubble and the launcher icon do. From an activity it opens on top of it, and back returns there; from any other context it opens as a task of its own |
| `ColdSpot.setBubbleVisible(visible)` | hides or shows the bubble everywhere, over the build's `bubble`; remembered across launches by this install only. Showing also ends a "Hide until restart" |
| `ColdSpot.reset()` | a clean start: the agent's probes cleared, every saved coverage file deleted, the baseline forgotten |
| `ColdSpot.analyze { report -> }` | saves, analyses off the main thread, and hands the `Report` to the callback on the main thread. A failure is a report too, one with nothing but errors in it. The callback is the app's own code: what it throws is not caught |
| `ColdSpot.isActive` | whether JaCoCo's agent was found and the runtime is collecting |
| `ColdSpot.dump()` | analyses and logs the debug dump, as the [DUMP broadcast](#adb-broadcasts) does |

```kotlin
// src/coverage/kotlin/.../DebugMenu.kt
ColdSpot.open(context)
ColdSpot.analyze { report ->
    val executed = report.files.sumOf { it.executed }
    val executable = report.files.sumOf { it.executable }
    Log.i("Coverage", "$executed / $executable changed lines executed")
}
```

**The `Report`** holds, among the rest:

- `files`: one `FileReport` per changed file, with its `path`, its `status` (`FileStatus`), its
  changed `lines` and what each did (`LineState`), `executed` and `executable` counts, the
  `modules` that shipped classes for it, the rule that excluded it (`excludedBy`), and per line the
  instructions executed and in all (`instructions`) and what explains a state (`reasons`)
- `errors`, `warnings` and `staleClasses`, as the overview shows them, and `saveError` while
  coverage cannot be saved
- `jacocoMismatch`: the build's JaCoCo is not the app's, and nothing is coloured
- `collectingSince`, and the `build`: the base, the head and the commits

**`LineState`**, one per changed line, is the marker the file screen shows: `EXECUTED` (Executed),
`PARTIAL` (Partly executed), `NOT_EXECUTED` (Not executed), `BLIND` (Can't be measured), `NO_CODE`
(No code), `PREVIEW` (Preview) and `ERROR` (Error). What each means is in
[Using ColdSpot](using.md#line-markers).

| `FileStatus` | the file |
|---|---|
| `EXECUTED` | every executable changed line executed |
| `PARTIAL` | anything in between, or any amber line |
| `NOT_EXECUTED` | no executable changed line executed |
| `NEUTRAL` | no executable changed lines |
| `ERROR` | a stale class covers one of its lines |
| `NOT_MEASURABLE` | no module of this app shipped a class for it |
| `EXCLUDED` | left out by an exclude rule |

## adb broadcasts

For scripts and UI automation. Each is guarded by `android.permission.DUMP`, which the shell has
and apps cannot get. `<applicationId>` is the coverage build's application ID.

```
adb shell am broadcast -a <applicationId>.coldspot.RESET -n <applicationId>/id.tensky.coldspot.runtime.ColdSpotResetReceiver
adb shell am broadcast -a <applicationId>.coldspot.DUMP -n <applicationId>/id.tensky.coldspot.runtime.ColdSpotDumpReceiver
adb shell am broadcast -a <applicationId>.coldspot.HIDE_BUBBLE -n <applicationId>/id.tensky.coldspot.runtime.ColdSpotBubbleReceiver
adb shell am broadcast -a <applicationId>.coldspot.SHOW_BUBBLE -n <applicationId>/id.tensky.coldspot.runtime.ColdSpotBubbleReceiver
```

| action | what it does |
|---|---|
| `RESET` | the same clean start as `ColdSpot.reset()` and the Reset button |
| `DUMP` | analyses and logs the result to logcat under the tag `ColdSpot`: `EXEC` lines (the execution data), then `CLASS`, `LINE`, `ERROR`, `STALE`, `SINCE` and `DONE`. `adb logcat -d -s 'ColdSpot:*'` reads it back |
| `HIDE_BUBBLE`, `SHOW_BUBBLE` | the same as `ColdSpot.setBubbleVisible(false)` and `(true)`, remembered the same way |

ColdSpot's screens themselves are not exported: open them from the app, with the bubble, the
launcher icon or `ColdSpot.open`.

## What the plugin adds to a module

| | application module | library module |
|---|---|---|
| the coverage build type | yes | yes |
| `coldSpotBundle<Variant>`: instruments the changed classes and bundles their original bytes as assets | yes, and it writes the shared manifest | yes |
| `coldSpotEntryPoints<Variant>`: writes `bubble` and `launcherIcon` as resources | yes | no |
| `coldSpotAgentProperties<Variant>`: writes the `jacoco-agent.properties` JaCoCo's offline runtime needs | yes | no |
| `io.github.tensky.coldspot:runtime`, at the plugin's own version, on the coverage variant's compile and runtime classpaths | yes | no |
| `org.jacoco:org.jacoco.agent:<version>:runtime`, forced to the version that instrumented the app | yes | no |
