// SPDX-License-Identifier: Apache-2.0
// VL-P0 T4 — the TTR serialization: `.ttrg` graph block with the in-file `layout:` block on write;
// `.ttrg` ± `.ttrl` sidecar on read, the sidecar winning (contracts §3, V-10…V-13).

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import type { LayoutDocument } from '../layout-document.js';
import { isTtrId, mergeTtr, parseTtrg, readTtrg, readTtrl, ttrUnwritable, writeTtrg, TTR_NON_ID_WORDS } from '../layout-ttr.js';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', '..');
const repo = (p: string) => join(REPO, p);
const sample = (p: string) => readFileSync(repo(p), 'utf8');

const ITEM = 'hartland.er.entity.item';
const STORE = 'hartland.er.entity.store';
const SALES = 'hartland.er.entity.store_sales';

const POSITIONED: LayoutDocument = {
  name: 'sales_overview',
  model: 'er',
  objects: [SALES, ITEM, STORE],
  positions: { [STORE]: { x: 580, y: 180 }, [ITEM]: { x: 320, y: -40 } },
};

function ok(r: ReturnType<typeof readTtrg>) {
  if (!r.ok) throw new Error(JSON.stringify(r.errors));
  return r;
}

describe('writeTtrg (V-10, V-12, V-13)', () => {
  it('writes one graph block with model, sorted objects and the in-file layout block', () => {
    expect(writeTtrg(POSITIONED)).toBe(
      [
        'graph sales_overview {',
        '  model: er,',
        '  objects: [',
        `    ${ITEM},`,
        `    ${STORE},`,
        `    ${SALES}`,
        '  ],',
        '  layout: {',
        '    nodes: {',
        `      ${ITEM}: { x: 320, y: -40 },`,
        `      ${STORE}: { x: 580, y: 180 }`,
        '    }',
        '  }',
        '}',
        '',
      ].join('\n'),
    );
  });

  it('writes no layout block when there are no positions, and `objects: []` when there are no cards', () => {
    expect(writeTtrg({ ...POSITIONED, positions: {} })).toBe(
      `graph sales_overview {\n  model: er,\n  objects: [\n    ${ITEM},\n    ${STORE},\n    ${SALES}\n  ]\n}\n`,
    );
    expect(writeTtrg({ name: 'empty', model: 'db', objects: [], positions: {} })).toBe('graph empty {\n  model: db,\n  objects: []\n}\n');
  });

  it('never writes skin, collapsed, a viewport or edges (V-10)', () => {
    const text = writeTtrg({ ...POSITIONED, skin: 'er.crow', collapsed: [STORE] });
    expect(text).toBe(writeTtrg(POSITIONED));
    expect(text).not.toMatch(/skin|collapsed|viewport|edges/);
  });

  it('A-VL-5: every ref bare (diacritics and idPart keywords included); a ref that is not an `id` has no TTR form', () => {
    const ok = { name: 'g', model: 'er' as const, objects: ['hartland.er.entity.zákazník', 'er.entity.graph'], positions: { 'er.entity.graph': { x: 1, y: 2 } } };
    expect(ttrUnwritable(ok)).toEqual([]);
    expect(writeTtrg(ok)).toContain('    er.entity.graph,\n    hartland.er.entity.zákazník\n'); // GRAPH is in idPart
    const bad = { ...ok, objects: [...ok.objects, 'Customer Address', 'hartland.er.entity.text', '1abc'], positions: { ...ok.positions, 'a-b': { x: 0, y: 0 } } };
    expect(ttrUnwritable(bad)).toEqual(['1abc', 'a-b', 'Customer Address', 'hartland.er.entity.text']); // TEXT is not in idPart
    expect(() => writeTtrg(bad)).toThrow('g has no TTR form: "1abc", "a-b", "Customer Address", "hartland.er.entity.text" are not a TTR id');
  });
});

