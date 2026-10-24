// SPDX-License-Identifier: Apache-2.0
import { TextDocument } from 'vscode-languageserver-textdocument';
import type { WorkspaceEdit, TextEdit } from 'vscode-languageserver-types';

/** Apply once to the host's pre-request bytes, then acknowledge the resulting full text. */
export async function applyWorkspaceEdit(
  edit: WorkspaceEdit,
  getText: (uri: string) => string | undefined,
  openDoc: (uri: string, content: string) => Promise<void>,
): Promise<string[]> {
  const texts = new Map<string, string>();
  const apply = (uri: string, edits: TextEdit[]) => {
    const text = texts.get(uri) ?? getText(uri) ?? '';
    texts.set(uri, TextDocument.applyEdits(TextDocument.create(uri, 'ttr', 0, text), edits));
  };
  for (const [uri, edits] of Object.entries(edit.changes ?? {})) apply(uri, edits);
  for (const change of edit.documentChanges ?? []) {
    if ('textDocument' in change) apply(change.textDocument.uri, change.edits);
    else if (change.kind === 'create') texts.set(change.uri, '');
    else throw new Error('Resource rename/delete is unsupported.');
  }
  for (const [uri, text] of texts) await openDoc(uri, text);
  return [...texts.keys()];
}
