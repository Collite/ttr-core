// SPDX-License-Identifier: Apache-2.0
import type { Definition, DimensionDef, EntityDef, FkDef, ObjectValue, PropertyValue, QueryDef, Reference } from '@tatrman/parser';
import { validateCn } from '../cn/validate.js';
import type { CnDocument } from '../cn/groundings.js';
import { ExplicitMdErHop, elementRefOf, type HopAnswer } from '../cn/hygiene.js';
import { buildCanonicalKey } from '../qname.js';
import type { Resolver } from '../resolver.js';
import { cubeletMeasureKeys, resolveMdAttribute, resolveMdErRef, resolveMdNs } from './explicit.js';

export interface MdHopIssue { element: string; slug: string; severity: 'warning' | 'info'; message: string }
export interface MdErHop { erTargetOf(md: string, bearer?: string): HopAnswer }
const parent = (key: string) => key.slice(0, key.lastIndexOf('.'));
const entityOf = (key: string) => key.startsWith('er.entity.') ? key.split('.').slice(0, 3).join('.') : undefined;
const id = (v: PropertyValue | undefined): string | undefined => v?.kind === 'id' ? v.path : undefined;
const scalar = (v: PropertyValue | undefined): string | undefined => v?.kind === 'string' ? v.value : v?.kind === 'number' ? String(v.value) : id(v);
function targetRef(target: ObjectValue | Reference | undefined): string | undefined {
  if (!target) return undefined;
  if ('path' in target) return target.path;
  return ['table', 'view', 'query', 'column'].map((key) => id(target.entries.find((e) => e.key === key)?.value)).find((x) => x !== undefined);
}
function add(index: Map<string, Set<string>>, key: string, values: Iterable<string>) {
  const set = index.get(key) ?? new Set<string>();
  for (const value of values) set.add(value);
  index.set(key, set);
}
export interface PhysicalRelation { key: string; from: string; to: string; pairs: Array<[string, string]>; fromCardinality?: string; toCardinality?: string }
/** Canonical physical symbols, including package and table/view kind. */
export class PhysicalIndex {
  readonly columnToErAttr = new Map<string, Set<string>>();
  readonly tableToErEntity = new Map<string, Set<string>>();
  readonly erAttrToColumn = new Map<string, Set<string>>();
  readonly entityToTable = new Map<string, Set<string>>();
  readonly entities = new Map<string, { doc: CnDocument; def: EntityDef }>();
  readonly queries = new Map<string, { doc: CnDocument; def: QueryDef }>();
  readonly queryEntities = new Map<string, string>();
  readonly relations: PhysicalRelation[] = [];
  resolve(doc: CnDocument, path: string) { return resolveMdErRef(this.resolver, doc, path); }
  key(doc: CnDocument, kind: Definition['kind'], name: string) {
    return buildCanonicalKey({ packageName: doc.packageName, schemaId: doc.namespace, kind, parts: [name], declaringModel: doc.schemaCode });
  }
  column(doc: CnDocument, table: string, name: string): string | undefined {
    const sym = this.resolver.getSymbol(`${table}.${name}`) ?? this.resolve(doc, name);
    return sym?.kind === 'column' && sym.parent === table ? sym.qname : undefined;
  }
  addOrigin(column: string, attribute: string) {
    add(this.columnToErAttr, column, [attribute]); add(this.erAttrToColumn, attribute, [column]);
  }
  private bindEntity(entity: string, target: ObjectValue | Reference | undefined, doc: CnDocument) {
    const path = targetRef(target); const sym = path && this.resolve(doc, path);
    if (!sym) return;
    if (sym.kind === 'table' || sym.kind === 'view') {
      add(this.tableToErEntity, sym.qname, [entity]); add(this.entityToTable, entity, [sym.qname]);
    } else if (sym.kind === 'query' && sym.schemaCode === 'er') this.queryEntities.set(entity, sym.qname);
  }
  constructor(readonly docs: CnDocument[], readonly resolver: Resolver) {
    const fks = new Map<string, { doc: CnDocument; def: FkDef }>();
    for (const doc of docs) for (const def of doc.ast.definitions) {
      if (def.kind === 'entity') {
        const key = this.key(doc, 'entity', def.name); this.entities.set(key, { doc, def });
        this.bindEntity(key, def.binding?.kind === 'block' ? def.binding.target : undefined, doc);
      } else if (def.kind === 'query') this.queries.set(this.key(doc, 'query', def.name), { doc, def });
      else if (def.kind === 'fk') fks.set(this.key(doc, 'fk', def.name), { doc, def });
    }
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'er2dbEntity') {
      const sym = def.entity && this.resolve(doc, def.entity.path);
      if (sym?.kind === 'entity') this.bindEntity(sym.qname, def.target, doc);
    }
    for (const [key, { doc, def }] of this.entities) {
      const columns = def.binding?.kind === 'block' ? def.binding.columns ?? [] : [];
      for (const attr of def.attributes ?? []) {
        const value = columns.find((e) => e.name === attr.name)?.value;
        let path = value?.kind === 'bareId' ? value.id.path : value?.kind === 'object' ? id(value.object.entries.find((e) => e.key === 'target' || e.key === 'column')?.value) : undefined;
        if (attr.binding?.kind === 'bareId') path = attr.binding.id.path;
        else if (attr.binding?.kind === 'block') path = targetRef(attr.binding.target);
        if (path) for (const table of this.entityToTable.get(key) ?? []) {
          const col = this.column(doc, table, path); if (col) this.addOrigin(col, `${key}.${attr.name}`);
        }
      }
    }
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'er2dbAttribute') {
      const attr = def.attribute && this.resolve(doc, def.attribute.path); const path = targetRef(def.target);
      if (attr?.kind !== 'attribute' || !path) continue;
      const col = this.resolve(doc, path);
      if (col?.kind === 'column') this.addOrigin(col.qname, attr.qname);
      else for (const table of this.entityToTable.get(attr.parent ?? '') ?? []) {
        const c = this.column(doc, table, path); if (c) this.addOrigin(c, attr.qname);
      }
    }
    const relationFks = new Map<string, string>();
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'er2dbRelation') {
      const rel = def.relation && this.resolve(doc, def.relation.path); const fk = def.fk && this.resolve(doc, def.fk.path);
      if (rel && fk) relationFks.set(rel.qname, fk.qname);
    }
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'relation') {
      const from = this.resolve(doc, id(def.from) ?? ''); const to = this.resolve(doc, id(def.to) ?? '');
      if (from?.kind !== 'entity' || to?.kind !== 'entity') continue;
      const key = this.key(doc, 'relation', def.name);
      const pairs: Array<[string, string]> = [];
      for (const item of def.join?.items ?? []) if (item.kind === 'object') {
        const a = this.resolve(doc, id(item.entries.find((e) => e.key === 'from')?.value) ?? '');
        const b = this.resolve(doc, id(item.entries.find((e) => e.key === 'to')?.value) ?? '');
        if (a?.parent === from.qname && b?.parent === to.qname) pairs.push([a.qname, b.qname]);
      }
      const binding = def.binding;
      const fkPath = binding?.kind === 'bareId' ? binding.id.path : binding?.fk?.path;
      const fk = fks.get((fkPath && this.resolve(doc, fkPath)?.qname) || relationFks.get(key) || '');
      if (fk) {
        const cols = (v: PropertyValue | undefined): string[] => {
          if (v?.kind !== 'list') return [];
          const resolved = v.items.map((x) => this.resolve(fk.doc, id(x) ?? ''));
          return resolved.every((x) => x?.kind === 'column') ? resolved.map((x) => x!.qname) : [];
        };
        const left = cols(fk.def.from); const right = cols(fk.def.to);
        if (left.length === right.length) left.forEach((a, i) => {
          for (const src of this.columnToErAttr.get(a) ?? []) for (const dst of this.columnToErAttr.get(right[i]) ?? []) {
            if (parent(src) === from.qname && parent(dst) === to.qname) pairs.push([src, dst]);
          }
        });
      }
      this.relations.push({ key, from: from.qname, to: to.qname, pairs,
        fromCardinality: scalar(def.cardinality?.entries.find((e) => e.key === 'from')?.value),
        toCardinality: scalar(def.cardinality?.entries.find((e) => e.key === 'to')?.value) });
    }
  }
}

