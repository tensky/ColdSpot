package id.tensky.coldspot.plugin

/**
 * The debug counterpart of a dependency configuration of the coverage build type, by name: `coverageImplementation`
 * is `debugImplementation`'s twin, `demoCoverageRuntimeOnly` is `demoDebugRuntimeOnly`'s, `kspCoverage` is
 * `kspDebug`'s. AGP, kapt and KSP all spell a build type into a configuration name the same way, as one camel-case
 * word, first when it leads (`coverageApi`) and capitalised after a flavor or a prefix (`kspDemoCoverage`), so the
 * twin is the name with that word swapped. Null when the name does not carry the build type as a word.
 */
internal fun twinConfigurationName(name: String, buildType: String, twin: String): String? {
    if (name.startsWith(buildType) && name.length > buildType.length && name[buildType.length].isUpperCase()) {
        return twin + name.substring(buildType.length)
    }
    val word = buildType.replaceFirstChar { it.uppercase() }
    var from = 1
    while (from < name.length) {
        val at = name.indexOf(word, from)
        if (at < 1) return null
        val end = at + word.length
        val wordEnds = end == name.length || name[end].isUpperCase()
        if (!name[at - 1].isUpperCase() && wordEnds) return name.substring(0, at) + twin.replaceFirstChar { it.uppercase() } + name.substring(end)
        from = at + 1
    }
    return null
}
