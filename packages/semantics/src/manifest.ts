// SPDX-License-Identifier: Apache-2.0
import { parse as parseToml } from 'smol-toml';
import type { SqlDialect } from '@tatrman/parser';

/**
 * The `[packages]` block of `modeler.toml` (contracts §13.1). Controls the
 * module-style root prefix and how strictly an in-file `package` declaration
 * must match its directory.
 */
export interface PackagesConfig {
  /** Module-style prefix prepended to directory-derived package names. "" = none. */
  root: string;
  /** Severity policy for a declaration that mismatches its directory. */
  layout: 'flexible' | 'strict' | 'off';
}

export const defaultPackagesConfig: PackagesConfig = { root: '', layout: 'flexible' };

/** A problem found while resolving the `[packages]` block. Surfaced by hosts. */
export interface PackagesConfigDiagnostic {
  field: 'root' | 'layout';
  message: string;
}

const VALID_LAYOUTS: ReadonlySet<string> = new Set(['flexible', 'strict', 'off']);

/**
 * Resolve the raw `[packages]` table into a {@link PackagesConfig}, validating
 * the `layout` value. Unknown `layout` values fall back to the default and yield
 * a config diagnostic (PD1.1). Pure — no filesystem.
 */
export function resolvePackagesConfig(
  raw: ProjectManifest['packages'] | undefined
): { config: PackagesConfig; diagnostics: PackagesConfigDiagnostic[] } {
  const diagnostics: PackagesConfigDiagnostic[] = [];
  const root = typeof raw?.root === 'string' ? raw.root : defaultPackagesConfig.root;

  let layout: PackagesConfig['layout'] = defaultPackagesConfig.layout;
  if (raw?.layout !== undefined) {
    if (VALID_LAYOUTS.has(raw.layout)) {
      layout = raw.layout as PackagesConfig['layout'];
    } else {
      diagnostics.push({
        field: 'layout',
        message: `Unknown [packages].layout value '${raw.layout}'; expected "flexible", "strict", or "off". Falling back to "${defaultPackagesConfig.layout}".`,
      });
    }
  }

  return { config: { root, layout }, diagnostics };
}

// ---------------------------------------------------------------------------
// v4.0 — named schema bindings + per-package default schema (qname-redesign
// contracts §1–§2). A schema is a named handle binding to a physical database;
// it subsumes the embedded-SQL `[[sql.namespace-map]]` (contracts §1.1).
// ---------------------------------------------------------------------------

/** A named schema binding — the TTR-side handle for a physical (database, db-schema). */
export interface SchemaBinding {
  /** TTR handle written in refs, e.g. 'sales' — the `[schemas.<name>]` table key. */
  name: string;
  database: string;
  /** The actual SQL schema inside the database (kept distinct from the handle). */
  dbSchema: string;
  dialect: SqlDialect;
}

/** Per-package config: a default schema lets refs under the package skip the slot (D8). */
export interface PackageConfig {
  /** The package name, e.g. 'shop.sales' — the `[packages.<name>]` table key. */
  name: string;
  /** Schema handle; must exist in `schemas`. */
  defaultSchema?: string;
}

/** A `modeler.toml` validation problem (D9 collision / unknown package schema). */
export interface ManifestDiagnostic {
  code: 'schema-name-collision' | 'unknown-package-schema' | 'manifest/unknown-key';
  severity: 'error' | 'warning';
  message: string;
}

export interface ProjectManifest {
  project?: { name?: string; version?: string };
  language?: { preferred?: string };
  // `declared`/`namespaces` are reserved keys; any other `[schemas.<name>]`
  // sub-table is a SchemaBinding.
  schemas?: { declared?: string[]; namespaces?: Record<string, string> } & Record<string, unknown>;
  stock?: { load?: string[] };
  bindings?: { required?: boolean } & Record<string, unknown>;
  lint?: {
    strict?: boolean;
    requireDescriptions?: boolean;
    requireQualifiedRefs?: boolean;
  };
  defaults?: { schema?: string };
  // `root`/`layout` are reserved keys; any other `[packages."x.y"]` sub-table is
  // a PackageConfig (default-schema).
  packages?: { root?: string; layout?: string } & Record<string, unknown>;
}

