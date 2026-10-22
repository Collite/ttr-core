// SPDX-License-Identifier: Apache-2.0
// Manual smoke over a real checkout. Build the workspace first, then:
// node packages/lsp/scripts/hosted-smoke.mjs ../hartland
import { readdirSync, readFileSync } from 'node:fs';
import { resolve, join, relative } from 'node:path';
import { PassThrough } from 'node:stream';
import * as lsp from 'vscode-languageserver/node.js';
import { createServerConnection } from '../dist/server.js';

if (!process.argv[2]) throw new Error('Usage: hosted-smoke.mjs <checkout>');
const checkout = resolve(process.argv[2]);
const root = 'file:///hosted-smoke';
const manifest = readFileSync(join(checkout, 'modeler.toml'), 'utf8');
const walk = (dir) =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name);
    return e.isDirectory() ? walk(path) : /\.(ttrm|ttrg)$/.test(e.name) ? [path] : [];
  });
const toServer = new PassThrough();
const toClient = new PassThrough();
const server = lsp.createConnection(
  new lsp.StreamMessageReader(toServer),
  new lsp.StreamMessageWriter(toClient)
);
const client = lsp.createConnection(
  new lsp.StreamMessageReader(toClient),
  new lsp.StreamMessageWriter(toServer)
);
createServerConnection(server, { layoutStore: new Map() });
server.listen();
client.listen();
const diagnostics = new Map();
client.onNotification('textDocument/publishDiagnostics', (p) =>
  diagnostics.set(p.uri, p.diagnostics)
);
try {
  await client.sendRequest('initialize', { processId: null, rootUri: null, capabilities: {} });
  await client.sendRequest('modeler/setProjectRoot', { projectRoot: root, manifest });
  const files = walk(join(checkout, 'model')).filter(
    (p) => !relative(checkout, p).startsWith('model/layouts/')
  );
  for (const path of files) {
    await client.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: `${root}/${relative(checkout, path)}`,
        languageId: 'ttr',
        version: 1,
        text: readFileSync(path, 'utf8'),
      },
    });
  }
  // Re-publish diagnostics once the entire project has been indexed.
  await client.sendRequest('modeler/setProjectRoot', { projectRoot: root, manifest });
  let cursor;
  const symbols = [];
  let pages = 0;
  do {
    const page = await client.sendRequest('modeler/listSymbols', { ...(cursor ? { cursor } : {}) });
    symbols.push(...page.symbols);
    cursor = page.nextCursor;
    pages++;
  } while (cursor);
  const { subjects } = await client.sendRequest('modeler/listSchemaSubjects', {});
  const schemaGraphs = {};
  for (const schema of ['er', 'db', 'md']) {
    const g = await client.sendRequest('modeler/getModelGraph', {
      textDocument: { uri: root },
      schema,
    });
    schemaGraphs[schema] = { nodes: g.nodes.length, edges: g.edges.length };
  }
  const allDiagnostics = [...diagnostics.values()].flat();
  const errors = allDiagnostics.filter((d) => d.severity === 1);
  const counts = {};
  for (const d of allDiagnostics) counts[d.code] = (counts[d.code] ?? 0) + 1;
  console.log(
    JSON.stringify(
      {
        checkout,
        files: files.length,
        symbols: symbols.length,
        pages,
        subjects,
        schemaGraphs,
        cubelets: symbols.filter((s) => s.kind === 'cubelet').map((s) => s.qname),
        errors: errors.length,
        warnings: allDiagnostics.filter((d) => d.severity === 2).length,
        diagnosticCodes: counts,
        ...(errors.length
          ? {
              errorDetails: [...diagnostics].flatMap(([uri, ds]) =>
                ds.filter((d) => d.severity === 1).map((d) => ({ uri, ...d }))
              ),
            }
          : {}),
      },
      null,
      2
    )
  );
  if (errors.length) process.exitCode = 1;
} finally {
  client.dispose();
  server.dispose();
}
