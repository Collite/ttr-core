// SPDX-License-Identifier: Apache-2.0
// The C-5-i plan-validator plugin SPI (contracts §9, PL-P4.S2) — an OPEN (Apache-2.0) interface the
// platform's validation organ hosts and third parties (e.g. kantheon's llm-guard) implement. Deliberately
// DEPENDENCY-FREE: `ValidationContext.plan` is opaque bytes (plan.v1 for the QUERY door; door-specific for
// PROGRAM — see tatrman-platform#16), so any org can implement the contract with no proto/plan dependency.
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `java-test-fixtures` // the RecordingFakePlugin ships as a published test fixture (S2.T1) for host suites.
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
    testImplementation(testFixtures(project(":packages:kotlin:ttr-validator-spi")))
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-validator-spi", version.toString())
    pom {
        name.set("TTR Validator SPI")
        description.set(
            "The C-5-i plan-validator plugin SPI (Pass|Deny|Advise; plugins never rewrite plans) hosted by " +
                "the Tatrman platform's validation organ.",
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
