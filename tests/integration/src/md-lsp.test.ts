// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';
import { mkdtempSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { pathToFileURL } from 'url';

// Phase 2 (2F) — MD language features through the standard LSP methods: md/*
// diagnostics, hover/go-to-definition on MD refs, and completion of `calc:`
// (catalog names) and `domain:` (project domains).

function createPairedConnection(): { client: lsp.Connection; server: lsp.Connection } {
  const ct = new PassThrough({ objectMode: true });
  const st = new PassThrough({ objectMode: true });
  const client = lsp.createConnection(
    new lsp.StreamMessageReader(ct as unknown as NodeJS.ReadableStream),
    new lsp.StreamMessageWriter(st as unknown as NodeJS.WritableStream)
  ) as lsp.Connection;
  const server = lsp.createConnection(
    new lsp.StreamMessageReader(st as unknown as NodeJS.ReadableStream),
    new lsp.StreamMessageWriter(ct as unknown as NodeJS.WritableStream)
  ) as lsp.Connection;
  client.listen();
  server.listen();
  return { client, server };
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const MODEL = [
  'model md',
  'def domain Money { type: decimal }',
  'def domain Day { type: date }',
  'def measure net { domain: md.Money, aggregation: sum }',
  'def map m { from: md.Day, to: md.Bad, calc: monthOfDate }',
  '',
].join('\n');

describe('Phase 2 — MD LSP features', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;
  let uri: string;
  const diagnostics = new Map<string, lsp.Diagnostic[]>();
  const lineOf = (n: number) => MODEL.split('\n')[n];

  beforeAll(async () => {
    const root = mkdtempSync(join(tmpdir(), 'modeler-md-'));
    writeFileSync(join(root, 'modeler.toml'), '[project]\nname = "md"\n', 'utf-8');
    writeFileSync(join(root, 'm.ttrm'), MODEL, 'utf-8');
    uri = pathToFileURL(join(root, 'm.ttrm')).href;

    const pair = createPairedConnection();
    client = pair.client;
    server = pair.server;
    const diagnosticsPublished = new Promise<void>((resolve) => {
      client.onNotification('textDocument/publishDiagnostics', (p: lsp.PublishDiagnosticsParams) => {
        diagnostics.set(p.uri, p.diagnostics);
        if (p.uri === uri) resolve();
      });
    });
    createServerConnection(server, {
      async scanProjectFiles() {
        return [{ uri, text: MODEL }];
      },
    });

    await client.sendRequest('initialize', { processId: null, rootUri: pathToFileURL(root).href, capabilities: {} });
    client.sendNotification('initialized', {});
    client.sendNotification('textDocument/didOpen', {
      textDocument: { uri, languageId: 'ttr', version: 1, text: MODEL },
    });
    // Concurrent CI suites can keep diagnostics in flight for longer than
    // 120 ms. Wait for delivery, then assert the notification's contents below.
    await diagnosticsPublished;
  });

  afterAll(async () => {
    await sleep(30);
    client.dispose();
    server.dispose();
  });

  it('publishes md/unknown-ref for the dangling md.Bad domain ref', () => {
    const d = diagnostics.get(uri) ?? [];
    expect(d.some((x) => x.code === 'md/unknown-ref')).toBe(true);
  });

  it('hover on a domain ref resolves to its def', async () => {
    const ch = lineOf(3).indexOf('md.Money') + 2;
    const res = (await client.sendRequest('textDocument/hover', {
      textDocument: { uri },
      position: { line: 3, character: ch },
    })) as lsp.Hover | null;
    const value = res && typeof res.contents === 'object' && 'value' in res.contents ? res.contents.value : '';
    expect(value).toContain('md.domain.Money');
  });

  it('go-to-definition on md.Day jumps to the domain def (line 2)', async () => {
    const ch = lineOf(4).indexOf('md.Day') + 2;
    const res = (await client.sendRequest('textDocument/definition', {
      textDocument: { uri },
      position: { line: 4, character: ch },
    })) as lsp.Location | lsp.Location[] | null;
    const loc = Array.isArray(res) ? res[0] : res;
    expect(loc?.range.start.line).toBe(2);
  });

  it('completion after `calc:` lists catalog names', async () => {
    const ch = lineOf(4).indexOf('monthOfDate');
    const res = (await client.sendRequest('textDocument/completion', {
      textDocument: { uri },
      position: { line: 4, character: ch },
    })) as lsp.CompletionList;
    const labels = res.items.map((i) => i.label);
    expect(labels).toContain('truncToDay');
    expect(labels).toContain('monthOfDate');
  });

  it('completion after `domain:` lists project domains', async () => {
    const ch = lineOf(3).indexOf('md.Money');
    const res = (await client.sendRequest('textDocument/completion', {
      textDocument: { uri },
      position: { line: 3, character: ch },
    })) as lsp.CompletionList;
    expect(res.items.map((i) => i.label)).toContain('md.Money');
  });
});
