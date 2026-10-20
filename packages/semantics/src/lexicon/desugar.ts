// SPDX-License-Identifier: Apache-2.0
// v4.4 TTR-M lexicon surface (RG-P4, RS-9 T1). Project inline `lexicon{}` sugar
// AND standalone `def term|pattern|example` entries into ONE canonical entry list
// (`CanonicalLexiconEntry`), then merge per target (union of forms). This mirrors
// the binding pattern — inline `binding:` and standalone `def er2db_*` synthesize
// into one symbol namespace tagged by source (see `mapping-synthesizer.ts`'s
// `mappingSource`); the difference is that vocabulary MERGES (many entries per
// target is normal), so the conflict signal is an exact-duplicate surface form,
// not a duplicate definition.

import type { ConceptDef, Definition, Document, LexiconBlock, LexiconEntryDef, SourceLocation } from '@tatrman/parser';
import { conceptProperty, DiagnosticCode } from '@tatrman/parser';
import { modelForKind, namespaceForKind, defaultNamespaceForSchema } from '../default-schema.js';
import { desugarConcepts } from '../cn/desugar.js';
import type { CanonicalLexiconEntry, LexiconAnalysis, LexiconDiagnostic } from './model.js';

const LEXICON_ENTRY_KINDS = new Set(['term', 'pattern', 'example']);

/**
 * The dotted target path an inline `lexicon{}` block resolves to (its carrier). A concept's is its
 * wire name `cn.<Name>` — what a canonical `def term { for: cn.<Name> }` writes (0.17).
 */
function inlineTargetPath(def: Definition & { name: string }): string {
  if (def.kind === 'concept') return `cn.${def.name}`;
  const model = modelForKind(def.kind);
  const ns = namespaceForKind(def.kind) || defaultNamespaceForSchema(model) || def.kind;
  return `${model}.${ns}.${def.name}`;
}

/** Carriers that may hold an inline `lexicon{}` block (grammar 4.4). */
interface InlineCarrier {
  kind: string;
  name: string;
  lexicon?: LexiconBlock;
}

/** A nested vocabulary carrier: an entity/dimension attribute or a table/view column. */
interface NestedCarrier {
  kind: string;
  name: string;
  source: SourceLocation;
  lexicon?: LexiconBlock;
  aliases?: string[];
  search?: LegacyCarrier['search'];
}

/**
 * The nested carriers of a def that may hold inline/legacy vocabulary: entity/
 * dimension `attributes` and table/view `columns`. On md2db binding kinds
 * `attributes` is an attribute MAP (not iterable) — `Array.isArray` guards it.
 */
function nestedCarriers(def: Definition): NestedCarrier[] {
  const holder = def as unknown as { attributes?: unknown; columns?: unknown };
  const out: NestedCarrier[] = [];
  for (const field of [holder.attributes, holder.columns]) {
    if (!Array.isArray(field)) continue;
    for (const child of field) {
      if (child && typeof child === 'object' && typeof (child as { name?: unknown }).name === 'string') {
        out.push(child as NestedCarrier);
      }
    }
  }
  return out;
}

/** Emit inline-sugar and (outside a lexicon unit) legacy vocabulary for one carrier. */
function collectCarrierVocab(
  kind: string,
  name: string,
  target: string,
  carrier: Definition | NestedCarrier,
  source: SourceLocation,
  isLexiconModel: boolean,
  entries: CanonicalLexiconEntry[],
  diagnostics: LexiconDiagnostic[],
): void {
  const c = carrier as unknown as InlineCarrier & LegacyCarrier;
  // 0.17 — a concept def keeps its `lexicon { }` in its ordered property list.
  const lexicon = c.lexicon ?? (carrier.kind === 'concept' ? conceptProperty(carrier as ConceptDef, 'lexicon')?.block : undefined);
  if (lexicon) collectInline(name, target, lexicon, entries);
  if (!isLexiconModel) collectLegacy(kind, name, target, c, source, entries, diagnostics);
}

