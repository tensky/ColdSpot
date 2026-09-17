# ColdSpot — Spike Findings

Verified on: AGP 9.x, JaCoCo 0.8.14, Kotlin (built-in kotlinc), Java 11
target, Compose. Emulator API 36.

## Core result: the design works

A probe→line legend built at build time from `.class` files correctly
describes the probe array present on-device. Verified three independent
ways on four subjects (plain class, suspend fun, simple composable,
realistic Compose screen):

- Probe counts survive the pipeline unchanged: Pricing=6, Loader=17,
  Loader$load$1=2, PanelKt=23, FeedKt=122
- Legend predictions matched the shipped DEX bytecode by hand inspection
- Legend predictions matched the runtime arrays exactly

**The ASM-ordering risk is closed.** Nothing rewrites classes between the
compile output we analyse and the bytes that ship. Hilt was NOT tested —
re-verify if a project adds bytecode transforms.

## Build setup that works

- Separate `coverage` build type: `initWith(debug)`,
  `enableAndroidTestCoverage = true`, `applicationIdSuffix = ".coverage"`
- `android.jacoco.version` / `testCoverage { jacocoVersion }` to pin
- Dependencies: `compileOnly` + `coverageRuntimeOnly`. `initWith` copies
  build-type PROPERTIES, not dependency configurations, so
  `debugImplementation` never reaches the coverage APK.
- jacoco-core on device costs ~107 KB
- `testBuildType` gates AGP's JaCoCo: instrumentation only runs for the
  build type device tests target (VariantImpl.kt:255). The spike worked
  only because testBuildType was still set; "not required" was wrong.
  Superseded by decision C1: ColdSpot does not use AGP's coverage flags.

## Finding 1: condy, not $jacocoData

With Java 11+ class files, JaCoCo uses constant-dynamic probe access.
There is NO `$jacocoData` static field. D8 desugars the condy into a
synthetic holder, e.g. `Pricing$$ExternalSynthetic$Condy0.get()`.

**Never read `$jacocoData` by reflection.** The synthetic holder name is a
D8 implementation detail with no stability guarantee.

**Correct runtime read** — strategy-independent:

```kotlin
val rt = Class.forName("org.jacoco.agent.rt.RT")
val agent = rt.getMethod("getAgent").invoke(null)
val exec = agent.javaClass
    .getMethod("getExecutionData", Boolean::class.javaPrimitiveType)
    .invoke(agent, false) as ByteArray
// parse with ExecutionDataReader → ExecutionDataStore
// (needs a SessionInfoStore as session visitor)
```

Note `javaPrimitiveType` — the signature takes primitive `boolean`.

Verified: agent output is byte-identical to condy on all 11 classes.

## Finding 2: absent ≡ never executed

A class that never ran has NO `ExecutionData` record in the agent dump.
The runtime MUST treat absence as all-false. Never an error, never
"unknown".

## Finding 3: shared-probe false greens — FIXED by instruction counting

Compilers merge trailing instructions across branches. Confirmed in
`FeedRow`: `Log.d` returns an int needing a `pop`; the if-branch's last
call has no `pop` of its own and does `goto 189`, landing on the
else-branch's `pop`. Offset 189 is attributed to line 41.

Result: taking the if-branch executes one instruction tagged line 41.
Under a binary covered/uncovered rule, line 41 renders GREEN despite the
else-branch never running. A false green — the worst possible error.

Same mechanism caused Panel.kt line 13 (`} else {`) to read green.

**Fix: count instructions, not booleans.** Use
`ILine.getInstructionCounter()`. The legend records per-line instruction
totals; the runtime sums covered instructions across fired probes.

## The colour rule (decided)

```
covered = instructions on this line reached by fired probes
total   = all instructions attributed to this line

covered == total  →  green   fully executed
covered == 0      →  red     never executed
otherwise         →  amber   partially executed
```

**Instructions only. Branch coverage does NOT affect colour.** A condition
where only one arm ran stays green — the untested arm is already visible
as red on the lines below, so amber there would be redundant. Branch
counters are still recorded in the legend for possible later use.

