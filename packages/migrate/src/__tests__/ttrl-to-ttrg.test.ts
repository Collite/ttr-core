// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach } from 'vitest';
import { convertTtrlToTtrg } from '../index.js';
import { writeFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';

describe('convertTtrlToTtrg', () => {
  const tmpDir = '/tmp/modeler-migrate-ttrl-test';

  beforeEach(async () => {
    await mkdir(tmpDir, { recursive: true });
    const { rm } = await import('node:fs/promises');
    try {
      await rm(tmpDir, { recursive: true });
      await mkdir(tmpDir, { recursive: true });
    } catch {
      // ignore
    }
  });

  it('produces one .ttrg per schema present in the layout', async () => {
    const ttrlPath = join(tmpDir, '.modeler', 'layout.ttrl');
    await mkdir(join(tmpDir, '.modeler'), { recursive: true });
    await writeFile(
      ttrlPath,
      JSON.stringify({
        version: 1,
        viewports: {
          db: { zoom: 1.5, panX: 10, panY: 20, displayMode: 'just-names' },
          er: { zoom: 2.0, panX: 5, panY: 15, displayMode: 'with-types' },
        },
        nodes: {
          'db.dbo.item': { x: 100, y: 200 },
          'er.entity.item': { x: 300, y: 400 },
        },
      }),
      'utf-8'
    );

    const projectSymbols = [
      { qname: 'db.dbo.item', schemaCode: 'db' },
      { qname: 'er.entity.item', schemaCode: 'er' },
      { qname: 'er.entity.category', schemaCode: 'er' },
    ];

    const ops = await convertTtrlToTtrg(ttrlPath, tmpDir, projectSymbols);

    expect(ops.length).toBeGreaterThanOrEqual(2);
    const schemas = ops.map(op => op.path.match(/_all_(.*)\.ttrg$/)?.[1]).filter(Boolean);
    expect(schemas).toContain('db');
    expect(schemas).toContain('er');
  });

  it('preserves layout viewport and node positions', async () => {
    const ttrlPath = join(tmpDir, '.modeler', 'layout.ttrl');
    await mkdir(join(tmpDir, '.modeler'), { recursive: true });
    await writeFile(
      ttrlPath,
      JSON.stringify({
        version: 1,
        viewports: {
          er: { zoom: 2.5, panX: 99, panY: 88, displayMode: 'with-constraints' },
        },
        nodes: {
          'er.entity.item': { x: 420, y: 130 },
        },
      }),
      'utf-8'
    );

    const projectSymbols = [
      { qname: 'er.entity.item', schemaCode: 'er' },
    ];

    const ops = await convertTtrlToTtrg(ttrlPath, tmpDir, projectSymbols);
    const erOp = ops.find(op => op.path.includes('_all_er.ttrg'));
    expect(erOp).toBeDefined();
    expect(erOp!.content).toContain('zoom: 2.5');
    expect(erOp!.content).toContain('er.entity.item: { x: 420, y: 130 }');
  });

  it('generated .ttrg parses cleanly', async () => {
    const ttrlPath = join(tmpDir, '.modeler', 'layout.ttrl');
    await mkdir(join(tmpDir, '.modeler'), { recursive: true });
    await writeFile(
      ttrlPath,
      JSON.stringify({
        version: 1,
        viewports: {
          er: { zoom: 1.0, panX: 0, panY: 0, displayMode: 'just-names' },
        },
        nodes: {
          'er.entity.item': { x: 100, y: 200 },
        },
      }),
      'utf-8'
    );

    const projectSymbols = [
      { qname: 'er.entity.item', schemaCode: 'er' },
    ];

    const { parseString } = await import('@tatrman/parser');
    const ops = await convertTtrlToTtrg(ttrlPath, tmpDir, projectSymbols);
    const erOp = ops.find(op => op.path.includes('_all_er.ttrg'));
    expect(erOp).toBeDefined();
    const result = parseString(erOp!.content, erOp!.path);
    expect(result.errors.filter(e => e.severity === 'error'), `parse errors: ${JSON.stringify(result.errors)}`).toHaveLength(0);
    expect(result.ast?.graph).toBeDefined();
  });
});