/** Conservative syntactic lineage. Unsupported expressions remain opaque. Kotlin can install a Calcite provider. */
export class SyntacticQueryLineage {
  private readonly visiting = new Set<string>();
  constructor(readonly physical: PhysicalIndex) {}
  private attributeOrigins(entity: string, column: string): Set<string> | undefined {
    const entry = this.physical.entities.get(entity);
    const ordinal = entry?.def.attributes?.findIndex((a) => a.name.toLowerCase() === column.toLowerCase()) ?? -1;
    if (!entry || ordinal < 0) return undefined;
    const name = entry.def.attributes![ordinal].name; const key = `${entity}.${name}`;
    const direct = this.physical.erAttrToColumn.get(key); if (direct) return direct;
    const query = this.physical.queryEntities.get(entity);
    if (!query || this.visiting.has(key)) return undefined;
    this.visiting.add(key);
    try { return this.originsOf(query, ordinal); } finally { this.visiting.delete(key); }
  }
  originsOf(query: string, ordinal: number): Set<string> | undefined {
    const entry = this.physical.queries.get(query); const sql = entry?.def.sourceText?.value;
    if (!entry || sql === undefined) return undefined;
    const pattern = /\s+|--[^\n]*|\/\*[\s\S]*?\*\/|'(?:[^']|'')*'|"(?:[^"]|"")*"|`(?:[^`]|``)*`|[A-Za-z_][A-Za-z_0-9]*|[0-9]+(?:\.[0-9]+)?|[.,;()+*/=<>-]/gy;
    const tokens: string[] = []; let end = 0; let m: RegExpExecArray | null;
    while ((m = pattern.exec(sql))) {
      end = pattern.lastIndex;
      if (!/^\s|^--|^\/\*/.test(m[0])) tokens.push(m[0]);
    }
    if (end !== sql.length) return undefined;
    const branches: string[][] = [[]];
    for (let i = 0; i < tokens.length; i++) {
      if (tokens[i].toUpperCase() === 'UNION') {
        if (tokens[++i]?.toUpperCase() !== 'ALL') return undefined;
        branches.push([]);
      } else branches.at(-1)!.push(tokens[i]);
    }
    const unquote = (x: string) => /^["`]/.test(x) ? x.slice(1, -1) : x;
    const identifier = (x: string) => /^[A-Za-z_][A-Za-z_0-9]*$|^"(?:[^"]|"")+"$|^`(?:[^`]|``)+`$/.test(x);
    const out = new Set<string>();
    for (const b of branches) {
      if (b[0]?.toUpperCase() !== 'SELECT') return undefined;
      const from = b.findIndex((t) => t.toUpperCase() === 'FROM'); if (from < 0) return undefined;
      const tail = b.slice(from + 1).filter((t) => t !== ';');
      const stop = tail.findIndex((t) => t.toUpperCase() === 'WHERE'); const sourceTokens = stop < 0 ? tail : tail.slice(0, stop);
      if (!sourceTokens.length) return undefined;
      let n = 1; let source = unquote(sourceTokens[0]);
      while (sourceTokens[n] === '.' && sourceTokens[n + 1] !== undefined) { source += `.${unquote(sourceTokens[n + 1])}`; n += 2; }
      const rest = sourceTokens.slice(n);
      const alias = !rest.length ? source.split('.').at(-1)! : rest.length === 1 && identifier(rest[0]) ? unquote(rest[0]) : rest.length === 2 && rest[0].toUpperCase() === 'AS' && identifier(rest[1]) ? unquote(rest[1]) : undefined;
      if (!alias) return undefined;
      const entity = this.physical.resolve(entry.doc, source.startsWith('er.') ? source : `er.entity.${source}`);
      if (entity?.kind !== 'entity') return undefined;
      const items: string[][] = [[]];
      for (const t of b.slice(1, from)) { if (t === ',') items.push([]); else items.at(-1)!.push(t); }
      let expr = items[ordinal]; if (!expr) return undefined;
      const as = expr.findIndex((t) => t.toUpperCase() === 'AS');
      if (as >= 0) { if (as !== expr.length - 2 || !identifier(expr.at(-1)!)) return undefined; expr = expr.slice(0, as); }
      else if (expr.length >= 2 && identifier(expr.at(-1)!) && expr.at(-2) !== '.') expr = expr.slice(0, -1);
      if (expr.length === 1 && (/^'|^[0-9]/.test(expr[0]) || ['NULL', 'TRUE', 'FALSE'].includes(expr[0].toUpperCase()))) continue;
      const attr = expr.length === 1 && identifier(expr[0]) ? unquote(expr[0]) : expr.length === 3 && expr[1] === '.' && unquote(expr[0]).toLowerCase() === alias.toLowerCase() && identifier(expr[2]) ? unquote(expr[2]) : undefined;
      if (!attr) return undefined;
      const origins = this.attributeOrigins(entity.qname, attr); if (!origins) return undefined;
      for (const col of origins) out.add(col);
    }
    return out;
  }
}

