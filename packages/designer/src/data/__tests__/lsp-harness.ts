// SPDX-License-Identifier: Apache-2.0
import { PassThrough } from 'node:stream';
import * as lsp from 'vscode-languageserver/node';
import { createServerConnection } from '@tatrman/lsp/server';
import { lspClientOver } from '../../lsp-connection.js';

export async function createTestClient(
  beforeRequest?: (method: string, params: unknown) => Promise<void>
) {
  const toServer = new PassThrough();
  const toClient = new PassThrough();
  const server = lsp.createConnection(
    new lsp.StreamMessageReader(toServer),
    new lsp.StreamMessageWriter(toClient)
  );
  const connection = lsp.createConnection(
    new lsp.StreamMessageReader(toClient),
    new lsp.StreamMessageWriter(toServer)
  );
  createServerConnection(server, { layoutStore: new Map() });
  server.listen();
  connection.listen();
  return lspClientOver(
    {
      sendRequest: async (m, p) => {
        await beforeRequest?.(m, p);
        return connection.sendRequest(m, p);
      },
      sendNotification: (m, p) => connection.sendNotification(m, p),
      onNotification: (m, h) => connection.onNotification(m, h),
    },
    {
      transportKind: 'browser',
      dispose: () => {
        connection.dispose();
        server.dispose();
      },
    }
  );
}
