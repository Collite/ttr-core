// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `manifest.json` — the bundle's machine record (contracts §5, F-f-i). Field order and names are
 * a contract (the file is committed/diffed by users), so this is pretty-printed with a stable key
 * order and **strict** decoding (unknown keys rejected — the manifest is a contract, not a grab
 * bag). `manifest.json` never appears in [files] (it can't contain its own hash — flagged for a
 * contracts §5 clarification line).
 */
@Serializable
data class RunManifest(
    /**
     * The E-5 version key (contracts §6 / §5.1). Door execution keys on this. PL-P1.S3 emits **v2**:
     * schemaVersion 2 + per-island/transfer `connections` + static `lineage`. `params`/`retries`/
     * `onFailureOf` stay ABSENT until PL-P2 supplies the F-4 grammar (the schema permits them).
     */
    val schemaVersion: Int = 2,
    val ttrpVersion: Int = 1,
    val toolchain: String,
    val program: String,
    val world: WorldRef,
    val islands: List<IslandEntry>,
    val transfers: List<TransferEntry>,
    val waves: List<List<String>>,
    val connections: List<String>,
    val displays: List<DisplayEntry>,
    /**
     * PL-P2.S1 (F-4-i): declared, typed runtime params (name/type/required/default). Null (and
     * omitted) for a param-free program, so its manifest stays byte-identical to pre-feature.
     */
    val params: List<Param>? = null,
    /** CQ-5 static, compile-derived column lineage (contracts §6). Null ⇒ omitted (no columns derived). */
    val lineage: Lineage? = null,
    /**
     * One entry per elaborated reject site (RJ-P3, contracts §7). Empty for every program with no
     * wired rejects — the field defaults empty, so a rejects-free `manifest.json` is byte-identical
     * to pre-feature. Feeds RJ-P5's eighth conform check: per site,
     * `count(in) == count(processed) + count(rejects)`.
     */
    val rejectSites: List<RejectSiteEntry> = emptyList(),
    /**
     * The MD compile parameters recorded for bind-time staleness (S4-B5, decision 13): the resolved
     * `asof` + the member-snapshot fingerprint. **Null (and omitted) for a non-MD program**, so every
     * existing manifest is byte-identical — the point of `explicitNulls = false`. Emitted only when the
     * program carries an MD model AND there is something to record (an `asof` or a fingerprint).
     */
    val md: MdManifest? = null,
    /**
     * AG-P0 S5 — the content fingerprint of each `[ttrp] extra-model-roots` tree the program was
     * compiled against (a host-GENERATED model, e.g. ai-platform's `ttr-gen` output). The host compares
     * it with the fingerprint of the model it has loaded and refuses a stale bundle. Null (omitted) for
     * a project without extra roots, so every other manifest stays byte-identical.
     */
    val modelRoots: List<ModelRootRef>? = null,
    val files: Map<String, String>,
) {
    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        /** Pretty, stable, strict — the manifest is a diffable contract. */
        val JSON =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
                encodeDefaults = true
                // Omit null optionals: a v2 manifest without derived columns carries no `lineage` key
                // (the schema forbids `lineage: null`), and a non-MD program carries no `md` block.
                // Non-null defaults still encode (encodeDefaults).
                explicitNulls = false
                // Strict decode: within-toolchain byte-parity reader — an unknown key is an emit bug here
                // (contracts §6 reader-policy: cross-version/external readers are the lenient ones).
                ignoreUnknownKeys = false
            }

        fun fromJson(text: String): RunManifest = JSON.decodeFromString(text)
    }
}

/**
 * The MD compile parameters recorded in `manifest.json` for bind-time staleness (S4-B5, decision 13):
 * the resolved [asof] (the `[ttrp] md-asof` value, else the compile-pass clock) and the
 * [memberFingerprint] of the [org.tatrman.ttr.md.resolve.MemberSnapshot] the resolution used. Both are
 * optional and omitted when absent (`explicitNulls = false`): a disconnected compile (S4, no snapshot)
 * records only `asof`; production snapshot loading (S6-B) fills the fingerprint.
 */
@Serializable
data class MdManifest(
    /** Resolved `asof`, ISO-8601 (e.g. `2026-07-08T00:00:00Z`). Omitted only if never resolved. */
    val asof: String? = null,
    /** `sha256:…`-style member-snapshot fingerprint; omitted in disconnected mode. */
    val memberFingerprint: String? = null,
)

/** One extra model root (AG-P0 S5): its path as configured in `[ttrp] extra-model-roots`, its content fingerprint. */
@Serializable
data class ModelRootRef(
    val path: String,
    val fingerprint: String,
)

@Serializable
data class WorldRef(
    val qname: String,
    /** `sha256:<64 hex>` semantic world fingerprint (F-f-ii). */
    val fingerprint: String,
)

