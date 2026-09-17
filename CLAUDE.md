# ColdSpot

Debug-only Android tooling that shows, inside the running app, which
lines changed vs a base git ref and which of those have actually been
executed. For devs and QA on manual builds from Android Studio. OSS intent.

## Authoritative docs — read before working, never contradict
- FINDINGS.md  — facts proven in the spike (how JaCoCo, D8, Kotlin behave)
- DECISIONS.md — product decisions (what counts as changed, UI rules)

## How it works
Build time (runs on the dev's machine, inside Gradle tasks):
diff   → resolves the base, diffs merge-base → WORKING TREE, reports
changed lines, the dirty flag (via IndexDiff) and the commit list
bundle → picks the compiled classes that contain changed lines, ships
their ORIGINAL (uninstrumented) bytes + manifest.json as assets

Run time (on the device, coverage build only):
JaCoCo agent execution data + shipped class bytes → JaCoCo Analyzer
on device → per-line instruction counters → colours

Retired: the probe→line legend and the single-probe technique
(FINDINGS Finding 5). Do not reintroduce them.

## Repo layout
coldspot/                MAIN build: the Android side
├── app/       sample app for testing the plugin end to end
├── runtime/   Android library — agent read, on-device Analyzer,
│              persistence, dev page                              phase 5
└──tooling/   INCLUDED build (pluginManagement { includeBuild }, and a
    │          plain includeBuild for library substitution):
    │          everything that runs inside Gradle on the dev machine
    ├── diff/      plain Kotlin JVM — git work                    DONE
    ├── manifest/  plain Kotlin JVM — manifest model + JSON codec,
    │              only kotlin-stdlib; used by plugin AND runtime  DONE
    ├── bundle/    plain Kotlin JVM — class selection + manifests  DONE
    └── plugin/    Gradle plugin — wiring + instrumentation        DONE (4d pending)

Task paths: `./gradlew :tooling:diff:test`, `:tooling:diff:printDiff`.
`./gradlew test` / `check` at the root run tooling's tests too, through
aggregate tasks in both root build files. Both builds read
gradle/libs.versions.toml.

## Non-negotiables
Git:
- Target is always the WORKING TREE (uncommitted edits included).
- Base resolution lives ONLY in `diff`. Order: -Pcoldspot.base →
  coldSpot { baseRef } → origin/HEAD → origin/main → origin/master →
  error naming coldSpot { baseRef }. Never guess local branches.
- Use JGit. Never shell out to git.
- Never WRITE .git/index, anything under .git/, or $HOME. Reading the
  index is allowed. JGit's own config is read-only (custom SystemReader).

JaCoCo:
- Public API only. Never import org.jacoco.core.internal.*.
- Never read $jacocoData — Java 11+ classes use condy. Probes come from
  the agent: RT.getAgent().getExecutionData(false).
- The jacoco-core version on the device MUST equal the JaCoCo version
  that instrumented the app. Analyzer and instrumenter must agree on
  probe placement.
- A missing ExecutionData record means "never executed", not an error.

Colours:
- From ILine.getInstructionCounter() only. NEVER ILine.getStatus()
  (it includes branch coverage).
- green = all instructions, red = none, amber = partial.
- A blind line is always amber (DECISIONS: Line colours).
- isNoMatch() == true → error state, never red.

Product:
- UI copy says "executed", never "tested".
- Debug only. The coverage build type must be debuggable; fail loudly
  otherwise. Never touch release variants.

Build:
- Kotlin everywhere.
- diff and bundle never depend on Android, AGP or the Android SDK.
- Configuration-cache compatible (Gradle 9 has it on by default). No git
  or file I/O at configuration time; do it in task actions.
- In 4d: JGit, ASM and jacoco-core are shaded into the plugin; jacoco-core
    and ASM are relocated inside the runtime AAR.
  Don't add build-time dependencies without flagging them.
- The runtime adds only kotlin-stdlib and JaCoCo to a team's app. No
    AndroidX, Compose, coroutines or kotlinx (allowlist test).

## How to work
- Verdict first, then reasoning. Assume senior Android/Kotlin knowledge.
- Flag uncertainty explicitly. Verify API details against the pinned
  library sources, not memory or old docs.
- Stop at the end of each requested step. Do not build ahead.
- If something doesn't work, say so plainly. Never work around silently.
- Mutation-check every new test: break the rule it guards, confirm it
  fails, revert.
- Where a reference tool exists (git status, git log), compare against
  it live in tests.
- Never commit. I review and commit.
- Throwaway scripts go in scratch/ (git-ignored). Tools meant to stay go
  in committed src/tools/ source sets.
- Never run git commands that change the working tree or index
  (checkout, reset, restore, stash, clean). For mutation testing, back up
  files with cp and restore from the copy.
- Run testbeds/release-check.sh before any release.
- Device tests run on scratch AVDs the scripts create. Never change
  settings, clear app data or install on an emulator the user is running
  without asking first.
- Never use rm -rf with globs or after cd. Delete explicit, absolute
  paths only.

## All Phase finished. Fixing issues. 

