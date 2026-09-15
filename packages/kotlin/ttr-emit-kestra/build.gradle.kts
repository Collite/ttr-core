// SPDX-License-Identifier: Apache-2.0
// PL-P5.S3 — the Kestra emit plugin (contracts §8, EQ-3). `targetId = "kestra"`. The SECOND emit consumer:
// building a second target early is what proves the SPI is an SPI, not a bash-shaped hole. Kestra flows are
// declarative YAML — the emit-fitness champion — so emit is PURE DATA GENERATION (snakeyaml-engine, fixed
// DumpSettings ⇒ deterministic, diffable text). Ships the Kestra executor-type manifest (§7) and registers via
// META-INF/services. Standalone/native (E-3-β): the flow runs islands directly; the platform is never on the path.
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
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
    implementation(project(":packages:kotlin:ttrp-emit-spi"))
    implementation(libs.snakeyaml.engine)
    testImplementation(libs.bundles.kotest)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-emit-kestra", version.toString())
    pom {
        name.set("TTR-P Emit — Kestra")
        description.set(
            "The Kestra emit plugin (org.tatrman:ttr-emit-kestra) — renders a declarative Kestra flow (YAML) " +
                "from the orchestration graph behind the E-1/EQ-1 emit SPI. Pure data generation, deterministic.",
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
