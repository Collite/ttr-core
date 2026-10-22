// SPDX-License-Identifier: Apache-2.0
import { parseString, type Definition, type Document, type EntityDef, type ObjectValue, type Reference } from '@tatrman/parser';
import { effectivePackage, ProjectSymbolTable, ReferenceIndex, Resolver, type SymbolEntry } from '@tatrman/semantics';
import type { SynthContext } from './types.js';
import { compareCodePoints } from '@tatrman/parser/layout';
import { Refusal } from './refusals.js';

export interface ModelDocument { uri: string; text: string; ast: Document; pkg: string; model: string; schema: string }
export interface IndexedNode { symbol: SymbolEntry; def: Definition; doc: ModelDocument }
export function targetOf(target: ObjectValue | Reference | undefined): { kind: string; path: string } | undefined {
  if (!target) return undefined;
  if (!('kind' in target)) return { kind: '', path: target.path };
  for (const entry of target.entries) if (entry.value.kind === 'id') return { kind: entry.key, path: entry.value.path };
  return undefined;
}
export class ModelIndex {
  readonly documents: ModelDocument[] = [];
  readonly symbols = new ProjectSymbolTable();
  readonly resolver: Resolver;
  readonly references = new ReferenceIndex();
  constructor(readonly ctx: SynthContext) {
    for (const [uri, text] of Object.entries(ctx.files).sort(([a], [b]) => compareCodePoints(a, b))) {
      if (!uri.endsWith('.ttrm')) continue;
      const parsed = parseString(text, uri);
      if (!parsed.ast || parsed.errors.some(e => e.severity === 'error')) throw new Refusal('text-unparseable', `Repair ${uri} before editing it.`);
      const ast = parsed.ast;
      const definitions = ast.definitions.map(d => `${d.kind}:${d.name}`);
      if (new Set(definitions).size !== definitions.length) throw new Refusal('ambiguous-reference', `${uri} repeats a definition name and kind.`);
      const doc = { uri, text, ast, pkg: effectivePackage(ast, uri, ctx.rootUri.startsWith('file://') ? new URL(ctx.rootUri).pathname : ctx.rootUri, ctx.manifest.packages), model: ast.modelDirective?.modelCode ?? '', schema: ast.modelDirective?.schema ?? '' };
      this.documents.push(doc);
      this.symbols.upsertDocument(uri, ast, doc.model, doc.schema, doc.pkg);
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
    if (direct.length > 1) throw new Refusal('ambiguous-reference', `${ref} has multiple declarations.`);
    let found = direct[0];
    if (!found) {
      const packages = scope ? [scope.pkg] : [...new Set(this.documents.map(d => d.pkg))];
      const hits = new Map<string, SymbolEntry>();
      for (const pkg of packages) {
        const res = this.resolver.resolveReference({ path: ref, parts: ref.split('.') }, { schemaCode: scope?.model ?? '', namespace: scope?.schema ?? '', imports: scope?.ast.imports ?? [], packageName: pkg });
        if (!res.resolved && res.reason === 'ambiguous') throw new Refusal('ambiguous-reference', `${ref} is ambiguous; qualify it.`);
        if (res.resolved) {
          if (this.symbols.getAll(res.symbol.qname).length > 1) throw new Refusal('ambiguous-reference', `${ref} has multiple declarations.`);
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
    if (def.binding?.kind === 'bareId') {
      const target = this.lookup(def.binding.id.path, ['table', 'query', 'view'], entity.doc);
      return target && { kind: target.def.kind, path: target.symbol.qname };
    }
    for (const d of this.documents) for (const b of d.ast.definitions) if (b.kind === 'er2dbEntity' && b.entity) {
      if (this.lookup(b.entity.path, ['entity'], d)?.symbol.qname === entity.symbol.qname) return targetOf(b.target);
    }
    return undefined;
  }
  uri(path: string): string {
    if (!path || path.startsWith('/') || path.includes('\\') || /[%?#\x00-\x1f]/.test(path) || path.split('/').some(p => p === '..' || p === '.') || /^[a-z]+:/i.test(path)) throw new Refusal('invalid-args', 'file must be a repository-relative path.');
    return this.ctx.rootUri ? this.ctx.rootUri.replace(/\/$/, '') + '/' + path : path;
  }
  packageFor(uri: string): string {
    return effectivePackage(parseString('', uri).ast!, uri, this.ctx.rootUri.startsWith('file://') ? new URL(this.ctx.rootUri).pathname : this.ctx.rootUri, this.ctx.manifest.packages);
  }
  bindingFile(pkg: string): string {
    const docs = this.documents.filter(d => d.pkg === pkg && d.model === 'binding');
    return docs.find(d => d.ast.definitions.some(b => b.kind === 'er2dbEntity'))?.uri ?? docs[0]?.uri ?? this.uri('model/binding/er2db.ttrm');
  }
  lexiconFile(lang: string, pkg: string, model: string, file?: string): string {
    if (file) return this.uri(`model/lexicon/${lang}/${file}`);
    const docs = this.documents.filter(d => d.pkg === pkg && d.model === 'lexicon' && d.ast.modelDirective?.locale === lang);
    return docs.find(d => d.ast.definitions.some(t => t.kind === 'term' && t.target?.parts.includes(model)))?.uri ?? docs[0]?.uri ?? this.uri(`model/lexicon/${lang}/${model}.ttrm`);
  }
}
