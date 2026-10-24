// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as parser from '@tatrman/parser';
import type { ModelGraph, SymbolPage } from '@tatrman/lsp';
import { PassThrough } from 'node:stream';
import * as lsp from 'vscode-languageserver/node';
import { createServerConnection } from '@tatrman/lsp/server';

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

  it('indexes and diagnoses each didOpen once in the filesystem-free worker', async () => {
    let publications = 0;
    client.onNotification('textDocument/publishDiagnostics', (p: lsp.PublishDiagnosticsParams) => {
      if (p.uri.endsWith('/model/er/items.ttrm')) publications++;
    });
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: MANIFEST });
    await client.sendNotification('textDocument/didOpen', { textDocument: { uri: `${ROOT}/model/er/items.ttrm`, languageId: 'ttr', version: 1, text: FILES['model/er/items.ttrm'] } });
    expect(await client.sendRequest('modeler/listSchemaSubjects', {})).toMatchObject({ subjects: [{ schema: 'er', objectCount: 1 }] });
    expect(publications).toBe(1);
  });

  it('loads the supplied TOML and resolves the model with no errors', async () => {
    expect(await load(MANIFEST)).toMatchObject({ ok: true, manifest: { bindings: { required: true } } });
    const errors = [...diagnostics.values()].flat().filter((d) => d.severity === 1);
    expect(errors).toEqual([]);
    expect([...diagnostics.values()].flat().some((d) => d.code === 'lsp/manifest-missing')).toBe(false);
  });

  it('keeps one missing-manifest warning published until a manifest is supplied', async () => {
    expect(await load()).toMatchObject({ ok: true, manifest: { bindings: { required: false }, defaults: {} } });
    const current = () => diagnostics.get(`${ROOT}/modeler.toml`) ?? [];
    expect(current().filter((d) => d.code === 'lsp/manifest-missing')).toHaveLength(1);
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: MANIFEST });
    expect(current().filter((d) => d.code === 'lsp/manifest-missing')).toHaveLength(0);
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

  it('lists renderable cnc subjects and leaves cn out', async () => {
    await client.sendNotification('textDocument/didOpen', { textDocument: { uri: `${ROOT}/old.ttrm`, languageId: 'ttr', version: 1, text: 'model cnc\ndef entity Item {}' } });
    await client.sendNotification('textDocument/didOpen', { textDocument: { uri: `${ROOT}/new.ttrm`, languageId: 'ttr', version: 1, text: 'model cn\ndef kind Product {}' } });
    expect(await client.sendRequest('modeler/listSchemaSubjects', {})).toEqual({ subjects: [{ schema: 'cnc', objectCount: 1, packageCount: 1 }] });
  });

  it('stamps the effective package without changing existing graph qnames', async () => {
    const uri = `${ROOT}/sales/items.ttrm`;
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: '[packages]\nroot = "shop"\nlayout = "strict"' });
    await client.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'ttr', version: 1, text: 'model er\ndef entity item {}' } });
    const graph = await client.sendRequest('modeler/getModelGraph', { textDocument: { uri }, schema: 'er' }) as ModelGraph;
    expect(graph.nodes[0]).toMatchObject({ pkg: 'shop.sales', qname: 'er.entity.item' });
    const page = await client.sendRequest('modeler/listSymbols', { kind: 'entity' }) as SymbolPage;
    expect(page.symbols[0].qname).toBe('shop.sales.er.entity.item');
  });

  it('reuses parsed member details across pages and rejects cursors after a change', async () => {
    await load(MANIFEST);
    const parse = vi.spyOn(parser, 'parseString');
    try {
      let page = await client.sendRequest('modeler/listSymbols', { includeMembers: true, limit: 2 }) as SymbolPage;
      const firstCount = parse.mock.calls.length;
      expect(firstCount).toBe(0); // didOpen already parsed these unchanged documents
      const listed = [...page.symbols];
      const cursor = page.nextCursor;
      let pages = 1;
      while (page.nextCursor) {
        page = await client.sendRequest('modeler/listSymbols', { includeMembers: true, limit: 2, cursor: page.nextCursor }) as SymbolPage;
        listed.push(...page.symbols);
        pages++;
      }
      expect(listed.map(symbol => symbol.kind)).toContain('member');
      expect(pages).toBeGreaterThanOrEqual(3);
      expect(parse.mock.calls.length).toBe(firstCount);
      await client.sendNotification('textDocument/didChange', { textDocument: { uri: `${ROOT}/model/er/items.ttrm`, version: 2 }, contentChanges: [{ text: FILES['model/er/items.ttrm'] + '\ndef entity another {}' }] });
      await expect(client.sendRequest('modeler/listSymbols', { includeMembers: true, cursor })).rejects.toThrow('stale cursor');
    } finally { parse.mockRestore(); }
  });

  it('loads malformed TOML with defaults and a located error diagnostic', async () => {
    expect(await load('[bindings')).toMatchObject({ ok: true, manifest: { bindings: { required: false } } });
    expect(diagnostics.get(`${ROOT}/modeler.toml`)).toEqual([expect.objectContaining({
      code: 'lsp/manifest-invalid', severity: 1, message: expect.stringContaining('Default manifest settings apply.'),
      range: { start: { line: 0, character: expect.any(Number) }, end: { line: 0, character: expect.any(Number) } },
    })]);
    const subjects = await client.sendRequest('modeler/listSchemaSubjects', {}) as { subjects: { schema: string }[] };
    expect(subjects.subjects.map((s) => s.schema)).toEqual(['er', 'db', 'md']);
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
