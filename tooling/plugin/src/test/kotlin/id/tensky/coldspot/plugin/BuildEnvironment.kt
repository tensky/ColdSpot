package id.tensky.coldspot.plugin

/*
 * The environment TestKit's builds run with. Without `CI`: on CI (the variable `true`) the base must be given
 * (DECISIONS.md), and most fixtures leave it to `origin/main`, so no test may depend on where it runs.
 */

/** This process's environment, without `CI`: a developer's build. */
internal fun localEnvironment(): Map<String, String> = System.getenv() - "CI"

/** [localEnvironment] with `CI=true`: a build on CI. */
internal fun ciEnvironment(): Map<String, String> = localEnvironment() + ("CI" to "true")
