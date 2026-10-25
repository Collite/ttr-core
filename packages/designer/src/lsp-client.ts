// SPDX-License-Identifier: Apache-2.0
import {
  BrowserMessageReader,
  BrowserMessageWriter,
  createProtocolConnection,
} from 'vscode-languageserver-protocol/browser.js';

import type { ModelGraph, LayoutFile, SymbolDetail, RenderableSchemaCode, GraphMetadata, GetGraphResponse, PackageGraphResponse, BindingMapData, ProjectRootResult, SchemaSubject, ListSymbolsParams, ListedSymbol } from '@tatrman/lsp';
import type { ModelerEditParams, ModelerEditResult } from '@tatrman/lsp';
import type { WorkspaceEdit } from 'vscode-languageserver-types';
import workerUrl from './worker-url.js';
import { lspClientOver } from './lsp-connection.js';
export { lspClientOver, type LspConnection } from './lsp-connection.js';

export interface LspClient {
  edit(params: ModelerEditParams): Promise<ModelerEditResult>;
  transportKind: 'node' | 'browser';
  openDocument(uri: string, content: string): Promise<void>;
  setProjectRoot(projectRoot: string, manifest?: string): Promise<ProjectRootResult>;
  listSchemaSubjects(): Promise<{ subjects: SchemaSubject[] }>;
  listGraphs(projectRoot: string): Promise<{ graphs: GraphMetadata[] }>;
  getGraph(uri: string): Promise<GetGraphResponse | null>;
  getPackageGraph(): Promise<PackageGraphResponse>;
  getModelGraph(uri: string, schema: RenderableSchemaCode): Promise<ModelGraph>;
  getBindings(): Promise<BindingMapData>;
  getLayout(uri: string, projectRoot?: string): Promise<LayoutFile>;
  setLayout(uri: string, layout: LayoutFile, projectRoot?: string): Promise<WorkspaceEdit>;
  exportLayout(uri?: string, projectRoot?: string): Promise<LayoutFile>;
  getSymbolDetail(qname: string): Promise<SymbolDetail | null>;
  /** Reads every page; `limit` is the size of a page, not a cap on the result. */
  listSymbols(options?: Omit<ListSymbolsParams, 'cursor'>): Promise<ListedSymbol[]>;
  revalidate?(): Promise<void>;
  diagnostics?(): import('@tatrman/lsp').WorkerDiagnostic[];
  onDiagnostics(handler: (uri: string, messages: string[]) => void): void;
  dispose(): void;
}

export async function createLspClient(): Promise<LspClient> {
  const worker = new Worker(workerUrl, { type: 'module' });
  const reader = new BrowserMessageReader(worker);
  const writer = new BrowserMessageWriter(worker);
  const connection = createProtocolConnection(reader, writer);
  connection.listen();
  const dispose = () => { connection.dispose(); worker.terminate(); };
  try { return await lspClientOver(connection, { transportKind: 'browser', dispose }); }
  catch (error) { dispose(); throw error; }
}
