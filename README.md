# ColdSpot

See, inside your running debug app, which lines you changed and which of them have executed.

![img.png](docs/coldspot_main_page.png)

ColdSpot is debug-only tooling for Android, for developers and QA on manual builds from Android
Studio.

## What it does

- Diffs your working tree, uncommitted edits included, against a base branch, once per build.
- Instruments only the classes that hold changed lines, with JaCoCo, in a `coverage` build type it
  makes from `debug`.
- Colours every changed line, inside the running app, by what has executed: green all of it, amber
  some of it or can't tell, red none of it.
- Keeps coverage across launches and rebuilds, and shares a text summary for a pull request or a
  ticket.
- Stays out of debug and release builds.

## Requirements

- AGP 9.x and Gradle 9, on JDK 17 or newer.
- An app with minSdk 21 or higher.
- A git repository with its history, back to where the branch forked from its base.
- A debug-like coverage build only: ColdSpot makes one from `debug`, and refuses one that is not
  debuggable. Release builds are never touched.

## Setup

> **TODO(publishing):** ColdSpot is not published yet. The coordinates below are final; the
> repository that will serve them is not decided. Until then, publish it to a directory of your own,
> from a ColdSpot checkout, and list that directory as the repository:
>
> ```
> ./gradlew :tooling:plugin:publishToMavenLocal :tooling:manifest:publishToMavenLocal :runtime:publishToMavenLocal -Dmaven.repo.local=<dir>
> ```

**1. Give your convention plugins ColdSpot**, by its plugin marker:

```kotlin
// build-logic/convention/build.gradle.kts
dependencies {
    implementation("io.github.tensky.coldspot:io.github.tensky.coldspot.gradle.plugin:0.1.0")
}
```

```kotlin
// build-logic/settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        // TODO(publishing): the repository that serves io.github.tensky.coldspot
    }
}
```

**2. Apply it** in the convention plugin for application modules, and in the one for Android library
modules:

```kotlin
apply(plugin = "io.github.tensky.coldspot")
```

A module without convention plugins applies it as any plugin:
`plugins { id("io.github.tensky.coldspot") version "0.1.0" }`.

**3. Build the coverage variant.** Pick it under Build Variants in Android Studio and run the app, or:

```
./gradlew :app:assembleCoverage            # :app:assembleDemoCoverage with a product flavor
```

