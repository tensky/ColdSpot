# Troubleshooting

Every message ColdSpot shows, where it shows, what causes it and what fixes it. Words in angle
brackets stand for what the message fills in.

- **In the build**, ColdSpot's messages follow Gradle's "Execution failed for task" line, for the
  task `coldSpotBundle<Variant>` of the module that ran the diff, or come while the build is
  configured.
- **In the app**, they are on ColdSpot's screen.
- **In logcat**, they are under the tag `ColdSpot`: `adb logcat -s 'ColdSpot:*'`.

Contents:

- [Nothing to see](#nothing-to-see)
- [Configuring the build](#configuring-the-build)
- [The diff](#the-diff)
- [The bundle](#the-bundle)
- [Warnings and notes in the build log](#warnings-and-notes-in-the-build-log)
- [Internal errors](#internal-errors)
- [In the app](#in-the-app)
- [In logcat](#in-logcat)
- [Installing](#installing)

## Nothing to see

These are not errors, but they are the first questions.

**"No changed lines against \<base\>. Nothing to execute: what was built is what the base has."**
The build is the base itself: a main that was just pushed, built with `origin/HEAD` as its base, has
nothing changed. Build on a feature branch, or pass an older commit with `-Pcoldspot.base=<commit>`.

**The total says "No changed lines to execute".** Files changed, but none of their changed lines
holds code ColdSpot can measure: they are comments or declarations, or every changed file is
excluded or not measurable. The folded sections under the files say which.

**A file is under "Not measurable".** The overview gives one reason, "No module shipped a class for
it: a plain Kotlin/JVM module, or a module without ColdSpot.", and there are three causes:

- the file is in a plain Kotlin/JVM module, which ColdSpot does not measure yet (planned for v0.2);
- its module does not apply the plugin: apply it in the convention plugin for Android library modules;
- its module is not part of this app at all, as when a repository holds two apps and the change
  touched the other one. Every app lists every changed file of the repository.

**A line says "Can't be measured".** It holds code inside a lambda that a library's inline function
regenerated (`items { }`, Flow operators, `sortedBy`), which JaCoCo cannot trace back to the line.

**A changed XML layout, resource or build script is not listed.** ColdSpot diffs Kotlin and Java
sources only.

**There is no bubble.** It was hidden (with the switch on ColdSpot's screen, "Hide until restart",
`ColdSpot.setBubbleVisible(false)` or the HIDE_BUBBLE broadcast), the build has `bubble = false`, the
screen is a floating (dialog) activity, or the app running is not the coverage build. Open ColdSpot
from the launcher icon or `ColdSpot.open(context)` instead; see [Entry points](configuration.md#entry-points).

## Configuring the build

### Build type not debuggable

```text
ColdSpot: build type '<name>' is not debuggable. ColdSpot is debug-only tooling (it ships class files and reads JaCoCo's agent inside the app), so its build type needs isDebuggable = true. Make '<name>' debuggable, or point coldSpot { buildTypeName = "..." } at a build type that is.
```

**Cause.** In the application module, the build type ColdSpot uses (`coverage` unless
`buildTypeName` says otherwise) is not debuggable: one the module defines itself, or one created from
a `debug` that is not.
**Fix.** Make it debuggable, or point `buildTypeName` at a name the module does not define, and
ColdSpot creates that build type from `debug`.

### applicationIdSuffix on your own build type

```text
ColdSpot: coldSpot { applicationIdSuffix = "<suffix>" } cannot apply: build type '<name>' is defined by this module, and ColdSpot never overrides a build type it did not create. Set applicationIdSuffix on that build type itself, or drop it from coldSpot { }.
```

**Cause.** `applicationIdSuffix` only applies to the build type ColdSpot creates.
**Fix.** Set the suffix on your build type, or drop it from `coldSpot { }`.

### enableAndroidTestCoverage and testBuildType

```text
ColdSpot: build type '<name>' is defined by this module with enableAndroidTestCoverage = true and is the testBuildType, so AGP would run its own JaCoCo over the classes ColdSpot already instrumented, and JaCoCo refuses instrumented input ("Cannot process instrumented class"). Choose one: let ColdSpot create its own build type (drop '<name>' from buildTypes, or point coldSpot { buildTypeName = "..." } at a name this module does not define); set enableAndroidTestCoverage = false on '<name>'; or make another build type the testBuildType.
```

**Cause.** AGP runs its own JaCoCo for the `testBuildType` when its coverage flag is on, over classes
ColdSpot has already instrumented.
**Fix.** One of the three the message names.

## The diff

These fail the build while the coverage variant's `coldSpotBundle` task runs. Every refusal of a
shallow clone begins with `shallow clone detected`. Whatever the case, ColdSpot never fetches and
never writes `.git`: the fix is always for you, or your CI's checkout, to make.
[CI builds (not yet confirmed)](ci.md) has set-ups to try.

### No git repository

```text
No git repository at or above <dir>: ColdSpot compares the working tree with the git history, and a source archive or a copy without .git has none. Build from a git clone, with its history (in CI: fetch-depth: 0)
```

**Cause.** The project is not inside a git repository: a source archive, or a copy without `.git`.
**Fix.** Build from a git clone, with its history.

### No base, on CI

```text
No base was given, and on CI (CI=true) ColdSpot takes none it is not given, neither origin/HEAD nor a guess: pass the branch the change is for as -Pcoldspot.base=origin/<target>, or set coldSpot { baseRef = "<ref>" }.
```

When the clone is shallow as well, it goes on: `The clone is shallow as well, HEAD's history stopping
at <sha>: fetch the full history too (e.g. fetch-depth: 0).`

**Cause.** `CI` is `true`, and neither `-Pcoldspot.base` nor `coldSpot { baseRef }` gives the base. On
CI, ColdSpot does not take `origin/HEAD` or guess: either could name the wrong branch for a pull
request.
**Fix.** Pass the pull request's target branch, `-Pcoldspot.base=origin/<target>`; see
[CI builds (not yet confirmed)](ci.md).

### No base to compare with

```text
No base ref was given, and this clone has no origin/HEAD, origin/main or origin/master to compare with (a single-branch clone has only its own branch): fetch the branch to compare with, and pass it as -Pcoldspot.base=origin/<target>, or set one with coldSpot { baseRef = "<ref>" }
```

In a shallow clone:

```text
shallow clone detected: no base was given, this clone has no origin/HEAD, origin/main or origin/master to compare with, and HEAD's history stops at <sha>. Fetch the full history with the branch to compare with (e.g. fetch-depth: 0), and pass that branch as -Pcoldspot.base=origin/<target>, or set coldSpot { baseRef = "<ref>" }.
```

**Cause.** No base was given and none of the three remote branches ColdSpot would take exists; a
single-branch clone has only its own. ColdSpot never guesses a local branch.
**Fix.** Fetch the branch to compare with and name it: `-Pcoldspot.base=origin/<target>` or
`coldSpot { baseRef }`.

### The base is not in this clone

```text
'<ref>' does not resolve to a commit in this clone: fetch it first (git fetch origin +refs/heads/<branch>:refs/remotes/origin/<branch>), or pass the base to compare with as -Pcoldspot.base=origin/<target>
```

In a shallow clone:

```text
shallow clone detected: '<ref>' is not in this clone, and HEAD's history stops at <sha>. Fetch the full history with that branch (e.g. fetch-depth: 0; with git itself, git fetch --unshallow origin +refs/heads/<branch>:refs/remotes/origin/<branch>).
```

For a base that is not a remote branch, the shallow message ends `Fetch the full history with the
commit it names (e.g. fetch-depth: 0).`

**Cause.** The base ref names nothing this clone has. A plain `git fetch origin <branch>` does not
create `origin/<branch>` in a single-branch clone, so the message gives the refspec that does.
**Fix.** The fetch the message names, or the full history in a shallow clone.

### Shallow clone detected

A shallow clone is diffed only when nothing between HEAD, the base and their merge-base is cut:
otherwise a better merge-base could lie behind the cut. Each message says where the history stops:

```text
shallow clone detected: HEAD and '<ref>' meet nowhere in the history this clone has: HEAD's history stops at <sha>, and the history of '<ref>' at <sha>. Fetch the full history (e.g. fetch-depth: 0; with git itself, git fetch --unshallow).
```

```text
shallow clone detected: HEAD's history stops at <sha> before it reaches its merge-base with '<ref>', so neither that merge-base nor the commits in between can be known. Fetch the full history (e.g. fetch-depth: 0; with git itself, git fetch --unshallow).
```

```text
shallow clone detected: HEAD's history stops at <sha> before it reaches '<ref>', so the commits in between cannot all be known. Fetch the full history (e.g. fetch-depth: 0; with git itself, git fetch --unshallow).
```

```text
shallow clone detected: HEAD's history also reaches <sha>, a root commit, without passing its merge-base with '<ref>', and the history below that stops at <sha>, so whether <sha> is the base's own cannot be known. Fetch the full history (e.g. fetch-depth: 0; with git itself, git fetch --unshallow).
```

(For a base that is not a branch, that one says `without passing '<ref>'`.)

```text
shallow clone detected: the history of '<ref>' stops at <sha> before it reaches its merge-base with HEAD, so the merge-base found may not be the real one. Fetch the full history (e.g. fetch-depth: 0; with git itself, git fetch --unshallow).
```

**Cause.** The clone's history is cut too close to HEAD or to the base: `actions/checkout`'s default
fetches one commit.
**Fix.** More history: `fetch-depth: 0`, or `git fetch --unshallow`. Fetching the base branch again
does not help: a cut history is never deepened by fetching a branch.

### No shared history

```text
'<ref>' shares no history with HEAD, so there is no merge-base to diff from
```

**Cause.** In a full clone, HEAD and the base have no commit in common: unrelated histories.
**Fix.** Pass a base on the same history as HEAD.

### A base ref ColdSpot cannot read

```text
'<ref>' is an ambiguous abbreviation
'<ref>' is not a commit
'<ref>' names an object this repository does not have
'<ref>' is not a revision JGit can resolve
'<ref>': @{upstream} is only understood at the very end, and @{push} not at all
'<ref>': <name> is not a local branch, so it has no upstream
'<ref>': branch <name> has no upstream configured
```

**Cause.** The base names something that is not a commit, an abbreviated SHA that matches several,
or an `@{upstream}` that has nothing to point at.
**Fix.** Give a branch, a tag or a full SHA; use `@{upstream}` only at the end, on a local branch that
tracks a remote one.

### Partial clone

```text
<JGit's message about the missing object>: this is a partial clone, and only git itself can fetch objects on demand; ColdSpot never fetches. Clone without --filter (in CI: no `filter` on the checkout), or run `git diff <sha> -- "*.kt" "*.java"` once before the build: it fetches the ones needed here.
```

**Cause.** A clone made with `--filter` lacks file contents the diff needs, and JGit cannot fetch them
on demand.
**Fix.** Clone without a filter, or run the `git diff` the message gives once before the build.

## The bundle

### Compiled differently, and the change touches it

```text
<class> is compiled differently in <first file> and <second file>, and the change touches it: ColdSpot cannot tell which of the two the app is meant to run. Have the build compile it once (a clean build usually does), or leave its file out with coldSpot { exclude(...) }.
```

**Cause.** The build handed ColdSpot two different compilations of a class the change touches. Hilt's
aggregating task does this after an incremental edit that changes the app module's ABI: it compiles
`*_GeneratedInjector` classes a second time.
**Fix.** A clean build, which compiles it once; or exclude the file. An untouched class compiled twice
is not an error: ColdSpot takes the first copy, the one the build uses, and says so at `--info`.

### A class whose SMAP cannot be read

```text
<class file>: <what is wrong with its SMAP>
```

For instance `<class file>: not an SMAP: begins with '<text>'`.

**Cause.** A compiled class carries source-mapping data (Kotlin's SMAP) ColdSpot cannot parse.
**Fix.** A clean build; if it stays, report it with the class file the message names.

## Warnings and notes in the build log

**A guessed base** (a warning, off CI only):

```text
ColdSpot: no base was given and there is no origin/HEAD: comparing with origin/main, a guess. Pass -Pcoldspot.base=<ref>, or set coldSpot { baseRef }, to compare with another branch.
```

The repository has no `origin/HEAD`, so ColdSpot took `origin/main` (or `origin/master`). Right for a
change meant for main; set the base for any other. The app's header says "Guessed" too.

**At `--info`:**

- `ColdSpot: diffed <dir> against <ref> (<sha>) in <n> ms: <n> changed files`
- `ColdSpot: <class> is compiled differently in <a> and <b>; the first is what the build uses`, an
  untouched class compiled twice, which is fine
- `ColdSpot: <n> classes instrumented and bundled for <n> changed files; ...`, what each module's
  bundle task did

## Internal errors

These mean ColdSpot's own artifacts are broken, or a variant is shaped in a way ColdSpot did not
expect. Report them, with the build output:

```text
ColdSpot: the plugin was built for jacoco-core <version>, but carries JaCoCo <version>
ColdSpot: the plugin's version.properties has no <key>
ColdSpot: variant <variant> has no assets to add the bundle to
ColdSpot: variant <variant> has no Android resources to add the entry points to
ColdSpot: variant <variant> has no Java resources to add jacoco-agent.properties to
<jar>: entry <name> escapes its directory
```

## In the app

### When nothing can be shown

**"ColdSpot has nothing to show"** takes the whole screen, with one of these:

- `no coldspot/manifest.json in the APK's assets: this is not a ColdSpot coverage build`, or
  `This build carries no ColdSpot bundle.`: the app has ColdSpot's runtime but not the bundle the
  plugin writes. The runtime reached a build the plugin did not make, or the assets were removed.
  Build the coverage variant of the application module with the plugin applied.
- `ColdSpot is not running: no JaCoCo agent in this build`: the app has no JaCoCo agent runtime
  (`org.jacoco.agent.rt.RT`). It was not built by the coverage build type of a module with the plugin
  applied, which adds the agent, or something removed or renamed that class. Build the coverage
  variant with the plugin applied, and do not shrink or obfuscate it.
- `ColdSpot could not start: <reason>`: ColdSpot's own startup failed. The app runs on without it,
  and ColdSpot stays idle. Report it, with the logcat line `starting ColdSpot failed; the app goes on
  without it` and its stack trace.
- `The analysis failed: <reason>`: the analysis itself failed. Report it. When the reason is the
  build's manifest, it says what is wrong with it:

  ```text
  The analysis failed: this manifest is schemaVersion <n>, and this ColdSpot reads schemaVersion <m>: the build that made the APK and the runtime inside it are different ColdSpot versions. Rebuild with one version of both.
  The analysis failed: line <n>, column <m>: <what is wrong>
  ```

  The first means the plugin and the runtime are different ColdSpot versions: let the plugin add the
  runtime, which it does at its own version, rather than declaring it by hand. The second means the
  manifest is damaged: build again.

Any problems ColdSpot recorded before (`<What> failed: <reason>`, below) are listed after it.

**"Nothing can be coloured"**, with:

```text
The build instrumented with JaCoCo <version> but the app runs JaCoCo <version>: probes may not line up, so nothing is coloured. Rebuild so that both match.
```

The JaCoCo that instrumented the build is not the one inside the app's ColdSpot runtime, so probe
placement may differ and no colour would be honest. Use one ColdSpot version for the plugin and the
runtime (let the plugin add the runtime), and rebuild.

**"ColdSpot cannot show this"**, with `Showing the analysis failed: <reason>`. The screen could not lay
out the analysis. Report it.

**A toast, "ColdSpot cannot open this screen: \<reason\>".** Opening one of ColdSpot's screens failed;
the app goes on. Report it.

### Banners on the overview

<!-- SCREENSHOT: docs/images/banner-save-error.png — the overview with the "Coverage can't be saved" banner -->

**"Coverage can't be saved: the saved coverage cannot be read (\<reason\>)"**, with `Until a save
succeeds, what the app executes is lost when it stops. The saved file is never written over; Reset
deletes it.` The coverage saved earlier cannot be read, and ColdSpot never writes over it, so every
save fails until it can be read. What the running app gathered is still shown, but none of it
outlives the app. A file that is not coverage data, or is cut short, is moved aside as `.unreadable`
on its own. Fix: Reset, which deletes the file.

**"\<n\> errors"**, listing:

- `<module>/<class>: the class file is missing from the APK's assets`: a class the build says it
  shipped is not in the APK. Build and install again; report it if it stays.
- `<What> failed: <reason>`, such as `The periodic save failed: ...` or `Resetting failed: ...`: one of
  ColdSpot's own operations failed, and the app went on without it. The last five are listed. Report
  them, with logcat.

**"\<n\> stale classes: what ran is not what this build shipped. Their lines are in error; build and
install again."**, listing each class with its module. The running app executed other bytes than the
ones this build shipped, typically code that reached the app without a new install of the build.
Their lines show the Error marker, never red. Build and install again.

### Warnings on the overview

**"\<n\> warnings"**, listing:

```text
:<module> ships classes for <path>, which the app excludes (<rule>): they are ignored, and the file shows as excluded. Set the exclude rules once, in the convention plugin that applies ColdSpot, so that every module excludes the same files.
```

A library module and the app have different exclude rules. The app's win: the file shows as excluded.
Set the rules once, in the convention plugin; see [Exclude rules](configuration.md#when-modules-disagree).

```text
:<module> ships classes for <path>, which the app's manifest has neither as changed nor as excluded: the modules were not built from the same change.
```

A module's bundle and the app's describe different changes. Build again, all of it, from the same
working tree.

### In the header

- **"No base was set: origin/HEAD, the remote's default branch, was used."** Nobody set the base, and
  `origin/HEAD` stood in. Set `coldSpot { baseRef }` to compare with another branch.
- **"Guessed: no base was set and the repository has no origin/HEAD, so origin/main was used. Set
  coldSpot { baseRef } to be sure."** As the build log's warning.
- **"Uncommitted changes were part of this build: it is not what the head commit holds."** The build
  had edits no commit has.
- **"Collecting since: nothing collected yet".** Nothing instrumented has run yet. JaCoCo's agent
  starts with the first changed class that runs.

### On a file's screen

- **"\<path\> is not among the changed files of this build any more."** The file was opened from an
  earlier analysis. Go back to the overview.
- **"ColdSpot cannot show this file: \<reason\>".** The screen failed. Report it.
- **"There is nothing to show of this file: \<module\> shipped classes for it, but the app's manifest
  has it neither as changed nor as excluded, so its text and its changed lines are unknown. The
  modules were not built from the same change; see the warning on the overview."** As the second
  warning above.
- **"No changed line of this file is measured."** The file is excluded, or not measurable.

## In logcat

Under the tag `ColdSpot`:

| message | what it means |
|---|---|
| `no JaCoCo agent in this build; ColdSpot stays idle` | as "ColdSpot is not running" above |
| `no Application context; ColdSpot stays idle` | ColdSpot was started without an application context, and does nothing |
| `<what> failed; the app goes on without it`, with a stack trace | one of ColdSpot's own operations failed; listed on the overview too |
| `the saved coverage cannot be read; nothing is saved until it can be` | as the "Coverage can't be saved" banner |
| `<what> failed: <reason>; the app goes on without it, and a save that fails the same way is logged in one line from now on` | a save failed: the first time in full, with its stack trace |
| `<what> failed again: <reason>` | the same save failure, again |
| `crash in thread <name>: saving the coverage first`, `could not save on crash` | the app is crashing, its own crash; ColdSpot saves on the way |
| `fresh-session build: saved coverage wiped once` | a `-Pcoldspot.freshSession` build wiped the saved coverage |
| `reset: probes cleared, saved coverage deleted` | a reset happened |
| `reset asked, but ColdSpot is not running` (and the same for the dump and the bubble) | a broadcast or call reached an idle ColdSpot |
| `the bubble is hidden until the app restarts` | "Hide until restart" was chosen |
| `the bubble is hidden, as asked earlier in this install; ...` | the bubble was hidden on an earlier launch; the message says how to bring it back |
| `a new install: what was said about the bubble earlier is forgotten, it shows as this build says` | a new build was installed |
| `could not save the bubble's visibility; it holds until the app restarts` | the bubble's setting could not be stored |

## Installing

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** when installing a build made on another machine over a local
one, or the other way round: the two were signed with different debug keys. Uninstalling first also
deletes the coverage saved so far. Share one debug keystore: commit it, and point the `debug` signing
config at it. The coverage build type ColdSpot creates is made from `debug`, signing included.
