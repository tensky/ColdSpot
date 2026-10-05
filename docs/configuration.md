# Configuration

Everything ColdSpot can be told, and every way to reach it from outside the app. The
[README](../README.md#configuration) has a short example of the `coldSpot` block.

- [The coverage build type](#the-coverage-build-type)
- [The coldSpot block](#the-coldspot-block)
- [Exclude rules](#exclude-rules)
- [Entry points](#entry-points)
- [Gradle properties and environment](#gradle-properties-and-environment)
- [Runtime API](#runtime-api)
- [adb broadcasts](#adb-broadcasts)
- [What the plugin adds to a module](#what-the-plugin-adds-to-a-module)

## The coverage build type

Apply `io.github.tensky.coldspot` to the application module and to every Android library module
whose changes should be measured, normally from the team's convention plugins. Every such module
then gets the build type named by [`buildTypeName`](#buildtypename), `coverage` unless set:

- **Created from `debug`** when the module has none of that name: debuggable, with debug's
  application ID (so Firebase, Sign-In, Maps keys and push keep working);
  [`applicationIdSuffix`](#applicationidsuffix) opts into another.
- **Debug's dependencies reach it.** Every dependency configuration extends its debug twin
  (`coverageImplementation` extends `debugImplementation`, `kspCoverage` extends `kspDebug`,
  `demoCoverageRuntimeOnly` extends `demoDebugRuntimeOnly`, and so on), because `initWith` copies
  a build type's properties, never its dependencies.
- **It falls back to `debug`**, so that a module left out of ColdSpot never breaks the modules that
  depend on it.
- **AGP's own coverage stays off** in the build type ColdSpot creates; `testBuildType` is
  untouched, so nobody's instrumented tests change. ColdSpot instruments what it selects itself.
- **A build type the module defines itself** is used as it is, never overridden: it only gains
  `debug` as a fallback.
- **One combination is refused**, with the ways out: that build type with
  `enableAndroidTestCoverage = true` while it is also the `testBuildType`. AGP would then run its
  own JaCoCo over the classes ColdSpot already instrumented, which JaCoCo refuses ("Cannot process
  instrumented class"). With another `testBuildType` the flag is inert. See
  [Troubleshooting](troubleshooting.md#enableandroidtestcoverage-and-testbuildtype).
- **It must be debuggable** in the application module: ColdSpot is debug-only tooling. A build type
  that is not is refused while the build is configured, before anything is changed.
- **Release build types are never touched.** Debug and release builds contain nothing of ColdSpot.

Build it from Android Studio (pick the coverage variant under Build Variants and run it), or:

```
./gradlew :app:assembleCoverage            # or :app:assembleDemoCoverage with a product flavor
./gradlew :app:assembleCoverage -Pcoldspot.base=origin/develop
```

## The coldSpot block

Set it in the convention plugin that applies ColdSpot, so that every module gets the same values.

| setting | default | what it does |
|---|---|---|
| [`baseRef`](#baseref) | unset: `origin/HEAD`, then `origin/main`, then `origin/master` | the ref the working tree is diffed from |
| [`buildTypeName`](#buildtypename) | `"coverage"` | the build type ColdSpot instruments |
| [`applicationIdSuffix`](#applicationidsuffix) | unset: debug's application ID | a suffix for the coverage build type ColdSpot creates |
| [`excludes` / `exclude(...)`](#excludes-and-exclude) | the [default rules](#default-rules) | changed files that are noise |
| [`bubble`](#bubble) | `true` | the floating bubble that opens ColdSpot; application module only |
| [`launcherIcon`](#launchericon) | `false` | a launcher icon of ColdSpot's own; application module only |

```kotlin
coldSpot {
    baseRef = "origin/develop"
    buildTypeName = "coverage"
    applicationIdSuffix = ".coverage"
    exclude("**/generated/**")
    bubble = true
    launcherIcon = false
}
```

### baseRef

The git ref the working tree is diffed from: a branch, which is compared from its merge-base with
HEAD, a tag or a commit SHA. `<branch>@{upstream}` (or `@{u}`) is understood at the very end of the
ref, `@{push}` not at all.

Left unset, ColdSpot takes `origin/HEAD`, then `origin/main`, then `origin/master`, and fails, naming
this setting, when the repository has none of them. It never guesses a local branch. A base it took
without being told shows as such in the app's header ("No base was set: origin/HEAD, the remote's
default branch, was used", or "Guessed: ..."), and a guess (`origin/main` or `origin/master`) also
warns in the build log.

On CI (`CI=true`) nothing is taken that was not given: without `baseRef` or
[`-Pcoldspot.base`](#gradle-properties-and-environment) the build fails. See [CI](ci.md).

`-Pcoldspot.base=<ref>` overrides whatever is set here.

### buildTypeName

The build type ColdSpot instruments and ships its bundle with: created from `debug` when the module
has no build type of that name, used as it is otherwise. It must be debuggable either way. Default
`coverage`.

### applicationIdSuffix

An application ID suffix for the coverage build type ColdSpot creates, to install coverage builds
next to debug ones. Unset by default: the coverage build then carries debug's application ID, and
everything keyed on it (Firebase, Sign-In, Maps keys, push) keeps working. It is refused when the
module defines the build type itself: that build type's own settings stand, and the build says so.

### excludes and exclude

Changed files that are noise: not measured, but never dropped in silence (see
[Exclude rules](#exclude-rules)). `exclude(...)` adds patterns to the rules, the defaults included;
setting `excludes` replaces them.

### bubble

The floating bubble inside the app that opens ColdSpot, on by default. It needs no permission and
shows on the app's own screens only; it can still be hidden while the app runs. Read in the
application module: a library module's setting has nothing to apply to. See
[Entry points](#entry-points).

### launcherIcon

A launcher icon of ColdSpot's own, labelled "ColdSpot", next to the app's, off by default. Read in
the application module. See [Entry points](#entry-points).

## Exclude rules

Changed files that match an exclude rule are not measured: no classes ship for them and no probes
go in. They are never dropped in silence: the shared manifest lists each with the rule that matched,
and the app lists them the same way, under "Excluded".

### Syntax

Rules are Ant-style globs, matched against the whole path relative to the repository root,
`/`-separated:

| pattern | matches |
|---|---|
| `*` | any characters within one path segment |
| `?` | one character within one path segment |
| `**` | any characters, across segments |
| `**/` at the start | the root as well as any depth: `**/src/test/**` matches `src/test/A.kt` and `app/src/test/A.kt` |

The first matching rule, in the order given, is the one reported.

### Default rules

```
**/src/test/**            unit test source sets
**/src/androidTest/**     instrumented test source sets
**/src/testFixtures/**    test fixtures
**/buildSrc/**            the build's own code
**/build-logic/**         the build's own code, convention-plugin style
```

### Adding and replacing

Add to them in any module (normally the convention plugin), or replace them:

```kotlin
coldSpot {
    exclude("**/generated/**", "**/*Fixtures.kt")
    // excludes.set(listOf("**/src/test/**"))   // replaces the defaults
}
```

### When modules disagree

Set the rules once, in the convention plugin that applies ColdSpot, so that every module excludes
the same files. Where they differ, the app's rules win (DECISIONS.md, "Noise"): a file the app
excludes shows under Excluded and nowhere else, even when a module ships classes for it, and that
module's data for it is ignored. A warning names the module:

> `:core:designsystem ships classes for …/Tag.kt, which the app excludes (**/designsystem/component/Tag.kt): they are ignored, and the file shows as excluded. Set the exclude rules once, in the convention plugin that applies ColdSpot, so that every module excludes the same files.`

A file a module names that the app has neither as changed nor as excluded is kept, without text,
with a warning: the modules were not built from the same change. See
[Troubleshooting](troubleshooting.md#warnings-on-the-overview).

## Entry points

```kotlin
coldSpot {
    bubble = true          // the default
    launcherIcon = false   // the default
}
```

Both are set in the application module and decided by the build. The runtime ships them as two bool
resources with these defaults, `coldspot_bubble` and `coldspot_launcher_icon`, and the plugin writes
the settings over them as a generated resource directory of the coverage variant (not `resValues`,
a build feature that is off by default). The runtime's manifest needs nothing filled in, so the
library merges into any app as it is. An app may also set the two resources in its own `res/`.

**The bubble** opens ColdSpot from inside the app: see [Using ColdSpot](using.md#the-bubble) for
how it behaves. The build's setting can be overridden while the app runs, in three ways: the "Show
bubble" switch on ColdSpot's screen, [`ColdSpot.setBubbleVisible`](#runtime-api), and the
[HIDE_BUBBLE and SHOW_BUBBLE broadcasts](#adb-broadcasts). Each is remembered across launches, but
only by the install it was said to: the next build installed starts from what that build says, so
nobody is locked out by a bubble hidden once. Showing also ends a "Hide until restart", which is
never written down.

**The launcher icon** is an `activity-alias` labelled "ColdSpot" with an icon of its own, enabled by
`@bool/coldspot_launcher_icon`: there when `launcherIcon = true`, not there otherwise. The icon is
ColdSpot's logo, the beetle frozen mid-step in a tilted ice cube (A3): `@mipmap/coldspot_launcher`, an
adaptive icon from API 26, with a monochrome layer for themed icons from Android 13, and a picture
for every density below API 26. The vector layers are the logo pack's SVGs as VectorDrawables; the
pictures are the pack's own.

<!-- SCREENSHOT: docs/images/launcher-icon.png — the launcher with ColdSpot's icon next to the app's, launcherIcon = true -->

## Gradle properties and environment

| name | default | what it does |
|---|---|---|
| `-Pcoldspot.base=<ref>` | unset | overrides [`baseRef`](#baseref) for this build |
| `-Pcoldspot.freshSession` | unset | the build's shared manifest carries a one-off token, and the first launch that sees it wipes the saved coverage, once. Any value turns it on, `false` included |
| `CI` (environment variable) | unset | `true`, as GitHub Actions, GitLab CI and most others set it, means the base must be given: no `origin/HEAD`, no guess. The build reads it as a configuration-cache input, so a configuration cached without it is not reused with it |

```
./gradlew :app:assembleCoverage -Pcoldspot.base=origin/develop
./gradlew :app:assembleCoverage -Pcoldspot.freshSession
```

A fresh-session build logs `fresh-session build: saved coverage wiped once` on the launch that wipes.
A build without the property, the normal case, never wipes.

## Runtime API

ColdSpot's runtime is on the coverage variant's classpath only, so code calling it belongs in that
variant's source set (`src/coverage/`, or `src/<flavor>Coverage/`). Everything is on the `ColdSpot`
object, in `id.tensky.coldspot.runtime`:

| call | what it does |
|---|---|
| `ColdSpot.open(context)` | opens ColdSpot's screen, for a team's own debug menu; what the bubble and the launcher icon do. From an activity it opens on top of it, and back returns there; from any other context it opens as a task of its own |
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

`LineState`, one per changed line, is the marker the file screen shows: `EXECUTED` (Executed),
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
