// SPDX-License-Identifier: Apache-2.0
import type { CnValidation } from '../cn/validate.js';
import type { CnDocument } from '../cn/groundings.js';
import type { Resolver } from '../resolver.js';
import { mdEscape } from '../cn/hygiene.js';
import { resolveMdNs } from './explicit.js';
import { CompositeMdErHop, mdHopElements, groundedMdHopIssues, type MdHopIssue } from './hop.js';

export interface TableRole { entity: string; role: string; why: string[] }
export interface MdHopReport {
  elements: Array<{ element: string; erTarget: string | null; via: string | null; diagnostic: string | null }>;
  roles: TableRole[];
  issues: MdHopIssue[];
}
export function deriveTableRoles(hop: CompositeMdErHop): TableRole[] {
  const p = hop.derived.physical; const why = new Map<string, Map<string, Set<string>>>();
  const local = (key: string) => key;
  const mark = (entity: string, role: string, cause: string) => {
    const roles = why.get(entity) ?? new Map<string, Set<string>>(); const causes = roles.get(role) ?? new Set<string>();
    causes.add(cause); roles.set(role, causes); why.set(entity, roles);
  };
  for (const md of mdHopElements(p.docs, p.resolver)) {
    const er = hop.qualifiedTargetOf(md).erTarget; if (!er) continue;
    const logical = md.includes('.md.') ? 'md.' + md.slice(md.lastIndexOf('.md.') + 4) : md;
    if (logical.startsWith('md.cubelet.') && logical.split('.').length === 3) mark(er, 'fact', `cubelet ${md} (R1)`);
    if (logical.startsWith('md.dimension.') && logical.split('.').length === 3) mark(er, 'dimension', `dimension ${md} (R3)`);
  }
  const one = (value?: string) => value === '1' || value === '0..1';
  const many = (value?: string) => ['N', 'M', '*', '0..*', '1..*'].includes(value ?? '');
  for (const key of p.entities.keys()) {
    const er = local(key);
    const outgoing = p.relations.filter((r) => r.from === key && many(r.fromCardinality) && one(r.toCardinality));
    const measures = [...hop.derived.candidates].some(([md, attrs]) => (md.startsWith('md.measure.') || md.includes('.md.measure.')) && [...attrs].some((a) => a.startsWith(`${er}.`)));
    if (outgoing.length >= 2 && measures) mark(er, 'fact', 'measures and at least two many-to-one relations');
  }
  for (const rel of p.relations) if (why.get(local(rel.from))?.has('fact') && many(rel.fromCardinality) && one(rel.toCardinality)) mark(local(rel.to), 'dimension', `one side of ${local(rel.key)} from a fact`);
  for (const doc of p.docs) for (const binding of doc.ast.definitions) if (binding.kind === 'md2dbMap') {
    const map = resolveMdNs(p.resolver, doc, binding.mapRef, 'map');
    const logical = p.docs.find((d) => d.uri === map?.documentUri)?.ast.definitions.find((d) => d.kind === 'mdMap' && d.name === map?.name);
    if (logical?.kind !== 'mdMap' || logical.cardinality !== 'N:N') continue;
    const table = p.resolve(doc, binding.table); if (!table || !map) continue;
    for (const entity of p.tableToErEntity.get(table.qname) ?? []) mark(local(entity), 'bridge', `N:N map ${local(map.qname)}; flagged, not classified`);
  }
  return [...new Set([...p.entities.keys()].map(local))].sort().map((entity) => {
    const reasons = why.get(entity) ?? new Map<string, Set<string>>();
    return { entity, role: ['bridge', 'fact', 'dimension'].find((r) => reasons.has(r)) ?? 'unclassified', why: [...reasons.values()].flatMap((s) => [...s]).sort() };
  });
}
export function mdHopReport(docs: CnDocument[], resolver: Resolver, all = false, hop = new CompositeMdErHop(docs, resolver), validation?: CnValidation): MdHopReport {
  const elements = mdHopElements(docs, resolver).map((element) => {
    const a = hop.qualifiedTargetOf(element); return { element, erTarget: a.erTarget ?? null, via: a.via ?? null, diagnostic: a.slug ?? null };
  });
  const issues = all ? elements.flatMap((r) => hop.issues(r.element)).concat(hop.derived.lineageIssues) : groundedMdHopIssues(docs, resolver, hop, validation);
  const roles = deriveTableRoles(hop);
  for (const doc of docs) for (const def of doc.ast.definitions) if (def.kind === 'entity') {
    const sym = resolver.getSymbol(hop.derived.physical.key(doc, 'entity', def.name)); if (!sym) continue;
    const role = roles.find((r) => r.entity === sym.qname); if (!role) continue;
    const claims = (def.roles ?? []).map((v) => v.split('.').at(-1)!).filter((c) => ['fact', 'dimension', 'bridge'].includes(c));
    if (role.role !== 'unclassified' && claims.some((c) => c !== role.role)) issues.push({ element: role.entity, slug: 'md/role-claim-mismatch', severity: 'warning', message: `Claimed ${claims.join(', ')}; derived ${role.role}` });
  }
  const distinct = [...new Map(issues.map((i) => [JSON.stringify(i), i])).values()];
  distinct.sort((a, b) => a.element < b.element ? -1 : a.element > b.element ? 1 : a.slug < b.slug ? -1 : a.slug > b.slug ? 1 : a.message < b.message ? -1 : a.message > b.message ? 1 : 0);
  return { elements, roles, issues: distinct };
}
const escape = mdEscape;
export function renderMdHopMarkdown(r: MdHopReport): string {
  let out = '# MD → ER hop\n\n| MD element | ER landing | Rule / diagnostic |\n| --- | --- | --- |\n';
  for (const row of r.elements) out += `| ${escape(row.element)} | ${escape(row.erTarget ?? '—')} | ${escape(row.via ?? row.diagnostic ?? '—')} |\n`;
  out += '\n## Derived warehouse roles\n\n| ER entity | Role | Why |\n| --- | --- | --- |\n';
  for (const row of r.roles) out += `| ${escape(row.entity)} | ${row.role} | ${escape(row.why.join('; '))} |\n`;
  out += '\n## Findings\n\n';
  if (!r.issues.length) out += 'None.\n';
  for (const i of r.issues) out += `- ${i.severity}: ${escape(i.slug)} — ${escape(i.element)}: ${escape(i.message)}\n`;
  return out;
}
