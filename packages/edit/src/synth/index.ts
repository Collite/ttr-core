// SPDX-License-Identifier: Apache-2.0
import { parseString, type AttributeDef, type Definition, type EntityDef, type Er2dbAttributeDef, type Er2dbEntityDef, type LexiconEntryDef, type RelationDef, type SourceLocation } from '@tatrman/parser';
import { isTtrId, compareCodePoints } from '@tatrman/parser/layout';
import type { CreateFile, TextDocumentEdit } from 'vscode-languageserver-types';
import { ModelIndex, type IndexedNode, type ModelDocument, type Written } from './model-index.js';
import { Fragment, location, position, printHeader, utf16Offset } from './print.js';
import { foreignKey } from './foreign-key.js';
import { addAttribute } from './attribute-edit.js';
import { queryOutput, type OutputColumn } from './query-output.js';
import { Refusal } from './refusals.js';
import type { EditArgs, EditArguments, EditOp, RefusalRef, SynthContext, SynthResult } from './types.js';

export type { EditArgs, EditArguments, EditOp, RefusalRef, SynthContext, SynthResult } from './types.js';
export type { RefusalCode } from './refusals.js';

class Synthesis {
  readonly index: ModelIndex;
  readonly texts = new Map<string, string>();
  /** The new files and the terms this run has written, which `index` (the files before the edit) does not show. */
  readonly written: Written = { files: new Map(), terms: [] };
  constructor(readonly ctx: SynthContext) { this.index = new ModelIndex(ctx); }
  text(uri: string): string { return this.texts.get(uri) ?? this.ctx.files[uri] ?? ''; }
  save(uri: string, text: string): void { this.index.assertWritable(uri); this.texts.set(uri, text); }
  /** New documents are explicit WorkspaceEdit CreateFile operations; existing bytes outside patches survive. */
  append(uri: string, pkg: string, model: string, definitions: Definition[], f: Fragment, locale?: string): void {
    if (!this.index.takes(uri, { pkg, model, locale }, this.written)) throw new Refusal('invalid-args', `Destination ${uri} has a different package/model/locale.`);
    const existing = this.index.documents.find(d => d.uri === uri);
    const names = new Set(existing?.ast.definitions.map(d => `${d.kind}:${d.name}`));
    for (const def of definitions) {
      if (names.has(`${def.kind}:${def.name}`)) throw new Refusal('invalid-args', `Destination already declares ${def.name}.`);
      names.add(`${def.kind}:${def.name}`);
    }
    const old = this.text(uri);
    const header = printHeader(pkg, model, locale);
    if (!old) this.written.files.set(uri, { pkg, model, locale });
    this.save(uri, (old || header).trimEnd() + '\n\n' + f.print(definitions));
  }
  require(ref: string, kinds: Parameters<ModelIndex['lookup']>[1], code: 'entity-unknown' | 'node-unknown' | 'source-unknown' | 'column-unknown' | 'target-unknown'): IndexedNode {
    const node = this.index.lookup(ref, kinds);
    if (!node) throw new Refusal(code, `${ref} is not loaded with the required kind.`);
    return node;
  }
  validateName(name: string): void { if (!isTtrId(name)) throw new Refusal('invalid-args', `Invalid TTR identifier ${JSON.stringify(name)}.`); }
  entityMapping(f: Fragment, name: string, entity: string, kind: string, target: string): Er2dbEntityDef {
    return { kind: 'er2dbEntity', name: `${name}_map`, source: location, entity: f.ref(entity), target: f.object({ [kind]: target }) };
  }
  attributeMapping(f: Fragment, name: string, attribute: string, column: string): Er2dbAttributeDef {
    return { kind: 'er2dbAttribute', name: `${name}_map`, source: location, attribute: f.ref(attribute), target: f.object({ column }) };
  }
  insert(args: EditArguments['insert-entity']): void {
    this.validateName(args.name);
    if (args.file && !args.file.endsWith('.ttrm')) throw new Refusal('invalid-args', 'Entity files must end in .ttrm.');
    if (args.from && args.on) throw new Refusal('invalid-args', 'Choose exactly one of from/on.');
    if (!args.from && !args.on && this.ctx.manifest.bindings.required) throw new Refusal('entity-needs-binding', 'Choose a table or a parameterless query.');
    const source = args.from ? this.require(args.from, ['table'], 'source-unknown') : args.on ? this.require(args.on, ['query'], 'source-unknown') : undefined;
    if (source?.def.kind === 'query' && source.def.parameters?.length) throw new Refusal('query-has-parameters', 'A stacked entity requires a parameterless query.');
    // A stacked entity lives beside the query it stacks on (hartland's LQ files hold both); otherwise the package's first er file.
    const erFile = args.on && source?.doc.model === 'er' ? source.doc : this.index.documents.find(d => d.model === 'er' && (!source || d.pkg === source.doc.pkg));
    let pkg = source?.doc.pkg ?? erFile?.pkg ?? this.ctx.manifest.packages.root;
    const uri = args.file ? this.index.uri(args.file) : erFile?.uri ?? this.index.uri(`model/er/${args.name}.ttrm`);
    if (!source && !erFile) pkg = this.index.packageFor(uri);
    const qname = [pkg, 'er.entity', args.name].filter(Boolean).join('.');
    if (this.index.symbols.get(qname)) throw new Refusal('entity-exists', `${qname} already exists.`);
    const columns: OutputColumn[] = source?.def.kind === 'query' ? queryOutput(this.index, source) : source?.def.kind === 'table' ? (source.def.columns ?? []).map(c => {
      if (!c.type) throw new Refusal('source-unknown', `Column ${c.name} has no type.`);
      return { name: c.name, type: c.type, isKey: c.isKey || source.def.kind === 'table' && source.def.primaryKey?.includes(c.name) || undefined, optional: c.optional };
    }) : [];
    const f = new Fragment();
    const attributes: AttributeDef[] = columns.map(c => ({ kind: 'attribute', name: c.name, source: location, type: f.type(c.type), ...(c.isKey ? { isKey: true } : {}), ...(c.optional ? { optional: true } : {}) }));
    this.append(uri, pkg, 'er', [{ kind: 'entity', name: args.name, source: location, ...(attributes.length ? { attributes } : {}) }], f);
    if (source) {
      const defs: Definition[] = [this.entityMapping(f, args.name, qname, args.on ? 'query' : 'table', source.symbol.qname)];
      if (args.from) for (const c of columns) defs.push(this.attributeMapping(f, `${args.name}_${c.name}`, `${qname}.${c.name}`, `${source.symbol.qname}.${c.name}`));
      this.append(this.index.bindingFile(pkg), pkg, 'binding', defs, f);
    }
    for (const lang of ['en', 'cs'] as const) if (args.terms?.[lang]) this.term({ lang, term: args.terms[lang], target: qname }, { qname, pkg, uri, model: 'er', name: args.name });
  }
  replace(node: IndexedNode, newText: string): void {
    const text = this.text(node.doc.uri);
    const start = utf16Offset(node.doc.text, node.def.source.offsetStart), end = utf16Offset(node.doc.text, node.def.source.offsetEnd);
    this.save(node.doc.uri, text.slice(0, start) + newText.trimEnd() + text.slice(end));
  }
  attribute(args: EditArguments['add-attribute']): void {
    this.validateName(args.name);
    const entity = this.require(args.entity, ['entity'], 'entity-unknown');
    const def = entity.def as EntityDef;
    if (def.attributes?.some(a => a.name === args.name)) throw new Refusal('attribute-exists', `${args.name} already exists.`);
    const binding = this.index.binding(entity);
    const boundTo = binding && this.index.lookup(binding.path, ['table', 'query', 'view'], entity.doc);
    if (binding?.kind === 'query' || boundTo?.def.kind === 'query') throw new Refusal('entity-is-query-backed', 'Edit the query with set-source; stacked attributes come from its outputs.');
    if (this.ctx.manifest.bindings.required && binding && !args.column) throw new Refusal('attribute-needs-binding', 'Choose a physical column.');
    const col = args.column ? this.require(args.column, ['column'], 'column-unknown') : undefined;
    if (col && boundTo && col.symbol.parent !== boundTo.symbol.qname) throw new Refusal('column-unknown', 'The column must belong to the entity’s bound table.');
    const f = new Fragment(entity.doc.text);
    const attribute: AttributeDef = { kind: 'attribute', name: args.name, type: f.type(args.type), source: location };
    this.save(entity.doc.uri, addAttribute(entity.doc.text, def, attribute, f));
    if (col) this.append(this.index.bindingFile(entity.doc.pkg), entity.doc.pkg, 'binding', [this.attributeMapping(f, `${def.name}_${args.name}`, `${entity.symbol.qname}.${args.name}`, col.symbol.qname)], f);
  }
  relation(args: EditArguments['add-relation']): void {
    const from = this.require(args.from, ['entity'], 'entity-unknown');
    const to = this.require(args.to, ['entity'], 'entity-unknown');
    const name = args.name ?? `rel_${from.def.name}_${to.def.name}`;
    this.validateName(name);
    const existing = this.index.symbols.all().filter(s => s.kind === 'relation' && s.packageName === from.doc.pkg).map(s => this.index.node(s));
    if (existing.some(n => n.def.name === name || n.def.kind === 'relation' && n.def.from?.kind === 'id' && n.def.to?.kind === 'id' && this.index.lookup(n.def.from.path, ['entity'], n.doc)?.symbol.qname === from.symbol.qname && this.index.lookup(n.def.to.path, ['entity'], n.doc)?.symbol.qname === to.symbol.qname)) throw new Refusal('relation-exists', 'This relation already exists.');
    const physicallyBound = (node: IndexedNode) => {
      const binding = this.index.binding(node);
      return binding && this.index.lookup(binding.path, ['table', 'view'], node.doc);
    };
    if (!args.fk && this.ctx.manifest.bindings.required && physicallyBound(from) && physicallyBound(to)) throw new Refusal('fk-needs-binding', 'Choose the physical foreign key.');
    const fk = args.fk ? foreignKey(this.index, args.fk, from, to) : undefined;
    const cardinalities = { 'one-to-many': ['1', '0..*'], 'many-to-one': ['0..*', '1'], 'one-to-one': ['0..1', '1'] } as const;
    if (!Object.hasOwn(cardinalities, args.kind)) throw new Refusal('invalid-args', 'Unsupported relation cardinality.');
    const pair = cardinalities[args.kind];
    const f = new Fragment();
    const def: RelationDef = { kind: 'relation', name, source: location, from: f.id(from.symbol.qname), to: f.id(to.symbol.qname), cardinality: f.object({ from: pair[0], to: pair[1] }, true) };
    if (fk) { const value = '{ fk: ' + fk.symbol.qname + ' }'; def.binding = { kind: 'block', fk: f.ref(fk.symbol.qname), source: f.span(value) }; }
    const uri = existing.find(n => n.def.kind === 'relation' && n.def.from?.kind === 'id' && this.index.lookup(n.def.from.path, ['entity'], n.doc)?.symbol.qname === from.symbol.qname)?.doc.uri ?? this.index.documents.find(d => d.pkg === from.doc.pkg && d.uri.endsWith('/relations.ttrm'))?.uri ?? this.index.uri('model/er/relations.ttrm');
    this.append(uri, from.doc.pkg, 'er', [def], f);
  }
  source(args: EditArguments['set-source']): void {
    this.index.assertComplete();
    const node = this.require(args.nodeRef, undefined, 'node-unknown');
    if (node.symbol.parent) throw new Refusal('invalid-args', 'set-source accepts top-level definitions.');
    const parsed = parseString(args.text, node.doc.uri);
    const errors = parsed.errors.filter(e => e.severity === 'error');
    if (!parsed.ast || errors.length || parsed.ast.definitions.length !== 1 || parsed.ast.modelDirective || parsed.ast.packageDecl || parsed.ast.imports.length || parsed.ast.securityBlocks.length || parsed.ast.graph) {
      const start = utf16Offset(node.doc.text, node.def.source.offsetStart);
      const end = utf16Offset(node.doc.text, node.def.source.offsetEnd);
      const candidate = node.doc.text.slice(0, start) + args.text + node.doc.text.slice(end);
      const refs = errors.map(e => this.ref(node.doc, { ...e.source, offsetStart: node.def.source.offsetStart + e.source.offsetStart, offsetEnd: node.def.source.offsetStart + e.source.offsetEnd }, 'parse-error', candidate));
      throw new Refusal('text-unparseable', errors.length ? errors.map(e => e.message).join('; ') : 'Provide exactly one parseable definition.', refs);
    }
    const def = parsed.ast.definitions[0];
    if (def.kind !== node.def.kind || def.name !== node.def.name)
      throw new Refusal('invalid-args', 'set-source keeps the definition kind and name; rename with textDocument/rename.');
    this.replace(node, args.text);
    if (this.text(node.doc.uri) === node.doc.text) return;
    const candidate = new ModelIndex({ ...this.ctx, files: { ...this.ctx.files, ...Object.fromEntries(this.texts) } });
    candidate.assertComplete();
    for (const symbol of this.index.symbols.all()) {
      if (symbol.documentUri !== node.doc.uri || symbol.qname === node.symbol.qname || symbol.qname.startsWith(node.symbol.qname + '.')) continue;
      if (!candidate.symbols.get(symbol.qname)) throw new Refusal('text-unparseable', 'The replacement would consume a neighboring definition.');
    }
    const refs: RefusalRef[] = [];
    for (const symbol of this.index.symbols.all()) {
      if (symbol.qname !== node.symbol.qname && !symbol.qname.startsWith(node.symbol.qname + '.')) continue;
      if (candidate.symbols.get(symbol.qname)) continue;
      for (const ref of this.index.references.findByQname(symbol.qname)) {
        // References inside the replaced definition are replaced with it.
        if (ref.documentUri === node.doc.uri && ref.source.offsetStart >= node.def.source.offsetStart && ref.source.offsetEnd <= node.def.source.offsetEnd) continue;
        const doc = this.index.documents.find(d => d.uri === ref.documentUri)!;
        refs.push(this.ref(doc, ref.source, ref.viaMapping ? 'binding' : 'reference'));
      }
    }
    if (refs.length) throw new Refusal('would-orphan', 'The replacement removes referenced members.', refs);
  }
  term(args: EditArguments['add-term'], inserted?: { qname: string; pkg: string; uri: string; model: string; name: string }): void {
    if (!['en', 'cs'].includes(args.lang) || !args.term.trim() || /[\x00-\x1f]/.test(args.term)) throw new Refusal('invalid-args', 'Term needs a supported locale and non-empty text.');
    const target = inserted ?? (() => { const n = this.require(args.target, undefined, 'target-unknown'); return { qname: n.symbol.qname, pkg: n.doc.pkg, uri: n.doc.uri, model: n.symbol.schemaCode, name: n.symbol.name }; })();
    for (const d of this.index.documents) if (d.model === 'lexicon' && d.ast.modelDirective?.locale === args.lang) for (const t of d.ast.definitions) if (t.kind === 'term' && t.forms?.includes(args.term)) {
      if (t.target && this.index.lookup(t.target.path, undefined, d)?.symbol.qname === target.qname) return;
      throw new Refusal('term-exists', `The term ${args.term} already targets another definition.`);
    }
    const uri = this.index.lexiconFile(args.lang, target, args.file, this.written);
    if (!uri.endsWith('.ttrm')) throw new Refusal('invalid-args', 'Lexicon files must end in .ttrm.');
    const used = new Set(this.index.documents.filter(d => d.model === 'lexicon' && d.pkg === target.pkg).flatMap(d => d.ast.definitions.map(t => t.name)));
    const base = target.name.replace(/[^a-zA-Z0-9_]/g, '_') + '_' + args.lang;
    let name = base, i = 2;
    while (used.has(name)) name = `${base}_${i++}`;
    this.validateName(name);
    const f = new Fragment();
    const def: LexiconEntryDef = { kind: 'term', name, source: location, target: f.ref(target.qname), forms: [args.term] };
    this.append(uri, target.pkg, 'lexicon', [def], f, args.lang);
    this.written.terms.push({ uri, qname: target.qname });
  }
  ref(doc: ModelDocument, span: SourceLocation, kind: string, text = doc.text): RefusalRef {
    return { uri: doc.uri, kind, range: { start: position(text, utf16Offset(text, span.offsetStart)), end: position(text, utf16Offset(text, span.offsetEnd)) } };
  }
  remove(args: EditArguments['remove-definition']): void {
    this.index.assertComplete();
    const node = this.require(args.qname, undefined, 'node-unknown');
    if (node.symbol.parent) throw new Refusal('invalid-args', 'remove-definition accepts top-level definitions.');
    const removals = new Map<string, { doc: ModelDocument; span: SourceLocation; kind: string }>();
    const queue = [node];
    const add = (doc: ModelDocument, span: SourceLocation, kind: string) => removals.set(`${doc.uri}:${span.offsetStart}:${span.offsetEnd}`, { doc, span, kind });
    add(node.doc, node.def.source, 'definition');
    const refs: RefusalRef[] = [];
    const visited = new Set<string>();
    const removedNames = new Set<string>();
    while (queue.length) {
      const current = queue.shift()!;
      if (visited.has(current.symbol.qname)) continue;
      visited.add(current.symbol.qname);
      removedNames.add(current.def.name);
      const symbols = this.index.symbols.all().filter(s => s.qname === current.symbol.qname || s.qname.startsWith(current.symbol.qname + '.'));
      for (const symbol of symbols) for (const ref of this.index.references.findByQname(symbol.qname)) {
        const doc = this.index.documents.find(d => d.uri === ref.documentUri)!;
        const owner = doc.ast.definitions.find(d => d.source.offsetStart <= ref.source.offsetStart && d.source.offsetEnd >= ref.source.offsetEnd);
        if (!owner || (doc.uri === node.doc.uri && owner === node.def)) continue;
        const kind = ref.viaMapping || owner.kind.startsWith('er2db') ? 'binding' : ['term', 'pattern', 'example'].includes(owner.kind) ? 'lexicon' : owner.kind;
        if (!refs.some(r => r.uri === doc.uri && r.range.start.line === this.ref(doc, ref.source, kind).range.start.line && r.kind === kind)) refs.push(this.ref(doc, ref.source, kind));
        // Remove the smallest inline binding containing the reference, preserving its carrier.
        const binding = owner.kind === 'entity'
          ? owner.attributes?.find(a => a.binding && a.binding.source.offsetStart <= ref.source.offsetStart && a.binding.source.offsetEnd >= ref.source.offsetEnd)?.binding ?? owner.binding
          : owner.kind === 'relation' ? owner.binding : undefined;
        if (binding && ref.viaMapping) {
          const start = utf16Offset(doc.text, binding.source.offsetStart);
          const prefix = doc.text.slice(0, start);
          const key = /binding\s*:\s*$/.exec(prefix);
          if (!key) throw new Refusal('would-orphan', 'Cannot safely remove the inline binding.', refs);
          add(doc, { ...binding.source, offsetStart: [...prefix.slice(0, key.index)].length }, kind);
        } else {
          add(doc, owner.source, kind);
          const entry = this.index.symbols.all().find(s => s.documentUri === doc.uri && s.name === owner.name && s.source.offsetStart === owner.source.offsetStart);
          if (entry) queue.push(this.index.node(entry));
        }
      }
    }
    // Detection is broad; only an entire single-entry roster line is safe to remove.
    const rosters = [...removedNames].map(name => {
      const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      return { hit: new RegExp("'" + escaped + "'"), pure: new RegExp("^\\s*'" + escaped + "'\\s*,?\\s*(?://[^\\r\\n]*)?(?:\\r?\\n)?$") };
    });
    for (const [uri, text] of Object.entries(this.ctx.files)) if (/(?:^|\/)model\/tests\/.*\.mjs$/.test(uri)) {
      let start = 0;
      for (const line of text.split(/(?<=\n)/)) {
        const matches = rosters.filter(roster => roster.hit.test(line));
        if (matches.length) {
          const span = { ...location, offsetStart: [...text.slice(0, start)].length, offsetEnd: [...text.slice(0, start + line.length)].length };
          const doc = { ...node.doc, uri, text };
          const kind = matches.every(roster => roster.pure.test(line)) ? 'roster' : 'roster-manual';
          refs.push(this.ref(doc, span, kind));
          if (kind === 'roster') add(doc, span, kind);
        }
        start += line.length;
      }
    }
    refs.sort((a, b) => compareCodePoints(a.uri, b.uri) || a.range.start.line - b.range.start.line);
    if (refs.some(ref => ref.kind === 'roster-manual')) throw new Refusal('would-orphan', 'Edit manual JS references before removing this definition, even with force.', refs);
    if (refs.length && !args.force) throw new Refusal('would-orphan', `${node.symbol.qname} still has references.`, refs);
    const byFile = new Map<string, [number, number][]>();
    for (const { doc, span, kind } of removals.values()) {
      let start = utf16Offset(doc.text, span.offsetStart), end = utf16Offset(doc.text, span.offsetEnd);
      if (kind !== 'roster') {
        const lineStart = doc.text.lastIndexOf('\n', start - 1) + 1;
        if (!doc.text.slice(lineStart, start).trim()) start = lineStart;
        if (doc.text[end] === ',') end++;
        if (doc.text[end] === '\r') end++;
        if (doc.text[end] === '\n') end++;
        // Consume one blank separator without splitting CRLF.
        if (doc.text.startsWith('\r\n', end)) end += 2;
        else if (doc.text[end] === '\n') end++;
      }
      const spans = byFile.get(doc.uri) ?? []; spans.push([start, end]); byFile.set(doc.uri, spans);
    }
    for (const [uri, spans] of byFile) {
      const merged: [number, number][] = [];
      for (const [start, end] of spans.sort((a, b) => a[0] - b[0])) {
        const last = merged.at(-1);
        if (last && start <= last[1]) last[1] = Math.max(last[1], end); else merged.push([start, end]);
      }
      let text = this.ctx.files[uri];
      for (const [start, end] of merged.reverse()) text = text.slice(0, start) + text.slice(end);
      this.save(uri, text.trimEnd() + '\n');
    }
  }
  result(): SynthResult {
    const documentChanges: (TextDocumentEdit | CreateFile)[] = [];
    const touched: string[] = [];
    for (const [uri, output] of [...this.texts].sort(([a], [b]) => compareCodePoints(a, b))) {
      const before = this.ctx.files[uri] ?? '';
      const eolInputs = before ? [before] : Object.values(this.ctx.files);
      const crlf = eolInputs.some(text => text.includes('\r\n')) && eolInputs.every(text => !/(?<!\r)\n/.test(text));
      const after = crlf ? output.replace(/\r?\n/g, '\r\n') : output;
      if (before === after) continue;
      if (uri.endsWith('.ttrm') && parseString(after, uri).errors.some(e => e.severity === 'error')) throw new Refusal('text-unparseable', `The operation would leave ${uri} unparseable.`);
      // One minimal replacement per file, avoiding conflicting same-position insertions.
      let start = 0;
      while (start < before.length && start < after.length && before[start] === after[start]) start++;
      // Never split a UTF-16 surrogate pair in a WorkspaceEdit.
      if (start && /[\uD800-\uDBFF]/.test(before[start - 1] ?? after[start - 1])) start--;
      if (start > 0 && before[start - 1] === '\r' && before[start] === '\n') start--;
      let end = before.length, afterEnd = after.length;
      while (end > start && afterEnd > start && before[end - 1] === after[afterEnd - 1]) { end--; afterEnd--; }
      if (end > start && /[\uD800-\uDBFF]/.test(before[end - 1])) { end++; afterEnd++; }
      if (end > start && before[end - 1] === '\r' && before[end] === '\n') { end++; afterEnd++; }
      if (!(uri in this.ctx.files)) documentChanges.push({ kind: 'create', uri });
      documentChanges.push({ textDocument: { uri, version: null }, edits: [{ range: { start: position(before, start), end: position(before, end) }, newText: after.slice(start, afterEnd) }] });
      touched.push(uri);
    }
    return { ok: true, edit: { documentChanges }, touched, diagnostics: [] };
  }
}

/** Pure: creates/refuses a WorkspaceEdit without mutating a source snapshot or touching I/O. */
export function synthesize<K extends EditOp>(op: K, args: EditArguments[K], ctx: SynthContext): SynthResult;
export function synthesize(op: EditOp, args: EditArgs, ctx: SynthContext): SynthResult {
  try {
    const s = new Synthesis(ctx);
    switch (op) {
      case 'insert-entity': s.insert(args as EditArguments['insert-entity']); break;
      case 'add-attribute': s.attribute(args as EditArguments['add-attribute']); break;
      case 'add-relation': s.relation(args as EditArguments['add-relation']); break;
      case 'set-source': s.source(args as EditArguments['set-source']); break;
      case 'remove-definition': s.remove(args as EditArguments['remove-definition']); break;
      case 'add-term': s.term(args as EditArguments['add-term']); break;
      default: throw new Refusal('invalid-args', `Unknown edit operation ${op}.`);
    }
    return s.result();
  } catch (error) {
    if (error instanceof Refusal) return error.result();
    throw error;
  }
}
