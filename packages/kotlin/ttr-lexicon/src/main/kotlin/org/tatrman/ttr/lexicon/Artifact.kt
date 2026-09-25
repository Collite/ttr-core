// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

//
// The COMPILED lexicon artifact (RV-P1.2; contracts §2) — model + codec only.
//
// This lives in `ttr-lexicon`, not in the compiler module, on purpose: the serving side
// (lex-matcher, the resolver — RO-13) READS this and must not drag `ttr-parser`,
// `ttr-metadata` or `ttr-snapshot` in to do it. The compiler that PRODUCES it is
// `ttr-lexicon-compile`.
//
// Kinds are not stored — an entry is an alias or a value by virtue of [TargetClass] (RV-38).
//

/** RV-38/RV-42 — what a `target_ref` points at. Derived at compile time; never authored. */
@Serializable
enum class TargetClass {
    /** An er/db/md model object or attribute. */
    MODEL_OBJECT,

    /** A member (value) of a model object — a VALUE entry. */
    MEMBER,

    /** An `op:` ref — an operator, from a skill file's frontmatter (RV-35). */
    OPERATOR,

    /** A `ground:` ref — a grounding trigger slice (RV-42). */
    GROUNDING_TRIGGER,

    /**
     * A `pred:` ref — a string-predicate trigger slice (LP contracts §3).
     *
     * The words that say *how* a quoted literal restricts its attribute — *začínající na*,
     * *obsahující*, *ends with*. Like [GROUNDING_TRIGGER] it names a behaviour rather than a model
     * object, so it never reaches the model snapshot and never gates a span's values.
     *
     * ⚠ Adding a member to this enum is the one change in this file that an OLD reader cannot
     * absorb. `targetClass` has no default, and kotlinx rejects an enum value it does not know, so
     * a pre-LP reader meeting a `STRING_PREDICATE` row fails to decode the WHOLE archive and
     * degrades to an empty vocabulary — see [CompiledLexiconHeader.SCHEMA_VERSION]'s
     * readers-before-producers rule, which exists for exactly this.
     */
    STRING_PREDICATE,
}

/**
 * Which layer produced a row. `DATA` and `LEARNED` are contract members of the same
 * enum on the wire (contracts §1) but are NOT compiled: DATA is the member index's own
 * refresh cadence and LEARNED is the estate overlay store. This artifact is exactly the
 * two build-time layers.
 */
@Serializable
enum class SourceTag {
    DECLARED,
    METADATA,
}

/** File + 1-based line the row was authored on. */
@Serializable
data class EntryProvenance(
    val file: String,
    val line: Int,
)

/**
 * One row of the uniform entry table. `lemma` is null until the NLP side lemmatizes at
 * build time — the column exists now so its absence is a value, not a schema change.
 */
@Serializable
data class CompiledEntry(
    val termNormalized: String,
    val lemma: String? = null,
    /** [Lang.wire] — `cs` · `en` · `cs|en`. */
    val lang: String,
    val targetRef: String,
    val targetClass: TargetClass,
    /**
     * [MatchMethod.wire] — `EXACT` · `TOKENS` · `TYPOS(n)`.
     *
     * Since RV-44 this is the *projection* of [matchProfile] for profile-carrying rows
     * ([MatchProfile.sugarMethod]). It is kept, not replaced: it is what a consumer that predates
     * profiles reads, and for the three sugar shapes the projection is exact.
     */
    val method: String,
    val sourceTag: SourceTag,
    val provenance: EntryProvenance,
    /**
     * RV-44 — the **resolved** matching profile, so no consumer re-derives sugar.
     *
     * Present on every DECLARED row (authored `match:`, or expanded from `method:`); **absent on
     * every METADATA row** — ⚑M-2 scopes profiles to the authoring surface, and the member index
     * and model labels keep engine scores. A reader must therefore treat absence as "score this
     * row the way you always did", which is exactly what makes an estate with no profiles
     * byte-identical to the pre-RV-44 service.
     *
     * The ⚑M-4 short-term guard is **not** applied here: the artifact records what the estate
     * authored, and the matcher is where a rule fails to fire (the build already warned).
     */
    val matchProfile: MatchProfile? = null,
)

/** Per-layer input fingerprints, so a rebuild can be attributed to the layer that moved. */
@Serializable
data class SourceHashes(
    val declared: String,
    val metadata: String,
)

