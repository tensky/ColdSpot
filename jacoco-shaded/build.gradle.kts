import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.util.zip.ZipFile

// jacoco-core with its ASM, relocated under id.tensky.coldspot.shaded: the one jar ColdSpot's runtime compiles against
// and packs into its AAR (as a local jar, libs/). Shadow cannot run inside an Android library, and an AAR carries only
// the library's own classes and its local jars, so the relocation happens here, before the runtime sees jacoco-core:
// the runtime's sources name the relocated packages, and what its unit tests run is what the device runs. The JaCoCo
// agent runtime is not in it, and is never relocated: the instrumented classes call it by name.
plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(libs.jacoco.core)
    // jacoco-core's ASM held to one version, asm's: the one the plugin's instrumenter uses.
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
}

val shadedPrefix = "id.tensky.coldspot.shaded"

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.jacoco.core", "$shadedPrefix.org.jacoco.core")
    relocate("org.objectweb.asm", "$shadedPrefix.org.objectweb.asm")
    // Module descriptors, Maven metadata and the libraries' own license files: META-INF/coldspot/THIRD-PARTY-NOTICES.txt
    // covers them instead, under a name no other library in an app uses.
    exclude("module-info.class", "META-INF/versions/*/module-info.class", "META-INF/maven/**", "META-INF/LICENSE*", "META-INF/NOTICE*", "about.html")
}

/** Fails `check` when THIRD-PARTY-NOTICES.txt does not name every library the jar holds, at its version. */
val checkNotices by tasks.registering {
    group = "verification"
    description = "Fails when the third-party notices miss a library the shaded jar holds."
    val notices = layout.projectDirectory.file("src/main/resources/META-INF/coldspot/THIRD-PARTY-NOTICES.txt")
    inputs.file(notices)
    val shaded = configurations.runtimeClasspath.get().incoming.resolutionResult.rootComponent.map { root ->
        val seen = LinkedHashSet<String>()
        fun visit(component: ResolvedComponentResult) {
            for (dependency in component.dependencies) {
                if (dependency is ResolvedDependencyResult && seen.add(dependency.selected.id.displayName)) visit(dependency.selected)
            }
        }
        visit(root)
        seen.sorted()
    }
    val jacoco = libs.versions.jacoco.get()
    inputs.property("jacoco", jacoco)
    doLast {
        val text = notices.asFile.readText()
        val missing = shaded.get().filter { it !in text }
        check(missing.isEmpty()) { "THIRD-PARTY-NOTICES.txt does not name ${missing.joinToString()}: the shaded jar holds them" }
        // jacoco-core is EPL-2.0: distributed in object code, its entry says where its source is, and that it was relocated.
        val entry = text.split(Regex("\n\\s*\n")).firstOrNull { "org.jacoco:org.jacoco.core:$jacoco" in it }.orEmpty().replace(Regex("\\s+"), " ")
        val unsaid = listOf(
            "Its source code is available under the EPL-2.0",
            "https://github.com/jacoco/jacoco/tree/v$jacoco",
            "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.core/$jacoco/org.jacoco.core-$jacoco-sources.jar",
            "relocated under id.tensky.coldspot.shaded",
        ).filter { it !in entry }
        check(unsaid.isEmpty()) {
            "THIRD-PARTY-NOTICES.txt: the entry of jacoco-core $jacoco (EPL-2.0) must say where its source is and that its classes are relocated; it lacks: ${unsaid.joinToString(" | ")}"
        }
    }
}

/** Fails `check` when the jar holds a class outside the shaded prefix, or lacks its notices. */
val checkShadedJar by tasks.registering {
    group = "verification"
    description = "Fails when the shaded jar holds a class that is not relocated under $shadedPrefix."
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jar)
    doLast {
        ZipFile(jar.get().asFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val foreign = names.filter { it.endsWith(".class") && !it.startsWith("id/tensky/coldspot/shaded/") }
            check(foreign.isEmpty()) { "the shaded jar holds classes outside id/tensky/coldspot/shaded: ${foreign.take(10)}" }
            check("META-INF/coldspot/THIRD-PARTY-NOTICES.txt" in names) { "the shaded jar has no META-INF/coldspot/THIRD-PARTY-NOTICES.txt" }
            check("id/tensky/coldspot/shaded/org/jacoco/core/jacoco.properties" in names) { "JaCoCo's version resource was not relocated with it" }
        }
    }
}

tasks.check {
    dependsOn(checkNotices, checkShadedJar)
}
