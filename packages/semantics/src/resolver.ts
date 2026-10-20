// SPDX-License-Identifier: Apache-2.0
import type { ImportDecl } from '@tatrman/parser';
import type { SymbolEntry } from './symbol-table.js';
import { ProjectSymbolTable } from './project-symbols.js';
import {
  classifyReference,
  resolveReference as slotResolveReference,
  MODEL_CODES,
  type Vocab,
  type RefSite,
  type SymbolIndex,
} from './qname.js';
import type { ResolvedManifest } from './manifest.js';

/**
 * Kind segments that may appear in a canonical key or a written reference
 * (camelCase kinds + their namespace aliases — `mdDomain`→`domain`, …). Used by
 * the resolver's vocabulary so {@link classifyReference} can strip a leading
 * kind keyword from a qualified reference.
 */
const KIND_KEYWORDS: ReadonlySet<string> = new Set([
  'entity', 'attribute', 'relation', 'table', 'view', 'column', 'index',
  'constraint', 'fk', 'procedure', 'query', 'drillMap', 'role', 'project', 'area',
  'er2dbEntity', 'er2dbAttribute', 'er2dbRelation', 'er2cncRole',
  'domain', 'map', 'dimension', 'hierarchy', 'measure', 'cubelet',
  'md2db_cubelet', 'md2db_domain', 'md2db_map', 'md2er_cubelet',
]);

export type ResolutionStep =
  | 'lexical'
  | 'same-package'
  | 'named-import'
  | 'wildcard-import'
  | 'auto-import'
  | 'fully-qualified';

export interface ResolutionAttempt {
  step: ResolutionStep;
  candidate: string;
  reason?:
    | 'unknown-symbol'
    | 'not-imported'
    | 'wildcard-non-recursive'
    | 'shadowed-by-named-import'
    | 'lexical-scope-empty'
    | 'ambiguous';
}

export type ResolutionResult =
  | { resolved: true; symbol: SymbolEntry; viaStep: ResolutionStep }
  | { resolved: false; reason: 'not-found' | 'ambiguous'; tried: ResolutionAttempt[]; candidates?: SymbolEntry[] };

export interface LexicalScope {
  schemaCode: string;
  namespace: string;
  enclosing?: { kind: 'entity' | 'table' | 'view' | 'procedure'; qname: string };
}

export interface ResolutionContext {
  schemaCode: string;
  namespace: string;
  /**
   * Optional enclosing-def qname (e.g. `er.entity.artikl`). When present, a
   * bare-id reference is first tried as a child of this enclosing def (so
   * `nameAttribute: id` inside `entity artikl` resolves to
   * `er.entity.artikl.id`).
   */
  enclosingQname?: string;
  /**
   * Optional document-level imports. When present, steps 3 (named-import) and
   * 4 (wildcard-import) use these to resolve bare references.
   */
  imports?: ImportDecl[];
  /**
   * The package name of the document containing the reference. Used for step 2
   * (same-package) lookups.
   */
  packageName?: string;
}



/**
 * What {@link Resolver.resolveReference} reads off a reference: its written path and segments, and —
 * for a reference from a cn slot (`CnSlotReference`) — the cn context. Pass the collected
 * reference itself, not a copy of its path, so the context survives.
 */
export interface ReferenceInput {
  path: string;
  parts: string[];
  cnContext?: boolean;
}

/** A concept or one of its participant labels (model `cn`, 0.17). */
function isCnSymbol(e: SymbolEntry): boolean {
  return e.schemaCode === 'cn';
}

/**
 * What an element reference never reaches: the cn model, and the bindings the cn desugar
 * synthesized (S2.2) — named `<Concept>.grounds` / `<Concept>.concept`, they would otherwise match a
 * bare name by suffix.
 */
function isHiddenFromElements(e: SymbolEntry): boolean {
  return isCnSymbol(e) || e.cnOrigin !== undefined;
}

/** The canonical key of a concept (or `<Name>.<label>`) in `pkg`: `<pkg>.cn.concept.<parts>`. */
function cnKey(pkg: string, parts: string[]): string {
  return (pkg ? `${pkg}.` : '') + ['cn', 'concept', ...parts].join('.');
}

function attempt(step: ResolutionStep, candidate: string, reason?: ResolutionAttempt['reason']): ResolutionAttempt {
  return { step, candidate, reason };
}

function dedupeBy<T>(xs: T[], key: (x: T) => string): T[] {
  const seen = new Set<string>();
  const out: T[] = [];
  for (const x of xs) {
    const k = key(x);
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(x);
  }
  return out;
}

