// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'node:stream';
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServerConnection } from '@tatrman/lsp/server';
import { parseManifest, resolveManifest } from '@tatrman/semantics';
import { synthesize, applyWorkspaceEditToText } from '../../../packages/edit/dist/index.js';
import type { EditOp, EditArgs, SynthResult } from '../../../packages/edit/dist/index.js';

type Result = Exclude<SynthResult, { ok: true }> | {
  ok: true; edit: lsp.WorkspaceEdit; touched: string[];
  diagnostics: (lsp.Diagnostic & { uri: string; source: 'worker' })[];
};
const ROOT = 'file:///studio';
const vectors = fileURLToPath(new URL('../../../packages/edit/__tests__/synth/vectors/', import.meta.url));
const disposables: { dispose(): void }[] = [];
afterEach(() => { for (const d of disposables.splice(0)) d.dispose(); });
function files(root: string, prefix = root): Record<string, string> {
  return Object.fromEntries(readdirSync(root, { withFileTypes: true }).flatMap(e => {
    const p = join(root, e.name);
    return e.isDirectory() ? Object.entries(files(p, prefix)) : [[relative(prefix, p), readFileSync(p, 'utf8')]];
  }));
}
function snapshot(world: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(world).filter(([p]) => p !== 'modeler.toml').map(([p, t]) => [`${ROOT}/${p}`, t]));
}
async function boot(world: Record<string, string>, mode: 'node' | 'browser') {
  const a = new PassThrough(); const b = new PassThrough();
  const client = lsp.createConnection(new lsp.StreamMessageReader(a), new lsp.StreamMessageWriter(b));
  const server = lsp.createConnection(new lsp.StreamMessageReader(b), new lsp.StreamMessageWriter(a));
  disposables.push(client, server);
  const input = snapshot(world);
  const manifest = resolveManifest(parseManifest(world['modeler.toml']), '/studio');
  createServerConnection(server, mode === 'node' ? {
    loadManifest: async () => manifest,
    scanProjectFiles: async () => Object.entries(input).map(([uri, text]) => ({ uri, text })),
  } : {});
  client.listen(); server.listen();
  const published = new Map<string, lsp.Diagnostic[]>();
  client.onNotification('textDocument/publishDiagnostics', (p: lsp.PublishDiagnosticsParams) => published.set(p.uri, p.diagnostics));
  await client.sendRequest('initialize', { processId: null, rootUri: mode === 'node' ? ROOT : null, capabilities: {} });
  if (mode === 'browser') {
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: world['modeler.toml'] });
    for (const [uri, text] of Object.entries(input)) client.sendNotification('textDocument/didOpen', {
      textDocument: { uri, languageId: uri.endsWith('.mjs') ? 'javascript' : 'ttr', version: 1, text },
    });
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: world['modeler.toml'] });
  }
  const edit = (command: unknown) => client.sendRequest('modeler/edit', command) as Promise<Result>;
  const symbols = () => client.sendRequest('modeler/listSymbols', { includeMembers: true }) as Promise<{ symbols: { qname: string }[]; total: number }>;
  return { client, published, edit, symbols, input, manifest };
}
function command(dir: string): { op: EditOp; args: EditArgs } { return JSON.parse(readFileSync(join(dir, 'op.json'), 'utf8')); }
const cases = readdirSync(vectors).flatMap(op => readdirSync(join(vectors, op)).map(name => ({ op, name, dir: join(vectors, op, name) })));

it('removes the legacy edit stub from the LSP sources and README', () => {
  const root = fileURLToPath(new URL('../../../packages/lsp/', import.meta.url));
  const obsolete = ['edit-mode', 'not-available-in-v1'].join('-');
  for (const [path, text] of Object.entries(files(join(root, 'src')))) expect(text, path).not.toContain(obsolete);
  expect(readFileSync(join(root, 'README.md'), 'utf8')).not.toContain(obsolete);
});

