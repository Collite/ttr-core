// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { PassThrough } from 'node:stream';
import * as lsp from 'vscode-languageserver/node';
import { createServerConnection } from '@tatrman/lsp/server';
import { applyWorkspaceEditToText } from '../../../packages/edit/dist/index.js';
it('textDocument/rename follows entity and attribute targets in binding files (#213)', async () => {
  const a = new PassThrough(), b = new PassThrough();
  const client = lsp.createConnection(new lsp.StreamMessageReader(a), new lsp.StreamMessageWriter(b));
  const server = lsp.createConnection(new lsp.StreamMessageReader(b), new lsp.StreamMessageWriter(a));
  createServerConnection(server, {}); client.listen(); server.listen();
  const entityUri = 'file:///repo/model/er/returns.ttrm';
  const bindingUri = 'file:///repo/model/binding/er2db.ttrm';
  const members = ['id', 'customer', 'state', 'amount', 'created'];
  const entity = '// 😀 UTF-16 source extraction\npackage hartland\nmodel er\ndef entity return_request { attributes: [' + members.map(n => `def attribute ${n} { type: text }`).join(',') + '] }';
  const binding = 'package hartland\nmodel binding\n' + ['entity: er.entity.return_request', ...members.map(n => `attribute: er.entity.return_request.${n}`)]
    .map((ref, i) => `def ${i ? 'er2db_attribute' : 'er2db_entity'} b${i} { ${ref}, target: { ${i ? 'column' : 'table'}: db.dbo.requests${i ? '.c' + i : ''} } }`).join('\n');
  try {
    await client.sendRequest('initialize', { processId: null, rootUri: null, capabilities: {} });
    await client.sendRequest('modeler/setProjectRoot', { projectRoot: 'file:///repo', manifest: '[packages]\nlayout="off"' });
    for (const [uri, text] of [[entityUri, entity], [bindingUri, binding]]) client.sendNotification('textDocument/didOpen', {
      textDocument: { uri, languageId: 'ttr', version: 1, text },
    });
    const edit = await client.sendRequest('textDocument/rename', {
      textDocument: { uri: entityUri }, position: { line: 3, character: 15 }, newName: 'return_case',
    }) as lsp.WorkspaceEdit;
    const nextBinding = applyWorkspaceEditToText(binding, edit, bindingUri);
    expect(nextBinding.match(/er\.entity\.return_case/g)).toHaveLength(6);
    expect(nextBinding).not.toContain('er.entity.return_request');
    expect(nextBinding).toContain('er.entity.return_case.customer');
    const ranges = (edit.documentChanges ?? []).filter((c): c is lsp.TextDocumentEdit => 'textDocument' in c && c.textDocument.uri === bindingUri).flatMap(c => c.edits.map(e => JSON.stringify(e.range)));
    expect(new Set(ranges).size).toBe(ranges.length); // exact ref + binding walk must not double-apply
    for (const [uri, text] of [[entityUri, applyWorkspaceEditToText(entity, edit, entityUri)], [bindingUri, nextBinding]]) client.sendNotification('textDocument/didChange', {
      textDocument: { uri, version: 2 }, contentChanges: [{ text }],
    });
    const detail = await client.sendRequest('modeler/getSymbolDetail', { qname: 'hartland.er.entity.return_case' });
    expect(detail).toMatchObject({ sourceText: expect.stringContaining('def entity return_case') });
    expect((detail as { sourceText: string }).sourceText).not.toContain('package hartland');
  } finally { client.dispose(); server.dispose(); }
});
