// SPDX-License-Identifier: Apache-2.0
// The LspClient's request mapping, apart from its transport: the browser worker (`lsp-client.ts`) and a
// test's in-process connection to the real server use the same calls.

import {
  InitializeRequest,
  DidOpenTextDocumentNotification,
  PublishDiagnosticsNotification,
  type InitializeParams,
} from 'vscode-languageserver-protocol';
import type { ModelGraph, LayoutFile, SymbolDetail, GraphMetadata, GetGraphResponse, PackageGraphResponse, BindingMapData } from '@tatrman/lsp';
import type { WorkspaceEdit } from 'vscode-languageserver-types';
import type { LspClient } from './lsp-client.js';

/**
 * The connection an `LspClient` speaks over: requests and notifications by method name. A browser
 * `ProtocolConnection` to the LSP worker is one; a test's in-process connection to the real server is another.
 */
export interface LspConnection {
  sendRequest(method: string, params?: unknown): Promise<unknown>;
  sendNotification(method: string, params?: unknown): Promise<void> | void;
  onNotification(method: string, handler: (params: unknown) => void): unknown;
}

/** An `LspClient` over an already-listening connection: initializes it, then maps each call to its request. */
export async function lspClientOver(
  connection: LspConnection,
  opts: { transportKind: LspClient['transportKind']; dispose: () => void },
): Promise<LspClient> {
  await connection.sendRequest(InitializeRequest.method, {
    processId: null,
    rootUri: null,
    capabilities: {},
  } satisfies InitializeParams);
  const diagnosticHandlers: Array<(uri: string, msgs: string[]) => void> = [];
  connection.onNotification(PublishDiagnosticsNotification.method, (raw) => {
    const params = raw as { uri: string; diagnostics: Array<{ message: string }> };
    const messages = params.diagnostics.map((d) => d.message);
    for (const h of diagnosticHandlers) h(params.uri, messages);
  });
  return {
    transportKind: opts.transportKind,
    async openDocument(uri, content) {
      await connection.sendNotification(DidOpenTextDocumentNotification.method, {
        textDocument: { uri, languageId: 'ttr', version: 1, text: content },
      });
    },
    async setProjectRoot(projectRoot) {
      return connection.sendRequest('modeler/setProjectRoot', { projectRoot }) as Promise<{ projectRoot: string }>;
    },
    async listGraphs(projectRoot) {
      return connection.sendRequest('modeler/listGraphs', { projectRoot }) as Promise<{ graphs: GraphMetadata[] }>;
    },
    async getGraph(uri) {
      return connection.sendRequest('modeler/getGraph', { uri }) as Promise<GetGraphResponse | null>;
    },
    async getPackageGraph() {
      return connection.sendRequest('modeler/getPackageGraph', {}) as Promise<PackageGraphResponse>;
    },
    async getModelGraph(uri, schema) {
      return connection.sendRequest('modeler/getModelGraph', {
        textDocument: { uri },
        schema,
      }) as Promise<ModelGraph>;
    },
    async getBindings() {
      return connection.sendRequest('modeler/getBindings', {}) as Promise<BindingMapData>;
    },
    async getLayout(uri, projectRoot) {
      return connection.sendRequest('modeler/getLayout', { graphUri: uri, projectRoot }) as Promise<LayoutFile>;
    },
    async setLayout(uri, layout, projectRoot) {
      return connection.sendRequest('modeler/setLayout', { graphUri: uri, layout, projectRoot }) as Promise<WorkspaceEdit>;
    },
    async exportLayout(uri, projectRoot) {
      return connection.sendRequest('modeler/exportLayout', { graphUri: uri, projectRoot }) as Promise<LayoutFile>;
    },
    async getSymbolDetail(qname) {
      return connection.sendRequest('modeler/getSymbolDetail', { qname }) as Promise<SymbolDetail | null>;
    },
    async listSymbols(options) {
      return connection.sendRequest('modeler/listSymbols', options ?? {}) as Promise<Array<{ qname: string; kind: string; name: string; packageName: string | null; memberPath?: string[]; parent?: string }>>;
    },
    onDiagnostics(handler) {
      diagnosticHandlers.push(handler);
    },
    dispose: opts.dispose,
  };
}

