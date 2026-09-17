// The POM every published ColdSpot artifact carries, and signing when there is a key. Applied, after `maven-publish` and
// `signing`, by tooling's plugin and manifest and by the main build's runtime. No repository is declared anywhere:
// nothing is uploaded; `publishToMavenLocal` is the one place these publications go until publishing is settled.
//
// TODO(publishing): the project's home page and SCM location, and the developer entry are still to be decided; every
// placeholder below says so. Maven Central refuses a POM without them.

configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        pom {
            url.set("TODO(publishing): the project's home page")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("TODO(publishing): developer id")
                    name.set("TODO(publishing): developer or organisation name")
                }
            }
            scm {
                url.set("TODO(publishing): the repository's URL")
                connection.set("TODO(publishing): scm:git:<read-only URL>")
                developerConnection.set("TODO(publishing): scm:git:<read-write URL>")
            }
        }
    }
}

// Signed only with a key at hand, as Gradle properties (for example ORG_GRADLE_PROJECT_signingInMemoryKey in the
// environment): every build without one, a local publishToMavenLocal included, publishes unsigned.
val signingKey = providers.gradleProperty("signingInMemoryKey")
if (signingKey.isPresent) {
    configure<SigningExtension> {
        useInMemoryPgpKeys(
            providers.gradleProperty("signingInMemoryKeyId").orNull,
            signingKey.get(),
            providers.gradleProperty("signingInMemoryKeyPassword").orNull,
        )
        sign(the<PublishingExtension>().publications)
    }
}
