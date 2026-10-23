// SPDX-License-Identifier: Apache-2.0
export { parseManifest, resolveManifest, resolvePackagesConfig, defaultPackagesConfig, validateManifest, effectiveSchemaId } from './manifest.js';
export type { ProjectManifest, ResolvedManifest, PackagesConfig, PackagesConfigDiagnostic, SchemaBinding, PackageConfig, ManifestDiagnostic } from './manifest.js';
export {
  loadSqlConfig,
  parseSqlConfig,
  emptySqlConfig,
  namespaceFor,
  sqlNameFor,
  defaultsFor,
} from './sql-config.js';
export type {
  SqlConfig,
  SqlNamespaceMapping,
  SqlDefaults,
  SqlConfigDiagnostic,
} from './sql-config.js';
export { loadProjectFromOpenDocuments } from './project.js';
export type { Project } from './project.js';
export { qnameToKey, buildCanonicalKey, modelForKind, modelHasSchema, classifyReference, resolveReference, MODEL_CODES, cnWireName } from './qname.js';
export type {
  Qname, ModelCode, Vocab, PartialQname, RefSite, SymbolIndex,
  ResolvedReference, ReferenceDiagnostic, ReferenceDiagnosticCode,
} from './qname.js';
export { DocumentSymbolTable } from './symbol-table.js';
export type { SymbolEntry } from './symbol-table.js';
export { validateWorldDocument } from './world-validate.js';
export type { WorldDiagnostic, WorldDiagnosticCode } from './world-validate.js';
export { validateAutomationProject, automationStdlibNeeded } from './automation-validate.js';
export type { AutomationDiagnostic, AutomationSeverity } from './automation-validate.js';
export { validateSecurityDocument } from './security-validate.js';
export type { SecurityDiagnostic, SecurityDiagnosticCode } from './security-validate.js';
export { effectiveMatchMethod, fuzzyDeprecationMessage, validateSearchMethods, DEFAULT_TYPOS_DISTANCE } from './search-method.js';
export type { EffectiveMatchMethod, MatchMethodName, SearchMethodDiagnostic } from './search-method.js';
export { desugarLexicon } from './lexicon/desugar.js';
export {
  desugarConcepts,
  bindingKindFor,
  bindingConceptKey,
  layerOf,
  synthesizedBindingName,
  type CnLayer,
  type CnGrounding,
  type SugarConcept,
  type ConceptDesugar,
} from './cn/desugar.js';
export {
  analyzeGroundings,
  conceptKeyOf,
  propertyFingerprint,
  type CnDocument,
  type CnDiagnostic,
  type PlacedGrounding,
  type GroundingAnalysis,
} from './cn/groundings.js';
export {
  validateCn,
  cncDeprecations,
  type CnConcept,
  type CnValidation,
  type StatedProperty,
} from './cn/validate.js';
export {
  cnHygieneReport,
  renderCnHygieneMarkdown,
  elementRefOf,
  ExplicitMdErHop,
  EDITOR_DEFINITIONS_NOTE,
  RETIRED_CODES_NOTE,
  type CnHygieneReport,
  type ConceptHygieneRow,
  type GroundingGrade,
  type HopAnswer,
  type HopSlug,
} from './cn/hygiene.js';
export type { CanonicalLexiconEntry, LexiconAnalysis, LexiconDiagnostic } from './lexicon/model.js';
export { serializeVocabularySnapshot, foldId } from './lexicon/snapshot.js';
export { foldForCollision } from './lexicon/fold.js';
export type { DeclaredVocabulary, DeclaredVocabularyEntry, DeclaredValue } from './lexicon/snapshot.js';
export { ProjectSymbolTable } from './project-symbols.js';
export { Resolver } from './resolver.js';
export type { ResolutionResult, LexicalScope, ResolutionStep, ResolutionAttempt } from './resolver.js';
export {
  collectReferences,
  collectAllReferences,
  collectSugarDeclaringRefs,
  conceptSugarOf,
  nestedDefs,
  isCnContextRef,
  sugarDeclaringRef,
} from './references.js';
export type { CnSlotReference, MdSlotReference } from './references.js';
export { ReferenceIndex, enclosingQnameOf } from './reference-index.js';
export type { ReferenceLocation } from './reference-index.js';
export { PackageGraphBuilder, findCyclesOn } from './package-graph.js';

