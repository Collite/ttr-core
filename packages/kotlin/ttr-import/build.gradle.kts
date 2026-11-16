// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":packages:kotlin:ttr-profile"))
    // The generated header (GeneratedHeader/GeneratedInputs/Hashes) and, through it, the canonical TtrRenderer.
    api(project(":packages:kotlin:ttr-metadata"))
    api(project(":packages:kotlin:ttr-writer"))
    testImplementation(libs.bundles.kotest)
}

// MW-P5 S5.1 (contracts §3): the profiles ttr-core ships live in the repository's top-level `profiles/` and are bundled
// as `/profiles/<name>/<major>.yaml`, so `--profile om-tables/1` resolves without a file.
val shippedProfiles = rootProject.layout.projectDirectory.dir("profiles")
tasks.processResources {
    from(shippedProfiles) { into("profiles") }
}

tasks.test {
    useJUnitPlatform()
    inputs.dir(shippedProfiles)
    inputs.file("../../grammar/schemas/profile.schema.json")
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-import", version.toString())
    pom {
        name.set("TTR Import")
        description.set(
            "The TTR reader harness: format front-ends, shipped profiles and generated .ttrm packages with sidecars",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/Collite/ttr-core")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("collite")
                name.set("Collite")
                url.set("https://github.com/Collite")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/Collite/ttr-core.git")
            developerConnection.set("scm:git:git@github.com:Collite/ttr-core.git")
            url.set("https://github.com/Collite/ttr-core")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Collite/ttr-core")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
