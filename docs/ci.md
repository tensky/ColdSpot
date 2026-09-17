# Using ColdSpot in CI

A coverage build in CI works as one on a laptop, as long as the checkout has what the diff needs: the
history from HEAD back to where it forked from the branch it is compared with, and that branch. CI
checkouts are cut short by default, so two settings matter: the full history, and the target branch,
passed as the base.

- [GitHub Actions](#github-actions)
- [GitLab CI](#gitlab-ci)
- [On CI the base must be given](#on-ci-the-base-must-be-given)
- [Checkouts that fall short](#checkouts-that-fall-short)
- [Cost](#cost)
- [Signing](#signing)

## GitHub Actions

`actions/checkout` fetches one commit by default, and for a pull request checks out its merge commit,
detached, with no other branch and no `origin/HEAD`:

```yaml
- uses: actions/checkout@v4
  with:
    fetch-depth: 0          # every branch, with its whole history
- run: ./gradlew :app:assembleCoverage -Pcoldspot.base=origin/${{ github.base_ref || github.event.repository.default_branch }}
```

`github.base_ref` is the pull request's target branch; a push has none, and the default branch stands
in. For a pull request the merge commit is compared with the target branch's tip, its first parent:
exactly what the pull request changes, as merged.

## GitLab CI

The runner clones shallowly by default (`GIT_DEPTH`, which a project sets), and which branches a merge
request pipeline has is up to its refspecs; fetching the target branch explicitly makes sure of it:

```yaml
coverage-apk:
  variables:
    GIT_DEPTH: 0            # the whole history
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
  script:
    - git fetch origin "+refs/heads/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME:refs/remotes/origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME"
    - ./gradlew :app:assembleCoverage -Pcoldspot.base=origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME
```

## On CI the base must be given

When the `CI` environment variable is `true`, as GitHub Actions, GitLab CI and most others set it,
ColdSpot takes no base it was not given: without `-Pcoldspot.base` or `coldSpot { baseRef }` the
build fails, saying so. `origin/HEAD`, which a CI checkout usually lacks anyway, and the guesses
`origin/main` and `origin/master` are right for a change meant for main and wrong for one meant for
`develop`, and on CI nobody would see which it was. On a developer's machine they stay: the build
warns of a guess ("comparing with origin/main, a guess") and the app's header shows the base as
guessed. The build reads `CI` as a configuration-cache input, so a configuration cached without it is
not reused with it.

## Checkouts that fall short

ColdSpot never fetches, and never writes `.git`. Every case below fails the build with where the
history is cut, or what is missing, and the fix; none gives a diff that is wrong without saying so.
For a cut history the fix is always more of it, `fetch-depth: 0`: fetching a branch again never
deepens what a clone has already cut, and in actions/checkout's default checkout it is HEAD's own
history that is short. The maintainer's release check makes each of these checkouts of a scratch
copy of the sample app and builds it on the published artifacts, off CI and on it.

| checkout | what ColdSpot does |
|---|---|
| full history, the base given | works; the manifest is the one a developer's clone of the same commit gets, byte for byte, but for the branch name a detached checkout lacks |
| shallow, deep enough (HEAD and the base both reach their fork point) | works, exactly as with the full history |
| shallow, cut above the fork point | fails: `shallow clone detected: HEAD and 'origin/main' meet nowhere in the history this clone has: HEAD's history stops at <sha>, and the history of 'origin/main' at <sha>. Fetch the full history (e.g. fetch-depth: 0; ...)` |
| `--depth 1` (actions/checkout's default), the base given | fails: `shallow clone detected: 'origin/main' is not in this clone, and HEAD's history stops at <sha>. Fetch the full history with that branch (e.g. fetch-depth: 0; ...)` |
| the same with the base branch fetched besides | fails: HEAD's history stops at the merge commit; fetching main added nothing HEAD could reach |
| no base branch (single-branch clone, full history) | fails, naming the `git fetch` that brings it and `-Pcoldspot.base` |
| detached HEAD on a merge commit | works, compared from the target branch's tip |
| no base given, on CI | fails: `No base was given, and on CI (CI=true) ColdSpot takes none it is not given, neither origin/HEAD nor a guess: pass the branch the change is for as -Pcoldspot.base=origin/<target>, or set coldSpot { baseRef = "<ref>" }.`, plus the history to fetch when the clone is shallow as well |
| no base given, no `origin/HEAD`, off CI | guesses `origin/main`, with the warning above |
| partial clone (`--filter=blob:none`) | fails: JGit cannot fetch what the filter left out; clone without a filter, or let `git diff <base> -- "*.kt" "*.java"` fetch it first |
| no `.git` (a source archive) | fails: there is no history to compare with |

A shallow clone is diffed only when it can be shown to be enough: when nothing between HEAD, the
base, and their merge-base is cut. Otherwise a better merge-base could lie behind the cut, where git
itself would silently take an older one and show commits of main as the build's.

Every one of these messages, with its fix, is also in
[Troubleshooting](troubleshooting.md#the-diff).

## Cost

The diff runs once per build, at execution time, and a CI build pays for JGit's cold start every
time: a fresh Gradle process loads it, and warms it up, for that one diff. Measured on an Apple M5,
the diff (with the uncommitted-changes check) takes 0.95 to 1.35 s in a fresh process on this
repository (71 changed files) and 1.0 to 1.2 s on a large open-source Android app (some 3,000
commits), against 130 to 210 ms and 285 to 450 ms in a warm daemon: little next to the rest of a CI
build.

## Signing

CI signs the coverage build with its own debug keystore unless the project shares one, and Android
refuses to install an APK over one signed with another key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
Uninstalling first clears the app's data, and with it the coverage ColdSpot saved. To install CI's
coverage builds over each other and over local ones, and keep what was executed, commit a debug
keystore and point the `debug` signing config at it; the `coverage` build type ColdSpot creates is
made from `debug`, signing included:

```kotlin
android {
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("keystore/debug.keystore") // a debug key only: never a release key
    }
}
```
