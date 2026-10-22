// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode, type PropertyValue, type SourceLocation, type CubeletDef } from '@tatrman/parser';
import type { CnDocument, CnDiagnostic } from '../cn/groundings.js';
import { buildCanonicalKey } from '../qname.js';
import type { Resolver } from '../resolver.js';
import type { SymbolEntry } from '../symbol-table.js';

export function resolveMdErRef(resolver: Resolver, doc: CnDocument, path: string): SymbolEntry | undefined {
  if (!path) return undefined;
  const r = resolver.resolveReference({ path, parts: path.split('.') }, {
    schemaCode: doc.schemaCode, namespace: doc.namespace, packageName: doc.packageName, imports: doc.ast.imports,
  });
  return r.resolved ? r.symbol : undefined;
}

function mdPaths(path: string, ns: string): string[] {
  if (!path) return [];
  const parts = path.split('.');
  const canonical = parts[0] === 'md'
    ? parts[1] === ns ? path : ['md', ns, ...parts.slice(1)].join('.')
    : `md.${ns}.${path}`;
  return [...new Set([canonical, path])];
}

/** Resolve authored MD shorthand in its expected namespace, retaining only the requested kind. */
export function resolveMdNs(resolver: Resolver, doc: CnDocument, path: string, ns: 'dimension' | 'cubelet' | 'measure'): SymbolEntry | undefined {
  for (const candidate of mdPaths(path, ns)) {
    const symbol = resolveMdErRef(resolver, doc, candidate);
    if (symbol?.kind === ns && symbol.schemaCode === 'md') return symbol;
  }
  return undefined;
}

export function resolveMdAttribute(resolver: Resolver, doc: CnDocument, path: string): SymbolEntry | undefined {
  for (const candidate of mdPaths(path, 'dimension')) {
    const symbol = resolveMdErRef(resolver, doc, candidate);
    if (symbol?.kind === 'attribute' && symbol.schemaCode === 'md') return symbol;
  }
  return undefined;
}

export function idPath(value?: PropertyValue): string | undefined {
  return value?.kind === 'id' ? value.parts.join('.') : undefined;
}

interface RelationLanding { toEntityQname: string; packageName: string; qname: string }
export type MdErRelationIndex = Map<string, Map<string, RelationLanding[]>>;

/** Build once per validation/hop pass; endpoints resolve in the relation's own document. */
export function buildMdErRelationIndex(docs: CnDocument[], resolver: Resolver): MdErRelationIndex {
  const index: MdErRelationIndex = new Map();
  for (const doc of docs) for (const def of doc.ast.definitions) {
    if (def.kind !== 'relation') continue;
    const from = resolveMdErRef(resolver, doc, idPath(def.from) ?? '');
    const to = resolveMdErRef(resolver, doc, idPath(def.to) ?? '');
    if (from?.kind !== 'entity' || to?.kind !== 'entity') continue;
    const byName = index.get(from.qname) ?? new Map<string, RelationLanding[]>();
    const entries = byName.get(def.name) ?? [];
    entries.push({ toEntityQname: to.qname, packageName: doc.packageName,
      qname: buildCanonicalKey({ packageName: doc.packageName, schemaId: doc.namespace, kind: 'relation', parts: [def.name], declaringModel: doc.schemaCode }) });
    byName.set(def.name, entries);
    index.set(from.qname, byName);
  }
  return index;
}

/** A direct attribute or one visible named outgoing relation. Count paths, even to the same attribute. */
export function explicitErAttributes(index: MdErRelationIndex, bindingDoc: CnDocument, resolver: Resolver, entity: SymbolEntry, path: string, usedRelations?: Set<string>): SymbolEntry[] {
  const parts = path.split('.');
  if (parts.length === 1) {
    const attr = resolver.getSymbol(`${entity.qname}.${path}`);
    return attr?.kind === 'attribute' ? [attr] : [];
  }
  if (parts.length !== 2) return [];
  const found: SymbolEntry[] = [];
  for (const rel of index.get(entity.qname)?.get(parts[0]) ?? []) {
    const visible = rel.packageName === bindingDoc.packageName || bindingDoc.ast.imports.some(imp =>
      imp.wildcard ? imp.target.replace(/\.\*$/, '') === rel.packageName : imp.target === rel.qname);
    if (!visible) continue;
    const attr = resolver.getSymbol(`${rel.toEntityQname}.${parts[1]}`);
    if (attr?.kind === 'attribute') {
      found.push(attr);
      usedRelations?.add(rel.qname);
    }
  }
  return found;
}

/** Relations actually traversed by a dimension binding, also used by unused-import checks. */
export function mdErUsedRelations(index: MdErRelationIndex, doc: CnDocument, resolver: Resolver): Set<string> {
  const used = new Set<string>();
  for (const def of doc.ast.definitions) {
    if (def.kind !== 'md2erDimension') continue;
    const entity = resolveMdErRef(resolver, doc, def.entity);
    if (entity?.kind !== 'entity') continue;
    for (const path of Object.values(def.attributes)) explicitErAttributes(index, doc, resolver, entity, path, used);
  }
  return used;
}

