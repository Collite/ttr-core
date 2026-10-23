// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { parseString } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';
import type { CnDocument } from '../cn/groundings.js';
import { CompositeMdErHop, DerivedMdErHop } from '../md/hop.js';
import { mdHopReport, renderMdHopMarkdown } from '../md/report.js';
import { cnHygieneReport } from '../cn/hygiene.js';

const ROOT = join(__dirname, '../../../kotlin/ttr-metadata/src/test/resources/md-hop');
export function mdHopProject(extra: Record<string, string> = {}) {
  const sources = Object.fromEntries(readdirSync(ROOT).filter((f) => f.endsWith('.ttrm')).sort().map((f) => [f, readFileSync(join(ROOT, f), 'utf8')]));
  const symbols = new ProjectSymbolTable();
  const docs: CnDocument[] = Object.entries({ ...sources, ...extra }).map(([uri, text]) => {
    const p = parseString(text, uri);
    expect(p.errors.filter((e) => e.severity === 'error')).toEqual([]);
    const ast = p.ast!;
    const doc = { uri, ast, schemaCode: ast.modelDirective?.modelCode ?? '', namespace: ast.modelDirective?.schema ?? '', packageName: ast.packageDecl?.name ?? '' };
    symbols.upsertDocument(uri, ast, doc.schemaCode, doc.namespace, doc.packageName);
    return doc;
  });
  return { docs, resolver: new Resolver(symbols) };
}

describe('MD/ER derived hop, the Kotlin fixture', () => {
  it('matches the Kotlin whole-model report and role goldens byte for byte', () => {
    const p = mdHopProject(); const report = mdHopReport(p.docs, p.resolver, true);
    const golden = join(__dirname, '../../../../tests/conformance/md-hop');
    expect(JSON.stringify(report, null, 2) + '\n').toBe(readFileSync(join(golden, 'expected.json'), 'utf8'));
    expect(renderMdHopMarkdown(report)).toBe(readFileSync(join(golden, 'expected.md'), 'utf8'));
    expect(mdHopReport(p.docs, p.resolver).issues).toEqual([]);
  });
  it('matches derived, ambiguous and overridden CN hygiene on the Kotlin golden', () => {
    const root = join(__dirname, '../../../../tests/conformance/cn-hygiene');
    const expected = JSON.parse(readFileSync(join(root, 'expected-derived.json'), 'utf8'));
    for (const [name, variant] of [['derived', null], ['conflict', 'conflicting.ttrm.variant'], ['override', 'override.ttrm.variant']] as const) {
      const p = mdHopProject(variant ? { 'variant.ttrm': readFileSync(join(ROOT, variant), 'utf8') } : {});
      const report = cnHygieneReport(p.docs, p.resolver);
      expect(report).toEqual(expected[name]);
    }
  });
  it('compares deprecated role claims with derived roles', () => {
    const text = readFileSync(join(ROOT, 'er.ttrm'), 'utf8').replace('def entity customer {', 'def entity customer { roles: [fact],');
    const p = mdHopProject({ 'er.ttrm': text });
    expect(mdHopReport(p.docs, p.resolver).issues.filter((i) => i.slug === 'md/role-claim-mismatch').map((i) => i.element)).toEqual(['er.entity.customer']);
  });

  it('derives every landing, respects the bearer and retains the grain attribute identity', () => {
    const { docs, resolver } = mdHopProject();
    const hop = new DerivedMdErHop(docs, resolver);
    for (const row of readFileSync(join(ROOT, 'landings.tsv'), 'utf8').trim().split('\n')) {
      const [md, er] = row.split('\t');
      expect(hop.erTargetOf(md), md).toEqual({ erTarget: er, via: 'derived', message: '' });
    }
    expect(hop.erTargetOf('md.measure.revenue').slug).toBe('md/er-ambiguous');
    expect(hop.erTargetOf('md.measure.revenue', 'er.entity.sales')).toEqual({ erTarget: 'er.entity.sales.ext_sales_price', via: 'derived-bearer', message: '' });
    expect(hop.erTargetOf('md.cubelet.storeSales.Customer.customerCode').erTarget).toBe('er.entity.store_sales.customer');
    expect(hop.erTargetOf('md.cubelet.orphan').message).toContain('no er2db');
    const report = cnHygieneReport(docs, resolver);
    expect(report.concepts.find((c) => c.concept === 'cn.revenue')?.landsOn).toBe('er.entity.sales.ext_sales_price');
  });
  it('isolates identically named MD elements in separate packages', () => {
    const extra: Record<string, string> = {};
    for (const pkg of ['east', 'west']) for (const f of readdirSync(ROOT).filter((f) => f.endsWith('.ttrm'))) {
      const source = readFileSync(join(ROOT, f), 'utf8').replace(/er\.entity\.customer(?=[.,\s])/g, `er.entity.${pkg}_customer`).replace('def entity customer {', `def entity ${pkg}_customer {`);
      extra[`${pkg}/${f}`] = `package ${pkg}\n${source}`;
    }
    const p = mdHopProject(extra); const report = cnHygieneReport(p.docs, p.resolver);
    expect(new CompositeMdErHop(p.docs, p.resolver).erTargetOf('east.md.cubelet.storeSales.Customer.customerCode').erTarget).toBe('er.entity.store_sales.customer');
    for (const pkg of ['east', 'west']) expect(report.concepts.find((c) => c.package === pkg && c.concept === 'cn.Customer')?.landsOn).toBe(`er.entity.${pkg}_customer`);
    expect(report.concepts.find((c) => c.package === '' && c.concept === 'cn.Customer')?.landsOn).toBe('er.entity.customer');
  });
  it('uses the same-package ER owner of an explicit override even when a root ER name matches', () => {
    const p = mdHopProject({
      'root-sale': 'model er\ndef entity sale { attributes: [def attribute rootKey { type: int }] }',
      'east-er': 'package east\nmodel er\ndef entity sale { attributes: [def attribute localKey { type: int }] }',
      'east-md': 'package east\nmodel md\ndef domain Code { type: string }\ndef dimension D { key: code, attributes: [def attribute code { domain: md.Code }] }',
      'east-pin': 'package east\nmodel binding\ndef md2er_dimension pin { dimension: md.D, entity: er.entity.sale, attributes: { code: localKey } }',
    });
    expect(new CompositeMdErHop(p.docs, p.resolver).erTargetOf('east.md.dimension.D.code')).toEqual({ erTarget: 'er.entity.sale.localKey', via: 'explicit', message: '' });
  });
  it('does not align unresolved FK positions or classify a one-to-many map as a bridge', () => {
    const db = readFileSync(join(ROOT, 'db.ttrm'), 'utf8').replace('from: [db.dbo.store_sales.cust], to: [db.dbo.customer.sk]', 'from: [db.dbo.store_sales.cust, db.dbo.store_sales.missing], to: [db.dbo.customer.missing, db.dbo.customer.sk]');
    const md = readFileSync(join(ROOT, 'md.ttrm'), 'utf8').replace('cardinality: { from: "N", to: "N" }', 'cardinality: { from: "1", to: "N" }');
    const p = mdHopProject({ 'db.ttrm': db, 'md.ttrm': md });
    const hop = new DerivedMdErHop(p.docs, p.resolver);
    expect(hop.physical.relations.find((r) => r.key === 'er.relation.store_sales_customer')?.pairs).toEqual([]);
    expect(mdHopReport(p.docs, p.resolver).roles.find((r) => r.entity === 'er.entity.customer_item')?.role).toBe('unclassified');
  });
  it('keeps disagreement ambiguous and reports explicit overrides', () => {
    const sources = { conflict: readFileSync(join(ROOT, 'conflicting.ttrm.variant'), 'utf8') };
    const p = mdHopProject(sources);
    expect(new DerivedMdErHop(p.docs, p.resolver).erTargetOf('md.dimension.Customer').slug).toBe('md/er-ambiguous');
    const q = mdHopProject({ ...sources, pin: readFileSync(join(ROOT, 'override.ttrm.variant'), 'utf8') });
    const hop = new CompositeMdErHop(q.docs, q.resolver);
    expect(hop.erTargetOf('md.dimension.Customer').erTarget).toBe('er.entity.household');
    expect(hop.issues('md.dimension.Customer').map((i) => i.slug)).toEqual(['md/er-hop-override']);
  });
});

