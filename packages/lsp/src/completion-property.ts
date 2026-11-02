// SPDX-License-Identifier: Apache-2.0
import {
  CompletionItem,
  CompletionItemKind,
  CompletionList,
} from 'vscode-languageserver';
import type {
  Document,
  Definition,
} from '@tatrman/parser';
import type { DefinitionKind, PropertyInfo } from '@tatrman/grammar';
import { PROPERTY_MAP, SEARCH_SUB_PROPERTIES } from '@tatrman/grammar';
import { inferPackageFromUri } from '@tatrman/semantics';
import { detectReferenceProperty } from './completion-reference.js';

export interface PropertyCompletionOptions {
  position: { line: number; character: number };
  content: string;
  doc: Document;
}

export interface ModelCodeCompletionOptions {
  position: { line: number; character: number };
  content: string;
  doc: Document;
}

export interface SchemaHandleCompletionOptions {
  position: { line: number; character: number };
  content: string;
  doc: Document;
  /** Registered schema handles from the manifest (`[schemas.*]` keys + default). */
  schemaHandles: string[];
}

export interface DefKindCompletionOptions {
  position: { line: number; character: number };
  content: string;
  doc: Document;
}

export interface PackageNameCompletionOptions {
  position: { line: number; character: number };
  content: string;
  doc: Document;
  projectPackages: string[];
  documentUri: string;
  projectRoot: string;
  projectSymbols: import('@tatrman/semantics').ProjectSymbolTable;
}

/**
 * Grammar 0.22 (D-142): properties the grammar still RECOGNISES for one release, only so the automation validator
 * refuses them (`ttr/automation-removed`) — never offered.
 */
const REMOVED_PROPERTIES: Partial<Record<string, ReadonlySet<string>>> = { role: new Set(['delivery']) };

export function getPropertyNameCompletions(
  opts: PropertyCompletionOptions
): CompletionList | null {
  const ctx = detectPropertyPosition(opts.doc, opts.position);
  if (!ctx) return null;

  const { def, searchSubProperty } = ctx;
  if (searchSubProperty !== null) {
    return {
      items: SEARCH_SUB_PROPERTIES.map((name: string) => ({
        label: name,
        kind: CompletionItemKind.Property,
        detail: 'search sub-property',
        sortText: `0_${name}`,
      })),
      isIncomplete: false,
    };
  }

  const props = PROPERTY_MAP[def.kind as DefinitionKind] ?? [];
  const presentProps = getPresentProperties(def);

  const available = props.filter(
    (p: PropertyInfo) => !presentProps.has(p.name) && !REMOVED_PROPERTIES[def.kind]?.has(p.name)
  );

  const items: CompletionItem[] = available.map((p: PropertyInfo) => ({
    label: p.name,
    kind: CompletionItemKind.Property,
    detail: p.type,
    sortText: `0_${p.name}`,
  }));

  return { items, isIncomplete: false };
}

function detectPropertyPosition(
  doc: Document,
  position: { line: number; character: number }
): { def: Definition; searchSubProperty: string | null } | null {
  const line = position.line + 1;
  const char = position.character;

  for (const def of doc.definitions) {
    if (!isInRange(line, char, def.source)) continue;

    if (line === def.source.line) {
      const bodyStart = def.source.column + def.name.length + 8;
      if (char <= bodyStart) return null;
      const searchCtx = detectSearchSubProperty(def, line, char);
      if (searchCtx !== null) return { def, searchSubProperty: searchCtx };
      return { def, searchSubProperty: null };
    }

    for (const child of nestedDefArray(def)) {
      if (isInRange(line, char, child.source)) {
        return { def: child, searchSubProperty: null };
      }
    }

    const searchCtx = detectSearchSubProperty(def, line, char);
    if (searchCtx !== null) return { def, searchSubProperty: searchCtx };

    return { def, searchSubProperty: null };
  }

  return null;
}

function detectSearchSubProperty(
  def: Definition,
  line: number,
  char: number
): string | null {
  const search = (def as unknown as Record<string, unknown>)['search'] as
    | { source: { line: number; column: number; endLine: number; endColumn: number } }
    | undefined;
  if (!search) return null;
  if (!isInRange(line, char, search.source)) return null;
  return '';
}