export class DerivedMdErHop implements MdErHop {
  readonly candidates = new Map<string, Set<string>>();
  readonly causes = new Map<string, string>();
  readonly lineageIssues: MdHopIssue[] = [];
  readonly physical: PhysicalIndex;
  private local(key: string) { const sym = this.resolver.getSymbol(key); return sym ? elementRefOf(sym) : key; }
  private add(md: string, values: Iterable<string>) { add(this.candidates, md, values); }
  private columns(md: string, column?: string, entities?: Set<string>) {
    if (!column) { this.causes.set(md, 'no md2db column'); return; }
    const attrs = [...this.physical.columnToErAttr.get(column) ?? []].filter((a) => !entities || entities.has(parent(a)));
    if (!attrs.length) this.causes.set(md, `no er2db for column '${column}'`);
    this.add(md, attrs.map((a) => this.local(a)));
  }
  constructor(readonly docs: CnDocument[], readonly resolver: Resolver, physical?: PhysicalIndex) {
    const p = this.physical = physical ?? new PhysicalIndex(docs, resolver);
    const lineage = new SyntacticQueryLineage(p);
    for (const [entity, query] of p.queryEntities) (p.entities.get(entity)!.def.attributes ?? []).forEach((a, i) => {
      const origins = lineage.originsOf(query, i);
      if (origins === undefined) this.lineageIssues.push({ element: `${this.local(entity)}.${a.name}`, slug: 'md/er-lineage-opaque', severity: 'info', message: `Column '${a.name}' of '${query}' has opaque lineage` });
      else for (const col of origins) p.addOrigin(col, `${entity}.${a.name}`);
    });
    const dimensions = new Map<string, { doc: CnDocument; def: DimensionDef }>();
    const domainAttrs = new Map<string, Set<string>>();
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'dimension') {
      const key = p.key(doc, 'dimension', def.name); dimensions.set(key, { doc, def });
      for (const attr of def.attributes ?? []) {
        const domain = attr.domainRef && resolveMdNs(resolver, doc, attr.domainRef, 'domain');
        if (domain) add(domainAttrs, domain.qname, [`${key}.${attr.name}`]);
      }
    }
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'md2dbCubelet') {
      const cubelet = resolveMdNs(resolver, doc, def.cubeletRef, 'cubelet'); const table = p.resolve(doc, def.table);
      if (!cubelet || !table) continue;
      const md = elementRefOf(cubelet); const entities = p.tableToErEntity.get(table.qname) ?? new Set<string>();
      this.add(md, [...entities].map((e) => this.local(e)));
      if (!entities.size) this.causes.set(md, `no er2db for table '${table.qname}'`);
      const measures = cubeletMeasureKeys(docs, resolver, cubelet);
      for (const [name, binding] of Object.entries(def.measures)) {
        const m = resolveMdNs(resolver, doc, name, 'measure'); if (!m || !measures.has(m.qname)) continue;
        const col = 'column' in binding ? binding.column : def.shape.shape === 'long' ? def.shape.valueColumn : undefined;
        this.columns(elementRefOf(m), col && p.column(doc, table.qname, col));
      }
      for (const [name, binding] of Object.entries(def.attributes)) {
        const attr = resolveMdAttribute(resolver, doc, name); if (!attr) continue;
        const from = 'via' in binding ? p.resolve(doc, binding.from.table) : undefined;
        const col = 'column' in binding ? p.column(doc, table.qname, binding.column) : from && p.column(doc, from.qname, binding.from.column);
        this.columns(`${md}.${elementRefOf(attr).replace(/^md.dimension\./, '')}`, col, entities);
        const dim = dimensions.get(attr.parent ?? ''); if (dim?.def.key !== attr.name) continue;
        const erAttrs = p.columnToErAttr.get(col ?? '') ?? new Set<string>();
        for (const rel of p.relations) if (entities.has(rel.from) && rel.pairs.some(([a]) => erAttrs.has(a))) {
          this.add(this.local(attr.parent!), [this.local(rel.to)]);
          this.add(elementRefOf(attr), rel.pairs.filter(([a]) => erAttrs.has(a)).map(([, b]) => this.local(b)));
        }
      }
    }
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'md2dbMap') {
      const map = resolveMdNs(resolver, doc, def.mapRef, 'map'); const table = p.resolve(doc, def.table);
      const mdDoc = docs.find((d) => d.uri === map?.documentUri);
      const logical = mdDoc?.ast.definitions.find((d) => d.kind === 'mdMap' && d.name === map?.name);
      if (logical?.kind !== 'mdMap' || !mdDoc || !table || logical.cardinality === 'N:N') continue;
      const from = logical.from.map((s) => resolveMdNs(resolver, mdDoc, s, 'domain')?.qname);
      const to = logical.to.map((s) => resolveMdNs(resolver, mdDoc, s, 'domain')?.qname);
      for (const [name, col] of Object.entries(def.columns)) {
        const domain = resolveMdNs(resolver, doc, name, 'domain'); if (!domain) continue;
        for (const attr of domainAttrs.get(domain.qname) ?? []) {
          const dim = dimensions.get(parent(attr)); if (!dim) continue;
          const isKey = dim.def.key === attr.split('.').at(-1);
          if (from.includes(domain.qname) && isKey) {
            this.add(this.local(parent(attr)), [...p.tableToErEntity.get(table.qname) ?? []].map((e) => this.local(e)));
            this.columns(this.local(attr), p.column(doc, table.qname, col));
          }
          if (to.includes(domain.qname) && !isKey) this.columns(this.local(attr), p.column(doc, table.qname, col));
        }
      }
    }
    const bound = new Set<string>();
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'md2dbDomain') {
      const domain = resolveMdNs(resolver, doc, def.domainRef, 'domain'); const table = p.resolve(doc, def.source_.table);
      if (!domain || !table) continue;
      bound.add(domain.qname); const col = p.column(doc, table.qname, def.source_.column);
      this.columns(elementRefOf(domain), col);
      for (const attr of domainAttrs.get(domain.qname) ?? []) {
        this.columns(this.local(attr), col);
        if (dimensions.get(parent(attr))?.def.key === attr.split('.').at(-1)) this.add(this.local(parent(attr)), [...p.tableToErEntity.get(table.qname) ?? []].map((e) => this.local(e)));
      }
    }
    for (const [key, { def }] of dimensions) {
      if (!def.key) continue;
      const md = this.local(key);
      if (!this.candidates.get(`${md}.${def.key}`)?.size) for (const entity of this.candidates.get(md) ?? []) {
        const er = [...p.entities].find(([k]) => this.local(k) === entity);
        this.add(`${md}.${def.key}`, (er?.[1].def.attributes ?? []).filter((a) => a.isKey).map((a) => `${entity}.${a.name}`));
      }
      if (!this.candidates.get(md)?.size) this.causes.set(md, "no relation or map from the dimension's key to an er entity");
    }
    for (const [domain, attrs] of domainAttrs) if (!bound.has(domain)) this.add(this.local(domain), [...attrs].flatMap((a) => [...this.candidates.get(this.local(a)) ?? []]));
  }
  erTargetOf(md: string, bearer?: string): HopAnswer {
    const found = [...this.candidates.get(md) ?? []].sort();
    if (bearer) {
      const restricted = found.filter((e) => entityOf(e) === bearer);
      if (restricted.length === 1) return { erTarget: restricted[0], via: 'derived-bearer', message: '' };
      if (restricted.length > 1) return { slug: 'md/er-ambiguous', message: `'${md}' maps to ${restricted.join(', ')}; author md2er_* to pick` };
    }
    return found.length === 1 ? { erTarget: found[0], via: 'derived', message: '' } : found.length ? { slug: 'md/er-ambiguous', message: `'${md}' maps to ${found.join(', ')}; author md2er_cubelet/md2er_dimension to pick` } : { slug: 'md/er-unmapped', message: `'${md}' has no er counterpart — ${this.causes.get(md) ?? 'no md2db binding for the element'}` };
  }
  mdElementsOn(er: string) { return [...this.candidates].filter(([, set]) => set.has(er)).map(([md]) => md).sort(); }
}