export interface ResolvedManifest {
  name: string;
  projectRoot: string;
  preferredLanguage: string;
  declaredSchemas: string[];
  namespaces: Record<string, string>;
  stockVocabularies: string[];
  lint: { strict: boolean; requireDescriptions: boolean; requireQualifiedRefs: boolean };
  packages: PackagesConfig;
  /** v4.0 — named schema bindings, keyed by handle (contracts §2). */
  schemas: Record<string, SchemaBinding>;
  /** v4.0 — per-package config (default schema), keyed by package name. */
  packageConfigs: Record<string, PackageConfig>;
  /** v4.0 — project-wide fallbacks. `model` is never defaulted (derived from kind). */
  defaults: { schema?: string };
  bindings: { required: boolean };
  /** Configuration warnings that require the raw TOML keys (before resolution). */
  configDiagnostics?: ManifestDiagnostic[];
}

/**
 * Reserved model codes — a schema handle may not collide with these (D9).
 * NOTE: kept as a local set (not the canonical `qname.ts` MODEL_CODES) because the D9
 * reserved-word list historically also includes `query`, which the canonical model-code
 * set omits. `world` and `cn` are added here in lock-step with their addition to the canonical set.
 */
const MODEL_CODES: ReadonlySet<string> = new Set(['db', 'er', 'md', 'binding', 'query', 'cnc', 'world', 'automation', 'cn']);

/** Kind keywords — a schema handle may not collide with these (D9). */
const KIND_KEYWORDS: ReadonlySet<string> = new Set([
  'project', 'table', 'view', 'column', 'index', 'constraint', 'fk', 'procedure',
  'entity', 'attribute', 'relation', 'er2db_entity', 'er2db_attribute', 'er2db_relation',
  'query', 'role', 'er2cnc_role', 'drill_map', 'area',
  'domain', 'dimension', 'map', 'hierarchy', 'measure', 'cubelet',
  'md2db_cubelet', 'md2db_domain', 'md2db_map', 'md2er_cubelet',
  // 0.16 — the automation kinds.
  'parameter', 'action', 'service', 'approval', 'program', 'recipe',
]);

const VALID_DIALECTS: ReadonlySet<string> = new Set(['tsql', 'postgres', 'duckdb', 'mysql', 'bigquery']);

/**
 * Parse a `modeler.toml` source. Normalizes kebab-case keys the architecture
 * uses (`require-descriptions`) to the camelCase shape `ProjectManifest`
 * expects (`requireDescriptions`).
 */
export function parseManifest(content: string): ProjectManifest {
  const raw = parseToml(content) as Record<string, unknown> & {
    lint?: Record<string, unknown>;
  };
  if (raw.lint && typeof raw.lint === 'object') {
    const lint = raw.lint;
    if ('require-descriptions' in lint && !('requireDescriptions' in lint)) {
      lint.requireDescriptions = lint['require-descriptions'];
    }
    if ('require-qualified-refs' in lint && !('requireQualifiedRefs' in lint)) {
      lint.requireQualifiedRefs = lint['require-qualified-refs'];
    }
  }
  return raw as ProjectManifest;
}

/** Reserved keys inside `[schemas]` / `[packages]` that are NOT sub-table bindings. */
const SCHEMA_RESERVED_KEYS: ReadonlySet<string> = new Set(['declared', 'namespaces']);
const PACKAGE_RESERVED_KEYS: ReadonlySet<string> = new Set(['root', 'layout']);

function asStr(v: unknown): string | undefined {
  return typeof v === 'string' ? v : undefined;
}

/** Build the named `[schemas.<name>]` bindings, skipping the reserved keys. */
function resolveSchemaBindings(raw: ProjectManifest['schemas'] | undefined): Record<string, SchemaBinding> {
  const out: Record<string, SchemaBinding> = {};
  if (!raw || typeof raw !== 'object') return out;
  for (const [name, val] of Object.entries(raw)) {
    if (SCHEMA_RESERVED_KEYS.has(name)) continue;
    if (!val || typeof val !== 'object') continue;
    const t = val as Record<string, unknown>;
    const dialectRaw = asStr(t['dialect']);
    out[name] = {
      name,
      database: asStr(t['database']) ?? '',
      dbSchema: asStr(t['db-schema']) ?? '',
      dialect: (dialectRaw && VALID_DIALECTS.has(dialectRaw) ? dialectRaw : 'tsql') as SqlDialect,
    };
  }
  return out;
}

/** Build the per-package `[packages."x.y"]` configs, skipping the reserved keys. */
function resolvePackageConfigs(raw: ProjectManifest['packages'] | undefined): Record<string, PackageConfig> {
  const out: Record<string, PackageConfig> = {};
  if (!raw || typeof raw !== 'object') return out;
  for (const [name, val] of Object.entries(raw)) {
    if (PACKAGE_RESERVED_KEYS.has(name)) continue;
    if (!val || typeof val !== 'object') continue;
    const t = val as Record<string, unknown>;
    out[name] = { name, defaultSchema: asStr(t['default-schema']) };
  }
  return out;
}