export function desugarLexicon(doc: Document): LexiconAnalysis {
  const entries: CanonicalLexiconEntry[] = [];
  const diagnostics: LexiconDiagnostic[] = [];

  const modelCode = doc.modelDirective?.modelCode;
  const isLexiconModel = modelCode === 'lexicon';
  const unitLocale = doc.modelDirective?.locale;

  // `locale` is only meaningful on a lexicon unit (RS-11).
  if (unitLocale !== undefined && !isLexiconModel) {
    diagnostics.push({
      code: DiagnosticCode.LexiconLocaleOnNonLexicon,
      message: `'locale ${unitLocale}' is only valid on a 'model lexicon' unit`,
      severity: 'warning',
      source: doc.modelDirective!.source,
    });
  }

  for (const def of doc.definitions) {
    if (LEXICON_ENTRY_KINDS.has(def.kind)) {
      collectCanonical(def as LexiconEntryDef, isLexiconModel, unitLocale, entries, diagnostics);
    } else if (isLexiconModel) {
      // A non-lexicon def in a `model lexicon` file is misplaced (world-validate
      // precedent: model-code and def-kind must agree).
      diagnostics.push({
        code: DiagnosticCode.LexiconWrongModelKind,
        message: `'def ${def.kind} ${def.name}' is not valid in a 'model lexicon' file`,
        severity: 'warning',
        source: def.source,
      });
    }

    // Inline `lexicon{}` sugar AND legacy vocabulary forms (`aliases` +
    // `search { … }`) live on the def itself AND on its nested carriers — entity/
    // dimension attributes, table/view columns (all can hold `lexicon?`/`search?`;
    // entities also `aliases?`). Walk both so vocabulary attached to a coded
    // attribute or column reaches the snapshot instead of being silently dropped.
    if ((def as { name?: unknown }).name !== undefined) {
      const defTarget = inlineTargetPath(def as Definition & { name: string });
      collectCarrierVocab(def.kind, def.name, defTarget, def, def.source, isLexiconModel, entries, diagnostics);
      for (const child of nestedCarriers(def)) {
        collectCarrierVocab(child.kind, child.name, `${defTarget}.${child.name}`, child, child.source, isLexiconModel, entries, diagnostics);
      }
    }

    // `valueLabels` (A4-β) already descends into nested attributes on its own.
    if (!isLexiconModel) {
      collectValueLabels(def, entries);
    }
  }

  // 0.17 — a `concept { }` block's `lexicon { }` names the concept it declares (`cn.<Name>`), not
  // its md / er host (whose own `lexicon { }` stays the host's).
  if (!isLexiconModel) {
    for (const c of desugarConcepts(doc).concepts) {
      const block = conceptProperty(c.body, 'lexicon')?.block;
      if (block) collectInline(c.name, `cn.${c.name}`, block, entries);
    }
  }

  const byTarget = mergeByTarget(entries, diagnostics);
  return { entries, byTarget, diagnostics };
}

/** A data-bearing carrier's legacy vocabulary surface (all optional). */
interface LegacyCarrier {
  kind: string;
  name: string;
  aliases?: string[];
  search?: {
    aliases?: string[];
    keywords?: { entries: Record<string, string[]>; source: SourceLocation };
    patterns?: string[];
    examples?: string[];
    descriptions?: { entries: Record<string, string[]>; source: SourceLocation };
    source: SourceLocation;
  };
}

/** A value-label entry (A4-β): a coded value's localized label + per-value aliases. */
interface ValueLabelLike {
  entries: Array<{ key: string; label: { entries: Record<string, string> }; aliases?: string[]; source: SourceLocation }>;
}
interface ValueLabelCarrier {
  kind: string;
  name: string;
  valueLabels?: ValueLabelLike;
  attributes?: Array<{ kind: string; name: string; valueLabels?: ValueLabelLike; source: SourceLocation }>;
}

/**
 * A4-β (RS-12) — a coded attribute's `valueLabels` are declared MEMBER vocabulary
 * and ride the snapshot beside lexicon terms (origin `valueLabels`). Each value's
 * localized label rides per-locale; per-value `aliases` ride the base locale.
 * Handles top-level `def attribute` and attributes nested in `entity`/`dimension`.
 */
function collectValueLabels(def: Definition, entries: CanonicalLexiconEntry[]): void {
  const carrier = def as unknown as ValueLabelCarrier;
  if (carrier.valueLabels) {
    emitValueLabels(inlineTargetPath(def as Definition & { name: string }), carrier.valueLabels, entries);
  }
  // Nested attributes (entity.attributes / dimension.attributes) — target is the
  // parent's ref plus the attribute name. `attributes` is an AttributeDef[] on
  // entity/dimension; on md2db binding kinds it is an attribute MAP (not iterable
  // here) — guard with Array.isArray.
  if (Array.isArray(carrier.attributes)) {
    const parentTarget = inlineTargetPath(def as Definition & { name: string });
    for (const attr of carrier.attributes) {
      if (attr.valueLabels) emitValueLabels(`${parentTarget}.${attr.name}`, attr.valueLabels, entries);
    }
  }
}

function emitValueLabels(target: string, vl: ValueLabelLike, entries: CanonicalLexiconEntry[]): void {
  for (const entry of vl.entries) {
    for (const [locale, text] of Object.entries(entry.label.entries)) {
      entries.push({ entryKind: 'term', name: entry.key, target, locale, forms: [text], origin: 'valueLabels', source: entry.source });
    }
    if (entry.aliases && entry.aliases.length > 0) {
      entries.push({ entryKind: 'term', name: entry.key, target, locale: undefined, forms: entry.aliases, origin: 'valueLabels', source: entry.source });
    }
  }
}