export class Resolver {
  /**
   * `root` is the configured `[packages].root` prefix (PD1.4). When set, a
   * reference is resolved by trying both its written form and its
   * root-normalised variant, so a reference may freely include or omit the
   * prefix (B17 elision) and still reach the canonical symbol.
   */
  /**
   * `manifest` may be a value or a getter (the LSP holds a mutable `manifest`
   * that is reloaded when the project root changes, so the resolver reads it
   * lazily through `() => manifest`). It feeds the slot vocabulary (registered
   * packages + schema handles, D9) and the manifest-driven schema defaults
   * (`[packages.*].default-schema` / `[defaults].schema`, D8) consumed by the
   * slot-filling engine. When absent, resolution is manifest-free (conformance).
   */
  private manifestSource?: ResolvedManifest | (() => ResolvedManifest | undefined);

  constructor(
    private symbols: ProjectSymbolTable,
    private root = '',
    manifest?: ResolvedManifest | (() => ResolvedManifest | undefined),
  ) {
    this.manifestSource = manifest;
  }

  /**
   * The symbols an element reference may resolve to. Concepts are reachable only from a cn slot or
   * through the `cn.` head (CN-P2 S2.1), so a concept never shadows, or ambiguates, an element of the
   * same name — adding concepts to a project moves no existing reference.
   */
  private readonly elements = {
    get: (qname: string): SymbolEntry | undefined => {
      const e = this.symbols.get(qname);
      return e && !isHiddenFromElements(e) ? e : undefined;
    },
    getByPackage: (pkg: string): SymbolEntry[] => this.symbols.getByPackage(pkg).filter((e) => !isHiddenFromElements(e)),
    getBySuffix: (suffix: string): SymbolEntry[] => this.symbols.getBySuffix(suffix).filter((e) => !isHiddenFromElements(e)),
  };

  /** The concept (or participant label) a key reads as (`ProjectSymbolTable.get`: authored first). */
  private cnSymbol(key: string): SymbolEntry | undefined {
    const e = this.symbols.get(key);
    return e && isCnSymbol(e) ? e : undefined;
  }

  private currentManifest(): ResolvedManifest | undefined {
    return typeof this.manifestSource === 'function' ? this.manifestSource() : this.manifestSource;
  }

  private vocabCache?: { size: number; manifest: ResolvedManifest | undefined; vocab: Vocab };

  /**
   * Vocabulary for classifying qualified references — fixed model codes + kind
   * keywords, plus packages and db schema handles. Sources both from the live
   * symbol table (harvested) AND the manifest (registered `[schemas.*]` handles
   * and `[packages.*]` names, D9), so a registered package/schema is classifiable
   * even before any symbol under it exists. Cached on the symbol count + manifest
   * identity (a cheap dirty check).
   */
  private vocab(): Vocab {
    const qnames = this.symbols.allQnames();
    const manifest = this.currentManifest();
    if (this.vocabCache && this.vocabCache.size === qnames.length && this.vocabCache.manifest === manifest) {
      return this.vocabCache.vocab;
    }
    const schemas = new Set<string>(['dbo']);
    for (const q of qnames) {
      const segs = q.split('.');
      const i = segs.indexOf('db');
      if (i >= 0 && segs[i + 1]) schemas.add(segs[i + 1]);
    }
    const packages = new Set(this.symbols.listPackages().filter((p) => p !== ''));
    if (manifest) {
      for (const handle of Object.keys(manifest.schemas)) schemas.add(handle);
      if (manifest.defaults.schema) schemas.add(manifest.defaults.schema);
      for (const pkg of Object.keys(manifest.packageConfigs)) if (pkg) packages.add(pkg);
    }
    const vocab: Vocab = {
      models: MODEL_CODES as ReadonlySet<string>,
      packages,
      schemas,
      kinds: KIND_KEYWORDS,
    };
    this.vocabCache = { size: qnames.length, manifest, vocab };
    return vocab;
  }

  /**
   * A {@link SymbolIndex} over the live project symbols, used by the slot-filling
   * engine ({@link slotResolveReference}). `candidates` re-parses each canonical
   * key back into slots via {@link classifyReference} (sound because every key is
   * uniform and package-first) so a scoped search can match on package/model/
   * schema while requiring the trailing name path to equal `parts` exactly.
   */
  private symbolIndex(vocab: Vocab): SymbolIndex {
    const symbols = this.elements;
    return {
      has: (key) => symbols.get(key) !== undefined,
      kindOf: (key) => symbols.get(key)?.kind,
      candidates: (parts, scope) => {
        const suffix = parts.join('.');
        const out: string[] = [];
        const seen = new Set<string>();
        for (const entry of symbols.getBySuffix(suffix)) {
          if (seen.has(entry.qname)) continue;
          seen.add(entry.qname);
          const parsed = classifyReference(entry.qname, vocab);
          if (parsed.parts.join('.') !== suffix) continue;
          if (scope.package !== undefined && (parsed.package ?? '') !== scope.package) continue;
          if (scope.model !== undefined && parsed.model !== scope.model) continue;
          if (scope.schema !== undefined && parsed.schema !== scope.schema) continue;
          out.push(entry.qname);
        }
        return out;
      },
    };
  }

