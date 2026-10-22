// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it, vi } from 'vitest';
import { createTestClient } from './lsp-harness.js';
import { openFileMap } from '../open-file-map.js';
import { BffFilesDataSource } from '../bff-files-data-source.js';
import type { LspClient } from '../../lsp-client.js';

const files = new Map([
  ['modeler.toml', '[packages]\nlayout = "off"'],
  [
    'model/a-binding.ttrm',
    'package shop\nmodel binding\ndef er2db_entity item_map { entity: shop.er.item, target: { table: shop.db.dbo.item } }',
  ],
  [
    'model/z-er.ttrm',
    'package shop\nmodel er\ndef entity item { attributes: [def attribute id { type: int }] }',
  ],
  [
    'model/z-db.ttrm',
    'package shop\nmodel db\ndef table item { columns: [def column id { type: int }] }',
  ],
  ['outside.ttrm', 'package other\nmodel er\ndef entity outside {}'],
  ['model/layouts/v.ttrg', 'graph v { model: er, objects: [item] }'],
]);

it('recovers a complete unique symbol list when a real model changes between pages', async () => {
  let changed = false;
  let client: LspClient;
  client = await createTestClient(async (method, params) => {
    if (method === 'modeler/listSymbols' && (params as { cursor?: string }).cursor && !changed) {
      changed = true;
      await client.openDocument(
        'file:///world/model/new.ttrm',
        'package shop\nmodel er\ndef entity added {}'
      );
    }
  });
  try {
    await openFileMap(client, 'file:///world', files);
    const symbols = await client.listSymbols({ limit: 1 });
    expect(changed).toBe(true);
    expect(symbols.some((s) => s.qname === 'shop.er.entity.added')).toBe(true);
    expect(new Set(symbols.map((s) => s.qname)).size).toBe(symbols.length);
    expect(symbols.map((s) => s.qname).sort()).toEqual(
      (await client.listSymbols()).map((s) => s.qname).sort()
    );
  } finally {
    client.dispose();
  }
});

describe.each([false, true])('file-map load (reverse order: %s)', (reverse) => {
  const ordered = () => new Map(reverse ? [...files].reverse() : files);
  it('reindexes references after loading the local worker map', async () => {
    const client = await createTestClient();
    try {
      const paths = await openFileMap(client, 'file:///world', ordered());
      expect(paths).toContain('outside.ttrm');
      expect(paths).not.toContain('model/layouts/v.ttrg');
      const detail = await client.getSymbolDetail('shop.er.entity.item');
      expect(
        detail!.referencedBy.some((r) => r.sourceUri === 'file:///world/model/a-binding.ttrm')
      ).toBe(true);
    } finally {
      client.dispose();
    }
  });
  it('reindexes references in the hosted model/ scope too', async () => {
    const source = new BffFilesDataSource(
      { api: '/api/model', token: 't', scope: { kind: 'served' } },
      {
        createClient: createTestClient,
        fetch: vi.fn<typeof fetch>(
          async () =>
            ({
              ok: true,
              json: async () => ({ sha: 'one', files: Object.fromEntries(ordered()) }),
            }) as Response
        ),
      }
    );
    try {
      await source.refresh();
      const detail = await source.getSymbolDetail('shop.er.entity.item');
      expect(
        detail!.referencedBy.some((r) => r.sourceUri === 'file:///repo/model/a-binding.ttrm')
      ).toBe(true);
      expect(await source.search({ query: 'outside' })).toEqual([]);
    } finally {
      source.dispose();
    }
  });
});