export function cubeletMeasureKeys(docs: CnDocument[], resolver: Resolver, cubelet: SymbolEntry): Set<string> {
  const doc = docs.find(d => d.uri === cubelet.documentUri);
  const def = doc?.ast.definitions.find(d => d.kind === 'cubelet' && d.name === cubelet.name) as CubeletDef | undefined;
  const keys = new Set<string>();
  if (doc && def) for (const measure of def.measures) {
    if (typeof measure !== 'string') continue; // Inline measures have no project symbol.
    const symbol = resolveMdNs(resolver, doc, measure, 'measure');
    if (symbol) keys.add(symbol.qname);
  }
  return keys;
}

export function mdErBindingDiagnostics(docs: CnDocument[], resolver: Resolver): CnDiagnostic[] {
  const out: CnDiagnostic[] = [];
  const seen = new Set<string>();
  const relationIndex = buildMdErRelationIndex(docs, resolver);
  const byUri = new Map(docs.map(d => [d.uri, d]));
  for (const doc of docs) for (const def of doc.ast.definitions) {
    if (def.kind !== 'md2erCubelet' && def.kind !== 'md2erDimension') continue;
    const report = (code: DiagnosticCode, message: string, source: SourceLocation = def.source) =>
      out.push({ code, message, source, uri: doc.uri, severity: 'error' });
    const resolvedEntity = resolveMdErRef(resolver, doc, def.entity);
    const entity = resolvedEntity?.kind === 'entity' ? resolvedEntity : undefined;
    if (def.kind === 'md2erCubelet') {
      if (!entity) report(DiagnosticCode.MdMd2erReference, `Unresolved ER entity '${def.entity}'`);
      if (def.physicalProps?.length) report(DiagnosticCode.MdMd2erPhysicalProp, `md2er_cubelet is structural-only; remove: ${def.physicalProps.join(', ')}`);
      const cubelet = resolveMdNs(resolver, doc, def.cubeletRef, 'cubelet');
      if (!cubelet) report(DiagnosticCode.MdMd2erReference, def.cubeletRef ? `Unresolved MD cubelet '${def.cubeletRef}'` : 'md2er_cubelet needs `cubelet:`');
      const measures = cubelet ? cubeletMeasureKeys(docs, resolver, cubelet) : undefined;
      for (const [key, attr] of Object.entries(def.measures ?? {})) {
        const measure = resolveMdNs(resolver, doc, key, 'measure');
        if (!measure) report(DiagnosticCode.MdMd2erReference, `Unknown MD measure '${key}'`);
        else if (measures && !measures.has(measure.qname)) report(DiagnosticCode.MdMd2erReference, `'${key}' is not a measure of cubelet '${def.cubeletRef}'`);
        if (entity && resolver.getSymbol(`${entity.qname}.${attr}`)?.kind !== 'attribute') report(DiagnosticCode.MdMd2erMeasureUnknown, `Measure '${key}' names unknown attribute '${attr}'`);
      }
      for (const [key, attr] of Object.entries(def.attributes)) {
        if (!resolveMdAttribute(resolver, doc, key)) report(DiagnosticCode.MdMd2erReference, `Unknown MD attribute '${key}'`);
        if (entity && resolver.getSymbol(`${entity.qname}.${attr}`)?.kind !== 'attribute') report(DiagnosticCode.MdMd2erReference, `Unresolved ER attribute path '${attr}' from '${def.entity}'`);
      }
      continue;
    }
    const dim = resolveMdNs(resolver, doc, def.dimensionRef, 'dimension');
    if (!dim) report(DiagnosticCode.MdMd2erReference, `Unresolved MD dimension '${def.dimensionRef}'`);
    const logical = dim && byUri.get(dim.documentUri)?.ast.definitions.find(d => d.kind === 'dimension' && d.name === dim.name);
    if (logical?.kind === 'dimension' && logical.key && !Object.hasOwn(def.attributes, logical.key)) report(DiagnosticCode.MdMd2erDimensionKey, `md2er_dimension must map key '${logical.key}'`);
    if (dim && seen.has(dim.qname)) report(DiagnosticCode.MdDuplicateBinding, `Duplicate md2er_dimension for '${def.dimensionRef}'`);
    if (dim) seen.add(dim.qname);
    if (!entity) report(DiagnosticCode.MdMd2erReference, `Unresolved ER entity '${def.entity}'`);
    for (const [key, path] of Object.entries(def.attributes)) {
      if (dim && resolver.getSymbol(`${dim.qname}.${key}`)?.kind !== 'attribute') report(DiagnosticCode.MdMd2erReference, `'${key}' is not an attribute of '${def.dimensionRef}'`);
      if (!entity) continue;
      const attrs = explicitErAttributes(relationIndex, doc, resolver, entity, path);
      if (attrs.length > 1) report(DiagnosticCode.MdMd2erPathAmbiguous, `ER attribute path '${path}' is ambiguous from '${def.entity}'`);
      if (attrs.length === 0) report(DiagnosticCode.MdMd2erReference, `Unresolved ER attribute path '${path}' from '${def.entity}'`);
    }
  }
  return out;
}
