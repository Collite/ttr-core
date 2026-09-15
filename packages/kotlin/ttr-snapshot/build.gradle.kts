// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // The archive is the content-addressed transport of canon; SnapshotArchiveStorage
    // implements ttr-metadata's ModelStorage SPI (contracts §2), so ttr-metadata is `api`.
    api(project(":packages:kotlin:ttr-metadata"))
    // Deterministic tar (pinned) + zstd. Versions fixed in the catalog for byte-determinism
    // (plan.md risk #1) — the golden archive hash depends on the compressor version.
    implementation(libs.apache.commons.compress.snapshot)
    implementation(libs.zstd.jni)
    // snapshot.json codec.
    implementation(libs.kotlinx.ser.json)

    testImplementation(libs.bundles.kotest)
    // Pack/round-trip the shared erp/world fixture tree; compare loads against LocalFsStorage.
    testImplementation(testFixtures(project(":packages:kotlin:ttr-metadata")))
}

ktlint {
    filter {
        exclude("**/generated/**")
    }
}

// SV-P1 S4 — Maven Central (Central Portal) is the PUBLIC lane (RO-17). vanniktech
// owns the publication (adds the sources + javadoc jars Central requires) and the
// Central target; the GH Packages block below stays as the pre-release staging lane.
mavenPublishing {
    publishToMavenCentral()
    // Central requires signatures and the Central CI lane supplies the key
    // (ORG_GRADLE_PROJECT_signingInMemoryKey). Local builds + the GH Packages
    // staging lane don't sign — gate signAllPublications() on the key's presence
    // so it doesn't hard-fail those (it fails a non-SNAPSHOT version when unkeyed).
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-snapshot", version.toString())
    pom {
        name.set("TTR Snapshot")
        description.set("Deterministic content-addressed snapshot archives + cache (the ② seam transport)")
        inceptionYear.set("2025")
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