describe('review-129 shared regressions', () => {
  const cases = JSON.parse(readFileSync(join(ROOT, 'review-129.json'), 'utf8')) as Array<{
    name: string; overrides: Record<string, string>; expected: Record<string, string | null>;
    bearer?: string; origins?: Record<string, string[]>; messages?: Record<string, string>; tables?: Record<string, string[]>; opaque?: string; relation?: string;
    override?: string; issue?: string; concept?: string;
  }>;
  for (const c of cases) it(c.name, () => {
    const p = mdHopProject(c.overrides); const hop = new CompositeMdErHop(p.docs, p.resolver);
    for (const [md, er] of Object.entries(c.expected)) expect(hop.qualifiedTargetOf(md, c.bearer).erTarget ?? null, md).toBe(er);
    for (const [attr, cols] of Object.entries(c.origins ?? {})) expect([...hop.derived.physical.erAttrToColumn.get(attr) ?? []].sort()).toEqual(cols);
    for (const [md, message] of Object.entries(c.messages ?? {})) expect(hop.qualifiedTargetOf(md).message).toContain(message);
    for (const [entity, tables] of Object.entries(c.tables ?? {})) expect([...hop.derived.physical.entityToTable.get(entity) ?? []].sort()).toEqual(tables);
    if (c.opaque) expect(hop.derived.lineageIssues.some((i) => i.element === c.opaque)).toBe(true);
    if (c.relation) expect(hop.derived.physical.relations.find((r) => r.key === c.relation)?.pairs).toEqual([['er.entity.store_sales.customer', 'er.entity.customer.sk']]);
    if (c.override) expect(hop.issues(c.override).map((i) => i.slug)).toContain('md/er-hop-override');
    if (c.issue) {
      expect(mdHopReport(p.docs, p.resolver).issues.map((i) => i.slug)).toContain(c.issue);
      expect(cnHygieneReport(p.docs, p.resolver).concepts.find((r) => r.concept === c.concept)?.hop?.slug).toBe(c.issue);
    }
  });
  it('keeps package role rows distinct and locates packaged lineage findings', () => {
    const extra: Record<string, string> = {};
    for (const pkg of ['east', 'west']) for (const f of readdirSync(ROOT).filter((f) => f.endsWith('.ttrm'))) extra[`${pkg}/${f}`] = `package ${pkg}\n${readFileSync(join(ROOT, f), 'utf8')}`;
    const p = mdHopProject(extra); const report = mdHopReport(p.docs, p.resolver, true);
    for (const prefix of ['', 'east.', 'west.']) expect(report.roles.filter((r) => r.entity === `${prefix}er.entity.store_sales`).map((r) => r.role)).toEqual(['fact']);
    expect(report.roles.filter((r) => r.entity.endsWith('er.entity.sales'))).toHaveLength(3);
  });
});
