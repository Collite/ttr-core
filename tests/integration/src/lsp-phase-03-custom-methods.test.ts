// SPDX-License-Identifier: Apache-2.0
// Worker-mode (in-browser LSP) coverage — the `modeler/*` custom methods this
// file exercises are the offline-fallback path (TP-5 T5.2, decided 2026-07-15:
// kept alongside `ttrm/*`/`ttr-designer-server`, not retired). Intentional
// coexistence, not drift — see tatrman/CLAUDE.md "Text is canonical".
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';
import path from 'path';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { writeFileSync, mkdirSync, rmSync } from 'node:fs';

const samplesDir = path.resolve(__dirname, '../../../samples');

async function getAllTtrFiles(dir: string, excludeDirs: string[] = []): Promise<string[]> {
  const results: string[] = [];
  const fs = await import('fs/promises');
  const entries = await fs.readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (excludeDirs.includes(entry.name)) continue;
      results.push(...await getAllTtrFiles(fullPath, excludeDirs));
    } else if (entry.isFile() && entry.name.endsWith('.ttrm')) {
      results.push(fullPath);
    }
  }
  return results;
}

function createPairedConnection(): { client: lsp.Connection; server: lsp.Connection } {
  const clientTransport = new PassThrough({ objectMode: true });
  const serverTransport = new PassThrough({ objectMode: true });

  const clientReader = new lsp.StreamMessageReader(clientTransport as unknown as NodeJS.ReadableStream);
  const clientWriter = new lsp.StreamMessageWriter(serverTransport as unknown as NodeJS.WritableStream);
  const client = lsp.createConnection(clientReader, clientWriter) as lsp.Connection;

  const serverReader = new lsp.StreamMessageReader(serverTransport as unknown as NodeJS.ReadableStream);
  const serverWriter = new lsp.StreamMessageWriter(clientTransport as unknown as NodeJS.WritableStream);
  const server = lsp.createConnection(serverReader, serverWriter) as lsp.Connection;

  client.listen();
  server.listen();

  return { client, server };
}

async function sleep(ms: number): Promise<void> {
  await new Promise(resolve => setTimeout(resolve, ms));
}

