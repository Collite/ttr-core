// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

/**
 * The `RG-LEX-*` catalogue (RV-P1.1). Ids follow the `RG-<AREA>-<NNN>` house convention used by
 * the resolution & grounding services — stable, documented, one code per condition — and are
 * mirrored in `docs/features/resolution/lexicon-schemas.md` with an example per code.
 *
 * Every rejection a caller can see is constructed here, so the catalogue cannot silently gain an
 * undocumented code. All of them are errors: each rejects a file, so none has a degraded mode.
 */
object LexiconErrors {
    const val UNKNOWN_METHOD = "RG-LEX-001"
    const val MISSING_REQUIRED = "RG-LEX-002"
    const val TYPOS_WITHOUT_DISTANCE = "RG-LEX-003"
    const val OP_NOT_PREFIXED = "RG-LEX-004"
    const val NO_TRIGGERS = "RG-LEX-005"
    const val DUPLICATE_TERM = "RG-LEX-006"
    const val UNKNOWN_KEY = "RG-LEX-007"
    const val SCHEMA_ID_MISMATCH = "RG-LEX-008"
    const val MALFORMED_FRONTMATTER = "RG-LEX-009"
    const val UNSUPPORTED_LANG = "RG-LEX-010"
    const val MALFORMED_YAML = "RG-LEX-011"
    const val UNKNOWN_GROUNDING_KIND = "RG-LEX-012"

    // RV-44 (RV-P3.0) — declared matching profiles.
    const val UNKNOWN_NORM = "RG-LEX-013"
    const val TYPOS_WITHOUT_EXACT = "RG-LEX-014"
    const val METHOD_AND_MATCH = "RG-LEX-015"
    const val SCORE_OUT_OF_RANGE = "RG-LEX-016"
    const val TYPOS_BUDGET_EXHAUSTS_SCORE = "RG-LEX-017"

    // LP (contracts §3.1/§7) — the `pred:` string-predicate slice.
    const val UNKNOWN_PREDICATE_KIND = "RG-LEX-030"
    const val WEAK_PREDICATE_FORM = "RG-LEX-031"
    const val WIDE_PREDICATE_FORM = "RG-LEX-032"

    /** Every code this library can emit — the catalogue's own index. */
    val ALL: List<String> =
        listOf(
            UNKNOWN_METHOD,
            MISSING_REQUIRED,
            TYPOS_WITHOUT_DISTANCE,
            OP_NOT_PREFIXED,
            NO_TRIGGERS,
            DUPLICATE_TERM,
            UNKNOWN_KEY,
            SCHEMA_ID_MISMATCH,
            MALFORMED_FRONTMATTER,
            UNSUPPORTED_LANG,
            MALFORMED_YAML,
            UNKNOWN_GROUNDING_KIND,
            UNKNOWN_NORM,
            TYPOS_WITHOUT_EXACT,
            METHOD_AND_MATCH,
            SCORE_OUT_OF_RANGE,
            TYPOS_BUDGET_EXHAUSTS_SCORE,
            UNKNOWN_PREDICATE_KIND,
            WEAK_PREDICATE_FORM,
            WIDE_PREDICATE_FORM,
        )

    fun unknownMethod(
        method: String,
        at: Provenance,
    ) = LexiconViolation(
        UNKNOWN_METHOD,
        "unknown match method '$method' — the set is EXACT | TYPOS(1..3) | TOKENS.",
        at,
    )

    fun typosWithoutDistance(
        method: String,
        at: Provenance,
    ) = LexiconViolation(
        TYPOS_WITHOUT_DISTANCE,
        "'$method' carries no edit budget — write TYPOS(1), TYPOS(2) or TYPOS(3).",
        at,
    )

    fun missingRequired(
        key: String,
        where: String,
        at: Provenance,
    ) = LexiconViolation(MISSING_REQUIRED, "required key '$key' missing at $where.", at)

    fun opNotPrefixed(
        op: String,
        at: Provenance,
    ) = LexiconViolation(
        OP_NOT_PREFIXED,
        "skill `op` value '$op' is not an `op:` ref — prefix it, e.g. `op:trend`.",
        at,
    )

