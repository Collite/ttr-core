// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '../server.js';

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
  await new Promise((resolve) => setTimeout(resolve, ms));
}

describe('completion-property', () => {
  let clientConnection: lsp.Connection;
  let serverConnection: lsp.Connection;

  beforeEach(async () => {
    const { client, server } = createPairedConnection();
    clientConnection = client;
    serverConnection = server;
    createServerConnection(serverConnection);
  });

  afterEach(() => {
    clientConnection.dispose();
    serverConnection.dispose();
  });

  it('returns property names inside entity def body', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: null,
      capabilities: {},
    });
    clientConnection.sendNotification('initialized', {});

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///test.ttrm',
        languageId: 'ttr',
        version: 1,
        text: `def entity artikl {
  desc
}`,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/completion', {
      textDocument: { uri: 'file:///test.ttrm' },
      position: { line: 1, character: 2 },
      context: { triggerKind: 1 },
    }) as { isIncomplete: boolean; items: unknown[] };

    expect(result.items.length).toBeGreaterThan(0);
    const labels = (result.items as Array<{ label: string }>).map((i) => i.label);
    expect(labels).toContain('description');
    expect(labels).toContain('tags');
    expect(labels).toContain('labelPlural');
    expect(labels).toContain('nameAttribute');
    expect(labels).toContain('codeAttribute');
    expect(labels).toContain('aliases');
    expect(labels).toContain('attributes');
    expect(labels).toContain('roles');
    expect(labels).toContain('displayLabel');
    expect(labels).toContain('search');
  });

  it('D-142: an automation role offers resolve and members, never the removed delivery', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: null,
      capabilities: {},
    });
    clientConnection.sendNotification('initialized', {});

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///role.ttrm',
        languageId: 'ttr',
        version: 1,
        text: `model automation
def role sales {
  res
}`,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/completion', {
      textDocument: { uri: 'file:///role.ttrm' },
      position: { line: 2, character: 2 },
      context: { triggerKind: 1 },
    }) as { isIncomplete: boolean; items: unknown[] };

    const labels = (result.items as Array<{ label: string }>).map((i) => i.label);
    expect(labels).toContain('resolve');
    expect(labels).toContain('members');
    expect(labels).not.toContain('delivery');
  });

  it('returns property names inside column def body', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: null,
      capabilities: {},
    });
    clientConnection.sendNotification('initialized', {});

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///test.ttrm',
        languageId: 'ttr',
        version: 1,
        text: `def column id_name {
  <CURSOR>
}`,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/completion', {
      textDocument: { uri: 'file:///test.ttrm' },
      position: { line: 1, character: 2 },
      context: { triggerKind: 1 },
    }) as { isIncomplete: boolean; items: unknown[] };

    expect(result.items.length).toBeGreaterThan(0);
    const labels = (result.items as Array<{ label: string }>).map((i) => i.label);
    expect(labels).toContain('description');
    expect(labels).toContain('tags');
    expect(labels).toContain('type');
    expect(labels).toContain('optional');
    expect(labels).toContain('isKey');
    expect(labels).toContain('indexed');
    expect(labels).toContain('search');
  });

  it('returns property names inside table def body', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: null,
      capabilities: {},
    });
    clientConnection.sendNotification('initialized', {});

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///test.ttrm',
        languageId: 'ttr',
        version: 1,
        text: `def table orders {
  <CURSOR>
}`,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/completion', {
      textDocument: { uri: 'file:///test.ttrm' },
      position: { line: 1, character: 2 },
      context: { triggerKind: 1 },
    }) as { isIncomplete: boolean; items: unknown[] };

    expect(result.items.length).toBeGreaterThan(0);
    const labels = (result.items as Array<{ label: string }>).map((i) => i.label);
    expect(labels).toContain('description');
    expect(labels).toContain('tags');
    expect(labels).toContain('primaryKey');
    expect(labels).toContain('columns');
    expect(labels).toContain('indices');
    expect(labels).toContain('constraints');
    expect(labels).toContain('search');
  });

  it('excludes properties already present in the def body', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: null,
      capabilities: {},
    });
    clientConnection.sendNotification('initialized', {});

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///test.ttrm',
        languageId: 'ttr',
        version: 1,
        text: `def entity artikl {
  description: "Test"
  <CURSOR>
}`,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/completion', {
      textDocument: { uri: 'file:///test.ttrm' },
      position: { line: 2, character: 2 },
      context: { triggerKind: 1 },
    }) as { isIncomplete: boolean; items: unknown[] };

    const labels = (result.items as Array<{ label: string }>).map((i) => i.label);
    expect(labels).not.toContain('description');
    expect(labels).toContain('tags');
  });

  it('returns search sub-properties inside search block', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: null,
      capabilities: {},
    });
    clientConnection.sendNotification('initialized', {});

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///test.ttrm',
        languageId: 'ttr',
        version: 1,
        text: `def entity artikl {
  search {
    <CURSOR>
  }
}`,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/completion', {
      textDocument: { uri: 'file:///test.ttrm' },
      position: { line: 2, character: 4 },
      context: { triggerKind: 1 },
    }) as { isIncomplete: boolean; items: unknown[] };

    expect(result.items.length).toBeGreaterThan(0);
    const labels = (result.items as Array<{ label: string }>).map((i) => i.label);
    expect(labels).toContain('keywords');
    expect(labels).toContain('patterns');
    expect(labels).toContain('descriptions');
    expect(labels).toContain('examples');
    expect(labels).toContain('aliases');
    expect(labels).toContain('searchable');
    expect(labels).toContain('fuzzy');
  });

  it('does not return items outside a def body', async () => {
    await clientConnection.sendRequest('initialize', {
      processId: null,
      rootUri: null,
      capabilities: {},
    });
    clientConnection.sendNotification('initialized', {});

    clientConnection.sendNotification('textDocument/didOpen', {
      textDocument: {
        uri: 'file:///test.ttrm',
        languageId: 'ttr',
        version: 1,
        text: `def entity artikl {
  description: "Test"
}`,
      },
    });

    await sleep(50);

    const result = await clientConnection.sendRequest('textDocument/completion', {
      textDocument: { uri: 'file:///test.ttrm' },
      position: { line: 0, character: 5 },
      context: { triggerKind: 1 },
    }) as { isIncomplete: boolean; items: unknown[] };

    const labels = (result.items as Array<{ label: string }>).map((i) => i.label);
    expect(labels).not.toContain('description');
    expect(labels).not.toContain('tags');
  });
});