describe('Phase 3 custom LSP methods', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;

  beforeAll(async () => {
    const pair = createPairedConnection();
    client = pair.client;
    server = pair.server;
    createServerConnection(server);
    await client.sendRequest('initialize', { processId: null, rootUri: null, capabilities: {} });
    client.sendNotification('initialized', {});
  });

  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  it('4.1 getLayout returns emptyLayout() when no .modeler/ directory exists', async () => {
    const emptyRoot = join(tmpdir(), `modeler-test-empty-${Date.now()}`);
    const result = await client.sendRequest('modeler/getLayout', { projectRoot: emptyRoot }) as {
      version: number;
      nodes: unknown;
      edges: unknown;
    };
    expect(result.version).toBe(1);
    expect(result).toHaveProperty('nodes');
    expect(result).toHaveProperty('edges');
  });

  it('4.2 setLayout via graphUri then getLayout round-trips the same LayoutFile', async () => {
    const graphPath = join(tmpdir(), `modeler-test-layout-${Date.now()}.ttrg`);
    let graphContent = `graph test { model: er, objects: [] }`;
    writeFileSync(graphPath, graphContent, 'utf-8');

    const uri = `file://${graphPath}`;
    client.sendNotification('textDocument/didOpen', {
      textDocument: { uri, languageId: 'ttr', version: 1, text: graphContent },
    });
    await sleep(50);

    try {
      const layoutPayload = {
        version: 1 as const,
        viewport: { zoom: 2.0, panX: 0, panY: 0, displayMode: 'just-names' as const },
        nodes: { 'er.entity.item': { x: 100, y: 200 } },
        edges: {} as Record<string, { bendPoints: [number, number][] }>,
      };
      const setResult = await client.sendRequest('modeler/setLayout', {
        graphUri: uri,
        layout: layoutPayload,
      }) as { documentChanges?: any[] };
      expect(setResult.documentChanges).toBeDefined();
      expect(setResult.documentChanges!.length).toBeGreaterThan(0);

      for (let i = setResult.documentChanges!.length - 1; i >= 0; i--) {
        const change = setResult.documentChanges![i];
        const start = change.edits[0].range.start;
        const end = change.edits[0].range.end;
        const lines = graphContent.split('\n');
        const before = lines.slice(0, start.line).join('\n') + '\n' + lines[start.line].slice(0, start.character);
        const after = (end.line > start.line ? '' : lines[start.line].slice(end.character)) + '\n' + lines.slice(end.line + 1).join('\n');
        graphContent = before + change.edits[0].newText + after;
      }
      client.sendNotification('textDocument/didChange', {
        textDocument: { uri, version: 2 },
        contentChanges: [{ text: graphContent }],
      });
      await sleep(50);

      const getResult = await client.sendRequest('modeler/getLayout', { graphUri: uri }) as typeof layoutPayload;
      expect(getResult.version).toBe(1);
      expect(getResult.viewport?.zoom).toBe(2.0);
      expect(getResult.nodes['er.entity.item']).toEqual({ x: 100, y: 200 });
    } finally {
      rmSync(graphPath, { recursive: true, force: true });
    }
  });

  it('4.3 applyGraphEdit rejects unsupported legacy operations with a migration note', async () => {
    const result = await client.sendRequest('modeler/applyGraphEdit', {
      operations: [{ op: 'add-node', node: {} }],
    }) as { ok: false; code: string; deprecated: string };
    expect(result.ok).toBe(false);
    expect(result.code).toBe('invalid-args');
    expect(result.deprecated).toBe('modeler/edit');
  });

  it('4.4 getSymbolDetail for er.entity.item returns its label, description, perKindData, referencedBy', async () => {
    const ttrFiles = await getAllTtrFiles(samplesDir, ['broken', 'v1-mini', 'v1.1-mini', 'v1.1-metadata', 'v1.1-mini-migrated', '2.1']);
    for (const file of ttrFiles) {
      const content = await import('fs/promises').then(fs => fs.readFile(file, 'utf-8'));
      client.sendNotification('textDocument/didOpen', {
        textDocument: {
          uri: `file://${file}`,
          languageId: 'ttr',
          version: 1,
          text: content,
        },
      });
    }
    await sleep(100);

    const result = await client.sendRequest('modeler/getSymbolDetail', {
      qname: 'er.entity.item',
    }) as {
      qname: string;
      label: string;
      description: string | null;
      perKindData: { kind: string; attributes?: unknown[] };
      referencedBy: unknown[];
    } | null;

    expect(result).not.toBeNull();
    expect(result!.label).toBe('item');
    expect(result!.description).not.toBeNull();
    expect(result!.perKindData.kind).toBe('entity');
    expect((result!.perKindData as { attributes?: unknown[] }).attributes?.length).toBeGreaterThan(0);
    expect(result!.referencedBy.length).toBeGreaterThan(0);
  });

  it('4.5 getModelGraph with model db on multi-file project returns >= 5 edges', async () => {
    const ttrFiles = await getAllTtrFiles(samplesDir, ['broken', 'v1-mini', 'v1.1-mini', 'v1.1-metadata', 'v1.1-mini-migrated', '2.1']);
    for (const file of ttrFiles) {
      const content = await import('fs/promises').then(fs => fs.readFile(file, 'utf-8'));
      client.sendNotification('textDocument/didOpen', {
        textDocument: {
          uri: `file://${file}`,
          languageId: 'ttr',
          version: 1,
          text: content,
        },
      });
    }
    await sleep(100);

    const result = await client.sendRequest('modeler/getModelGraph', {
      textDocument: { uri: `file://${ttrFiles.find(f => f.endsWith('db.ttrm')) ?? ttrFiles[0]}` },
      schema: 'db',
    }) as {
      schemaCode: string;
      nodes: Array<{ qname: string }>;
      edges: Array<{ fromNode: string; toNode: string }>;
    };

    expect(result.schemaCode).toBe('db');
    expect(result.nodes.length).toBeGreaterThan(0);
    // samples/v1-metadata/db.ttrm has 114 fk defs; bump if the sample changes.
    expect(result.edges.length).toBeGreaterThanOrEqual(5);
    for (const edge of result.edges) {
      expect(result.nodes.some(n => n.qname === edge.fromNode)).toBe(true);
      expect(result.nodes.some(n => n.qname === edge.toNode)).toBe(true);
    }
  });

  it('4.5b getModelGraph with model er returns relation edges with from/toCardinality and localized entity labels', async () => {
    const ttrFiles = await getAllTtrFiles(samplesDir, ['broken', 'v1-mini', 'v1.1-mini', 'v1.1-metadata', 'v1.1-mini-migrated', '2.1']);
    for (const file of ttrFiles) {
      const content = await import('fs/promises').then(fs => fs.readFile(file, 'utf-8'));
      client.sendNotification('textDocument/didOpen', {
        textDocument: {
          uri: `file://${file}`,
          languageId: 'ttr',
          version: 1,
          text: content,
        },
      });
    }
    await sleep(100);

    const erFile = ttrFiles.find(f => f.endsWith('er.ttrm')) ?? ttrFiles[0];
    const result = await client.sendRequest('modeler/getModelGraph', {
      textDocument: { uri: `file://${erFile}` },
      schema: 'er',
    }) as {
      schemaCode: string;
      nodes: Array<{ qname: string; label: string; kind: string }>;
      edges: Array<{ kind: string; fromCardinality: string | null; toCardinality: string | null }>;
    };

    expect(result.schemaCode).toBe('er');
    expect(result.nodes.length).toBeGreaterThan(0);
    const relationEdges = result.edges.filter((e: { kind: string }) => e.kind === 'relation');
    expect(relationEdges.length).toBeGreaterThan(0);
    for (const edge of relationEdges) {
      expect(edge.fromCardinality).not.toBeNull();
      expect(edge.toCardinality).not.toBeNull();
    }
    // er.ttrm has no displayLabel on item, so label falls back to def.name.
    const item = result.nodes.find(n => n.qname === 'er.entity.item');
    expect(item).toBeDefined();
    expect(item!.label).toBe('item');
  });

  it('4.7 parse-recovery-info diagnostics arrive with Information severity', async () => {
    const received: lsp.Diagnostic[] = [];
    client.onNotification('textDocument/publishDiagnostics', (params: lsp.PublishDiagnosticsParams) => {
      received.push(...params.diagnostics);
    });

    client.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///recovery-test.ttrm',
        languageId: 'ttr',
        version: 1,
        text: `def entity {
  description: "Test"
`,
      },
    });

    // Diagnostics arrive asynchronously after didOpen. Poll for them rather than
    // waiting a fixed interval — a fixed sleep is too short on a loaded CI runner
    // (the publishDiagnostics notification simply hasn't arrived yet), yielding a
    // spurious 0. Bounded well within the 10s test timeout.
    let infoDiagnostics: lsp.Diagnostic[] = [];
    const deadline = Date.now() + 8000;
    while (Date.now() < deadline) {
      infoDiagnostics = received.filter(d =>
        d.code === 'ttr/parse-recovery-info' && d.severity === 3
      );
      if (infoDiagnostics.length >= 1) break;
      await sleep(50);
    }
    expect(infoDiagnostics.length, 'expected at least one parse-recovery-info with Information severity').toBeGreaterThanOrEqual(1);
  });

  it('4.6 getSymbolDetail for a column qname returns null in v1 (nested-qname limitation)', async () => {
    const ttrFiles = await getAllTtrFiles(samplesDir, ['broken', 'v1-mini', 'v1.1-mini', 'v1.1-metadata', 'v1.1-mini-migrated', '2.1']);
    for (const file of ttrFiles) {
      const content = await import('fs/promises').then(fs => fs.readFile(file, 'utf-8'));
      client.sendNotification('textDocument/didOpen', {
        textDocument: { uri: `file://${file}`, languageId: 'ttr', version: 1, text: content },
      });
    }
    await sleep(100);
    const result = await client.sendRequest('modeler/getSymbolDetail', {
      qname: 'db.dbo.store.s_market_id',
    });
    expect(result).toBeNull();
  });
});