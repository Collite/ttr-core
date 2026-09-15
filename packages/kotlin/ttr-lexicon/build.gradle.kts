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
    // YAML only — the house parser (same as ttr-import-schema's ConventionsLoader). Composed
    // rather than loaded, so every term keeps the line it was authored on.
    implementation(libs.snakeyaml)
    // The compiled-artifact codec (Artifact.kt). `api` — a consumer that reads a compiled
    // lexicon holds CompiledLexicon values and re-serializes them.
    api(libs.kotlinx.ser.json)

    testImplementation(libs.bundles.kotest)
    // The two `.schema.json` resources are the published contract, and `SchemaEquivalenceSpec`
    // holds the Kotlin validator to them fixture by fixture. TEST scope on purpose: networknt
    // drags jackson-databind, and a toolchain artifact consumed by every TTR-P project should
    // not ship a JSON stack to enforce rules it already enforces.
    testImplementation(libs.json.schema.validator)
}

// RV-P1.1 — see `docs/features/resolution/lexicon-schemas.md`. In the `grammar` bundle since
// 2026-08-03: `ttr-lexicon-compile` `api`s this module, and that module is version-coupled to
// ttr-parser/ttr-metadata/ttr-snapshot, so a separate lane would let a compiler POM name a
// ttr-lexicon version nothing forced to be cut (the 0.10.3 failure). One version line, one tag.
mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-lexicon", version.toString())
    pom {
        name.set("TTR Lexicon")
        description.set("ttr-lexicon/v1 + ttr-skill/v1 schemas, validator and typed lexicon-area model")
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