function getPresentProperties(def: Definition): Set<string> {
  const present = new Set<string>();
  const obj = def as unknown as Record<string, unknown>;

  const propertyNames = Object.keys(obj).filter(
    (k) => k !== 'kind' && k !== 'name' && k !== 'source' && k !== 'parent'
  );

  for (const key of propertyNames) {
    const val = obj[key];
    if (val === undefined || val === null) continue;
    if (key === 'search' && typeof val === 'object' && val !== null) {
      const searchObj = val as Record<string, unknown>;
      for (const subKey of SEARCH_SUB_PROPERTIES) {
        if (subKey in searchObj) present.add(subKey);
      }
      continue;
    }
    present.add(key);
  }

  return present;
}

function nestedDefArray(def: Definition): Definition[] {
  switch (def.kind) {
    case 'entity':
      return def.attributes ?? [];
    case 'table':
      return [
        ...(def.columns ?? []),
        ...(def.indices ?? []),
        ...(def.constraints ?? []),
      ];
    case 'view':
      return def.columns ?? [];
    case 'procedure':
      return def.resultColumns ?? [];
    default:
      return [];
  }
}

function isInRange(
  line: number,
  char: number,
  loc: { line: number; column: number; endLine: number; endColumn: number }
): boolean {
  if (line < loc.line || line > loc.endLine) return false;
  if (line === loc.line && char < loc.column) return false;
  if (line === loc.endLine && char > loc.endColumn) return false;
  return true;
}

/**
 * Completions after the v4.0 `model` directive keyword — the model type/layer
 * codes. `query` is NOT offered (D14: it folded into `db`); `md` is included.
 */
export function getModelCodeCompletions(
  opts: ModelCodeCompletionOptions
): CompletionList | null {
  const lines = opts.content.split('\n');
  const pos = opts.position;

  if (pos.line >= lines.length) return null;
  const beforeCursor = lines[pos.line].slice(0, pos.character);
  if (!/\bmodel\s*$/.test(beforeCursor)) return null;

  const modelCodes = ['db', 'er', 'md', 'binding', 'cnc'];
  const items: CompletionItem[] = modelCodes.map((code) => ({
    label: code,
    kind: CompletionItemKind.Keyword,
    detail: `model ${code}`,
    sortText: `0_${code}`,
  }));

  return { items, isIncomplete: false };
}

/**
 * Completions after the v4.0 `schema` directive keyword — the named schema
 * handles registered in `modeler.toml` (`[schemas.*]`). Empty when the manifest
 * declares none (the schema slot is db-only — D6).
 */
export function getSchemaHandleCompletions(
  opts: SchemaHandleCompletionOptions
): CompletionList | null {
  const lines = opts.content.split('\n');
  const pos = opts.position;

  if (pos.line >= lines.length) return null;
  const beforeCursor = lines[pos.line].slice(0, pos.character);
  if (!/\bschema\s*$/.test(beforeCursor)) return null;

  const items: CompletionItem[] = opts.schemaHandles.map((handle) => ({
    label: handle,
    kind: CompletionItemKind.Value,
    detail: 'schema handle',
    sortText: `0_${handle}`,
  }));

  return { items, isIncomplete: false };
}

const ALL_DEF_KINDS = [
  'project',
  'table',
  'view',
  'column',
  'index',
  'constraint',
  'fk',
  'procedure',
  'entity',
  'attribute',
  'relation',
  'er2dbEntity',
  'er2dbAttribute',
  'er2dbRelation',
  'query',
  'role',
  'er2cncRole',
];

const SCHEMA_TO_DEFS: Record<string, string[]> = {
  er: ['entity', 'attribute', 'relation', 'er2cncRole'],
  db: ['table', 'view', 'column', 'index', 'constraint', 'fk', 'procedure', 'er2dbEntity', 'er2dbAttribute', 'er2dbRelation'],
  map: ['model', 'query'],
  query: ['query'],
  cnc: ['er2cncRole'],
};

