// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode } from '@tatrman/parser';
import type { Document, SourceLocation, AttributeDef, LocalizedString } from '@tatrman/parser';
import { cnWireName, desugarLexicon, foldForCollision, MODEL_CODES } from '@tatrman/semantics';
import type { LexiconAnalysis } from '@tatrman/semantics';
import type { Rule, DocumentRuleContext, ProjectRuleContext } from '../rule.js';

// v4.4 TTR-M lexicon surface (RG-P4) — surface the ttr-semantics lexicon desugar
// diagnostics (placement / missing target / missing field / duplicate form /
// misplaced locale) as lint rules. One code per rule id (per-rule severity, the
// semantics-rules pattern); the desugar analysis runs once per document and is
// memoised. Referential integrity (`for:` target resolution) rides the generic
// reference resolver — it is NOT re-checked here.

const cache = new WeakMap<Document, LexiconAnalysis>();

function analysisFor(ctx: DocumentRuleContext): LexiconAnalysis {
  const memo = cache.get(ctx.ast);
  if (memo) return memo;
  const analysis = desugarLexicon(ctx.ast);
  cache.set(ctx.ast, analysis);
  return analysis;
}

function lexRule(id: string, code: DiagnosticCode, defaultSeverity: 'error' | 'warning', docs: string): Rule {
  return {
    id,
    code,
    category: 'lexicon',
    scope: 'document',
    defaultSeverity,
    docs,
    check(ctx) {
      if (ctx.scope !== 'document') return;
      for (const d of analysisFor(ctx).diagnostics) {
        if (d.code !== code) continue;
        ctx.report({ source: d.source, message: d.message });
      }
    },
  };
}

// ---------------------------------------------------------------------------
// MH T1 (mention homonymy) — `lexicon-form-collides-with-name`.
//
// One word claimed by two refs is the runtime problem MH exists for: on hartland
// `prodejna` is `er.entity.store`'s `displayLabel.cs` AND a form of the Stores-channel
// term pinned to `er.entity.store_sales`, so the resolver sees two refs on one anchor
// and asks (a G2) instead of binding. This rule reports the collision at authoring
// time, keyed by the fold the resolver's anchor INDEX uses (`foldForCollision`,
// contracts §1) — so it fires exactly when the two refs would actually meet.
//
// Project-scoped: the term and the name it collides with live in different files.
// See `project/server/features/mention-homonymy/contracts.md` §2.
//
// DEVIATION from contracts §2.3, deliberate: the diagnostic is reported on the TERM
// def's span, not on the colliding string literal, because `LexiconEntryDef.forms`
// is a bare `string[]` with no per-form spans. That is also what makes contracts
// §2.4 work: a comment written inside the term body (above `forms:`) is attached as
// the term def's trailing trivia, so `ttr-disable-next-line` targets the DEF's line.
// Reporting on the form's line would leave that directive suppressing nothing.
// ---------------------------------------------------------------------------

/** Where an anchor came from. The order is the report order for one form (§2.3). */
const ANCHOR_KIND_ORDER = ['name', 'displayLabel', 'labelPlural', 'alias', 'attribute.displayLabel', 'declared form'];

function anchorRank(anchorKind: string): number {
  const head = anchorKind.startsWith('displayLabel.')
    ? 'displayLabel'
    : anchorKind.startsWith('attribute.displayLabel.')
      ? 'attribute.displayLabel'
      : anchorKind;
  const i = ANCHOR_KIND_ORDER.indexOf(head);
  return i < 0 ? ANCHOR_KIND_ORDER.length : i;
}

interface AnchorRow {
  /** `foldForCollision(anchor)`. */
  fold: string;
  /** The ref that claims the word, e.g. `er.entity.store`. */
  ref: string;
  anchorKind: string;
  anchor: string;
}

interface FormRow {
  fold: string;
  termName: string;
  /** The term's `for:` target, canonicalised to the archive's dotted spelling. */
  target: string;
  form: string;
  source: SourceLocation;
}

interface AnchorIndex {
  anchors: AnchorRow[];
  forms: FormRow[];
}

const ANCHOR_INDEX_KEY = 'mh:anchor-index';

/**
 * Strip the package prefix off a canonical key so the ref reads the way the
 * lexicon archive (and a `for:` target) spells it: `hartland.er.entity.store`
 * becomes `er.entity.store`. The first segment that is a model code starts the path.
 * A concept reads as its wire name, `cn.<Name>` (0.17).
 */
function modelPathOf(qname: string): string {
  const wire = cnWireName(qname);
  if (wire) return wire;
  const parts = qname.split('.');
  const i = parts.findIndex((p) => (MODEL_CODES as ReadonlySet<string>).has(p));
  return i <= 0 ? qname : parts.slice(i).join('.');
}

function pushLocalized(out: AnchorRow[], ref: string, kind: string, label: LocalizedString | undefined): void {
  if (!label) return;
  for (const [locale, value] of Object.entries(label.entries)) {
    if (value.trim().length === 0) continue;
    out.push({ fold: foldForCollision(value), ref, anchorKind: `${kind}.${locale}`, anchor: value });
  }
}