  /**
   * The trailing name path of a qualified reference, with any leading
   * model/package/schema/kind segments stripped (D4 slot order). For a bare or
   * trailing-path reference this equals the input. Used so a reference that
   * names a slot the canonical key now carries explicitly (e.g. `db.dbo.Orders`,
   * which became `…db.dbo.table.Orders`) still finds its symbol by suffix.
   */
  private classifiedTail(path: string): string {
    const partial = classifyReference(path, this.vocab());
    return partial.parts.join('.');
  }

  /** Direct symbol-table lookup by fully-qualified name. */
  getSymbol(qname: string): SymbolEntry | undefined {
    return this.symbols.get(qname);
  }

  /** Every symbol with this local name, in any package or model (one scan of the table). */
  symbolsNamed(name: string): SymbolEntry[] {
    return this.symbols.findByName(name);
  }

  /**
   * The root-elision variants of a dotted name: the name itself, plus the name
   * with the configured `root` stripped (if present) or prepended (if absent).
   * Order preserves the written form first so `tried[]` reflects intent.
   */
  private rootVariants(name: string): string[] {
    if (!this.root) return [name];
    const variants = [name];
    if (name === this.root || name.startsWith(`${this.root}.`)) {
      const stripped = name === this.root ? '' : name.slice(this.root.length + 1);
      if (stripped && stripped !== name) variants.push(stripped);
    } else {
      variants.push(`${this.root}.${name}`);
    }
    return variants;
  }

  /** Symbol-table lookup that also tries the root-elision variants of `qname`. */
  private getCanonical(qname: string): SymbolEntry | undefined {
    for (const cand of this.rootVariants(qname)) {
      const symbol = this.elements.get(cand);
      if (symbol) return symbol;
    }
    return undefined;
  }

