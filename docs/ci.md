# CI builds (not yet confirmed)

> **Not confirmed.** ColdSpot has not been run on a real CI service yet. The rules on this page are
> how the build behaves. The set-ups are suggestions, tried only on local clones made the way CI
> makes them. If you try one, please
> [tell us how it went](https://github.com/tensky/ColdSpot/issues).

- [What a CI build needs](#what-a-ci-build-needs)
- [Set-ups to try](#set-ups-to-try)
- [What to expect](#what-to-expect)

## What a CI build needs

A coverage build compares the working tree with the git history, so on CI it needs two things a
default checkout does not give it:

- **The base, given.** When the `CI` environment variable is `true`, as GitHub Actions, GitLab CI
  and most others set it, ColdSpot takes no base it was not given. Pass
  `-Pcoldspot.base=origin/<target branch>`, or set `coldSpot { baseRef }`, or the build fails,
  saying so. On a developer's machine ColdSpot falls back to `origin/HEAD` or a guess, which on CI
  could name the wrong branch for a pull request with nobody there to notice.
- **The full history.** CI checkouts are cut short by default. ColdSpot never fetches, and it
  refuses a history that is cut between HEAD and the base, saying where it stops.

This holds for every task that builds the coverage variant, **`assemble` and `build` included**. A
job that only runs `assembleDebug`, `test` or `lint` does not run the diff and needs neither.

## Set-ups to try

### GitHub Actions

`actions/checkout` fetches one commit by default, and for a pull request checks out its merge
commit, detached, with no other branch and no `origin/HEAD`:

```yaml
- uses: actions/checkout@v4
  with:
    fetch-depth: 0          # every branch, with its whole history
- run: ./gradlew :app:assembleCoverage -Pcoldspot.base=origin/${{ github.base_ref || github.event.repository.default_branch }}
```

`github.base_ref` is the pull request's target branch. A push has none, and the default branch
stands in.

### GitLab CI

The runner clones shallowly by default (`GIT_DEPTH`, which a project sets), and which branches a
merge request pipeline has depends on its refspecs. Fetching the target branch explicitly makes sure
of it:

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

## What to expect

| checkout | what we expect |
|---|---|
| full history, the base given | builds, with the same result as a developer's clone of that commit |
| a pull request's detached merge commit, full history | builds, compared from the target branch's tip |
| the default shallow checkout | fails with `shallow clone detected`, saying where the history stops: fetch the full history |
| no base given | fails with `No base was given, and on CI (CI=true) ColdSpot takes none it is not given` |
| no `.git` at all (a source archive) | fails: there is no history to compare with |

Every message, with its cause and fix, is in [Troubleshooting](troubleshooting.md#the-diff).

A CI build signs with its own debug key unless the project shares one, so it does not install over a
local build: see [Installing](troubleshooting.md#installing).