Every module that applies the plugin gets a `coverage` build type made from `debug`, with debug's
application ID and dependencies: see [The coverage build type](docs/configuration.md#the-coverage-build-type).

**To see something, build a change:** on a feature branch, or with `-Pcoldspot.base=<an older commit>`.
On a main that was just pushed, the base is the very commit being built, and there is nothing to
show.

## Using it

![ColdSpot Bubble](docs/coldspot_using_it_bubble.png)
**Opening ColdSpot.** Tap the bubble ColdSpot adds to the app's screens; drag it to move it,
long-press it for "Hide until restart". With `launcherIcon = true` there is also a launcher icon, and
`ColdSpot.open(context)` opens ColdSpot from your own debug menu.

**The overview** says what was built (base, head, uncommitted changes, commits), the total ("41 / 65
changed lines executed (63%)") and the changed files, worst first. Tap a file for its changed lines,
each with a marker:

| marker | colour | means |
|---|---|---|
| Executed | green | every instruction on the line executed |
| Partly executed | amber | some of its instructions executed |
| Not executed | red | none of its instructions executed |
| Can't be measured | amber | code in an inline lambda, which can't be traced to the line: it may have run, or not |
| No code | neutral | nothing on the line to execute |
| Preview | neutral | a Compose preview's line, which never runs in the app |
| Error | error | the class that ran is not the one this build shipped: build and install again |

Tap a line for the reason, such as "12 of 15 instructions executed".

![Coldspot Instruction Lines Executed](docs/coldspot_instruction_line_executed.png)

**Share summary** sends, as text a pull request, a ticket or a chat can show, the changed lines still
to execute, file by file: paths and line numbers, never source.

**Reset coverage…** on the overview starts collecting afresh. Otherwise coverage builds up across
launches and rebuilds, and a file you edit starts fresh. A build made with `-Pcoldspot.freshSession`
wipes the saved coverage once, on its first launch.

More in [Using ColdSpot](docs/using.md).

## Configuration

| setting | where | default | what it does |
|---|---|---|---|
| `baseRef` | `coldSpot { }` | unset: `origin/HEAD`, then `origin/main`, then `origin/master` | the ref the working tree is diffed from; a branch is compared from its merge-base with HEAD |
| `buildTypeName` | `coldSpot { }` | `"coverage"` | the build type ColdSpot instruments: created from `debug` when missing, debuggable either way |
| `applicationIdSuffix` | `coldSpot { }` | unset: debug's application ID | a suffix for the coverage build type ColdSpot creates, to install it next to debug |
| `excludes` / `exclude(...)` | `coldSpot { }` | test sources, test fixtures, `buildSrc`, `build-logic` | changed files that are noise: listed as excluded, never measured. `exclude` adds rules; setting `excludes` replaces them |
| `bubble` | `coldSpot { }`, app module | `true` | the floating bubble that opens ColdSpot |
| `launcherIcon` | `coldSpot { }`, app module | `false` | a launcher icon of ColdSpot's own |
| `-Pcoldspot.base=<ref>` | Gradle property | unset | overrides `baseRef` for one build |
| `-Pcoldspot.freshSession` | Gradle property | unset | the build wipes the saved coverage once, on its first launch; any value turns it on |
| `CI=true` | environment | unset | the base must be given: no `origin/HEAD`, no guess |
| `ColdSpot.open(context)` | runtime API | | opens ColdSpot's screen |
| `ColdSpot.setBubbleVisible(visible)` | runtime API | the build's `bubble` | hides or shows the bubble, remembered by this install |
| `ColdSpot.reset()` | runtime API | | a clean start: probes cleared, saved coverage deleted |
| `ColdSpot.analyze { report -> }` | runtime API | | the analysis, off the main thread, for your own tools |
| `ColdSpot.isActive` | runtime API | | whether ColdSpot found JaCoCo's agent and is collecting |
| `ColdSpot.dump()` | runtime API | | logs the analysis, as the DUMP broadcast does |

The runtime API is on the coverage variant's classpath only: call it from `src/coverage/`. The full
reference, with the exclude-rule syntax and the adb broadcasts, is in
[Configuration](docs/configuration.md).

## CI

On CI (`CI=true`), give the base and the full history: `fetch-depth: 0` and
`-Pcoldspot.base=origin/<target branch>`, or the build fails, saying what to fetch or pass.
[Using ColdSpot in CI](docs/ci.md) has GitHub Actions and GitLab CI set-ups, and every checkout case.

## Limitations

- Plain Kotlin/JVM modules are not measured yet (planned for v0.2): their files show as "not
  measurable", never red.
- Lambdas from a library's inline function (`items { }`, Flow operators) cannot be traced to their
  line: those lines show "Can't be measured", amber at most, never red.
- Kotlin and Java sources only: changed layouts, resources and build scripts are not shown.
- AGP 9 only, and a debuggable coverage build only.
- In a repository with several apps, a file only another app includes shows as "not measurable".

## Documentation

- [Using ColdSpot](docs/using.md): opening it, the screens, what every marker means, sharing,
  resetting
- [Configuration](docs/configuration.md): every setting, the exclude rules, the runtime API, the adb
  broadcasts
- [Troubleshooting](docs/troubleshooting.md): every message ColdSpot shows, with its cause and fix
- Design records: [DECISIONS.md](DECISIONS.md) and [FINDINGS.md](FINDINGS.md)

## License

ColdSpot is licensed under the [Apache License, Version 2.0](LICENSE).

```
Copyright 2026 tensky

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Attributions

ColdSpot repackages, under `id.tensky.coldspot.shaded`:

- JaCoCo (EPL-2.0), the coverage engine, in the plugin and in the runtime. JaCoCo's agent runtime is
  a dependency of the coverage build, not repackaged.
- ASM (BSD-3-Clause), in the plugin and in the runtime.
- JGit (Eclipse Distribution License 1.0, a BSD-3-Clause license), with JavaEWAH and Apache Commons
  Codec (both Apache-2.0), in the plugin.

Their license texts and notices travel with the artifacts, in
`META-INF/coldspot/THIRD-PARTY-NOTICES.txt`: see
[the plugin's](tooling/plugin/src/main/resources/META-INF/coldspot/THIRD-PARTY-NOTICES.txt) and
[the runtime's](jacoco-shaded/src/main/resources/META-INF/coldspot/THIRD-PARTY-NOTICES.txt).