/**
 * RS-32 — migrate a def's legacy vocabulary forms into canonical entries (origin
 * `legacy`), emitting a named deprecation warning per form. `searchable`/`fuzzy`
 * are retrieval config and are NOT migrated. `search.descriptions` folds into
 * `description` and is handled separately (T3).
 */
function collectLegacy(
  kind: string,
  name: string,
  target: string,
  carrier: LegacyCarrier,
  defSource: SourceLocation,
  entries: CanonicalLexiconEntry[],
  diagnostics: LexiconDiagnostic[],
): void {
  const pushTerm = (forms: string[], locale: string | undefined, source: SourceLocation): void => {
    if (forms.length === 0) return;
    entries.push({ entryKind: 'term', name, target, locale, forms, origin: 'legacy', source });
  };

  // entity `aliases: [...]` → term (base locale).
  if (carrier.aliases && carrier.aliases.length > 0) {
    pushTerm(carrier.aliases, undefined, defSource);
    diagnostics.push({
      code: DiagnosticCode.LexiconLegacyAliases,
      message: `'aliases' on '${kind} ${name}' is deprecated — declare a lexicon 'term' for '${target}' instead`,
      severity: 'warning',
      source: defSource,
    });
  }

  const search = carrier.search;
  if (!search) return;
  const searchSource = search.source;

  // `search { aliases }` → term (base locale).
  if (search.aliases && search.aliases.length > 0) {
    pushTerm(search.aliases, undefined, searchSource);
    diagnostics.push({
      code: DiagnosticCode.LexiconLegacyAliases,
      message: `'search { aliases }' on '${kind} ${name}' is deprecated — declare a lexicon 'term' for '${target}' instead`,
      severity: 'warning',
      source: searchSource,
    });
  }

  // `search { keywords: { <locale>: [...] } }` → one term per locale.
  if (search.keywords && Object.keys(search.keywords.entries).length > 0) {
    for (const [locale, forms] of Object.entries(search.keywords.entries)) {
      pushTerm(forms, locale, search.keywords.source);
    }
    diagnostics.push({
      code: DiagnosticCode.LexiconLegacyKeywords,
      message: `'search { keywords }' on '${kind} ${name}' is deprecated — declare locale-keyed lexicon 'term' entries for '${target}' instead`,
      severity: 'warning',
      source: search.keywords.source,
    });
  }

  // `search { patterns }` → pattern entries (one per regex, base locale).
  if (search.patterns && search.patterns.length > 0) {
    for (const match of search.patterns) {
      entries.push({ entryKind: 'pattern', name, target, locale: undefined, match, origin: 'legacy', source: searchSource });
    }
    diagnostics.push({
      code: DiagnosticCode.LexiconLegacyPatterns,
      message: `'search { patterns }' on '${kind} ${name}' is deprecated — declare lexicon 'pattern' entries for '${target}' instead`,
      severity: 'warning',
      source: searchSource,
    });
  }

  // `search { examples }` → example entries (one per utterance, base locale).
  if (search.examples && search.examples.length > 0) {
    for (const text of search.examples) {
      entries.push({ entryKind: 'example', name, target, locale: undefined, text, origin: 'legacy', source: searchSource });
    }
    diagnostics.push({
      code: DiagnosticCode.LexiconLegacyExamples,
      message: `'search { examples }' on '${kind} ${name}' is deprecated — declare lexicon 'example' entries for '${target}' instead`,
      severity: 'warning',
      source: searchSource,
    });
  }

  // `search { descriptions }` → fold into `description` (RS-32 T3: no consumer
  // reads it distinctly). Prose, not vocabulary — so NO lexicon entry, only the
  // deprecation pointing at the single `description` home.
  if (search.descriptions && Object.keys(search.descriptions.entries).length > 0) {
    diagnostics.push({
      code: DiagnosticCode.LexiconLegacyDescriptions,
      message: `'search { descriptions }' on '${kind} ${name}' is deprecated — use the single 'description' property (no consumer reads it distinctly, RS-32)`,
      severity: 'warning',
      source: search.descriptions.source,
    });
  }
}

