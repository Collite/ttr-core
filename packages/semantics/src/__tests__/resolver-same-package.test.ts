// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { parseString } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';
import { collectAllReferences } from '../references.js';

function project(texts: string[]) {
  const symbols = new ProjectSymbolTable();
  for (const [i, text] of texts.entries()) {
    const uri = `file:///world/${i}.ttrm`;
    const { ast, errors } = parseString(`package shop\n${text}`, uri);
    expect(errors).toEqual([]);
    symbols.upsertDocument(uri, ast!, ast!.modelDirective?.modelCode ?? '', '', 'shop');
  }
  return new Resolver(symbols);
}
const context = { schemaCode: 'er', namespace: '', packageName: 'shop' };

describe('same-package candidate selection', () => {
  it('prefers the current model in either index order', () => {
    const texts = ['model er\ndef entity revenue {}', 'model md\ndef measure revenue {}'];
    for (const order of [texts, [...texts].reverse()]) {
      expect(project(order).resolveReference({ path: 'revenue', parts: ['revenue'] }, context))
        .toMatchObject({ resolved: true, symbol: { qname: 'shop.er.entity.revenue' } });
    }
  });

  it('reports all remaining candidates deterministically instead of choosing the first', () => {
    const texts = [
      'model er\ndef entity sale { attributes: [def attribute revenue { type: decimal }] }',
      'model er\ndef entity order { attributes: [def attribute revenue { type: decimal }] }',
    ];
    const results = [texts, [...texts].reverse()].map((order) => {
      const result = project(order).resolveReference({ path: 'revenue', parts: ['revenue'] }, context);
      expect(result).toMatchObject({ resolved: false, reason: 'ambiguous' });
      return result.resolved ? [] : result.candidates?.map((s) => s.qname);
    });
    expect(results[0]).toEqual(['shop.er.entity.order.revenue', 'shop.er.entity.sale.revenue']);
    expect(results[1]).toEqual(results[0]);
  });

  it('preserves the MD slot kind when collecting references for the reverse index', () => {
    const resolver = project([
      'model md\ndef dimension Sale { attributes: [def attribute revenue { domain: md.Money }] }',
      'model md\ndef measure revenue { domain: md.Money }',
      'model md\ndef domain Money { type: decimal }',
    ]);
    const ast = parseString('package shop\nmodel md\ndef cubelet Sales { grain: [], measures: [revenue] }').ast!;
    const [{ ref }] = collectAllReferences(ast);
    expect(resolver.resolveReference(ref, { ...context, schemaCode: 'md' }))
      .toMatchObject({ resolved: true, symbol: { qname: 'shop.md.measure.revenue' } });
  });

  it('never substitutes another kind for a missing MD measure', () => {
    const resolver = project(['model md\ndef dimension Sale { attributes: [def attribute revenue { domain: md.Money }] }']);
    const ast = parseString('package shop\nmodel md\ndef cubelet Sales { grain: [], measures: [revenue] }').ast!;
    expect(resolver.resolveReference(collectAllReferences(ast)[0].ref, { ...context, schemaCode: 'md' }))
      .toMatchObject({ resolved: false, reason: 'not-found' });
    const cn = project(['model cn\ndef kind Revenue {}']);
    const wrongModel = parseString('model md\ndef cubelet Sales { measures: [cn.Revenue] }').ast!;
    expect(cn.resolveReference(collectAllReferences(wrongModel)[0].ref, { ...context, schemaCode: 'md' }))
      .toMatchObject({ resolved: false, reason: 'not-found' });
  });

  it('keeps MD references local when another package has the same measure', () => {
    const symbols = new ProjectSymbolTable();
    for (const pkg of ['east', 'west']) {
      const ast = parseString(`package ${pkg}\nmodel md\ndef measure revenue {}`).ast!;
      symbols.upsertDocument(`${pkg}.ttrm`, ast, 'md', '', pkg);
    }
    const ast = parseString('model md\ndef cubelet Sales { grain: [], measures: [revenue, east.md.revenue, east.md.measure.revenue, root.east.md.revenue] }').ast!;
    const refs = collectAllReferences(ast);
    const resolver = new Resolver(symbols, 'root');
    const ctx = { ...context, schemaCode: 'md', packageName: 'west' };
    expect(resolver.resolveReference(refs[0].ref, ctx))
      .toMatchObject({ resolved: true, symbol: { qname: 'west.md.measure.revenue' } });
    for (const { ref } of refs.slice(1)) {
      expect(resolver.resolveReference(ref, ctx))
        .toMatchObject({ resolved: true, symbol: { qname: 'east.md.measure.revenue' } });
    }
  });

  it('preserves named and wildcard MD imports, including ambiguous wildcards', () => {
    const symbols = new ProjectSymbolTable();
    for (const pkg of ['east', 'west']) {
      const ast = parseString(`package ${pkg}\nmodel md\ndef measure revenue {}`).ast!;
      symbols.upsertDocument(`${pkg}.ttrm`, ast, 'md', '', pkg);
    }
    const resolver = new Resolver(symbols);
    for (const imports of ['import west.md.measure.revenue', 'import west.*']) {
      const ast = parseString(`package app\n${imports}\nmodel md\ndef cubelet Sales { measures: [revenue] }`).ast!;
      expect(resolver.resolveReference(collectAllReferences(ast)[0].ref,
        { ...context, schemaCode: 'md', packageName: 'app', imports: ast.imports }))
        .toMatchObject({ resolved: true, symbol: { qname: 'west.md.measure.revenue' } });
    }
    const ast = parseString('package app\nimport east.*\nimport west.*\nmodel md\ndef cubelet Sales { measures: [revenue] }').ast!;
    expect(resolver.resolveReference(collectAllReferences(ast)[0].ref,
      { ...context, schemaCode: 'md', packageName: 'app', imports: ast.imports }))
      .toMatchObject({ resolved: false, reason: 'ambiguous' });
  });
});