/**
 * `AttributeDef` carries `displayLabel`; `ColumnDef` does not (a db column has no
 * label in the grammar), so a table's columns contribute no anchors — the shared
 * shape keeps one walk for both.
 */
type LabelledMember = Pick<AttributeDef, 'name'> & Partial<Pick<AttributeDef, 'displayLabel'>>;

function memberAnchors(out: AnchorRow[], ownerRef: string, members: LabelledMember[]): void {
  for (const m of members) {
    // Member VALUES (`valueLabels`) are deliberately NOT anchors: they are `M:`
    // identities at runtime, which the Binder already separates from `V:` refs.
    pushLocalized(out, `${ownerRef}.${m.name}`, 'attribute.displayLabel', m.displayLabel);
  }
}

/** Resolve a term's `for:` path to the dotted ref the archive uses; the written form is the fallback. */
function canonicalTarget(ctx: ProjectRuleContext, uri: string, path: string, packageName?: string): string {
  const doc = ctx.documents.get(uri);
  const res = ctx.resolver.resolveReference(
    { path, parts: path.split('.') },
    {
      schemaCode: doc?.modelDirective?.modelCode ?? '',
      namespace: doc?.modelDirective?.schema ?? '',
      packageName,
      imports: doc?.imports,
    }
  );
  return res.resolved ? modelPathOf(res.symbol.qname) : path;
}

/**
 * Build the project-wide (anchor, declared-form) index once per `lintProject`
 * pass. Mirrors the compiler's `MetadataExtractor` (`Extractors.kt`) minus
 * `valueLabels`, plus the object's own local name — which the METADATA layer has
 * no row for, so the lint is deliberately a superset there (contracts §2.2).
 */
function buildAnchorIndex(ctx: ProjectRuleContext): AnchorIndex {
  const cached = ctx.cache.get(ANCHOR_INDEX_KEY) as AnchorIndex | undefined;
  if (cached) return cached;

  const anchors: AnchorRow[] = [];
  const forms: FormRow[] = [];

  // documentUri + kind + parent + name -> the qname the symbol table gave that def,
  // so a def is named the way the archive names it without re-deriving the
  // package/schema slots here.
  const qnameOf = new Map<string, string>();
  const packageOf = new Map<string, string>();
  for (const e of ctx.symbols.all()) {
    qnameOf.set(`${e.documentUri} ${e.kind} ${e.parent ?? ''} ${e.name}`, e.qname);
    if (!packageOf.has(e.documentUri)) packageOf.set(e.documentUri, e.packageName);
  }
  const refFor = (uri: string, kind: string, name: string, parent = ''): string | undefined => {
    const q = qnameOf.get(`${uri} ${kind} ${parent} ${name}`);
    return q === undefined ? undefined : modelPathOf(q);
  };

  for (const [uri, doc] of ctx.documents) {
    for (const def of doc.definitions ?? []) {
      if (def.kind === 'entity') {
        const ref = refFor(uri, 'entity', def.name);
        if (!ref) continue;
        anchors.push({ fold: foldForCollision(def.name), ref, anchorKind: 'name', anchor: def.name });
        pushLocalized(anchors, ref, 'displayLabel', def.displayLabel);
        if (def.labelPlural && def.labelPlural.trim().length > 0) {
          anchors.push({
            fold: foldForCollision(def.labelPlural),
            ref,
            anchorKind: 'labelPlural',
            anchor: def.labelPlural,
          });
        }
        for (const alias of def.aliases ?? []) {
          if (alias.trim().length === 0) continue;
          anchors.push({ fold: foldForCollision(alias), ref, anchorKind: 'alias', anchor: alias });
        }
        memberAnchors(anchors, ref, def.attributes ?? []);
      } else if (def.kind === 'table') {
        const ref = refFor(uri, 'table', def.name);
        if (!ref) continue;
        // A `def db table` carries no display labels in the grammar — its local
        // name is the whole anchor set.
        anchors.push({ fold: foldForCollision(def.name), ref, anchorKind: 'name', anchor: def.name });
        memberAnchors(anchors, ref, def.columns ?? []);
      }
    }

    // Declared forms — `canonical` and `inline` origins only.
    //
    // `valueLabels`-origin entries are member vocabulary (`M:` at runtime). `legacy`-origin
    // entries come from THREE deprecated surfaces — entity `aliases`, `search { aliases }` and
    // `search { keywords }` — of which only the first is harvested above as a name anchor. All
    // three are excluded anyway, and the reason is the compiler, not the anchor walk: the Kotlin
    // `MetadataExtractor` reads `def.aliases` and never touches `search {}`, so admitting a
    // migrated `search { keywords }` form here would make this lint report collisions
    // `RG-LEXC-004` cannot have.
    for (const entry of desugarLexicon(doc).entries) {
      if (entry.entryKind !== 'term') continue;
      if (entry.origin !== 'canonical' && entry.origin !== 'inline') continue;
      if (!entry.target) continue;
      const target = canonicalTarget(ctx, uri, entry.target, packageOf.get(uri));
      for (const form of entry.forms ?? []) {
        if (form.trim().length === 0) continue;
        forms.push({ fold: foldForCollision(form), termName: entry.name, target, form, source: entry.source });
      }
    }
  }

  const index: AnchorIndex = { anchors, forms };
  ctx.cache.set(ANCHOR_INDEX_KEY, index);
  return index;
}

