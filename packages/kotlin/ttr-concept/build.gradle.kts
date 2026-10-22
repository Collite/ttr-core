// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    application
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // CN-P3 S3.7 — the goldens (`ConceptGoldenSpec`): every case runs in `check` (S3.8 removed the stage switch).
    // `-Pcn.goldens.regen` rewrites the oracle-made expectations; it reaches the test JVM only as a system property (a
    // `-D` on the Gradle command line does not).
    systemProperty("cn.goldens.regen", providers.gradleProperty("cn.goldens.regen").map { "true" }.getOrElse("false"))
}

// CN-P3 S3.3 — `LowerProvenance.grammarVersion`: TTR.g4's `@grammar-version`, baked in at build time (the parser
// artifact carries none; ttr-concept and ttr-parser are cut together in the grammar bundle, so they agree). S3.8: a
// generated constant, not a resource — a lower reads nothing, not even its own jar (`PuritySpec`).
val grammarVersionSource by tasks.registering {
    val g4 = rootProject.file("packages/grammar/src/TTR.g4")
    val out = layout.buildDirectory.dir("generated/sources/grammar-version/kotlin")
    inputs.file(g4)
    outputs.dir(out)
    doLast {
        val version =
            Regex("@grammar-version:\\s*(\\S+)").find(g4.readText())?.groupValues?.get(1)
                ?: error("no `@grammar-version` header in $g4")
        val file = out.get().file("org/tatrman/concept/GrammarVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// Generated from TTR.g4's `@grammar-version` (build.gradle.kts, grammarVersionSource) — do not edit.\n" +
                "package org.tatrman.concept\n\ninternal const val GRAMMAR_VERSION: String = \"$version\"\n",
        )
    }
}

kotlin.sourceSets.main { kotlin.srcDir(grammarVersionSource) }

ktlint {
    filter { exclude("**/generated/**") }
}

// CN (conceptual model, `project/tatrman/features/conceptual/`) — `ttr-concept`: the three function families
// below the frontier (statements · assertions · definition rendering on the md and er layers), contracts §3.
// Built at CN-P3. A pure function of resolved, cached inputs (the S6 / PL-P0.S2 compile rule): no LLM, no server
// call, no I/O mid-lower.
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
    // The hygiene report (CN-P2 S2.7): the lexicon's collision fold (`TermNormalizer.fold`, the one the runtime index
    // folds by) for redundant terms and phase-as-default; its JSON rendering is kotlinx (api — `HygieneRender.json`).
    implementation(project(":packages:kotlin:ttr-lexicon"))
    api(libs.kotlinx.ser.json)
    // `ttr cn hygiene` (the house `ttr <verb>` pattern — ttr-import-schema, ttr-security-gen).
    implementation(libs.clikt)
    implementation(project(":packages:kotlin:ttr-md-resolver"))
    implementation(project(":packages:kotlin:ttrp-emit"))
    implementation(project(":packages:kotlin:ttr-translator")) // dialects only (definition_render(er) targets)

    testImplementation(libs.bundles.kotest)
    // CN-P3 S3.1 — the frames' canonical JSON mapping (JsonFormat), the golden inputs' format.
    testImplementation(libs.protobuf.java.util)
    // CN-P3 S3.7 — the goldens' er oracle translates over an `InMemoryModelHandle` (the translator's test fixture).
    testImplementation(testFixtures(project(":packages:kotlin:ttr-translator")))
    // CN-P3 S3.3 T7 — the live suite (`ConceptLiveSpec`) runs the lowered plans on PostgreSQL (`TTR_CONN_ERP_PG`).
    testImplementation(libs.postgresql)
}

// CN-P2 S2.7 — `ttr cn hygiene <dir> [--format text|json] [--fail-on error|warning]`; CN-P3 S3.4 — `ttr cn materialize
// <dir> <cn.Phase> [--dialect …] [--out <dir>]`. `just cn-hygiene` / `just cn-materialize` run them from the repo, with
// paths relative to the repo root.
application {
    applicationName = "ttr"
    mainClass = "org.tatrman.concept.cli.MainKt"
}

tasks.named<JavaExec>("run") { workingDir = rootDir }

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

// Published in the `grammar/v*` bundle (publish.yml `MODULES`, since CN-P3 S3.1 — A-CN-1 amends ⚑CN-5): it shares the
// ttrp-emit closure, so the translator bundle would write a POM naming grammar modules at a version never cut.
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