@Serializable
data class CompiledLexiconHeader(
    val schemaVersion: String = SCHEMA_VERSION,
    /** The model snapshot every `targetRef` was validated against (RV-20). */
    val modelSnapshotHash: String,
    val sourceHashes: SourceHashes,
    /**
     * ISO-8601, **caller-supplied**. Never `Instant.now()` inside the compiler: two builds over
     * the same inputs must produce the same bytes (contracts §2 determinism, the same rule
     * `SnapshotWriter` lives by). A build passes the model snapshot's stamp or SOURCE_DATE_EPOCH.
     */
    val builtAt: String,
) {
    companion object {
        /**
         * v2 (MS) adds [CompiledLexicon.targets]; v3 (MH) adds [TargetFacts.reachedFrom];
         * v4 (LP) adds [TargetFacts.nameRef]/[TargetFacts.codeRef]/[TargetFacts.codeFormat] —
         * and the [TargetClass.STRING_PREDICATE] member, which is why v4 is the first bump where
         * the ordering rule below is not merely prudent but load-bearing: the three fields are
         * defaulted and harmless, the enum member is not. v5 (MV) adds
         * [TargetFacts.memberVocabulary] and a `targets` entry for every indexed attribute —
         * defaulted, no enum member, so a v4 reader reads a v5 archive (it simply sees no member
         * facet) and v5 is additive in both directions.
         *
         * Each field is defaulted, so an older archive decodes here (v1 → v2 → v3). That is the
         * only direction a
         * default buys, and it is worth being exact about which one, because the reverse is what
         * bites: a v2 archive carries `targets` in every case ([CompiledLexicon.PRETTY] encodes
         * defaults), so a reader that has never heard of the field must be lenient about it.
         * [CompiledLexicon.PRETTY] now is — see the ⛑ note there — but that only helps readers
         * built from THIS version on. **Nothing makes an already-shipped v1 reader accept a v2
         * archive**, and both serving readers degrade an undecodable archive to an empty
         * vocabulary rather than failing loudly. Hence contracts §6's ordering rule: for the
         * release that crosses v1→v2, **readers before producers**.
         *
         * The version moves so that a consumer CAN tell the two apart. ⚠ Today none does: neither
         * lex-matcher's `LexiconArchiveSource` nor the resolver's `LexiconArchiveRegistrySource`
         * reads `schemaVersion` at all (review-082 F2 — an earlier revision of this comment
         * claimed they pinned it; they do not). Introducing that check, in both, is MS-P2's job,
         * and a mismatch should log a WARN that NAMES the versions — an old reader's only signal
         * today is a generic "undecodable", which is the hardest thing to diagnose in a cluster.
         */
        const val SCHEMA_VERSION: String = "ttr-lexicon-compiled/v5"
    }
}

/**
 * MH (contracts §4) — an object with a declared relation TO this entity:
 * `def relation { from: <factRef>, to: <this> }`.
 *
 * [factRef] is named for the case the resolver's rules act on, but it is *whatever object declares
 * a relation to this ref* — on a real estate often another dimension (a customer relates to a
 * customer_address). Harmless, because those rules only ever pair a dimension with a
 * measure-capable candidate; said here so the first reader of a raw archive does not have to
 * derive it.
 *
 * The E-R reachability the resolver's T3 rule decides on, projected here at compile time so no
 * consumer infers structure from names (the same rule that put `objectKind` in [TargetFacts]).
 * [mandatory] is the relation's `cardinality.to` lower bound ≥ 1 — "every row of this fact carries
 * one of these entities" — which is what makes the dimension reading and the channel reading
 * provably the same rows rather than merely the same on today's data.
 */
@Serializable
data class Reach(
    val factRef: String,
    val mandatory: Boolean,
)

/**
 * MS (contracts §5/§6) — per-targetRef model facts, derived by `MentionKinds` at compile time.
 *
 * Facts are per REF, not per row: a term and its target are different things, and three rows
 * naming the same entity share one set of facts. That is why this is a header-level map rather
 * than columns on [CompiledEntry], which stays byte-identical to v1.
 */
