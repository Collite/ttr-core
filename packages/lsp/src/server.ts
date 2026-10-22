// SPDX-License-Identifier: Apache-2.0
import {
  InitializeParams,
  InitializeResult,
  TextDocuments,
  TextDocumentSyncKind,
  Diagnostic,
  DiagnosticSeverity,
  TextDocumentChangeEvent,
  Connection,
  Location,
  SymbolInformation,
  SymbolKind,
  Hover,
  SemanticTokensBuilder,
  ResponseError,
  ErrorCodes,
  CodeActionKind,
  FileChangeType,
  CompletionItemKind,
  type CodeAction,
  type CompletionItem,
  type TextDocumentEdit,
  type TextEdit,
} from 'vscode-languageserver';
import { TextDocument } from 'vscode-languageserver-textdocument';
import fuzzysort from 'fuzzysort';
import {
  parseString,
  DiagnosticCode,
  sqlPosToFile,
  fileToSqlOffset,
  type ParseError,
  type Document,
  type Definition,
  type Reference,
  type SourceLocation,
  type TaggedBlockValue,
  type SqlDialect,
  type ColumnDef,
  type TableDef,
  type ViewDef,
  type DataType,
  type AreaDef,
} from '@tatrman/parser';
// Lexer-only entry — keeps the browser (Worker) bundle free of the SQL parsers.
import { lexSql, resolveDialect, classifyToken, maskPlaceholders, type SqlSemanticType } from '@tatrman/sql/lexers';
// Type-only: erased at compile, so the SQL parsers never reach the browser bundle.
// The runtime producer (`opts.analyzeSqlBlock`) is injected by the desktop host.
import type { SqlRefModel, Span as SqlSpan } from '@tatrman/sql';
import {
  ProjectSymbolTable,
  Resolver,
  effectiveSchemaId,
  resolveManifest,
  parseManifest,
  validateManifest,
  loadProjectFromOpenDocuments,
  collectReferences,
  nestedDefs,
  ReferenceIndex,
  PackageGraphBuilder,
  AreaTableBuilder,
  areaPackageClosure,
  enclosingQnameOf,
  effectivePackage,
  buildCanonicalKey,
  keyKindOf,
  synthesizeMappings,
  collectAllReferences,
  loadSqlConfig,
  emptySqlConfig,
  resolveSqlReferences,
  resolveSqlRefAt,
  checkSqlParameters,
  buildSqlDbIndex,
  resolveSqlTableName,
  sqlNameFor,
  foldEq,
  SqlReferenceIndex,
  resolveMdRef,
  mdCrossRefsOf,
  calcNames,
  cnHygieneReport,
  renderCnHygieneMarkdown,
  validateCn,
  sugarDeclaringRef,
  type CnDocument,
  type CnHygieneReport,
  type CnValidation,
  type ResolvedManifest,
  type ProjectManifest,
  type PackageGraph,
  type SqlConfig,
  type SqlRefHit,
  type SqlRefEntry,
  type SymbolEntry,
} from '@tatrman/semantics';
import { findSqlRefAtOffset, sqlCompletionContext, sqlScopeFromTokens } from './sql-features.js';
import { buildProjectModelGraph, emptyLayout, buildSymbolDetail, memberRefsOf, type LayoutFile, type RenderableSchemaCode } from './model-graph.js';
import { listGraphs, getGraph, getPackageGraphFromCache, buildBindingMap } from './graph-methods.js';
import { buildAddObjectEdit, buildRemoveObjectEdit, buildCreateGraphEdit, buildSetLayoutEdit, buildRenameSymbolEdit, buildRenamePackageEdit, type WorkspaceEdit } from '@tatrman/edit';
import { getReferenceCompletions, extractQueryPrefix } from './completion-reference.js';
import {
  getPropertyNameCompletions,
  getModelCodeCompletions,
  getSchemaHandleCompletions,
  getDefKindCompletions,
  getPackageNameCompletions,
  detectCompletionContext,
} from './completion-property.js';
import { buildDocumentSymbols } from './document-symbol.js';
import { loadCompletionConfig, getCompletionConfig, invalidateCompletionConfig } from './config-completion.js';
import { automationHoverLines } from './automation-hover.js';
import { conceptHoverLines } from './concept-hover.js';
import type { ListSymbolsParams, ListedSymbol, SchemaSubject } from './hosted-protocol.js';
import { definitionLabelReads, labelSource, participantsOf, type LabelRead } from './concept-rename.js';
import { formatDocument, DEFAULT_FORMAT_CONFIG, type FormatConfig } from '@tatrman/format';
import { lintDocument, lintProject, recommendedConfig, loadLintConfig, ruleForCode, type LintDiagnostic, type ResolvedLintConfig, type DocumentRuleContext } from '@tatrman/lint';
import { refactorExtractDefToNewFile } from './code-actions.js';
import { getCodeLenses } from './code-lens.js';

export interface ServerOptions {
  /**
   * Optional callback to load the project manifest for a workspace.
   * The stdio entry wires this to `findProjectRoot` + `loadProject` from
   * `@tatrman/semantics/node-only`; the browser entry leaves it undefined.
   */
  loadManifest?: (rootUri: string) => Promise<ResolvedManifest>;

  /**
   * Optional callback to pre-load stock vocabulary documents. Each entry's
   * `uri` is used as the URI in the symbol table (typically
   * `stock://<name>.ttrm`).
   */
  loadStock?: () => Promise<
    Array<{ uri: string; ast: Document; schemaCode: string; namespace: string }>
  >;

  /**
   * Optional in-memory layout store for browser mode. Maps project root URI
   * to the current LayoutFile.
   */
  layoutStore?: Map<string, LayoutFile>;

  /**
   * Whether auto-import is enabled for reference completion suggestions.
   * When true (default), selecting an unimported symbol inserts the
   * appropriate `import` line. Set to false to disable.
   */
  completionAutoImport?: boolean;

  /**
   * Reads a config file (`.ttrlint.toml`) from disk, or resolves `undefined` if
   * absent. The stdio host wires this to node fs; the browser worker leaves it
   * undefined (and the lint config falls back to `recommended`). Keeping the fs
   * access behind this callback keeps `fs` out of the browser bundle.
   */
  readConfigFile?: (path: string) => Promise<string | undefined>;

  /**
   * Scans the whole project for `.ttrm` files and returns each one's URI and
   * current on-disk text. Used to seed the symbol table so references resolve
   * across files the user hasn't opened. The stdio host wires this to
   * `loadProject` + node fs; the browser worker leaves it undefined (no
   * filesystem — resolution stays scoped to open documents). `root` is the
   * project root path (not a `file://` URI).
   */
  scanProjectFiles?: (root: string) => Promise<Array<{ uri: string; text: string }>>;

  /**
   * Reads a single project `.ttrm` file's on-disk text by URI, or resolves
   * `undefined` if absent. Used to refresh the symbol table when a closed file
   * changes on disk (watched-file events) or when an edited buffer is closed
   * and must revert to its saved content. Node host only.
   */
  readProjectFile?: (uri: string) => Promise<string | undefined>;

  /**
   * Parses + extracts a `SqlRefModel` from an embedded-SQL value for the given
   * dialect (embedded-sql §3.4). DESKTOP ONLY — wired by the stdio host to
   * `@tatrman/sql`'s `parseSql` + `extract`. The browser worker leaves it
   * undefined so the heavy SQL **parsers** never enter the Worker bundle (E11),
   * which also disables SQL reference diagnostics there (the Designer is
   * read-only). Returns `undefined` when the block can't be parsed at all.
   */
  analyzeSqlBlock?: (value: string, dialect: SqlDialect) => SqlRefModel | undefined;
}

type FoundNode =
  | { kind: 'def'; def: Definition; enclosing?: Definition }
  | { kind: 'ref'; ref: Reference; from: Definition };

function isPositionInRange(line: number, char: number, loc: SourceLocation): boolean {
  if (line < loc.line || line > loc.endLine) return false;
  if (line === loc.line && char < loc.column) return false;
  if (line === loc.endLine && char > loc.endColumn) return false;
  return true;
}

function rangeArea(loc: SourceLocation): number {
  return (loc.endLine - loc.line) * 1000 + (loc.endColumn - loc.column);
}

function sourceLocationToRange(source: SourceLocation) {
  return {
    start: { line: source.line - 1, character: source.column },
    end: { line: source.endLine - 1, character: source.endColumn },
  };
}

/** Line (1-indexed) / column (0-indexed) of a char offset within `text`. */
function offsetToLineColumn(text: string, offset: number): { line: number; column: number } {
  let line = 1;
  let column = 0;
  const end = Math.min(offset, text.length);
  for (let i = 0; i < end; i++) {
    if (text[i] === '\n') {
      line++;
      column = 0;
    } else {
      column++;
    }
  }
  return { line, column };
}

/**
 * Map a {@link SqlSpan} (coords within an embedded-SQL `value`) to an LSP file
 * range, threading both ends through the §8 source map (`sqlPosToFile`). The
 * span's start carries its own line/column; the end is derived from
 * `offset + length` so multi-line spans still close correctly.
 */
function sqlSpanToRange(span: SqlSpan, block: TaggedBlockValue) {
  const start = sqlPosToFile({ line: span.line, column: span.column }, block);
  const end = sqlPosToFile(offsetToLineColumn(block.value, span.offset + span.length), block);
  return {
    start: { line: start.line - 1, character: start.column },
    end: { line: end.line - 1, character: end.column },
  };
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The file range of just the *identifier* within a SQL ref's span — the last
 * whole-word, case-insensitive occurrence of `bareName` inside the ref text. A
 * table ref's span is the whole `dbo.Orders o`; renaming/highlighting wants only
 * `Orders` (not the `dbo.` qualifier or the `o` alias); a column ref's span is
 * `a.email`, and we want only `email`. Falls back to the full ref range if the
 * name isn't found literally (e.g. it was quoted/bracketed in the source).
 */
function preciseSqlNameRange(block: TaggedBlockValue, refSpan: SqlSpan, bareName: string) {
  const text = block.value.slice(refSpan.offset, refSpan.offset + refSpan.length);
  const re = new RegExp(`(^|[^A-Za-z0-9_])(${escapeRegExp(bareName)})(?![A-Za-z0-9_])`, 'gi');
  let start = -1;
  let len = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    start = m.index + m[1].length;
    len = m[2].length;
  }
  if (start < 0) return sqlSpanToRange(refSpan, block);
  const s = offsetToLineColumn(block.value, refSpan.offset + start);
  const e = offsetToLineColumn(block.value, refSpan.offset + start + len);
  return {
    start: { line: s.line - 1, character: s.column },
    end: { line: e.line - 1, character: e.column },
  };
}

