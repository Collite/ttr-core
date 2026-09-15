// SPDX-License-Identifier: Apache-2.0
// The E-1/EQ-1 emit-plugin SPI (contracts §8, PL-P5.S1) — an OPEN (Apache-2.0) interface the toolchain's
// bundle assembler hosts and per-target emitters (bash, kestra, airflow3, …) implement. Deliberately
// DEPENDENCY-FREE (no serialization, no ttrp-emit dep): a plugin sees only the projected OrchestrationGraph +
// verbatim payload bytes, so any org can implement the contract with no toolchain-internal dependency.
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `java-test-fixtures` // the RecordingFakePlugin ships as a published test fixture for host + kit suites.
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
    testImplementation(libs.bundles.kotest)
    testImplementation(testFixtures(project(":packages:kotlin:ttrp-emit-spi")))
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttrp-emit-spi", version.toString())
    pom {
        name.set("TTR-P Emit SPI")
        description.set(
            "The E-1/EQ-1 emit-plugin SPI (TtrEmitPlugin: emit orchestration files from a projected graph; " +
                "island payloads are handed in verbatim) hosted by the Tatrman TTR-P toolchain's bundle assembler.",
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
