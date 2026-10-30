// SPDX-License-Identifier: Apache-2.0
import { parseString, type Definition, type Document, type EntityDef, type ObjectValue, type Reference } from '@tatrman/parser';
import { effectivePackage, effectiveSchemaId, ProjectSymbolTable, ReferenceIndex, Resolver, type SymbolEntry } from '@tatrman/semantics';
import type { SynthContext } from './types.js';
import { compareCodePoints } from '@tatrman/parser/layout';
import { Refusal } from './refusals.js';

export interface ModelDocument { uri: string; text: string; ast: Document; pkg: string; model: string; schema: string }
/** What a file declares at its top: on disk, or in the header `append` prints into a new one. */
export interface Header { pkg: string; model: string; locale?: string }
/**
 * What one synthesis has written so far. `ModelIndex.documents` are the files before the edit, so a later step of the
 * same run (inline terms: en, then cs) sees an earlier step's new file and term only through this.
 */
export interface Written { files: Map<string, Header>; terms: { uri: string; qname: string }[] }
export interface IndexedNode { symbol: SymbolEntry; def: Definition; doc: ModelDocument }
export function targetOf(target: ObjectValue | Reference | undefined): { kind: string; path: string } | undefined {
  if (!target) return undefined;
  if (!('kind' in target)) return { kind: '', path: target.path };
  for (const entry of target.entries) if (entry.value.kind === 'id') return { kind: entry.key, path: entry.value.path };
  return undefined;
}
export class ModelIndex {
  private readonly ambiguous = new Set<string>();
  readonly broken: { uri: string; reason: string }[] = [];
  readonly documents: ModelDocument[] = [];
  readonly symbols = new ProjectSymbolTable();
  readonly resolver: Resolver;
  readonly references = new ReferenceIndex();
  constructor(readonly ctx: SynthContext) {
    for (const [uri, text] of Object.entries(ctx.files).sort(([a], [b]) => compareCodePoints(a, b))) {
      if (!uri.endsWith('.ttrm')) continue;
      const parsed = parseString(text, uri);
      if (!parsed.ast || parsed.errors.some(e => e.severity === 'error')) { this.broken.push({uri, reason:'unparseable'}); continue; }
      const ast = parsed.ast;
      const duplicates = new Set<number>();
      const scan = (defs: Definition[]): void => {
        const seen = new Map<string, Definition>();
        for (const def of defs) {
          const key = `${def.kind}:${def.name}`, first = seen.get(key);
          if (first) { duplicates.add(first.source.offsetStart); duplicates.add(def.source.offsetStart); }
          else seen.set(key, def);
          if (def.kind === 'entity') scan(def.attributes ?? []);
          else if (def.kind === 'table' || def.kind === 'view') scan(def.columns ?? []);
        }
      };
      scan(ast.definitions);
      if (duplicates.size) this.broken.push({uri, reason:'duplicate definitions'});
      const doc = { uri, text, ast, pkg: effectivePackage(ast, uri, ctx.rootUri.startsWith('file://') ? new URL(ctx.rootUri).pathname : ctx.rootUri, ctx.manifest.packages), model: ast.modelDirective?.modelCode ?? '', schema: '' };
      doc.schema = effectiveSchemaId(ast.modelDirective?.schema, doc.pkg, ctx.manifest);
      this.documents.push(doc);
      this.symbols.upsertDocument(uri, ast, doc.model, doc.schema, doc.pkg);
      if (duplicates.size) for (const symbol of this.symbols.all()) if (symbol.documentUri === uri && duplicates.has(symbol.source.offsetStart)) this.ambiguous.add(symbol.qname);
    }
    this.resolver = new Resolver(this.symbols, ctx.manifest.packages.root, ctx.manifest);
    for (const d of this.documents) this.references.upsertDocument(d.uri, d.ast, d.model, d.schema, this.resolver, d.pkg);
  }
  node(symbol: SymbolEntry): IndexedNode {
    const doc = this.documents.find(d => d.uri === symbol.documentUri)!;
    const find = (defs: Definition[]): Definition | undefined => {
      for (const def of defs) {
        if (def.source.offsetStart === symbol.source.offsetStart && def.name === symbol.name) return def;
        const children = def.kind === 'entity' ? def.attributes : def.kind === 'table' || def.kind === 'view' ? def.columns : undefined;
        const hit = children && find(children);
        if (hit) return hit;
      }
    };
    const def = find(doc.ast.definitions);
    if (!def) throw new Refusal('node-unknown', `Cannot edit synthesized symbol ${symbol.qname}.`);
    return { symbol, def, doc };
  }
  lookup(ref: string, kinds?: SymbolEntry['kind'][], scope?: ModelDocument): IndexedNode | undefined {
    const direct = this.symbols.getAll(ref);
    if (direct.length > 1 || this.ambiguous.has(ref)) throw new Refusal('ambiguous-reference', `${ref} has multiple declarations.`);
    let found = direct[0];
    if (!found) {
      const packages = scope ? [scope.pkg] : [...new Set(this.documents.map(d => d.pkg))];
      const hits = new Map<string, SymbolEntry>();
      for (const pkg of packages) {
        const res = this.resolver.resolveReference({ path: ref, parts: ref.split('.') }, { schemaCode: scope?.model ?? '', namespace: scope?.schema ?? '', imports: scope?.ast.imports ?? [], packageName: pkg });
        if (!res.resolved && res.reason === 'ambiguous') throw new Refusal('ambiguous-reference', `${ref} is ambiguous; qualify it.`);
        if (res.resolved) {
          if (this.symbols.getAll(res.symbol.qname).length > 1 || this.ambiguous.has(res.symbol.qname)) throw new Refusal('ambiguous-reference', `${ref} has multiple declarations.`);
          hits.set(res.symbol.qname, res.symbol);
        }
      }
      if (hits.size > 1) throw new Refusal('ambiguous-reference', `${ref} resolves in multiple packages; qualify it.`);
      found = [...hits.values()][0];
    }
    return found && (!kinds || kinds.includes(found.kind)) ? this.node(found) : undefined;
  }
  binding(entity: IndexedNode): { kind: string; path: string } | undefined {
    const def = entity.def as EntityDef;
    if (def.binding?.kind === 'block') { const target = targetOf(def.binding.target); if (target) return target; }
    for (const d of this.documents) for (const b of d.ast.definitions) if (b.kind === 'er2dbEntity' && b.entity) {
      if (this.lookup(b.entity.path, ['entity'], d)?.symbol.qname === entity.symbol.qname) return targetOf(b.target);
    }
    return undefined;
  }
  uri(path: string): string {
    if (!usablePath(path)) throw new Refusal('invalid-args', 'file must be a repository-relative path.');
    const root = this.ctx.rootUri.replace(/\/$/, '');
    const candidate = root ? root + '/' + path : path;
    const existing = Object.keys(this.ctx.files).find(key => decoded(key) === decoded(candidate));
    if (existing) return existing;
    // A new key is spelled like the host's keys: encoded when the root is, or when the keys under a raw root are.
    if (root.startsWith('file://')) {
      const href = new URL(root).href.replace(/\/$/, '');
      if (href === root || Object.keys(this.ctx.files).some(key => key.startsWith(href + '/')))
        return href + '/' + path.split('/').map(encodeURIComponent).join('/');
    }
    return candidate;
  }
  assertWritable(uri: string): void {
    const broken = this.broken.find(d => d.uri === uri);
    if (broken) throw new Refusal('text-unparseable', `Repair ${broken.uri} (${broken.reason}) before editing it.`);
  }
  assertComplete(): void {
    if (this.broken.length) throw new Refusal('text-unparseable', 'Cannot prove references while files are broken: ' + this.broken.map(d => `${d.uri} (${d.reason})`).join(', '));
  }
  packageFor(uri: string): string {
    return effectivePackage(parseString('', uri).ast!, uri, this.ctx.rootUri.startsWith('file://') ? new URL(this.ctx.rootUri).pathname : this.ctx.rootUri, this.ctx.manifest.packages);
  }
  /** The header of the file at `uri`: on disk, else as this run created it. */
  header(uri: string, written?: Written): Header | undefined {
    const d = this.documents.find(x => x.uri === uri);
    return d ? { pkg: d.pkg, model: d.model, locale: d.ast.modelDirective?.locale } : written?.files.get(uri);
  }
  /** Whether the file at `uri` takes a definition of `want`'s package and model (and locale, when given): no file yet, or one declaring the same. */
  takes(uri: string, want: Header, written?: Written): boolean {
    const h = this.header(uri, written);
    return !h || h.pkg === want.pkg && h.model === want.model && (!want.locale || h.locale === want.locale);
  }
  bindingFile(pkg: string): string {
    const docs = this.documents.filter(d => d.pkg === pkg && d.model === 'binding');
    return docs.find(d => d.ast.definitions.some(b => b.kind === 'er2dbEntity'))?.uri ?? docs[0]?.uri ?? this.uri('model/binding/er2db.ttrm');
  }
  /**
   * Without `file`, a term goes beside the target's terms in this locale (a file of the package with a `def term` for
   * it), else into the twin of a file holding them in another locale (its path under this locale's folder:
   * `en/topics/sales.ttrm` → `cs/topics/sales.ttrm`), else into the target file's first hyphen-separated stem
   * (`returns-review.ttrm` → `returns.ttrm`; the target's model code when the stem is empty or not a usable name). The
   * stem file goes in the folder of the package's first lexicon file in this locale; without one, in the folder of
   * its first file in another locale, swapped to this locale (`b/lexicon/cs/terms.ttrm` → `b/lexicon/en/`), and
   * `model/lexicon/<lang>/` when the package has no lexicon file at all. A path held by another package, model or
   * locale gives way to that first file; without one, to `<folder>/<model code>.ttrm`. `written` is what this run has
   * written already, so inline `insert-entity.terms` place each locale as the two-step path does.
   */
  lexiconFile(lang: string, target: { qname: string; pkg: string; uri: string; model: string }, file?: string, written?: Written): string {
    if (file) return this.uri(`model/lexicon/${lang}/${file}`);
    const refs = this.references.findByQname(target.qname);
    const holds = (d: ModelDocument): boolean => d.ast.definitions.some(t => t.kind === 'term' && t.target
      && refs.some(r => r.documentUri === d.uri && r.source.offsetStart === t.target!.source.offsetStart));
    // The package's lexicon files, on disk and new in this run, in the order the index reads files in.
    const lexicon = [
      ...this.documents.filter(d => d.model === 'lexicon' && d.pkg === target.pkg).map(d => ({ uri: d.uri, locale: d.ast.modelDirective?.locale, holds: holds(d) })),
      ...[...written?.files ?? []].filter(([, h]) => h.model === 'lexicon' && h.pkg === target.pkg).map(([uri, h]) => ({ uri, locale: h.locale, holds: false })),
    ].map(f => ({ ...f, holds: f.holds || !!written?.terms.some(t => t.uri === f.uri && t.qname === target.qname) }))
      .sort((a, b) => compareCodePoints(a.uri, b.uri));
    const holders = lexicon.filter(f => f.holds);
    const same = holders.find(f => f.locale === lang);
    if (same) return same.uri;
    const fits = (uri: string): boolean => this.takes(uri, { pkg: target.pkg, model: 'lexicon', locale: lang }, written);
    // The index of `path`'s last folder named `locale`; -1 when it has none.
    const localeAt = (segments: string[], locale: string | undefined): number => locale ? segments.slice(0, -1).lastIndexOf(locale) : -1;
    const first = lexicon.find(f => f.locale === lang), other = lexicon.find(f => f.locale);
    const folder = ((): string => {
      if (first) return this.path(first.uri).split('/').slice(0, -1).join('/');
      if (!other) return '';
      const segments = this.path(other.uri).split('/'), at = localeAt(segments, other.locale);
      return (at < 0 ? segments.slice(0, -1) : [...segments.slice(0, at), lang]).join('/');
    })();
    const twins = holders.flatMap(f => {
      const segments = this.path(f.uri).split('/'), at = localeAt(segments, f.locale);
      if (at < 0) return [];
      segments[at] = lang;
      const twin = segments.join('/');
      return usablePath(twin) ? [this.uri(twin)] : [];
    });
    const stem = fileName(target.uri).replace(/\.ttrm$/, '').split('-')[0];
    const named = (name: string): string => this.uri(`${folder && usablePath(folder) ? folder : `model/lexicon/${lang}`}/${name}.ttrm`);
    const derived = [...twins, named(stem && usableName(stem) ? stem : target.model)];
    return derived.find(fits) ?? first?.uri ?? [named(target.model)].find(fits) ?? derived.at(-1)!;
  }
  /** A file key's repository-relative, decoded path; the root and the key are compared decoded, as `uri()` does. */
  private path(uri: string): string {
    const root = decoded(this.ctx.rootUri.replace(/\/$/, '')), key = decoded(uri);
    return root && key.startsWith(root + '/') ? key.slice(root.length + 1) : key;
  }
}
/** Characters a path may not hold: `%`, `?`, `#` and control characters. */
const UNSAFE = /[%?#\x00-\x1f]/;
/** A path `uri()` accepts: repository-relative, no backslash, `.`/`..` segment, scheme or UNSAFE character. */
function usablePath(path: string): boolean {
  return !!path && !path.startsWith('/') && !path.includes('\\') && !UNSAFE.test(path)
    && !path.split('/').some(p => p === '..' || p === '.') && !/^[a-z]+:/i.test(path);
}
/** A derived file name `uri()` accepts as one segment. */
function usableName(name: string): boolean {
  return !name.includes('/') && usablePath(`${name}.ttrm`);
}
function decoded(key: string): string {
  try { return decodeURIComponent(key); } catch { return key; }
}
function fileName(uri: string): string {
  return decoded(uri.split('/').at(-1)!);
}