describe.each(['node', 'browser'] as const)('modeler/edit (%s snapshot)', mode => {
  it.each(cases)('$op/$name preserves the pure vector result', async ({ dir }) => {
    const world = files(join(dir, 'world'));
    const h = await boot(world, mode);
    const cmd = command(dir);
    const expected = synthesize(cmd.op, cmd.args, { files: h.input, rootUri: ROOT, manifest: h.manifest });
    const before = await h.symbols();
    const result = await h.edit(cmd);
    if (!expected.ok) {
      expect(result).toEqual(expected);
      expect(await h.symbols()).toEqual(before);
      // A second synthesis sees identical bytes, including roster refs/ranges.
      expect(await h.edit(cmd)).toEqual(result);
    } else {
      expect(result).toMatchObject({ ok: true, edit: expected.edit, touched: expected.touched });
      if (!result.ok) return;
      expect(result.diagnostics.filter(d => d.severity === 1)).toEqual([]);
      for (const uri of result.touched.filter(u => u.endsWith('.ttrm'))) {
        expect(result.diagnostics.filter(d => d.uri === uri).map(({ uri: _u, ...d }) => d)).toEqual(
          (h.published.get(uri) ?? []).map(d => ({ ...d, source: 'worker' })));
      }
      if (cmd.op === 'insert-entity') {
        expect((await h.symbols()).symbols.some(s => s.qname.endsWith('.er.entity.' + (cmd.args as { name: string }).name))).toBe(true);
        expect(await h.edit(cmd)).toMatchObject({ ok: false, code: 'entity-exists' });
      }
    }
  });
  const physicalWorld = {
    'modeler.toml': '[packages]\nlayout="off"\n[bindings]\nrequired=true',
    'model/er/a.ttrm': 'package shop\nmodel er\ndef entity a { attributes: [def attribute id { type: int }] }\ndef entity b { attributes: [def attribute id { type: int }] }',
    'model/db/a.ttrm': 'package shop\nmodel db schema dbo\ndef table a { columns: [def column id { type: int }] }\ndef table b { columns: [def column id { type: int }] }',
    'model/binding/a.ttrm': 'package shop\nmodel binding\ndef er2db_entity a { entity: er.entity.a, target: { table: db.dbo.a } }\ndef er2db_entity b { entity: er.entity.b, target: { table: db.dbo.b } }',
    'model/lexicon/en/a.ttrm': 'package shop\nmodel lexicon locale en\ndef term taken { for: er.entity.a, forms: ["taken"] }',
  };
  it.each([
    { code: 'entity-exists', op: 'insert-entity', args: { name: 'a', from: 'db.dbo.a' } },
    { code: 'source-unknown', op: 'insert-entity', args: { name: 'c', from: 'db.dbo.missing' } },
    { code: 'attribute-needs-binding', op: 'add-attribute', args: { entity: 'er.entity.a', name: 'new', type: 'int' } },
    { code: 'attribute-exists', op: 'add-attribute', args: { entity: 'er.entity.a', name: 'id', type: 'int' } },
    { code: 'column-unknown', op: 'add-attribute', args: { entity: 'er.entity.a', name: 'new', type: 'int', column: 'db.dbo.missing.id' } },
    { code: 'entity-unknown', op: 'add-attribute', args: { entity: 'er.entity.missing', name: 'id', type: 'int' } },
    { code: 'fk-needs-binding', op: 'add-relation', args: { from: 'er.entity.a', to: 'er.entity.b', kind: 'one-to-many' } },
    { code: 'node-unknown', op: 'set-source', args: { nodeRef: 'er.entity.missing', text: 'def entity c {}' } },
    { code: 'term-exists', op: 'add-term', args: { lang: 'en', term: 'taken', target: 'er.entity.b' } },
    { code: 'target-unknown', op: 'add-term', args: { lang: 'en', term: 'new', target: 'er.entity.missing' } },
    { code: 'invalid-args', op: 'insert-entity', args: { name: 'x', file: '../escape.ttrm', from: 'db.dbo.a' } },
  ])('refuses $code without applying anything', async ({ code, op, args }) => {
    const h = await boot(physicalWorld, mode);
    const before = await h.symbols(); const published = structuredClone(h.published);
    const result = await h.edit({ op, args });
    expect(result).toMatchObject({ ok: false, code });
    expect(await h.symbols()).toEqual(before); expect(h.published).toEqual(published);
    expect(await h.edit({ op, args })).toEqual(result);
  });
  it('refuses ambiguous definitions without changing the cache', async () => {
    const h = await boot({ ...physicalWorld, 'model/er/duplicate.ttrm': physicalWorld['model/er/a.ttrm'] }, mode);
    const before = await h.symbols();
    expect(await h.edit({ op: 'remove-definition', args: { qname: 'shop.er.entity.a' } })).toMatchObject({ ok: false, code: 'ambiguous-reference' });
    expect(await h.symbols()).toEqual(before);
  });
  it('keeps a later staged edit when the host acknowledges an earlier one', async () => {
    const h = await boot(physicalWorld, mode);
    const uri = `${ROOT}/model/er/a.ttrm`;
    if (mode === 'node') h.client.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'ttr', version: 1, text: physicalWorld['model/er/a.ttrm'] } });
    await h.symbols();
    const a = await h.edit({ op: 'set-source', args: { nodeRef: 'er.entity.a', text: 'def entity first { attributes: [def attribute id { type: int }] }' } });
    if (!a.ok) throw new Error(JSON.stringify(a));
    const b = await h.edit({ op: 'set-source', args: { nodeRef: 'er.entity.first', text: 'def entity second { attributes: [def attribute id { type: int }] }' } });
    if (!b.ok) throw new Error(JSON.stringify(b));
    const first = applyWorkspaceEditToText(physicalWorld['model/er/a.ttrm'], a.edit, uri);
    h.client.sendNotification('textDocument/didChange', { textDocument: { uri, version: 2 }, contentChanges: [{ text: first }] });
    expect((await h.symbols()).symbols.some(s => s.qname === 'shop.er.entity.second')).toBe(true);
    h.client.sendNotification('textDocument/didChange', { textDocument: { uri, version: 3 }, contentChanges: [{ text: applyWorkspaceEditToText(first, b.edit, uri) }] });
    expect((await h.symbols()).symbols.some(s => s.qname === 'shop.er.entity.second')).toBe(true);
  });
  it('applies WorkspaceEdits through the same atomic cache and validates document versions', async () => {
    const h = await boot(physicalWorld, mode);
    const uri = `${ROOT}/model/er/a.ttrm`, created = `${ROOT}/model/er/new.ttrm`;
    h.client.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'ttr', version: 5, text: physicalWorld['model/er/a.ttrm'] } });
    const before = await h.symbols();
    const create = { kind: 'create', uri: created };
    const range = { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } };
    const write = { textDocument: { uri: created, version: null }, edits: [{ range, newText: 'package shop\nmodel er\ndef entity added { attributes: [def attribute id { type: int }] }' }] };
    // A bad later change must roll back the preceding CreateFile and write.
    await expect(h.client.sendRequest('modeler/applyWorkspaceEdit', { edit: { documentChanges: [create, write, { textDocument: { uri, version: 4 }, edits: [] }] } })).rejects.toMatchObject({ code: -32602 });
    expect(await h.symbols()).toEqual(before);
    const params = { edit: { documentChanges: [create, write] } };
    const dry = await h.client.sendRequest('modeler/applyWorkspaceEdit', { ...params, dryRun: true });
    expect(await h.symbols()).toEqual(before);
    expect(await h.client.sendRequest('modeler/applyWorkspaceEdit', params)).toEqual(dry);
    expect((await h.symbols()).symbols.some(s => s.qname === 'shop.er.entity.added')).toBe(true);
    await expect(h.client.sendRequest('modeler/applyWorkspaceEdit', params)).rejects.toMatchObject({ code: -32602 });
    await expect(h.client.sendRequest('modeler/applyWorkspaceEdit', { edit: { changes: { [uri]: [{ range: { start: { line: 999, character: 0 }, end: { line: 999, character: 0 } }, newText: 'bad' }] } } })).rejects.toMatchObject({ code: -32602 });
  });
  it('dry run returns the real edit and diagnostics without changing symbols, cursor or notifications', async () => {
    const dir = join(vectors, 'insert-entity/on-query');
    const h = await boot(files(join(dir, 'world')), mode);
    const before = await h.symbols();
    const page = await h.client.sendRequest('modeler/listSymbols', { limit: 1 }) as { nextCursor: string };
    const published = structuredClone(h.published);
    const dry = await h.edit({ ...command(dir), dryRun: true });
    expect(dry.ok).toBe(true);
    expect(await h.symbols()).toEqual(before);
    expect(h.published).toEqual(published);
    await expect(h.client.sendRequest('modeler/listSymbols', { limit: 1, cursor: page.nextCursor })).resolves.toBeDefined();
    const real = await h.edit(command(dir));
    expect(dry).toEqual(real);
    expect((await h.symbols()).total).toBeGreaterThan(before.total);
    // A newly created binding/term file must be readable before the host didOpen.
    expect(await h.edit({ op: 'add-term', args: { lang: 'cs', term: 'přehled reklamací', target: 'hartland.er.entity.returns_review' } })).toMatchObject({ ok: true, touched: [] });
  });
  it('set-source alias returns worker diagnostics, clears old symbols, and accepts the host full-text acknowledgement', async () => {
    const world = { 'modeler.toml': '[packages]\nlayout="off"', 'model/er/a.ttrm': 'package shop\nmodel er\n// 😀 keep\ndef entity old {}\n' };
    const h = await boot(world, mode);
    const uri = `${ROOT}/model/er/a.ttrm`;
    if (mode === 'node') h.client.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'ttr', version: 1, text: world['model/er/a.ttrm'] } });
    await h.symbols();
    expect(await h.client.sendRequest('modeler/applyGraphEdit', { op: 'remove-definition', args: { nodeRef: 'shop.er.entity.old', text: 'def entity accidental {}' } })).toMatchObject({ ok: false, code: 'invalid-args', deprecated: 'modeler/edit' });
    const args = { nodeRef: 'shop.er.entity.old', text: 'def entity renamed { nameAttribute: missing }' };
    const preview = await h.edit({ op: 'set-source', args, dryRun: true });
    const result = await h.client.sendRequest('modeler/applyGraphEdit', { op: 'set-source', args }) as Result & { deprecated: string };
    expect(result).toEqual({ ...preview, deprecated: 'modeler/edit' });
    if (!result.ok) throw new Error(JSON.stringify(result));
    expect(result.diagnostics.some(d => d.uri === uri && d.source === 'worker' && d.severity === 1 && d.code && d.range)).toBe(true);
    expect((await h.symbols()).symbols.map(s => s.qname)).toEqual(['shop.er.entity.renamed']);
    const text = applyWorkspaceEditToText(world['model/er/a.ttrm'], result.edit, uri);
    h.client.sendNotification('textDocument/didChange', { textDocument: { uri, version: 2 }, contentChanges: [{ text }] });
    expect((await h.symbols()).symbols.map(s => s.qname)).toEqual(['shop.er.entity.renamed']);
    // The returned patch is applied once by the host, never on top of the already staged text.
    const final = await h.edit({ op: 'set-source', args: { nodeRef: 'shop.er.entity.renamed', text: 'def entity final { attributes: [def attribute id { type: int }] }' } });
    expect(final.ok).toBe(true);
    if (final.ok) expect(final.diagnostics.filter(d => d.severity === 1)).toEqual([]);
  });
  it('edits an existing percent-encoded source URI', async () => {
    const world = { 'modeler.toml': '[packages]\nlayout="off"', 'model/er/a%20b.ttrm': 'package shop\nmodel er\ndef entity a { attributes: [def attribute id { type: int }] }' };
    const h = await boot(world, mode);
    expect(await h.edit({ op: 'set-source', args: { nodeRef: 'er.entity.a', text: 'def entity renamed { attributes: [def attribute id { type: int }] }' } })).toMatchObject({ ok: true, touched: [`${ROOT}/model/er/a%20b.ttrm`] });
    expect((await h.symbols()).symbols.some(s => s.qname === 'shop.er.entity.renamed')).toBe(true);
  });
  it('rejects malformed wire arguments without mutating the snapshot', async () => {
    const h = await boot(physicalWorld, mode); const before = await h.symbols();
    for (const params of [
      { op: 'insert-entity', args: null }, { op: 'insert-entity', args: { name: 3 } },
      { op: 'add-relation', args: { from: 'a', to: 'b', kind: 'invalid' } },
      { op: 'add-term', args: { lang: 'de', term: 'x', target: 'er.entity.a' } },
      { op: 'add-attribute', args: { entity: 'a', name: 'x', type: {} } },
    ]) expect(await h.edit(params)).toMatchObject({ ok: false, code: 'invalid-args' });
    await expect(h.edit({ op: 'insert-entity', args: { name: 'x' }, dryRun: 'true' })).rejects.toMatchObject({ code: -32602 });
    expect(await h.symbols()).toEqual(before);
  });
  it('accepts ranges regardless of JSON field order and rejects overlapping edits atomically', async () => {
    const h = await boot(physicalWorld, mode); const uri = `${ROOT}/model/er/a.ttrm`;
    const before = await h.symbols();
    const range = { start: { character: 0, line: 0 }, end: { character: 0, line: 0 } };
    const params = { edit: { changes: { [uri]: [{ range, newText: '// authoring 😀\n' }] } } };
    const result = await h.client.sendRequest('modeler/applyWorkspaceEdit', params) as Result;
    expect(result).toMatchObject({ ok: true, touched: [uri] });
    const overlap = { start: { line: 0, character: 0 }, end: { line: 1, character: 0 } };
    await expect(h.client.sendRequest('modeler/applyWorkspaceEdit', { edit: { changes: { [uri]: [{ range: overlap, newText: 'bad' }, { range: overlap, newText: 'bad' }] } } })).rejects.toMatchObject({ code: -32602 });
    expect(await h.symbols()).toEqual(before);
    expect(await h.edit({ op: 'set-source', args: { nodeRef: 'er.entity.a', text: 'def entity preserved { attributes: [def attribute id { type: int }] }' } })).toMatchObject({ ok: true });
  });
  it('roster-only references prevent removal and force removes the whole line', async () => {
    const h = await boot({ 'modeler.toml': '[packages]\nlayout="off"', 'model/er/a.ttrm': 'package shop\nmodel er\ndef entity a {}', 'model/tests/a.mjs': "const roster = [\n  'a',\n];\n" }, mode);
    expect(await h.edit({ op: 'remove-definition', args: { qname: 'shop.er.entity.a' } })).toMatchObject({ ok: false, code: 'would-orphan', refs: [{ uri: `${ROOT}/model/tests/a.mjs`, kind: 'roster' }] });
    const removed = await h.edit({ op: 'remove-definition', args: { qname: 'shop.er.entity.a', force: true } });
    expect(removed).toMatchObject({ ok: true, touched: [`${ROOT}/model/er/a.ttrm`, `${ROOT}/model/tests/a.mjs`] });
    if (removed.ok) expect(removed.diagnostics.filter(d => d.severity === 1)).toEqual([]);
  });
});
