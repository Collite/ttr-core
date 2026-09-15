// SPDX-License-Identifier: Apache-2.0
// PL-P5.S4 — the Airflow 3 emit plugin (contracts §8, EQ-3). `targetId = "airflow3"`. A CODE-defined target
// (Python DAG), so emit is string-template codegen with the heavier determinism burden (no timestamps, stable
// task ids). TWO world-driven bindings (E-3-γ) that must never blur: NATIVE (standalone, an emit target — no
// platform on the runtime path) and DOOR-CALLING (platform world, a frontend — a whole-program task calling the
// program door). Ships the Airflow 3 executor-type manifest (§7) and registers via META-INF/services.
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
    testImplementation(libs.bundles.kotest)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-emit-airflow3", version.toString())
    pom {
        name.set("TTR-P Emit — Airflow 3")
        description.set(
            "The Airflow 3 emit plugin (org.tatrman:ttr-emit-airflow3) — renders a native or door-calling " +
                "Python DAG (E-3-γ) from the orchestration graph behind the E-1/EQ-1 emit SPI. Deterministic codegen.",
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
