// SPDX-License-Identifier: Apache-2.0
// Copied into the fresh consumer so every import resolves from installed tarballs.
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { createServerConnection } from '@tatrman/lsp/server';
import assert from 'node:assert/strict';
const require = createRequire(import.meta.url);
const lspRequire = createRequire(import.meta.resolve('@tatrman/lsp'));
const { createMessageConnection, StreamMessageReader, StreamMessageWriter } = await import(
  lspRequire.resolve('vscode-jsonrpc/node.js')
);
assert.equal(typeof createServerConnection, 'function');
const child = spawn(process.execPath, [require.resolve('@tatrman/lsp/server-stdio')], {
  stdio: 'pipe',
});
let stderr = '';
child.stderr.on('data', (chunk) => {
  stderr += chunk;
});
const connection = createMessageConnection(
  new StreamMessageReader(child.stdout),
  new StreamMessageWriter(child.stdin)
);
const timeout = setTimeout(() => {
  console.error(stderr);
  child.kill();
  process.exitCode = 1;
}, 15_000);
try {
  connection.listen();
  await connection.sendRequest('initialize', {
    processId: process.pid,
    rootUri: null,
    capabilities: {},
  });
  await connection.sendNotification('initialized', {});
  await connection.sendNotification('textDocument/didOpen', {
    textDocument: {
      uri: 'file:///model/customer.ttrm',
      languageId: 'ttrm',
      version: 1,
      text: 'model er\ndef entity customer { attributes: [def attribute id { type: int }] }',
    },
  });
  const symbols = await connection.sendRequest('workspace/symbol', { query: 'customer' });
  assert.ok(
    symbols.some((s) => s.name.includes('customer')),
    JSON.stringify({ symbols, stderr })
  );
  await connection.sendRequest('shutdown');
  await connection.sendNotification('exit');
  console.log('Installed LSP server/stdio: initialization and parsed workspace symbols pass.');
} finally {
  clearTimeout(timeout);
  connection.dispose();
  child.kill();
}
