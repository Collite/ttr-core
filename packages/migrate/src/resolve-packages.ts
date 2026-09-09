// SPDX-License-Identifier: Apache-2.0
import { parseString } from '@tatrman/parser';
import {
  ProjectSymbolTable,
  Resolver,
  AreaTableBuilder,
  effectivePackage,
  elideRoot,
  parseManifest,
  resolveManifest,
  type PackagesConfig,
  type AreaEntry,
} from '@tatrman/semantics';

export interface ResolvedPackage {
  canonicalName: string;
  declaredName: string;
  nested: boolean;
  directory: string;
}

export interface ResolvedEntity {
  qname: string;
  package: string;
  schema: string;
}

export interface ResolvedArtifactArea {
  name: string;
  resolvedPackages: string[];
  resolvedEntities: string[];
}

export interface ResolvedPackagesArtifact {
  formatVersion: 1;
  generatedFrom: string;
  root: string;
  packages: ResolvedPackage[];
  entities: ResolvedEntity[];
  areas: ResolvedArtifactArea[];
}

/** A model file as text, keyed by its path relative to (or under) the project root. */
export interface ModelFile {
  /** Absolute or project-root-relative path; used to derive package directory. */
  path: string;
  text: string;
}

const isModelExt = (p: string) => p.endsWith('.ttrm') || p.endsWith('.ttrg');

/** POSIX dirname of a path, relative to projectRoot, normalised with '/'. */
function relativeDir(path: string, projectRoot: string): string {
  const norm = path.replace(/\\/g, '/');
  const root = projectRoot.replace(/\\/g, '/').replace(/\/$/, '');
  const rel = norm.startsWith(root + '/') ? norm.slice(root.length + 1) : norm;
  const idx = rel.lastIndexOf('/');
  return idx === -1 ? '' : rel.slice(0, idx);
}

/** True if `canonicalName` has more than one segment after the configured root. */
function isNested(canonicalName: string, root: string): boolean {
  const rel = elideRoot(canonicalName, root);
  return rel.length > 0 && rel.includes('.');
}

/**
 * The fully root-prefixed canonical form of a package name or qname (contracts
 * §13.4). PD1's symbol table stores a *declared* package verbatim — which may
 * elide the configured `root` — so the artifact re-applies the prefix here so
 * every `canonicalName` / `qname` is uniformly prefixed. With `root === ""` this
 * is the identity (the common `ai-models` case is unaffected).
 */
function canonicalize(name: string, root: string): string {
  if (!root) return name;
  const rel = elideRoot(name, root);
  return rel ? `${root}.${rel}` : root;
}

/**
 * Build the resolved-packages artifact from a set of model files (pure — no fs).
 * Deterministic: every array is sorted and input file order is irrelevant.
 */
