// SPDX-License-Identifier: Apache-2.0
// Qualified-name redesign (docs/features/qname-redesign, contracts §3–§4).
//
// Two jobs, two strings:
//   - **Canonical key** — internal, uniform, fully-qualified, package-first.
//     Every slot present. The resolver + symbol table speak this. Built by
//     `qnameToKey`.
//   - **Surface reference** — what authors write; short, almost every slot
//     elided. Classified by `classifyReference` and filled by `resolveReference`.
//
// `query` is NOT a model (D14) — a `def query` resolves to model `db`, or to `er`
// when it is declared in a `model er` file (a logical query, LQ L-1). `cnc` is
// schema-less with no namespace echo (D15). The schema slot is **db only** (D6).

import { namespaceForKind, modelForKind } from './default-schema.js';

// `modelForKind` is defined once in `default-schema.ts` (the single kind→model
// source of truth, D4/D14/D15) and re-exported here so the public API name and
// the Kotlin/Python parity name stay stable.
export { modelForKind };

/** The model type / layer. `query` folds into `db`; `cnc` is schema-less; `world` is v4.1; `automation` is 0.16. */
export type ModelCode = 'db' | 'er' | 'md' | 'binding' | 'cnc' | 'world' | 'automation';

export const MODEL_CODES: ReadonlySet<ModelCode> = new Set(['db', 'er', 'md', 'binding', 'cnc', 'world', 'automation']);

/**
 * The uniform, package-first canonical key. `schema` and `kind` may be absent
 * only transiently during filling; after resolution both `model` and `kind` are
 * known and `schema` is present iff `model === 'db'`.
 */
export interface Qname {
  /** '' for the root package. */
  package: string;
  model: ModelCode;
  /** db ONLY; undefined for er/md/cnc/binding. */
  schema?: string;
  /** Resolved object kind (always known after resolution), e.g. 'table', 'entity'. */
  kind: string;
  /** Name + sub-objects, e.g. ['Orders', 'id']. Never empty. */
  parts: string[];
}

/**
 * Canonical key, package-first, dropping absent slots (contracts §3):
 *   shop.sales · db · dbo · table · Orders   → "shop.sales.db.dbo.table.Orders"
 *   shop.core  · er ·     · entity · customer → "shop.core.er.entity.customer"
 */
export function qnameToKey(q: Qname): string {
  const segments: string[] = [];
  if (q.package) segments.push(q.package);
  segments.push(q.model);
  if (q.schema) segments.push(q.schema);
  segments.push(q.kind);
  segments.push(...q.parts);
  return segments.join('.');
}

/** `true` iff this model carries a schema slot (db only — D6). */
export function modelHasSchema(model: ModelCode): boolean {
  return model === 'db';
}

/**
 * The single source of truth for the v4.0 uniform canonical-key shape
 * `<package>.<model>.<schema?>.<kind>.<parts>`. The model is derived from the
 * (top-level / owning) `kind`; the schema segment is present only for `db`
 * (defaulting to `dbo`); the kind segment uses the namespace alias where one
 * exists (`mdDomain`→`domain`, …) else the kind verbatim. The symbol table,
 * reference index, model-graph and migrator all build keys through here so the
 * shape lives in exactly one place (mirrored in Kotlin `Kinds.kt` / Python).
 */
export function buildCanonicalKey(opts: {
  packageName?: string;
  /** The file's `schema` directive id (db only); falls back to `dbo`. */
  schemaId?: string;
  /** The owning def's kind (a column/attribute uses its parent table/entity kind). */
  kind: string;
  /** Name path: `[def.name]` for a top-level def, `[parent, child]` for a member. */
  parts: string[];
  /**
   * The declaring file's `model` directive code. Only a `query` reads it: declared
   * under `model er` it is a logical query, `er.query.<name>` (LQ L-1).
   */
  declaringModel?: string;
}): string {
  const model = modelForKind(opts.kind, opts.declaringModel);
  return qnameToKey({
    package: opts.packageName ?? '',
    model,
    schema: model === 'db' ? (opts.schemaId || 'dbo') : undefined,
    kind: namespaceForKind(opts.kind) || opts.kind,
    parts: opts.parts,
  });
}