  resolveReference(ref: ReferenceInput, context: ResolutionContext): ResolutionResult {
    // 0.17 — a cn slot's reference, or one written `cn.<Name>`, resolves among concepts only.
    if (ref.cnContext || (ref.parts.length > 1 && ref.parts[0] === 'cn')) return this.resolveCn(ref, context);

    const tried: ResolutionAttempt[] = [];
    const enclosingCandidate = context.enclosingQname ? `${context.enclosingQname}.${ref.path}` : undefined;

    if (enclosingCandidate) {
      tried.push(attempt('lexical', enclosingCandidate));
      const symbol = this.elements.get(enclosingCandidate);
      if (symbol) return { resolved: true, symbol, viaStep: 'lexical' };
      tried[tried.length - 1].reason = 'unknown-symbol';
    }

    const fullQname = `${context.schemaCode}.${context.namespace}.${ref.path}`;
    if (fullQname !== context.enclosingQname) {
      tried.push(attempt('same-package', fullQname));
      const symbol = this.getCanonical(fullQname);
      if (symbol) return { resolved: true, symbol, viaStep: 'same-package' };
      tried[tried.length - 1].reason = 'unknown-symbol';
    }

    if (context.packageName) {
      const pkgSymbols = this.elements.getByPackage(context.packageName);
      for (const entry of pkgSymbols) {
        if (entry.name === ref.path) {
          const candidate = entry.qname;
          if (candidate !== enclosingCandidate && candidate !== fullQname) {
            return { resolved: true, symbol: entry, viaStep: 'same-package' };
          }
        }
      }
    }

    if (context.imports) {
      for (const imp of context.imports) {
        if (imp.wildcard) continue;
        if (imp.target.endsWith(`.${ref.path}`)) {
          const symbol = this.getCanonical(imp.target);
          if (symbol) return { resolved: true, symbol, viaStep: 'named-import' };
        }
      }

      const wildcardMatches: SymbolEntry[] = [];
      for (const imp of context.imports) {
        if (!imp.wildcard) continue;
        const pkg = imp.target;
        const matches = this.elements.getByPackage(pkg).filter(
          (e) => e.name === ref.path && e.qname !== fullQname
        );
        for (const m of matches) {
          if (!wildcardMatches.find((w) => w.qname === m.qname)) {
            wildcardMatches.push(m);
          }
        }
      }
      if (wildcardMatches.length > 1) {
        for (const m of wildcardMatches) {
          tried.push(attempt('wildcard-import', m.qname, 'ambiguous'));
        }
        return { resolved: false, reason: 'ambiguous', tried, candidates: wildcardMatches };
      }
      if (wildcardMatches.length === 1) {
        tried.push(attempt('wildcard-import', wildcardMatches[0].qname));
        return { resolved: true, symbol: wildcardMatches[0], viaStep: 'wildcard-import' };
      }
    }

    // Stock cnc roles are auto-imported (D15: `cnc.role.<name>`, no doubling).
    const cncQname = `cnc.role.${ref.path}`;
    if (cncQname !== fullQname && cncQname !== context.enclosingQname) {
      tried.push(attempt('auto-import', cncQname));
      const cncSymbol = this.elements.get(cncQname);
      if (cncSymbol) return { resolved: true, symbol: cncSymbol, viaStep: 'auto-import' };
      tried[tried.length - 1].reason = 'unknown-symbol';
    }

    // Fully-qualified: try the written form and its root-elision variants (B17),
    // then the classified tail — the trailing name path with any leading
    // model/package/schema/kind segments stripped — so a reference naming a slot
    // the v4.0 key now carries explicitly (`db.dbo.Orders` → `…db.dbo.table.Orders`)
    // still resolves by suffix. The first variant that yields a unique match
    // wins; an exact canonical lookup short-circuits ahead of suffix matching.
    const tail = this.classifiedTail(ref.path);
    const forms = tail && tail !== ref.path ? [...this.rootVariants(ref.path), tail] : this.rootVariants(ref.path);
    for (const cand of forms) {
      const exact = this.elements.get(cand);
      if (exact && exact.qname !== fullQname && exact.qname !== cncQname) {
        tried.push(attempt('fully-qualified', exact.qname));
        return { resolved: true, symbol: exact, viaStep: 'fully-qualified' };
      }
      const uniqueMatches = dedupeBy(
        this.elements.getBySuffix(cand).filter((e) => e.qname !== fullQname && e.qname !== cncQname),
        (e) => e.qname,
      );
      if (uniqueMatches.length === 1) {
        tried.push(attempt('fully-qualified', uniqueMatches[0].qname));
        return { resolved: true, symbol: uniqueMatches[0], viaStep: 'fully-qualified' };
      }
    }

    // Slot-filling engine (qname-redesign architecture §5) — the authoritative
    // canonical resolver. Reached only when the global suffix match above was not
    // unique (0 or >1 matches). Classifies the reference by vocabulary and fills
    // its slots from the manifest-aware site (D8 package/`[defaults]` schema), then
    // runs a scoped unique-match (current package first, then widen — D10): this
    // resolves a name that is ambiguous project-wide but unique within the file's
    // package, and otherwise surfaces an explicit AmbiguousReference instead of a
    // bare not-found.
    {
      const vocab = this.vocab();
      const manifest = this.currentManifest();
      const filePackage = context.packageName ?? '';
      const site: RefSite = {
        filePackage,
        headerSchema: context.namespace || undefined,
        packageDefaultSchema: manifest?.packageConfigs[filePackage]?.defaultSchema,
        projectDefaultSchema: manifest?.defaults.schema,
      };
      const partial = classifyReference(ref.path, vocab);
      const slot = slotResolveReference(partial, site, vocab, this.symbolIndex(vocab));
      if (slot.resolved) {
        const symbol = this.elements.get(slot.key);
        if (symbol && symbol.qname !== fullQname && symbol.qname !== cncQname) {
          tried.push(attempt('fully-qualified', symbol.qname));
          return { resolved: true, symbol, viaStep: 'fully-qualified' };
        }
      } else if (slot.code === 'AmbiguousReference') {
        const candidates = (slot.candidates ?? [])
          .map((k) => this.elements.get(k))
          .filter((s): s is SymbolEntry => s !== undefined && s.qname !== fullQname && s.qname !== cncQname);
        if (candidates.length > 1) {
          for (const c of candidates) tried.push(attempt('fully-qualified', c.qname, 'ambiguous'));
          return { resolved: false, reason: 'ambiguous', tried, candidates };
        }
      }
    }

    return { resolved: false, reason: 'not-found', tried };
  }

