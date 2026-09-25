// SPDX-License-Identifier: Apache-2.0
// Grounding Phase 1 (grammar 4.2) — validate `semantics { … }` blocks against the
// closed vocabulary (vocabulary.ts, NORMATIVE) and produce the typed
// ResolvedSemantics for diagnostics-free elements.
//
// Pipeline per README §T3.4: shape (keys/values) → cross-ref resolution
// (`period:` entity ref, `currency:` sibling-attribute ref) → type-constraint
// check against the declared `type` → per-owner aggregation (completeness,
// event_date cardinality, geo/valid pairs). ResolvedSemantics is emitted for an
// element ONLY when that element is diagnostics-free (degrade, don't fail).

import { DiagnosticCode } from '@tatrman/parser';
import type {
  Document,
  Definition,
  EntityDef,
  TableDef,
  AttributeDef,
  ColumnDef,
  SemanticsBlock,
  SemanticsValue,
  DataType,
  SourceLocation,
} from '@tatrman/parser';
import type { ProjectSymbolTable } from '../project-symbols.js';
import {
  ATTRIBUTE_ROLES,
  ENTITY_KINDS,
  KIND_COMPLETENESS,
  ALL_ROLES,
  ALL_ENTITY_KEYS,
  AGGREGATIONS,
  DEFAULT_AGGREGATION,
  type EntityKind,
  type AttributeRole,
  type TypeConstraint,
  type Aggregation,
} from './vocabulary.js';
import { nearestMatch } from './suggest.js';
import type {
  ResolvedSemantics,
  ResolvedEntitySemantics,
  ResolvedAttributeSemantics,
  MeasureRef,
  SymbolRef,
} from './model.js';

export interface SemanticsDiagnostic {
  code: DiagnosticCode;
  message: string;
  source: SourceLocation;
  /** Closed-vocabulary nearest match for 200/201/202, when one exists. */
  suggestion?: string;
}

export interface SemanticsAnalysis {
  diagnostics: SemanticsDiagnostic[];
  /** qname-free: resolved results keyed by the owning element's `source` node. */
  resolved: Map<SourceLocation, ResolvedSemantics>;
}

type OwnerDef = EntityDef | TableDef;

/** A member (attribute or column) of an owner, with its parsed semantics role. */
interface Member {
  name: string;
  role?: AttributeRole;
  rawRole?: string;
  type?: DataType;
  block: SemanticsBlock;
  source: SourceLocation;
  /** true once this member's block is proven diagnostics-free. */
  clean: boolean;
}

/** Map a declared TTR type name to a semantics type-constraint family. */
export function typeFamilyOf(dt: DataType | undefined): TypeConstraint | 'other' | undefined {
  if (!dt) return undefined;
  const raw = (dt.kind === 'simple' ? dt.name : dt.typeName).toLowerCase();
  if (['date', 'datetime', 'timestamp'].includes(raw)) return 'date';
  if (['text', 'varchar', 'char', 'string'].includes(raw)) return 'text';
  if (['decimal', 'number', 'numeric', 'int', 'integer', 'float', 'double', 'bigint', 'smallint'].includes(raw)) {
    return 'numeric';
  }
  return 'other';
}

const lastSeg = (path: string): string => path.split('.').pop() ?? path;

/** The entity/table key roster, for the misplaced-keyword message. */
const entityKeyList = (): string => ALL_ENTITY_KEYS.map((k) => `'${k}'`).join(', ');

/**
 * LP review-103 (D4) — Java regex constructs JavaScript's engine rejects: inline flags
 * (`(?i)`, `(?i:…)`), atomic groups (`(?>…)`) and possessive quantifiers (`a*+`). A
 * `code_pattern:` that fails in JS but uses one of these is left to the JVM analyzer.
 */
const JAVA_ONLY_REGEX = /\(\?[a-zA-Z-]+[):]|\(\?>|[*+?}]\+/;

/** Why `pattern` does not compile, or undefined when it does (or is Java-only; see above). */
function regexError(pattern: string): string | undefined {
  try {
    new RegExp(pattern);
    return undefined;
  } catch (e) {
    if (JAVA_ONLY_REGEX.test(pattern)) return undefined;
    return e instanceof Error ? e.message : String(e);
  }
}

