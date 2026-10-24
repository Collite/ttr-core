// SPDX-License-Identifier: Apache-2.0
rootProject.name = "tatrman"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":packages:kotlin:ttr-parser")
include(":packages:kotlin:ttr-writer")
include(":packages:kotlin:ttr-semantics")

// The C-5-i plan-validator plugin SPI (contracts §9, PL-P4.S2) — an open Apache-2.0 interface the platform
// validation organ hosts and third parties (kantheon's llm-guard) implement. Dependency-free (opaque plan
// bytes). Published as org.tatrman:ttr-validator-spi.
include(":packages:kotlin:ttr-validator-spi")
// MD dot-path resolver arc (docs/features/md/dot-path/, phase S2): the §2–§3 constraint
// resolver over MdModel. Standalone — must NOT depend on ttrp-frontend (S3 wires it the other way).
include(":packages:kotlin:ttr-md-resolver")
include(":packages:kotlin:ttr-metadata")
include(":packages:kotlin:ttr-metadata-cli")
include(":packages:kotlin:ttr-metadata-git")
// PL-P1.S1 — deterministic snapshot archives + cache (the ② seam transport unit).
include(":packages:kotlin:ttr-snapshot")
// RV-P1.1 — `ttr-lexicon/v1` + `ttr-skill/v1` schemas, validator and typed lexicon-area model.
// Upstream of both the RV-P1.2 compiler and the tatrman-server consumers (lex-matcher, resolver).
include(":packages:kotlin:ttr-lexicon")
// RV-P1.2 — the compilation stage: declared (area + TTR-M sugar) + metadata layers → the
// deterministic `kind: "lexicon"` archive + the operator library. Split from ttr-lexicon so
// serving consumers read an artifact without resolving parser/metadata/snapshot.
include(":packages:kotlin:ttr-lexicon-compile")
// RV-P3.1 — the producer. `LexiconBuild.run` was a library function with no caller outside
// tests; this is the `ttr-lexicon build <repoRoot>` command that actually emits an estate's
// archive, and the only thing that makes the P1 readers reachable by real content.
include(":packages:kotlin:ttr-lexicon-cli")
include(":packages:kotlin:ttr-designer-server")
// MD dot-path agent service (docs/features/md/dot-path/, phase S7, MDS6): a thin MCP shell
// over ttr-md-resolver — md_resolve / md_explain / md_list_members. Non-published app module.
include(":packages:kotlin:ttr-md-agent")

// ttr-translator extraction arc (docs/ttr-translator/): wire formats + translation
// core, extracted from kantheon shared/libs/kotlin/query-translator.
include(":packages:kotlin:ttr-plan-proto")
include(":packages:kotlin:ttr-translator")
// CN (conceptual model, project/tatrman/features/conceptual/) — statements · assertions · definition rendering
// below the determinism frontier; CN-P0 S0.4 scaffold (bodies are CN-P3). Not in a publish bundle yet.
include(":packages:kotlin:ttr-concept")

// TTR-P toolchain (module cut per docs/ttr-p/implementation/v1/plan.md Phase 0)
include(":packages:kotlin:ttrp-frontend")
include(":packages:kotlin:ttrp-graph")
include(":packages:kotlin:ttrp-emit")
// PL-P5.S1 — the E-1/EQ-1 emit-plugin SPI (contracts §8), extracted from ttrp-emit; published org.tatrman:ttrp-emit-spi.
include(":packages:kotlin:ttrp-emit-spi")
// PL-P5.S1 — the bash F-lite emit plugin (the proving extraction); published org.tatrman:ttr-emit-bash.
include(":packages:kotlin:ttr-emit-bash")
// PL-P5.S3 — the Kestra emit plugin (data-defined flow YAML, the second SPI consumer); published org.tatrman:ttr-emit-kestra.
include(":packages:kotlin:ttr-emit-kestra")
// PL-P5.S4 — the Airflow 3 emit plugin (code-defined DAG, native + door-calling bindings); published org.tatrman:ttr-emit-airflow3.
include(":packages:kotlin:ttr-emit-airflow3")
include(":packages:kotlin:ttrp-lsp")
include(":packages:kotlin:ttrp-cli")
include(":packages:kotlin:ttrp-conform")

// `ttr import-schema` — the standard's brownfield front door (STRAT-8/RO-26; SV-P4·S3/S4).
// Engine (introspection → db mirror → conventions → probes → er first cut → review checklist)
// + a thin `ttr import-schema` clikt CLI. Emits TTR-M through the canonical ttr-writer.
include(":packages:kotlin:ttr-import-schema")

// PL-P4.S3 — the H-1 `security { }` → OPA/Rego generator: deterministic, one-way
// (block model → policy fragments + data.json). Published as org.tatrman:ttr-security-gen
// so Perun (S4, tatrman-platform) consumes it as a PUBLISHED artifact (P2). One
// generator, two callers (I-2): the `ttr security-gen` CLI + Perun's build pipeline.
include(":packages:kotlin:ttr-security-gen")

include(":packages:kotlin:ttr-emit-native")

include(":packages:kotlin:ttr-emit-n8n")

include(":packages:kotlin:ttr-emit-camunda")

include(":packages:kotlin:ttr-emit-python")

include(":packages:kotlin:ttr-emit-sql-job")
