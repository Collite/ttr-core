// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, afterEach } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'node:stream';
import { mkdtempSync, cpSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createServerConnection } from '../server.js';
import { YamlWorkspaceLoader } from '@tatrman/lint/yaml';

const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).reverse().forEach(fn => fn()); });
async function workspace() {
  const root = mkdtempSync(join(tmpdir(), 's73-lsp-'));
  cpSync(new URL('./fixtures/yaml-workspace/', import.meta.url), root, { recursive: true });
  cleanup.push(() => rmSync(root, { recursive: true, force: true }));
  const a = new PassThrough(), b = new PassThrough();
  const client = lsp.createConnection(new lsp.StreamMessageReader(a), new lsp.StreamMessageWriter(b));
  const server = lsp.createConnection(new lsp.StreamMessageReader(b), new lsp.StreamMessageWriter(a));
  cleanup.push(() => { client.dispose(); server.dispose(); });
  const loader = new YamlWorkspaceLoader([root]);
  createServerConnection(server, { loadYamlDocuments: buffers => loader.load(buffers) });
  client.listen(); server.listen();
  const diagnostics = new Map<string, lsp.Diagnostic[]>();
  client.onNotification('textDocument/publishDiagnostics', (p: lsp.PublishDiagnosticsParams) => diagnostics.set(p.uri, p.diagnostics));
  await client.sendRequest('initialize', { processId: null, rootUri: pathToFileURL(root).href, capabilities: {} });
  const uri = (file: string) => pathToFileURL(join(root, file)).href;
  const open = async (file: string, text = readFileSync(join(root, file), 'utf8')) => {
    client.sendNotification('textDocument/didOpen', { textDocument: { uri: uri(file), languageId: 'yaml', version: 1, text } });
    await client.sendRequest('textDocument/documentSymbol', { textDocument: { uri: uri(file) } });
  };
  return { root, client, diagnostics, uri, open };
}

describe('S7.3 demo-cs/1 mixed workspace', () => {
  it('opens a marked dialect, leaving identical plain YAML outside the marker untouched', async () => {
    const w = await workspace();
    await w.open('model/entities.yaml'); await w.open('notes.yaml');
    const symbols = await w.client.sendRequest<lsp.DocumentSymbol[]>('textDocument/documentSymbol', { textDocument: { uri: w.uri('model/entities.yaml') } });
    expect(JSON.stringify(symbols)).toContain('Customer');
    expect(await w.client.sendRequest('textDocument/documentSymbol', { textDocument: { uri: w.uri('notes.yaml') } })).toEqual([]);
    expect(w.diagnostics.has(w.uri('notes.yaml'))).toBe(false);
  });
  it.each([
    ['entity:\n  Customer:\n    typo: A customer\n', 'ys/unknown-key', 2, 4],
    ['entity:\n  Customer:\n    popis: [wrong]\n', 'ys/profile-invalid', 2, 4],
    ['relations:\n  Purchases:\n    od: demo.er.entity.Missing\n    do: demo.er.entity.Customer\n', 'ttr/unresolved-reference', 2, 4],
  ])('one diagnostic at the authored key/value: %s', async (text, code, line, character) => {
    const w = await workspace();
    const file = code.startsWith('ttr/') ? 'model/relations.yaml' : 'model/entities.yaml';
    await w.open(file, text);
    const errors = w.diagnostics.get(w.uri(file))!.filter(d => d.code === code);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatchObject({ code, range: { start: { line, character } } });
  });
  it('hovers canonical properties and lists authored definition ranges', async () => {
    const w = await workspace(); await w.open('model/entities.yaml');
    const hover = await w.client.sendRequest<lsp.Hover>('textDocument/hover', { textDocument: { uri: w.uri('model/entities.yaml') }, position: { line: 1, character: 4 } });
    expect(JSON.stringify(hover)).toContain('entity'); expect(JSON.stringify(hover)).toContain('A customer');
    const symbols = await w.client.sendRequest<lsp.DocumentSymbol[]>('textDocument/documentSymbol', { textDocument: { uri: w.uri('model/entities.yaml') } });
    expect(JSON.stringify(symbols)).toContain('Customer');
    const flatten = (ss: lsp.DocumentSymbol[]): lsp.DocumentSymbol[] => ss.flatMap(s => [s, ...flatten(s.children ?? [])]);
    expect(flatten(symbols).find(s => s.name === 'Customer')!.selectionRange.start).toEqual({ line: 1, character: 2 });
  });
  it('cross-file definition lands on the dialect and refreshes an unsaved profile', async () => {
    const w = await workspace(); await w.open('model/relations.yaml');
    const hit = await w.client.sendRequest<lsp.Location>('textDocument/definition', { textDocument: { uri: w.uri('model/relations.yaml') }, position: { line: 2, character: 15 } });
    expect(hit).toMatchObject({ uri: w.uri('model/entities.yaml'), range: { start: { line: 1, character: 2 } } });
    const profile = readFileSync(join(w.root, 'model/ttr-profile.yaml'), 'utf8');
    await w.open('model/ttr-profile.yaml', profile.replace('property: description', 'property: attributes'));
    expect(w.diagnostics.get(w.uri('model/entities.yaml'))?.some(d => d.code === 'ys/profile-invalid')).toBe(true);
    writeFileSync(join(w.root, 'model/ttr-profile.yaml'), profile);
    await w.client.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: w.uri('model/ttr-profile.yaml'), type: 2 }] });
  });
});
