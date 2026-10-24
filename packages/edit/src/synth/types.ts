// SPDX-License-Identifier: Apache-2.0
import type { DataType } from '@tatrman/parser';
import type { ResolvedManifest } from '@tatrman/semantics';
import type { Diagnostic, Range, WorkspaceEdit } from 'vscode-languageserver-types';
import type { RefusalCode } from './refusals.js';

export interface EditArguments {
  'insert-entity': { name: string; file?: string; from?: string; on?: string; terms?: { en?: string; cs?: string } };
  'add-attribute': { entity: string; name: string; type: string | DataType; column?: string };
  'add-relation': { from: string; to: string; kind: 'one-to-many' | 'many-to-one' | 'one-to-one'; fk?: string; name?: string };
  'set-source': { nodeRef: string; text: string };
  'remove-definition': { qname: string; force?: boolean };
  'add-term': { lang: 'en' | 'cs'; term: string; target: string; file?: string };
}
export type EditOp = keyof EditArguments;
export type EditArgs = EditArguments[EditOp];
export interface SynthContext {
  /** Complete immutable snapshot, including model/tests/*.mjs. Keys are document URIs or repo-relative paths. */
  files: Readonly<Record<string, string>>;
  rootUri: string;
  manifest: ResolvedManifest;
}
export interface RefusalRef { uri: string; range: Range; kind: string }
export type SynthResult =
  | { ok: true; edit: WorkspaceEdit; touched: string[]; diagnostics: Diagnostic[] }
  | { ok: false; code: RefusalCode; message: string; refs?: RefusalRef[] };