function collectCanonical(
  def: LexiconEntryDef,
  isLexiconModel: boolean,
  unitLocale: string | undefined,
  entries: CanonicalLexiconEntry[],
  diagnostics: LexiconDiagnostic[],
): void {
  // Canonical entries live in `model lexicon` (the inline sugar is the in-model
  // carrier elsewhere).
  if (!isLexiconModel) {
    diagnostics.push({
      code: DiagnosticCode.LexiconWrongModelKind,
      message: `'def ${def.kind} ${def.name}' is only valid in a 'model lexicon' file (use inline 'lexicon { … }' sugar on the target otherwise)`,
      severity: 'warning',
      source: def.source,
    });
  }

  if (!def.target) {
    diagnostics.push({
      code: DiagnosticCode.LexiconMissingTarget,
      message: `lexicon ${def.kind} '${def.name}' has no 'for:' target`,
      severity: 'error',
      source: def.source,
    });
  }

  // Per-kind required field (term→forms, pattern→match, example→text).
  const missingField =
    (def.kind === 'term' && (!def.forms || def.forms.length === 0)) ||
    (def.kind === 'pattern' && def.match === undefined) ||
    (def.kind === 'example' && def.text === undefined);
  if (missingField) {
    const field = def.kind === 'term' ? 'forms' : def.kind === 'pattern' ? 'match' : 'text';
    diagnostics.push({
      code: DiagnosticCode.LexiconEntryFieldMissing,
      message: `lexicon ${def.kind} '${def.name}' is missing its '${field}:'`,
      severity: 'warning',
      source: def.source,
    });
  }

  entries.push({
    entryKind: def.kind,
    name: def.name,
    target: def.target?.path ?? '',
    targetRef: def.target,
    locale: isLexiconModel ? unitLocale : undefined,
    forms: def.forms,
    match: def.match,
    text: def.text,
    ...senseOf(def.default, def.sensePriority),
    origin: 'canonical',
    source: def.source,
  });
}

/** The 0.17 sense fields, only those written (cn contracts §1.5). */
function senseOf(isDefault: boolean | undefined, sensePriority: number | undefined): Pick<CanonicalLexiconEntry, 'default' | 'sensePriority'> {
  return {
    ...(isDefault !== undefined ? { default: isDefault } : {}),
    ...(sensePriority !== undefined ? { sensePriority } : {}),
  };
}

/** The sense fields an inline `lexicon { }` block writes beside its shorthands (`default: true`, `sense_priority: 2`). */
function inlineSense(block: LexiconBlock): Pick<CanonicalLexiconEntry, 'default' | 'sensePriority'> {
  const entry = (key: string) => block.object.entries.find((e) => e.key === key)?.value;
  const d = entry('default');
  const p = entry('sense_priority');
  return senseOf(d?.kind === 'bool' ? d.value : undefined, p?.kind === 'number' ? p.value : undefined);
}

function collectInline(name: string, target: string, block: LexiconBlock, entries: CanonicalLexiconEntry[]): void {
  // Inline entries default to the base/deployment locale (RS-11) — locale left
  // undefined; the loader resolves it against the deployment default.
  const sense = inlineSense(block);
  if (block.terms && block.terms.length > 0) {
    entries.push({
      entryKind: 'term',
      name,
      target,
      locale: undefined,
      forms: block.terms,
      ...sense,
      origin: 'inline',
      source: block.source,
    });
  }
  for (const pattern of block.patterns ?? []) {
    entries.push({
      entryKind: 'pattern',
      name,
      target,
      locale: undefined,
      match: pattern,
      origin: 'inline',
      source: block.source,
    });
  }
  for (const example of block.examples ?? []) {
    entries.push({
      entryKind: 'example',
      name,
      target,
      locale: undefined,
      text: example,
      ...sense,
      origin: 'inline',
      source: block.source,
    });
  }
}

/**
 * Group entries by target and union their forms; warn on an exact-duplicate
 * surface form for one target (the merge-conflict signal — the vocabulary
 * analogue of a duplicate binding).
 */
function mergeByTarget(
  entries: CanonicalLexiconEntry[],
  diagnostics: LexiconDiagnostic[],
): Map<string, CanonicalLexiconEntry[]> {
  const byTarget = new Map<string, CanonicalLexiconEntry[]>();
  // Per target+locale, the surface forms already contributed (dup detection).
  const seenForms = new Map<string, Set<string>>();

  for (const entry of entries) {
    if (!entry.target) continue;
    const group = byTarget.get(entry.target) ?? [];
    group.push(entry);
    byTarget.set(entry.target, group);

    if (entry.entryKind !== 'term' || !entry.forms) continue;
    const key = `${entry.target} ${entry.locale ?? ''}`;
    const seen = seenForms.get(key) ?? new Set<string>();
    for (const form of entry.forms) {
      if (seen.has(form)) {
        diagnostics.push({
          code: DiagnosticCode.LexiconDuplicateForm,
          message: `surface form '${form}' is declared more than once for target '${entry.target}'`,
          severity: 'warning',
          source: entry.source,
        });
      } else {
        seen.add(form);
      }
    }
    seenForms.set(key, seen);
  }

  return byTarget;
}
