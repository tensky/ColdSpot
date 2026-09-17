# Using ColdSpot

What you see in a coverage build, and what it means. Setting ColdSpot up is in the
[README](../README.md#setup); every setting is in [Configuration](configuration.md).

- [Build a change first](#build-a-change-first)
- [Opening ColdSpot](#opening-coldspot)
- [The overview](#the-overview)
- [A file](#a-file)
- [Line markers](#line-markers)
- [Sharing a summary](#sharing-a-summary)
- [Reset and fresh sessions](#reset-and-fresh-sessions)
- [Coverage across launches and builds](#coverage-across-launches-and-builds)
- [Look and accessibility](#look-and-accessibility)

## Build a change first

ColdSpot shows the lines that changed against the base, and which of them ran, so it needs a change
to show. Build on a feature branch, or pass an older commit with `-Pcoldspot.base=<commit>`. On a
main that was just pushed, the base, `origin/HEAD`, is the very commit being built: nothing
changed, and the overview says only that there are no changed lines ("No changed lines against
origin/HEAD @ ... Nothing to execute: what was built is what the base has.").

## Opening ColdSpot

### The bubble

<!-- SCREENSHOT: docs/images/bubble.png — an app screen with ColdSpot's bubble at its right edge -->

The bubble is a 48 dp view ColdSpot adds to each of the app's activities once its window is up:
ColdSpot's small mark, the beetle in its ice cube, on a white disc with a light rim, as the logo
draws it at that size. It needs no
permission, since it is part of the activity's own window and not an overlay, and it takes touches
inside its own bounds only.

- **Tap** it to open ColdSpot's screen.
- **Drag** it to move it. Let go, it goes to the nearer edge, to one place for every activity,
  remembered across launches.
- **Long press** it for "Hide until restart".

It keeps clear of system bars and display cutouts, in edge-to-edge windows and on a turned screen,
never shows on ColdSpot's own screen or in floating (dialog) activities, and is labelled "Open
ColdSpot" for TalkBack.

The "Show bubble" switch on ColdSpot's screen hides it and brings it back. Hidden, it comes back
with the next build you install. `bubble = false` in `coldSpot { }` leaves it out of a build
altogether; see [Entry points](configuration.md#entry-points) for every way to show or hide it.

### The launcher icon

With `launcherIcon = true` in `coldSpot { }`, the launcher shows a second icon, "ColdSpot", next to
the app's. It opens the same screen.

### From code

`ColdSpot.open(context)` opens ColdSpot's screen from a team's own debug menu. From an activity it
opens on top of it, and back returns there; from any other context it opens as a task of its own.
The call belongs in the coverage variant's source set, `src/coverage/`: see
[Runtime API](configuration.md#runtime-api).

## The overview

<!-- SCREENSHOT: docs/images/overview-light.png — the overview with red, amber and green files, light theme -->
<!-- SCREENSHOT: docs/images/overview-dark.png — the same overview, dark theme -->

The overview opens from the bubble, the launcher icon or `ColdSpot.open`. The analysis runs when it
opens and on Refresh, off the main thread. From the top:

- **What was built.** The base ref @ SHA, and how it came about when nobody set it ("No base was
  set: origin/HEAD, the remote's default branch, was used", or "Guessed: ..."); the head's
  branch @ SHA; a warning when uncommitted changes were part of the build ("Uncommitted changes
  were part of this build: it is not what the head commit holds."); when it was installed, which is
  the package's last update, since the manifest carries no build time (that would change it with
  every build); since when coverage has been collected; and the commits, folded, newest first, as
  many as the manifest lists (50), then "+N more".
- **Banners**, in this order:
  - coverage that cannot be saved ("Coverage can't be saved: the saved coverage cannot be read
    (...)"): what the running app gathered is shown all the same, and none of it outlives the app
    until a save succeeds;
  - errors;
  - stale classes: what ran is not what the build shipped, so their lines are in error, never red;
  - warnings.

  Another JaCoCo than the build's takes the whole screen ("Nothing can be coloured"): nothing is
  coloured. Every message is explained in [Troubleshooting](troubleshooting.md#in-the-app).
- **The total**, "41 / 65 changed lines executed (63%)", the percentage rounded down: 100% is every
  line and nothing less.
- **The changed files, worst first**: in error, never executed, partly executed, fully executed,
  nothing to execute; among equals, the one with more left to execute first. Each row has an icon, a
  colour and a count ("18/20"), the file's name, its directory dimmed, and its module
  (`:feature:login`).
- **Folded, with their counts and a reason per file**: "Not measurable", files no module of this
  app shipped a class for, and "Excluded", files an exclude rule left out.
- **The "Show bubble" switch, and Reset coverage…**, which asks first.

<!-- SCREENSHOT: docs/images/overview-banners.png — the overview with a stale-class banner and a warning banner -->

## A file

<!-- SCREENSHOT: docs/images/file-detail-light.png — a file with executed, partly executed, not executed and can't-be-measured lines, light theme -->

A file shows its path, module and status, then:

- a summary of the lines still to look at, "Not executed: lines 40–58, 72", and "Can't be measured:
  line 20" when there are blind lines, as many ranges as the share summary lists, then "+N more";
  TalkBack hears "40 to 58";
- a legend, "What the markers mean";
- its changed lines as hunks with three lines of context, what lies between counted ("⋯ 12 unchanged
  lines"): at code density, with line numbers, monospace, long lines wrapped, the marker and the
  number on a wrapped line's first line.

Every changed line has a marker in the gutter, each a shape of its own. Tapping anywhere on a changed
line's row says what the marker means for it: "12 of 15 instructions executed", "Can't be measured:
code inside an inline lambda, ...", the preview or the stale class by name. TalkBack puts its focus
on one line at a time, the whole row, activates it with a double tap, and reads the explanation out
as it changes.

A file only a module manifest names has no text, and says why instead.

<!-- SCREENSHOT: docs/images/line-explained.png — a file with one line tapped and its explanation, "12 of 15 instructions executed" -->

## Line markers

A line's colour comes from instruction counts only: of the instructions on the line, how many ran.
Branch coverage never changes it.

| marker | colour | what it means |
|---|---|---|
| Executed | green | every instruction on the line executed |
| Partly executed | amber | some of its instructions executed |
| Not executed | red | none of its instructions executed |
| Can't be measured | amber | code inside an inline lambda, which JaCoCo cannot trace back to this line: it may have run, or not. Never red, never green |
| No code | neutral | the changed line holds no instructions, as a comment, a blank line or a lone brace usually does |
| Preview | neutral | a Compose preview's line: previews never run in the app, so they are never red |
| Error | error | the class that ran is not the class this build shipped: build and install again |

A line in a preview is a preview first, then a line a stale class covers is an error, then a blind
line can't be measured; every other line is coloured by its counts. A file's own marker sums up its
changed lines: fully executed, partly executed, never executed, nothing to execute, or error.

## Sharing a summary

<!-- SCREENSHOT: docs/images/share-summary.png — the share sheet with ColdSpot's summary text -->

"Share summary" sends a text summary through the system's chooser, for a pull request, a ticket or
a chat. In this order:

1. the header: branch, base and head, the uncommitted warning, the device, when it was installed,
   since when coverage has been collected;
2. the total ("unknown" when another JaCoCo means nothing was measured);
3. the files, worst first (in error, never, partly, fully executed), with the line ranges still to
   execute, "not executed" apart from "can't be measured";
4. not measurable, previews, excluded, errors and warnings, each said to be "none" when it is;
5. ColdSpot's version.

Bold, inline code and simple bullets only, so it reads the same in a GitHub or GitLab comment and in
Slack, Jira or email. Long lists are cut off with "+N more". Paths and line numbers only, never a
line of source.

## Reset and fresh sessions

Coverage keeps building up across launches and builds until something starts it afresh:

- **Reset coverage…** on the overview asks first, then deletes the saved coverage and clears what
  this launch has executed so far. Collecting starts again from that moment.
- **`ColdSpot.reset()`** does the same from code, and the RESET broadcast from adb:
  see [adb broadcasts](configuration.md#adb-broadcasts).
- **A fresh-session build**, `-Pcoldspot.freshSession`, wipes the saved coverage once, on its first
  launch.

`./gradlew clean` alone does not reset: a clean build makes the same class bytes, and coverage is
kept by those.

<!-- SCREENSHOT: docs/images/reset-dialog.png — the "Reset coverage?" confirmation -->

## Coverage across launches and builds

What the app executes is saved every 10 s while an activity is started, when the app goes to the
background, on memory pressure and on a crash, and it is kept per class: a class whose bytes did not
change keeps its coverage across rebuilds, and a changed class starts fresh. In practice that is per
file: files you edit start fresh, other files keep their coverage. The header always says since when
it has been collected.

A class changed since the last build, and not run since, is "not executed", never an error. Only the
running app executing other bytes than the ones the build shipped puts a class in error.

## Look and accessibility

The screens are plain platform views on `android.app.Activity`, light and dark (`values-night`),
laid out edge to edge where the platform does (API 35 on), text in `sp` with no fixed heights.
Every marker has a content description, and every target is 48 dp but a file's lines, which are at
code density with the whole row for a target. They are checked on API 24–37; the runtime declares
minSdk 21. The copy says "executed", never "tested".