    /**
     * RV-42 — the `ground:` kind vocabulary is CLOSED (chrono | money | geo). Unlike a dangling
     * model ref, which the compiler drops with a warning, an unknown grounding kind is rejected
     * at authoring time: no kernel would ever load `ground:weather`, so the entry could not
     * degrade into anything — it would simply never fire, silently.
     */
    fun unknownGroundingKind(
        ref: String,
        known: Collection<String>,
        at: Provenance,
    ) = LexiconViolation(
        UNKNOWN_GROUNDING_KIND,
        "`$ref` is not a grounding kind — the set is closed: ${known.sorted().joinToString(" | ") { "ground:$it" }}.",
        at,
    )

    /**
     * LP contracts §3.1 — the `pred:` kind vocabulary is CLOSED, and closed for the same reason
     * `ground:` is: the ref names a *behaviour a consumer implements*, not an extension point. The
     * resolver only carries the ref on the lattice; the consumer — kantheon's fast-path renderer —
     * lowers `pred:starts_with` to a parameterised `col LIKE ? || '%' ESCAPE …`, and refuses a ref
     * it does not know rather than defaulting. `pred:like` is a kind nothing lowers, so the entry
     * would never fire and never say why.
     */
    fun unknownPredicateKind(
        ref: String,
        known: Collection<String>,
        at: Provenance,
    ) = LexiconViolation(
        UNKNOWN_PREDICATE_KIND,
        "`$ref` is not a string predicate — the set is closed: ${known.sorted().joinToString(" | ") { "pred:$it" }}.",
        at,
    )

    /**
     * LP contracts §3.1 — a `pred:` form that is one character or a function word of its
     * language, or a phrase made of function words only (*with the*, *s na*).
     *
     * Every other target class is anchored by something: a model object has a ref in the snapshot,
     * an operator needs its whole trigger phrase. A predicate form is matched against RUNNING TEXT
     * to decide that the words around a quoted literal mean "starts with" — so a form like cs `s`
     * or en `with` fires on most questions ever asked, and turns a filter the user did not write
     * into one the plan executes. A phrase with a content word in it (`s textem`) is legal: `pred:`
     * forms are authored `EXACT` and the resolver accepts one only when its window covers the whole
     * form, so the content word is always part of the evidence.
     */
    fun weakPredicateForm(
        text: String,
        ref: String,
        why: String,
        at: Provenance,
    ) = LexiconViolation(
        WEAK_PREDICATE_FORM,
        "\"$text\" cannot be a `$ref` trigger — $why. A predicate form is matched against running " +
            "text, so words this common would declare a filter in questions nobody meant one in. " +
            "Use a longer form, or a phrase with a content word in it (`s textem`).",
        at,
    )

    /**
     * Review-103 F1 — a `pred:` form wider than [LexiconValidator.MAX_PREDICATE_FORM_TOKENS].
     *
     * The resolver looks for predicate forms in the windows immediately left of a quoted literal,
     * and those windows are at most [LexiconValidator.MAX_PREDICATE_FORM_TOKENS] tokens wide. A
     * wider form can never be seen whole, so the only thing it could ever contribute is a fragment
     * — and a fragment firing a predicate is the defect this rule exists to make unauthorable.
     */
    fun widePredicateForm(
        text: String,
        ref: String,
        width: Int,
        max: Int,
        at: Provenance,
    ) = LexiconViolation(
        WIDE_PREDICATE_FORM,
        "\"$text\" cannot be a `$ref` trigger — it is $width words, and a predicate form may be at " +
            "most $max: the resolver only looks $max words to the left of a quoted literal, so a " +
            "wider form could never match whole. Shorten it.",
        at,
    )

    fun noTriggers(
        op: String,
        at: Provenance,
    ) = LexiconViolation(
        NO_TRIGGERS,
        "skill '$op' declares no triggers — it could never fire.",
        at,
    )

    fun duplicateTerm(
        text: String,
        lang: Lang,
        firstSeenLine: Int,
        at: Provenance,
    ) = LexiconViolation(
        DUPLICATE_TERM,
        "term \"$text\" (${lang.wire}) is declared twice in this file — first at line $firstSeenLine. " +
            "Two targets for one term in one file have no defined winner.",
        at,
    )

    fun unknownKey(
        key: String,
        where: String,
        at: Provenance,
    ) = LexiconViolation(
        UNKNOWN_KEY,
        "unknown key '$key' at $where — the lexicon schemas are closed.",
        at,
    )

    fun schemaIdMismatch(
        declared: String,
        expected: String,
        at: Provenance,
    ) = LexiconViolation(
        SCHEMA_ID_MISMATCH,
        "`schema: $declared` does not match the schema this file is validated against ($expected).",
        at,
    )

