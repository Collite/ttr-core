// SPDX-License-Identifier: Apache-2.0
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

// CN (conceptual model, `project/tatrman/features/conceptual/`) — `ttr-concept`: the three function families
// below the frontier (statements · assertions · definition rendering on the md and er layers), contracts §3.
// CN-P0 S0.4: the SCAFFOLD — signatures and the arch guard only; every body is CN-P3. A pure function of
// resolved, cached inputs (the S6 / PL-P0.S2 compile rule): no LLM, no server call, no I/O mid-lower.
dependencies {
    // api: the `statement.v1` / `assertion.v1` / `concept.v1` messages are in ConceptService's signatures.
    api(project(":packages:kotlin:ttr-plan-proto"))
    // The typed model (`CnSchema`, CN-P2 S2.5) — api: `Via` and `GroundingGrade` live there and are in this module's
    // public types (aliases in Results.kt). Then the md lowering chain and the er renderer's dialects.
    api(project(":packages:kotlin:ttr-metadata"))
    // TTRP 0.5 (CN-P2 S2.6, CN-D29): a `definition:` / `members:` / `filter:` block is a TTR-P expression; the parsed
    // tree is in this module's public types (`CnDefinition.expression`). No cycle: ttrp-frontend depends on
    // ttr-metadata, never on ttr-concept.
    api(project(":packages:kotlin:ttrp-frontend"))
    implementation(project(":packages:kotlin:ttr-md-resolver"))
    implementation(project(":packages:kotlin:ttrp-emit"))
    implementation(project(":packages:kotlin:ttr-translator")) // dialects only (definition_render(er) targets)

    testImplementation(libs.bundles.kotest)
}

// CN-P0 S0.4 T1 (architecture §7, the RG-P5 pattern) — the ZERO-LLM rule made mechanical, at BUILD time: fail
// `check` if this module's runtimeClasspath resolves any LLM, gateway or kantheon artifact, or an HTTP client
// (a lower is never a server call). `NoLlmDependencySpec` is the runtime backstop on the test classpath.
val forbiddenRuntimeCoordinates =
    listOf("llm", "gateway", "openai", "anthropic", "langchain", "theokanning", "kantheon", "ktor-client")

val verifyNoLlmDependency by tasks.registering {
    val runtimeClasspath = configurations.named("runtimeClasspath")
    doLast {
        val hits =
            runtimeClasspath
                .get()
                .resolvedConfiguration.resolvedArtifacts
                .map { it.moduleVersion.id }
                .filter { id ->
                    forbiddenRuntimeCoordinates.any { bad ->
                        id.name.contains(bad, ignoreCase = true) || id.group.contains(bad, ignoreCase = true)
                    }
                }.map { "${it.group}:${it.name}:${it.version}" }
                .distinct()
        require(hits.isEmpty()) {
            "ZERO-LLM violation (cn architecture §7): ttr-concept runtimeClasspath resolves forbidden artifact(s): $hits"
        }
    }
}

tasks.named("check") { dependsOn(verifyNoLlmDependency) }

// Not in any publish.yml bundle yet (scaffold only, CHANGELOG "Unreleased") — see the bundle header there for why
// the translator bundle cannot carry it as the build stands (⚑CN-5 re-check, CN-P0 S0.4). The block is here so
// CN-P3 S3.1 only has to add the module to a bundle.
mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-concept", version.toString())
    pom {
        name.set("TTR Concept")
        description.set(
            "The conceptual-model (cn) layer of the TTR (Tatrman) DSL: statement / assertion validation and " +
                "lowering, and definition rendering on the md and er layers — deterministic, no LLM.",
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