Known gap: a one-line conditional (`val x = if (a) 1 else 2`) has no body
lines to colour, so its untested arm is invisible. Accepted for v1.

### Amber semantics

Two causes, one honest meaning ("don't trust this as green"):

1. **Composed but not exercised** — rendered, but the interactive bit
   never ran. Feed.kt lines 21 (state setter), 22 (filter arm), 24
   (Button onClick). This is a genuinely useful QA signal.
2. **Can't tell** — the inline-lambda blind spot below.

### Measured impact

On FeedKt with a real Compose screen, 4 of 18 executable lines change
colour vs the binary rule. Composable signature and closing-brace lines
(20, 29, 30, 31, 35, 43, 44) all stayed GREEN — the predicted structural
noise did not materialise.

## Finding 4: inline-lambda blind spot — render amber

Lambdas passed to library inline functions (`items {}`, `item {}`) are
regenerated as classes stamped with the LIBRARY's `SourceFile`
(e.g. `LazyDsl.kt`), so source-file-based class discovery skips them.
Even when included, JaCoCo's `KotlinInlineFilter` blanks every
instruction: it walks the SMAP skipping only mappings whose class name
equals the class being analysed, and for regenerated lambdas the SMAP
names the library class, so the threshold collapses to 1.

Feed.kt produced 7 classes; 4 carried `LazyDsl.kt` and reported zero
executable lines. Line 27 — the `FeedRow(...)` call site — is invisible
during composition.

**The probes DO fire** (verified on device: `$1 [tttt]`, `$3 [ttt]`, `$4`
mostly true). Only the line mapping is discarded, so this is recoverable.

- **v1: render affected lines amber, never red.** A false red destroys
  trust in every other colour.
- **Deferred fix:** parse the SMAP directly to bypass the filter.

**Mechanism (implemented):** the bundle parses the SMAP of skipped
foreign-SourceFile classes and records the changed lines they map to as
`files[].blindLines` in the manifest. UI rule: a line in blindLines that
would render red renders amber instead. Verified on the spike: Feed.kt
blindLines = [27, 28]; line 27 was a real false red in the 3a device run
(onClick lambda 0/7 while the composition call site had executed).

**Scope is wider than Compose.** Any inline function that wraps a
crossinline lambda in an object regenerates it as a foreign-SourceFile
class: Flow operators (map, filter, onEach…, SourceFile e.g. Emitters.kt),
sortedBy/compareBy (Comparisons.kt), LazyList items. Observed in ColdSpot's
own code: ClassSelection.kt lines 62–63 blind via sortedBy. Expect heavy
amber in Flow-based ViewModels. This raises the priority of SMAP recovery.
Candidate approach (unverified): ship SMAP-stripped copies, copy probes
onto the stripped class ID on device, analyse, remap lines via the SMAP.

## Technique notes

- Legend extraction: for each probe index k, build an `ExecutionDataStore`
  with only `probes[k] = true`, run `Analyzer.analyzeClass()`, record
  which lines come back non-`NOT_COVERED`.
- Use only `org.jacoco.core.analysis.Analyzer`. Never `internal.*`.
- `probeCount` and `classId` are not available from the analysis API.
  `org.jacoco.core.instr.Instrumenter` with a custom
  `IExecutionDataAccessorGenerator` provides them — public package.
  Cross-check its classId against `IClassCoverage.getId()`.
- Class discovery by ASM `SourceFile` + `LineNumberTable`, never by
  filename→classname. Name mapping misses lambdas, inline functions,
  coroutine state machines, Compose synthetics. (Verified: MainActivity's
  Compose synthetics were correctly excluded.)
- **Drop classes with no LineNumberTable** — they can only add noise
  (e.g. `Loader$load$1`, the coroutine continuation).

## Open / unverified

- Compose noise measured only on a moderate screen. Untested: nested
  composables, `LaunchedEffect`, `derivedStateOf`, heavy state hoisting.