export class CompositeMdErHop implements MdErHop {
  readonly explicit: ExplicitMdErHop;
  readonly derived: DerivedMdErHop;
  constructor(docs: CnDocument[], resolver: Resolver, physical?: PhysicalIndex, private readonly allowScopes = true) { this.explicit = new ExplicitMdErHop(docs, resolver); this.derived = new DerivedMdErHop(docs, resolver, physical); }
  private readonly scopes = new Map<string, CompositeMdErHop>();
  private scoped(md: string): { hop: CompositeMdErHop; local: string } | undefined {
    if (!this.allowScopes) return undefined;
    let sym = this.derived.resolver.getSymbol(md);
    if (!sym) {
      let prefix = md;
      while (prefix.includes('.')) {
        prefix = prefix.slice(0, prefix.lastIndexOf('.'));
        const parent = this.derived.resolver.getSymbol(prefix);
        if (parent?.schemaCode === 'md' && parent.kind === 'cubelet') { sym = parent; break; }
      }
    }
    if (sym?.schemaCode !== 'md') return undefined;
    let hop = this.scopes.get(sym.packageName);
    if (!hop) {
      const resolver = this.derived.resolver;
      const docs = this.derived.docs.map((doc) => ({ ...doc, ast: { ...doc.ast, definitions: doc.ast.definitions.filter((def) => {
        if (def.kind === 'md2dbCubelet' || def.kind === 'md2erCubelet') return resolveMdNs(resolver, doc, def.cubeletRef, 'cubelet')?.packageName === sym.packageName;
        if (def.kind === 'md2erDimension') return resolveMdNs(resolver, doc, def.dimensionRef, 'dimension')?.packageName === sym.packageName;
        if (def.kind === 'md2dbDomain') return resolveMdNs(resolver, doc, def.domainRef, 'domain')?.packageName === sym.packageName;
        if (def.kind === 'md2dbMap') return resolveMdNs(resolver, doc, def.mapRef, 'map')?.packageName === sym.packageName;
        return !['dimension', 'mdDomain', 'cubelet', 'mdMap', 'measure'].includes(def.kind) || doc.packageName === sym.packageName;
      }) } }));
      hop = new CompositeMdErHop(docs, resolver, this.derived.physical, false);
      this.scopes.set(sym.packageName, hop);
    }
    return { hop, local: elementRefOf(sym) + md.slice(sym.qname.length) };
  }
  erTargetOf(md: string, bearer?: string): HopAnswer {
    const scope = this.scoped(md); if (scope) return scope.hop.erTargetOf(scope.local, bearer);
    const pinned = this.explicit.erTargetOf(md, bearer);
    return pinned.slug !== 'md/er-unmapped' ? { ...pinned, ...(pinned.erTarget ? { via: 'explicit' as const } : {}) } : this.derived.erTargetOf(md, bearer);
  }
  issues(md: string, bearer?: string): MdHopIssue[] {
    const scope = this.scoped(md);
    if (scope) return scope.hop.issues(scope.local, bearer).map((i) => i.element === scope.local ? { ...i, element: md } : i);
    const answer = this.erTargetOf(md, bearer); const out: MdHopIssue[] = [];
    if (answer.slug) out.push({ element: md, slug: answer.slug, severity: 'warning', message: answer.message });
    const found = [...this.derived.candidates.get(md) ?? []].sort();
    if (answer.via === 'explicit' && found.length && (found.length !== 1 || found[0] !== answer.erTarget)) out.push({ element: md, slug: 'md/er-hop-override', severity: 'info', message: `Explicit '${answer.erTarget}' overrides derived ${found.join(', ')}` });
    out.push(...this.derived.lineageIssues.filter((i) => i.element === answer.erTarget || (bearer !== undefined && entityOf(i.element) === bearer)));
    return out;
  }
}

