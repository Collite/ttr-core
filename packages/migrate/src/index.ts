// SPDX-License-Identifier: Apache-2.0
import { readFile } from 'node:fs/promises';
import { join, relative, dirname } from 'node:path';
import type { Document } from '@tatrman/parser';
import { parseString } from '@tatrman/parser';
import { collectAllReferences, DocumentSymbolTable } from '@tatrman/semantics';

export {
  resolvePackages,
  buildArtifactFromFiles,
  serializeArtifact,
  basename,
} from './resolve-packages.js';
export type {
  ResolvedPackagesArtifact,
  ResolvedPackage,
  ResolvedEntity,
  ResolvedArtifactArea,
  ModelFile,
} from './resolve-packages.js';
export { migratePhase0, runPhase0 } from './phase0.js';
export type { Phase0File, Phase0Result } from './phase0.js';
export { computeKeyMap, newKeyForEntry, legacyKeyForEntry, rewriteCanonicalKeys } from './qname-migrate.js';
export { planQnameMigration, runQnameMigration } from './qname-migrate-driver.js';
export type { QnameMigrationPlan, QnameMigrationWrite } from './qname-migrate-driver.js';
export { rewriteV4Keywords, rewriteV4References } from './keyword-rewrite.js';
export { migrateLexicon } from './lexicon-migrate.js';
export { liftManifest } from './manifest-lift.js';
export type { ManifestLiftResult, SchemaBindingLift } from './manifest-lift.js';
export { unifiedDiff } from './text-diff.js';
export { planTtrlExtraction, isSkip, sidecarPathFor } from './ttrl-migrate.js';
export type { TtrlMigrationResult, TtrlMigrationSkip, TtrlMigrationSkipReason } from './ttrl-migrate.js';
export { planTtrlMigration, runTtrlMigration } from './ttrl-migrate-driver.js';
export type { TtrlMigrationPlan } from './ttrl-migrate-driver.js';

export interface MigrateArgs {
  projectRoot: string;
  dryRun: boolean;
  commitTtrlRemoval: boolean;
  verbose: boolean;
  wildcardThreshold: number;
}

export interface MigrateReport {
  filesTouched: string[];
  packagesCreated: string[];
  importsInserted: { uri: string; package: string; isWildcard: boolean; schema: string; namespace: string; defName: string }[];
  ttrgFilesCreated: string[];
  ttrlRemoved: string | null;
  ambiguousReferences: { uri: string; line: number; ref: string; candidates: string[] }[];
}

export interface WriteOp {
  path: string;
  content: string;
}

export function normaliseSep(p: string): string {
  return p.replace(/\\/g, '/');
}

export function inferPackage(filePath: string, projectRoot: string): string {
  const rel = normaliseSep(relative(projectRoot, filePath));
  const dir = dirname(rel);
  if (dir === '.' || dir === '') return '';
  return dir.replace(/\//g, '.');
}

function leadingCommentLines(content: string): string[] {
  const lines = content.split('\n');
  const commentLines: string[] = [];
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('//')) {
      commentLines.push(line);
    } else {
      break;
    }
  }
  if (commentLines.length === 0) return commentLines;
  let trailingBlankCount = 0;
  for (let i = commentLines.length; i < lines.length; i++) {
    if (lines[i].trim() === '') {
      trailingBlankCount++;
    } else {
      break;
    }
  }
  for (let i = 0; i < trailingBlankCount; i++) {
    commentLines.push('');
  }
  return commentLines;
}

export function insertPackageDecl(content: string, packageName: string): string {
  if (/^package\s/m.test(content)) return content;
  if (packageName === '') return content;
  const commentLines = leadingCommentLines(content);
  if (commentLines.length > 0) {
    const commentBlock = commentLines.join('\n') + '\n';
    const afterComments = content.slice(commentBlock.length);
    return commentBlock + `package ${packageName}\n` + afterComments;
  }
  return `package ${packageName}\n` + content;
}

export interface ImportSpec {
  packageName: string;
  schema: string;
  namespace: string;
  defName: string;
  isWildcard: boolean;
}