describe('isTtrId — the grammar `id` rule, mirrored', () => {
  it('admits IDENT parts (Latin-1 + Latin Extended-A/B letters) and idPart keywords, joined by dots', () => {
    for (const s of ['item', 'zákazník', 'a.b.c', 'er.entity.item', 'db.dbo.table.item', 'graph', 'layout', 'canvas', '_x9']) {
      expect(isTtrId(s), s).toBe(true);
    }
  });

  it('rejects empty parts, leading digits, other characters and the keywords idPart does not admit', () => {
    for (const s of ['', 'a..b', '.a', 'a.', '9a', 'a b', 'a-b', 'true', 'x.null', 'text', 'description', 'Ωmega']) {
      expect(isTtrId(s), s).toBe(false);
    }
  });

  it('TTR_NON_ID_WORDS is exactly the lexer keywords outside idPart in TTR.g4 (fails when the grammar moves)', () => {
    const g4 = sample('packages/grammar/src/TTR.g4')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/[^\n]*/g, '');
    const idPartBody = /\bidPart\s*:([\s\S]*?);/.exec(g4)![1];
    const inIdPart = new Set(idPartBody.match(/\b[A-Z][A-Z0-9_]*\b/g));
    const words = new Set<string>();
    for (const m of g4.matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*:\s*([^;]*?);/gm)) {
      const alts = m[2].split('|').map((a) => a.replace(/->.*/, '').trim());
      const lits = alts.map((a) => /^'([^']*)'$/.exec(a)?.[1]);
      if (inIdPart.has(m[1]) || lits.some((l) => l === undefined)) continue;
      for (const l of lits) if (/^[A-Za-z_\u00C0-\u024F][A-Za-z0-9_\u00C0-\u024F]*$/.test(l!)) words.add(l!);
    }
    expect([...TTR_NON_ID_WORDS].sort()).toEqual([...words].sort());
  });
});

