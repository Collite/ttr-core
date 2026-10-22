// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode, type PropertyValue, type SourceLocation } from '@tatrman/parser';
import type { CnDocument, CnDiagnostic } from '../cn/groundings.js';
import type { Resolver } from '../resolver.js';
import type { SymbolEntry } from '../symbol-table.js';

export function resolveMdErRef(resolver: Resolver, doc: CnDocument, path: string): SymbolEntry | undefined {
  const r = resolver.resolveReference({ path, parts: path.split('.') }, {
    schemaCode: doc.schemaCode, namespace: doc.namespace, packageName: doc.packageName, imports: doc.ast.imports,
  });
  return r.resolved ? r.symbol : undefined;
}

export function idPath(value?: PropertyValue): string | undefined {
  return value?.kind === 'id' ? value.parts.join('.') : undefined;
}

/** A direct attribute or exactly one outgoing relation named by the path's first segment. */
export function explicitErAttributes(docs: CnDocument[], resolver: Resolver, entity: SymbolEntry, path: string): SymbolEntry[] {
  const parts = path.split('.');
  if (parts.length === 1) {
    const attr = resolver.getSymbol(`${entity.qname}.${path}`);
    return attr?.kind === 'attribute' ? [attr] : [];
  }
  if (parts.length !== 2) return [];
  const found: SymbolEntry[] = [];
  for (const doc of docs) for (const def of doc.ast.definitions) {
    if (def.kind !== 'relation' || def.name !== parts[0]) continue;
    const from = idPath(def.from), to = idPath(def.to);
    if (!from || !to || resolveMdErRef(resolver, doc, from)?.qname !== entity.qname) continue;
    const target = resolveMdErRef(resolver, doc, to);
    const attr = target && resolver.getSymbol(`${target.qname}.${parts[1]}`);
    if (attr?.kind === 'attribute') found.push(attr);
  }
  // Keep relation count: two paths to the same attribute are still ambiguous.
  return found;
}

export function mdErBindingDiagnostics(docs: CnDocument[], resolver: Resolver): CnDiagnostic[] {
  const out: CnDiagnostic[] = [];
  const seen = new Set<string>();
  for (const doc of docs) for (const def of doc.ast.definitions) {
    if (def.kind !== 'md2erCubelet' && def.kind !== 'md2erDimension') continue;
    const report = (code: DiagnosticCode, message: string, source: SourceLocation = def.source) =>
      out.push({ code, message, source, uri: doc.uri, severity: 'error' });
    const entity = resolveMdErRef(resolver, doc, def.entity);
    if (entity?.kind !== 'entity') report(DiagnosticCode.MdMd2erReference, `Unresolved ER entity '${def.entity}'`);
    if (def.kind === 'md2erCubelet') {
      if (def.physicalProps?.length) report(DiagnosticCode.MdMd2erPhysicalProp, `md2er_cubelet is structural-only; remove: ${def.physicalProps.join(', ')}`);
      for (const [measure, attr] of Object.entries(def.measures ?? {})) {
        if (entity && !resolver.getSymbol(`${entity.qname}.${attr}`)) report(DiagnosticCode.MdMd2erMeasureUnknown, `Measure '${measure}' names unknown attribute '${attr}'`);
      }
      continue;
    }
    const dim = resolveMdErRef(resolver, doc, def.dimensionRef);
    if (dim?.kind !== 'dimension') report(DiagnosticCode.MdMd2erReference, `Unresolved MD dimension '${def.dimensionRef}'`);
    const logical = dim && docs.find(d => d.uri === dim.documentUri)?.ast.definitions.find(d => d.kind === 'dimension' && d.name === dim.name);
    if (logical?.kind === 'dimension' && logical.key && !(logical.key in def.attributes)) report(DiagnosticCode.MdMd2erDimensionKey, `md2er_dimension must map key '${logical.key}'`);
    if (dim) {
      if (seen.has(dim.qname)) report(DiagnosticCode.MdDuplicateBinding, `Duplicate md2er_dimension for '${def.dimensionRef}'`);
      seen.add(dim.qname);
    }
    if (entity?.kind !== 'entity') continue;
    for (const path of Object.values(def.attributes)) {
      const attrs = explicitErAttributes(docs, resolver, entity, path);
      if (attrs.length > 1) report(DiagnosticCode.MdMd2erPathAmbiguous, `ER attribute path '${path}' is ambiguous from '${def.entity}'`);
      if (attrs.length === 0) report(DiagnosticCode.MdMd2erReference, `Unresolved ER attribute path '${path}' from '${def.entity}'`);
    }
  }
  return out;
}