  /**
   * 0.17 — a reference from a cn slot (`CnSlotReference`), or one written with the `cn.` head,
   * resolves among concepts only: `<pkg>.cn.concept.<Name>`, or an event's participant label
   * `<Name>.<label>`. The steps mirror the element ones — package-qualified, same package, named
   * import, wildcard import, then a unique match across the project (`unimported-reference` names
   * the missing import). It never falls back to an element of the same name.
   */
  private resolveCn(ref: ReferenceInput, context: ResolutionContext): ResolutionResult {
    const tried: ResolutionAttempt[] = [];
    const parts = ref.parts.length > 1 && ref.parts[0] === 'cn' ? ref.parts.slice(1) : ref.parts;
    const lookup = (step: ResolutionStep, key: string): SymbolEntry | undefined => {
      tried.push(attempt(step, key));
      const symbol = this.cnSymbol(key);
      if (symbol) return symbol;
      tried[tried.length - 1].reason = 'unknown-symbol';
      return undefined;
    };

    // Package-qualified (`hartland.core.Party`): a known package prefix that leaves a name.
    const packages = this.vocab().packages;
    for (let i = parts.length - 1; i >= 1; i--) {
      const pkg = parts.slice(0, i).join('.');
      if (!packages.has(pkg)) continue;
      for (const variant of this.rootVariants(pkg)) {
        const symbol = lookup('fully-qualified', cnKey(variant, parts.slice(i)));
        if (symbol) return { resolved: true, symbol, viaStep: 'fully-qualified' };
      }
    }

    const samePackage = lookup('same-package', cnKey(context.packageName ?? '', parts));
    if (samePackage) return { resolved: true, symbol: samePackage, viaStep: 'same-package' };

    for (const imp of context.imports ?? []) {
      if (imp.wildcard) continue;
      const segs = imp.target.split('.');
      if (segs.length < 2 || segs[segs.length - 1] !== parts[0]) continue;
      const symbol = lookup('named-import', cnKey(segs.slice(0, -1).join('.'), parts));
      if (symbol) return { resolved: true, symbol, viaStep: 'named-import' };
    }

    const wildcardMatches = dedupeBy(
      (context.imports ?? [])
        .filter((imp) => imp.wildcard)
        .map((imp) => this.cnSymbol(cnKey(imp.target, parts)))
        .filter((e): e is SymbolEntry => e !== undefined),
      (e) => e.qname,
    );
    if (wildcardMatches.length > 1) {
      for (const m of wildcardMatches) tried.push(attempt('wildcard-import', m.qname, 'ambiguous'));
      return { resolved: false, reason: 'ambiguous', tried, candidates: wildcardMatches };
    }
    if (wildcardMatches.length === 1) {
      tried.push(attempt('wildcard-import', wildcardMatches[0].qname));
      return { resolved: true, symbol: wildcardMatches[0], viaStep: 'wildcard-import' };
    }

    const suffix = cnKey('', parts);
    const anywhere = dedupeBy(this.symbols.getBySuffix(suffix).filter(isCnSymbol), (e) => e.qname)
      .map((e) => this.cnSymbol(e.qname) ?? e);
    if (anywhere.length === 1) {
      tried.push(attempt('fully-qualified', anywhere[0].qname));
      return { resolved: true, symbol: anywhere[0], viaStep: 'fully-qualified' };
    }
    if (anywhere.length > 1) {
      for (const m of anywhere) tried.push(attempt('fully-qualified', m.qname, 'ambiguous'));
      return { resolved: false, reason: 'ambiguous', tried, candidates: anywhere };
    }
    return { resolved: false, reason: 'not-found', tried };
  }

  resolveBareId(name: string, scope: LexicalScope): ResolutionResult {
    const tried: ResolutionAttempt[] = [];

    if (scope.enclosing) {
      const withEnclosing = `${scope.enclosing.qname}.${name}`;
      tried.push(attempt('lexical', withEnclosing));
      const symbol = this.symbols.get(withEnclosing);
      if (symbol) return { resolved: true, symbol, viaStep: 'lexical' };
      tried[tried.length - 1].reason = 'unknown-symbol';
    }

    const withSchema = `${scope.schemaCode}.${scope.namespace}.${name}`;
    if (withSchema !== scope.enclosing?.qname) {
      tried.push(attempt('same-package', withSchema));
      const symbol = this.symbols.get(withSchema);
      if (symbol) return { resolved: true, symbol, viaStep: 'same-package' };
      tried[tried.length - 1].reason = 'unknown-symbol';
    }

    const cncQname = `cnc.role.${name}`;
    if (cncQname !== withSchema) {
      tried.push(attempt('auto-import', cncQname));
      const cncSymbol = this.symbols.get(cncQname);
      if (cncSymbol) return { resolved: true, symbol: cncSymbol, viaStep: 'auto-import' };
      tried[tried.length - 1].reason = 'unknown-symbol';
    }

    return { resolved: false, reason: 'not-found', tried };
  }
}
