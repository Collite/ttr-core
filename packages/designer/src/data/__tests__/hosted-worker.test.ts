// SPDX-License-Identifier: Apache-2.0
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { PassThrough } from 'node:stream';
import * as lsp from 'vscode-languageserver/node';
import { createServerConnection } from '@tatrman/lsp/server';
import { lspClientOver } from '../../lsp-connection.js';
import type { LspClient } from '../../lsp-client.js';
import { WorkerLspDataSource } from '../worker-lsp-data-source.js';
import { buildCatalog } from '../../shell/catalog.js';
import { projectLayout } from '../../model/layout-projection.js';

const ROOT = 'file:///world';
const files = new Map([
  ['modeler.toml', '[packages]\nlayout = "off"'],
  ['model/er/items.ttrm', `package shop\nmodel er
def entity item { attributes: [def attribute id { type: int }] }
def entity sale { attributes: [def attribute id { type: int }] }
def relation item_sale { from: er.entity.item, to: er.entity.sale }`],
  ['model/md/items.ttrm', `package shop\nmodel md
def domain Count { type: int }
def measure count { domain: md.Count, class: additive, aggregation: sum }
def cubelet Items { grain: [], measures: [count] }`],
  ['model/layouts/sales.ttrg', `graph sales { model: er, objects: [shop.er.entity.item, shop.er.entity.sale] }`],
]);
let client: LspClient;
let close: () => void;
let source: WorkerLspDataSource;

beforeAll(async () => {
  const toServer = new PassThrough();
  const toClient = new PassThrough();
  const serverConn = lsp.createConnection(new lsp.StreamMessageReader(toServer), new lsp.StreamMessageWriter(toClient));
  const clientConn = lsp.createConnection(new lsp.StreamMessageReader(toClient), new lsp.StreamMessageWriter(toServer));
  createServerConnection(serverConn, { layoutStore: new Map() });
  serverConn.listen(); clientConn.listen();
  close = () => { clientConn.dispose(); serverConn.dispose(); };
  client = await lspClientOver({ sendRequest: (m, p) => clientConn.sendRequest(m, p), sendNotification: (m, p) => clientConn.sendNotification(m, p), onNotification: (m, h) => clientConn.onNotification(m, h) }, { transportKind: 'browser', dispose: close });
  await client.setProjectRoot(ROOT, files.get('modeler.toml'));
  for (const [path, text] of files) if (path.endsWith('.ttrm') || path.endsWith('.ttrg')) await client.openDocument(`${ROOT}/${path}`, text);
  source = new WorkerLspDataSource(client, { projectRoot: ROOT, files });
});
afterAll(() => close());

describe('hosted worker catalog and reads over the real LSP', () => {
  it('lists whole schemas ahead of layouts and opens a whole-schema ref', async () => {
    const { graphs, symbols } = await source.listCatalog();
    const catalog = buildCatalog(graphs, symbols);
    expect(catalog.find((g) => g.kind === 'schema')!.items.map((i) => i.label)).toEqual(['er', 'md', 'er · sales']);
    expect((await source.getGraph('er'))!.nodes).toHaveLength(2);
    expect(catalog.find((g) => g.kind === 'cube')!.items[0].label).toBe('cube Items');
  });

  it('keeps packages on MD cards as on ER and DB cards', async () => {
    const md = await source.getSchemaGraph('md');
    expect(md!.nodes.length).toBeGreaterThan(0);
    expect(md!.nodes.every((n) => n.pkg === 'shop')).toBe(true);
  });

  it('opens model/layouts as a VL layout and derives the relation omitted from objects', async () => {
    const layout = await source.getLayoutDoc('sales');
    expect(layout.missing).toEqual([]);
    expect(layout.objects).toEqual(['shop.er.entity.item', 'shop.er.entity.sale']);
    const projected = projectLayout((await source.getSchemaGraph('er'))!, { model: 'er', objects: layout.objects }, { hops: 1 });
    expect(projected.graph.nodes).toHaveLength(2);
    expect(projected.graph.edges).toHaveLength(1);
    expect(projected.graph.edges[0].kind).toBe('relation');
    expect((await source.listLayouts())[0]).toMatchObject({ name: 'sales', objectCount: 2, missingCount: 0 });
  });
});