// ---------------------------------------------------------------------------
// Slot-filling: classify (pure) then resolve (architecture §5).
// ---------------------------------------------------------------------------

export interface Vocab {
  /** Fixed reserved set. */
  models: ReadonlySet<string>;
  /** Registered (manifest) + path-inferred package names. */
  packages: ReadonlySet<string>;
  /** Registered schema handles. */
  schemas: ReadonlySet<string>;
  /** Kind keywords (table, entity, …). */
  kinds: ReadonlySet<string>;
}

export interface PartialQname {
  model?: ModelCode;
  package?: string;
  schema?: string;
  kind?: string;
  /** Remaining name segments (never empty). */
  parts: string[];
}

/**
 * Step 1 — classify a dotted surface reference into partial slots by vocabulary
 * (NOT by position; sound because of the no-collision rule D9). Pure & total:
 * any segment run yields a PartialQname. Leading segments are consumed greedily
 * in the order model → package(longest dotted match) → schema → kind; whatever
 * remains is the name path.
 */
export function classifyReference(text: string, vocab: Vocab): PartialQname {
  const segs = text.split('.').filter((s) => s.length > 0);
  const out: PartialQname = { parts: [] };
  let i = 0;

  // model (a single reserved code)
  if (i < segs.length && vocab.models.has(segs[i])) {
    out.model = segs[i] as ModelCode;
    i++;
  }

  // package — longest registered dotted prefix from the current position.
  {
    let best = -1;
    for (let j = i; j < segs.length; j++) {
      const candidate = segs.slice(i, j + 1).join('.');
      if (vocab.packages.has(candidate)) best = j;
    }
    if (best >= i) {
      out.package = segs.slice(i, best + 1).join('.');
      i = best + 1;
    }
  }

  // schema (a registered handle)
  if (i < segs.length && vocab.schemas.has(segs[i])) {
    out.schema = segs[i];
    i++;
  }

  // model may also follow the package (e.g. `shop.sales.er.entity.x`).
  if (out.model === undefined && i < segs.length && vocab.models.has(segs[i])) {
    out.model = segs[i] as ModelCode;
    i++;
  }

  // schema may follow the post-package model (e.g. `shop.sales.db.dbo.x`); the
  // earlier schema check only fires for the model-less `<schema>.<name>` form.
  if (out.schema === undefined && i < segs.length && vocab.schemas.has(segs[i])) {
    out.schema = segs[i];
    i++;
  }

  // kind (a leading kind keyword — rarely written)
  if (i < segs.length && vocab.kinds.has(segs[i])) {
    out.kind = segs[i];
    i++;
  }

  // everything else is the name path.
  out.parts = segs.slice(i);
  return out;
}

export interface RefSite {
  /** From the grammar position, e.g. 'table' for `target: { table: … }`. */
  expectedKind?: string;
  filePackage: string;
  /** The file header `model … schema <id>`, if any (db/binding only — D12). */
  headerSchema?: string;
  /** The default schema for `filePackage` (manifest `[packages.*].default-schema`). */
  packageDefaultSchema?: string;
  /** The project-wide `[defaults].schema`. */
  projectDefaultSchema?: string;
}

/**
 * A minimal symbol index the resolver queries. Implemented over the live
 * `ProjectSymbolTable` during integration; a plain map drives the unit tests.
 */
export interface SymbolIndex {
  /** Exact canonical-key membership. */
  has(key: string): boolean;
  /** The resolved `kind` for a canonical key, or undefined. */
  kindOf(key: string): string | undefined;
  /**
   * Canonical keys whose trailing name path equals `parts` and that match the
   * given non-undefined scope slots. Used for scoped unique-match (D10).
   */
  candidates(parts: string[], scope: { package?: string; model?: ModelCode; schema?: string }): string[];
}