export function getDefKindCompletions(
  opts: DefKindCompletionOptions
): CompletionList | null {
  const lines = opts.content.split('\n');
  const pos = opts.position;

  if (pos.line >= lines.length) return null;

  const defLine = lines[pos.line];
  const beforeCursor = defLine.slice(0, pos.character);

  const defKeywordMatch = beforeCursor.match(/^(\s*)def\s+$/);
  if (!defKeywordMatch) return null;

  const schemaCode = opts.doc.modelDirective?.modelCode;
  let kinds = ALL_DEF_KINDS;
  if (schemaCode && SCHEMA_TO_DEFS[schemaCode]) {
    kinds = SCHEMA_TO_DEFS[schemaCode];
  }

  const items: CompletionItem[] = kinds.map((kind) => ({
    label: kind,
    kind: CompletionItemKind.Keyword,
    detail: `def ${kind}`,
    sortText: `0_${kind}`,
  }));

  return { items, isIncomplete: false };
}

export function getPackageNameCompletions(
  opts: PackageNameCompletionOptions
): CompletionList | null {
  const lines = opts.content.split('\n');
  const pos = opts.position;

  if (pos.line >= lines.length) return null;

  const pkgLine = lines[pos.line];
  const beforeCursor = pkgLine.slice(0, pos.character);

  const packageMatch = beforeCursor.match(/^(\s*)package\s+$/);
  const importMatch = beforeCursor.match(/^(\s*)import\s+$/);

  if (!packageMatch && !importMatch) return null;

  const isPackage = !!packageMatch;
  const packages = opts.projectPackages;

  let items: CompletionItem[];
  if (isPackage) {
    const { inferred } = inferPackageFromUri(opts.documentUri, opts.projectRoot);
    const others = packages.filter((p) => p !== inferred);
    const all = [inferred, ...others].filter((p) => p.length > 0);

    if (all.length === 0) return { items: [], isIncomplete: false };

    items = all.map((pkg) => ({
      label: pkg,
      kind: CompletionItemKind.File,
      detail: pkg === inferred ? '(inferred from path)' : `package ${pkg}`,
      sortText: pkg === inferred ? `0_${pkg}` : `1_${pkg}`,
    }));
  } else {
    const prefixMatch = beforeCursor.match(/^(\s*)import\s+([\w.]*)$/);
    const prefix = prefixMatch ? prefixMatch[2] : '';

    items = packages
      // Drop the empty root package (package-less files) — it would render as a
      // blank-label item; you can't `import` the default package anyway.
      .filter((pkg) => pkg.length > 0 && (!prefix || pkg.startsWith(prefix)))
      .map((pkg) => {
        const count = opts.projectSymbols.getByPackage(pkg).length;
        return {
          label: pkg,
          kind: CompletionItemKind.File,
          detail: `${count} symbol${count !== 1 ? 's' : ''}`,
          sortText: `0_${pkg}`,
        };
      });

    return { items, isIncomplete: packages.length > 50 };
  }

  return { items, isIncomplete: packages.length > 50 };
}

export function detectCompletionContext(
  opts: PropertyCompletionOptions & { projectPackages?: string[]; documentUri?: string; projectRoot?: string }
): 'property' | 'modelCode' | 'schemaHandle' | 'defKind' | 'packageName' | 'reference' | null {
  const { position, content, doc } = opts;
  const lines = content.split('\n');
  const pos = position;

  if (pos.line < lines.length) {
    const line = lines[pos.line];
    const beforeCursor = line.slice(0, pos.character);

    // v4.0 — `model <code>` offers model codes; `schema <handle>` offers the
    // manifest's named schema handles. (`def ` is checked first so `def project`
    // never trips the model branch.)
    if (/\bdef\s+$/.test(beforeCursor)) return 'defKind';
    if (/\bmodel\s+$/.test(beforeCursor)) return 'modelCode';
    if (/\bschema\s+$/.test(beforeCursor)) return 'schemaHandle';
    if (/package\s+$/.test(beforeCursor)) return 'packageName';
    if (/import\s+[\w.]*$/.test(beforeCursor)) return 'packageName';
  }

  // H2.6 specifies reference → property → schema/def-kind → package-name.
  // In practice the five contexts (reference, property, schemaCode, defKind,
  // packageName) are mutually exclusive so the order has no functional effect.
  // Checking line-prefix patterns (schema/def/package) first is a cheap
  // fast-path — they resolve immediately without tree iteration.
  const refCtx = detectReferenceProperty(doc, position);
  if (refCtx) return 'reference';

  const ctx = detectPropertyPosition(doc, position);
  if (ctx) return 'property';

  return null;
}