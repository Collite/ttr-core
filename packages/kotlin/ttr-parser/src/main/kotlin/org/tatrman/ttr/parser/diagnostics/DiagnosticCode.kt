// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.diagnostics

/**
 * Canonical diagnostic codes, mirroring `packages/parser/src/diagnostics.ts`
 * one-for-one (every value identical). The full set lives in `ttr-parser` even
 * though some codes are only fired by `ttr-semantics` (Phase 2): the codes are
 * a contract shared by all modeler artifacts, so consumers depend on one
 * canonical enum. See contracts.md §2.8.
 */
enum class DiagnosticCode(
    val id: String,
) {
    ParseError("ttr/parse-error"),
    ParseRecoveryInfo("ttr/parse-recovery-info"),
    UnknownProperty("ttr/unknown-property"),
    UnresolvedReference("ttr/unresolved-reference"),
    DuplicateDefinition("ttr/duplicate-definition"),
    RequiredPropertyMissing("ttr/required-property-missing"),
    InvalidType("ttr/invalid-type"),
    EntityAttributeNotFound("ttr/entity-attribute-not-found"),
    PrimaryKeyColumnNotFound("ttr/primary-key-column-not-found"),
    WrongFileKind("ttr/wrong-file-kind"),
    UnimportedReference("ttr/unimported-reference"),
    UnusedImport("ttr/unused-import"),
    WildcardWithNoMatches("ttr/wildcard-with-no-matches"),
    DuplicateImport("ttr/duplicate-import"),
    CircularPackageDependency("ttr/circular-package-dependency"),
    PackageDeclarationMismatch("ttr/package-declaration-mismatch"),
    MissingPackageDeclaration("ttr/missing-package-declaration"),
    AreaMemberNotFound("ttr/area-member-not-found"),
    AreaEmpty("ttr/area-empty"),
    DuplicateArea("ttr/duplicate-area"),
    AreaRedundantMember("ttr/area-redundant-member"),
    AmbiguousReference("ttr/ambiguous-reference"),
    GraphObjectNotFound("ttr/graph-object-not-found"),
    GraphLayoutStaleNode("ttr/graph-layout-stale-node"),
    GraphObjectsEmpty("ttr/graph-objects-empty"),
    GraphNameMismatch("ttr/graph-name-mismatch"),
    FileOrdering("ttr/file-ordering"),
    FuzzyWithoutSearchable("ttr/fuzzy-without-searchable"),
    DuplicateSearchProperty("ttr/duplicate-search-property"),
    DuplicateBinding("ttr/duplicate-binding"),

    // RV-P1.5 (grammar 0.12, RV-32) — the match-method attribute on `searchable`.
    // Cross-target contract: mirrored in the TS `DiagnosticCode` and Python
    // `diagnostics.py`; the deprecation rides the portable conformance subset.
    SearchFuzzyDeprecated("ttr/search-fuzzy-deprecated"),
    UnknownMatchMethod("ttr/unknown-match-method"),
    InvalidMatchMethodArgument("ttr/invalid-match-method-argument"),

    // Grounding Phase 1 (grammar 4.2): a `semantics { … }` entry whose value is a
    // nested object/list rather than a scalar. Keeps ttr-semantics' input flat.
    SemanticsNonScalarValue("ttr/semantics-non-scalar"),

    // Grounding Phase 1 — `semantics { … }` vocabulary/shape validation. These
    // stable TTR-SEM-2xx codes are the cross-repo contract mirrored by ai-platform's
    // closed proto enums (feature-grounding-contracts.md §4); the vocabulary
    // (ttr-semantics `SEMANTICS_VOCABULARY_VERSION`) and the enums version together.
    SemUnknownKey("TTR-SEM-200"),
    SemUnknownRole("TTR-SEM-201"),
    SemUnknownKind("TTR-SEM-202"),
    SemDuplicateKey("TTR-SEM-203"),
    SemMisplacedKeyword("TTR-SEM-204"),
    SemTypeConstraint("TTR-SEM-205"),
    SemCompleteness("TTR-SEM-206"),
    SemMultipleEventDate("TTR-SEM-207"),
    SemBadPeriodRef("TTR-SEM-208"),
    SemBadCurrencyRef("TTR-SEM-209"),
    SemGeoPair("TTR-SEM-210"),
    SemValidPair("TTR-SEM-211"),

    // MS (vocabulary v3) — the mention facet, contracts §4. `SemMentionShape` landed first
    // (review-081 F2): MS-P0·S1b widened THIS runtime's walker to carry lists and objects, so
    // the analyzer needed a code to reject them with before it knew the mention keys. The
    // other six arrive with the keys themselves in MS-P1·S1.
    SemMentionRefUnresolved("TTR-SEM-212"), // name:/code:/measure attribute: not an attribute of THIS owner
    SemMeasureNotNumeric("TTR-SEM-213"),
    SemMeasureDuplicate("TTR-SEM-214"),
    SemBadAggregation("TTR-SEM-215"), // outside the closed AGGREGATIONS list
    SemMentionShape("TTR-SEM-216"), // a semantics value is not the shape its key takes
    SemLegacyMentionMismatch("TTR-SEM-217"), // legacy and semantics disagree — always a bug (MS-D2)
    SemLegacyMentionDeprecated("TTR-SEM-218"),

    // LP review-103 (D4) — `code_pattern:` on the entity/table mention facet: a pattern the JVM
    // cannot compile as a regex, an empty one, or one declared on a block that names no `code:`.
    SemBadCodePattern("TTR-SEM-219"),

    // EN-P1 (grammar 0.10) — TTR-M entry declarations (`management` / `changeSemantics`, FO §9/§11).
    // Deliberately in the `ttr/entry-*` slug family, NOT the ai-platform-synced TTR-SEM-2xx grounding
    // vocabulary. `EntryMissingRole` (scd2 without valid-from/valid-to, ledger without reversal-link)
    // is the model diagnostic that feeds the compiler's TTRP-EN-003 in EN-P2.
    EntryInvalidManagement("ttr/entry-invalid-management"),
    EntryInvalidChangeSemantics("ttr/entry-invalid-change-semantics"),
    EntryUnknownRole("ttr/entry-unknown-role"),
    EntryRoleColumnNotFound("ttr/entry-role-column-not-found"),
    EntryMissingRole("ttr/entry-missing-role"),

    // MH T1 — a declared lexicon form folds onto another ref's name/label anchor; both refs claim
    // the word at runtime (contracts §2 of project/server/features/mention-homonymy).
    // NOTE: this enum carries none of the other `ttr/lexicon-*` codes; the one-for-one mirror claim
    // in the header is already false (MH finding, not fixed here).
    LexiconFormCollidesWithName("ttr/lexicon-form-collides-with-name"),

    // embedded-sql (DESIGN §5/§6): tagged-block tag resolution.
    UnknownLanguageTag("ttr/unknown-language-tag"),
    LanguageTagMismatch("ttr/language-tag-mismatch"),
    DeprecatedLanguageProperty("ttr/deprecated-language-property"),
    ;

    override fun toString(): String = id
}

/** Mirrors `DiagnosticSeverity` in `diagnostics.ts`. */
enum class DiagnosticSeverity { Error, Warning, Information, Hint }
