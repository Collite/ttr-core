// SPDX-License-Identifier: Apache-2.0
import {
  BrowserMessageReader,
  BrowserMessageWriter,
  createProtocolConnection,
} from 'vscode-languageserver-protocol/browser.js';

import type { ModelGraph, LayoutFile, SymbolDetail, RenderableSchemaCode, GraphMetadata, GetGraphResponse, PackageGraphResponse, BindingMapData } from '@tatrman/lsp';
import type { WorkspaceEdit } from 'vscode-languageserver-types';
import LspWorker from '@tatrman/lsp/browser?worker';
import { lspClientOver } from './lsp-connection.js';
export { lspClientOver, type LspConnection } from './lsp-connection.js';

export interface LspClient {
  transportKind: 'node' | 'browser';
  openDocument(uri: string, content: string): Promise<void>;
  setProjectRoot(projectRoot: string): Promise<{ projectRoot: string }>;
  listGraphs(projectRoot: string): Promise<{ graphs: GraphMetadata[] }>;
  getGraph(uri: string): Promise<GetGraphResponse | null>;
  getPackageGraph(): Promise<PackageGraphResponse>;
  getModelGraph(uri: string, schema: RenderableSchemaCode): Promise<ModelGraph>;
  getBindings(): Promise<BindingMapData>;
  getLayout(uri: string, projectRoot?: string): Promise<LayoutFile>;
  setLayout(uri: string, layout: LayoutFile, projectRoot?: string): Promise<WorkspaceEdit>;
  exportLayout(uri?: string, projectRoot?: string): Promise<LayoutFile>;
  getSymbolDetail(qname: string): Promise<SymbolDetail | null>;
  listSymbols(options?: { kinds?: string[]; limit?: number; includeMembers?: boolean }): Promise<Array<{ qname: string; kind: string; name: string; packageName: string | null; memberPath?: string[]; parent?: string }>>;
  onDiagnostics(handler: (uri: string, messages: string[]) => void): void;
  dispose(): void;
}

export async function createLspClient(): Promise<LspClient> {
  const worker = new LspWorker();
  const reader = new BrowserMessageReader(worker);
  const writer = new BrowserMessageWriter(worker);
  const connection = createProtocolConnection(reader, writer);
  connection.listen();
  return lspClientOver(connection, { transportKind: 'browser', dispose: () => worker.terminate() });
}