@Serializable
data class TargetFacts(
    /** One of the four `MentionKinds` values. Consumers tolerate unknowns (J-v2). */
    val objectKind: String,
    /** The owning entity's targetRef; null for entities. */
    val ownerRef: String? = null,
    /**
     * MH — the facts that relate to this ref, sorted by `factRef`. Empty for members, and for
     * entities nothing relates to. Defaulted, so a **v2 archive decodes here**; the reverse
     * direction is what §11's compatibility matrix covers — a v3 archive read by an MS-era
     * reader ignores the field and simply leaves T3 inert.
     */
    val reachedFrom: List<Reach> = emptyList(),
    /**
     * LP (contracts §2.1) — the MENTION facet: which attribute carries this object under the
     * aspect a quoted literal is about. `semantics { name: · code: }`, as FULL attribute refs
     * (`er.entity.store.name`), because the resolver compares them to `Attribution.attribute_ref`
     * and a local name would make every consumer re-join.
     *
     * Null when the model declares none — or when it names a member this object does not have,
     * which is a model mistake and not a fact to invent. Empty for members: a member has no name
     * column, it IS one.
     *
     * ⛔ Copied from the same `mentionSemantics` read that fills [objectKind], never derived from
     * the ref string. "The entity's name column" is a DECLARED fact (the rule this whole file
     * lives by), and the resolver already refuses to guess it — LP-P1's `Verbatim` leaves a
     * literal HEADLESS rather than pick a column.
     */
    val nameRef: String? = null,
    val codeRef: String? = null,
    /**
     * A **Java regex** every value of the [codeRef] attribute matches, so the resolver can tell a
     * code-shaped quoted literal by the model's own pattern — in addition to (never instead of) its
     * fallback shape test (`VerbatimAttribution.attributeRefOf`). Always a regex, never a mask
     * (review-103 D4 / F6): the owner's declared `semantics { code_pattern: "…" }` when there is
     * one; otherwise the code attribute's period `code_format:` MASK translated to a regex
     * (`yyyyMM` → `^\d{6}$`); otherwise null. Null too when [codeRef] is null.
     *
     * ⚠ The name is historical and kept on purpose: before D4 the compiler copied the raw
     * `code_format:` mask here, which the resolver then compiled as a regex that matched nothing a
     * user would type. Renaming the field would have been an archive-schema change for a fix that
     * only had to correct what the PRODUCER writes — every reader already treats it as a regex.
     */
    val codeFormat: String? = null,
    /**
     * MV (member-vocabulary contracts §5.1) — this ref is an INDEXED attribute/column: it has a
     * member vocabulary, registered and queried under this very ref as its category. True for every
     * indexed attribute whether or not it has lexicon terms — which is why, from v5, `targets` holds
     * refs that no entry row points at. Its owner is [ownerRef], and that is the only way a consumer
     * reaches an entity's member vocabularies (✅MV-3): the entity's own entry lists none.
     *
     * Defaulted: a v4 archive decodes with `false` everywhere, and a v4 reader ignores the field.
     */
    val memberVocabulary: Boolean = false,
)

/**
 * The compiled declared+metadata lexicon: one header, one flat entry table.
 *
 * [contentHash] deliberately covers the ENTRY TABLE ONLY. It is the RV-39 layer tuple's
 * `lexicon_artifact_hash`, and a version that changed because the clock moved would make the
 * tuple useless for "did the vocabulary change?" — which is the only question it is asked.
 */
@Serializable
data class CompiledLexicon(
    val header: CompiledLexiconHeader,
    val entries: List<CompiledEntry>,
    /**
     * MS — keyed by `targetRef`, MODEL_OBJECT refs only; `op:`/`ground:` refs never appear.
     * A ref the model does not describe simply has no entry, which is the same posture RV-20
     * takes toward a dangling ref: say nothing rather than guess.
     *
     * ⚠ Iteration order IS byte order here (kotlinx.serialization writes a map in iteration
     * order), and the archive must be byte-stable across builds — see [CompiledLexiconHeader.builtAt].
     * The producer sorts by key; [toJson] sorts again before encoding, so a caller handing over an
     * unsorted map cannot break determinism by accident.
     */
    val targets: Map<String, TargetFacts> = emptyMap(),
) {
    val contentHash: String get() = sha256(CANONICAL.encodeToString(entries).toByteArray(Charsets.UTF_8))

    /** The determinism guard: what gets serialized is always key-sorted, whatever the caller built. */
    private fun canonical(): CompiledLexicon = if (targets.size <= 1) this else copy(targets = targets.toSortedMap())

    fun toJson(): String = PRETTY.encodeToString(canonical())

    companion object {
        /**
         * Compact + key-stable: the bytes [contentHash] is taken over. Encode-only — nothing
         * decodes through this instance, so its leniency setting would be inert either way.
         */
        internal val CANONICAL =
            Json {
                prettyPrint = false
                encodeDefaults = true
                ignoreUnknownKeys = false
            }

        /**
         * What actually lands in the archive — diffable in review — **and** what reads it back.
         *
         * ⛑ `ignoreUnknownKeys = true` is the half of backward compatibility a defaulted field
         * does NOT buy (review-082 F1). A default makes old json decode in a NEW reader; it says
         * nothing about new json in an OLD one, and `encodeDefaults = true` means every added
         * field is written into EVERY archive — `"targets": {}` ships even for an estate with no
         * model. With strict decoding, the first reader built one version behind throws
         * `JsonDecodingException: Encountered an unknown key`, and because both serving readers
         * treat an undecodable archive as "missing" (warn, keep the cache, serve an EMPTY
         * vocabulary), the symptom is silent: every declared term simply stops resolving.
         *
         * So: encoding stays strict and total, decoding tolerates fields it has not been taught.
         * This cannot repair readers already built against `ttr-lexicon-compiled/v1` — nothing
         * can — which is why contracts §6 also carries the ordering rule, **readers before
         * producers**, for the release that crosses v1→v2.
         */
        internal val PRETTY =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        fun fromJson(text: String): CompiledLexicon = PRETTY.decodeFromString(text)
    }
}