export type ReferenceDiagnosticCode = 'UnresolvedReference' | 'AmbiguousReference';

export interface ResolvedReference {
  resolved: true;
  key: string;
  qname: Qname;
  /** True when the result came from cross-schema unique-match (lintable, D10). */
  viaUniqueMatch: boolean;
}

export interface ReferenceDiagnostic {
  resolved: false;
  code: ReferenceDiagnosticCode;
  message: string;
  candidates?: string[];
}

/**
 * Step 2 — fill the gaps per architecture §5 and resolve. Fill order:
 *   kind   (context → leading keyword → from resolved target)
 *   model  (written → kind→model → unique)
 *   package(written → file → root)
 *   schema (written → package default → [defaults].schema → scoped-unique;
 *           absent for er/md/cnc/binding)
 * then resolve `parts` against (package, model, schema), searching the current
 * package/schema first before widening (D10).
 */
export function resolveReference(
  partial: PartialQname,
  site: RefSite,
  _vocab: Vocab,
  symbols: SymbolIndex,
): ResolvedReference | ReferenceDiagnostic {
  const parts = partial.parts;
  if (parts.length === 0) {
    return { resolved: false, code: 'UnresolvedReference', message: 'empty reference' };
  }

  // kind: written → context. (Inference from the resolved target happens via the
  // candidate search below when neither is known.)
  const kind = partial.kind ?? site.expectedKind;

  // model: written → derived from kind.
  const model: ModelCode | undefined = partial.model ?? (kind ? modelForKind(kind) : undefined);

  // package: written → file → root.
  const pkg = partial.package ?? site.filePackage ?? '';

  // schema: db only. written → package default → project default → (scoped-unique).
  let schema: string | undefined;
  if (model === undefined || modelHasSchema(model)) {
    schema = partial.schema ?? site.headerSchema ?? site.packageDefaultSchema ?? site.projectDefaultSchema;
  }

  // 1. If we know model + kind, try the exact canonical key (scoped to package,
  //    then widened) before any unique-match.
  if (model !== undefined && kind !== undefined) {
    const scopedSchema = modelHasSchema(model) ? schema : undefined;
    const exact = qnameToKey({ package: pkg, model, schema: scopedSchema, kind, parts });
    if (symbols.has(exact)) {
      return { resolved: true, key: exact, qname: { package: pkg, model, schema: scopedSchema, kind, parts }, viaUniqueMatch: false };
    }
  }

  // 2. Scoped candidate search: current package/schema first, then widen.
  const scopes: Array<{ package?: string; model?: ModelCode; schema?: string; label: 'scoped' | 'wide' }> = [];
  scopes.push({ package: pkg, model, schema: modelHasSchema(model ?? 'er') ? schema : undefined, label: 'scoped' });
  scopes.push({ model, label: 'wide' });
  scopes.push({ label: 'wide' });

  for (const scope of scopes) {
    const cands = dedupe(symbols.candidates(parts, { package: scope.package, model: scope.model, schema: scope.schema }));
    if (cands.length === 1) {
      const key = cands[0];
      const resolvedKind = symbols.kindOf(key) ?? kind ?? 'unknown';
      const resolvedModel = scope.model ?? model ?? modelForKind(resolvedKind);
      return {
        resolved: true,
        key,
        qname: { package: pkg, model: resolvedModel, schema: modelHasSchema(resolvedModel) ? schema : undefined, kind: resolvedKind, parts },
        viaUniqueMatch: scope.label === 'wide',
      };
    }
    if (cands.length > 1) {
      return { resolved: false, code: 'AmbiguousReference', message: `reference '${parts.join('.')}' matches ${cands.length} symbols`, candidates: cands };
    }
  }

  return { resolved: false, code: 'UnresolvedReference', message: `unresolved reference '${parts.join('.')}'` };
}

function dedupe(xs: string[]): string[] {
  return [...new Set(xs)];
}
