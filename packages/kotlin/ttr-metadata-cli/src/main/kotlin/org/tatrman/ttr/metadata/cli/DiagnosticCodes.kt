// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One diagnostic code of the catalogue (contracts §5, DW C-13). */
data class DiagnosticCode(
    val code: String,
    val meaning: String,
    val raisedBy: String,
) {
    /** `ys` (the yaml source and profile application) or `xm` (cross-model: links, overlays, generated files). */
    val family: String get() = code.substringBefore('/')
}

/**
 * MW S3.5 T5 — the `ys/…` and `xm/…` codes the profile engine, the canonical codec, the `yaml` source and the CLI raise. This list
 * is the source of the generated resource `diagnostic-codes.json` (`lint --codes`); a test fails when a code is raised
 * that it lacks, or when the resource drifts from it. Codes reserved for a later stage say so in `raisedBy`.
 */
object DiagnosticCodes {
    const val RESOURCE = "/org/tatrman/ttr/metadata/cli/diagnostic-codes.json"

    val all: List<DiagnosticCode> =
        listOf(
            DiagnosticCode(
                "ys/profile-invalid",
                "Invalid profile syntax, schema, operands or safety policy; an invalid model.ignore",
                "ttr-profile ProfileReader, DeclarationReader, ProfileResolver, operations; ttr-metadata YamlFileSource",
            ),
            DiagnosticCode(
                "ys/profile-grammar-mismatch",
                "A profile or declaration excludes the running grammar",
                "ttr-profile ProfileResolver",
            ),
            DiagnosticCode(
                "ys/profile-not-found",
                "A profile, overlay or ttr.yaml cannot be resolved or read within the root",
                "ttr-profile ProfileResolver, Overlay; ttr-metadata YamlFileSource",
            ),
            DiagnosticCode(
                "ys/profile-cycle",
                "The profile inheritance chain contains a cycle",
                "ttr-profile ProfileResolver",
            ),
            DiagnosticCode(
                "ys/unknown-key",
                "An authored foreign key was neither consumed nor ignored",
                "ttr-profile RecordPlan",
            ),
            DiagnosticCode("ys/unknown-kind", "A record discriminator has no kind mapping", "ttr-profile RecordPlan"),
            DiagnosticCode(
                "ys/profile-conflict",
                "Two operations or records own the same canonical target",
                "ttr-profile RecordPlan, StructuralOps, CanonicalBuilder",
            ),
            DiagnosticCode(
                "ys/value-unmapped",
                "A selected value (a package key, a value map) has no mapping and no default",
                "ttr-profile StructuralOps, ValueOps",
            ),
            DiagnosticCode(
                "ys/profile-duplicate",
                "Two catalogue profiles declare one identity: left out of the catalog (a warning); usage for a run naming it",
                "ttr-metadata-cli lint, profile check|apply (the catalog read)",
            ),
            DiagnosticCode(
                "ys/twin-missing",
                "A dialect fixture has no canonical twin",
                "reserved (contracts §5); the conformance roster refuses such a fixture",
            ),
            DiagnosticCode(
                "ys/record-id-missing",
                "A record has no string ID at kinds.id or the default /\$key",
                "ttr-profile RecordPlan (at the authored record)",
            ),
            DiagnosticCode(
                "ys/canonical-invalid",
                "A file read as canonical YAML is not valid canonical YAML",
                "ttr-yaml CanonicalYaml via ttr-metadata YamlFileSource",
            ),
            DiagnosticCode(
                "xm/link-unresolved",
                "A link names nothing in the run or its sidecars",
                "ttr-profile ValueOps (link)",
            ),
            DiagnosticCode(
                "xm/link-ambiguous",
                "A link names more than one target",
                "ttr-profile ValueOps (link), Overlay",
            ),
            DiagnosticCode(
                "xm/overlay-orphan",
                "An overlay entry names no emitted definition (its suggestion may carry a rename)",
                "ttr-profile Overlay",
            ),
            DiagnosticCode(
                "xm/generated-stale",
                "An input a generated file's header names changed since generation, or is not found",
                "ttr-metadata GeneratedGuard (lint)",
            ),
            DiagnosticCode(
                "xm/generated-edited",
                "A generated file's body differs from a fresh generation of its inputs, or its header is malformed",
                "ttr-metadata GeneratedGuard (lint)",
            ),
            DiagnosticCode(
                "xm/snapshot-size",
                "A captured snapshot exceeds 5 MiB and --large was not given",
                "ttr-metadata SnapshotCapture (snapshot)",
            ),
        )

    /** The resource's text: deterministic, one entry per line. */
    fun render(): String {
        val entries: JsonArray =
            buildJsonArray {
                all.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("code", entry.code)
                            put("family", entry.family)
                            put("meaning", entry.meaning)
                            put("raisedBy", entry.raisedBy)
                        },
                    )
                }
            }
        return entries.joinToString(",\n", "[\n", "\n]\n") { "  $it" }
    }

    /** The bundled resource, as `lint --codes` prints it. */
    fun resource(): String =
        requireNotNull(
            javaClass.getResourceAsStream(RESOURCE),
        ) { "missing $RESOURCE" }.bufferedReader().use { it.readText() }
}