export function mdHopElements(docs: CnDocument[], resolver: Resolver): string[] {
  const out = new Set<string>();
  for (const doc of docs) for (const def of doc.ast.definitions) {
    if (!['dimension', 'mdDomain', 'measure', 'cubelet'].includes(def.kind)) continue;
    const key = buildCanonicalKey({ packageName: doc.packageName, schemaId: doc.namespace, kind: def.kind, parts: [def.name], declaringModel: doc.schemaCode });
    const sym = resolver.getSymbol(key); if (!sym) continue;
    const ref = sym.qname; out.add(ref);
    if (def.kind === 'dimension') for (const attr of def.attributes ?? []) out.add(`${ref}.${attr.name}`);
    if (def.kind === 'cubelet') for (const grain of def.grain) {
      const attr = resolveMdAttribute(resolver, doc, grain); if (attr) out.add(`${ref}.${elementRefOf(attr).replace(/^md.dimension\./, '')}`);
    }
  }
  return [...out].sort();
}

export function groundedMdHopIssues(docs: CnDocument[], resolver: Resolver, hop: CompositeMdErHop): MdHopIssue[] {
  const validation = validateCn(docs, resolver); const visiting = new Set<string>();
  function bearer(key: string): string | undefined {
    const prop = validation.concepts.get(key)?.properties.find((s) => s.property.key === 'of');
    if (prop?.property.kind !== 'entry' || prop.property.value.kind !== 'id') return undefined;
    const doc = prop.doc;
    const value = prop.property.value;
    const r = resolver.resolveReference({ path: value.path, parts: value.parts, cnContext: true }, { schemaCode: doc.schemaCode, namespace: doc.namespace, packageName: doc.packageName, imports: doc.ast.imports });
    return r.resolved ? r.symbol.qname : undefined;
  }
  function landing(key: string): string | undefined {
    if (visiting.has(key)) return undefined;
    visiting.add(key);
    try {
      const target = validation.groundings.byConcept.get(key)?.[0]?.targetSymbol; const of = bearer(key);
      if (target?.schemaCode === 'er') return elementRefOf(target);
      if (target?.schemaCode === 'md') return hop.erTargetOf(target.qname, of ? entityOf(landing(of) ?? '') : undefined).erTarget;
      return of ? landing(of) : undefined;
    } finally { visiting.delete(key); }
  }
  return validation.groundings.groundings.flatMap((g) => {
    if (g.targetSymbol?.schemaCode !== 'md') return [];
    const of = g.conceptKey && bearer(g.conceptKey);
    return hop.issues(g.targetSymbol.qname, of ? entityOf(landing(of) ?? '') : undefined);
  });
}