/** How to name a wrong-shaped value in a diagnostic, without dumping its contents. */
function describeValue(v: SemanticsValue): string {
  if (Array.isArray(v)) return 'a list';
  if (v !== null && typeof v === 'object') return 'an object';
  if (v === null) return 'null';
  return `'${String(v)}'`;
}

/**
 * Analyse every `semantics` block in `ast`. `symbols` (optional) enables
 * cross-document `period:` resolution; same-document targets resolve without it.
 */
export function analyzeSemantics(ast: Document, symbols?: ProjectSymbolTable): SemanticsAnalysis {
  const diagnostics: SemanticsDiagnostic[] = [];
  const resolved = new Map<SourceLocation, ResolvedSemantics>();
  const emit = (code: DiagnosticCode, source: SourceLocation, message: string, suggestion?: string): void => {
    diagnostics.push({ code, source, message, suggestion });
  };

  /**
   * ⛑ The scalar gate, for every key whose value the vocabulary reads as a single value.
   *
   * Until MS-P0·S1b the parser rejected a list or a nested object itself, so nothing here
   * ever saw one. It now carries them verbatim — deliberately, since which keys may hold
   * structure is vocabulary knowledge and the parser holds no vocabulary — which makes
   * this layer responsible for the judgement the parser gave up. Without the gate every
   * vocabulary lookup stringifies the structure and then reports a PERFECTLY VALID member
   * as unknown: `kind: [period_table]` produced "unknown entity/table kind 'period_table'",
   * which sends the author to check a spelling that was never wrong.
   *
   * Returns true when the value is a single one; emits and returns false otherwise.
   * `null` is a scalar here (`SemanticsValue` admits it, and `typeof null === 'object'`).
   */
  const scalarOnly = (key: string, value: SemanticsValue, source: SourceLocation): boolean => {
    if (value === null || typeof value !== 'object') return true;
    emit(DiagnosticCode.SemMentionShape, source, `'${key}:' takes a single value, not ${describeValue(value)}`);
    return false;
  };

  // Document-local index of declared entity/table kinds (raw), for `period:`.
  const localKinds = new Map<string, string>();
  for (const def of ast.definitions) {
    if ((def.kind === 'entity' || def.kind === 'table') && def.semantics) {
      const k = def.semantics.entries.kind;
      if (typeof k === 'string') localKinds.set(def.name, k);
    }
  }

  for (const def of ast.definitions) {
    if (def.kind === 'entity' || def.kind === 'table') {
      validateOwner(def, def.kind === 'entity' ? def.attributes ?? [] : def.columns ?? []);
    } else if (def.kind === 'attribute') {
      // Standalone attribute: shape/type/period checks only (no owner aggregation,
      // no sibling for `currency:`).
      if (def.semantics) validateStandaloneAttribute(def);
    }
  }

  function validateOwner(owner: OwnerDef, rawMembers: ReadonlyArray<AttributeDef | ColumnDef>): void {
    // --- entity/table-level block ---
    let ownerKind: EntityKind | undefined;
    let ownerClean = true;
    if (owner.semantics) {
      const r = validateEntityBlock(owner.semantics, rawMembers);
      ownerKind = r.kind;
      // ⛑ Evaluated BEFORE the `&&`, not inside it. `r.clean && legacyMentionOk(...)`
      // short-circuits, which silently switched the whole contracts §1.2 matrix off for
      // any block carrying an unrelated error — including row 4, the MS-D2 "a
      // disagreement is always a bug" ERROR. The legacy properties are a surface of
      // their own; whether the semantics block validated says nothing about them.
      const legacyOk = legacyMentionOk(owner, r);
      ownerClean = r.clean && legacyOk;
      // Resolve when the block declared SOMETHING. An empty `semantics { }` carries no
      // facts, and a block that only errored is degraded by the `clean` gate above.
      if (ownerClean && (r.kind || r.name || r.code || r.measures.length > 0)) {
        resolved.set(owner.semantics.source, {
          kind: r.kind,
          name: r.name,
          code: r.code,
          measures: r.measures,
          ...(r.codePattern !== undefined ? { codePattern: r.codePattern } : {}),
        } satisfies ResolvedEntitySemantics);
      }
    } else {
      legacyMentionOk(owner, undefined);
    }

    // --- member blocks ---
    const members: Member[] = [];
    for (const m of rawMembers) {
      if (!m.semantics) continue;
      const parsed = validateAttributeBlock(m.semantics, m.type, owner, rawMembers);
      members.push({
        name: m.name,
        role: parsed.role,
        rawRole: parsed.rawRole,
        type: m.type,
        block: m.semantics,
        source: m.semantics.source,
        clean: parsed.clean,
      });
      if (parsed.clean && parsed.resolved) resolved.set(m.semantics.source, parsed.resolved);
    }

    // --- per-owner aggregation (runs regardless of kind for 207/210/211) ---
    aggregate(owner, ownerKind, ownerClean, members);
  }

  function validateStandaloneAttribute(attr: AttributeDef): void {
    const parsed = validateAttributeBlock(attr.semantics!, attr.type, undefined, []);
    if (parsed.clean && parsed.resolved) resolved.set(attr.semantics!.source, parsed.resolved);
  }

  // ---- entity/table block shape ----
  /** What an entity/table block declared, once shape-checked and owner-resolved. */
  interface EntityBlockResult {
    kind?: EntityKind;
    name?: SymbolRef;
    code?: SymbolRef;
    codePattern?: string;
    measures: MeasureRef[];
    clean: boolean;
  }

  function validateEntityBlock(
    block: SemanticsBlock,
    rawMembers: ReadonlyArray<AttributeDef | ColumnDef>,
  ): EntityBlockResult {
    let clean = true;
    for (const dup of block.duplicateProperties ?? []) {
      emit(DiagnosticCode.SemDuplicateKey, block.source, `duplicate semantics key '${dup}'`);
      clean = false;
    }
    let kind: EntityKind | undefined;
    let name: SymbolRef | undefined;
    let code: SymbolRef | undefined;
    let codePattern: string | undefined;
    let measures: MeasureRef[] = [];
    for (const [key, value] of Object.entries(block.entries)) {
      if (key === 'kind') {
        if (!scalarOnly(key, value, block.source)) {
          clean = false;
        } else if (typeof value === 'string' && (ENTITY_KINDS as ReadonlyArray<string>).includes(value)) {
          kind = value as EntityKind;
        } else {
          const s = typeof value === 'string' ? nearestMatch(value, ENTITY_KINDS) : undefined;
          emit(DiagnosticCode.SemUnknownKind, block.source, `unknown entity/table kind '${String(value)}'${didYouMean(s)}`, s);
          clean = false;
        }
      } else if (key === 'name' || key === 'code') {
        // ⛑ Must be matched BEFORE the misplaced-keyword branch below, which tests the
        // VALUE against the role roster: `name: amount` on an entity whose attribute is
        // called `amount` would otherwise be reported as an attribute key on an entity
        // block. Attributes named like roles are ordinary, not a mistake.
        const ref = mentionRef(key, value, rawMembers, block.source);
        if (!ref) clean = false;
        else if (key === 'name') name = ref;
        else code = ref;
      } else if (key === 'code_pattern') {
        // Matched before the misplaced-keyword branch for the same reason `name`/`code`
        // are: that branch tests the VALUE against the role roster.
        codePattern = codePatternOf(value, block.source);
        if (codePattern === undefined) clean = false;
      } else if (key === 'measures') {
        const parsed = parseMeasures(value, rawMembers, block.source);
        measures = parsed.measures;
        if (!parsed.clean) clean = false;
        // `typeof value === 'string'` guards the roster test: it reads the VALUE, and
        // `String(['event_date'])` is `'event_date'`, so a structured value would be
        // misreported as a misplaced attribute key instead of a wrong shape.
      } else if (key === 'role' || (typeof value === 'string' && ALL_ROLES.includes(value)) || isAttributeOnlyKey(key)) {
        // `role:` (and role-only extras) belong on an attribute/column, not here.
        emit(DiagnosticCode.SemMisplacedKeyword, block.source, `'${key}' is an attribute/column key; entity/table blocks carry ${entityKeyList()}`);
        clean = false;
      } else {
        const s = nearestMatch(key, ALL_ENTITY_KEYS);
        emit(DiagnosticCode.SemUnknownKey, block.source, `unknown semantics key '${key}'${didYouMean(s)}`, s);
        clean = false;
      }
    }
    // A pattern describes the CODE attribute's values, so a block that names no code has
    // nothing for it to describe. Keyed on the `code` KEY, not on the resolved ref: a
    // `code:` that failed to resolve has already been reported, and a second error blaming
    // the pattern for it would send the author to the wrong line of the block.
    if ('code_pattern' in block.entries && !('code' in block.entries)) {
      emit(DiagnosticCode.SemBadCodePattern, block.source, `'code_pattern:' needs 'code:' in the same block — it describes the values of the code attribute, and this block names none`);
      clean = false;
      codePattern = undefined;
    }
    return { kind, name, code, codePattern, measures, clean };
  }

  /**
   * LP review-103 (D4) — a `code_pattern:` value: a quoted regex the code attribute's values
   * match. Undefined (after emitting) when it is not a single string, is blank, or does not
   * compile.
   *
   * ⚠ The dialect is JAVA's: the lexicon compiler copies the pattern into the archive and
   * the resolver matches literals against it on the JVM, and the Kotlin analyzer compiles
   * it with `java.util.regex.Pattern` — that twin is the authority. JavaScript's engine can
   * only stand in for it, so a pattern JS rejects is reported only when it uses nothing
   * Java-specific (`JAVA_ONLY_REGEX`); a Java-only construct is left for the JVM to judge
   * rather than flagged here as an error it is not.
   */
  function codePatternOf(value: SemanticsValue, source: SourceLocation): string | undefined {
    if (typeof value !== 'string') {
      emit(DiagnosticCode.SemMentionShape, source, `'code_pattern:' takes a regular expression in quotes, not ${describeValue(value)}`);
      return undefined;
    }
    if (value.trim() === '') {
      emit(DiagnosticCode.SemBadCodePattern, source, `'code_pattern:' is empty — an empty pattern matches no code anyone can quote`);
      return undefined;
    }
    const error = regexError(value);
    if (error !== undefined) {
      emit(DiagnosticCode.SemBadCodePattern, source, `'code_pattern: "${value}"' is not a valid regular expression: ${error}`);
      return undefined;
    }
    return value;
  }

  /**
   * A `name:` / `code:` value: a bare id naming an attribute of THIS owner.
   *
   * Owner-scoped on purpose. The mention facet says "which of MY attributes carries me
   * under this aspect", so a name that resolves somewhere else in the document is exactly
   * as wrong as one that resolves nowhere.
   */
  function mentionRef(
    key: string,
    value: SemanticsValue,
    rawMembers: ReadonlyArray<AttributeDef | ColumnDef>,
    source: SourceLocation,
  ): SymbolRef | undefined {
    if (typeof value !== 'string') {
      emit(DiagnosticCode.SemMentionShape, source, `'${key}:' takes an attribute/column id, not ${describeValue(value)}`);
      return undefined;
    }
    if (!rawMembers.some((m) => m.name === value)) {
      const s = nearestMatch(value, rawMembers.map((m) => m.name));
      emit(DiagnosticCode.SemMentionRefUnresolved, source, `'${key}: ${value}' does not name an attribute/column of this entity/table${didYouMean(s)}`, s);
      return undefined;
    }
    return { path: value };
  }

  /** The `measures:` list — shape, owner resolution, numeric type, and duplicates. */
  function parseMeasures(
    value: SemanticsValue,
    rawMembers: ReadonlyArray<AttributeDef | ColumnDef>,
    source: SourceLocation,
  ): { measures: MeasureRef[]; clean: boolean } {
    if (!Array.isArray(value)) {
      emit(DiagnosticCode.SemMentionShape, source, `'measures:' takes a list, not ${describeValue(value)}`);
      return { measures: [], clean: false };
    }
    const measures: MeasureRef[] = [];
    const seen = new Set<string>();
    let clean = true;
    for (const item of value) {
      const parsed = measureItem(item, source);
      if (!parsed) {
        clean = false;
        continue;
      }
      const { attribute, aggregation } = parsed;
      const member = rawMembers.find((m) => m.name === attribute);
      if (!member) {
        const s = nearestMatch(attribute, rawMembers.map((m) => m.name));
        emit(DiagnosticCode.SemMentionRefUnresolved, source, `measure '${attribute}' does not name an attribute/column of this entity/table${didYouMean(s)}`, s);
        clean = false;
        continue;
      }
      const fam = typeFamilyOf(member.type);
      if (fam && fam !== 'numeric') {
        emit(DiagnosticCode.SemMeasureNotNumeric, source, `measure '${attribute}' has type '${typeName(member.type)}', which is not numeric`);
        clean = false;
        continue;
      }
      if (seen.has(attribute)) {
        emit(DiagnosticCode.SemMeasureDuplicate, source, `measure '${attribute}' is listed more than once`);
        clean = false;
        continue;
      }
      seen.add(attribute);
      measures.push({ attribute: { path: attribute }, aggregation });
    }
    return { measures, clean };
  }

  /**
   * One `measures:` item: a bare id, or `{ attribute: <id>, aggregation?: <id> }`.
   *
   * ⚠ `aggregation` HERE is the aggregation of a measure. It is not the def-level
   * `aggregation:` attribute property (EN-P1.2: "this attribute is derived by an
   * aggregation") and not md's measure property. Nothing reads across those surfaces —
   * a bare id gets DEFAULT_AGGREGATION regardless of what the attribute def says.
   */
  function measureItem(
    item: SemanticsValue,
    source: SourceLocation,
  ): { attribute: string; aggregation: Aggregation } | undefined {
    if (typeof item === 'string') return { attribute: item, aggregation: DEFAULT_AGGREGATION };
    if (item === null || typeof item !== 'object' || Array.isArray(item)) {
      emit(DiagnosticCode.SemMentionShape, source, `a measures item is an id or '{ attribute: …, aggregation: … }', not ${describeValue(item)}`);
      return undefined;
    }
    const entries = item as { readonly [k: string]: SemanticsValue };
    let bad = false;
    for (const k of Object.keys(entries)) {
      if (k === 'attribute' || k === 'aggregation') continue;
      const s = nearestMatch(k, ['attribute', 'aggregation']);
      emit(DiagnosticCode.SemMentionShape, source, `unknown key '${k}' in a measures item${didYouMean(s)}`, s);
      bad = true;
    }
    const attribute = entries.attribute;
    if (typeof attribute !== 'string') {
      emit(DiagnosticCode.SemMentionShape, source, `a measures item needs 'attribute:' as an id`);
      bad = true;
    }
    let aggregation: Aggregation = DEFAULT_AGGREGATION;
    const raw = entries.aggregation;
    if (raw !== undefined) {
      if (typeof raw === 'string' && (AGGREGATIONS as ReadonlyArray<string>).includes(raw)) {
        aggregation = raw as Aggregation;
      } else if (!scalarOnly('aggregation', raw, source)) {
        // Shape before vocabulary, as everywhere else: `aggregation: [avg]` is a wrong
        // shape, not an unknown aggregation — and `String(['avg'])` is `'avg'`, so the
        // vocabulary message would have named a valid member as unknown.
        bad = true;
      } else {
        const s = typeof raw === 'string' ? nearestMatch(raw, AGGREGATIONS) : undefined;
        emit(DiagnosticCode.SemBadAggregation, source, `unknown aggregation '${String(raw)}'${didYouMean(s)}`, s);
        bad = true;
      }
    }
    if (bad || typeof attribute !== 'string') return undefined;
    return { attribute, aggregation };
  }

  /**
   * contracts §1.2 / MS-D2 — the legacy `nameAttribute:` / `codeAttribute:` matrix.
   *
   * Returns false only for the disagreement case, which is an ERROR and degrades the
   * block: "a disagreement is always a bug", so the validator refuses to pick a winner
   * rather than silently preferring one source over the other.
   */
  function legacyMentionOk(owner: OwnerDef, block: EntityBlockResult | undefined): boolean {
    if (owner.kind !== 'entity') return true;
    let ok = true;
    for (const [prop, declared] of [
      ['nameAttribute', block?.name],
      ['codeAttribute', block?.code],
    ] as const) {
      const legacy = owner[prop];
      if (!legacy) continue;
      const key = prop === 'nameAttribute' ? 'name' : 'code';
      if (!declared) {
        // Review-103 F16 — say what the property is still FOR: the mention facet a quoted
        // literal is attributed through reads the semantics block, and this only as a
        // fallback. "Superseded" alone read as "harmless", and the facet is not.
        emit(
          DiagnosticCode.SemLegacyMentionDeprecated,
          legacy.source,
          `'${prop}:' is superseded by 'semantics { ${key}: ${lastSeg(legacy.path)} }' — quoting a value ("…") filters the column the semantics block names, and reads '${prop}:' only as a fallback`,
        );
        continue;
      }
      if (namesTheSameAttribute(legacy.path, declared.path, owner.name)) {
        emit(DiagnosticCode.SemLegacyMentionDeprecated, legacy.source, `'${prop}:' repeats 'semantics { ${key}: ${declared.path} }' — drop the legacy property`);
        continue;
      }
      emit(DiagnosticCode.SemLegacyMentionMismatch, legacy.source, `'${prop}: ${legacy.path}' disagrees with 'semantics { ${key}: ${declared.path} }'`);
      ok = false;
    }
    return ok;
  }

  /**
   * Does a legacy `nameAttribute:`/`codeAttribute:` path name the same attribute as the
   * semantics block's bare `name:`/`code:` id?
   *
   * The semantics side is always a bare local id — `mentionRef` only accepts a member of
   * this owner. The legacy side is a `Reference` and may be written qualified, so the
   * last segment has to be compared rather than the whole path. ⛑ But only when the
   * qualifier is the owner ITSELF: `nameAttribute: Other.customer_name` names a different
   * entity's attribute, and comparing last segments alone reported that as agreement and
   * then advised deleting the legacy property — destructive advice built on a comparison
   * that could not see the difference.
   */
  function namesTheSameAttribute(legacyPath: string, declaredName: string, ownerName: string): boolean {
    if (lastSeg(legacyPath) !== declaredName) return false;
    const qualifier = legacyPath.slice(0, Math.max(0, legacyPath.length - declaredName.length - 1));
    return qualifier === '' || lastSeg(qualifier) === ownerName;
  }

  // ---- attribute/column block shape + cross-refs + type ----
  function validateAttributeBlock(
    block: SemanticsBlock,
    memberType: DataType | undefined,
    owner: OwnerDef | undefined,
    siblings: ReadonlyArray<AttributeDef | ColumnDef>,
  ): { role?: AttributeRole; rawRole?: string; clean: boolean; resolved?: ResolvedAttributeSemantics } {
    let clean = true;
    for (const dup of block.duplicateProperties ?? []) {
      emit(DiagnosticCode.SemDuplicateKey, block.source, `duplicate semantics key '${dup}'`);
      clean = false;
    }

    // `kind` on an attribute/column is misplaced.
    if ('kind' in block.entries) {
      emit(DiagnosticCode.SemMisplacedKeyword, block.source, `'kind' is an entity/table key; attribute/column blocks carry 'role'`);
      clean = false;
    }

    const rawRole = block.entries.role;
    let role: AttributeRole | undefined;
    if (typeof rawRole === 'string' && rawRole in ATTRIBUTE_ROLES) {
      role = rawRole as AttributeRole;
    } else if (rawRole !== undefined) {
      // Shape before vocabulary — `role: [event_date]` is a wrong shape, not an unknown role.
      if (!scalarOnly('role', rawRole, block.source)) {
        clean = false;
      } else {
        const s = typeof rawRole === 'string' ? nearestMatch(rawRole, ALL_ROLES) : undefined;
        emit(DiagnosticCode.SemUnknownRole, block.source, `unknown semantics role '${String(rawRole)}'${didYouMean(s)}`, s);
        clean = false;
      }
    }

    // Allowed keys for this role.
    const spec = role ? ATTRIBUTE_ROLES[role] : undefined;
    const allowed = new Set<string>(['role', ...(spec ? spec.extraKeys.map((k) => k.key) : [])]);
    for (const key of Object.keys(block.entries)) {
      if (key === 'kind') continue; // already handled (204)
      if (allowed.has(key)) continue;
      if (role) {
        const s = nearestMatch(key, [...allowed]);
        emit(DiagnosticCode.SemUnknownKey, block.source, `key '${key}' is not valid for role '${role}'${didYouMean(s)}`, s);
        clean = false;
      }
      // when role is unknown we already reported 201; don't pile on per-key noise
    }

    // Type constraint.
    if (role && spec?.typeConstraint) {
      const fam = typeFamilyOf(memberType);
      if (fam && fam !== spec.typeConstraint) {
        emit(
          DiagnosticCode.SemTypeConstraint,
          block.source,
          `role '${role}' requires a ${spec.typeConstraint} type, but the declared type is '${typeName(memberType)}'`,
        );
        clean = false;
      }
    }

    // Cross-refs.
    const refs: ResolvedAttributeSemantics['refs'] = {};
    if (role) {
      // period: → entity ref of kind period_table (event/document/posting/due dates).
      const periodVal = block.entries.period;
      if (periodVal !== undefined && spec?.extraKeys.some((k) => k.key === 'period')) {
        if (!scalarOnly('period', periodVal, block.source)) {
          clean = false;
        } else {
          const ref = resolvePeriodRef(String(periodVal), block.source);
          if (ref.ok) refs.period = { path: String(periodVal), qname: ref.qname };
          else clean = false;
        }
      }
      // currency: → sibling attribute of role currency_code (on `amount`).
      const currencyVal = block.entries.currency;
      if (currencyVal !== undefined && spec?.extraKeys.some((k) => k.key === 'currency')) {
        if (!scalarOnly('currency', currencyVal, block.source)) {
          clean = false;
        } else {
          const ref = resolveCurrencyRef(String(currencyVal), siblings, block.source);
          if (ref.ok) refs.currency = { path: String(currencyVal) };
          else clean = false;
        }
      }
      // code_format: → the format string on `period_code`. Gated here rather than at the
      // `params` build below, which is only reached once `clean` holds: a structured
      // value there would have fallen through the `typeof cf === 'string'` test and
      // silently become the 'yyyyMM' default.
      const codeFormatVal = block.entries.code_format;
      if (codeFormatVal !== undefined && spec?.extraKeys.some((k) => k.key === 'code_format')) {
        if (!scalarOnly('code_format', codeFormatVal, block.source)) clean = false;
      }
    }

    if (!role || !clean) return { role, rawRole: typeof rawRole === 'string' ? rawRole : undefined, clean };
    const params: ResolvedAttributeSemantics['params'] = {};
    if (role === 'period_code') {
      const cf = block.entries.code_format;
      params.codeFormat = typeof cf === 'string' ? cf : 'yyyyMM';
    }
    return { role, rawRole: role, clean: true, resolved: { role, refs, params } };
  }

  // period: resolution — same-doc kind index first, then project symbols.
  function resolvePeriodRef(path: string, source: SourceLocation): { ok: boolean; qname?: string } {
    const name = lastSeg(path);
    // same document
    const localKind = localKinds.get(name);
    if (localKind !== undefined) {
      if (localKind === 'period_table') return { ok: true };
      emit(DiagnosticCode.SemBadPeriodRef, source, `period: '${path}' refers to '${name}', which is not a 'period_table' kind`);
      return { ok: false };
    }
    // cross document via the project symbol table
    if (symbols) {
      const cands = symbols.findByName(name).filter((s) => s.kind === 'entity' || s.kind === 'table');
      if (cands.length > 0) {
        if (cands.some((s) => s.semanticsKind === 'period_table')) return { ok: true, qname: cands[0].qname };
        emit(DiagnosticCode.SemBadPeriodRef, source, `period: '${path}' refers to an entity/table that is not a 'period_table' kind`);
        return { ok: false };
      }
    }
    emit(DiagnosticCode.SemBadPeriodRef, source, `period: '${path}' does not resolve to any entity/table`);
    return { ok: false };
  }

  // currency: resolution — a sibling member with role currency_code.
  function resolveCurrencyRef(
    path: string,
    siblings: ReadonlyArray<AttributeDef | ColumnDef>,
    source: SourceLocation,
  ): { ok: boolean } {
    const name = lastSeg(path);
    const sib = siblings.find((m) => m.name === name);
    if (!sib) {
      emit(DiagnosticCode.SemBadCurrencyRef, source, `currency: '${path}' does not resolve to a sibling attribute/column`);
      return { ok: false };
    }
    if (sib.semantics?.entries.role !== 'currency_code') {
      emit(DiagnosticCode.SemBadCurrencyRef, source, `currency: '${path}' must reference a sibling with role 'currency_code'`);
      return { ok: false };
    }
    return { ok: true };
  }

  // ---- per-owner aggregation ----
  function aggregate(owner: OwnerDef, ownerKind: EntityKind | undefined, ownerClean: boolean, members: Member[]): void {
    const roleCount = (r: AttributeRole): number => members.filter((m) => m.role === r).length;

    // 207 — at most one event_date per owner.
    if (roleCount('event_date') > 1) {
      emit(DiagnosticCode.SemMultipleEventDate, owner.source, `entity/table '${owner.name}' has more than one 'event_date' — exactly one is the default query date`);
    }

    // 210 — geo_lat/geo_lon pair required together.
    const hasLat = roleCount('geo_lat') > 0;
    const hasLon = roleCount('geo_lon') > 0;
    if (hasLat !== hasLon) {
      emit(DiagnosticCode.SemGeoPair, owner.source, `'${owner.name}' has ${hasLat ? 'geo_lat without geo_lon' : 'geo_lon without geo_lat'} — the pair is required together`);
    }

    // 211 — valid_from/valid_to both-or-neither.
    const hasFrom = roleCount('valid_from') > 0;
    const hasTo = roleCount('valid_to') > 0;
    if (hasFrom !== hasTo) {
      emit(DiagnosticCode.SemValidPair, owner.source, `'${owner.name}' has ${hasFrom ? 'valid_from without valid_to' : 'valid_to without valid_from'} — the validity pair is both-or-neither`);
    }

    if (!ownerKind || !ownerClean) return;

    // Kind completeness.
    if (ownerKind === 'poi') {
      const point = roleCount('geo_point');
      const pair = hasLat && hasLon ? 1 : 0;
      if (!((point === 1 && pair === 0) || (point === 0 && pair === 1))) {
        emit(DiagnosticCode.SemGeoPair, owner.source, `poi '${owner.name}' must have exactly one 'geo_point' XOR one 'geo_lat' + one 'geo_lon'`);
      }
    } else {
      for (const clause of KIND_COMPLETENESS[ownerKind]) {
        const n = roleCount(clause.role);
        if (n !== clause.count) {
          emit(DiagnosticCode.SemCompleteness, owner.source, `${ownerKind} '${owner.name}' requires exactly ${clause.count} '${clause.role}' (found ${n})`);
        }
      }
    }
  }

  return { diagnostics, resolved };
}

function isAttributeOnlyKey(key: string): boolean {
  for (const spec of Object.values(ATTRIBUTE_ROLES)) {
    if (spec.extraKeys.some((k) => k.key === key)) return true;
  }
  return key === 'code_format' || key === 'period' || key === 'currency';
}

function typeName(dt: DataType | undefined): string {
  if (!dt) return '<none>';
  return dt.kind === 'simple' ? dt.name : dt.typeName;
}

function didYouMean(s: string | undefined): string {
  return s ? `; did you mean '${s}'?` : '';
}

// Re-exports so consumers import the whole surface from the validator module.
export type { SemanticsValue, Definition };
