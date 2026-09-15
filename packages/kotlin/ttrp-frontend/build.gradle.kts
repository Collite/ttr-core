// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    // PL-P1.S3: the ② seam serializes project artifacts (compile record §5, stats §4) as JSON.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
    antlr
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Golden AST snapshots: run with `-DupdateSnapshots=true` to (re)write the
    // committed snapshots under src/test/resources/golden/snapshots/.
    systemProperty("updateSnapshots", System.getProperty("updateSnapshots") ?: "false")
}

// The canonical TTR-P grammar lives in the pnpm workspace beside TTR.g4; the
// Kotlin build reads it directly — no copy, no sync (G-b: TTR-P is Kotlin-only;
// ANTLR Gradle plugin is the ONLY generation path — no antlr-ng/TS target, no
// TextMate grammar — see architecture §6, which supersedes plan.md's stale
// "antlr-ng generation task" wording).
val canonicalGrammar = file("../../grammar/src/TTRP.g4")

sourceSets["main"].antlr.setSrcDirs(listOf(canonicalGrammar.parentFile))

val generatedPackage = "org.tatrman.ttrp.parser.generated"

tasks.named<org.gradle.api.plugins.antlr.AntlrTask>("generateGrammarSource") {
    // TTRP.g4 (canonical) + the fragment dialects TTRSql.g4 / TTRPandas.g4 (P6) + TTRB.g4
    // (P7). All share ONE generated package (no class-name collision — ANTLR prefixes by
    // grammar name: TTRP*, TTRSql*, TTRPandas*, TTRB*). TTR.g4 belongs to ttr-parser, excluded.
    source = fileTree(canonicalGrammar.parentFile) { include("TTRP.g4", "TTRSql.g4", "TTRPandas.g4", "TTRB.g4") }
    arguments = arguments + listOf("-visitor", "-long-messages", "-package", generatedPackage)
    // NOTE (same footgun as ttr-parser): the ANTLR plugin emits generated .java
    // FILES flat into build/generated-src/antlr/main/ regardless of `-package`;
    // the files still declare `package org.tatrman.ttrp.parser.generated`, so they
    // compile correctly. Do NOT override `outputDirectory` to nest them — on a
    // clean rebuild ANTLR regenerates flat while the nested copy lingers, causing
    // duplicate-class compile errors.
}

// The ANTLR plugin makes the `antlr` configuration (the code-gen tool, which
// transitively pulls ST4 + antlr-runtime3) extend `api`, leaking the tool into
// the published POM as a compile dependency. Consumers only need the runtime
// (`api(libs.antlr.runtime)` below), so strip `antlr` from api's extendsFrom.
// Generation still works — the AntlrTask uses the `antlr` configuration directly.
configurations.api {
    setExtendsFrom(extendsFrom.filterNot { it.name == "antlr" })
}

dependencies {
    antlr(libs.antlr.tool)
    api(libs.antlr.runtime)

    // Stage 1.3: all model/world reading goes THROUGH ttr-metadata (D-g, offline);
    // ttrp-frontend never parses `.ttrm` directly. TOML for the `[ttrp]` manifest (S5).
    implementation(project(":packages:kotlin:ttr-metadata"))
    // PL-P1.S2: the ② connected binding — `ttr.lock` pins reference cached snapshot archives;
    // MetadataServerSource reads canon out of the cache (no network in load(), B-5).
    implementation(project(":packages:kotlin:ttr-snapshot"))
    implementation(libs.tomlj)
    // PL-P1.S3: JSON for the compile-record sidecar (§5) + stats entries (§4).
    implementation(libs.kotlinx.ser.json)
    // RJ-P1: parse the shipped canonical-validity specs (ttrp/validity/*.yaml, RJ-P0). The
    // specs carry Unicode-escaped corpus rows, so a real YAML parser (not a hand roll) is used.
    implementation(libs.snakeyaml)

    // MD dot-path (S3): the resolver core turns `mdPath` expression nodes into canonical
    // paths + shapes/diagnostics. `api` because the frontend's check result surfaces the
    // resolver's DTOs (CanonicalPath/PathShape/Explanation). This transitively api-exposes
    // ttr-semantics (MdModel/AggKind) and ttr-parser — the one-way graph the arc mandates
    // (grammar → parser → semantics → resolver → frontend); the resolver never depends back.
    api(project(":packages:kotlin:ttr-md-resolver"))

    // MD dot-path (S5C-B.2): materialize (`C := e`, R27) emits a generated `.ttrm` (cubelet + binding)
    // through ttr-writer's TtrRenderer (MDS7 — "materialization writes model text"). ttr-writer is a
    // leaf (parser-only); this pulls its `api(ttr-parser)` onto the compile classpath for the inferred
    // parser Definitions the emitter renders.
    implementation(project(":packages:kotlin:ttr-writer"))

    // kotlinx-serialization is TEST-ONLY (the deterministic AST snapshot dumper) —
    // kept off the published runtime classpath, same as ttr-parser's conformance dump.
    testImplementation(libs.kotlinx.ser.json)
    testImplementation(libs.bundles.kotest)
    // Shared world/model fixture project (contracts §8): consume, never duplicate.
    testImplementation(testFixtures(project(":packages:kotlin:ttr-metadata")))
    // The shared `sales-model` MdModel fixture (S1) + InMemoryMemberSnapshot (S2) for the
    // MD dot-path checker specs — injected as the model/snapshot the resolver reads.
    testImplementation(testFixtures(project(":packages:kotlin:ttr-semantics")))
    testImplementation(testFixtures(project(":packages:kotlin:ttr-md-resolver")))
}

tasks.named("compileKotlin") { dependsOn("generateGrammarSource") }
tasks.named("compileJava") { dependsOn("generateGrammarSource") }

ktlint {
    filter {
        exclude("**/generated/**")
        exclude { it.file.path.contains("/generated-src/antlr/") }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("TTR-P Compiler Front-Half")
                description.set("parse to resolve to typecheck for TTR-P (.ttrp)")
                url.set("https://github.com/Collite/ttr-core")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("Bora Perusic")
                        email.set("boraperusic@gmail.com")
                        organization.set("Collite")
                        organizationUrl.set("https://github.com/Collite")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Collite/ttr-core.git")
                    developerConnection.set("scm:git:git@github.com:Collite/ttr-core.git")
                    url.set("https://github.com/Collite/ttr-core")
                }
            }
        }
    }
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