export function buildArtifactFromFiles(
  files: ModelFile[],
  projectRoot: string,
  cfg: PackagesConfig,
  generatedFrom: string,
  /**
   * Called once per file the parser rejected. Optional, and the artifact is identical with or
   * without it — but a build that drops a file silently fails the same way the bug below did, so
   * the CLI passes one and says so.
   */
  onSkip?: (path: string, errors: readonly { message: string }[]) => void
): ResolvedPackagesArtifact {
  const symbols = new ProjectSymbolTable();
  const areaEntries: AreaEntry[] = [];
  // canonicalName → representative {declaredName, directory}. First file wins;
  // files are visited in sorted path order so the choice is deterministic.
  const pkgMeta = new Map<string, { declaredName: string; directory: string }>();

  for (const file of [...files].filter((f) => isModelExt(f.path)).sort((a, b) => a.path.localeCompare(b.path))) {
    const uri = file.path.startsWith('file://') ? file.path : `file://${file.path}`;
    const parsed = parseString(file.text, uri);
    const ast = parsed.ast;
    if (!ast) continue;

    // ⛔ A FILE THE PARSER REJECTED CONTRIBUTES NOTHING.
    //
    // The parser is deliberately error-TOLERANT: it recovers past a bad token and keeps whatever
    // definitions it can still read, which is exactly what an editor wants from a file being typed
    // into. A build artifact wants the opposite — it is a statement about what a project CONTAINS,
    // and a definition salvaged from a syntax error is not something the project contains.
    //
    // This line used to read `parseString(...).ast` and never look at `.errors`, so a rejected file
    // still contributed packages, entities and areas — under a GUESSED schema code, because its
    // `model` directive was usually part of what failed (an empty modelCode reads as `er`). Found
    // on kantheon's `investment` package: three files declare `model book`, a model code this
    // grammar does not have, and were believed invisible. They were not — their `def entity`
    // declarations reached the symbol table, so the artifact claimed er entities nobody had
    // authored, and inside the table they competed for qnames with the real `er` layer, with
    // alphabetical file order deciding the winner.
    if (parsed.errors.length > 0) {
      onSkip?.(file.path, parsed.errors);
      continue;
    }

    // v3.0: subject areas are now `def area` definitions (no `.ttrd` file kind).
    // They drive the `areas` artifact (recursive package closure). Collect them
    // here from the definition list.
    const areaDefs = ast.definitions.filter((def) => def.kind === 'area');
    for (const area of areaDefs) areaEntries.push({ area, documentUri: uri });

    // A file whose ONLY definitions are areas establishes no package/entities —
    // mirrors the v2.3 `.ttrd` "contributes no symbols" rule, so migrating a
    // `domains/*.ttrd` to `domains/*.ttrm` produces no package-list drift.
    if (areaDefs.length > 0 && areaDefs.length === ast.definitions.length) continue;

    const canonical = effectivePackage(ast, file.path, projectRoot, cfg);
    const schemaCode = ast.modelDirective?.modelCode ?? '';
    const namespace = ast.modelDirective?.schema ?? '';
    symbols.upsertDocument(uri, ast, schemaCode, namespace, canonical);

    if (canonical && !pkgMeta.has(canonical)) {
      pkgMeta.set(canonical, {
        declaredName: ast.packageDecl?.name ?? canonical,
        directory: relativeDir(file.path, projectRoot),
      });
    }
  }

  const resolver = new Resolver(symbols, cfg.root);

  const root = cfg.root;

  const packages: ResolvedPackage[] = symbols
    .listPackages()
    .filter((name) => name !== '')
    .map((effectiveName) => {
      const meta = pkgMeta.get(effectiveName);
      const canonicalName = canonicalize(effectiveName, root);
      return {
        canonicalName,
        // The bare written declaration (may elide root); falls back to canonical
        // for derived (undeclared) packages, which have no written form.
        declaredName: meta?.declaredName ?? canonicalName,
        nested: isNested(canonicalName, root),
        directory: meta?.directory ?? '',
      };
    })
    .sort((a, b) => a.canonicalName.localeCompare(b.canonicalName));

  const entities: ResolvedEntity[] = symbols
    .all()
    .filter((e) => e.kind === 'entity')
    .map((e) => ({
      qname: canonicalize(e.qname, root),
      package: canonicalize(e.packageName, root),
      schema: e.schemaCode,
    }))
    .sort((a, b) => a.qname.localeCompare(b.qname));

  const areaTable = new AreaTableBuilder(symbols, resolver, root).build(areaEntries);
  const areas: ResolvedArtifactArea[] = [...areaTable.values()]
    .map((d) => ({
      name: d.name,
      resolvedPackages: d.resolvedPackages.map((p) => canonicalize(p, root)).sort((a, b) => a.localeCompare(b)),
      resolvedEntities: d.resolvedEntities.map((q) => canonicalize(q, root)).sort((a, b) => a.localeCompare(b)),
    }))
    .sort((a, b) => a.name.localeCompare(b.name));

  return { formatVersion: 1, generatedFrom, root: cfg.root, packages, entities, areas };
}

/**
 * Serialise the artifact per the determinism contract (§13.4): 2-space JSON,
 * trailing newline. Key order is fixed by the object literal in
 * {@link buildArtifactFromFiles}; arrays are pre-sorted.
 */
export function serializeArtifact(artifact: ResolvedPackagesArtifact): string {
  return JSON.stringify(artifact, null, 2) + '\n';
}

/** POSIX basename, used for a machine-independent `generatedFrom`. */
export function basename(p: string): string {
  const norm = p.replace(/\\/g, '/').replace(/\/$/, '');
  const idx = norm.lastIndexOf('/');
  return idx === -1 ? norm : norm.slice(idx + 1);
}

/**
 * Walk a project root and build the artifact from disk. `generatedFrom` is the
 * project-root **basename** (not the absolute path) so the committed snapshot is
 * byte-identical across machines (a CI drift gate compares it to a local run).
 */
export async function resolvePackages(
  projectRoot: string,
  onSkip?: (path: string, errors: readonly { message: string }[]) => void
): Promise<ResolvedPackagesArtifact> {
  const { readFile } = await import('node:fs/promises');
  const { join } = await import('node:path');

  let cfg: PackagesConfig;
  try {
    cfg = resolveManifest(parseManifest(await readFile(join(projectRoot, 'modeler.toml'), 'utf-8')), projectRoot).packages;
  } catch {
    cfg = resolveManifest(undefined, projectRoot).packages;
  }

  const files: ModelFile[] = [];
  await walk(projectRoot, files);
  return buildArtifactFromFiles(files, projectRoot, cfg, basename(projectRoot), onSkip);
}

async function walk(dir: string, out: ModelFile[]): Promise<void> {
  const { readdir, readFile } = await import('node:fs/promises');
  const { join } = await import('node:path');
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === '.modeler' || entry.name === 'node_modules' || entry.name === '.git') continue;
      await walk(full, out);
    } else if (entry.isFile() && isModelExt(entry.name)) {
      out.push({ path: full, text: await readFile(full, 'utf-8') });
    }
  }
}
