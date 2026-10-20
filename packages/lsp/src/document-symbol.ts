// SPDX-License-Identifier: Apache-2.0
import {
  DocumentSymbol,
  SymbolKind,
  Range,
} from 'vscode-languageserver';
import type { Document, Definition, GraphBlock } from '@tatrman/parser';

export function buildDocumentSymbols(doc: Document, lines: string[] = []): DocumentSymbol[] {
  if (doc.graph) {
    return buildGraphSymbols(doc.graph);
  }
  return buildTtrSymbols(doc, lines);
}

/**
 * The selection range for a def — the name token on its first line.
 *
 * `def.source` spans the whole `def <kind> <name> { … }`, which may be
 * multi-line; its `endColumn` belongs to the LAST line. Using that column on the
 * start line (the old behaviour) produces a range that escapes — or even
 * inverts — when the closing `}` is less indented than the def, and VS Code
 * rejects the whole result with "selectionRange must be contained in fullRange".
 *
 * Locate the name on the start line instead, and keep the range on that single
 * line so it is always contained in `range`. Falls back to the def's start
 * column when the text isn't available or the name can't be located.
 */
function nameRange(def: Definition, lines: string[]): Range {
  const startLine = def.source.line - 1;
  const lineText = lines[startLine] ?? '';
  const located = lineText ? locateName(lineText, def.name, def.source.column) : -1;
  const col = located >= 0 ? located : def.source.column;
  let endCol = col + def.name.length;
  // Single-line def: never extend past the def's own end column.
  if (def.source.endLine === def.source.line) {
    endCol = Math.min(endCol, def.source.endColumn);
  }
  return {
    start: { line: startLine, character: col },
    end: { line: startLine, character: Math.max(col, endCol) },
  };
}

/** Locate `name` as a whole word at or after `startCol` on a line (or -1). */
function locateName(lineText: string, name: string, startCol: number): number {
  let idx = lineText.indexOf(name, startCol);
  while (idx >= 0) {
    const before = idx === 0 ? '' : lineText[idx - 1];
    if (!before || !/[A-Za-z0-9_]/.test(before)) return idx;
    idx = lineText.indexOf(name, idx + name.length);
  }
  return -1;
}

function buildGraphSymbols(graph: GraphBlock): DocumentSymbol[] {
  const children: DocumentSymbol[] = graph.objects.map((qname) => ({
    name: qname,
    kind: SymbolKind.Class,
    range: graph.source ? toRange(graph.source) : { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
    selectionRange: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
    detail: 'graph object',
    children: [],
  }));

  return [{
    name: graph.name || 'Unnamed Graph',
    kind: SymbolKind.File,
    range: toRange(graph.source),
    selectionRange: { start: { line: graph.source.line - 1, character: graph.source.column }, end: { line: graph.source.line - 1, character: graph.source.column + (graph.name?.length ?? 0) } },
    detail: graph.schema ? `schema ${graph.schema}` : undefined,
    children,
  }];
}

function buildTtrSymbols(doc: Document, lines: string[]): DocumentSymbol[] {
  const children: DocumentSymbol[] = [];

  if (doc.modelDirective?.modelCode) {
    const schemaKind = doc.modelDirective.modelCode;
    children.push({
      name: `${schemaKind}${doc.modelDirective.schema ? `.${doc.modelDirective.schema}` : ''}`,
      kind: SymbolKind.Namespace,
      range: doc.modelDirective.source ? toRange(doc.modelDirective.source) : { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
      selectionRange: doc.modelDirective.source
        ? { start: { line: doc.modelDirective.source.line - 1, character: doc.modelDirective.source.column }, end: { line: doc.modelDirective.source.line - 1, character: doc.modelDirective.source.endColumn } }
        : { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
      detail: 'schema',
      children: doc.definitions.map((def) => buildDefSymbol(def, lines)),
    });
  } else {
    children.push(...doc.definitions.map((def) => buildDefSymbol(def, lines)));
  }

  if (doc.packageDecl) {
    return [{
      name: doc.packageDecl.name,
      kind: SymbolKind.Package,
      range: toRange(doc.packageDecl.source),
      selectionRange: { start: { line: doc.packageDecl.source.line - 1, character: doc.packageDecl.source.column }, end: { line: doc.packageDecl.source.line - 1, character: doc.packageDecl.source.endColumn } },
      detail: 'package',
      children,
    }];
  }

  return children;
}

function buildDefSymbol(def: Definition, lines: string[]): DocumentSymbol {
  const kind = symbolKindOfDef(def.kind);
  const children: DocumentSymbol[] = [];

  const nestedKinds = nestedDefKinds(def);
  for (const [array, subKind] of nestedKinds) {
    const arr = (def as unknown as Record<string, unknown>)[array] as Definition[] | undefined;
    if (Array.isArray(arr)) {
      for (const child of arr) {
        children.push(buildNestedDefSymbol(child, subKind, lines));
      }
    }
  }

  return {
    name: def.name,
    kind,
    range: toRange(def.source),
    selectionRange: nameRange(def, lines),
    detail: `def ${defKeyword(def)}`,
    children,
  };
}

function buildNestedDefSymbol(def: Definition, subKind: SymbolKind, lines: string[]): DocumentSymbol {
  return {
    name: def.name,
    kind: subKind,
    range: toRange(def.source),
    selectionRange: nameRange(def, lines),
    detail: `def ${defKeyword(def)}`,
    children: [],
  };
}

/** The def keyword as written — a concept def and a cn binding carry theirs beside a shared `kind` (0.17). */
function defKeyword(def: Definition): string {
  if (def.kind === 'concept') return def.stereotype;
  if (def.kind === 'conceptBinding') return def.bindingKind;
  return def.kind;
}

function symbolKindOfDef(kind: string): SymbolKind {
  switch (kind) {
    case 'entity':
    case 'table':
    case 'view':
    case 'project':
      return SymbolKind.Class;
    case 'attribute':
    case 'column':
    case 'index':
    case 'constraint':
    case 'fk':
    case 'resultColumn':
      return SymbolKind.Field;
    case 'procedure':
    case 'query':
      return SymbolKind.Method;
    case 'relation':
      return SymbolKind.Interface;
    case 'role':
    case 'er2cncRole':
      return SymbolKind.Constant;
    default:
      return SymbolKind.Class;
  }
}

function nestedDefKinds(def: Definition): Array<[string, SymbolKind]> {
  switch (def.kind) {
    case 'entity':
      return [['attributes', SymbolKind.Field]];
    case 'table':
      return [['columns', SymbolKind.Field], ['indices', SymbolKind.Field], ['constraints', SymbolKind.Field]];
    case 'view':
      return [['columns', SymbolKind.Field]];
    case 'procedure':
      return [['resultColumns', SymbolKind.Field]];
    default:
      return [];
  }
}

function toRange(source: { line: number; column: number; endLine: number; endColumn: number }): Range {
  return {
    start: { line: source.line - 1, character: source.column },
    end: { line: source.endLine - 1, character: source.endColumn },
  };
}