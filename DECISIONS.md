# ColdSpot — Product Decisions

## What counts as changed
- Net diff from merge-base (with the resolved base branch) to the
  working tree. All commits + uncommitted edits collapse into one diff.
- No per-commit attribution (no blame). Not planned.
- Uncommitted lines are NOT marked per line. The header's global
  "uncommitted changes" warning stays. Revisit only on user feedback.
- Deleted lines are not shown: deleted code cannot execute.
- Base resolution on CI: when the CI environment variable is "true",
  the base must be explicit (-Pcoldspot.base or coldSpot { baseRef });
  a guessed base fails the build with the fix. Locally, guessing stays
  and the header says "guessed".
- Shallow history: ColdSpot diffs only when nothing between HEAD, the
  base and their merge-base is cut. Otherwise it fails with where the
  history is cut and how to fetch it. It never fetches by itself.

## Dev page structure
- Header: base (ref @ SHA, guessed or not), head SHA, dirty warning,
  and the commits in the build (collapsible; details in Phase 5).
- Main screen: one row per changed file, tappable → per-file detail.
- Each row shows an aggregate status as icon + colour + count
  ("18/20 lines"). Never colour alone.
- File detail uses normal code density; the whole row is tappable. A
  summary line at the top lists the not-executed and can't-be-measured
  line ranges.

## Line colours (changed lines only)
- A line's colour comes from instruction counts only: instructions on
  the line reached by fired probes / all instructions on the line,
  summed across every class that has code on that line.
  ILine.getInstructionCounter() only, never getStatus(). Branch coverage
  is recorded but never affects colour.
- green: all instructions executed · red: none · amber: some.
- Blind lines (Finding 4) are always amber, whether or not the
  surrounding code ran: we can't see their instructions, so we claim
  neither red nor green. The UI shows them with their own icon and
  "can't be measured", distinct from "partly executed".
- Neutral: changed lines with no instructions, and @Preview lines.
- A class with no execution data counts as never executed, not as an
  error.
- Error: a line whose class failed the stale-bytes check (isNoMatch). Never green, amber or red; it has its own error style, icon and label.

## File status (changed lines only; grey lines ignored)
- green: every changed line executed
- red: no changed line executed
- yellow: anything in between, or any amber line
- neutral: no executable changed lines
- error: stale-bytes check failed (isNoMatch). Never red.
- If the manifest's jacoco.build differs from the JaCoCo version bundled
    in the app, the runtime shows an error and colours nothing.

## Language
- The UI says "executed", never "tested".

## Instrumentation (C1)
- ColdSpot instruments with JaCoCo's public Instrumenter itself, per
  module, via a PROJECT-scope CLASSES transform. AGP coverage flags OFF:
  no testBuildType change, no effect on anyone's instrumented tests or CI.
- Only classes the bundle selects (changed classes) get probes.
- Shipped bytes = exactly the bytes fed to the Instrumenter.
- Plugin is applied to every module, normally through the team's
  convention plugin. The app-only variant (C2/H) and JVMTI breakpoints (F)
  are deferred alternatives.
- Base resolution on CI: when the CI environment variable is "true",
  the base must be explicit (-Pcoldspot.base or coldSpot { baseRef }).
  Every guess fails the build with the fix, origin/HEAD included: it
  names the default branch, not the pull request's target. Locally,
  guessing stays and the header says "guessed".

## Build setup
- The coverage build uses debug's application ID by default (Firebase,
  Sign-In, Maps keys, push keep working). Opt-in:
  coldSpot { applicationIdSuffix = "..." }. A user-defined coverage
  build type is never overridden.
- Coverage = debug + ColdSpot: coverage dependency configurations extend
  debug's.

## Noise
- Default excludes: test source sets, test fixtures, buildSrc,
  build-logic. User-configurable.
- Excluded files are always disclosed (manifest + UI list with the
  reason), never silently dropped.
- @Preview code (direct or multipreview, plus PreviewParameterProvider
  classes) is neutral, never red, and always disclosed with the reason.
  Previews never run in the app.
- Exclusion wins: a file the app excludes appears only under Excluded,
    even if a module ships classes for it. That mismatch is reported as a
    warning.

## Roadmap
- v0.2: plain Kotlin/JVM module support. Until then, their changed
  files show as "not measurable" (neutral, never red).

## Runtime compatibility
- Support AGP 9 only for now. AGP 8 would be a later, backward-compatible
  addition if a pilot needs it.
- The runtime adds exactly two things to a team's coverage build:
  kotlin-stdlib 2.0.21 (language/API 2.0) and JaCoCo (agent + core).
  jacoco-core and ASM are relocated under ColdSpot's package (4d).
  A dependency allowlist test enforces this.
- No AndroidX, Compose, coroutines or kotlinx libraries. Plain Views/XML
  on android.app.Activity; resources prefixed coldspot_; minSdk 21;
  startup through our own ContentProvider.
- Debug and release builds contain none of it.
- The runtime adds exactly three things to a team's coverage build:
  kotlin-stdlib 2.0.21 (language/API 2.0), the JaCoCo agent runtime,
  and ColdSpot's manifest artifact. jacoco-core and ASM are relocated
  inside the runtime AAR (id.tensky.coldspot.shaded). A dependency
  allowlist test enforces this.
- The runtime's language/API floor (2.0) moves to 2.1 when a Kotlin
  compiler we use drops 2.0; that still covers every AGP 9 app.
- ColdSpot's own code never crashes the host app: its startup,
  analysis and saving catch errors and show an error state.

## JSON
- One hand-written, dependency-free JSON reader/writer in the shared
  manifest module, used by both the plugin and the runtime.
  kotlinx-serialization is removed.

## Coverage across builds
- Coverage is kept per class fingerprint (JaCoCo class ID). In practice
  that means per file: files you edit start fresh, other files keep
  their coverage across rebuilds and restarts (Finding 9).
- The UI and the share summary always show "collecting since <time>".
- Clean start: in-app Reset (clears saved data and in-memory probes),
  -Pcoldspot.freshSession (the build wipes old coverage once, on first
  launch), and an adb broadcast for scripts. `gradlew clean` alone does
  not reset, because identical bytes give identical fingerprints.
- Saved periodically while in the foreground, on going to the
  background, on low memory and on a crash. One file per process.

## Entry points
- In-app floating bubble on by default (no overlay permission); launcher
  icon off by default. Both switchable in coldSpot {}.
- ColdSpot.open(context) is always available for teams with their own
  debug menu.
- The bubble can be hidden at runtime and through an adb broadcast, for
  UI automation.

## Warnings
- Configuration mismatches found when merging module manifests (e.g. a
  file only one module lists) are shown as warnings in the header, never
  silently dropped.
## Share summary
- v1: a Markdown-lite text summary sent through Android's share sheet.
  It renders in GitLab/GitHub MR comments and reads fine as plain text
  in Slack, Jira and email. v1.1: an optional JSON attachment for tools.
- Markdown-lite = bold, inline code and simple bullets only. No tables,
  no headers (Slack doesn't render them).
- Contents, in order:
  - header: branch, base ref @ SHA → head SHA, uncommitted warning,
    device + Android version, build time, "collecting since"
  - total: changed lines executed X / Y (Z%)
  - files, worst first: never executed → partly executed → fully
    executed. Never-executed and partly-executed files list their line
    ranges; "not executed" and "can't be measured" are listed
    separately. Long lists are cut off with "+N more".
  - always listed: not measurable, previews, excluded, errors, warnings
  - ColdSpot version
- Paths and line numbers only, never source code.
- Says "executed", never "tested". 
- files, worst first: errors → never executed → partly executed →
      fully executed.