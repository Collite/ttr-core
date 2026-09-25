// SPDX-License-Identifier: Apache-2.0
export enum DiagnosticCode {
  ParseError = 'ttr/parse-error',
  ParseRecoveryInfo = 'ttr/parse-recovery-info',
  UnknownProperty = 'ttr/unknown-property',
  UnresolvedReference = 'ttr/unresolved-reference',
  DuplicateDefinition = 'ttr/duplicate-definition',
  RequiredPropertyMissing = 'ttr/required-property-missing',
  InvalidType = 'ttr/invalid-type',
  EntityAttributeNotFound = 'ttr/entity-attribute-not-found',
  PrimaryKeyColumnNotFound = 'ttr/primary-key-column-not-found',
  WrongFileKind = 'ttr/wrong-file-kind',
  UnimportedReference = 'ttr/unimported-reference',
  UnusedImport = 'ttr/unused-import',
  WildcardWithNoMatches = 'ttr/wildcard-with-no-matches',
  DuplicateImport = 'ttr/duplicate-import',
  CircularPackageDependency = 'ttr/circular-package-dependency',
  PackageDeclarationMismatch = 'ttr/package-declaration-mismatch',
  PackagePrefixDivergence = 'ttr/package-prefix-divergence',
  InvalidPackageSegment = 'ttr/invalid-package-segment',
  MissingPackageDeclaration = 'ttr/missing-package-declaration',
  // Subject-area resolution (v3.0 `def area`; formerly `.ttrd` domain).
  AreaMemberNotFound = 'ttr/area-member-not-found',
  AreaEmpty = 'ttr/area-empty',
  DuplicateArea = 'ttr/duplicate-area',
  AreaRedundantMember = 'ttr/area-redundant-member',
  AmbiguousReference = 'ttr/ambiguous-reference',
  GraphObjectNotFound = 'ttr/graph-object-not-found',
  GraphLayoutStaleNode = 'ttr/graph-layout-stale-node',
  GraphObjectsEmpty = 'ttr/graph-objects-empty',
  GraphNameMismatch = 'ttr/graph-name-mismatch',
  FileOrdering = 'ttr/file-ordering',
  FuzzyWithoutSearchable = 'ttr/fuzzy-without-searchable',
  DuplicateSearchProperty = 'ttr/duplicate-search-property',
  // RV-P1.5 (grammar 0.12, RV-32) — the match-method attribute on `searchable`.
  // `fuzzy` is the boolean it replaces; the vocabulary (EXACT/TYPOS/TOKENS) and
  // the arity rule are semantic, so these three are cross-target contracts
  // mirrored in Kotlin `DiagnosticCode` and Python `diagnostics.py`.
  SearchFuzzyDeprecated = 'ttr/search-fuzzy-deprecated',
  UnknownMatchMethod = 'ttr/unknown-match-method',
  InvalidMatchMethodArgument = 'ttr/invalid-match-method-argument',
  // Grounding Phase 1 (grammar 4.2): a `semantics { … }` entry whose value is a
  // nested object/list rather than a scalar. Keeps ttr-semantics' input flat.
  SemanticsNonScalarValue = 'ttr/semantics-non-scalar',
  // Grounding Phase 1 — `semantics { … }` vocabulary/shape validation. These
  // stable TTR-SEM-2xx codes are the cross-repo contract mirrored by ai-platform's
  // closed proto enums (feature-grounding-contracts.md §4); the vocabulary
  // (ttr-semantics `SEMANTICS_VOCABULARY_VERSION`) and the enums version together.
  SemUnknownKey = 'TTR-SEM-200',
  SemUnknownRole = 'TTR-SEM-201',
  SemUnknownKind = 'TTR-SEM-202',
  SemDuplicateKey = 'TTR-SEM-203',
  SemMisplacedKeyword = 'TTR-SEM-204',   // kind on an attribute/column, or role on an entity/table
  SemTypeConstraint = 'TTR-SEM-205',
  SemCompleteness = 'TTR-SEM-206',
  SemMultipleEventDate = 'TTR-SEM-207',
  SemBadPeriodRef = 'TTR-SEM-208',       // dangling / mis-kinded `period:` ref
  SemBadCurrencyRef = 'TTR-SEM-209',     // dangling / roleless `currency:` ref
  SemGeoPair = 'TTR-SEM-210',
  SemValidPair = 'TTR-SEM-211',
  // MS (vocabulary v3) — the mention facet: entity-side `name:` / `code:` / `measures:`,
  // and the deprecation of the legacy `nameAttribute:` / `codeAttribute:` properties.
  SemMentionRefUnresolved = 'TTR-SEM-212',   // name:/code:/measure attribute: not an attribute of THIS owner
  SemMeasureNotNumeric = 'TTR-SEM-213',
  SemMeasureDuplicate = 'TTR-SEM-214',
  SemBadAggregation = 'TTR-SEM-215',         // outside the closed AGGREGATIONS list
  SemMentionShape = 'TTR-SEM-216',           // a mention value is not the shape the key takes
  SemLegacyMentionMismatch = 'TTR-SEM-217',  // legacy and semantics disagree — always a bug (MS-D2)
  SemLegacyMentionDeprecated = 'TTR-SEM-218',
  // LP review-103 (D4) — `code_pattern:` on the mention facet: not a regex, empty, or no `code:`.
  SemBadCodePattern = 'TTR-SEM-219',
  DuplicateBinding = 'ttr/duplicate-binding',
  // qname-redesign (contracts §5): manifest schema config + slot discipline.
  SchemaNameCollision = 'ttr/schema-name-collision',
  UnknownPackageSchema = 'ttr/unknown-package-schema',
  SchemaOnLogicalModel = 'ttr/schema-on-logical-model',
  RequireQualifiedRefs = 'ttr/require-qualified-refs',
  // embedded-sql (DESIGN §5/§6): tagged-block tag resolution.
  UnknownLanguageTag = 'ttr/unknown-language-tag',
  LanguageTagMismatch = 'ttr/language-tag-mismatch',
  DeprecatedLanguageProperty = 'ttr/deprecated-language-property',
  // v3.1 MD (multidimensional) model — contracts §7. Logical + binding codes.
  MdUnknownSchemaDef = 'md/unknown-schema-def',
  MdUnknownRef = 'md/unknown-ref',
  MdAttrNeedsDomain = 'md/attr-needs-domain',
  MdAttrTypeInMd = 'md/attr-type-in-md',
  ErAttrDomainInEr = 'er/attr-domain-in-er',
  MdDimKeyUnknown = 'md/dim-key-unknown',
  MdDimMultipleKeys = 'md/dim-multiple-keys',
  MdKindOnScalar = 'md/kind-on-scalar',
  MdBadRestrictValue = 'md/bad-restrict-value',
  MdUnknownRestrictClause = 'md/unknown-restrict-clause',
  MdBoundDomainNoSource = 'md/bound-domain-no-source',
  MdUnknownCalcMap = 'md/unknown-calc-map',
  MdBadCalcArgs = 'md/bad-calc-args',
  MdCalcTypeMismatch = 'md/calc-type-mismatch',
  MdCalcCardinalityConflict = 'md/calc-cardinality-conflict',
  MdTableMapNoBinding = 'md/table-map-no-binding',
  MdNoHierarchyStep = 'md/no-hierarchy-step',
  MdAmbiguousHierarchyStep = 'md/ambiguous-hierarchy-step',
  MdLevelNotInDim = 'md/level-not-in-dim',
  MdSemiadditiveNoValidby = 'md/semiadditive-no-validby',
  MdNonadditiveRecomputeUnsupported = 'md/nonadditive-recompute-unsupported',
  MdGrainRefUnknown = 'md/grain-ref-unknown',
  MdCubeletGrainUncovered = 'md/cubelet-grain-uncovered',
  MdGrainNotLeaf = 'md/grain-not-leaf',
  MdShapeMeasureMismatch = 'md/shape-measure-mismatch',
  MdMultisourceGrainMismatch = 'md/multisource-grain-mismatch',
  MdSourceOnUnboundDomain = 'md/source-on-unbound-domain',
  MdBindingOnCalcMap = 'md/binding-on-calc-map',
  MdMapColumnsIncomplete = 'md/map-columns-incomplete',
  MdMd2erPhysicalProp = 'md/md2er-physical-prop',
  MdIncompleteJournaling = 'md/incomplete-journaling',
  // embedded-sql (DESIGN §12.8 / contracts §5.1): SQL reference resolution
  // against the TTR `db` symbol table. Best-effort (warnings); see §3.4/§3.5.
  SqlUnknownTable = 'sql-unknown-table',
  SqlUnknownColumn = 'sql-unknown-column',
  SqlAmbiguousColumn = 'sql-ambiguous-column',
  SqlColumnNotOnAlias = 'sql-column-not-on-alias',
  SqlUndeclaredParam = 'sql-undeclared-param',
  SqlUnusedParam = 'sql-unused-param',
  // v4.4 TTR-M lexicon surface (RG-P4, contracts §7). Model-side vocabulary
  // diagnostics; the RG-* ids in contracts §8 are service-side (NLP/FUZ/GND/RES).
  LexiconWrongModelKind = 'ttr/lexicon-wrong-model-kind',   // term/pattern/example outside `model lexicon`, or a non-lexicon def inside one
  LexiconMissingTarget = 'ttr/lexicon-missing-target',      // entry with no `for:` target
  LexiconEntryFieldMissing = 'ttr/lexicon-entry-field-missing', // term without forms | pattern without match | example without text
  LexiconDuplicateForm = 'ttr/lexicon-duplicate-form',      // two entries contribute the identical surface form for one target
  // MH T1 — a declared form folds onto another ref's name/label anchor (contracts §2)
  LexiconFormCollidesWithName = 'ttr/lexicon-form-collides-with-name',
  LexiconLocaleOnNonLexicon = 'ttr/lexicon-locale-on-non-lexicon', // `model <x> locale …` where <x> ≠ lexicon
  // v4.4 S2 (RS-32) — legacy vocabulary sub-properties deprecate in favour of the
  // lexicon surface; each fires a named warning and migrates to canonical entries.
  LexiconLegacyAliases = 'ttr/lexicon-legacy-aliases',      // entity `aliases` / `search { aliases }` → `term`
  LexiconLegacyKeywords = 'ttr/lexicon-legacy-keywords',    // `search { keywords }` → `term`
  LexiconLegacyPatterns = 'ttr/lexicon-legacy-patterns',    // `search { patterns }` → `pattern`
  LexiconLegacyExamples = 'ttr/lexicon-legacy-examples',    // `search { examples }` → `example`
  LexiconLegacyDescriptions = 'ttr/lexicon-legacy-descriptions', // `search { descriptions }` → `description` (fold)
  // NLS-P10 (grammar 0.13, ⚑GXP-D7) — the localised `description: { … }` form.
  // Both are LINT codes, not parse errors: the parser stays mechanical and the
  // D7 fallback chain already degrades an unusable map to "".
  LocalizedDescriptionEmpty = 'ttr/localized-description-empty',        // `description: {}` — a map that names no locale
  LocalizedDescriptionMissingLocale = 'ttr/localized-description-missing-locale', // map omits the unit's declared `locale`
}

export enum DiagnosticSeverity {
  Error = 'error',
  Warning = 'warning',
  Information = 'information',
  Hint = 'hint',
}

export interface DiagnosticEntry {
  code: DiagnosticCode;
  message: string;
  severity: DiagnosticSeverity;
}