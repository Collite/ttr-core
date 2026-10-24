// SPDX-License-Identifier: Apache-2.0
// Browser JSON-RPC transport in-thread: assertions still run the actual LSP, parser and edit path.
import { createConnection, ProposedFeatures } from 'vscode-languageserver/browser.js';
import { BrowserMessageReader, BrowserMessageWriter } from 'vscode-languageserver-protocol/browser.js';
import { createServerConnection } from '@tatrman/lsp/server';

class Endpoint {
  private listeners = new Set<(event: MessageEvent) => void>();
  peer!: Endpoint;
  onmessage: ((event: MessageEvent) => void) | null = null;
  closed = false;
  addEventListener(type: string, listener: (event: MessageEvent) => void) { if (type === 'message') this.listeners.add(listener); }
  removeEventListener(type: string, listener: (event: MessageEvent) => void) { if (type === 'message') this.listeners.delete(listener); }
  postMessage(data: unknown) {
    const value = structuredClone(data);
    queueMicrotask(() => { if (!this.closed) this.peer.emit(value); });
  }
  emit(data: unknown) { if (this.closed) return; const event = new MessageEvent('message', { data }); this.onmessage?.(event); for (const listener of this.listeners) listener(event); }
}
export class FakeWorker extends Endpoint {
  static urls: string[] = [];
  private server: ReturnType<typeof createConnection>;
  constructor(url: string | URL, options?: WorkerOptions) {
    super();
    if (options?.type !== 'module') throw new Error('Expected module worker.');
    FakeWorker.urls.push(String(url));
    const endpoint = new Endpoint();
    endpoint.peer = this; this.peer = endpoint;
    this.server = createConnection(ProposedFeatures.all,
      new BrowserMessageReader(endpoint as unknown as Worker), new BrowserMessageWriter(endpoint as unknown as Worker));
    createServerConnection(this.server, { layoutStore: new Map() });
    this.server.listen();
  }
  terminate() { this.closed = this.peer.closed = true; this.server.dispose(); }
}