function basename(p: string): string {
  // works for both POSIX and Windows separators
  const norm = p.replace(/\\/g, '/');
  const trimmed = norm.endsWith('/') ? norm.slice(0, -1) : norm;
  const idx = trimmed.lastIndexOf('/');
  return idx === -1 ? trimmed : trimmed.slice(idx + 1);
}

export function resolveManifest(m: ProjectManifest | undefined, projectRoot: string): ResolvedManifest {
  return {
    name: m?.project?.name ?? (basename(projectRoot) || 'unnamed'),
    projectRoot,
    preferredLanguage: m?.language?.preferred ?? 'en',
    declaredSchemas: m?.schemas?.declared ?? ['db', 'er', 'binding', 'query', 'cnc'],
    namespaces: m?.schemas?.namespaces ?? {},
    stockVocabularies: m?.stock?.load ?? ['cnc-roles'],
    lint: {
      strict: m?.lint?.strict ?? false,
      requireDescriptions: m?.lint?.requireDescriptions ?? false,
      requireQualifiedRefs: m?.lint?.requireQualifiedRefs ?? false,
    },
    packages: resolvePackagesConfig(m?.packages).config,
    schemas: resolveSchemaBindings(m?.schemas),
    packageConfigs: resolvePackageConfigs(m?.packages),
    defaults: { schema: m?.defaults?.schema },
    bindings: { required: m?.bindings?.required === true },
    configDiagnostics: Object.keys(m?.bindings ?? {}).filter((k) => k !== 'required').map((key) => ({
      code: 'manifest/unknown-key' as const,
      severity: 'warning' as const,
      message: `Unknown [bindings] key '${key}'. Expected 'required'.`,
    })),
  };
}

/**
 * Validate the v4.0 schema/package config (qname-redesign contracts §5):
 *  - `schema-name-collision` (D9): a schema handle equals a package name, a
 *    model code, or a kind keyword — which would make a leading ref segment
 *    un-classifiable by vocabulary alone.
 *  - `unknown-package-schema`: a `[packages.*].default-schema` names a schema
 *    absent from `[schemas]`.
 * Pure — returns diagnostics for the host to surface.
 */
export function validateManifest(resolved: ResolvedManifest): ManifestDiagnostic[] {
  const diagnostics: ManifestDiagnostic[] = [...(resolved.configDiagnostics ?? [])];
  const packageNames = new Set(Object.keys(resolved.packageConfigs));
  for (const name of Object.keys(resolved.schemas)) {
    let clash: string | undefined;
    if (MODEL_CODES.has(name)) clash = 'a model code';
    else if (KIND_KEYWORDS.has(name)) clash = 'a kind keyword';
    else if (packageNames.has(name)) clash = 'a package name';
    if (clash) {
      diagnostics.push({
        code: 'schema-name-collision',
        severity: 'error',
        message: `Schema handle '${name}' collides with ${clash}; every leading reference segment must be classifiable by vocabulary alone (D9).`,
      });
    }
  }
  for (const pkg of Object.values(resolved.packageConfigs)) {
    if (pkg.defaultSchema !== undefined && !(pkg.defaultSchema in resolved.schemas)) {
      diagnostics.push({
        code: 'unknown-package-schema',
        severity: 'error',
        message: `Package '${pkg.name}' default-schema '${pkg.defaultSchema}' is not declared in [schemas].`,
      });
    }
  }
  return diagnostics;
}
// ---------------------------------------------------------------------------
// v4.0 — manifest-driven slot defaults (qname-redesign D8). These wire the
// parsed `[packages.*].default-schema` / `[defaults].schema` bindings into the
// resolver and the symbol-table population, so a db reference under a package
// resolves (and a db symbol is keyed) with the package's schema even when no
// `schema` directive is written. The schema slot is db-only (D6); for er/md/cnc/
// binding the effective id is ignored downstream by `buildCanonicalKey`.
// ---------------------------------------------------------------------------

/**
 * The schema id a document (or db reference) should use, filling the slot from
 * the manifest when the file declares no `schema` directive:
 *   file `schema` directive → package `default-schema` (D8) → `[defaults].schema`
 *   → '' (the caller then falls back to the conventional `dbo` for db keys).
 */
export function effectiveSchemaId(
  fileSchema: string | undefined,
  packageName: string | undefined,
  manifest?: Pick<ResolvedManifest, 'packageConfigs' | 'defaults'>,
): string {
  if (fileSchema) return fileSchema;
  if (!manifest) return '';
  const pkg = packageName ? manifest.packageConfigs[packageName] : undefined;
  return pkg?.defaultSchema ?? manifest.defaults.schema ?? '';
}