describe('readTtrg / mergeTtr (V-11)', () => {
  it('write → read is idempotent', () => {
    for (const d of [POSITIONED, { ...POSITIONED, positions: {} }, { name: 'e', model: 'db' as const, objects: [], positions: {} }]) {
      const text = writeTtrg(d);
      expect(writeTtrg(ok(readTtrg(text)).doc)).toBe(text);
    }
  });

  it('reads positions from the in-file block and says so', () => {
    const r = ok(readTtrg(writeTtrg(POSITIONED)));
    expect(r.positionsFrom).toBe('in-file');
    expect(r.doc).toEqual({ ...POSITIONED, objects: [ITEM, STORE, SALES], positions: { [ITEM]: { x: 320, y: -40 }, [STORE]: { x: 580, y: 180 } } });
  });

  it('samples/v1.1-mini item_overview: the .ttrg + its .ttrl sidecar → 5 objects, 5 positions from the sidecar', () => {
    const r = ok(mergeTtr(sample('samples/v1.1-mini/graphs/item_overview.ttrg'), sample('samples/v1.1-mini/graphs/item_overview.ttrl')));
    expect(r.positionsFrom).toBe('sidecar');
    expect(r.doc.name).toBe('item_overview');
    expect(r.doc.objects).toHaveLength(5);
    expect(r.doc.positions).toEqual({
      'billing.invoicing.er.entity.item': { x: 320, y: 200 },
      'billing.invoicing.er.entity.store_return': { x: 620, y: 200 },
      'billing.invoicing.er.relation.item_in_category': { x: 320, y: 420 },
      'billing.invoicing.er.relation.item_in_class': { x: 320, y: 600 },
      'billing.products.er.relation.class_in_category': { x: 580, y: 600 },
    });
    // description + tags are graph metadata, not layout content — reported, not kept
    expect(r.warnings.map((w) => w.path)).toEqual(['description', 'tags']);
  });

  it('samples/v1.1-mini all_er: 12 sidecar positions; all_db: no positions at all', () => {
    const er = ok(mergeTtr(sample('samples/v1.1-mini/graphs/all_er.ttrg'), sample('samples/v1.1-mini/graphs/all_er.ttrl')));
    expect(Object.keys(er.doc.positions)).toHaveLength(12);
    const db = ok(readTtrg(sample('samples/v1.1-mini/graphs/all_db.ttrg')));
    expect(db.positionsFrom).toBeNull();
    expect(db.doc.model).toBe('db');
    expect(db.doc.objects).toHaveLength(11);
  });

  it('the stale-node sample parses with its one position; the document read refuses it per V-3', () => {
    const text = sample('samples/broken/v1.1/graph-layout-stale-node.ttrg');
    const raw = parseTtrg(text);
    expect(raw.errors).toEqual([]);
    expect(raw.graph?.nodes).toEqual({ 'er.entity.nonexistent': { x: 100, y: 100 } });
    expect(raw.graph?.objects).toHaveLength(1);
    const r = readTtrg(text);
    expect(r.ok).toBe(false);
    if (r.ok) return;
    expect(r.errors).toEqual([
      { path: 'positions.er.entity.nonexistent', message: 'positions key er.entity.nonexistent is not in objects', line: 4 },
    ]);
  });

  it('when both carriers have positions the sidecar wins; a sidecar without the canvas, or without nodes, does not', () => {
    const ttrg = writeTtrg(POSITIONED);
    const side = (body: string) => `ttrl 1\n\ncanvas sales_overview {\n${body}}\n`;
    const wins = ok(mergeTtr(ttrg, side(`    skin: "er.crow"\n    mode: manual\n    nodes: {\n        "${ITEM}": { x: 1, y: 2 }\n    }\n    collapsed: ["${STORE}"]\n`)));
    expect(wins.positionsFrom).toBe('sidecar');
    expect(wins.doc.positions).toEqual({ [ITEM]: { x: 1, y: 2 } });
    expect(wins.doc.skin).toBe('er.crow');
    expect(wins.doc.collapsed).toEqual([STORE]);

    const otherCanvas = ok(mergeTtr(ttrg, 'ttrl 1\n\ncanvas something_else {\n    mode: auto\n}\n'));
    expect(otherCanvas.positionsFrom).toBe('in-file');
    expect(otherCanvas.warnings).toEqual([{ path: 'ttrl', message: 'the sidecar has no canvas sales_overview — its positions are not used' }]);

    const auto = ok(mergeTtr(ttrg, side('    mode: auto\n')));
    expect(auto.positionsFrom).toBe('in-file');
    expect(auto.doc.positions[STORE]).toEqual({ x: 580, y: 180 });
  });

  it('a sidecar the Studio’s WS store wrote (`canvas main`) is read; a canvas named after the graph wins over it', () => {
    const ttrg = writeTtrg(POSITIONED);
    const canvas = (key: string, x: number) => `canvas ${key} {\n    mode: manual\n    nodes: {\n        "${ITEM}": { x: ${x}, y: 2 }\n    }\n}\n`;
    const main = ok(mergeTtr(ttrg, `ttrl 1\n\n${canvas('main', 1)}`));
    expect(main.positionsFrom).toBe('sidecar');
    expect(main.doc.positions).toEqual({ [ITEM]: { x: 1, y: 2 } });
    expect(main.warnings).toEqual([]);

    const both = ok(mergeTtr(ttrg, `ttrl 1\n\n${canvas('main', 1)}\n${canvas('sales_overview', 5)}`));
    expect(both.doc.positions).toEqual({ [ITEM]: { x: 5, y: 2 } });
  });

  it('what the sidecar holds beyond the graph is dropped with a warning, not an error (A-VL-3)', () => {
    const ttrg = writeTtrg(POSITIONED);
    const gone = 'hartland.er.entity.customer';
    const stale = ok(mergeTtr(ttrg, `ttrl 1\n\ncanvas sales_overview {\n    mode: manual\n    nodes: {\n        "${gone}": { x: 9, y: 9 }\n        "${ITEM}": { x: 1, y: 2 }\n    }\n}\n`));
    expect(stale.positionsFrom).toBe('sidecar');
    expect(stale.doc.positions).toEqual({ [ITEM]: { x: 1, y: 2 } });
    expect(stale.warnings).toEqual([{ path: `ttrl.nodes.${gone}`, message: `the sidecar positions ${gone}, which the graph does not list — dropped` }]);

    const onlyStale = ok(mergeTtr(ttrg, `ttrl 1\n\ncanvas sales_overview {\n    mode: manual\n    nodes: {\n        "${gone}": { x: 9, y: 9 }\n    }\n}\n`));
    expect(onlyStale.positionsFrom).toBe('in-file');
    expect(onlyStale.doc.positions[STORE]).toEqual({ x: 580, y: 180 });

    const auto = ok(mergeTtr(ttrg, `ttrl 1\n\ncanvas sales_overview {\n    mode: auto\n    nodes: {\n        "${ITEM}": { x: 1, y: 2 }\n    }\n}\n`));
    expect(auto.positionsFrom).toBe('in-file');
    expect(auto.doc.positions[ITEM]).not.toEqual({ x: 1, y: 2 });
    expect(auto.warnings).toEqual([{ path: 'ttrl', message: "the sidecar's canvas sales_overview is `mode: auto` — its nodes are not positions" }]);

    const unstated = ok(mergeTtr(ttrg, `ttrl 1\n\ncanvas sales_overview {\n    nodes: {\n        "${ITEM}": { x: 1, y: 2 }\n    }\n}\n`));
    expect(unstated.doc.positions).toEqual({ [ITEM]: { x: 1, y: 2 } }); // no `mode` stated — TtrlLoader takes the nodes too
  });

  it('reads the worker mode block shape (no commas, 4-space indent, a viewport) and drops the viewport (C1-b)', () => {
    const worker = [
      'import hartland',
      '',
      'graph sales_overview {',
      '    model: er',
      `    objects: [${ITEM}, ${STORE}],`,
      '    layout: {',
      '    viewport: { zoom: 1.5, panX: 10, panY: 20, displayMode: "just-names" }',
      '    nodes: {',
      `        ${ITEM}: { x: 320.4, y: 180 }`,
      `        ${STORE}: { x: 580, y: 180.5 }`,
      '    }',
      '    edges: {}',
      '}',
      '  }',
    ].join('\n');
    const r = ok(readTtrg(worker));
    expect(r.doc.positions).toEqual({ [ITEM]: { x: 320, y: 180 }, [STORE]: { x: 580, y: 181 } });
    expect(JSON.stringify(r.doc)).not.toMatch(/viewport|zoom/);
  });

  it('is not fooled by comments or strings that mention objects, brackets or braces', () => {
    const tricky = [
      '// graph decoy { objects: [nope] }',
      'graph sales_overview {',
      '  description: "objects: [not, these] } {",',
      '  /* layout: { nodes: { nope: { x: 1, y: 1 } } } */',
      '  model = er',
      `  objects = [${ITEM}, "${STORE}",]`,
      `  layout = { nodes = { "${ITEM}" = { y = 2, x = 1 } } }`,
      '}',
    ].join('\n');
    const r = ok(readTtrg(tricky));
    expect(r.doc.objects).toEqual([ITEM, STORE]);
    expect(r.doc.positions).toEqual({ [ITEM]: { x: 1, y: 2 } });
  });

  it('reads a quoted (non-id) ref back exactly — a writer never writes one (A-VL-5), a reader accepts it (V-12)', () => {
    const text = 'graph odd {\n  model: er,\n  objects: [\n    "Customer Address",\n    "a\\"b"\n  ],\n  layout: { nodes: { "Customer Address": { x: 0, y: 0 } } }\n}\n';
    const d: LayoutDocument = { name: 'odd', model: 'er', objects: ['a"b', 'Customer Address'], positions: { 'Customer Address': { x: 0, y: 0 } } };
    expect(ok(readTtrg(text)).doc).toEqual(d);
  });

  it('reports what makes a file unreadable, with lines', () => {
    const errs = (t: string, side?: string) => {
      const r = mergeTtr(t, side);
      return r.ok ? [] : r.errors.map((e) => [e.path, e.message, e.line]);
    };
    expect(errs('model er\n')).toEqual([['', 'no `graph <name> { … }` block', undefined]]);
    expect(errs('graph a-b {\n  model: er,\n  objects: []\n}\n')[0][0]).toBe('');
    expect(errs('graph a {\n  objects: []\n}\n')).toEqual([['model', 'the graph has no `model:`', 1]]);
    expect(errs('graph a {\n  model: binding,\n  objects: []\n}\n')).toEqual([['model', 'model must be one of er, db, md, cnc (got binding)', 2]]);
    expect(errs(`graph a {\n  model: er,\n  objects: [${ITEM}],\n  layout: { nodes: { ${ITEM}: { x: 1 } } }\n}\n`)).toEqual([
      [`positions.${ITEM}.y`, 'missing y', 4],
    ]);
    expect(errs('graph a {\n  model: er,\n  description: "unterminated\n}\n')[0][1]).toMatch(/unterminated string/);
    expect(errs('graph a {\n  model: er,\n  objects: [a b]\n}\n')).toEqual([['objects', 'expected `,` or `]` in objects', 3]]);
    expect(errs(writeTtrg(POSITIONED), 'ttrl 2\n')).toEqual([['ttrl', '`.ttrl` version 2 is not supported (expected 1)', 1]]);
  });
});

describe('readTtrl', () => {
  it('reads every canvas: skin, mode, nodes, collapsed; chains are TTR-P-only and skipped', () => {
    const r = readTtrl(
      'ttrl 1\n\ncanvas a {\n    skin: "er.crow"\n    mode: manual\n    nodes: {\n        "x.y": { x: 1, y: -2 }\n    }\n    collapsed: ["x.y"]\n    chains: {\n        "s": 2\n    }\n}\n\ncanvas b {\n    mode: auto\n}\n\ncanvas c {\n}\n',
    );
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.canvases).toEqual([
      { key: 'a', skin: 'er.crow', mode: 'manual', nodes: { 'x.y': { x: 1, y: -2 } }, collapsed: ['x.y'] },
      { key: 'b', mode: 'auto', nodes: {}, collapsed: [] },
      { key: 'c', nodes: {}, collapsed: [] },
    ]);
  });

  it('reads the samples sidecar', () => {
    const r = readTtrl(sample('samples/v1.1-mini/graphs/all_er.ttrl'));
    expect(r.ok && r.canvases.map((c) => [c.key, c.mode, Object.keys(c.nodes).length])).toEqual([['all_er', 'manual', 12]]);
  });
});
