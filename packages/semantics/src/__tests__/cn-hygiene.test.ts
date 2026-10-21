// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { parseString } from '@tatrman/parser';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';
import type { CnDocument } from '../cn/groundings.js';
import { cnHygieneReport, mdEscape, renderCnHygieneMarkdown } from '../cn/hygiene.js';

// CN-P2 S2.7 T3 — the editor's hygiene report against the golden `ttr-concept` is held to
// (`tests/conformance/cn-hygiene/`): the data is `expected.json` less the Kotlin-only `definitions`
// (CN-D29), the text is `expected-editor.md`.
const GOLDEN = join(__dirname, '../../../../tests/conformance/cn-hygiene');
const PROJECT = join(GOLDEN, 'hartland/hygiene');

function load(files: Array<{ uri: string; src: string }>) {
  const symbols = new ProjectSymbolTable();
  const docs: CnDocument[] = files.map(({ uri, src }) => {
    const r = parseString(src, uri);
    expect(r.errors.filter((e) => e.severity === 'error'), uri).toEqual([]);
    const ast = r.ast!;
    return {
      uri,
      ast,
      packageName: ast.packageDecl?.name ?? '',
      schemaCode: ast.modelDirective?.modelCode ?? '',
      namespace: ast.modelDirective?.schema ?? '',
    };
  });
  for (const d of docs) symbols.upsertDocument(d.uri, d.ast, d.schemaCode, d.namespace, d.packageName);
  return cnHygieneReport(docs, new Resolver(symbols));
}

const golden = () =>
  load(readdirSync(PROJECT).sort().map((f) => ({ uri: `hartland/hygiene/${f}`, src: readFileSync(join(PROJECT, f), 'utf-8') })));

describe('cn hygiene report — the editor half of the golden (CN-P2 S2.7)', () => {
  it('is expected.json less the Kotlin-only definitions', () => {
    const expected = JSON.parse(readFileSync(join(GOLDEN, 'expected.json'), 'utf-8'));
    delete expected.definitions;
    expect(golden()).toEqual(expected);
  });

  it('renders as expected-editor.md', () => {
    expect(renderCnHygieneMarkdown(golden())).toBe(readFileSync(join(GOLDEN, 'expected-editor.md'), 'utf-8'));
  });

  it('escapes authored text for markdown; an underscore inside a word stays (review-119 L-5, = Kotlin HygieneRender.md)', () => {
    expect(mdEscape('*vendor*')).toBe('\\*vendor\\*');
    expect(mdEscape('_x_')).toBe('\\_x\\_');
    expect(mdEscape('loyalty_programme')).toBe('loyalty_programme');
    expect(mdEscape('`a|b` [c] <d> ~e~ \\')).toBe('\\`a\\|b\\` \\[c\\] \\<d\\> \\~e\\~ \\\\');
    expect(mdEscape('é_é')).toBe('é_é');
    // review-120 F20: a name wrapped in `_`, an entity, a line break
    expect(mdEscape('cn._Hidden_')).toBe('cn.\\_Hidden\\_');
    expect(mdEscape('&lt;b')).toBe('\\&lt;b');
    expect(mdEscape('a\nb')).toBe('a b');
  });

  it('a project with no concepts: an empty report', () => {
    const r = load([{ uri: 'a.ttrm', src: 'package hartland.none\nmodel er\n\ndef entity shopper { attributes: [ def attribute id { type: int, isKey: true } ] }\n' }]);
    expect(r.concepts).toEqual([]);
    expect(renderCnHygieneMarkdown(r)).toContain('0 concepts\n');
  });

  it('a quality on an attribute two cubelets map: the bearer picks the hop (AM-29), else md/er-ambiguous', () => {
    const files = [
      {
        uri: 'a-er.ttrm',
        src: `package hartland.web
model er
def entity web_sale   { attributes: [ def attribute sale_id { type: int, isKey: true }, def attribute shopper_id { type: int } ] }
def entity web_return { attributes: [ def attribute return_id { type: int, isKey: true }, def attribute shopper_id { type: int } ] }
`,
      },
      {
        uri: 'b-md.ttrm',
        src: `package hartland.web
model md
def domain Code { type: text }
def dimension Shopper { key: code, attributes: [ def attribute code { domain: md.Code, isKey: true } ] }
def cubelet web_sales   { grain: [Shopper.code] }
def cubelet web_returns { grain: [Shopper.code] }
def md2er_cubelet web_sales_er   { cubelet: md.web_sales,   target: er.entity.web_sale,   attributes: { Shopper.code: shopper_id } }
def md2er_cubelet web_returns_er { cubelet: md.web_returns, target: er.entity.web_return, attributes: { Shopper.code: shopper_id } }
`,
      },
      {
        uri: 'c-cn.ttrm',
        src: `package hartland.web
model cn
def event WebReturn { period: { at: returned_at }, grounds: md.web_returns }
def kind Shopper { grounds: md.Shopper }
def quality returning { of: WebReturn, grounds: md.Shopper.code }
def quality shopper_code { of: Shopper, grounds: md.Shopper.code }
`,
      },
    ];
    const rows = new Map(load(files).concepts.map((c) => [c.concept, c]));
    expect(rows.get('cn.WebReturn')!.landsOn).toBe('er.entity.web_return');
    expect(rows.get('cn.returning')!.landsOn).toBe('er.entity.web_return.shopper_id');
    expect(rows.get('cn.Shopper')!.hop).toEqual({ from: 'md.dimension.Shopper', to: null, slug: 'md/er-unmapped' });
    // the bearer lands nowhere on E-R, so nothing picks between the two cubelets' mappings
    expect(rows.get('cn.shopper_code')!.hop!.slug).toBe('md/er-ambiguous');
    expect(rows.get('cn.shopper_code')!.grade).toBe('md-grounded-hop-missing');
  });
});