- Hilt or any `AsmClassVisitorFactory` present in the build — probe
  placement could differ from the analysed bytes.
- Multi-process apps: the reader only sees its own process's probes.
  Persistence filenames must be per-process (`cov-main.bin`) from day one.
- In one dump, Pricing and Loader each showed a fired probe with no lines
  in a session where neither was tapped. Likely the condy read itself
  triggering class init. Affects no line colour. Not device-verified.
- Class files were stamped one minute after the device dump. Probe counts
  matched, but strictly the analysed bytes are not proven to be the bytes
  that ran.
- 
## Finding 5: run JaCoCo's Analyzer on the device (replaces the probe→line legend)

The probe→line legend cannot support the instruction-count colour rule.
One instruction can belong to several probes (Pricing line 8's condition
sits under probes 1, 3 and 4), so per-probe instruction counts cannot be
summed. Correct counts need the whole probe array analysed at once.

Solution: ship the ORIGINAL (uninstrumented) .class files of the changed
classes as coverage-variant assets, and run `Analyzer.analyzeClass()` on
the device against the agent's ExecutionDataStore.

Verified on device (emulator, API 36):
- ASM + jacoco-core run on ART with no errors
- Per-line covered/total identical to the laptop diagnostic, all 7 classes
- isNoMatch=false and class IDs match for all 7
- 60 ms for 7 classes; FeedKt (20 KB) took 17 ms
- APK cost +17 KB (class assets + analysis code); jacoco-core already present

Rules:
- Package class bytes through the AGP Variant API as a generated assets
  directory that depends on the compile task. Same build as the APK,
  never copied by hand.
- Colour comes from `ILine.getInstructionCounter()`, NEVER from
  `ILine.getStatus()`. Status includes branch coverage, so a fully
  executed condition with one untaken branch reports PARTLY_COVERED
  (Pricing 8, Loader 10, Panel 10, Feed 36).
- `isNoMatch() == true` → show an error for that class, never colour it.
  This is the stale-bytes check.
- Classes with no line info (e.g. Loader$load$1) return 0 instructions
  and firstLine=-1. Skip them.
- Run the analysis off the main thread; cost grows with class count.
- Finding 4's blind spot is unchanged: the same KotlinInlineFilter runs
  on the device.

The single-probe legend technique is retired. The spike's extractor stays
useful only as a laptop diagnostic.

## Finding 6: C1 validated (own instrumentation, per module)

- PROJECT-scope CLASSES toTransform in each module + JaCoCo Instrumenter
  with OfflineInstrumentationAccessGenerator. Library module classes
  reach the APK instrumented. 7/7 classes noMatch=false, device and
  laptop identical on 89/89 lines.
- Hilt: our transform runs before Hilt's ASM transform; Hilt then
  rewrites the superclass. Probes and class IDs survive. Hilt-generated
  classes are never selected (not in the diff).
- The offline runtime REQUIRES jacoco-agent.properties (output=none) in
  the APK. Without it: EROFS crash at the first probe.