/** One operator's behavior body, kept verbatim (RV-35 — the matcher never reads this). */
@Serializable
data class OperatorEntry(
    val body: String,
    val version: Int,
    /** `sha256:<hex>` over the body bytes, so a Golem can cache by identity. */
    val checksum: String,
    /** Which file won — estate overrides stdlib (T5). Provenance, not identity. */
    val source: EntryProvenance,
)

/**
 * The operator library — a SEPARATE artifact from the lexicon (RV-35): skill frontmatter
 * compiles into vocabulary, skill bodies never do. Keyed by `op:` id, insertion-ordered by
 * the producer in sorted key order so the JSON bytes are stable.
 */
@Serializable
data class OperatorLibrary(
    val schemaVersion: String = SCHEMA_VERSION,
    val operators: Map<String, OperatorEntry> = emptyMap(),
) {
    val contentHash: String get() = sha256(CompiledLexicon.CANONICAL.encodeToString(this).toByteArray(Charsets.UTF_8))

    fun toJson(): String = CompiledLexicon.PRETTY.encodeToString(this)

    companion object {
        const val SCHEMA_VERSION: String = "ttr-operator-library/v1"

        fun fromJson(text: String): OperatorLibrary = CompiledLexicon.PRETTY.decodeFromString(text)
    }
}

/** `sha256:<hex>`. Public: the compiler module hashes bodies and layer inputs with the same rule. */
fun sha256(bytes: ByteArray): String =
    "sha256:" +
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

/**
 * Term normalization — the one rule the matcher and the compiler must agree on.
 *
 * NFC, trimmed, internal whitespace collapsed, lowercased in the root locale. **Diacritics are
 * preserved**: Czech diacritics are contract-observable (CLAUDE.md), and folding them here would
 * silently make "vyroba" match "výroba" as EXACT, which is a TYPOS decision the author did not make.
 */
object TermNormalizer {
    private val WS = Regex("""\s+""")
    private val COMBINING_MARKS = Regex("""\p{M}+""")

    fun normalize(text: String): String =
        java.text.Normalizer
            .normalize(text, java.text.Normalizer.Form.NFC)
            .trim()
            .replace(WS, " ")
            .lowercase()

    /**
     * MH — the **collision** key: [normalize], then NFD, then strip combining marks.
     *
     * This is the resolver's index key (`org.tatrman.text.Normalization.fold`), not the archive's.
     * Two refs meet at runtime iff their FOLDED forms are equal, so "do these two declarations
     * claim the same word?" has to be asked here and nowhere else — `vyroba` and `výroba` are one
     * anchor to the matcher even though [normalize] keeps them as two rows.
     *
     * A second function, never a replacement: [normalize] stays the stored/merge form
     * (`LexiconCompiler.merge`), and folding there would lose a distinction the author made.
     *
     * Only *combining* marks are stripped — a precomposed letter with no canonical decomposition
     * (`Đ`, `Ł`) survives, exactly as the service's fold leaves it. Pinned across all three
     * implementations by `packages/semantics/src/lexicon/fold-parity.json` (`FoldParitySpec`).
     */
    fun fold(text: String): String =
        java.text.Normalizer
            .normalize(normalize(text), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
}

/**
 * The `kind: "lexicon"` archive's layout — the only thing a SERVING consumer needs to know.
 *
 * Ruled 2026-08-02 (a3): the compiled lexicon is its own archive, packed by the same
 * `SnapshotWriter` under the same determinism rules, with its own content id and a manifest
 * naming the model snapshot it was built against. Not entries inside the model archive — that
 * would break "loading over an archive == loading over the repo it was packed from".
 *
 * Reading it takes `ttr-snapshot` + this module and nothing else:
 * ```
 * val docs = SnapshotReader.read(bytes).contents.docs
 * val lexicon = CompiledLexicon.fromJson(docs.getValue(LexiconArchive.LEXICON))
 * ```
 */
object LexiconArchive {
    /** `SnapshotManifest.kind` for this archive. */
    const val KIND: String = "lexicon"

    /** Paths **relative to `docs/`**, which is the container's own prefix. */
    const val LEXICON: String = "lexicon.json"
    const val OPERATORS: String = "operator-library.json"

    /** `SnapshotManifest.resolvedFrom` key naming the model snapshot the refs were checked against. */
    const val RESOLVED_FROM_MODEL: String = "modelSnapshotId"
}