const formCollidesWithName: Rule = {
  id: 'lexicon-form-collides-with-name',
  code: DiagnosticCode.LexiconFormCollidesWithName,
  category: 'lexicon',
  scope: 'project',
  defaultSeverity: 'warning',
  docs:
    "A declared term form folds to the same key as another ref's name/label anchor; at runtime " +
    'both refs claim that word (a G2 on a pre-MH resolver, a slot decision on an MH one). The ' +
    'name-owner should keep the bare form; drop it from the term, or keep it deliberately and ' +
    'suppress this rule.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    const { anchors, forms } = buildAnchorIndex(ctx);

    const byFold = new Map<string, AnchorRow[]>();
    const add = (row: AnchorRow): void => {
      const list = byFold.get(row.fold);
      if (list) list.push(row);
      else byFold.set(row.fold, [row]);
    };
    for (const a of anchors) add(a);
    // A declared form is itself an anchor for the OTHER terms at the same fold.
    for (const f of forms) add({ fold: f.fold, ref: f.target, anchorKind: 'declared form', anchor: f.form });

    for (const f of forms) {
      const seen = new Set<string>();
      const colliding = (byFold.get(f.fold) ?? [])
        // A term that repeats its own target's label is redundant, not a collision.
        .filter((a) => a.ref !== f.target)
        .filter((a) => {
          const key = `${a.ref} ${a.anchorKind} ${a.anchor}`;
          if (seen.has(key)) return false;
          seen.add(key);
          return true;
        })
        .sort(
          (a, b) =>
            anchorRank(a.anchorKind) - anchorRank(b.anchorKind) ||
            a.ref.localeCompare(b.ref) ||
            a.anchorKind.localeCompare(b.anchorKind) ||
            a.anchor.localeCompare(b.anchor)
        );

      for (const a of colliding) {
        ctx.report({
          source: f.source,
          message:
            `term "${f.termName}" form "${f.form}" (for: ${f.target}) collides with the ` +
            `${a.anchorKind} anchor "${a.anchor}" of ${a.ref}; the name-owner binds the bare form — ` +
            'drop it from the term, or keep it deliberately (an MH resolver decides by slot) and suppress this rule',
          data: { ref: a.ref, anchorKind: a.anchorKind, anchor: a.anchor, fold: f.fold },
        });
      }
    }
  },
};

export const LEXICON_RULES: Rule[] = [
  lexRule('lexicon-wrong-model-kind', DiagnosticCode.LexiconWrongModelKind, 'warning', 'A `term`/`pattern`/`example` outside `model lexicon`, or a non-lexicon def inside one.'),
  lexRule('lexicon-missing-target', DiagnosticCode.LexiconMissingTarget, 'error', 'A lexicon entry has no `for:` target.'),
  lexRule('lexicon-entry-field-missing', DiagnosticCode.LexiconEntryFieldMissing, 'warning', 'A `term` needs `forms`, a `pattern` needs `match`, an `example` needs `text`.'),
  lexRule('lexicon-duplicate-form', DiagnosticCode.LexiconDuplicateForm, 'warning', 'The same surface form is declared more than once for one target.'),
  lexRule('lexicon-locale-on-non-lexicon', DiagnosticCode.LexiconLocaleOnNonLexicon, 'warning', 'A `locale` unit header on a model that is not `lexicon`.'),
  // RS-32 legacy-vocabulary deprecations — each fires when a legacy form migrates.
  lexRule('lexicon-legacy-aliases', DiagnosticCode.LexiconLegacyAliases, 'warning', 'Entity `aliases` / `search { aliases }` is deprecated — declare a lexicon `term`.'),
  lexRule('lexicon-legacy-keywords', DiagnosticCode.LexiconLegacyKeywords, 'warning', '`search { keywords }` is deprecated — declare locale-keyed lexicon `term` entries.'),
  lexRule('lexicon-legacy-patterns', DiagnosticCode.LexiconLegacyPatterns, 'warning', '`search { patterns }` is deprecated — declare lexicon `pattern` entries.'),
  lexRule('lexicon-legacy-examples', DiagnosticCode.LexiconLegacyExamples, 'warning', '`search { examples }` is deprecated — declare lexicon `example` entries.'),
  lexRule('lexicon-legacy-descriptions', DiagnosticCode.LexiconLegacyDescriptions, 'warning', '`search { descriptions }` is deprecated — fold into `description`.'),
  // MH T1 — the only project-scoped rule of the family (contracts §2).
  formCollidesWithName,
];