export function scanCrossReferences(
  ast: Document,
  fromPackage: string,
  projectSymbols: { qname: string; packageName: string; schemaCode: string }[],
  wildcardThreshold: number
): { specs: ImportSpec[]; ambiguous: { line: number; ref: string; candidates: string[] }[] } {
  const ambiguous: { line: number; ref: string; candidates: string[] }[] = [];

  // Group import-target qnames by their (real) package. `projectSymbols` carries
  // only top-level defs (children are filtered upstream), and each entry's qname
  // is unqualified — `<schema>.<namespace>.<def>` — with the package held
  // separately in `packageName`. The package therefore comes from this key, never
  // from the qname string.
  const byPackage = new Map<string, string[]>();
  for (const entry of projectSymbols) {
    let list = byPackage.get(entry.packageName);
    if (!list) {
      list = [];
      byPackage.set(entry.packageName, list);
    }
    list.push(entry.qname);
  }

  const refsByPackage = new Map<string, Set<string>>();

  for (const { ref } of collectAllReferences(ast)) {
    const refStr = ref.path;
    const matches: { pkg: string; qname: string }[] = [];
    for (const [pkg, qnames] of byPackage) {
      for (const qname of qnames) {
        if (qname.endsWith('.' + refStr) || qname === refStr) {
          matches.push({ pkg, qname });
        }
      }
    }
    if (matches.length === 0) continue;
    const distinctPkgs = new Set(matches.map(m => m.pkg));
    if (distinctPkgs.size > 1) {
      // Same bare name exported by 2+ packages — can't auto-decide.
      const candidates = [...new Set(matches.map(m => `${m.pkg}.${m.qname}`))].sort();
      ambiguous.push({ line: ref.source.line, ref: refStr, candidates });
      continue;
    }
    const { pkg } = matches[0];
    if (pkg === fromPackage) continue;
    let set = refsByPackage.get(pkg);
    if (!set) {
      set = new Set<string>();
      refsByPackage.set(pkg, set);
    }
    for (const m of matches) set.add(m.qname);
  }

  const specs: ImportSpec[] = [];
  for (const [pkg, qnameSet] of refsByPackage) {
    const distinctQnames = [...qnameSet];
    if (distinctQnames.length >= wildcardThreshold) {
      specs.push({ packageName: pkg, schema: '', namespace: '', defName: '', isWildcard: true });
    } else {
      for (const qname of distinctQnames) {
        // Unqualified qname: <schema>.<namespace>.<def>.
        const parts = qname.split('.');
        specs.push({
          packageName: pkg,
          schema: parts[0] ?? '',
          namespace: parts[1] ?? '',
          defName: parts[2] ?? '',
          isWildcard: false,
        });
      }
    }
  }

  return { specs, ambiguous };
}

export function insertImports(content: string, specs: ImportSpec[]): string {
  if (specs.length === 0) return content;
  const existingImports = new Set<string>();
  for (const line of content.split('\n')) {
    const trimmed = line.trim();
    if (trimmed.startsWith('import ')) existingImports.add(trimmed);
  }
  const byPackage = new Map<string, ImportSpec[]>();
  for (const spec of specs) {
    let list = byPackage.get(spec.packageName);
    if (!list) {
      list = [];
      byPackage.set(spec.packageName, list);
    }
    list.push(spec);
  }
  const importLines: string[] = [];
  for (const [pkg, specs2] of byPackage) {
    const wildcard = specs2.some(s => s.isWildcard);
    const named = specs2.filter(s => !s.isWildcard);
    if (wildcard) {
      const line = `import ${pkg}.*`;
      if (!existingImports.has(line)) importLines.push(line);
    }
    for (const s of named) {
      const line = `import ${s.packageName}.${s.schema}.${s.namespace}.${s.defName}`;
      if (!existingImports.has(line)) importLines.push(line);
    }
  }
  importLines.sort();
  if (importLines.length === 0) return content;
  const importBlock = importLines.join('\n') + '\n';
  const schemaIdx = content.indexOf('model '); // v4.0 — the model directive line
  if (schemaIdx === -1) return content;
  const before = content.slice(0, schemaIdx);
  const after = content.slice(schemaIdx);
  const commentPattern = /^((\/\/[^\n]*\n)*)/;
  const match = before.match(commentPattern);
  const leadingComments = match ? match[1] : '';
  const rest = before.slice(leadingComments.length);
  return leadingComments + rest + importBlock + after;
}

export async function convertTtrlToTtrg(
  ttrlPath: string,
  projectRoot: string,
  projectSymbols: { qname: string; schemaCode: string }[]
): Promise<WriteOp[]> {
  const ops: WriteOp[] = [];
  const ttrlContent = await readFile(ttrlPath, 'utf-8');
  let layout: { viewports?: Record<string, { zoom: number; panX: number; panY: number; displayMode: string }>; nodes?: Record<string, { x: number; y: number }> } = {};
  try {
    const inner = ttrlContent.slice(ttrlContent.indexOf('{'));
    layout = JSON.parse(inner);
  } catch {
    return ops;
  }
  const rawViewports = layout.viewports ?? {};
  const schemas = new Set<string>();
  for (const key of Object.keys(rawViewports)) {
    if (key === 'db' || key === 'er' || key === 'binding' || key === 'cnc') schemas.add(key);
  }
  if (schemas.size === 0) schemas.add('er');
  const graphsDir = join(projectRoot, 'graphs');
  for (const schema of schemas) {
    const vp = rawViewports[schema] ?? { zoom: 1.0, panX: 0, panY: 0, displayMode: 'just-names' };
    const nodes: Record<string, { x: number; y: number }> = {};
    const nodePositions = layout.nodes ?? {};
    for (const [qname, pos] of Object.entries(nodePositions)) {
      if (qname.startsWith(`${schema}.`)) {
        nodes[qname] = pos as { x: number; y: number };
      }
    }
    const allOfSchema = projectSymbols.filter(s => s.schemaCode === schema);
    const objects = allOfSchema.map(s => s.qname);
    const { serializeLayoutBlock } = await import('@tatrman/edit');
    const layoutBlockStr = serializeLayoutBlock({ viewport: vp, nodes, edges: {} });
    const ttrgContent = `// Generated by modeler migrate-to-packages; rename/split as desired.\ngraph _all_${schema} {\n  model: ${schema},\n  objects: [\n${objects.map(o => `    ${o}`).join(',\n')}\n  ],\n  ${layoutBlockStr}\n}\n`;
    ops.push({ path: join(graphsDir, `_all_${schema}.ttrg`), content: ttrgContent });
  }
  return ops;
}

