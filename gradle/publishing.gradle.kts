// What every published ColdSpot artifact shares, applied after `maven-publish` and `signing` by tooling's plugin and
// manifest and by the main build's runtime: the POM's project metadata, on every MavenPublication of theirs, the Gradle
// plugin's marker included (each module names and describes its own); a README in every javadoc jar; the repository a
// Maven Central bundle is staged in; and signing, when a key is at hand. Nothing is uploaded from here: the bundle is
// zipped by testbeds/central-bundle.sh and uploaded by hand (testbeds/RELEASING.md).

// The repository's root: the main build's settings directory, or the tooling build's parent.
val repoRoot: File = layout.settingsDirectory.asFile.let { dir -> if (dir.resolve("gradle/publishing.gradle.kts").isFile) dir else dir.parentFile }

configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        pom {
            url.set("https://github.com/tensky/coldspot")
            inceptionYear.set("2026")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("tensky")
                    name.set("tensky")
                    url.set("https://github.com/tensky")
                }
            }
            scm {
                url.set("https://github.com/tensky/coldspot")
                connection.set("scm:git:https://github.com/tensky/coldspot.git")
                developerConnection.set("scm:git:ssh://git@github.com/tensky/coldspot.git")
            }
        }
    }
    repositories {
        // A Maven Central bundle, staged as a plain file repository, which makes maven-publish write the .md5 and .sha1
        // of every file. Both builds stage into this one; testbeds/central-bundle.sh checks it and zips it.
        maven {
            name = "centralStaging"
            url = uri(repoRoot.resolve("build/central-staging"))
        }
    }
}

// Maven Central asks every jar and AAR for a javadoc jar. ColdSpot's documentation is its repository, so each javadoc
// jar carries a README that points there: beside the API pages AGP generates for the runtime, alone in the others.
val javadocReadme = repoRoot.resolve("gradle/javadoc-README.md")
tasks.configureEach {
    if (name == "javadocJar" || name == "javaDocReleaseJar") (this as AbstractCopyTask).from(javadocReadme) { rename { "README.md" } }
}

// Signed with a key at hand, as Gradle properties: ORG_GRADLE_PROJECT_signingInMemoryKey (the ASCII-armoured secret
// key), ORG_GRADLE_PROJECT_signingInMemoryKeyId and ORG_GRADLE_PROJECT_signingInMemoryKeyPassword in the environment.
// Without one, a build still publishes, unsigned, anywhere but the Central staging repository: publishToMavenLocal, for
// one, as the consumer check does.
val signingKey = providers.gradleProperty("signingInMemoryKey")
val signingKeyGiven = signingKey.isPresent
if (signingKeyGiven) {
    configure<SigningExtension> {
        useInMemoryPgpKeys(
            providers.gradleProperty("signingInMemoryKeyId").orNull,
            signingKey.get(),
            providers.gradleProperty("signingInMemoryKeyPassword").orNull,
        )
        sign(the<PublishingExtension>().publications)
    }
}

// A bundle for Maven Central is never staged unsigned: without a key, publishing there fails before writing anything.
// The tasks are told apart by name (publish<Publication>PublicationToCentralStagingRepository): a task's repository is
// not kept in the configuration cache, so it cannot be asked while the task runs.
if (!signingKeyGiven) {
    tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToCentralStagingRepository")) {
            val task = path
            doFirst {
                throw GradleException(
                    "ColdSpot: no signing key, so nothing is staged for Maven Central ($task). Set " +
                        "ORG_GRADLE_PROJECT_signingInMemoryKey, ORG_GRADLE_PROJECT_signingInMemoryKeyId and " +
                        "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: see testbeds/RELEASING.md.",
                )
            }
        }
    }
}