-- Mirrored in 4c: the coverage variant's runtime classpath forces
  org.jacoco.core and org.jacoco.agent:runtime to the instrumenter's
  version (as AGP's TaskManager.handleJacocoDependencies does). Verified:
  a library depending on 0.8.15 still resolves 0.8.14. The shared manifest
  records jacoco.version and jacoco.build (JaCoCo.VERSION).
- Debug variant untouched, testBuildType untouched.
- Cost on the sample: +0.2–0.3 s per build; dex/package UP-TO-DATE when
  nothing changed.

Open: cost at scale (many modules), plain Kotlin/JVM modules not
covered, changed files in modules without the plugin.

## AGP JaCoCo ordering and gating (AGP 9.3.2, verified)
- Order: ASM transforms → ScopedArtifact transforms (ColdSpot's) →
  JacocoTask (TaskManager ~1262).
- JacocoTask exists only when deviceTests.any { codeCoverageEnabled }
  (VariantImpl:256), and device tests exist only for testBuildType.
- So AGP coverage + ColdSpot on the same build type conflicts only when
  that type is the testBuildType with enableAndroidTestCoverage = true:
  "Cannot process instrumented class". ColdSpot refuses that case with
  a clear message and never silently changes a user-defined type.
## Finding 7: Hilt compiles some classes twice (verified)
- After an ABI-changing edit in the app module (adding, removing or
  renaming a function, or changing a signature), Hilt's aggregating
  hiltJavaCompile regenerates classes KSP+javac already produced (e.g.
  *_GeneratedInjector). Its classpath input is @CompileClasspath, compared
  by ABI, so body-only edits never trigger it.
- The copies differ only by MethodParameters (-parameters).
- ColdSpot rule: an untouched class keeps the first copy (what the build
  uses), logged at info; a touched class fails with a clear message.
- Guarded by ColdSpotHiltTest (slowTest, ABI edit, precondition check)
  and testbeds/sample/hilt-incremental.sh.

## Finding 8: detecting @Preview from class files (verified)
- @Preview has CLASS retention, so it lands in RuntimeInvisibleAnnotations
  and is readable with ASM.
- Multipreview annotations (e.g. an app's own @DevicePreviews) are resolved by
  walking meta-annotations through the module's compile classpath.
- Component.compileClasspath is @Incubating in AGP 9.3.2. Re-check it on
  AGP upgrades.

## Finding 9: coverage carries forward per file in practice (verified)
- Editing a Kotlin file changes the class id of all its classes that
  carry an SMAP (the SMAP records the file's line count), and of any
  class whose line numbers shift. So "unchanged class" effectively means
  "unchanged file": files you edit start fresh; other files keep their
  coverage across rebuilds.
- A fresh-session wipe must delete saved files only, never reset the
  agent: probes fired during the current launch are real.
- The adb reset broadcast is protected by android.permission.DUMP (the
  shell has it, apps don't). Verified on API 31.
## Finding 10: runtime facts from 5c (verified on API 24, 31, 37)
- JaCoCo's agent starts lazily: RT.getAgent() throws until the first
  instrumented class initialises (0.8.14). The runtime treats "not
  started" as "no data yet" and asks again; never as "no agent".
- Views added to an activity's decor before its window is attached can
  end up without an accessibility node (androidx.core 1.16/1.17 inserts
  a decor child at index 0 during attach). The bubble attaches only after
  the window is attached; the sample reproduces the hazard on purpose.
- Gradle does not substitute an external coordinate with a sibling
  project in the same build. The sample uses one explicit
  dependencySubstitution rule; other builds use includeBuild.
- DUMP-protected broadcasts (reset, hide/show bubble) work on API 24,
  31 and 37.
- resValues is off by default in AGP 9.3.2.
- Cutout emulation crash-loops SystemUI on API 31; the scripts only
  emulate cutouts on API 32+.
- Tested range: API 24–37. The runtime declares minSdk 21.
## Finding 11: JaCoCo's isNoMatch rule vs carry-forward (verified, 0.8.14)
- Analyzer sets isNoMatch() on a class when the execution data store
  holds its name under any other class id.
- So data from earlier builds (old ids) must be removed before analysis,
  and only the current process's agent data may make a class stale.
  Stale data is never saved.
- A changed, not-yet-run class therefore shows "not executed", never
  "error". Rebuilding from restored sources gives the same class ids
  (builds are reproducible).
## Finding 12: git in shallow clones is silently wrong (verified, git 2.50.1)
- In a shallow clone, git merge-base can return an older commit, and
  git log base..HEAD can list the base's commits as the branch's.
- actions/checkout's default (depth 1, detached merge commit) cuts
  HEAD's own history: fetching the base branch doesn't help; only more
  depth does.
- A developer clone and a CI checkout of the same commit give
  byte-identical manifests when both pass -Pcoldspot.base.
- JGit cold start in a fresh Gradle process: ~1.0–1.35 s (Apple M5).