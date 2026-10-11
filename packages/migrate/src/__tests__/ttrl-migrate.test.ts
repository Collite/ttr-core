// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
import { planTtrlExtraction, isSkip, sidecarPathFor } from '../ttrl-migrate.js';
import { planTtrlMigration, runTtrlMigration } from '../ttrl-migrate-driver.js';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');

describe('sidecarPathFor', () => {
  it('replaces the .ttrg extension with .ttrl', () => {
    expect(sidecarPathFor('/proj/graphs/all_er.ttrg')).toBe('/proj/graphs/all_er.ttrl');
  });
});

describe('planTtrlExtraction', () => {
  it('extracts a layout as the LAST property (leading-comma absorption)', () => {
    const text = 'graph g {\n  model: er,\n  objects: [er.entity.a],\n  layout: {\n    nodes: {\n      er.entity.a: { x: 10, y: 20 }\n    }\n  }\n}\n';
    const r = planTtrlExtraction('g.ttrg', text);
    expect(isSkip(r)).toBe(false);
    if (isSkip(r)) return;
    expect(r.sidecarPath).toBe('g.ttrl');
    expect(r.nodeCount).toBe(1);
    expect(r.viewportDropped).toBe(false);
    expect(r.ttrgAfter).not.toContain('layout');
    expect(r.ttrgAfter).toContain('objects: [er.entity.a]');
    expect(r.ttrl).toBe('ttrl 1\n\ncanvas g {\n    mode: manual\n    nodes: {\n        "er.entity.a": { x: 10, y: 20 }\n    }\n}\n');
  });

  it('extracts a layout as the FIRST property (trailing-comma absorption)', () => {
    const text = 'graph g {\n  layout: {\n    nodes: {\n      er.entity.a: { x: 10, y: 20 }\n    }\n  },\n  model: er,\n  objects: [er.entity.a]\n}\n';
    const r = planTtrlExtraction('g.ttrg', text);
    expect(isSkip(r)).toBe(false);
    if (isSkip(r)) return;
    expect(r.ttrgAfter).not.toContain('layout');
    // Only ONE comma consumed either side — no double-comma / dangling-comma artifacts.
    expect(r.ttrgAfter).not.toMatch(/,\s*,/);
    expect(r.ttrgAfter).not.toMatch(/{\s*,/);
  });

  it('drops viewport and reports it (C1-c-iii has no viewport slot)', () => {
    const text =
      'graph g {\n  model: er,\n  objects: [er.entity.a],\n  layout: {\n    viewport: { zoom: 1.0, panX: 0, panY: 0, displayMode: "with-types" },\n    nodes: {\n      er.entity.a: { x: 10, y: 20 }\n    }\n  }\n}\n';
    const r = planTtrlExtraction('g.ttrg', text);
    expect(isSkip(r)).toBe(false);
    if (isSkip(r)) return;
    expect(r.viewportDropped).toBe(true);
    expect(r.ttrl).not.toContain('viewport');
    expect(r.ttrl).not.toContain('zoom');
  });

  it('sorts node entries deterministically by ζ key (qname)', () => {
    const text =
      'graph g {\n  model: er,\n  objects: [er.entity.b, er.entity.a],\n  layout: {\n    nodes: {\n      er.entity.b: { x: 1, y: 2 },\n      er.entity.a: { x: 3, y: 4 }\n    }\n  }\n}\n';
    const r = planTtrlExtraction('g.ttrg', text);
    expect(isSkip(r)).toBe(false);
    if (isSkip(r)) return;
    const aIdx = r.ttrl.indexOf('er.entity.a');
    const bIdx = r.ttrl.indexOf('er.entity.b');
    expect(aIdx).toBeGreaterThan(0);
    expect(aIdx).toBeLessThan(bIdx);
  });

  it('is idempotent: re-running on the migrated output is a clean skip, not an error', () => {
    const text = 'graph g {\n  model: er,\n  objects: [er.entity.a],\n  layout: {\n    nodes: {\n      er.entity.a: { x: 10, y: 20 }\n    }\n  }\n}\n';
    const once = planTtrlExtraction('g.ttrg', text);
    expect(isSkip(once)).toBe(false);
    if (isSkip(once)) return;
    const twice = planTtrlExtraction('g.ttrg', once.ttrgAfter);
    expect(isSkip(twice)).toBe(true);
    if (!isSkip(twice)) return;
    expect(twice.reason).toBe('no-layout-property');
  });

  it('skips a graph with no layout property at all (already-clean, not an error)', () => {
    const text = 'graph g {\n  model: er,\n  objects: [er.entity.a]\n}\n';
    const r = planTtrlExtraction('g.ttrg', text);
    expect(isSkip(r)).toBe(true);
    if (!isSkip(r)) return;
    expect(r.reason).toBe('no-layout-property');
  });

  it('skips a non-graph file (no graph block) without error', () => {
    const r = planTtrlExtraction('not-a-graph.ttrm', 'package p\nmodel db schema dbo\n');
    expect(isSkip(r)).toBe(true);
    if (!isSkip(r)) return;
    expect(r.reason).toBe('no-graph-block');
  });

  it('round-trips a copy of the real sample fixture (samples/v1.1-mini/graphs/all_er.ttrg)', async () => {
    // NOT read from disk directly: T5's real repo-wide migration run (2026-07-15)
    // already migrated this exact file in place, so its on-disk content today is
    // POST-migration (no layout property — see the idempotency test below, which
    // covers that state). This test keeps proving the pre-migration behavior
    // against a frozen copy of the original content.
    const path = join(REPO_ROOT, 'samples', 'v1.1-mini', 'graphs', 'all_er.ttrg');
    const text = `graph all_er {
  model: er,
  objects: [
    billing.invoicing.er.entity.item,
    billing.invoicing.er.entity.store_return,
    billing.invoicing.er.entity.market,
    billing.invoicing.er.entity.store,
    billing.invoicing.er.entity.division,
    billing.products.er.entity.category,
    billing.products.er.entity.item_class,
    billing.invoicing.er.relation.item_in_category,
    billing.invoicing.er.relation.item_in_class,
    billing.products.er.relation.class_in_category,
    billing.invoicing.er.relation.market_in_division,
    billing.invoicing.er.relation.store_in_market
  ],
  layout: {
    viewport: { zoom: 1.0, panX: 0, panY: 0, displayMode: "with-types" },
    nodes: {
      billing.invoicing.er.entity.item: { x: 320, y: 180 },
      billing.invoicing.er.entity.store_return: { x: 580, y: 180 },
      billing.invoicing.er.entity.market: { x: 320, y: 400 },
      billing.invoicing.er.entity.store: { x: 580, y: 400 },
      billing.invoicing.er.entity.division: { x: 850, y: 400 },
      billing.products.er.entity.category: { x: 850, y: 180 },
      billing.products.er.entity.item_class: { x: 580, y: 50 },
      billing.invoicing.er.relation.item_in_category: { x: 320, y: 300 },
      billing.invoicing.er.relation.item_in_class: { x: 320, y: 480 },
      billing.products.er.relation.class_in_category: { x: 580, y: 200 },
      billing.invoicing.er.relation.market_in_division: { x: 320, y: 580 },
      billing.invoicing.er.relation.store_in_market: { x: 580, y: 480 }
    }
  }
}
`;
    const r = planTtrlExtraction(path, text);
    expect(isSkip(r)).toBe(false);
    if (isSkip(r)) return;
    expect(r.nodeCount).toBe(12);
    expect(r.viewportDropped).toBe(true); // the sample has a viewport block
    expect(r.ttrl).toContain('canvas all_er {');
    expect(r.ttrl).toContain('"billing.invoicing.er.entity.item": { x: 320, y: 180 }');
    expect(r.ttrgAfter).toContain('billing.invoicing.er.entity.item'); // still in objects:
    expect(r.ttrgAfter).not.toContain('layout');
  });

  it('the real repo fixture is post-migration on disk: clean skip + sidecar present (T5 real-run proof)', async () => {
    const ttrgPath = join(REPO_ROOT, 'samples', 'v1.1-mini', 'graphs', 'all_er.ttrg');
    const ttrgText = await readFile(ttrgPath, 'utf-8');
    const r = planTtrlExtraction(ttrgPath, ttrgText);
    expect(isSkip(r)).toBe(true);
    if (!isSkip(r)) return;
    expect(r.reason).toBe('no-layout-property');

    const ttrlText = await readFile(sidecarPathFor(ttrgPath), 'utf-8');
    expect(ttrlText).toContain('canvas all_er {');
    expect(ttrlText).toContain('"billing.invoicing.er.entity.item": { x: 320, y: 180 }');
  });
});

describe('planTtrlMigration (in-memory batch)', () => {
  it('separates results from skips across a file set', () => {
    const files = [
      { path: 'a.ttrg', text: 'graph a {\n  model: er,\n  objects: [],\n  layout: { nodes: {} }\n}\n' },
      { path: 'b.ttrg', text: 'graph b {\n  model: er,\n  objects: []\n}\n' },
      { path: 'c.ttrm', text: 'package p\nmodel db schema dbo\n' },
    ];
    const plan = planTtrlMigration(files);
    expect(plan.results.map((r) => r.ttrgPath)).toEqual(['a.ttrg']);
    expect(plan.skips.map((s) => s.ttrgPath)).toEqual(['b.ttrg']);
  });
});

describe('runTtrlMigration (fs)', () => {
  const tmpDir = join(REPO_ROOT, '.tmp-ttrl-migrate-test');

  beforeEach(async () => {
    await rm(tmpDir, { recursive: true, force: true });
    await mkdir(tmpDir, { recursive: true });
  });
  afterEach(async () => {
    await rm(tmpDir, { recursive: true, force: true });
  });

  it('--dry-run writes nothing', async () => {
    const ttrgPath = join(tmpDir, 'g.ttrg');
    await writeFile(ttrgPath, 'graph g {\n  model: er,\n  objects: [],\n  layout: { nodes: { er.entity.a: { x: 1, y: 2 } } }\n}\n', 'utf-8');

    const plan = await runTtrlMigration(tmpDir, { dryRun: true });
    expect(plan.results).toHaveLength(1);

    await expect(readFile(join(tmpDir, 'g.ttrl'), 'utf-8')).rejects.toThrow();
    const stillHasLayout = await readFile(ttrgPath, 'utf-8');
    expect(stillHasLayout).toContain('layout');
  });

  it('a real run writes both the stripped .ttrg and the new .ttrl sidecar', async () => {
    const ttrgPath = join(tmpDir, 'g.ttrg');
    await writeFile(ttrgPath, 'graph g {\n  model: er,\n  objects: [],\n  layout: { nodes: { er.entity.a: { x: 1, y: 2 } } }\n}\n', 'utf-8');

    const plan = await runTtrlMigration(tmpDir, { dryRun: false });
    expect(plan.results).toHaveLength(1);

    const ttrgAfter = await readFile(ttrgPath, 'utf-8');
    expect(ttrgAfter).not.toContain('layout');
    const ttrl = await readFile(join(tmpDir, 'g.ttrl'), 'utf-8');
    expect(ttrl).toContain('canvas g {');
    expect(ttrl).toContain('"er.entity.a": { x: 1, y: 2 }');

    // Idempotent: a second real run finds nothing left to migrate.
    const second = await runTtrlMigration(tmpDir, { dryRun: false });
    expect(second.results).toHaveLength(0);
  });

  it('skips dist/ and build/ — generated output is never migrated (T5 real-run regression)', async () => {
    const distGraph = join(tmpDir, 'dist', 'samples', 'g.ttrg');
    await mkdir(join(tmpDir, 'dist', 'samples'), { recursive: true });
    await writeFile(distGraph, 'graph g {\n  model: er,\n  objects: [],\n  layout: { nodes: {} }\n}\n', 'utf-8');
    const buildGraph = join(tmpDir, 'build', 'g.ttrg');
    await mkdir(join(tmpDir, 'build'), { recursive: true });
    await writeFile(buildGraph, 'graph g {\n  model: er,\n  objects: [],\n  layout: { nodes: {} }\n}\n', 'utf-8');

    const plan = await runTtrlMigration(tmpDir, { dryRun: true });
    expect(plan.results).toHaveLength(0);
    expect(plan.skips).toHaveLength(0);
  });
});
