// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PassThrough } from 'node:stream';
import * as lsp from 'vscode-languageserver/node';
import { createServerConnection } from '@tatrman/lsp/server';
import { DiagnosticCode } from '@tatrman/parser';

const ROOT = 'file:///world';
const MANIFEST = '[packages]\nlayout = "off"';
const FILES = {
  measures: 'package shop\nmodel md\ndef domain Money { type: decimal }\ndef measure revenue { domain: md.Money, class: additive, aggregation: sum }',
  sales: 'package shop\nmodel er\ndef entity sale { attributes: [def attribute revenue { type: decimal }] }',
  cubelets: 'package shop\nmodel md\ndef cubelet sales { grain: [], measures: [revenue] }',
};
const ORDERS = [
  ['measures', 'sales', 'cubelets'], ['measures', 'cubelets', 'sales'],
  ['sales', 'measures', 'cubelets'], ['sales', 'cubelets', 'measures'],
  ['cubelets', 'measures', 'sales'], ['cubelets', 'sales', 'measures'],
] as const;

describe('same-package reference resolution (#258)', () => {
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
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: MANIFEST });
  });
  afterEach(() => { client.dispose(); server.dispose(); });

  it.each(ORDERS)('indexes MD measure references in open order %s, %s, %s', async (...order) => {
    for (const name of order) {
      await client.sendNotification('textDocument/didOpen', { textDocument: {
        uri: `${ROOT}/${name}.ttrm`, languageId: 'ttr', version: 1, text: FILES[name],
      } });
    }
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: MANIFEST });
    const detail = async (qname: string) => client.sendRequest('modeler/getSymbolDetail', { qname }) as Promise<{ referencedBy: { qname: string }[] }>;
    expect((await detail('shop.md.measure.revenue')).referencedBy).toHaveLength(1);
    expect((await detail('shop.er.entity.sale.revenue')).referencedBy).toHaveLength(0);
    const references = await client.sendRequest('textDocument/references', {
      textDocument: { uri: `${ROOT}/measures.ttrm` }, position: { line: 3, character: 15 },
      context: { includeDeclaration: false },
    }) as lsp.Location[];
    expect(references.map((ref) => ref.uri)).toEqual([`${ROOT}/cubelets.ttrm`]);
    const edit = await client.sendRequest('textDocument/rename', {
      textDocument: { uri: `${ROOT}/measures.ttrm` }, position: { line: 3, character: 15 }, newName: 'net_revenue',
    }) as lsp.WorkspaceEdit;
    const uris = (edit.documentChanges as lsp.TextDocumentEdit[]).map((change) => change.textDocument.uri);
    expect(uris).toContain(`${ROOT}/cubelets.ttrm`);
    expect(uris).not.toContain(`${ROOT}/sales.ttrm`);
    // Reindexing must not depend on one particular settling order either.
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: MANIFEST });
    expect((await detail('shop.md.measure.revenue')).referencedBy).toHaveLength(1);
    expect((await detail('shop.er.entity.sale.revenue')).referencedBy).toHaveLength(0);
  });

  it.each([false, true])('keeps bare entity references indexed beside same-named attributes (reverse=%s)', async (reverse) => {
    const docs = Object.entries({
      entities: 'package shop\nmodel er\ndef entity customer {}\ndef entity order { attributes: [def attribute customer { type: int }] }',
      relations: 'package shop\nmodel er\ndef relation places { from: order, to: customer, cardinality: { from: "0..*", to: "1" } }',
      terms: 'package shop\nmodel lexicon locale en\ndef term Customer { for: customer, forms: ["Customer"] }',
    });
    for (const [name, text] of reverse ? docs.reverse() : docs) {
      await client.sendNotification('textDocument/didOpen', { textDocument: {
        uri: `${ROOT}/${name}.ttrm`, languageId: 'ttr', version: 1, text,
      } });
    }
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: MANIFEST });
    expect([...diagnostics.values()].flat().filter((d) => d.code === DiagnosticCode.AmbiguousReference)).toEqual([]);
    const detail = await client.sendRequest('modeler/getSymbolDetail', { qname: 'shop.er.entity.customer' }) as { referencedBy: { qname: string }[] };
    expect(detail.referencedBy).toHaveLength(2);
    expect(detail.referencedBy.map((r) => r.qname)).toContain('shop.er.relation.places');
    const references = await client.sendRequest('textDocument/references', {
      textDocument: { uri: `${ROOT}/entities.ttrm` }, position: { line: 2, character: 15 },
      context: { includeDeclaration: false },
    }) as lsp.Location[];
    expect(references.map((r) => r.uri).sort()).toEqual([`${ROOT}/relations.ttrm`, `${ROOT}/terms.ttrm`]);
    const edit = await client.sendRequest('textDocument/rename', {
      textDocument: { uri: `${ROOT}/entities.ttrm` }, position: { line: 2, character: 15 }, newName: 'buyer',
    }) as lsp.WorkspaceEdit;
    const changes = edit.documentChanges as lsp.TextDocumentEdit[];
    expect(changes.map((c) => c.textDocument.uri).sort()).toEqual([`${ROOT}/entities.ttrm`, `${ROOT}/relations.ttrm`, `${ROOT}/terms.ttrm`]);
    expect(changes.find((c) => c.textDocument.uri.endsWith('/entities.ttrm'))?.edits).toHaveLength(1);
  });

  it('publishes same-package ambiguity on the reference rather than choosing an attribute', async () => {
    const uri = `${ROOT}/terms.ttrm`;
    for (const [name, text] of Object.entries({
      sales: 'package shop\nmodel er\ndef entity sale { attributes: [def attribute revenue { type: decimal }] }\ndef entity order { attributes: [def attribute revenue { type: decimal }] }',
      terms: 'package shop\nmodel lexicon locale en\ndef term Revenue { for: revenue, forms: ["Revenue"] }',
    })) {
      await client.sendNotification('textDocument/didOpen', { textDocument: {
        uri: `${ROOT}/${name}.ttrm`, languageId: 'ttr', version: 1, text,
      } });
    }
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: ROOT, manifest: MANIFEST });
    const ambiguous = (diagnostics.get(uri) ?? []).filter((d) => d.code === DiagnosticCode.AmbiguousReference);
    expect(ambiguous).toHaveLength(1);
    expect(ambiguous[0]).toMatchObject({
      message: expect.stringContaining('2 definitions via same-package lookup'),
      range: { start: { line: 2, character: 24 } },
    });
  });
});
