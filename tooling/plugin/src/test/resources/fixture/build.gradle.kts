// The smallest Android application the plugin can be applied to. The tests fill in the two marked lines.
// AGP comes without a version: TestKit puts it on the same classpath as the plugin under test (see the plugin's
// build file), so this is the AGP of the version catalog.
plugins {
    id("com.android.application")
    id("io.github.tensky.coldspot")
}

android {
    namespace = "fixture.app" // also the application ID, which the manifest merger wants dotted
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        minSdk = 24
    }
    @ANDROID_EXTRAS@
}

@COLDSPOT@

// What the build types ended up as once the DSL was final. A provider, so that it is read after the
// plugin's finalizeDsl callback rather than while this script is still running.
val buildTypeReport = provider {
    android.buildTypes.sortedBy { it.name }.map {
        "${it.name}: debuggable=${it.isDebuggable} androidTestCoverage=${it.enableAndroidTestCoverage} suffix=${it.applicationIdSuffix} fallbacks=${it.matchingFallbacks}"
    }
}

tasks.register("printBuildTypes") {
    val report = buildTypeReport
    doLast { report.get().forEach(::println) }
}

tasks.register("printExcludes") {
    val excludes = coldSpot.excludes
    doLast { println("excludes=${excludes.get()}") }
}

tasks.register("printBase") {
    val base = coldSpot.effectiveBaseRef
    doLast { println("base=${base.orNull}") }
}

// `-Pnames=a,b,c`: each configuration and the names of the configurations it extends, sorted; "absent" if there is none.
tasks.register("printConfigurationParents") {
    val names = providers.gradleProperty("names").map { it.split(",") }
    val report = provider {
        names.get().map { name -> "$name <- ${configurations.findByName(name)?.extendsFrom?.map { it.name }?.sorted() ?: "absent"}" }
    }
    doLast { report.get().forEach(::println) }
}