export function createServerConnection(
  connection: Connection,
  opts: ServerOptions = {}
): void {
  const documents = new TextDocuments(TextDocument);

  const projectSymbols = new ProjectSymbolTable();
  let manifest: ResolvedManifest = resolveManifest(undefined, '');
  // `[sql]` config from modeler.toml (embedded-sql §3.3) — default dialect +
  // (database, schema) ⇄ namespace map for the §3.4 SQL resolver. Refreshed
  // whenever the project root changes; empty until then.
  let sqlConfig: SqlConfig = emptySqlConfig();
  // `() => manifest` so the resolver always reads the current manifest (reloaded
  // when the project root changes) for its slot vocabulary + schema defaults (D8).
  let resolver = new Resolver(projectSymbols, '', () => manifest);
  const refIndex = new ReferenceIndex();
  // Project-wide index of resolved embedded-SQL refs → `db` symbol qname (§4.3).
  const sqlRefIndex = new SqlReferenceIndex();
  let packageGraph: PackageGraph | null = null;
  // CN-P2 S2.7 — the project's cn analysis (the validation and the hygiene report), read by
  // `modeler/getCnHygiene` and the concept hover; dropped whenever the symbol table changes.
  let cnCache: { validation: CnValidation; report: CnHygieneReport } | null = null;
  let symbolGeneration = 0;
  let symbolPageCache: { key: string; symbols: ListedSymbol[] } | null = null;
  function invalidateSymbols(): void {
    cnCache = null;
    symbolGeneration++;
    symbolPageCache = null;
  }
  let supportsConfiguration = false;
  // Resolved `.ttrlint.toml` config, cached and refreshed asynchronously
  // (publishDiagnostics is sync). Starts at the legacy-aware recommended config.
  let cachedLintConfig: ResolvedLintConfig = recommendedConfig({});

  /**
   * The document's effective package (PD1.3): in-file `package` declaration if
   * present, else directory-derived with the configured `[packages].root`
   * prefix. The single source of a file's package name across symbol building,
   * reference resolution, and qname construction.
   */
  function effPkg(uri: string, ast: Document): string {
    return effectivePackage(ast, uri, manifest.projectRoot, manifest.packages);
  }

  /**
   * Locate the most-specific AST node under the cursor.
   *
   * Walks every top-level def, recurses into nested attribute / column /
   * resultColumn children, and inspects every reference-valued property.
   * Returns the smallest range that contains the cursor; prefers
   * reference matches over their enclosing defs (so Cmd-clicking on
   * `er.entity.foo` inside a `nameAttribute:` lands on the reference).
   */
  function findNodeAtPosition(
    ast: Document,
    position: { line: number; character: number }
  ): FoundNode | null {
    const line = position.line + 1;
    const char = position.character;
    let best: FoundNode | null = null;
    let bestArea = Number.POSITIVE_INFINITY;

    function consider(node: FoundNode, source: SourceLocation): void {
      if (!isPositionInRange(line, char, source)) return;
      const area = rangeArea(source);
      if (area < bestArea) {
        best = node;
        bestArea = area;
      }
    }

    function visit(def: Definition, enclosing?: Definition): void {
      if (!isPositionInRange(line, char, def.source)) return;
      consider({ kind: 'def', def, enclosing }, def.source);

      for (const child of nestedDefs(def)) {
        visit(child, def);
      }
      for (const ref of collectReferences(def)) {
        consider({ kind: 'ref', ref, from: def }, ref.source);
      }
      // 0.17 — a `concept { }` block's declaring slot names its concept (review-119 H-2).
      const declaring = sugarDeclaringRef(def);
      if (declaring) consider({ kind: 'ref', ref: declaring, from: def }, declaring.source);
    }

    for (const def of ast.definitions) visit(def);
    return best;
  }

  function qnameOf(def: Definition, ast: Document, enclosing?: Definition, uri = ''): string {
    // Symbol-table keys are package-qualified (B2), so the package prefix must
    // lead. For package-less files `pkg` is '' and is filtered out, leaving the
    // v1 shape unchanged.
    const pkg = uri ? effPkg(uri, ast) : ast.packageDecl?.name ?? '';
    // TODO(pkg-schema-defaults): the `?? 'db'` display/lookup defaults in this
    // file are presentation-layer and out of scope for the schema-by-kind
    // correctness fix; they should later derive via defaultSchemaForKind.
    const namespace = ast.modelDirective?.schema ?? '';
    // v4.0 uniform key: model/schema/kind come from the (owning) def's kind; a
    // member uses its enclosing def's kind, with the name path growing under it.
    const kind = enclosing ? enclosing.kind : keyKindOf(def);
    const parts = enclosing ? [enclosing.name, def.name] : [def.name];
    // The raw directive (no presentation default): it moves only a `query` declared under
    // `model er` — a logical query, `er.query.<name>` (LQ L-1), as the symbol table keys it.
    const declaringModel = ast.modelDirective?.modelCode ?? '';
    return buildCanonicalKey({ packageName: pkg, schemaId: namespace, kind, parts, declaringModel });
  }

  // A symbol's stored `source` is the whole `def <kind> <name> { … }` span, but
  // rename / prepareRename need just the name token. Locate it after the
  // `def <kind> ` prefix on the definition's first line. Falls back to the full
  // span if the shape is unexpected.
  function defNameSource(content: string, symbol: { name: string; source: SourceLocation }): SourceLocation {
    const line = content.split('\n')[symbol.source.line - 1] ?? '';
    const m = line.slice(symbol.source.column).match(/^def\s+\S+\s+/);
    if (!m) return symbol.source;
    const col = symbol.source.column + m[0].length;
    return { ...symbol.source, endLine: symbol.source.line, column: col, endColumn: col + symbol.name.length };
  }

  function symbolKindOf(kind: string): SymbolKind {
    if (kind === 'entity' || kind === 'table' || kind === 'view') return SymbolKind.Class;
    if (kind === 'column' || kind === 'attribute') return SymbolKind.Field;
    if (kind === 'procedure' || kind === 'query') return SymbolKind.Method;
    return SymbolKind.File;
  }

  function getDocument(uri: string): TextDocument | undefined {
    return documents.get(uri);
  }

  function parseDocument(content: string, uri: string): Document | undefined {
    const result = parseString(content, uri);
    return result.ast;
  }

  // --- Embedded-SQL IDE features (§4.1–4.3) — desktop only --------------------
  // Cache extracted SqlRefModels by (dialect, value) so hover/definition/refs
  // don't re-parse SQL. Identical SQL text ⇒ identical model, so the key is
  // content-based (version-independent); stale entries are harmless.
  const sqlModelCache = new Map<string, SqlRefModel | null>();
  function analyzeSqlCached(value: string, dialect: SqlDialect): SqlRefModel | undefined {
    if (!opts.analyzeSqlBlock) return undefined;
    const key = `${dialect}\u0000${value}`;
    const cached = sqlModelCache.get(key);
    if (cached !== undefined) return cached ?? undefined;
    let model: SqlRefModel | undefined;
    try {
      model = opts.analyzeSqlBlock(value, dialect);
    } catch {
      model = undefined;
    }
    sqlModelCache.set(key, model ?? null);
    return model;
  }

  /** SQL-language tagged block on a query/view def, or undefined. */
  function sqlBlockOf(def: Definition): TaggedBlockValue | undefined {
    const block = def.kind === 'query' ? def.sourceText : def.kind === 'view' ? def.definitionSql : undefined;
    return block?.kind === 'taggedBlock' && block.language === 'SQL' ? block : undefined;
  }

  interface SqlContext {
    block: TaggedBlockValue;
    dialect: SqlDialect;
    model?: SqlRefModel;
    /** The hit-tested table/column ref under the cursor, if any. */
    hit?: SqlRefHit;
  }
  /**
   * Whether a file position lies inside a SQL block, and (if so) the ref under
   * the cursor. Returns a context even on a keyword/whitespace miss (`hit`
   * undefined) so hover/definition can short-circuit there rather than falling
   * through to the enclosing-def behaviour. Desktop only (gated on the SQL
   * analyzer); the browser worker gets `undefined` and unchanged behaviour.
   */
  function sqlContextAt(ast: Document, position: { line: number; character: number }): SqlContext | undefined {
    if (!opts.analyzeSqlBlock) return undefined;
    for (const def of ast.definitions) {
      const block = sqlBlockOf(def);
      if (!block) continue;
      const offset = fileToSqlOffset(block, position.line + 1, position.character);
      if (offset === undefined) continue;
      const dialect = resolveDialect(block, sqlConfig);
      const model = analyzeSqlCached(block.value, dialect);
      const hit = model ? findSqlRefAtOffset(model, offset) : undefined;
      return { block, dialect, model, hit };
    }
    return undefined;
  }

  /** Resolve a hit-tested SQL ref to its TTR `db` symbol(s). */
  function resolveSqlContextHit(ctx: SqlContext) {
    if (!ctx.hit || !ctx.model) return [];
    return resolveSqlRefAt(ctx.hit, ctx.model, { dialect: ctx.dialect, config: sqlConfig, symbols: projectSymbols });
  }

  /** Best-effort text for any project document (open buffer or scanned-from-disk). */
  function anyDocumentText(uri: string): string | undefined {
    return getDocument(uri)?.getText() ?? diskDocs.get(uri);
  }

  function dataTypeToString(t: DataType | undefined): string | undefined {
    if (!t) return undefined;
    if (t.kind === 'simple') return t.name;
    const args = [t.length, t.precision].filter((n): n is number => n !== undefined);
    return args.length ? `${t.typeName}(${args.join(', ')})` : t.typeName;
  }

  /** The AST table/view def (and column def, for a column symbol) backing a symbol. */
  function sqlDefInfo(symbol: { kind: string; name: string; parent?: string; documentUri: string }): {
    table?: TableDef | ViewDef;
    column?: ColumnDef;
  } {
    const text = anyDocumentText(symbol.documentUri);
    if (!text) return {};
    const ast = parseDocument(text, symbol.documentUri);
    if (!ast) return {};
    if (symbol.kind === 'table' || symbol.kind === 'view') {
      const def = ast.definitions.find((d) => (d.kind === 'table' || d.kind === 'view') && d.name === symbol.name);
      return { table: def as TableDef | ViewDef | undefined };
    }
    if (symbol.kind === 'column') {
      const tableName = symbol.parent ? projectSymbols.get(symbol.parent)?.name : undefined;
      for (const d of ast.definitions) {
        if (d.kind !== 'table' && d.kind !== 'view') continue;
        if (tableName && d.name !== tableName) continue;
        const col = (d.columns ?? []).find((c) => c.name === symbol.name);
        if (col) return { table: d, column: col };
      }
    }
    return {};
  }

  /**
   * (Re)build the SQL reference index for one document (§4.3): resolve every
   * table/column ref in its SQL blocks and record the unambiguously-resolved ones
   * under their `db` symbol qname. Reuses cached SqlRefModels (no extra parse for
   * already-seen SQL). Desktop only — a no-op without the SQL analyzer.
   */
  function indexSqlReferences(uri: string, ast: Document): void {
    if (!opts.analyzeSqlBlock) return;
    const entries: SqlRefEntry[] = [];
    for (const def of ast.definitions) {
      const block = sqlBlockOf(def);
      if (!block) continue;
      const dialect = resolveDialect(block, sqlConfig);
      const model = analyzeSqlCached(block.value, dialect);
      if (!model) continue;
      const rctx = { dialect, config: sqlConfig, symbols: projectSymbols };
      const record = (hit: SqlRefHit, span: SqlSpan): void => {
        const syms = resolveSqlRefAt(hit, model, rctx);
        if (syms.length !== 1) return; // only unambiguous refs are attributable
        // Store the identifier's precise range (not the whole ref span) so
        // find-refs highlights and rename (§4.5) touch only the name.
        entries.push({ qname: syms[0].qname, loc: { uri, range: preciseSqlNameRange(block, span, syms[0].name) } });
      };
      for (const ref of model.tables) record({ kind: 'table', ref }, ref.span);
      for (const ref of model.columns) {
        if (ref.name !== '*') record({ kind: 'column', ref }, ref.span);
      }
    }
    sqlRefIndex.upsertDocument(uri, entries);
  }

  const descOf = (def: { description?: { kind: string; value: string } } | undefined): string | undefined => {
    const d = def?.description;
    return d && (d.kind === 'string' || d.kind === 'tripleString') ? d.value : undefined;
  };

  /** Completion items at a cursor inside a SQL block (§4.4) — best-effort, lexer-first. */
  function sqlCompletionItems(block: TaggedBlockValue, dialect: SqlDialect, offset: number): CompletionItem[] {
    const ctx = sqlCompletionContext(block.value, dialect, offset);
    if (!ctx) return [];

    if (ctx.kind === 'table') {
      // Every modelled `db` table, inserted as a dialect-correct qualified name
      // via the reverse namespace map (3.3.4). Casing is the TTR symbol's own.
      const items: CompletionItem[] = [];
      for (const [namespace, tables] of buildSqlDbIndex(projectSymbols)) {
        const sqlName = sqlNameFor(sqlConfig, namespace);
        for (const t of tables) {
          const insertText = sqlName ? `${sqlName.schema}.${t.entry.name}` : t.entry.name;
          items.push({
            label: t.entry.name,
            kind: CompletionItemKind.Class,
            detail: `${insertText}  (namespace ${namespace})`,
            documentation: descOf(sqlDefInfo(t.entry).table),
            insertText,
          });
        }
      }
      return items;
    }

    // Column context: derive the in-scope tables from FROM/JOIN tokens (works on
    // partial SQL where a full parse yields no scope), filter by `alias.` if given.
    const rctx = { dialect, config: sqlConfig, symbols: projectSymbols };
    const scope = sqlScopeFromTokens(block.value, dialect);
    const wanted = ctx.qualifier
      ? scope.filter(
          (t) =>
            (t.alias && foldEq(t.alias, ctx.qualifier!, dialect)) ||
            (!t.alias && t.name.length > 0 && foldEq(t.name[t.name.length - 1]!, ctx.qualifier!, dialect)),
        )
      : scope;

    const items: CompletionItem[] = [];
    const seen = new Set<string>();
    for (const t of wanted) {
      const symbol = resolveSqlTableName(t.name, rctx);
      if (!symbol) continue; // unresolved / CTE / derived → no column metadata
      const cols = sqlDefInfo(symbol).table?.columns ?? [];
      for (const col of cols) {
        const key = `${symbol.qname}.${col.name}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const type = dataTypeToString(col.type);
        items.push({
          label: col.name,
          kind: CompletionItemKind.Field,
          detail: [type, symbol.name].filter(Boolean).join(' · '),
          documentation: descOf(col),
          insertText: col.name,
        });
      }
    }
    return items;
  }

  function rebuildSemantics(projectRoot?: string): void {
    resolver = new Resolver(projectSymbols, '', () => manifest);
    if (projectRoot) manifest.projectRoot = projectRoot;
    packageGraph = null;
    invalidateSymbols();
  }

  function getPackageGraph(): PackageGraph {
    if (!packageGraph) {
      const docs = new Map<string, Document>();
      for (const uri of documents.keys()) {
        const doc = parseDocument(documents.get(uri)?.getText() ?? '', uri);
        if (doc) docs.set(uri, doc);
      }
      packageGraph = new PackageGraphBuilder(projectSymbols, docs).build();
    }
    return packageGraph;
  }

  function publishDiagnostics(uri: string, content: string, projectDiagnostics?: Map<string, LintDiagnostic[]>): void {
    if (uri.endsWith('.ttrl')) return;

    const result = parseString(content, uri);
    const diagnostics: Diagnostic[] = result.errors.map((err: ParseError) => ({
      range: sourceLocationToRange(err.source),
      message: err.message,
      severity: severityToLsp(err.severity),
      code: err.code,
      source: 'modeler',
    }));

    if (result.ast) {
      const pkgGraph = getPackageGraph();
      const deps = { manifest, symbols: projectSymbols, resolver };
      const config = lintConfig();

      // Document-scoped rules for this file.
      const docDiags = lintDocument(uri, result.ast, deps, config);
      // Project-scoped rules (duplicate-definition/-mapping, circular), bucketed
      // by uri and filtered to this file — mirrors the old `.filter(d.source.file)`.
      const projectByUri = projectDiagnostics ?? lintProject(getProjectDocuments(), pkgGraph, deps, config);
      const projDiags = projectByUri.get(uri) ?? [];

      for (const d of [...docDiags, ...projDiags]) {
        diagnostics.push(toLspDiagnostic(d));
      }

      // Embedded-SQL reference resolution (§3.4). Desktop only: the producer
      // (`opts.analyzeSqlBlock`, the SQL parsers) is absent in the browser worker.
      if (opts.analyzeSqlBlock) {
        for (const d of collectSqlDiagnostics(result.ast)) diagnostics.push(d);
      }
    }

    connection.sendDiagnostics({ uri, diagnostics });
  }

  /**
   * Resolve every SQL-language tagged block in the document against the TTR `db`
   * symbol table and return LSP diagnostics positioned via the §8 source map.
   * Only `db`-targeting SQL is analyzed; transform/dataframe blocks, untagged
   * triple-strings, and unparseable SQL are skipped (best-effort).
   */
  function collectSqlDiagnostics(ast: Document): Diagnostic[] {
    const out: Diagnostic[] = [];
    for (const def of ast.definitions) {
      const block =
        def.kind === 'query' ? def.sourceText : def.kind === 'view' ? def.definitionSql : undefined;
      if (block?.kind !== 'taggedBlock' || block.language !== 'SQL') continue;
      const dialect = resolveDialect(block, sqlConfig);
      const model = analyzeSqlCached(block.value, dialect);
      if (!model) continue;
      const placeholders = maskPlaceholders(block.value).placeholders;
      const sqlBlock = block;
      const pushSqlDiag = (d: { code: DiagnosticCode; message: string; span: SqlSpan; severity: 'error' | 'warning' }): void => {
        out.push({
          range: sqlSpanToRange(d.span, sqlBlock),
          message: d.message,
          severity: d.severity === 'error' ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning,
          code: d.code,
          source: 'modeler',
        });
      };

      // §3.4 — table/column reference resolution.
      for (const d of resolveSqlReferences(model, { dialect, config: sqlConfig, symbols: projectSymbols, placeholders })) {
        pushSqlDiag(d);
      }

      // §3.5 — cross-check SQL params against the query's declared `parameters`.
      if (def.kind === 'query') {
        const declared = def.parameters ?? [];
        const placeholderUsages = placeholders.map((p) => ({
          name: p.name,
          span: { offset: p.offset, length: p.length, ...offsetToLineColumn(block.value, p.offset) },
        }));
        const { diagnostics: paramDiags, unusedParamNames } = checkSqlParameters({
          declared,
          placeholders: placeholderUsages,
          nativeParams: model.params,
          dialect,
        });
        for (const d of paramDiags) pushSqlDiag(d);
        // Unused-param warnings sit on the TTR declaration (file coords), not the SQL value.
        for (const name of unusedParamNames) {
          const decl = declared.find((p) => p.name === name);
          if (!decl) continue;
          out.push({
            range: sourceLocationToRange(decl.source),
            message: `Query parameter '${name}' is declared but never used in the SQL`,
            severity: DiagnosticSeverity.Warning,
            code: DiagnosticCode.SqlUnusedParam,
            source: 'modeler',
          });
        }
      }
    }
    return out;
  }

  function lintConfig(): ResolvedLintConfig {
    return cachedLintConfig;
  }

  /**
   * Re-read `.ttrlint.toml` (legacy `[lint]` fallback), cache it, surface its
   * config-level diagnostics, and re-lint open documents. Safe to call whenever
   * the manifest/project root changes or the config file is edited.
   */
  async function refreshLintConfig(): Promise<void> {
    await refreshSqlConfig();
    cachedLintConfig = await loadLintConfig(manifest.projectRoot, manifest.lint, opts.readConfigFile);
    publishConfigDiagnostics();
    // One stable project snapshot for this full revalidation. Rebuilding project lint per document
    // made a browser file-map's required second pass quadratic and repeated the same diagnostics.
    const projectDiagnostics = lintProject(getProjectDocuments(), getPackageGraph(),
      { manifest, symbols: projectSymbols, resolver }, lintConfig());
    for (const doc of documents.all()) {
      publishDiagnostics(doc.uri, doc.getText(), projectDiagnostics);
    }
  }

  /**
   * Re-read the `[sql]` section of `modeler.toml` (embedded-sql §3.3) and cache
   * it for the §3.4 SQL resolver. Best-effort: a missing file or absent host
   * `readConfigFile` (browser) leaves the empty config in place. Returns silently;
   * callers re-publish diagnostics themselves.
   */
  async function refreshSqlConfig(): Promise<void> {
    const root = manifest.projectRoot;
    if (!root || !opts.readConfigFile) {
      sqlConfig = emptySqlConfig();
      return;
    }
    const content = await opts.readConfigFile(`${root.replace(/\/$/, '')}/modeler.toml`);
    sqlConfig = content ? loadSqlConfig(content).config : emptySqlConfig();
  }

  /** Publish ttrlint/* config diagnostics on the `.ttrlint.toml` document. */
  function publishConfigDiagnostics(): void {
    const root = manifest.projectRoot;
    if (!root) return;
    const configUri = `file://${root.replace(/\/$/, '')}/.ttrlint.toml`;
    connection.sendDiagnostics({
      uri: configUri,
      diagnostics: cachedLintConfig.diagnostics.map(toLspDiagnostic),
    });
  }

  function cnProject(): { validation: CnValidation; report: CnHygieneReport } {
    if (cnCache) return cnCache;
    const texts = new Map<string, string>(diskDocs);
    for (const d of documents.all()) texts.set(d.uri, d.getText());
    const docs: CnDocument[] = [];
    for (const [uri, text] of texts) {
      if (!uri.endsWith('.ttrm')) continue;
      const ast = parseDocument(text, uri);
      if (!ast) continue;
      const context = projectSymbols.documentContext(uri) ?? {
        schemaCode: ast.modelDirective?.modelCode ?? '',
        namespace: ast.modelDirective?.schema ?? '',
        packageName: effPkg(uri, ast),
      };
      docs.push({ uri, ast, ...context });
    }
    const validation = validateCn(docs, resolver);
    cnCache = { validation, report: cnHygieneReport(docs, resolver, validation) };
    return cnCache;
  }

  function getProjectDocuments(): Map<string, Document> {
    const docs = new Map<string, Document>();
    for (const u of documents.keys()) {
      const doc = parseDocument(documents.get(u)?.getText() ?? '', u);
      if (doc) docs.set(u, doc);
    }
    return docs;
  }

  function toLspDiagnostic(d: LintDiagnostic): Diagnostic {
    return {
      range: sourceLocationToRange(d.source),
      message: d.message,
      severity: severityToLsp(d.severity),
      code: d.code,
      source: 'modeler',
    };
  }

  function severityToLsp(s: 'error' | 'warning' | 'info' | 'off'): DiagnosticSeverity {
    return s === 'warning' ? DiagnosticSeverity.Warning
      : s === 'info' ? DiagnosticSeverity.Information
      // 'off' is unreachable here (off rules are never emitted), but keeps the
      // mapping total for the lint Severity type.
      : s === 'off' ? DiagnosticSeverity.Information
      : DiagnosticSeverity.Error;
  }

  function updateSymbolTable(uri: string, content: string): void {
    invalidateSymbols();
    const result = parseString(content, uri);
    if (!result.ast) return;
    // '' (no directive) ⇒ the semantics layer derives the schema per definition
    // from its kind (modelForKind). Do NOT default to 'db' here. When the file
    // declares no `schema`, fill the db schema slot from the manifest (package
    // default-schema → `[defaults].schema`, D8); er/md/cnc ignore it downstream.
    const schemaCode = result.ast.modelDirective?.modelCode ?? '';
    const packageName = effPkg(uri, result.ast);
    const namespace = effectiveSchemaId(result.ast.modelDirective?.schema, packageName, manifest);
    projectSymbols.upsertDocument(uri, result.ast, schemaCode, namespace, packageName);
    synthesizeMappings(projectSymbols, uri, result.ast);
    refIndex.upsertDocument(uri, result.ast, schemaCode, namespace, resolver, packageName);
    indexSqlReferences(uri, result.ast);
  }

  // On-disk text of every project `.ttrm` file, keyed by URI. Seeded by
  // `loadProjectIntoSymbols` and kept current via watched-file events. Lets a
  // closed editor buffer revert to its saved content (rather than vanish from
  // the project) on `onDidClose`. Empty in browser mode (no `scanProjectFiles`).
  const diskDocs = new Map<string, string>();

  const isOpen = (uri: string): boolean => documents.get(uri) !== undefined;

  /**
   * Seed `projectSymbols` (and the reference index) from every `.ttrm` file on
   * disk so references resolve across files the user hasn't opened. Open editor
   * buffers are authoritative and are skipped here — their content is owned by
   * the document lifecycle. Best-effort: a missing callback or a read failure
   * leaves resolution scoped to open documents, exactly as before.
   */
  async function loadProjectIntoSymbols(): Promise<void> {
    if (!opts.scanProjectFiles) return;
    const root = manifest.projectRoot;
    if (!root) return;

    let files: Array<{ uri: string; text: string }>;
    try {
      files = await opts.scanProjectFiles(root);
    } catch {
      return; // best-effort, mirrors stock loading
    }

    // Pass 1: register every file's symbols. References can only resolve once
    // the full symbol universe is present, so the reference index waits.
    const ingested: Array<{ uri: string; ast: Document; schemaCode: string; namespace: string; packageName: string }> = [];
    for (const f of files) {
      diskDocs.set(f.uri, f.text);
      if (isOpen(f.uri)) continue;
      const result = parseString(f.text, f.uri);
      if (!result.ast) continue;
      const schemaCode = result.ast.modelDirective?.modelCode ?? '';
      const packageName = effPkg(f.uri, result.ast);
      const namespace = effectiveSchemaId(result.ast.modelDirective?.schema, packageName, manifest);
      projectSymbols.upsertDocument(f.uri, result.ast, schemaCode, namespace, packageName);
      synthesizeMappings(projectSymbols, f.uri, result.ast);
      ingested.push({ uri: f.uri, ast: result.ast, schemaCode, namespace, packageName });
    }

    // Pass 2: build the reference index now that all symbols are known.
    for (const f of ingested) {
      refIndex.upsertDocument(f.uri, f.ast, f.schemaCode, f.namespace, resolver, f.packageName);
      indexSqlReferences(f.uri, f.ast);
    }

    packageGraph = null;
    invalidateSymbols();
  }

  documents.onDidOpen(async (event: TextDocumentChangeEvent<TextDocument>) => {
    // TextDocuments fires onDidChangeContent immediately after onDidOpen, even for a new file.
    // The worker already has its manifest: let that handler index/lint once. A desktop host's
    // async manifest load still needs the second pass below, after its configuration arrives.
    if (!opts.loadManifest) return;
    // First open in a workspace: ask the host for the manifest. Cheap when
    // no callback is wired (browser worker).
    if (opts.loadManifest) {
      try {
        const root = event.document.uri.replace(/\/[^/]+$/, '');
        const loaded = await opts.loadManifest(root);
        manifest = loaded;
        rebuildSemantics();
        await refreshLintConfig();
      } catch {
        // keep the default manifest
      }
    }
    updateSymbolTable(event.document.uri, event.document.getText());
    publishDiagnostics(event.document.uri, event.document.getText());
  });

  documents.onDidChangeContent((event: TextDocumentChangeEvent<TextDocument>) => {
    updateSymbolTable(event.document.uri, event.document.getText());
    publishDiagnostics(event.document.uri, event.document.getText());
  });

  documents.onDidClose((event: TextDocumentChangeEvent<TextDocument>) => {
    const uri = event.document.uri;
    const disk = diskDocs.get(uri);
    if (disk !== undefined) {
      // A project file on disk: revert the table to its saved content rather
      // than dropping it — it's still part of the project after the tab closes.
      updateSymbolTable(uri, disk);
    } else {
      projectSymbols.removeDocument(uri);
      refIndex.removeDocument(uri);
      sqlRefIndex.removeDocument(uri);
    }
    packageGraph = null;
    invalidateSymbols();
  });

  documents.onDidSave(() => {
    // nothing yet
  });

  connection.onInitialize(async (params: InitializeParams): Promise<InitializeResult> => {
    if (opts.loadStock) {
      try {
        const docs = await opts.loadStock();
        for (const d of docs) {
          projectSymbols.upsertDocument(d.uri, d.ast, d.schemaCode, d.namespace, '');
          invalidateSymbols();
          synthesizeMappings(projectSymbols, d.uri, d.ast);
        }
      } catch {
        // stock loading is best-effort
      }
    }
    const wsUri = params.workspaceFolders?.[0]?.uri
      ?? params.rootUri
      ?? (params.rootPath ? `file://${params.rootPath}` : null);
    supportsConfiguration = !!params.capabilities?.workspace?.configuration;
    if (wsUri) {
      const projectRoot = wsUri.startsWith('file://') ? new URL(wsUri).pathname : wsUri;
      manifest = resolveManifest(undefined, projectRoot);
      // Load the real manifest (incl. `[packages]` root/layout) before seeding,
      // so disk-seeded symbols are keyed by the configured effective package
      // (PD1.3) rather than the default root="". Best-effort; falls back to the
      // default manifest when no host callback is wired (browser worker).
      if (opts.loadManifest) {
        try {
          // `loadManifest` resolves the root by walking up from the arg's parent
          // directory, so point it at a path *inside* the workspace root.
          const inside = `${wsUri.replace(/\/$/, '')}/modeler.toml`;
          manifest = await opts.loadManifest(inside);
        } catch {
          // keep the default manifest
        }
        await refreshLintConfig();
      }
      rebuildSemantics(projectRoot);
      // Seed the symbol table from the whole project so references resolve into
      // files that are never opened. Awaited here (before the initialize
      // response, hence before any didOpen) so the first opened file already
      // sees the full project.
      await loadProjectIntoSymbols();
    }
    return {
      capabilities: {
        textDocumentSync: {
          openClose: true,
          willSave: false,
          save: false,
          change: TextDocumentSyncKind.Full,
        },
        definitionProvider: true,
        referencesProvider: true,
        hoverProvider: true,
        workspaceSymbolProvider: true,
        documentSymbolProvider: true,
        completionProvider: {
          triggerCharacters: ['.'],
          resolveProvider: true,
        },
        renameProvider: { prepareProvider: true },
        documentFormattingProvider: true,
        codeActionProvider: { codeActionKinds: ['quickfix', 'refactor.extract'] },
        codeLensProvider: { resolveProvider: false },
        semanticTokensProvider: {
          legend: {
            tokenTypes: [
              'namespace',
              'type',
              'class',
              'property',
              'string',
              'number',
              'comment',
              'keyword',
              'variable',
              // v1.1 additions (indices 9-12). Appended to keep existing indices stable.
              'packageName',
              'importedSymbol',
              'localSymbol',
              'unimportedReference',
              // embedded-sql additions (indices 13-14). Other SQL token types reuse
              // existing indices: keyword(7), string(4), number(5), comment(6),
              // variable(8), table→class(2), column→property(3). (§7)
              'operator',
              'parameter',
            ],
            tokenModifiers: ['declaration', 'readonly', 'deprecated'],
          },
          full: true,
        },
      },
    };
  });

  connection.onInitialized(async () => {
    if (supportsConfiguration) {
      await loadCompletionConfig(connection);
    }
    await refreshLintConfig();
  });

  connection.onDidChangeConfiguration(async () => {
    if (supportsConfiguration) {
      await loadCompletionConfig(connection);
    } else {
      invalidateCompletionConfig();
    }
  });

  // A `.ttrlint.toml` change re-reads the lint config and re-lints open docs.
  // Real editors: a `.ttrlint.toml` change re-reads the lint config (the client
  // must watch `**/.ttrlint.toml`). The Designer / browser host (and tests) can
  // also trigger a reload deterministically via `modeler/reloadLintConfig`.
  connection.onDidChangeWatchedFiles(async (params) => {
    if (params.changes.some((c) => c.uri.endsWith('.ttrlint.toml'))) {
      await refreshLintConfig();
    }

    // Keep the project symbol table in sync with `.ttrm` files that change on
    // disk outside the editor (external edits, git operations, create/delete).
    // Open buffers are authoritative and driven by the document lifecycle, so
    // they're skipped here.
    let touched = false;
    for (const change of params.changes) {
      if (!change.uri.endsWith('.ttrm') || isOpen(change.uri)) continue;
      if (change.type === FileChangeType.Deleted) {
        diskDocs.delete(change.uri);
        projectSymbols.removeDocument(change.uri);
        refIndex.removeDocument(change.uri);
        sqlRefIndex.removeDocument(change.uri);
        touched = true;
      } else {
        const text = opts.readProjectFile ? await opts.readProjectFile(change.uri) : undefined;
        if (text === undefined) continue;
        diskDocs.set(change.uri, text);
        updateSymbolTable(change.uri, text);
        touched = true;
      }
    }

    if (touched) {
      packageGraph = null;
    invalidateSymbols();
      // Cross-file resolution changed; re-publish diagnostics for open docs.
      for (const doc of documents.all()) {
        publishDiagnostics(doc.uri, doc.getText());
      }
    }
  });
  connection.onRequest('modeler/reloadLintConfig', async () => {
    await refreshLintConfig();
    return { reloaded: true };
  });

  // CN-P2 S2.7 T3 — the editor's cn hygiene report (cn contracts §7) over the whole project: the open buffers
  // (authoritative) and every scanned file. The definition-level findings are `ttr cn hygiene`'s (CN-D29); the
  // markdown's last section says so. The VS Code command "TTR: Concept hygiene report" previews `markdown`.
  connection.onRequest('modeler/getCnHygiene', (): { report: CnHygieneReport; markdown: string } => {
    const { report } = cnProject();
    return { report, markdown: renderCnHygieneMarkdown(report) };
  });

  connection.onRequest('modeler/getProjectInfo', async (params: { textDocument: { uri: string } }) => {
    const allDocs = documents.all();
    const project = loadProjectFromOpenDocuments(
      allDocs.map((d) => ({ uri: d.uri })),
      params.textDocument.uri.replace(/\/[^/]+$/, ''),
      manifest
    );
    // PD1.7: surface the resolved package set with canonical (root-prefixed)
    // names plus the dependency edges, per contracts §8.7. Names are canonical
    // because the symbol table is keyed by `effectivePackage` (PD1.3).
    const pg = getPackageGraph();
    const packages = pg.nodes.map((n) => ({
      name: n.name,
      documentUris: n.documentUris,
      dependents: pg.edges.filter((e) => e.to === n.name).map((e) => e.from),
      dependencies: pg.edges.filter((e) => e.from === n.name).map((e) => e.to),
    }));

    // v3.0 (was PD3.6): list resolved subject areas (`def area`, formerly `.ttrd`
    // domain blocks) with their member counts and recursive closure sizes. Full
    // per-area detail is the resolved-packages artifact (PD4 `areas`); this is
    // the minimal Designer/CLI view.
    const areaEntries: Array<{ area: AreaDef; documentUri: string }> = [];
    const seenAreaUris = new Set<string>();
    const collectAreas = (uri: string, text: string): void => {
      if (seenAreaUris.has(uri)) return;
      seenAreaUris.add(uri);
      const doc = parseDocument(text, uri);
      if (!doc) return;
      for (const def of doc.definitions) {
        if (def.kind === 'area') areaEntries.push({ area: def, documentUri: uri });
      }
    };
    for (const d of allDocs) collectAreas(d.uri, d.getText());
    for (const [uri, text] of diskDocs) collectAreas(uri, text);
    const areaTable = new AreaTableBuilder(projectSymbols, resolver, manifest.packages.root).build(areaEntries);
    const areas: Array<{
      name: string;
      packageMemberCount: number;
      entityMemberCount: number;
      resolvedPackageCount: number;
      resolvedEntityCount: number;
    }> = [];
    const seenAreaNames = new Set<string>();
    for (const entry of areaEntries) {
      if (seenAreaNames.has(entry.area.name)) continue;
      seenAreaNames.add(entry.area.name);
      const resolved = areaTable.get(entry.area.name);
      areas.push({
        name: entry.area.name,
        packageMemberCount: entry.area.packages.length,
        entityMemberCount: entry.area.entities.length,
        resolvedPackageCount: resolved?.resolvedPackages.length ?? 0,
        resolvedEntityCount: resolved?.resolvedEntities.length ?? 0,
      });
    }

    return {
      ...project.manifest,
      root: project.root,
      ttrFileCount: project.ttrFiles.length,
      packages,
      areas,
    };
  });

  // Lets hosts without a workspace folder (the browser worker uses rootUri:null)
  // declare the project root after init. Package inference is relative to this
  // root; without it, nested files mis-infer their package and emit spurious
  // ttr/package-declaration-mismatch errors. Re-validates already-open docs so
  // it is order-independent with respect to didOpen.
  connection.onRequest('modeler/setProjectRoot', async (params: { projectRoot: string; manifest?: string }) => {
    const root = params.projectRoot.startsWith('file://')
      ? new URL(params.projectRoot).pathname
      : params.projectRoot;
    // The browser has no filesystem: the host supplies the very same TOML that
    // the Node loader reads. Desktop projects fall back to defaults on malformed TOML;
    // browser projects must still load too, with a diagnostic explaining the fallback.
    let raw: ProjectManifest | undefined;
    let invalid: unknown;
    try { raw = params.manifest === undefined ? undefined : parseManifest(params.manifest); }
    catch (e) { invalid = e; }
    let nextManifest = resolveManifest(raw, root);
    if (params.manifest === undefined && opts.loadManifest) {
      nextManifest = await opts.loadManifest(`${params.projectRoot.replace(/\/$/, '')}/modeler.toml`);
    }
    manifest = nextManifest;
    const configDiagnostics: Diagnostic[] = validateManifest(manifest).map((d) => ({
      range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
      severity: d.severity === 'error' ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning,
      code: d.code, source: 'modeler', message: d.message,
    }));
    if (params.manifest === undefined && !opts.loadManifest) {
      configDiagnostics.push({
        range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
        severity: DiagnosticSeverity.Warning, code: 'lsp/manifest-missing', source: 'modeler',
        message: 'The host did not supply modeler.toml. Default manifest settings apply; model references may not resolve.',
      });
    }
    if (invalid) {
      const error = invalid as { line?: number; column?: number; message?: string };
      const position = { line: Math.max(0, (error.line ?? 1) - 1), character: Math.max(0, (error.column ?? 1) - 1) };
      configDiagnostics.push({ range: { start: position, end: position }, severity: DiagnosticSeverity.Error,
        code: 'lsp/manifest-invalid', source: 'modeler',
        message: `modeler.toml could not be parsed: ${String(error.message ?? invalid).split(/\r?\n/)[0]}. Default manifest settings apply.`,
      });
    }
    connection.sendDiagnostics({ uri: `file://${root.replace(/\/$/, '')}/modeler.toml`, diagnostics: configDiagnostics });
    rebuildSemantics(root);
    // Seed the whole project first; open docs then override their own entries.
    await loadProjectIntoSymbols();
    for (const doc of documents.all()) {
      updateSymbolTable(doc.uri, doc.getText());
    }
    // Re-reads `.ttrlint.toml` under the new root and re-publishes all open docs.
    await refreshLintConfig();
    return { ok: true, projectRoot: root, manifest };
  });

  connection.onRequest('modeler/listSchemaSubjects', () => {
    const symbols = projectSymbols.all();
    // Legacy cnc entities use ER canonical symbol kinds, but their canvas is still the cnc model.
    const cncFiles = new Set([...getProjectDocuments()].filter(([, ast]) => ast.modelDirective?.modelCode === 'cnc').map(([uri]) => uri));
    const subjects: SchemaSubject[] = [];
    for (const schema of (['er', 'db', 'md', 'cnc'] as const satisfies readonly RenderableSchemaCode[])) {
      const members = symbols.filter((s) => (cncFiles.has(s.documentUri) ? 'cnc' : s.schemaCode) === schema && !s.parent && !['term', 'area', 'project'].includes(s.kind));
      if (members.length === 0) continue;
      subjects.push({ schema, objectCount: members.length, packageCount: new Set(members.map((s) => s.packageName ?? '')).size });
    }
    return { subjects };
  });

  connection.onRequest('modeler/getModelGraph', (params: { textDocument: { uri: string }; schema: RenderableSchemaCode }) => {
    if (params.schema !== 'db' && params.schema !== 'er' && params.schema !== 'md' && params.schema !== 'cnc') {
      return { schemaCode: params.schema, nodes: [], edges: [] };
    }

    const allDocs = documents.all();
    const asts: Document[] = [];
    const packages = new Map<Document, string>();
    for (const doc of allDocs) {
      const content = doc.getText();
      const result = parseString(content, doc.uri);
      if (result.ast) { asts.push(result.ast); packages.set(result.ast, effPkg(doc.uri, result.ast)); }
    }

    return buildProjectModelGraph(asts, params.schema, manifest.preferredLanguage, (ast) => packages.get(ast) ?? '');
  });

  connection.onRequest('modeler/listGraphs', (_params: { projectRoot: string }) => {
    const docMap = new Map<string, string>();
    for (const doc of documents.all()) docMap.set(doc.uri, doc.getText());
    const allDocs: import('@tatrman/parser').Document[] = [];
    for (const doc of documents.all()) {
      const result = parseString(doc.getText(), doc.uri);
      if (result.ast) allDocs.push(result.ast);
    }
    const qnameToDef = new Map<string, { def: import('@tatrman/parser').Definition; schemaCode: string; namespace: string }>();
    for (const ast of allDocs) {
      const schemaCode = ast.modelDirective?.modelCode ?? 'er';
      const namespace = ast.modelDirective?.schema ?? '';
      const packageName = ast.packageDecl?.name ?? '';
      for (const def of ast.definitions) {
        const qname = buildCanonicalKey({
          packageName,
          schemaId: namespace,
          kind: def.kind,
          parts: [def.name],
          declaringModel: ast.modelDirective?.modelCode ?? '',
        });
        qnameToDef.set(qname, { def, schemaCode, namespace });
      }
    }
    return { graphs: listGraphs(docMap, qnameToDef) };
  });

  connection.onRequest('modeler/getGraph', (_params: { uri: string }) => {
    const docMap = new Map<string, string>();
    for (const doc of documents.all()) docMap.set(doc.uri, doc.getText());
    return getGraph(_params.uri, docMap, manifest.preferredLanguage);
  });

  connection.onRequest('modeler/getPackageGraph', () => {
    const pkgGraph = getPackageGraph();
    return getPackageGraphFromCache(pkgGraph);
  });

  // DS-P4.S1 — the er↔db binding map (C-2), canonicalized to match er/db graph qnames. Feeds
  // the binding perspective and the er-canvas show-bindings decoration.
  connection.onRequest('modeler/getBindings', () => {
    const docMap = new Map<string, string>();
    for (const doc of documents.all()) docMap.set(doc.uri, doc.getText());
    return buildBindingMap(docMap);
  });

  connection.onRequest('modeler/getLayout', async (_params: { graphUri?: string; projectRoot?: string }): Promise<LayoutFile> => {
    if (_params.graphUri) {
      const content = documents.get(_params.graphUri)?.getText();
      if (content) {
        const result = parseString(content, _params.graphUri);
        if (result.ast?.graph?.layout) {
          const layout = result.ast.graph.layout;
          const viewport = layout.viewport ? {
            zoom: layout.viewport.zoom,
            panX: layout.viewport.panX,
            panY: layout.viewport.panY,
            displayMode: layout.viewport.displayMode as 'with-types' | 'just-names' | 'with-constraints',
          } : undefined;
          return {
            version: 1,
            viewport,
            nodes: layout.nodes ?? {},
            edges: (layout.edges ?? {}) as Record<string, { bendPoints: [number, number][] }>,
          } as LayoutFile;
        }
      }
      return emptyLayout();
    }
    if (opts.layoutStore && _params.projectRoot) {
      return opts.layoutStore.get(_params.projectRoot) ?? emptyLayout();
    }
    return emptyLayout();
  });

  connection.onRequest('modeler/setLayout', async (_params: { graphUri?: string; projectRoot?: string; layout: LayoutFile }): Promise<WorkspaceEdit> => {
    if (_params.graphUri) {
      const content = documents.get(_params.graphUri)?.getText();
      if (!content) return { documentChanges: [] };
      return buildSetLayoutEdit(content, _params.graphUri, { nodes: _params.layout.nodes, edges: _params.layout.edges, viewport: _params.layout.viewport });
    }
    if (opts.layoutStore && _params.projectRoot) {
      opts.layoutStore.set(_params.projectRoot, _params.layout);
      return { documentChanges: [] };
    }
    return { documentChanges: [] };
  });

  connection.onRequest('modeler/exportLayout', async (_params: { graphUri?: string; projectRoot?: string }): Promise<LayoutFile> => {
    if (_params.graphUri) {
      return connection.sendRequest('modeler/getLayout', { graphUri: _params.graphUri }) as Promise<LayoutFile>;
    }
    return connection.sendRequest('modeler/getLayout', { projectRoot: _params.projectRoot }) as Promise<LayoutFile>;
  });

  connection.onRequest('modeler/applyGraphEdit', (_params: unknown): { ok: false; reason: string } => {
    return { ok: false, reason: 'edit-mode-not-available-in-v1' };
  });

  connection.onRequest('modeler/addObjectToGraph', (_params: { uri: string; qname: string; autoImport: boolean }) => {
    const content = documents.get(_params.uri)?.getText();
    if (!content) return { documentChanges: [] };
    let packageToImport: string | null = null;
    if (_params.autoImport) {
      const symbol = projectSymbols.get(_params.qname);
      if (symbol?.packageName) {
        packageToImport = symbol.packageName;
      } else {
        const firstSegment = _params.qname.split('.')[0];
        const schemaCodes = ['db', 'er', 'binding', 'query', 'cnc'];
        if (!schemaCodes.includes(firstSegment)) {
          packageToImport = firstSegment;
        }
      }
    }
    return buildAddObjectEdit(content, _params.uri, _params.qname, packageToImport);
  });

  connection.onRequest('modeler/removeObjectFromGraph', (_params: { uri: string; qname: string; pruneUnusedImport: boolean }) => {
    const content = documents.get(_params.uri)?.getText();
    if (!content) return { documentChanges: [] };
    return buildRemoveObjectEdit(content, _params.uri, _params.qname, _params.pruneUnusedImport);
  });

  connection.onRequest('modeler/createGraph', (_params: { uri: string; name: string; schema: 'db' | 'er' | 'binding' | 'query' | 'cnc'; packages: string[]; objects: string[]; description?: string; tags?: string[] }) => {
    if (!_params.uri.endsWith('.ttrg')) {
      return { documentChanges: [] };
    }
    return buildCreateGraphEdit(_params);
  });

  connection.onRequest('modeler/getSymbolDetail', (params: { qname: string }) => {
    return buildSymbolDetail(params.qname, projectSymbols, resolver, refIndex, manifest, (uri) => {
      const doc = documents.get(uri);
      return doc ? doc.getText() : null;
    }, (content, uri) => parseString(content, uri));
  });

  connection.onRequest('modeler/listSymbols', (params: ListSymbolsParams = {}) => {
    const limit = params.limit ?? 500;
    const cursor = params.cursor === undefined ? null : /^(\d+):(\d+)$/.exec(params.cursor);
    const start = cursor ? Number(cursor[2]) : 0;
    if (!Number.isSafeInteger(limit) || limit < 1 || !Number.isSafeInteger(start) || start < 0 || (params.cursor !== undefined && !cursor)) {
      throw new ResponseError(ErrorCodes.InvalidParams, 'Expected a positive integer limit and a cursor returned by listSymbols.');
    }
    if (cursor && Number(cursor[1]) !== symbolGeneration) throw new ResponseError(ErrorCodes.InvalidParams, 'stale cursor');
    const kinds = params.kind ? [params.kind] : params.kinds;
    const key = JSON.stringify([symbolGeneration, kinds ? [...new Set(kinds)].sort() : null, params.package ?? null, !!params.includeMembers]);
    if (symbolPageCache?.key !== key) {
      const allowed = kinds ? new Set(kinds) : null;
      const tops = projectSymbols.all().filter((s) => (!allowed || allowed.has(s.kind)) && (params.package === undefined || (s.packageName ?? '') === params.package));
      const out: ListedSymbol[] =
        tops.map((s) => ({ qname: s.qname, kind: s.kind, name: s.name, packageName: s.packageName ?? null }));
      if (params.includeMembers) {
        // FO-A1 W2 (contracts §5): expand table/view/entity members into `kind:'member'`
        // refs carrying memberPath + parent. Reuses the same rosters as getSymbolDetail.
        for (const s of tops) {
          if (s.kind !== 'table' && s.kind !== 'view' && s.kind !== 'entity') continue;
          const detail = buildSymbolDetail(s.qname, projectSymbols, resolver, refIndex, manifest, (uri) => {
            const doc = documents.get(uri);
            return doc ? doc.getText() : null;
          }, (content, uri) => parseString(content, uri));
          if (!detail) continue;
          for (const m of memberRefsOf(detail, s.packageName ?? null)) out.push(m);
        }
      }
      symbolPageCache = { key, symbols: out };
    }
    const out = symbolPageCache.symbols;
    return { symbols: out.slice(start, start + limit), total: out.length, ...(start + limit < out.length ? { nextCursor: `${symbolGeneration}:${start + limit}` } : {}) };
  });

  connection.onDefinition((params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return null;

    const ast = parseDocument(doc.getText(), uri);
    if (!ast) return null;

    // Embedded-SQL go-to-definition (§4.2): jump from a SQL table/column ref to
    // its TTR `db` def. Ambiguous bare columns return all candidates (Location[]).
    // Inside a SQL block we always return here (never the enclosing-def fallback).
    const sqlCtx = sqlContextAt(ast, params.position);
    if (sqlCtx) {
      const locs = resolveSqlContextHit(sqlCtx).map(
        (sym): Location => ({ uri: sym.documentUri, range: sourceLocationToRange(sym.source) }),
      );
      return locs.length === 0 ? null : locs.length === 1 ? locs[0] : locs;
    }

    // Area member go-to-def (v3.0; was the `.ttrd` domain PD3 path). Area members
    // are not AST references, so resolve them off the parallel `packageSources`/
    // `entitySources` spans: a `packages:` member jumps to the files of its
    // recursive closure; an `entities:` member resolves like any cross-ref.
    {
      const line1 = params.position.line + 1;
      const ch = params.position.character;
      const within = (loc: SourceLocation): boolean =>
        line1 >= loc.line && line1 <= loc.endLine &&
        (line1 > loc.line || ch >= loc.column) && (line1 < loc.endLine || ch <= loc.endColumn);

      for (const area of ast.definitions) {
        if (area.kind !== 'area') continue;
        const pkgIdx = (area.packageSources ?? []).findIndex(within);
        if (pkgIdx >= 0) {
          const closure = areaPackageClosure(projectSymbols, area.packages[pkgIdx], manifest.packages.root);
          const uris = new Set<string>();
          for (const pkg of closure) for (const e of projectSymbols.getByPackage(pkg)) uris.add(e.documentUri);
          const locs = [...uris].map((u): Location => ({
            uri: u,
            range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
          }));
          return locs.length === 0 ? null : locs.length === 1 ? locs[0] : locs;
        }

        const entIdx = (area.entitySources ?? []).findIndex(within);
        if (entIdx >= 0) {
          const member = area.entities[entIdx];
          const res = resolver.resolveReference({ path: member, parts: member.split('.') }, { schemaCode: '', namespace: '' });
          if (res.resolved) return { uri: res.symbol.documentUri, range: sourceLocationToRange(res.symbol.source) } satisfies Location;
          return null;
        }
      }
    }

    // MD cross-references (domain/map/dimension/measure/hierarchy/grain) are not
    // generic AST `Reference`s; resolve them off their span-carrying crossRefs
    // via the MD-aware resolver (namespace insertion).
    {
      const line1 = params.position.line + 1;
      const ch = params.position.character;
      const within = (loc: SourceLocation): boolean =>
        line1 >= loc.line && line1 <= loc.endLine &&
        (line1 > loc.line || ch >= loc.column) && (line1 < loc.endLine || ch <= loc.endColumn);
      for (const def of ast.definitions) {
        for (const cr of mdCrossRefsOf(def)) {
          if (!within(cr.source)) continue;
          const sym = resolveMdRef(projectSymbols, cr.path, cr.role);
          return sym ? ({ uri: sym.documentUri, range: sourceLocationToRange(sym.source) } satisfies Location) : null;
        }
      }
    }

    const found = findNodeAtPosition(ast, params.position);

    // Inline-mapping column refs aren't in the AST walk; the cursor lands on the
    // enclosing def. Resolve them through the reference index instead.
    if (found?.kind !== 'ref') {
      const m = refIndex.findAtPosition(uri, params.position.line + 1, params.position.character);
      if (m?.viaMapping) {
        const sym = projectSymbols.get(m.targetQname);
        if (sym) return { uri: sym.documentUri, range: sourceLocationToRange(sym.source) } satisfies Location;
      }
    }
    if (!found) return null;

    const schemaCode = ast.modelDirective?.modelCode ?? 'db';
    const namespace = ast.modelDirective?.schema ?? '';

    if (found.kind === 'ref') {
      const res = resolver.resolveReference(
        found.ref,
        { schemaCode, namespace, enclosingQname: enclosingQnameOf(found.from, schemaCode, namespace, effPkg(uri, ast)), imports: ast.imports, packageName: effPkg(uri, ast) }
      );
      if (!res.resolved) return null;
      return {
        uri: res.symbol.documentUri,
        range: sourceLocationToRange(res.symbol.source),
      } satisfies Location;
    }

    // cursor on a def: return its canonical declaration location
    const qname = qnameOf(found.def, ast, found.enclosing, uri);
    const symbol = projectSymbols.get(qname);
    if (!symbol) return null;
    return {
      uri: symbol.documentUri,
      range: sourceLocationToRange(symbol.source),
    } satisfies Location;
  });

  /**
   * All references to a symbol qname: its declaration (optional), its TTR
   * reference sites, and its embedded-SQL usages (§4.3 — `db` symbols only; the
   * SQL index is empty for other qnames).
   */
  function referencesForQname(targetQname: string, includeDeclaration: boolean): Location[] {
    const locations: Location[] = [];
    if (includeDeclaration) {
      const declSymbol = projectSymbols.get(targetQname);
      if (declSymbol) {
        locations.push({ uri: declSymbol.documentUri, range: sourceLocationToRange(declSymbol.source) });
      }
    }
    for (const refLoc of refIndex.findByQname(targetQname)) {
      locations.push({ uri: refLoc.documentUri, range: sourceLocationToRange(refLoc.source) });
    }
    for (const sqlLoc of sqlRefIndex.findByQname(targetQname)) {
      locations.push({ uri: sqlLoc.uri, range: sqlLoc.range });
    }
    return locations;
  }

  connection.onReferences((params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return [];

    const ast = parseDocument(doc.getText(), uri);
    if (!ast) return [];

    // Invoked from inside a SQL block on a resolved ref → find-refs for that db
    // symbol (and never fall through to TTR node detection).
    const sqlCtx = sqlContextAt(ast, params.position);
    if (sqlCtx) {
      const syms = resolveSqlContextHit(sqlCtx);
      return syms.length === 1 ? referencesForQname(syms[0].qname, params.context?.includeDeclaration ?? true) : [];
    }

    const found = findNodeAtPosition(ast, params.position);

    // Cursor on an inline-mapping column ref: pivot to the resolved db column.
    let mappingTargetQname: string | null = null;
    if (found?.kind !== 'ref') {
      const m = refIndex.findAtPosition(uri, params.position.line + 1, params.position.character);
      if (m?.viaMapping) mappingTargetQname = m.targetQname;
    }
    if (!found && !mappingTargetQname) return [];

    const schemaCode = ast.modelDirective?.modelCode ?? 'db';
    const namespace = ast.modelDirective?.schema ?? '';

    let targetQname: string | null = mappingTargetQname;
    if (!targetQname && found?.kind === 'ref') {
      const res = resolver.resolveReference(
        found.ref,
        { schemaCode, namespace, enclosingQname: enclosingQnameOf(found.from, schemaCode, namespace, effPkg(uri, ast)), imports: ast.imports, packageName: effPkg(uri, ast) }
      );
      if (res.resolved) targetQname = res.symbol.qname;
    } else if (!targetQname && found?.kind === 'def') {
      targetQname = qnameOf(found.def, ast, found.enclosing, uri);
    }

    if (!targetQname) return [];

    return referencesForQname(targetQname, params.context?.includeDeclaration ?? true);
  });

  connection.onHover((params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return null;

    const ast = parseDocument(doc.getText(), uri);
    if (!ast) return null;

    // Embedded-SQL hover (§4.1): a cursor inside a SQL block resolves to the TTR
    // `db` table/column symbol. Inside a SQL block we never fall through to the
    // enclosing-def hover — a keyword/param/unresolved ref just yields no hover.
    const sqlCtx = sqlContextAt(ast, params.position);
    if (sqlCtx) {
      const sym = resolveSqlContextHit(sqlCtx)[0];
      if (!sym) return null;
      const { table, column } = sqlDefInfo(sym);
      const lines: string[] = [`**${sym.qname}** *(${sym.kind})*`];
      if (sqlCtx.hit?.kind === 'column' && column) {
        const type = dataTypeToString(column.type);
        const flags = [column.isKey ? 'key' : '', column.optional ? 'optional' : 'required']
          .filter(Boolean)
          .join(', ');
        if (type) lines.push(`\`${type}\`${flags ? ` — ${flags}` : ''}`);
        const desc = column.description;
        if (desc && (desc.kind === 'string' || desc.kind === 'tripleString')) lines.push(desc.value);
      } else if (sqlCtx.hit?.kind === 'table' && table) {
        const desc = table.description;
        if (desc && (desc.kind === 'string' || desc.kind === 'tripleString')) lines.push(desc.value);
        lines.push(`- **Columns:** ${(table.columns ?? []).length}`);
      }
      const base = sym.documentUri.split('/').pop() ?? sym.documentUri;
      lines.push(`- **Defined at:** ${base}:${sym.source.line}`);
      return { contents: { kind: 'markdown', value: lines.join('\n\n') } } satisfies Hover;
    }

    // MD cross-reference hover: resolve via the MD-aware resolver and show the
    // target def's qname/kind/location.
    {
      const line1 = params.position.line + 1;
      const ch = params.position.character;
      const within = (loc: SourceLocation): boolean =>
        line1 >= loc.line && line1 <= loc.endLine &&
        (line1 > loc.line || ch >= loc.column) && (line1 < loc.endLine || ch <= loc.endColumn);
      for (const def of ast.definitions) {
        for (const cr of mdCrossRefsOf(def)) {
          if (!within(cr.source)) continue;
          const sym = resolveMdRef(projectSymbols, cr.path, cr.role);
          if (!sym) return null;
          const base = sym.documentUri.split('/').pop() ?? sym.documentUri;
          const value = `**${sym.qname}** *(${sym.kind})*\n\n- **Defined at:** ${base}:${sym.source.line}`;
          return { contents: { kind: 'markdown', value } } satisfies Hover;
        }
      }
    }

    const found = findNodeAtPosition(ast, params.position);

    // Inline-mapping column ref (not reachable via the AST walk): hover the
    // resolved db column symbol.
    if (found?.kind !== 'ref') {
      const m = refIndex.findAtPosition(uri, params.position.line + 1, params.position.character);
      if (m?.viaMapping) {
        const sym = projectSymbols.get(m.targetQname);
        if (sym) {
          const base = sym.documentUri.split('/').pop() ?? sym.documentUri;
          const value = `**${sym.qname}** *(${sym.kind})*\n\n- **Defined at:** ${base}:${sym.source.line}`;
          return { contents: { kind: 'markdown', value } } satisfies Hover;
        }
      }
    }
    if (!found) return null;

    const schemaCode = ast.modelDirective?.modelCode ?? 'db';
    const namespace = ast.modelDirective?.schema ?? '';

    let qname: string | null = null;
    let def: Definition | null = null;

    if (found.kind === 'ref') {
      const res = resolver.resolveReference(
        found.ref,
        { schemaCode, namespace, enclosingQname: enclosingQnameOf(found.from, schemaCode, namespace, effPkg(uri, ast)), imports: ast.imports, packageName: effPkg(uri, ast) }
      );
      if (!res.resolved) return null;
      qname = res.symbol.qname;
    } else {
      qname = qnameOf(found.def, ast, found.enclosing, uri);
      def = found.def;
    }

    const symbol = projectSymbols.get(qname);
    if (!symbol) return null;

    const lines: string[] = [];
    lines.push(`**${symbol.qname}** *(${symbol.kind})*`);
    if (def && 'description' in def && def.description) {
      const desc = def.description;
      if (desc.kind === 'string' || desc.kind === 'tripleString') {
        lines.push(desc.value);
      }
    }
    // 0.16 — a parameter's value/label, an action's columns/sinks.
    if (def) lines.push(...automationHoverLines(def));
    // 0.17 (CN-P2 S2.7 T4) — a concept: its stereotype, label, grounding and grade (the hygiene row).
    if (symbol.kind === 'concept') {
      const { validation, report } = cnProject();
      lines.push(...conceptHoverLines(validation.concepts.get(symbol.qname), report, symbol.qname));
    }
    const fileBaseName = symbol.documentUri.split('/').pop() ?? symbol.documentUri;
    lines.push(`- **Defined at:** ${fileBaseName}:${symbol.source.line}`);

    return {
      contents: { kind: 'markdown', value: lines.join('\n\n') },
    } satisfies Hover;
  });

  /**
   * Rename a `db` symbol (§4.5): the TTR-side edit (def + references + imports +
   * .ttrg) from `buildRenameSymbolEdit`, plus a text edit for every in-SQL usage
   * from the SQL reference index. The index stores each usage's precise
   * identifier range, so a multi-part `dbo.Orders` keeps its `dbo.` qualifier and
   * a column rename never touches a same-named column on another table.
   */
  function buildSymbolRenameWithSql(targetQname: string, symbol: ReturnType<typeof projectSymbols.get>, newBareName: string, fallbackContent: string): WorkspaceEdit {
    const sym = symbol!;
    const ttrgDocs = new Map<string, string>();
    for (const d of documents.all()) ttrgDocs.set(d.uri, d.getText());
    const defContent = getDocument(sym.documentUri)?.getText() ?? fallbackContent;
    const edit = buildRenameSymbolEdit({
      oldQname: targetQname,
      newBareName,
      defEntry: { ...sym, source: defNameSource(defContent, sym) },
      defDocumentContent: defContent,
      references: refIndex.findByQname(targetQname),
      ttrgDocuments: ttrgDocs,
    });
    const changes = (edit.documentChanges ?? []) as Array<{ textDocument: { uri: string; version: number | null }; edits: Array<{ range: unknown; newText: string }> }>;
    for (const loc of sqlRefIndex.findByQname(targetQname)) {
      changes.push({ textDocument: { uri: loc.uri, version: null }, edits: [{ range: loc.range, newText: newBareName }] });
    }
    return { documentChanges: changes } as WorkspaceEdit;
  }

  connection.onPrepareRename((params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return null;

    const ast = parseDocument(doc.getText(), uri);
    if (!ast) return null;

    // Rename from inside a SQL block (§4.5.5): only offered on a ref that
    // resolves to exactly one `db` symbol — never a keyword/param/unresolved or
    // an ambiguous bare column.
    const sqlCtx = sqlContextAt(ast, params.position);
    if (sqlCtx) {
      const syms = resolveSqlContextHit(sqlCtx);
      if (syms.length !== 1 || !sqlCtx.hit) return null;
      return { range: preciseSqlNameRange(sqlCtx.block, sqlCtx.hit.ref.span, syms[0].name), placeholder: syms[0].name };
    }

    // 0.17 — on a participant label (`Store as seller`) F2 renames the label, never the event around it.
    const label = participantLabelAt(ast, uri, params.position);
    if (label) return label.symbol ? { range: sourceLocationToRange(label.range), placeholder: label.symbol.name } : null;

    const found = findNodeAtPosition(ast, params.position);

    // An inline-mapping ref (a column, an fk, a binding target) is not in the AST
    // walk: the cursor lands on the enclosing def. Rename the object the ref names,
    // through the reference index — as definition, references and hover do.
    if (found?.kind !== 'ref') {
      const m = refIndex.findAtPosition(uri, params.position.line + 1, params.position.character);
      if (m?.viaMapping) {
        const symbol = projectSymbols.get(m.targetQname);
        return symbol ? { range: sourceLocationToRange(m.source), placeholder: symbol.name } : null;
      }
    }
    if (!found || found.kind === 'ref' && !found.ref) return null;

    const schemaCode = ast.modelDirective?.modelCode ?? 'db';
    const namespace = ast.modelDirective?.schema ?? '';
    const packageName = effPkg(uri, ast);

    let qname: string | null = null;
    if (found.kind === 'def') {
      qname = qnameOf(found.def, ast, found.enclosing, uri);
    } else if (found.kind === 'ref') {
      const res = resolver.resolveReference(
        found.ref,
        { schemaCode, namespace, enclosingQname: enclosingQnameOf(found.from, schemaCode, namespace, packageName), imports: ast.imports, packageName }
      );
      if (res.resolved) qname = res.symbol.qname;
    }

    if (!qname) return null;
    const symbol = projectSymbols.get(qname);
    if (!symbol) return null;

    // The editable range is the token under the cursor: the def's name span, or
    // the reference span when renaming from a use site.
    const range = found.kind === 'def'
      ? sourceLocationToRange(defNameSource(getDocument(symbol.documentUri)?.getText() ?? doc.getText(), symbol))
      : sourceLocationToRange(found.ref.source);
    return { range, placeholder: symbol.name };
  });

  connection.onRenameRequest((params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return { documentChanges: [] };

    const ast = parseDocument(doc.getText(), uri);
    if (!ast) return { documentChanges: [] };

    // Package rename only when the cursor is on the `package` declaration line;
    // otherwise fall through to symbol rename. (Previously this fired for any
    // file that had a package declaration, so renaming an entity renamed the
    // package instead.)
    if (ast.packageDecl && params.position.line === ast.packageDecl.source.line - 1) {
      const content = doc.getText();
      const lines = content.split('\n');
      const pkgLine = ast.packageDecl.source.line - 1;
      const lineText = lines[pkgLine] ?? '';
      const nameStart = lineText.indexOf(ast.packageDecl.name);
      if (nameStart >= 0) {
        const allDocs = new Map<string, string>();
        for (const d of documents.all()) allDocs.set(d.uri, d.getText());
        return buildRenamePackageEdit({
          oldPackageName: ast.packageDecl.name,
          newPackageName: params.newName,
          allDocuments: allDocs,
        });
      }
    }

    const schemaCode = ast.modelDirective?.modelCode ?? 'db';
    const namespace = ast.modelDirective?.schema ?? '';
    const packageName = effPkg(uri, ast);

    let targetQname: string | null = null;
    let labelSymbol: SymbolEntry | undefined;
    // Rename invoked from inside a SQL usage (§4.5) — resolve the db symbol.
    const sqlCtx = sqlContextAt(ast, params.position);
    const label = sqlCtx ? undefined : participantLabelAt(ast, uri, params.position);
    if (sqlCtx) {
      const syms = resolveSqlContextHit(sqlCtx);
      if (syms.length !== 1) return { documentChanges: [] };
      targetQname = syms[0].qname;
    } else if (label) {
      // A participant label: rename the label, never the event around it (review-119 L-7(c)).
      if (!label.symbol) return { documentChanges: [] };
      labelSymbol = label.symbol;
      targetQname = label.symbol.qname;
    } else {
      const found = findNodeAtPosition(ast, params.position);
      // An inline-mapping ref: rename the object it names, not the enclosing def.
      const m = found?.kind !== 'ref'
        ? refIndex.findAtPosition(uri, params.position.line + 1, params.position.character)
        : undefined;
      if (m?.viaMapping) {
        targetQname = m.targetQname;
      } else if (!found) {
        return null;
      } else if (found.kind === 'def') {
        targetQname = qnameOf(found.def, ast, found.enclosing, uri);
      } else if (found.kind === 'ref') {
        const res = resolver.resolveReference(
          found.ref,
          { schemaCode, namespace, enclosingQname: enclosingQnameOf(found.from, schemaCode, namespace, packageName), imports: ast.imports, packageName }
        );
        if (res.resolved) targetQname = res.symbol.qname;
      }
    }

    if (!targetQname) return { documentChanges: [] };
    const symbol = labelSymbol ?? projectSymbols.get(targetQname);
    if (!symbol) return { documentChanges: [] };

    // Surface invalid / colliding renames as LSP errors (I1.5) so VS Code shows
    // a refusal dialog, instead of silently producing an empty edit.
    if (params.newName && !/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(params.newName)) {
      throw new ResponseError(ErrorCodes.InvalidParams, `'${params.newName}' is not a valid identifier.`);
    }
    const newBareName = params.newName ?? symbol.name;
    const conflictCheck = renameConflicts(symbol, newBareName);
    if (conflictCheck.length > 0) {
      throw new ResponseError(ErrorCodes.InvalidParams, `Cannot rename to '${newBareName}': a symbol with that name already exists (${conflictCheck[0].qname}).`);
    }

    // 0.17 — a participant label: its own edit (the label, the `participants:` keys that read it). A definition
    // block that reads the label is out of the editor's reach (CN-D29), so such a rename is refused (review-120 F2).
    if (symbol.kind === 'participant') {
      const read = labelReads(symbol.name)[0];
      if (read) {
        throw new ResponseError(
          ErrorCodes.InvalidParams,
          `Cannot rename label '${symbol.name}': the definition of '${read.owner}' (${read.uri.split('/').pop()}:${read.source.line}) reads it (\`${read.text}\`), and the editor does not rewrite definition blocks. Edit the definition first, then rename.`,
        );
      }
      return buildParticipantRename(symbol, newBareName);
    }

    // TTR-side edit (def + references + imports + .ttrg) plus in-SQL usages.
    const edit = buildSymbolRenameWithSql(targetQname, symbol, newBareName, doc.getText());
    if (symbol.kind === 'concept') {
      keepParticipantLabels(edit, targetQname, newBareName);
      renameConceptImports(edit, symbol, newBareName);
    }
    return edit;
  });

  /**
   * The symbols a rename to `newName` collides with. 0.17 (review-119 M-6): concepts share one namespace
   * per package and an event's labels one per event, apart from the elements' — so a concept collides only
   * with a concept of its package, a label only with a label of its event, and an element never with
   * either (`cn.Customer` and `md.Customer` never meet).
   */
  function renameConflicts(symbol: SymbolEntry, newName: string): SymbolEntry[] {
    if (symbol.kind === 'participant') {
      const sibling = symbol.parent ? projectSymbols.get(`${symbol.parent}.${newName}`) : undefined;
      return sibling && sibling.qname !== symbol.qname ? [sibling] : [];
    }
    const sameName = projectSymbols
      .findByName(newName)
      .filter((e) => e.qname !== symbol.qname && e.qname.endsWith('.' + newName));
    if (symbol.kind === 'concept') return sameName.filter((e) => e.kind === 'concept' && e.packageName === symbol.packageName);
    return sameName.filter((e) => e.kind !== 'concept' && e.kind !== 'participant');
  }

  /** Every definition block in the project (open buffers over the scanned files) that reads `label`. */
  function labelReads(label: string): Array<LabelRead & { uri: string }> {
    const texts = new Map<string, string>(diskDocs);
    for (const d of documents.all()) texts.set(d.uri, d.getText());
    const out: Array<LabelRead & { uri: string }> = [];
    for (const [uri, text] of texts) {
      if (!uri.endsWith('.ttrm')) continue;
      const ast = parseDocument(text, uri);
      if (ast) for (const r of definitionLabelReads(ast, label)) out.push({ ...r, uri });
    }
    return out;
  }

  /**
   * 0.17 (review-119 M-5) — rename an event's participant label: a written label's span, or ` as <new>`
   * after a default-labelled participant, and every binding `participants:` key that reads it. The
   * participant ref names the concept the event involves; it is never edited. A definition's
   * `this.<label>` is out of the editor's reach (CN-D29): the caller refuses the rename when one reads it.
   */
  function buildParticipantRename(symbol: SymbolEntry, newName: string): WorkspaceEdit {
    const changes = new Map<string, TextEdit[]>();
    const push = (uri: string, edit: TextEdit) => changes.set(uri, [...(changes.get(uri) ?? []), edit]);
    const text = anyDocumentText(symbol.documentUri);
    const ast = text !== undefined ? parseDocument(text, symbol.documentUri) : undefined;
    const written = ast
      ? participantsOf(ast).find(({ participant }) => participant.source.offsetStart === symbol.source.offsetStart)
      : undefined;
    if (written) {
      const at = labelSource(written.participant);
      if (at) {
        push(symbol.documentUri, { range: sourceLocationToRange(at), newText: newName });
      } else {
        const end = sourceLocationToRange(written.participant.ref.source).end;
        push(symbol.documentUri, { range: { start: end, end }, newText: ` as ${newName}` });
      }
    }
    for (const ref of refIndex.findByQname(symbol.qname)) {
      push(ref.documentUri, { range: sourceLocationToRange(ref.source), newText: newName });
    }
    return { documentChanges: [...changes].map(([uri, edits]) => ({ textDocument: { uri, version: null }, edits })) };
  }

  /**
   * 0.17 (review-119 M-5, L-7(c)) — the participant label at the cursor: a written label (`seller` in
   * `Store as seller`) and the label symbol it declares (`undefined` where none registers — a block
   * restating an event's participants). The cursor on a label is never a rename of the enclosing def.
   */
  function participantLabelAt(
    ast: Document,
    uri: string,
    position: { line: number; character: number },
  ): { range: SourceLocation; symbol?: SymbolEntry } | undefined {
    const packageName = effPkg(uri, ast);
    for (const { participant, event } of participantsOf(ast)) {
      const range = labelSource(participant);
      if (!range || !isPositionInRange(position.line + 1, position.character, range)) continue;
      const qname = `${buildCanonicalKey({ packageName, kind: 'concept', parts: [event] })}.${participant.label}`;
      const symbol = projectSymbols
        .getAll(qname)
        .find((e) => e.documentUri === uri && e.source.offsetStart === participant.source.offsetStart);
      return { range, symbol };
    }
    return undefined;
  }

  /**
   * 0.17 (review-119 M-7) — a named import of a concept is written `import <pkg>.<Name>`, not by its key
   * (`<pkg>.cn.concept.<Name>`, which the generic rename follows): rename those too, or the references the
   * rename rewrote no longer resolve through the import.
   */
  function renameConceptImports(edit: WorkspaceEdit, symbol: SymbolEntry, newName: string): void {
    if (!symbol.packageName) return;
    const oldTarget = `${symbol.packageName}.${symbol.name}`;
    const changes = (edit.documentChanges ?? []) as TextDocumentEdit[];
    for (const d of documents.all()) {
      if (!d.uri.endsWith('.ttrm')) continue;
      const content = d.getText();
      const importRe = new RegExp(`(^|\\n)(\\s*import\\s+)(${escapeRegExp(oldTarget)})(?=\\s|$)`, 'g');
      let m: RegExpExecArray | null;
      while ((m = importRe.exec(content)) !== null) {
        const at = m.index + m[1].length + m[2].length;
        const range = { start: d.positionAt(at), end: d.positionAt(at + oldTarget.length) };
        changes.push({ textDocument: { uri: d.uri, version: null }, edits: [{ range, newText: `${symbol.packageName}.${newName}` }] });
      }
    }
    edit.documentChanges = changes;
  }

  /**
   * 0.17 (CN-P2 S2.7 T4) — a concept an event `involves:` unlabelled names its participant by the default label,
   * its name lower-cased; a binding's `participants:` key and a definition's `this.<label>` read that label, and a
   * definition block is out of the editor's reach (CN-D29). So a rename keeps the label, written out:
   * `involves: [Customer]` → `involves: [Buyer as customer]`.
   */
  function keepParticipantLabels(edit: WorkspaceEdit, conceptKey: string, newBareName: string): void {
    const changes = (edit.documentChanges ?? []).filter((c): c is TextDocumentEdit => 'edits' in c);
    for (const c of cnProject().validation.concepts.values()) {
      const involves = c.properties.find((s) => s.property.kind === 'involves');
      if (involves?.property.kind !== 'involves') continue;
      const doc = involves.doc;
      for (const p of involves.property.participants) {
        if (p.label !== undefined) continue;
        const label = p.ref.parts[p.ref.parts.length - 1].toLowerCase();
        if (label === newBareName.toLowerCase()) continue;
        const r = resolver.resolveReference(
          { ...p.ref, cnContext: true },
          { schemaCode: doc.schemaCode, namespace: doc.namespace, imports: doc.ast.imports, packageName: doc.packageName },
        );
        if (!r.resolved || r.symbol.qname !== conceptKey) continue;
        const at = sourceLocationToRange(p.ref.source).start;
        for (const change of changes) {
          if (change.textDocument.uri !== doc.uri) continue;
          for (const e of change.edits) {
            if (e.range.start.line === at.line && e.range.start.character === at.character) e.newText += ` as ${label}`;
          }
        }
      }
    }
  }

  connection.onWorkspaceSymbol((params) => {
    const query = params.query ?? '';
    const allSymbols = projectSymbols.all();

    if (!query) {
      return allSymbols.slice(0, 100).map((symbol) => ({
        name: symbol.qname,
        kind: symbolKindOf(symbol.kind),
        location: {
          uri: symbol.documentUri,
          range: sourceLocationToRange(symbol.source),
        },
      })) satisfies SymbolInformation[];
    }

    const scored = fuzzysort.go(query, allSymbols, {
      keys: ['qname', 'name'],
      limit: 100,
    });

    const queryLower = query.toLowerCase();

    // H3.4: per-package query mode. If query ends with '.', treat it as a
    // package-prefix filter (e.g. "billing." → all symbols in billing.*).
    // Match prefix + '.' so "billing." doesn't also match "billingsystem.*".
    if (query.endsWith('.')) {
      const prefix = query.slice(0, -1).toLowerCase();
      const packageFiltered = allSymbols.filter((s) => {
        const qnameLower = s.qname.toLowerCase();
        return qnameLower.startsWith(prefix + '.');
      });
      return packageFiltered.slice(0, 100).map((symbol) => ({
        name: symbol.qname,
        kind: symbolKindOf(symbol.kind),
        location: {
          uri: symbol.documentUri,
          range: sourceLocationToRange(symbol.source),
        },
      })) satisfies SymbolInformation[];
    }

    // Kind-name boost: when the query is a prefix of a definition kind (e.g.
    // "rel" -> "relation"), float every symbol of that kind, drawn from the
    // *full* index, above the fuzzy matches. fuzzysort only searches qname and
    // name, so a kind-name query would otherwise be drowned out: "rel" matches
    // the 111 `er2dbRelation` qnames and saturates the limit before any
    // `relation`-kind def (whose qname is `er.entity.<name>`, no "rel"
    // substring) is ever reached. Gated at 3+ chars so short name-fragment
    // queries aren't hijacked by an accidental kind prefix.
    const isKindQuery = (kind: string): boolean => {
      const k = kind.toLowerCase();
      return k === queryLower || k.startsWith(queryLower);
    };
    const kindMatched =
      query.length >= 3 ? allSymbols.filter((s) => isKindQuery(s.kind)) : [];
    const seen = new Set(kindMatched.map((s) => s.qname));
    const results =
      kindMatched.length > 0
        ? [...kindMatched, ...scored.map((e) => e.obj).filter((s) => !seen.has(s.qname))].slice(0, 100)
        : scored.map((e) => e.obj).slice(0, 100);

    return results.map((symbol) => ({
      name: symbol.qname,
      kind: symbolKindOf(symbol.kind),
      location: {
        uri: symbol.documentUri,
        range: sourceLocationToRange(symbol.source),
      },
    })) satisfies SymbolInformation[];
  });

  connection.onDocumentSymbol((params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return [];

    const content = doc.getText();
    const result = parseString(content, uri);
    if (!result.ast) return [];

    return buildDocumentSymbols(result.ast, content.split('\n'));
  });

  connection.onCodeLens((params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return [];
    const ast = parseDocument(doc.getText(), uri);
    if (!ast) return [];
    return getCodeLenses({ ast, refIndex, projectSymbols });
  });

  connection.onDocumentFormatting(async (params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return [];
    const content = doc.getText();
    const result = parseString(content, uri);
    if (!result.ast || result.errors.some((e) => e.severity === 'error')) {
      // Don't reformat a file that doesn't parse — we'd risk dropping content.
      return [];
    }

    const config = await loadFormatConfig();
    const formatted = formatDocument(result.ast, content, config);
    if (formatted === content) return [];

    const lines = content.split('\n');
    const endLine = lines.length - 1;
    return [{
      range: { start: { line: 0, character: 0 }, end: { line: endLine, character: lines[endLine].length } },
      newText: formatted,
    }];
  });

  connection.onCodeAction(async (params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return [];
    const content = doc.getText();
    const ast = parseDocument(content, uri);
    if (!ast) return [];

    const actions: CodeAction[] = [];

    // Re-lint to obtain LintDiagnostics carrying each fix's `data` (the editor's
    // round-tripped diagnostics don't preserve it), then offer a CodeAction for
    // every fixable diagnostic that matches one in the request context.
    const fixCtx: DocumentRuleContext = {
      scope: 'document', uri, ast, text: content,
      refs: collectAllReferences(ast),
      manifest, symbols: projectSymbols, resolver,
      report: () => {},
    };
    const lintDiags = lintDocument(uri, ast, { manifest, symbols: projectSymbols, resolver }, lintConfig());
    for (const cd of params.context.diagnostics) {
      const ld = lintDiags.find(
        (d) => d.code === cd.code && d.source.line - 1 === cd.range.start.line && d.source.column === cd.range.start.character
      ) ?? lintDiags.find((d) => d.code === cd.code && d.source.line - 1 === cd.range.start.line);
      if (!ld) continue;
      const rule = ruleForCode(ld.code as DiagnosticCode);
      if (!rule?.fix) continue;
      const edit = rule.fix.build(fixCtx, ld);
      if (!edit.documentChanges || edit.documentChanges.length === 0) continue;
      actions.push({
        title: rule.fix.title,
        kind: rule.fix.kind === 'safe' ? CodeActionKind.QuickFix : CodeActionKind.RefactorRewrite,
        diagnostics: [cd],
        isPreferred: rule.fix.kind === 'safe',
        edit,
      });
    }

    // Refactor: extract the top-level def under the cursor into its own file.
    const atCursor = findNodeAtPosition(ast, params.range.start);
    if (atCursor && atCursor.kind === 'def' && !atCursor.enclosing) {
      const a = refactorExtractDefToNewFile(uri, content, atCursor.def, ast, await loadFormatConfig());
      if (a) actions.push(a);
    }

    return actions;
  });

  async function loadFormatConfig(): Promise<FormatConfig> {
    if (!supportsConfiguration) return DEFAULT_FORMAT_CONFIG;
    try {
      const cfg = await connection.sendRequest('workspace/configuration', {
        items: [{ section: 'modeler.format' }],
      }) as Array<Record<string, unknown> | null>;
      const m = cfg[0] ?? {};
      const sep = m['separator'];
      return {
        separator: sep === 'newline' || sep === 'comma' || sep === 'preserve' ? sep : DEFAULT_FORMAT_CONFIG.separator,
        alignKeys: typeof m['alignKeys'] === 'boolean' ? m['alignKeys'] : DEFAULT_FORMAT_CONFIG.alignKeys,
        indentSpaces: typeof m['indentSpaces'] === 'number' ? m['indentSpaces'] : DEFAULT_FORMAT_CONFIG.indentSpaces,
        width: typeof m['width'] === 'number' ? m['width'] : DEFAULT_FORMAT_CONFIG.width,
      };
    } catch {
      return DEFAULT_FORMAT_CONFIG;
    }
  }

  /**
   * Emit one `class`-typed `declaration`-modified semantic token per
   * definition name. The token's start is computed by scanning the def's
   * opening line for the name immediately after the def-kind keyword.
   */
  // SQL semantic type → legend index (see the initialize legend). class(2)/
  // property(3) are produced by the Phase 3 resolver, not the lexer.
  const SQL_SEM_TO_LEGEND: Record<SqlSemanticType, number> = {
    keyword: 7,
    string: 4,
    number: 5,
    comment: 6,
    variable: 8,
    operator: 13,
  };
  const LEGEND_PARAMETER = 14;

  /** Flat char offset within a SQL `value` → ANTLR-style (1-based line, 0-based col). */
  function offsetToLineCol(text: string, offset: number): { line: number; column: number } {
    let line = 1;
    let column = 0;
    const end = Math.min(offset, text.length);
    for (let i = 0; i < end; i++) {
      if (text[i] === '\n') {
        line++;
        column = 0;
      } else {
        column++;
      }
    }
    return { line, column };
  }

  connection.onRequest('textDocument/semanticTokens/full', (params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return { data: [] };

    const content = doc.getText();
    const result = parseString(content, uri);
    if (!result.ast) return { data: [] };

    const ast = result.ast;
    const lines = content.split('\n');

    // Token type indices into the legend (see initialize). 2=class, 9=packageName,
    // 10=importedSymbol, 11=localSymbol, 12=unimportedReference.
    interface Tok { line: number; char: number; len: number; type: number; mod: number }
    const toks: Tok[] = [];

    function emitForDef(def: Definition): void {
      const lineIndex = def.source.line - 1; // 0-based
      const lineText = lines[lineIndex] ?? '';
      const nameStart = locateName(lineText, def.name, def.source.column);
      if (nameStart >= 0) toks.push({ line: lineIndex, char: nameStart, len: def.name.length, type: 2, mod: 1 });
      for (const child of nestedDefs(def)) emitForDef(child);
    }
    for (const def of ast.definitions) emitForDef(def);

    function emitSqlTokens(block: TaggedBlockValue, out: Tok[]): void {
      // Lexer-first + best-effort: never let a malformed SQL block break the
      // whole semantic-tokens response.
      let lexed;
      try {
        lexed = lexSql(block.value, resolveDialect(block));
      } catch {
        return;
      }
      const { tokens, masked } = lexed;
      // A placeholder's masked inner text lexes as a bare identifier (or, rarely,
      // a keyword); recolour the whole `{name}` span as `parameter` and suppress
      // any lexer token that falls inside it, so the two never overlap.
      const inPlaceholder = (offset: number): boolean =>
        masked.placeholders.some((p) => offset >= p.offset && offset < p.offset + p.length);

      for (const t of tokens) {
        if (inPlaceholder(t.span.offset)) continue;
        const sem = classifyToken(t.typeName, t.literalName);
        if (!sem) continue;
        const pos = sqlPosToFile({ line: t.span.line, column: t.span.column }, block);
        out.push({ line: pos.line - 1, char: pos.column, len: t.span.length, type: SQL_SEM_TO_LEGEND[sem], mod: 0 });
      }
      for (const p of masked.placeholders) {
        const pos = sqlPosToFile(offsetToLineCol(block.value, p.offset), block);
        out.push({ line: pos.line - 1, char: pos.column, len: p.length, type: LEGEND_PARAMETER, mod: 0 });
      }
    }

    // package declaration qname → packageName.
    if (ast.packageDecl) {
      const pd = ast.packageDecl;
      toks.push({ line: pd.source.line - 1, char: pd.source.column, len: pd.name.length, type: 9, mod: 0 });
    }

    // References → localSymbol (same package) / importedSymbol (package imported) /
    // unimportedReference (resolved via package search, not imported).
    const currentPkg = ast.packageDecl?.name ?? '';
    const imports = ast.imports ?? [];
    for (const refLoc of refIndex.getForDocument(uri)) {
      const target = projectSymbols.get(refLoc.targetQname);
      const targetPkg = target?.packageName ?? '';
      // 0.17 — a concept (or a participant label under it) is imported by package and name
      // (`import hartland.core.Customer`), not by its key (review-120 F13).
      const namedTarget =
        target?.schemaCode === 'cn' && targetPkg
          ? `${targetPkg}.${refLoc.targetQname.slice(`${targetPkg}.cn.concept.`.length).split('.')[0]}`
          : refLoc.targetQname;
      let type: number;
      if (targetPkg === currentPkg) {
        type = 11; // localSymbol
      } else {
        // Imported if a named import targets this exact symbol, or a wildcard
        // import targets its package.
        const imported = imports.some((imp) =>
          (!imp.wildcard && imp.target === namedTarget) || (imp.wildcard && imp.target === targetPkg));
        type = imported ? 10 : 12; // importedSymbol : unimportedReference
      }
      const s = refLoc.source;
      const len = s.endLine === s.line ? s.endColumn - s.column : (lines[s.line - 1]?.length ?? 0) - s.column;
      if (len > 0) toks.push({ line: s.line - 1, char: s.column, len, type, mod: 0 });
    }

    // Embedded SQL: lex each tagged SQL block, classify, and map every token
    // back to its file position (§8). Comments/params/keywords colour inside the
    // `"""sql … """` body alongside the TTR tokens. Only SQL-language blocks are
    // lexed (transform/dataframe/etc. and untagged triple-strings are skipped).
    for (const def of ast.definitions) {
      const block =
        def.kind === 'query' ? def.sourceText : def.kind === 'view' ? def.definitionSql : undefined;
      if (block?.kind === 'taggedBlock' && block.language === 'SQL') {
        emitSqlTokens(block, toks);
      }
    }

    // SemanticTokensBuilder requires tokens in (line, char) order.
    toks.sort((a, b) => a.line - b.line || a.char - b.char);
    const builder = new SemanticTokensBuilder();
    for (const t of toks) builder.push(t.line, t.char, t.len, t.type, t.mod);
    return builder.build();
  });

  connection.onCompletion(async (params) => {
    const uri = params.textDocument.uri;
    const doc = getDocument(uri);
    if (!doc) return { isIncomplete: false, items: [] };

    const content = doc.getText();
    const result = parseString(content, uri);
    if (!result.ast) return { isIncomplete: false, items: [] };

    // Embedded-SQL completion (§4.4): a cursor inside a SQL block offers tables
    // (after FROM/JOIN) or in-scope columns (after SELECT/WHERE/alias.). Desktop
    // only; short-circuits the TTR completion contexts below.
    if (opts.analyzeSqlBlock) {
      for (const def of result.ast.definitions) {
        const block = sqlBlockOf(def);
        if (!block) continue;
        const offset = fileToSqlOffset(block, params.position.line + 1, params.position.character);
        if (offset === undefined) continue;
        const dialect = resolveDialect(block, sqlConfig);
        return { isIncomplete: false, items: sqlCompletionItems(block, dialect, offset) };
      }
    }

    // MD `calc:` completion — list the built-in calc-map catalog names.
    {
      const lineText = content.split('\n')[params.position.line] ?? '';
      const before = lineText.slice(0, params.position.character);
      const m = /\bcalc\s*:\s*([A-Za-z0-9_]*)$/.exec(before);
      if (m) {
        const partial = m[1].toLowerCase();
        const items: CompletionItem[] = calcNames()
          .filter((n) => n.toLowerCase().startsWith(partial))
          .map((n) => ({ label: n, kind: CompletionItemKind.Function, detail: 'calc map' }));
        return { isIncomplete: false, items };
      }
    }

    // MD `domain:` completion — list the project's `def domain`s as `md.<name>`.
    {
      const lineText = content.split('\n')[params.position.line] ?? '';
      const before = lineText.slice(0, params.position.character);
      const m = /\bdomain\s*:\s*([\w.]*)$/.exec(before);
      if (m && result.ast.modelDirective?.modelCode === 'md') {
        const partial = m[1].toLowerCase();
        const items: CompletionItem[] = projectSymbols
          .all()
          .filter((e) => e.kind === 'mdDomain')
          .map((e) => `md.${e.name}`)
          .filter((label) => label.toLowerCase().startsWith(partial))
          .map((label) => ({ label, kind: CompletionItemKind.Class, detail: 'domain' }));
        if (items.length > 0) return { isIncomplete: false, items };
      }
    }

    const context = detectCompletionContext({
      position: params.position,
      content,
      doc: result.ast,
    });

    if (context === 'reference') {
      const query = extractQueryPrefix(content, params.position);

      const config = getCompletionConfig();
      const autoImport = opts.completionAutoImport ?? config.autoImport;

      const completions = getReferenceCompletions({
        position: params.position,
        content,
        doc: result.ast,
        projectSymbols,
        autoImport,
        query,
      });

      return completions ?? { isIncomplete: false, items: [] };
    }

    if (context === 'property') {
      return getPropertyNameCompletions({
        position: params.position,
        content,
        doc: result.ast,
      }) ?? { isIncomplete: false, items: [] };
    }

    if (context === 'modelCode') {
      return getModelCodeCompletions({
        position: params.position,
        content,
        doc: result.ast,
      }) ?? { isIncomplete: false, items: [] };
    }

    if (context === 'schemaHandle') {
      // The named schema handles from the manifest (`[schemas.*]` + default).
      const schemaHandles = [
        ...new Set([
          ...Object.keys(manifest.schemas),
          ...(manifest.defaults.schema ? [manifest.defaults.schema] : []),
        ]),
      ];
      return getSchemaHandleCompletions({
        position: params.position,
        content,
        doc: result.ast,
        schemaHandles,
      }) ?? { isIncomplete: false, items: [] };
    }

    if (context === 'defKind') {
      return getDefKindCompletions({
        position: params.position,
        content,
        doc: result.ast,
      }) ?? { isIncomplete: false, items: [] };
    }

    if (context === 'packageName') {
      const projectPackages = projectSymbols.listPackages();
      const projectRoot = manifest.projectRoot ?? '';
      return getPackageNameCompletions({
        position: params.position,
        content,
        doc: result.ast,
        projectPackages,
        documentUri: uri,
        projectRoot,
        projectSymbols,
      }) ?? { isIncomplete: false, items: [] };
    }

    return { isIncomplete: false, items: [] };
  });

  connection.onExit(() => {
    // nothing cleanup needed
  });

  documents.listen(connection);
}

/**
 * Find the column where `name` appears after `startCol` on the line. Returns
 * -1 if the name isn't found (e.g. on a continuation line where the def
 * keyword and name are on different lines).
 */
function locateName(lineText: string, name: string, startCol: number): number {
  // Search forward from startCol for the first occurrence of `name` as a
  // whole word. For inline defs (`def attribute id { ... }`), `name` is
  // usually a few tokens to the right of the def's first column.
  const idx = lineText.indexOf(name, startCol);
  if (idx < 0) return -1;
  // Sanity: prefer the name when preceded by whitespace or the def-kind word.
  const before = idx === 0 ? '' : lineText[idx - 1];
  if (before && /[A-Za-z0-9_]/.test(before)) {
    // adjacent to an identifier — try a later occurrence
    return lineText.indexOf(name, idx + name.length);
  }
  return idx;
}
