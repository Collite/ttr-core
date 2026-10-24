// SPDX-License-Identifier: Apache-2.0
import type { WorkspaceEdit, TextDocumentEdit, Position } from 'vscode-languageserver-types';
import { parseString } from '@tatrman/parser';
import { collectReferences } from '@tatrman/semantics';
import type { SymbolEntry } from '@tatrman/semantics';

function offsetToPosition(content: string, offset: number): Position {
  let line = 0;
  let character = 0;
  for (let i = 0; i < offset && i < content.length; i++) {
    if (content[i] === '\n') { line++; character = 0; }
    else { character++; }
  }
  return { line, character };
}

function positionToOffset(content: string, pos: Position): number {
  let offset = 0;
  let line = 0;
  while (line < pos.line && offset < content.length) {
    if (content[offset] === '\n') { line++; }
    offset++;
  }
  while (offset < content.length && content[offset] === '\n') offset++;
  let character = 0;
  while (character < pos.character && offset < content.length && content[offset] !== '\n') {
    offset++;
    character++;
  }
  return offset;
}

function buildTextEdit(uri: string, start: Position, end: Position, newText: string): TextDocumentEdit {
  return {
    textDocument: { uri, version: null },
    edits: [{ range: { start, end }, newText }],
  };
}

function findObjectsBrackets(content: string): { openOffset: number; closeOffset: number } | null {
  const objectsKeywordIdx = content.indexOf('objects');
  if (objectsKeywordIdx === -1) return null;
  const bracketIdx = content.indexOf('[', objectsKeywordIdx);
  if (bracketIdx === -1) return null;
  let depth = 0;
  let closeOffset = -1;
  for (let i = bracketIdx; i < content.length; i++) {
    if (content[i] === '[') depth++;
    else if (content[i] === ']') { depth--; if (depth === 0) { closeOffset = i; break; } }
  }
  if (closeOffset === -1) return null;
  return { openOffset: bracketIdx, closeOffset };
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export interface RenameSymbolEditParams {
  oldQname: string;
  newBareName: string;
  defEntry: SymbolEntry;
  defDocumentContent: string;
  references: Array<{ documentUri: string; source: { line: number; column: number; endLine: number; endColumn: number }; targetQname: string }>;
  ttrgDocuments: Map<string, string>;
  packageOf?: (uri: string) => string;
}

export function buildRenameSymbolEdit(params: RenameSymbolEditParams): WorkspaceEdit {
  const { oldQname, newBareName, defEntry, defDocumentContent, references, ttrgDocuments } = params;
  const edits: TextDocumentEdit[] = [];

  const startOffset = positionToOffset(defDocumentContent, {
    line: defEntry.source.line - 1,
    character: defEntry.source.column,
  });
  const endOffset = positionToOffset(defDocumentContent, {
    line: defEntry.source.endLine - 1,
    character: defEntry.source.endColumn,
  });
  const currentText = defDocumentContent.slice(startOffset, endOffset);

  const newQname = [...oldQname.split('.').slice(0, -1), newBareName].join('.');

  if (currentText === newBareName || currentText === newQname) {
    return { documentChanges: [] };
  }

  edits.push(buildTextEdit(
    defEntry.documentUri,
    offsetToPosition(defDocumentContent, startOffset),
    offsetToPosition(defDocumentContent, endOffset),
    newBareName,
  ));

  for (const ref of references) {
    const content = ttrgDocuments.get(ref.documentUri);
    if (!content) continue;
    const refStartOffset = positionToOffset(content, {
      line: ref.source.line - 1,
      character: ref.source.column,
    });
    const refEndOffset = positionToOffset(content, {
      line: ref.source.endLine - 1,
      character: ref.source.endColumn,
    });
    const refLen = refEndOffset - refStartOffset;
    if (refLen <= 0) continue;
    const refText = content.slice(refStartOffset, refEndOffset);
    const oldSuffix = oldQname.split('.').slice(-1)[0];

    // Preserve the reference's qualification level: a fully-qualified mention
    // keeps its prefix (→ newQname), a bare mention stays bare (→ newBareName),
    // and a partially-qualified mention (e.g. `er.entity.produkt`) gets only its
    // trailing segment swapped. Collapsing a qualified ref to the bare name would
    // dangle it (the prefix/import no longer matches).
    let replacement: string;
    if (refText === oldQname) {
      replacement = newQname;
    } else if (refText === oldSuffix) {
      replacement = newBareName;
    } else {
      const swapped = refText.replace(new RegExp(escapeRegExp(oldSuffix) + '$'), newBareName);
      replacement = swapped !== refText ? swapped : newQname;
    }
    edits.push(buildTextEdit(
      ref.documentUri,
      offsetToPosition(content, refStartOffset),
      offsetToPosition(content, refEndOffset),
      replacement,
    ));
  }

  // Entity bindings name both the entity and its nested attributes. The reference
  // index for the entity itself excludes attribute targets; walk parsed binding slots
  // to rename their entity segment, keeping member suffixes and qualification.
  if (defEntry.kind === 'entity') for (const [uri, content] of ttrgDocuments) {
    if (!uri.endsWith('.ttrm')) continue;
    const ast = parseString(content, uri).ast;
    if (!ast) continue;
    const pkg = params.packageOf?.(uri) ?? ast.packageDecl?.name ?? '';
    const local = defEntry.packageName && oldQname.startsWith(defEntry.packageName + '.')
      ? oldQname.slice(defEntry.packageName.length + 1) : oldQname;
    for (const def of ast.definitions) {
      if (def.kind !== 'er2dbEntity' && def.kind !== 'er2dbAttribute') continue;
      for (const ref of collectReferences(def)) {
        let head: string | undefined;
        if (ref.path === oldQname || ref.path.startsWith(oldQname + '.')) head = oldQname;
        else if (pkg === defEntry.packageName && (ref.path === local || ref.path.startsWith(local + '.'))) head = local;
        if (!head) continue;
        const range = sourceLocationToRange(ref.source);
        if (edits.some(e => e.textDocument.uri === uri && e.edits.some(t =>
          t.range.start.line === range.start.line && t.range.start.character === range.start.character))) continue;
        const replacement = head.slice(0, head.lastIndexOf('.') + 1) + newBareName + ref.path.slice(head.length);
        edits.push(buildTextEdit(uri, range.start, range.end, replacement));
      }
    }
  }

  // Named imports of the renamed symbol (`import <oldQname>`) must follow it to
  // the new qname, or they dangle. Wildcard imports (`import <pkg>.*`) don't
  // name the symbol, so they're left untouched.
  for (const [uri, content] of ttrgDocuments) {
    if (uri.endsWith('.ttrg')) continue;
    const importRe = new RegExp(`(^|\\n)(\\s*import\\s+)(${escapeRegExp(oldQname)})(?=\\s|$)`, 'g');
    let im: RegExpExecArray | null;
    while ((im = importRe.exec(content)) !== null) {
      const qnameStart = im.index + im[1].length + im[2].length;
      edits.push(buildTextEdit(
        uri,
        offsetToPosition(content, qnameStart),
        offsetToPosition(content, qnameStart + oldQname.length),
        newQname,
      ));
    }
  }

  for (const [uri, content] of ttrgDocuments) {
    if (!uri.endsWith('.ttrg')) continue;
    const brackets = findObjectsBrackets(content);
    if (!brackets) continue;
    const { openOffset, closeOffset } = brackets;
    const inner = content.slice(openOffset + 1, closeOffset);
    const trimmed = inner.trim();
    if (!trimmed) continue;

    const parts = trimmed.split(',').map(s => s.trim()).filter(Boolean);
    let changed = false;
    const oldSegLen = oldQname.split('.').length;
    const newParts = parts.map(part => {
      const segs = part.split('.');
      const partQname = segs.join('.');
      if (partQname === oldQname || partQname.startsWith(oldQname + '.')) {
        changed = true;
        // Replace the renamed symbol's last segment with the new bare name,
        // keeping any child suffix (e.g. a.b.c → a.b.c2 leaves a.b.c.d → a.b.c2.d).
        const newSegs = [...segs.slice(0, oldSegLen - 1), newBareName, ...segs.slice(oldSegLen)];
        return newSegs.join('.');
      }
      return part;
    });
    if (!changed) continue;

    edits.push(buildTextEdit(
      uri,
      offsetToPosition(content, openOffset + 1),
      offsetToPosition(content, closeOffset),
      newParts.join(', '),
    ));
  }

  return { documentChanges: edits };
}

export function sourceLocationToRange(source: { line: number; column: number; endLine: number; endColumn: number }) {
  return {
    start: { line: source.line - 1, character: source.column },
    end: { line: source.endLine - 1, character: source.endColumn },
  };
}