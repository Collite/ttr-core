// SPDX-License-Identifier: Apache-2.0
import type { RenderableSchemaCode } from './model-graph.js';
import type { ResolvedManifest } from '@tatrman/semantics';

export interface ProjectRootResult {
  ok: true;
  manifest: ResolvedManifest;
  /** Kept for existing hosts. */
  projectRoot: string;
}

export interface SchemaSubject {
  schema: RenderableSchemaCode;
  objectCount: number;
  packageCount: number;
}

export interface ListedSymbol {
  qname: string;
  kind: string;
  name: string;
  packageName: string | null;
  memberPath?: string[];
  parent?: string;
}

export interface ListSymbolsParams {
  kind?: string;
  /** Existing picker API; `kind` is the singular shorthand. */
  kinds?: string[];
  package?: string;
  cursor?: string;
  limit?: number;
  includeMembers?: boolean;
}

export interface SymbolPage {
  symbols: ListedSymbol[];
  nextCursor?: string;
  total: number;
}

/** Studio authoring is transport-independent; the host still applies returned edits to its files. */
export type ModelerEditParams = {
  [O in import('@tatrman/edit').EditOp]: { op: O; args: import('@tatrman/edit').EditArguments[O]; dryRun?: boolean }
}[import('@tatrman/edit').EditOp];
export type WorkerDiagnostic = import('vscode-languageserver').Diagnostic & { uri: string; source: 'worker' };
export type ModelerEditResult = Exclude<import('@tatrman/edit').SynthResult, { ok: true }> | {
  ok: true;
  edit: import('vscode-languageserver').WorkspaceEdit;
  touched: string[];
  diagnostics: WorkerDiagnostic[];
};
