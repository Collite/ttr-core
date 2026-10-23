// SPDX-License-Identifier: Apache-2.0
import type { Definition, DimensionDef, EntityDef, FkDef, ObjectValue, PropertyValue, QueryDef, Reference } from '@tatrman/parser';
import type { CnValidation } from '../cn/validate.js';
import { cnHopIssues } from '../cn/hygiene.js';
import type { CnDocument } from '../cn/groundings.js';
import { ExplicitMdErHop, elementRefOf, type HopAnswer } from '../cn/hygiene.js';
import { columnRefFromColumnValue } from '../mapping-references.js';
import { buildCanonicalKey } from '../qname.js';
import type { Resolver } from '../resolver.js';
import { cubeletMeasureKeys, resolveMdAttribute, resolveMdErRef, resolveMdNs } from './explicit.js';

export interface MdHopIssue { element: string; slug: string; severity: 'warning' | 'info'; message: string }
export interface MdErHop { erTargetOf(md: string, bearer?: string): HopAnswer }
const parent = (key: string) => key.slice(0, key.lastIndexOf('.'));
export const entityOf = (key: string) => { const parts = key.split('.'); const i = parts.findIndex((p, n) => p === 'er' && parts[n + 1] === 'entity'); return i < 0 ? undefined : parts.slice(0, i + 3).join('.'); };
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
  // ModelReconciler constructs DbSchema with this namespace; ErModelHandle uses the same one.
  readonly defaultDbNamespace = 'dbo';
  private readonly namedImports = new Map<string, Set<string>>();
  isVisible(doc: CnDocument, element: string): boolean {
    const sym = this.resolver.getSymbol(element); if (!sym) return false;
    if (!sym.packageName || sym.packageName === doc.packageName) return true;
    if (doc.ast.imports.some((imp) => imp.wildcard && imp.target.replace(/\.\*$/, '') === sym.packageName)) return true;
    let named = this.namedImports.get(doc.uri);
    if (!named) {
      named = new Set(doc.ast.imports.filter((imp) => !imp.wildcard).map((imp) => this.resolver.getSymbol(imp.target)?.qname).filter((key): key is string => key !== undefined));
      this.namedImports.set(doc.uri, named);
    }
    return named.has(sym.qname) || sym.parent !== undefined && named.has(sym.parent);
  }
  entitiesOn(doc: CnDocument, table: string): Set<string> { return new Set([...this.tableToErEntity.get(table) ?? []].filter((e) => this.isVisible(doc, e))); }
  attributesOn(doc: CnDocument, column?: string): Set<string> { return new Set([...this.columnToErAttr.get(column ?? '') ?? []].filter((a) => this.isVisible(doc, a))); }
  readonly attributeColumns = new Map<string, string>();
  boundColumn(attr: string) { return this.attributeColumns.get(attr) ?? attr.split('.').at(-1)!; }
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
    for (const [key, { doc, def }] of this.entities) if (!this.entityToTable.has(key) && !this.queryEntities.has(key)) {
      const table = this.resolve(doc, `db.${this.defaultDbNamespace}.${def.name}`);
      if (table?.kind === 'table' && elementRefOf(table) === `db.${this.defaultDbNamespace}.${def.name}`) { add(this.tableToErEntity, table.qname, [key]); add(this.entityToTable, key, [table.qname]); }
    }
    for (const [key, { doc, def }] of this.entities) {
      const columns = def.binding?.kind === 'block' ? def.binding.columns ?? [] : [];
      for (const attr of def.attributes ?? []) {
        const value = columns.find((e) => e.name === attr.name)?.value;
        let path = value && columnRefFromColumnValue(value)?.path;
        if (attr.binding?.kind === 'bareId') path = attr.binding.id.path;
        else if (attr.binding?.kind === 'block') path = targetRef(attr.binding.target);
        path ??= attr.name;
        this.attributeColumns.set(`${key}.${attr.name}`, path.split('.').at(-1)!);
        for (const table of this.entityToTable.get(key) ?? []) {
          const col = this.column(doc, table, path); if (col) this.addOrigin(col, `${key}.${attr.name}`);
        }
      }
    }
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'er2dbAttribute') {
      const attr = def.attribute && this.resolve(doc, def.attribute.path); const path = targetRef(def.target);
      if (attr?.kind !== 'attribute' || !path) continue;
      this.attributeColumns.set(attr.qname, path.split('.').at(-1)!);
      for (const previous of this.erAttrToColumn.get(attr.qname) ?? []) this.columnToErAttr.get(previous)?.delete(attr.qname);
      this.erAttrToColumn.delete(attr.qname);
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
          if (v?.kind === 'id') { const col = this.resolve(fk.doc, v.path); return col?.kind === 'column' ? [col.qname] : []; }
          if (v?.kind === 'object') {
            const table = this.resolve(fk.doc, id(v.entries.find((e) => e.key === 'table')?.value) ?? '');
            const names = v.entries.find((e) => e.key === 'columns')?.value;
            if (!table || names?.kind !== 'list') return [];
            const cols = names.items.map((x) => this.column(fk.doc, table.qname, id(x) ?? ''));
            return cols.every((x) => x !== undefined) ? cols as string[] : [];
          }
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
    try { return this.originsOf(query, this.physical.boundColumn(key)); } finally { this.visiting.delete(key); }
  }
  originsOf(query: string, column: string): Set<string> | undefined {
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
    let selected: number | undefined;
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
      if (selected === undefined) {
        const matches = items.map((item, i) => {
          const as = item.findIndex((t) => t.toUpperCase() === 'AS');
          const label = as >= 0 ? item[as + 1] : item.at(-1);
          return label && unquote(label).toLowerCase() === column.toLowerCase() ? i : -1;
        }).filter((i) => i >= 0);
        if (matches.length !== 1) return undefined;
        selected = matches[0];
      }
      let expr = items[selected]; if (!expr) return undefined;
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
  private local(key: string) { return key; }
  private add(md: string, values: Iterable<string>) { add(this.candidates, md, values); }
  private columns(doc: CnDocument, md: string, column?: string, entities?: Set<string>) {
    if (!column) { this.causes.set(md, 'no md2db column'); return; }
    const attrs = [...this.physical.attributesOn(doc, column)].filter((a) => !entities || entities.has(parent(a)));
    if (!attrs.length) this.causes.set(md, `no ${this.physical.columnToErAttr.get(column)?.size ? 'visible ' : ''}er2db for column '${column}'`);
    this.add(md, attrs.map((a) => this.local(a)));
  }
  constructor(readonly docs: CnDocument[], readonly resolver: Resolver, physical?: PhysicalIndex) {
    const p = this.physical = physical ?? new PhysicalIndex(docs, resolver);
    const lineage = new SyntacticQueryLineage(p);
    for (const [entity, query] of p.queryEntities) (p.entities.get(entity)!.def.attributes ?? []).forEach((a) => {
      const origins = lineage.originsOf(query, p.boundColumn(`${entity}.${a.name}`));
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
    const mapTargets = new Map<string, Set<string>>();
    for (const doc of docs) for (const binding of doc.ast.definitions) if (binding.kind === 'md2dbMap') {
      const map = resolveMdNs(resolver, doc, binding.mapRef, 'map');
      const mapDoc = docs.find((d) => d.uri === map?.documentUri);
      const logical = mapDoc?.ast.definitions.find((d) => d.kind === 'mdMap' && d.name === map?.name);
      const table = p.resolve(doc, binding.table);
      if (!map || !mapDoc || logical?.kind !== 'mdMap' || !table) continue;
      const targets = logical.to.map((d) => resolveMdNs(resolver, mapDoc, d, 'domain')?.qname);
      for (const [name, column] of Object.entries(binding.columns)) if (targets.includes(resolveMdNs(resolver, doc, name, 'domain')?.qname)) add(mapTargets, map.qname, p.attributesOn(doc, p.column(doc, table.qname, column)));
    }
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'md2dbCubelet') {
      const cubelet = resolveMdNs(resolver, doc, def.cubeletRef, 'cubelet'); const table = p.resolve(doc, def.table);
      if (!cubelet || !table) continue;
      const md = cubelet.qname; const entities = p.entitiesOn(doc, table.qname);
      this.add(md, [...entities].map((e) => this.local(e)));
      if (!entities.size) this.causes.set(md, `no ${p.tableToErEntity.get(table.qname)?.size ? 'visible ' : ''}er2db for table '${table.qname}'`);
      const measures = cubeletMeasureKeys(docs, resolver, cubelet);
      for (const [name, binding] of Object.entries(def.measures)) {
        const m = resolveMdNs(resolver, doc, name, 'measure'); if (!m || !measures.has(m.qname)) continue;
        const col = 'column' in binding ? binding.column : def.shape.shape === 'long' ? def.shape.valueColumn : undefined;
        this.columns(doc, m.qname, col && p.column(doc, table.qname, col));
      }
      for (const [name, binding] of Object.entries(def.attributes)) {
        const attr = resolveMdAttribute(resolver, doc, name); if (!attr) continue;
        const from = 'via' in binding ? p.resolve(doc, binding.from.table) : undefined;
        const col = 'column' in binding ? p.column(doc, table.qname, binding.column) : from && p.column(doc, from.qname, binding.from.column);
        const grain = `${md}.${elementRefOf(attr).replace(/^md.dimension\./, '')}`;
        if ('via' in binding) {
          this.columns(doc, grain, col);
          const map = resolveMdNs(resolver, doc, binding.via, 'map');
          const targets = mapTargets.get(map?.qname ?? '');
          if (targets) {
            this.candidates.set(grain, new Set([...targets].filter((a) => p.isVisible(doc, a))));
            if (!this.candidates.get(grain)?.size) this.causes.set(grain, 'no visible er2db for via map target');
          }
        } else this.columns(doc, grain, col, entities);
        const dim = dimensions.get(attr.parent ?? ''); if (dim?.def.key !== attr.name) continue;
        const erAttrs = p.attributesOn(doc, col);
        for (const rel of p.relations) if (entities.has(rel.from) && p.isVisible(doc, rel.key) && p.isVisible(doc, rel.to) && rel.pairs.some(([a]) => erAttrs.has(a))) {
          this.add(this.local(attr.parent!), [this.local(rel.to)]);
          this.add(attr.qname, rel.pairs.filter(([a, b]) => erAttrs.has(a) && p.isVisible(doc, b)).map(([, b]) => this.local(b)));
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
            this.add(this.local(parent(attr)), [...p.entitiesOn(doc, table.qname)].map((e) => this.local(e)));
            this.columns(doc, this.local(attr), p.column(doc, table.qname, col));
          }
          if (to.includes(domain.qname) && !isKey) this.columns(doc, this.local(attr), p.column(doc, table.qname, col));
        }
      }
    }
    const bound = new Set<string>();
    for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'md2dbDomain') {
      const domain = resolveMdNs(resolver, doc, def.domainRef, 'domain'); const table = p.resolve(doc, def.source_.table);
      if (!domain || !table) continue;
      bound.add(domain.qname); const col = p.column(doc, table.qname, def.source_.column);
      this.columns(doc, domain.qname, col);
      for (const attr of domainAttrs.get(domain.qname) ?? []) {
        this.columns(doc, this.local(attr), col);
        if (dimensions.get(parent(attr))?.def.key === attr.split('.').at(-1)) this.add(this.local(parent(attr)), [...p.entitiesOn(doc, table.qname)].map((e) => this.local(e)));
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
    const answer = this.qualifiedTargetOf(md, bearer);
    const sym = answer.erTarget && this.resolver.getSymbol(answer.erTarget);
    return sym ? { ...answer, erTarget: elementRefOf(sym) } : answer;
  }
  qualifiedTargetOf(md: string, bearer?: string, allowed?: Set<string>): HopAnswer {
    const found = [...allowed ?? this.candidates.get(md) ?? []].sort();
    if (bearer) {
      const restricted = found.filter((e) => entityOf(e) === bearer || (() => { const s = this.resolver.getSymbol(entityOf(e) ?? ''); return !this.resolver.getSymbol(bearer) && s && elementRefOf(s) === bearer; })());
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
  constructor(docs: CnDocument[], resolver: Resolver) { this.explicit = new ExplicitMdErHop(docs, resolver, true); this.derived = new DerivedMdErHop(docs, resolver); }
  erTargetOf(md: string, bearer?: string): HopAnswer {
    const answer = this.qualifiedTargetOf(md, bearer);
    const sym = answer.erTarget && this.derived.resolver.getSymbol(answer.erTarget);
    return sym ? { ...answer, erTarget: elementRefOf(sym) } : answer;
  }
  private dimensionPin(md: string): string | undefined {
    const attr = this.derived.resolver.getSymbol(md);
    const dimension = attr?.kind === 'attribute' ? this.derived.resolver.getSymbol(attr.parent ?? '') : undefined;
    if (dimension?.kind !== 'dimension') return undefined;
    return this.explicit.erTargetOf(dimension.qname).erTarget;
  }
  private pinnedEntities(md: string): Set<string> | undefined {
    const pin = this.dimensionPin(md); if (!pin) return undefined;
    const reached = new Set([pin]); let size: number;
    do {
      size = reached.size;
      for (const rel of this.derived.physical.relations) {
        if (reached.has(rel.from) && ['1', '0..1', '1..1'].includes(rel.toCardinality ?? '')) reached.add(rel.to);
        if (reached.has(rel.to) && ['1', '0..1', '1..1'].includes(rel.fromCardinality ?? '')) reached.add(rel.from);
      }
    } while (size !== reached.size);
    return reached;
  }
  private attributeCandidates(md: string): Set<string> | undefined {
    const reached = this.pinnedEntities(md); if (!reached) return undefined;
    const found = new Set([...this.derived.candidates.get(md) ?? []].filter((e) => reached.has(entityOf(e) ?? '')));
    if (found.size) return found;
    const sym = this.derived.resolver.getSymbol(md); const owner = this.derived.resolver.getSymbol(sym?.parent ?? '');
    const dimension = this.derived.docs.find((d) => d.uri === owner?.documentUri)?.ast.definitions.find((d) => d.kind === 'dimension' && d.name === owner?.name);
    if (dimension?.kind === 'dimension' && dimension.key === sym?.name) for (const er of reached) for (const attr of this.derived.physical.entities.get(er)?.def.attributes ?? []) if (attr.isKey) found.add(`${er}.${attr.name}`);
    return found;
  }
  qualifiedTargetOf(md: string, bearer?: string): HopAnswer {
    const pinned = this.explicit.erTargetOf(md, bearer);
    if (pinned.slug !== 'md/er-unmapped') return { ...pinned, ...(pinned.erTarget ? { via: 'explicit' as const } : {}) };
    const allowed = this.attributeCandidates(md);
    const answer = this.derived.qualifiedTargetOf(md, bearer, allowed);
    return answer.slug === 'md/er-unmapped' && allowed && this.derived.candidates.get(md)?.size ? { ...answer, message: `'${md}' has no er counterpart — the dimension pin to '${this.dimensionPin(md)}' excludes every derived landing` } : answer;
  }
  issues(md: string, bearer?: string): MdHopIssue[] {
    const answer = this.qualifiedTargetOf(md, bearer); const out: MdHopIssue[] = [];
    if (answer.slug) out.push({ element: md, slug: answer.slug, severity: 'warning', message: answer.message });
    const found = [...this.derived.candidates.get(md) ?? []].sort();
    if (answer.via === 'explicit' && found.length && (found.length !== 1 || found[0] !== answer.erTarget)) out.push({ element: md, slug: 'md/er-hop-override', severity: 'info', message: `Explicit '${answer.erTarget}' overrides derived ${found.join(', ')}` });
    const reached = this.pinnedEntities(md);
    const unreachable = found.filter((e) => reached && !reached.has(entityOf(e) ?? ''));
    if (unreachable.length) out.push({ element: md, slug: 'md/er-hop-override', severity: 'info', message: `Dimension pin excludes derived ${unreachable.join(', ')}` });
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

export function groundedMdHopIssues(docs: CnDocument[], resolver: Resolver, hop: CompositeMdErHop, validation?: CnValidation): MdHopIssue[] {
  return cnHopIssues(docs, resolver, hop, validation);
}