export { AreaTableBuilder, resolveArea, areaPackageClosure } from './area-table.js';
export type { ResolvedArea, AreaEntry } from './area-table.js';
export type { PackageGraph, PackageNode, PackageEdge } from './package-graph.js';
export { packageOfImport } from './references.js';
export { inferPackageFromUri } from './package-inference.js';
export {
  derivedPackage,
  effectivePackage,
  elideRoot,
  classifyPackageMismatch,
  isValidPackageSegment,
  invalidPackageSegments,
} from './derivation.js';
export type { PackageMismatchKind } from './derivation.js';
export { defaultSchemaForKind, defaultNamespaceForSchema, namespaceForKind, keyKindOf } from './default-schema.js';
export { resolveMdRef, underlyingDomainOf, mdCrossRefsOf } from './md-resolve.js';
export { shapeSatisfied, validateCalcArgs } from './md-calc.js';
export type { DomainShape, CalcArgProblem } from './md-calc.js';
export { computeLeaves, coLeafClasses, grainReachable, connectingMaps, inferStep } from './md-lattice.js';
export type { MapEdge, StepResult } from './md-lattice.js';
export { buildMdMapGraph, resolveLevelDomains } from './md-graph.js';
export type { MdMapGraph } from './md-graph.js';
export { aggKindOf, defaultMeasure, defaultAgg } from './md-defaults.js';
export type { AggKind } from './md-defaults.js';
export {
  MD_CALC_CATALOG,
  MD_CATALOG_VERSION,
  isKnownCalc,
  getCalcEntry,
  calcNames,
} from './md-catalog-source.js';
export type { CatalogEntry } from './md-catalog-source.js';
export { synthesizeMappings } from './mapping-synthesizer.js';
export { collectBindingReferences, collectBindingTargetReferences } from './mapping-references.js';
export type { BindingReference } from './mapping-references.js';
export { foldEq, foldIdent } from './sql/fold.js';
export {
  resolveSqlReferences,
  resolveSqlRefAt,
  buildSqlDbIndex,
  sqlScopeTables,
  resolveSqlTableName,
} from './sql/resolve.js';
export type {
  SqlDiagnostic,
  SqlResolveContext,
  SqlRefHit,
  DbTableInfo,
  SqlDbIndex,
  SqlScopeTable,
} from './sql/resolve.js';
export { checkSqlParameters } from './sql/param-check.js';
export type { SqlParamUsage, SqlParamCheckResult } from './sql/param-check.js';
export { SqlReferenceIndex } from './sql/reference-index.js';
export type { SqlRefLocation, SqlRefEntry, SqlRefRange } from './sql/reference-index.js';

// Grounding Phase 1 (grammar 4.2) — the `semantics { }` block vocabulary,
// validator, and typed result model.
export {
  SEMANTICS_VOCABULARY_VERSION,
  ENTITY_KINDS,
  ATTRIBUTE_ROLES,
  KIND_COMPLETENESS,
  ALL_ROLES,
  ALL_ATTRIBUTE_KEYS,
  ALL_ENTITY_KEYS,
  AGGREGATIONS,
  DEFAULT_AGGREGATION,
} from './semantics-block/vocabulary.js';
export type {
  EntityKind,
  AttributeRole,
  TypeConstraint,
  RoleSpec,
  CompletenessClause,
  RoleFacet,
  RoleFamily,
  Aggregation,
} from './semantics-block/vocabulary.js';
export { analyzeSemantics, typeFamilyOf } from './semantics-block/validator.js';
export type { SemanticsDiagnostic, SemanticsAnalysis } from './semantics-block/validator.js';
export { isEntitySemantics, isAttributeSemantics } from './semantics-block/model.js';
export type {
  ResolvedSemantics,
  ResolvedEntitySemantics,
  ResolvedAttributeSemantics,
  MeasureRef,
  SymbolRef,
} from './semantics-block/model.js';
export { editDistance, nearestMatch } from './semantics-block/suggest.js';
export {
  MENTION_KIND_MEASURE,
  MENTION_KIND_ATTRIBUTE,
  MENTION_KIND_ENTITY,
  MENTION_KIND_ENTITY_WITH_MEASURES,
  mentionKindOf,
} from './semantics-block/mention-kinds.js';
export type { MentionKind, ObjectFacts } from './semantics-block/mention-kinds.js';

export { mdErBindingDiagnostics, explicitErAttributes, resolveMdErRef, buildMdErRelationIndex, mdErUsedRelations } from './md/explicit.js';

export { PhysicalIndex, DerivedMdErHop, CompositeMdErHop, SyntacticQueryLineage, mdHopElements } from './md/hop.js';
export type { MdHopIssue, MdErHop } from './md/hop.js';
export { mdHopReport, deriveTableRoles, renderMdHopMarkdown } from './md/report.js';
export type { MdHopReport, TableRole } from './md/report.js';
