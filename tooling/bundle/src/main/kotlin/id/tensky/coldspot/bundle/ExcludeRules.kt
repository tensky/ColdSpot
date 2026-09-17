package id.tensky.coldspot.bundle

/**
 * Which changed files are noise: not measured, but never dropped in silence either (the shared manifest lists
 * each with the rule that matched, see [Manifest.excluded]).
 *
 * Every pattern is an Ant-style glob matched against the whole work-tree-relative, `/`-separated path: `*` and
 * `?` stay within one path segment, `**` crosses segments, and a leading `**` followed by a slash matches at the
 * root as well as at any depth. [DEFAULT_PATTERNS] cover what a diff usually carries that the app never runs:
 * test source sets, test fixtures, and the build's own code.
 */
public class ExcludeRules(public val patterns: List<String>) {
    private val compiled: List<Pair<String, Regex>> = patterns.map { it to globToRegex(it) }

    /** The first of [patterns] that [path] matches, in the order given, or null when none does. */
    public fun matching(path: String): String? = compiled.firstOrNull { (_, regex) -> regex.matches(path) }?.first

    public companion object {
        /** Unit and instrumented tests, test fixtures, `buildSrc` and `build-logic`, at any depth. */
        public val DEFAULT_PATTERNS: List<String> = listOf(
            "**/src/test/**",
            "**/src/androidTest/**",
            "**/src/testFixtures/**",
            "**/buildSrc/**",
            "**/build-logic/**",
        )

        /** [DEFAULT_PATTERNS] alone. */
        public val DEFAULT: ExcludeRules = ExcludeRules(DEFAULT_PATTERNS)

        private fun globToRegex(glob: String): Regex {
            val regex = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when {
                    glob.startsWith("**/", i) -> {
                        regex.append("(?:.*/)?")
                        i += 3
                    }
                    glob.startsWith("**", i) -> {
                        regex.append(".*")
                        i += 2
                    }
                    glob[i] == '*' -> {
                        regex.append("[^/]*")
                        i++
                    }
                    glob[i] == '?' -> {
                        regex.append("[^/]")
                        i++
                    }
                    else -> {
                        regex.append(Regex.escape(glob[i].toString()))
                        i++
                    }
                }
            }
            return Regex(regex.toString())
        }
    }
}
