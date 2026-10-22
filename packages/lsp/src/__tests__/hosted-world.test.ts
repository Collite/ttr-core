// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PassThrough } from 'node:stream';
import * as lsp from 'vscode-languageserver/node';
import { createServerConnection } from '../server.js';

const ROOT = 'file:///world';
const MANIFEST = `[schemas]\ndeclared = ["db", "er", "md", "binding", "lexicon"]
[schemas.warehouse]
database = "world"
db-schema = "public"
[defaults]
schema = "warehouse"
[packages]
layout = "off"
[bindings]
required = true
`;
const FILES = {
  'model/db/items.ttrm': 'package shop\nmodel db\ndef table item { columns: [def column id { type: int, isKey: true }] }',
  'model/er/items.ttrm': 'package shop\nmodel er\ndef entity item { attributes: [def attribute id { type: int, isKey: true }] }',
  'model/binding/items.ttrm': 'package shop\nmodel binding\ndef er2db_entity item_map { entity: shop.er.item, target: { table: shop.db.warehouse.item } }',
  'model/md/items.ttrm': `package shop\nmodel md
def domain Count { type: int }
def measure count { domain: md.Count, class: additive, aggregation: sum }
def cubelet Items { grain: [], measures: [count] }`,
  'model/lexicon/en/items.ttrm': 'package shop\nmodel lexicon locale en\ndef term item_term { for: er.entity.item, forms: ["Item"] }',
};

describe('the hosted worker world (shared Node/browser handlers)', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;
  let diagnostics: Map<string, lsp.Diagnostic[]>;

  beforeEach(async () => {
    const toServer = new PassThrough();
    const toClient = new PassThrough();
    server = lsp.createConnection(new lsp.StreamMessageReader(toServer), new lsp.StreamMessageWriter(toClient));
    client = lsp.createConnection(new lsp.StreamMessageReader(toClient), new lsp.StreamMessageWriter(toServer));
    createServerConnection(server, { layoutStore: new Map() });
    server.listen();
    client.listen();
    diagnostics = new Map();
    client.onNotification('textDocument/publishDiagnostics', (p: lsp.PublishDiagnosticsParams) => diagnostics.set(p.uri, p.diagnostics));
    await client.sendRequest('initialize', { processId: null, rootUri: null, capabilities: {} });
  });
  afterEach(() => { client.dispose(); server.dispose(); });

  async function load(manifest?: string) {
    const result = await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest });
    for (const [path, text] of Object.entries(FILES)) {
      await client.sendNotification('textDocument/didOpen', { textDocument: { uri: `${ROOT}/${path}`, languageId: 'ttr', version: 1, text } });
    }
    // Revalidate after the whole map is loaded; request completion is the barrier.
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest });
    return result;
  }

  it('loads the supplied TOML and resolves the model with no errors', async () => {
    expect(await load(MANIFEST)).toMatchObject({ ok: true, manifest: { bindings: { required: true } } });
    const errors = [...diagnostics.values()].flat().filter((d) => d.severity === 1);
    expect(errors).toEqual([]);
    expect([...diagnostics.values()].flat().some((d) => d.code === 'lsp/manifest-missing')).toBe(false);
  });

  it('warns once per root when the host omits the manifest, and keeps defaults', async () => {
    let warnings = 0;
    client.onNotification('textDocument/publishDiagnostics', (p: lsp.PublishDiagnosticsParams) => {
      warnings += p.diagnostics.filter((d) => d.code === 'lsp/manifest-missing').length;
    });
    expect(await load()).toMatchObject({ ok: true, manifest: { bindings: { required: false }, defaults: {} } });
    expect(warnings).toBe(1);
    const page = await client.sendRequest('modeler/listSymbols', { kind: 'table' }) as { symbols: { qname: string }[] };
    expect(page.symbols[0].qname).toBe('shop.db.dbo.table.item');
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: MANIFEST });
    expect([...diagnostics.values()].flat().filter((d) => d.severity === 1)).toEqual([]);
  });

  it('lists populated schema subjects with object and package counts', async () => {
    await load(MANIFEST);
    const result = await client.sendRequest('modeler/listSchemaSubjects', {});
    expect(result).toEqual({ subjects: [
      { schema: 'er', objectCount: 1, packageCount: 1 },
      { schema: 'db', objectCount: 1, packageCount: 1 },
      { schema: 'md', objectCount: 3, packageCount: 1 },
    ] });
  });

  it('rejects malformed TOML without replacing the working configuration', async () => {
    await load(MANIFEST);
    await expect(client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: '[bindings' })).rejects.toThrow();
    const page = await client.sendRequest('modeler/listSymbols', { kind: 'table' }) as { symbols: { qname: string }[] };
    expect(page.symbols[0].qname).toBe('shop.db.warehouse.table.item');
  });

  it('pages symbols without truncation, preserving filters and member expansion', async () => {
    await load(MANIFEST);
    const all: string[] = [];
    let cursor: string | undefined;
    let total = 0;
    do {
      const page = await client.sendRequest('modeler/listSymbols', { cursor, limit: 2 }) as { symbols: { qname: string }[]; total: number; nextCursor?: string };
      expect(page.symbols.length).toBeLessThanOrEqual(2);
      total = page.total;
      all.push(...page.symbols.map((s) => s.qname));
      cursor = page.nextCursor;
    } while (cursor);
    expect(all.length).toBe(total);
    expect(new Set(all).size).toBe(total);
    expect(total).toBeGreaterThanOrEqual(6);
    const filtered = await client.sendRequest('modeler/listSymbols', { kind: 'entity', package: 'shop', includeMembers: true }) as { symbols: { kind: string }[]; total: number };
    expect(filtered.symbols.map((s) => s.kind)).toEqual(['entity', 'member']);
    expect(filtered.total).toBe(2);
    await expect(client.sendRequest('modeler/listSymbols', { cursor: 'broken' })).rejects.toThrow();
    await expect(client.sendRequest('modeler/listSymbols', { limit: 0 })).rejects.toThrow();
  });
});