@Serializable
data class IslandEntry(
    val name: String,
    val engine: String,
    val executor: String,
    /** `psql` | `python3`. */
    val invocation: String,
    val file: String,
    val sha256: String,
    /** v2 (§6): per-island connection refs (H-5 least exposure — was bundle-global only). */
    val connections: List<String> = emptyList(),
    /** PL-P2.S1 (F-4-ii): manifest-declared per-island retry count; null (omitted) for no retries. */
    val retries: Int? = null,
    /** PL-P2.S1 (F-4-iv): when set, this island runs iff the named source island failed. Null ⇒ happy-path. */
    val onFailureOf: String? = null,
    /**
     * PL-P2.S1 (F-4-i): the declared params this island consumes (H-5 least exposure — the executor
     * injects only these beside `TTR_CONN_*`). Null (omitted) when the island references no param.
     */
    val params: List<String>? = null,
    /**
     * AG-P0 — a `sql-text` island's outputs: ONE self-contained statement per OUT port (the host runs
     * each through its own SQL door). Null (omitted) for every other delivery, so their manifests stay
     * byte-identical. [file]/[sha256] above then name the first output.
     */
    val outputs: List<IslandOutput>? = null,
)

/** One `sql-text` island output (AG-P0): the port, its statement file, its row type, the params it binds. */
@Serializable
data class IslandOutput(
    val port: String,
    val file: String,
    val sha256: String,
    val columns: List<OutputColumn>,
    val params: List<String>? = null,
)

@Serializable
data class OutputColumn(
    val name: String,
    val type: String,
)

/**
 * A declared runtime param (PL-P2.S1, F-4-i, contracts §6). [default] is the `@`-builtin (`@run-date`)
 * or the literal value; null (and omitted) ⇒ [required] is true (a value is supplied at trigger time).
 */
@Serializable
data class Param(
    val name: String,
    val type: String,
    val required: Boolean,
    val default: String? = null,
)

@Serializable
data class TransferEntry(
    val from: String,
    val to: String,
    val via: String,
    val file: String,
    val sha256: String,
    /** v2 (§6): the connection refs this transfer touches (source × target). */
    val connections: List<String> = emptyList(),
)

/**
 * CQ-5 static column lineage (contracts §6), compile-derived. Maps 1:1 onto the OpenLineage
 * `columnLineage` facet (export lives in Veles connectors, PL-P1.S9). `transform` draws the ⚑
 * vocabulary `identity | expression | aggregate:<fn> | join-key | filter-only`.
 */
@Serializable
data class Lineage(
    val version: Int = 1,
    val columns: List<LineageColumn> = emptyList(),
)

@Serializable
data class LineageColumn(
    val output: LineageOutput,
    val inputs: List<LineageInput>,
    val transform: String,
)

@Serializable
data class LineageOutput(
    val island: String,
    val relation: String,
    val column: String,
    /**
     * The **physical target qname** this output column materializes to — the island's `store(...)`
     * target (a catalog-shaped qname a downstream catalog like OpenMetadata can key on). Null when the
     * island produces an in-memory/display-only output (e.g. the hero's `out/main_result.arrow`) that
     * has no catalogued table; a lineage consumer parks such columns (PL-P1.S9 export organ). Added so
     * the OM column-lineage edge has a resolvable output entity instead of the island/relation form.
     */
    val materialized: String? = null,
)

@Serializable
data class LineageInput(
    val qname: String,
    val column: String,
)

@Serializable
data class DisplayEntry(
    val name: String,
    /**
     * `out/<name>.<fmt>` — unique per entry: several entries may share an action display's [name] (one per
     * source, in wiring order), and the k-th (k ≥ 2) writes `out/<name>~<k>.<fmt>`.
     */
    val file: String,
    /**
     * AG-P0 — the island OUT port feeding this display (set for `sql-text` bundles only; else omitted). An
     * action display fed from a port that also feeds a differently-shaped display names the derived
     * `<port>~<schema>` output holding its projected statement.
     */
    val source: DisplaySource? = null,
    /**
     * Grammar 0.14 — an **action display**: the package-qualified `def schema` its rows were held to (omitted for
     * an ordinary display). The source statement's columns are the schema's, in schema order.
     */
    val schema: String? = null,
    /** The action display's declared columns (the schema's, in order); omitted for an ordinary display. */
    val columns: List<DisplayColumn>? = null,
)

/** One declared column of an action display's row schema: name, TTR-M type spelling, optional (omitted ⇒ required). */
@Serializable
data class DisplayColumn(
    val name: String,
    val type: String,
    val optional: Boolean? = null,
)

@Serializable
data class DisplaySource(
    val island: String,
    val port: String,
)

/**
 * An elaborated reject site (RJ-P3, contracts §7): the reject-producing node, its island/container,
 * the container OUT port carrying the `rejects` stream, and the sibling OUT ports carrying the
 * accepted rows. RJ-P5's eighth check does NOT count these OUT ports (that model was abandoned in the
 * seal — a downstream join/aggregate makes OUT-port counts diverge from the guard's clean output);
 * instead each engine writes `counts.json` counted at the guard's clean branch-child and the partition
 * check asserts `in == processed + rejects` there, cross-engine. The [site] id is the reconciliation
 * key the partition check verifies every engine's `counts.json` reports (RJ-P5 review, B3). The
 * [rejectsPort]/[processedPorts] here drive the reject/bad **stream** compares, not the count balance.
 */
@Serializable
data class RejectSiteEntry(
    val site: String,
    val container: String,
    val rejectsPort: String,
    val processedPorts: List<String>,
)
