// SPDX-License-Identifier: Apache-2.0
import { TextDocument } from 'vscode-languageserver-textdocument';
import { ResponseError, ErrorCodes, type WorkspaceEdit, type TextEdit } from 'vscode-languageserver';

/** Stage the supported WorkspaceEdit subset atomically. No filesystem or live cache mutation. */
export function stageWorkspaceEdit(
  files: ReadonlyMap<string, string>, edit: WorkspaceEdit, rootUri: string,
  version: (uri: string) => number | undefined,
): { files: Map<string, string>; touched: string[] } {
  const next = new Map(files);
  const touched = new Set<string>();
  const invalid = (message: string): never => { throw new ResponseError(ErrorCodes.InvalidParams, message); };
  const checkUri = (uri: string): void => {
    if (typeof uri !== 'string' || !uri.startsWith(rootUri.replace(/\/$/, '') + '/')) invalid('Edit URI is outside the project root');
    let relative = uri.slice(rootUri.replace(/\/$/, '').length + 1);
    try { relative = decodeURIComponent(relative); } catch { invalid('Invalid edit URI encoding'); }
    if (/[\\%?#\x00-\x1f]/.test(relative) || relative.split('/').some(p => !p || p === '.' || p === '..')) invalid('Invalid edit URI');
    if (!/\.ttr[mg]$/.test(relative) && !/^model\/tests\/.+\.mjs$/.test(relative)) invalid('Unsupported edit file');
  };
  const apply = (uri: string, edits: TextEdit[]): void => {
    checkUri(uri);
    const text = next.get(uri);
    if (text === undefined) invalid('Text edit requires an existing file or preceding CreateFile');
    const doc = TextDocument.create(uri, 'ttr', 0, text!);
    if (!Array.isArray(edits)) invalid('Expected text edits');
    const offsets = edits.map(e => {
      if (!e?.range?.start || !e.range.end) invalid('Invalid text edit range');
      const start = doc.offsetAt(e.range.start), end = doc.offsetAt(e.range.end);
      const valid = (offset: number, position: { line: number; character: number }): boolean => {
        const actual = doc.positionAt(offset);
        return Number.isInteger(position.line) && Number.isInteger(position.character) &&
          actual.line === position.line && actual.character === position.character;
      };
      if (typeof e.newText !== 'string' || start > end || !valid(start, e.range.start) || !valid(end, e.range.end)) invalid('Invalid text edit range');
      return { start, end };
    }).sort((a, b) => a.start - b.start || a.end - b.end);
    for (let i = 1; i < offsets.length; i++) if (offsets[i].start < offsets[i - 1].end) invalid('Overlapping text edits');
    next.set(uri, TextDocument.applyEdits(doc, edits));
    touched.add(uri);
  };
  if ((edit.changes !== undefined && (!edit.changes || typeof edit.changes !== 'object' || Array.isArray(edit.changes))) ||
      (edit.documentChanges !== undefined && !Array.isArray(edit.documentChanges))) invalid('Invalid WorkspaceEdit');
  if (edit.changes && edit.documentChanges) invalid('Use changes or documentChanges, not both');
  for (const [uri, edits] of Object.entries(edit.changes ?? {})) apply(uri, edits);
  for (const change of edit.documentChanges ?? []) {
    if (!change || typeof change !== 'object') invalid('Invalid document change');
    if ('textDocument' in change) {
      if (!change.textDocument || typeof change.textDocument !== 'object') invalid('Invalid text document');
      const { uri, version: expected } = change.textDocument;
      if (expected !== null && expected !== version(uri)) invalid('Document version changed');
      apply(uri, change.edits);
    } else if (change.kind === 'create') {
      checkUri(change.uri);
      if (next.has(change.uri)) {
        if (change.options?.ignoreIfExists) continue;
        if (!change.options?.overwrite) invalid('CreateFile target already exists');
      }
      next.set(change.uri, ''); touched.add(change.uri);
    } else invalid('Only CreateFile and text edits are supported');
  }
  return { files: next, touched: [...touched].sort() };
}
