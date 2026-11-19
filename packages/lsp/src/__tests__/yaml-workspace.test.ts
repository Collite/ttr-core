// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, afterEach } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'node:stream';
import { spawn } from 'node:child_process';
import { mkdtempSync, cpSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createServerConnection, type ServerOptions } from '../server.js';
import { YamlWorkspaceLoader } from '@tatrman/lint/yaml';

const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).reverse().forEach(fn => fn()); });
async function workspace(shared = false, extra: ServerOptions = {}) {
  const root = mkdtempSync(join(tmpdir(), 's73-lsp-'));
  if (shared) {
    cpSync(new URL('../../../../tests/conformance/dialect/demo-cs/tree/', import.meta.url), join(root, 'model'), { recursive: true });
    writeFileSync(join(root, 'notes.yaml'), 'entity: {Unrelated: plain}');
  } else cpSync(new URL('./fixtures/yaml-workspace/', import.meta.url), root, { recursive: true });
  cleanup.push(() => rmSync(root, { recursive: true, force: true }));
  const a = new PassThrough(), b = new PassThrough();
  const client = lsp.createConnection(new lsp.StreamMessageReader(a), new lsp.StreamMessageWriter(b));
  const server = lsp.createConnection(new lsp.StreamMessageReader(b), new lsp.StreamMessageWriter(a));
  cleanup.push(() => { client.dispose(); server.dispose(); });
  const loader = new YamlWorkspaceLoader([root]);
  createServerConnection(server, { ...extra, loadYamlDocuments: buffers => loader.load(buffers), yamlFilesChanged: changes => loader.notify(changes), yamlLanguage: (uri, text) => loader.language(uri, text) });
  client.listen(); server.listen();
  const diagnostics = new Map<string, lsp.Diagnostic[]>();
  client.onNotification('textDocument/publishDiagnostics', (p: lsp.PublishDiagnosticsParams) => diagnostics.set(p.uri, p.diagnostics));
  await client.sendRequest('initialize', { processId: null, rootUri: pathToFileURL(root).href, capabilities: {} });
  const uri = (file: string) => pathToFileURL(join(root, file)).href;
  const open = async (file: string, text = readFileSync(join(root, file), 'utf8')) => {
    client.sendNotification('textDocument/didOpen', { textDocument: { uri: uri(file), languageId: 'yaml', version: 1, text } });
    await new Promise(resolve => setTimeout(resolve, 250));
    await client.sendRequest('textDocument/documentSymbol', { textDocument: { uri: uri(file) } });
  };
  const change = async (file: string, text: string) => {
    client.sendNotification('textDocument/didChange', { textDocument: { uri: uri(file), version: 2 }, contentChanges: [{ text }] });
    await new Promise(resolve => setTimeout(resolve, 250));
    await client.sendRequest('textDocument/documentSymbol', { textDocument: { uri: uri(file) } });
  };
  const close = async (file: string) => {
    client.sendNotification('textDocument/didClose', { textDocument: { uri: uri(file) } });
    await new Promise(resolve => setTimeout(resolve, 250));
    await client.sendRequest('textDocument/documentSymbol', { textDocument: { uri: uri(file) } });
  };
  return { root, client, diagnostics, uri, open, change, close };
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
    ['entity:\n  Invoice:\n    typo: A customer\n', 'ys/unknown-key', 2, 4],
    ['entity:\n  Invoice:\n    popis: [wrong]\n', 'ys/profile-invalid', 2, 4],
    ['zaznamy:\n- druh: vazba\n  nazev: Broken\n  entita: demo.er.entity.Missing\n', 'ttr/unresolved-reference', 3, 2],
  ])('one diagnostic at the authored key/value: %s', async (text, code, line, character) => {
    const w = await workspace(true);
    const file = code.startsWith('ttr/') ? 'model/03-mixed.yaml' : 'model/02-mapping.yaml';
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
  it('converts code-point ranges and cursor positions to LSP UTF-16 after emoji', async () => {
    const w = await workspace();
    const text = 'entity: {Customer: {popis: "😀"}, Other: {popis: "second"}}';
    await w.open('model/entities.yaml', text);
    const hover = await w.client.sendRequest<lsp.Hover>('textDocument/hover', { textDocument: { uri: w.uri('model/entities.yaml') }, position: { line: 0, character: text.indexOf('Other') + 1 } });
    expect(JSON.stringify(hover)).toContain('second');
    const symbols = await w.client.sendRequest<lsp.DocumentSymbol[]>('textDocument/documentSymbol', { textDocument: { uri: w.uri('model/entities.yaml') } });
    expect(symbols.find(s => s.name === 'Other')!.selectionRange.start.character).toBe(text.indexOf('Other'));
    const bad = text.replace('popis: "second"', 'typo: "second"');
    await w.change('model/entities.yaml', bad);
    expect(w.diagnostics.get(w.uri('model/entities.yaml'))!.find(d => d.code === 'ys/unknown-key')!.range.start).toEqual({ line: 0, character: bad.indexOf('typo') });
  });
  it('removes stale symbols on model errors and restores saved profile state on close', async () => {
    const w = await workspace(); await w.open('model/entities.yaml');
    const original = readFileSync(join(w.root, 'model/entities.yaml'), 'utf8');
    await w.change('model/entities.yaml', 'entity: {Customer: {typo: bad}}');
    const symbols = await w.client.sendRequest<lsp.SymbolInformation[]>('workspace/symbol', { query: 'Customer' });
    expect(symbols).toEqual([]);
    await w.change('model/entities.yaml', original);
    const profile = readFileSync(join(w.root, 'model/ttr-profile.yaml'), 'utf8');
    await w.open('model/ttr-profile.yaml', profile.replace('property: description', 'property: attributes'));
    expect(w.diagnostics.get(w.uri('model/entities.yaml'))!.some(d => d.code === 'ys/profile-invalid')).toBe(true);
    await w.close('model/ttr-profile.yaml');
    expect(w.diagnostics.get(w.uri('model/entities.yaml'))!.some(d => d.code === 'ys/profile-invalid')).toBe(false);
    expect(await w.client.sendRequest<lsp.SymbolInformation[]>('workspace/symbol', { query: 'Customer' })).toHaveLength(1);
  });
  it('never offers dialect write-back, completion, formatting or TTR semantic tokens', async () => {
    const w = await workspace(); await w.open('model/entities.yaml');
    const textDocument = { uri: w.uri('model/entities.yaml') }, position = { line: 1, character: 4 };
    expect(await w.client.sendRequest('textDocument/prepareRename', { textDocument, position })).toBeNull();
    await expect(w.client.sendRequest('textDocument/rename', { textDocument, position, newName: 'Changed' })).rejects.toThrow(/entities.yaml:2 is a dialect YAML file/);
    expect(await w.client.sendRequest('textDocument/formatting', { textDocument, options: { tabSize: 2, insertSpaces: true } })).toEqual([]);
    expect(await w.client.sendRequest('textDocument/codeAction', { textDocument, range: { start: position, end: position }, context: { diagnostics: [] } })).toEqual([]);
    expect(await w.client.sendRequest('textDocument/completion', { textDocument, position })).toEqual({ isIncomplete: false, items: [] });
    expect(await w.client.sendRequest('textDocument/semanticTokens/full', { textDocument })).toEqual({ data: [] });
  });

  it('desktop stdio discovers marker-only workspaces and indexes closed YAML files', async () => {
    const root = mkdtempSync(join(tmpdir(), 's73-stdio-'));
    cpSync(new URL('./fixtures/yaml-workspace/', import.meta.url), root, { recursive: true });
    cleanup.push(() => rmSync(root, { recursive: true, force: true }));
    const child = spawn(process.execPath, [new URL('../../dist/server-stdio.js', import.meta.url).pathname], { stdio: ['pipe', 'pipe', 'pipe'] });
    const client = lsp.createConnection(new lsp.StreamMessageReader(child.stdout), new lsp.StreamMessageWriter(child.stdin));
    cleanup.push(() => { client.dispose(); child.kill(); });
    child.once('exit', () => client.dispose());
    client.listen();
    await client.sendRequest('initialize', { processId: null, rootUri: pathToFileURL(root).href, workspaceFolders: [{ uri: pathToFileURL(root).href, name: 'models' }], capabilities: {} });
    const uri = (file: string) => pathToFileURL(join(root, file)).href;
    expect(JSON.stringify(await client.sendRequest('textDocument/documentSymbol', { textDocument: { uri: uri('model/entities.yaml') } }))).toContain('Customer');
    expect(await client.sendRequest('textDocument/definition', { textDocument: { uri: uri('model/relations.yaml') }, position: { line: 2, character: 15 } })).toMatchObject({ uri: uri('model/entities.yaml'), range: { start: { line: 1, character: 2 } } });
  });

  it('uses the original shared demo-cs chain for default-string links, hover and symbols', async () => {
    const w = await workspace(true); await w.open('model/10-vazby.yaml');
    const hit = await w.client.sendRequest<lsp.Location>('textDocument/definition', { textDocument: { uri: w.uri('model/10-vazby.yaml') }, position: { line: 4, character: 9 } });
    expect(hit).toMatchObject({ uri: w.uri('model/07-fanout.yaml'), range: { start: { line: 5, character: 2 } } });
    const hover = await w.client.sendRequest<lsp.Hover>('textDocument/hover', { textDocument: { uri: w.uri('model/07-fanout.yaml') }, position: { line: 5, character: 4 } });
    expect(JSON.stringify(hover)).toContain('Dodávka');
    const symbols = await w.client.sendRequest<lsp.DocumentSymbol[]>('textDocument/documentSymbol', { textDocument: { uri: w.uri('model/07-fanout.yaml') } });
    expect(symbols.find(s => s.name === 'Dodavka')!.selectionRange.start).toEqual({ line: 5, character: 2 });
    const graph = await w.client.sendRequest<{ edges: { fromNode: string; toNode: string }[] }>('modeler/getModelGraph', { textDocument: { uri: w.uri('model/10-vazby.yaml') }, schema: 'er' });
    expect(graph.edges).toContainEqual(expect.objectContaining({ fromNode: 'demo.er.entity.Dodavka', toNode: 'demo.er.entity.Odberatel' }));
    await w.open('notes.yaml');
    expect(w.diagnostics.has(w.uri('notes.yaml'))).toBe(false);
  });

  it('refuses an atomic native rename when an SQL usage would edit YAML', async () => {
    const { parseSql, extract } = await import('@tatrman/sql');
    const { resolveManifest } = await import('@tatrman/semantics');
    const w = await workspace(false, {
      loadManifest: async () => resolveManifest(undefined, '/proj'),
      readConfigFile: async path => path.endsWith('modeler.toml') ? '[sql]\ndefault-dialect = "tsql"\n[[sql.namespace-map]]\nnamespace = "dbo"\ndatabase = "WH"\nschema = "dbo"\n[sql.defaults.tsql]\ndatabase = "WH"\nschema = "dbo"\n' : undefined,
      analyzeSqlBlock(value, dialect) {
      const { tree } = parseSql(value, dialect);
      return tree ? extract(tree, dialect) : undefined;
    } });
    await w.open('table.ttrm', 'package demo\nmodel db schema dbo\ndef table Customer { columns { def column id { type: int } } }');
    await w.open('model/sql.yaml', 'ttr: "0.22"\npackage: demo\nmodel: db\nschema: dbo\ndefs:\n- kind: query\n  id: Q\n  properties:\n    sourceText: {tag: sql, block: "SELECT id FROM Customer"}\n');
    const textDocument = { uri: w.uri('table.ttrm') }, position = { line: 2, character: 12 };
    const refs = await w.client.sendRequest<lsp.Location[]>('textDocument/references', { textDocument, position, context: { includeDeclaration: true } });
    expect(refs.some(ref => ref.uri === w.uri('model/sql.yaml'))).toBe(true);
    await expect(w.client.sendRequest('textDocument/rename', { textDocument, position, newName: 'Renamed' })).rejects.toThrow(/sql.yaml:.*dialect YAML/);
  });

  it('renames native-only packages and symbols, refusing only actual YAML dependencies', async () => {
    const w = await workspace();
    await w.open('native.ttrm', 'package isolated\nmodel er\ndef entity Solo {}');
    const request = (line: number, character: number, newName: string) => w.client.sendRequest<lsp.WorkspaceEdit>('textDocument/rename', {textDocument: {uri: w.uri('native.ttrm')}, position: {line, character}, newName});
    expect((await request(0, 10, 'other')).documentChanges?.length).toBeGreaterThan(0);
    expect((await request(2, 12, 'Other')).documentChanges?.length).toBeGreaterThan(0);
    await w.open('native.ttrm', 'package demo\nmodel er\ndef entity Solo {}');
    await expect(request(0, 10, 'other')).rejects.toThrow(/entities.yaml:.*dialect YAML/);
    await w.open('native.ttrm', 'package external\nmodel er\ndef entity External {}');
    await w.open('model/relations.yaml', 'relations:\n  ExternalUse:\n    od: external.er.entity.External\n    do: demo.er.entity.Customer\n');
    await expect(request(0, 10, 'other')).rejects.toThrow(/relations.yaml:3 is a dialect YAML file/);
    await expect(request(2, 12, 'Other')).rejects.toThrow(/\.yaml:\d+ is a dialect YAML file/);
  });
  it('coalesces a burst of YAML keystrokes into one load and ignores unrelated watchers', async () => {
    let calls = 0;
    const w = await workspace(false, { });
    // A separate injected loader keeps the assertion independent of cache internals.
    const a = new PassThrough(), b = new PassThrough();
    const client = lsp.createConnection(new lsp.StreamMessageReader(a), new lsp.StreamMessageWriter(b));
    const server = lsp.createConnection(new lsp.StreamMessageReader(b), new lsp.StreamMessageWriter(a));
    const loader = new YamlWorkspaceLoader([w.root]);
    createServerConnection(server, { loadYamlDocuments: buffers => { calls++; return loader.load(buffers); } });
    cleanup.push(() => {client.dispose(); server.dispose();}); client.listen(); server.listen();
    await client.sendRequest('initialize', {processId: null, rootUri: pathToFileURL(w.root).href, capabilities: {}});
    client.sendNotification('textDocument/didOpen', {textDocument: {uri: w.uri('model/entities.yaml'), languageId: 'ttr-yaml', version: 1, text: 'entity: {Customer: {popis: x}}'}});
    await new Promise(resolve => setTimeout(resolve, 300)); calls = 0;
    for (let i = 0; i < 10; i++) client.sendNotification('textDocument/didChange', {textDocument: {uri: w.uri('model/entities.yaml'), version: i + 2}, contentChanges: [{text: `entity: {Customer: {popis: x${i}}}`} ]});
    await new Promise(resolve => setTimeout(resolve, 350));
    expect(calls).toBe(1);
    client.sendNotification('workspace/didChangeWatchedFiles', {changes: [{uri: w.uri('notes.yaml'), type: 2}]});
    await new Promise(resolve => setTimeout(resolve, 300)); expect(calls).toBe(1);
  });

  it('keeps ignored CI YAML plain and answers canonical-header admission from the loader', async () => {
    const w = await workspace();
    mkdirSync(join(w.root, 'model/.github/workflows'), {recursive: true});
    await w.open('model/.github/workflows/ci.yml', 'notInProfile: CI');
    expect(w.diagnostics.has(w.uri('model/.github/workflows/ci.yml'))).toBe(false);
    expect(await w.client.sendRequest('modeler/yamlLanguage', {uri: w.uri('canonical.yaml'), text: '"ttr": 0.22\nmodel: er\ndefs: []'})).toBe('ttr-yaml');
    expect(await w.client.sendRequest('modeler/yamlLanguage', {uri: w.uri('model/.github/workflows/ci.yml'), text: 'notInProfile: CI'})).toBe('yaml');
  });

});