    fun unsupportedLang(
        lang: String,
        at: Provenance,
    ) = LexiconViolation(
        UNSUPPORTED_LANG,
        "unsupported lang '$lang' — use `cs`, `en`, or the both-languages form `cs|en`.",
        at,
    )

    fun missingFrontmatter(at: Provenance) =
        LexiconViolation(
            MALFORMED_FRONTMATTER,
            "no `---` frontmatter block — the body alone is not a skill.",
            at,
        )

    fun unterminatedFrontmatter(at: Provenance) =
        LexiconViolation(
            MALFORMED_FRONTMATTER,
            "frontmatter opened with `---` but never closed.",
            at,
        )

    fun malformedYaml(
        detail: String,
        at: Provenance,
    ) = LexiconViolation(MALFORMED_YAML, "not parseable as YAML: $detail", at)

    fun unknownNorm(
        norm: String,
        at: Provenance,
    ) = LexiconViolation(
        UNKNOWN_NORM,
        "unknown norm '$norm' — the set is closed: ${Norm.WIRE_NAMES.joinToString(" | ")}.",
        at,
    )

    fun typosWithoutExact(
        norm: String,
        at: Provenance,
    ) = LexiconViolation(
        TYPOS_WITHOUT_EXACT,
        "`typos` on norm '$norm' has no sibling `exact` on the same norm — the per-edit penalty " +
            "is subtracted from that score, so it needs its anchor.",
        at,
    )

    fun methodAndMatch(
        where: String,
        at: Provenance,
    ) = LexiconViolation(
        METHOD_AND_MATCH,
        "`method` and `match` are both declared at $where — `method` IS sugar for a `match` " +
            "profile, so writing both leaves no precedence question worth answering. Keep one.",
        at,
    )

    fun scoreOutOfRange(
        key: String,
        value: String,
        bound: String,
        at: Provenance,
    ) = LexiconViolation(SCORE_OUT_OF_RANGE, "`$key: $value` is out of range — $bound.", at)

    /**
     * `exact` and `typos` are each in range on their own, and the pair is still unusable: the
     * matcher scores an edit at `exact − d·penalty`, so a budget that can reach or pass the anchor
     * declares a match at zero or a NEGATIVE within-class score. Scores are `(0,1]` by contract;
     * this is the one way to leave that range while every individual field is legal, which is
     * exactly why it needs its own check rather than a wider bound on either field.
     */
    fun typosBudgetExhaustsScore(
        norm: String,
        exact: Double,
        distance: Int,
        penalty: Double,
        at: Provenance,
    ) = LexiconViolation(
        TYPOS_BUDGET_EXHAUSTS_SCORE,
        "on norm '$norm', `exact: $exact` with `typos: { distance: $distance, penalty: $penalty }` " +
            "scores ${exact - distance * penalty} at the widest edit — a score must stay in (0,1]. " +
            "Lower `distance`, lower `penalty`, or raise `exact`.",
        at,
    )
}

/**
 * Authoring **warnings** (RV-44). Distinct from [LexiconErrors] in more than severity: a warning
 * names a file that is valid, compiles, and ships — with one behaviour the author probably did not
 * intend. They ride out of the loader on [LexiconLoad.Ok.warnings] and are folded into the build's
 * warning stream beside RV-20's dangling refs, which is where an author already looks.
 *
 * The `1xx` band is the warning band, so a code alone tells you whether it stops a build.
 */
object LexiconWarnings {
    /** ⚑M-4 — a term too short to fuzz-match declared a typos/TYPOS rule anyway. */
    const val SHORT_TERM_TYPOS_GUARD = "RG-LEX-101"

    /** Every warning code this library can emit. */
    val ALL: List<String> = listOf(SHORT_TERM_TYPOS_GUARD)

    fun shortTermTyposGuard(
        text: String,
        at: Provenance,
    ) = LexiconViolation(
        SHORT_TERM_TYPOS_GUARD,
        "\"$text\" is ${MatchProfile.SHORT_TERM_MAX_CHARS} characters or fewer, so the short-term " +
            "guard suppresses its typos rule — a one-edit neighbourhood around a token this short " +
            "reaches most of its siblings. The build succeeds and the matcher will not fuzz it; " +
            "drop the rule, or lengthen the authored form.",
        at,
    )
}
