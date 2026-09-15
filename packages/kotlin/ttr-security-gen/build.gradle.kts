// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) // data.json is emitted via kotlinx.serialization (deterministic)
    alias(libs.plugins.ktlint)
    `java-library`
    application
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}

kotlin {
    jvmToolchain(21)
}

// Unit tier — the PURE deterministic core (security blocks → Rego fragments +
// data.json). No I/O in the core; the CLI is the only edge. Determinism (same
// block ⇒ same bytes, S3.T3) is proven here against committed goldens.
tasks.test {
    useJUnitPlatform()
    systemProperty("updateGolden", System.getProperty("updateGolden") ?: "false")
}

// PL-P4.S3 — the H-1 security-block → Rego generator. One generator, two callers
// (I-2): the CLI verb `ttr security-gen` for humans/CI, and Perun's build pipeline
// (S4) calling the published library in-process. Depends ONLY on ttr-parser (it
// reads `security { }` blocks); the block is grammar-structured, so no resolution
// is needed to emit fragments — object refs are sanitized verbatim (qname
// canonicalisation is Perun's §19 composition concern).
application {
    applicationName = "ttr"
    mainClass = "org.tatrman.ttr.securitygen.cli.MainKt"
}

dependencies {
    api(project(":packages:kotlin:ttr-parser"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.ser.json)

    testImplementation(libs.bundles.kotest)
}

ktlint {
    filter {
        exclude("**/generated/**")
        exclude { it.file.path.contains("/generated-src/antlr/") }
    }
}

// SV-P1 S4 — Maven Central (Central Portal) is the PUBLIC lane (RO-17). vanniktech
// owns the publication (sources + javadoc jars Central requires) + the Central
// target; the GH Packages block below is the pre-release staging lane. Published
// as `org.tatrman:ttr-security-gen` so Perun (S4, tatrman-platform) consumes it as
// a PUBLISHED artifact (P2 — never a project link).
mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-security-gen", version.toString())
    pom {
        name.set("TTR Security-block Generator")
        description.set(
            "Deterministic, one-way generator: TTR-M `security { }` blocks → OPA/Rego policy " +
                "fragments + structured data (the H-1 sugar). For the TTR (Tatrman) DSL.",
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