export async function runMigration(args: MigrateArgs): Promise<{ report: MigrateReport; writes: WriteOp[] }> {
  const fs = await import('node:fs/promises');
  const { readFile: rf, readdir, mkdir, writeFile, unlink, stat } = fs;

  const report: MigrateReport = {
    filesTouched: [],
    packagesCreated: [],
    importsInserted: [],
    ttrgFilesCreated: [],
    ttrlRemoved: null,
    ambiguousReferences: [],
  };

  const projectRoot = args.projectRoot.replace(/\/$/, '') + '/';

  async function walkDir(dir: string): Promise<string[]> {
    const entries = await readdir(dir, { withFileTypes: true });
    const results: string[] = [];
    for (const entry of entries) {
      if (entry.name === '.modeler' || entry.name === 'node_modules' || entry.name === '.git') continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        results.push(...(await walkDir(full)));
      } else if (entry.isFile() && entry.name.endsWith('.ttrm')) {
        results.push(full);
      }
    }
    return results;
  }

  const ttrFiles = await walkDir(projectRoot);
  const ttrlPath = join(projectRoot, '.modeler', 'layout.ttrl');
  let ttrlExists = false;
  try {
    await stat(ttrlPath);
    ttrlExists = true;
  } catch {
    ttrlExists = false;
  }

  const allSymbols: { qname: string; packageName: string; schemaCode: string }[] = [];
  const fileDocs = new Map<string, Document>();

  for (const file of ttrFiles) {
    const content = await rf(file, 'utf-8');
    const result = parseString(content, file);
    if (result.ast) {
      fileDocs.set(file, result.ast);
      const pkg = inferPackage(file, projectRoot);
      // The raw directive ('' when absent): the symbol table derives each def's model from its kind, and
      // the directive moves only a query declared under `model er` (a logical query, LQ L-1).
      const schemaCode = result.ast.modelDirective?.modelCode ?? '';
      const ns = result.ast.modelDirective?.schema ?? '';
      const symbolTable = new DocumentSymbolTable(file, result.ast, schemaCode, ns);
      for (const entry of symbolTable.all()) {
        // Only top-level objects are graph objects / import targets — skip
        // children (attributes, columns), which carry a `parent`.
        if (entry.parent) continue;
        allSymbols.push({ qname: entry.qname, packageName: pkg, schemaCode: entry.schemaCode });
      }
    }
  }

  const writes: WriteOp[] = [];

  for (const file of ttrFiles) {
    const content = await rf(file, 'utf-8');
    const ast = fileDocs.get(file);
    if (!ast) continue;
    const pkg = inferPackage(file, projectRoot);
    let newContent = insertPackageDecl(content, pkg);
    if (pkg && !report.packagesCreated.includes(pkg)) {
      report.packagesCreated.push(pkg);
    }
    const { specs, ambiguous } = scanCrossReferences(ast, pkg, allSymbols, args.wildcardThreshold);
    for (const amb of ambiguous) {
      report.ambiguousReferences.push({ uri: file, ...amb });
    }
    newContent = insertImports(newContent, specs);
    for (const spec of specs) {
      report.importsInserted.push({ uri: file, package: spec.packageName, isWildcard: spec.isWildcard, schema: spec.schema, namespace: spec.namespace, defName: spec.defName });
    }
    if (newContent !== content) {
      writes.push({ path: file, content: newContent });
      report.filesTouched.push(file);
    }
  }

  if (ttrlExists) {
    const ttrgOps = await convertTtrlToTtrg(ttrlPath, projectRoot, allSymbols);
    for (const op of ttrgOps) {
      writes.push(op);
      report.ttrgFilesCreated.push(op.path);
    }
    if (args.commitTtrlRemoval) {
      try {
        await unlink(ttrlPath);
        report.ttrlRemoved = ttrlPath;
      } catch {
        // ignore
      }
    }
  }

  if (!args.dryRun) {
    try {
      await mkdir(join(projectRoot, 'graphs'), { recursive: true });
    } catch {
      // ignore
    }
    for (const op of writes) {
      await mkdir(dirname(op.path), { recursive: true });
      await writeFile(op.path, op.content, 'utf-8');
    }
  }

  return { report, writes };
}