#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { Command } from 'commander';
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { parseString } from '@tatrman/parser';
import type { Document } from '@tatrman/parser';
import {
  ProjectSymbolTable,
  Resolver,
  PackageGraphBuilder,
  parseManifest,
  resolveManifest,
  validateManifest,
  synthesizeMappings,
  effectivePackage,
  effectiveSchemaId,
} from '@tatrman/semantics';
import { DiagnosticCode } from '@tatrman/parser';
import type { ManifestDiagnostic, ResolvedManifest } from '@tatrman/semantics';
import { applyWorkspaceEditToText } from '@tatrman/edit';
import { lintDocument, lintProject } from './runner.js';
import { loadLintConfig, type ResolvedLintConfig } from './config.js';
import { collectSafeFixes } from './fix.js';
import { RULES } from './registry.js';
import type { LintCode, LintDiagnostic, Rule } from './rule.js';

const RANK: Record<string, number> = { off: 0, info: 1, warning: 2, error: 3, none: 0 };

const program = new Command();

program
  .name('modeler-lint')
  .description('Lint a TTR project against the .ttrlint.toml rule set')
  .argument('<project-root>', 'Root of the project (directory containing modeler.toml)')
  .option('--fix', 'Apply safe fixes (per [fix].apply), then report the remainder', false)
  .option('--format <fmt>', 'pretty | json', 'pretty')
  .option('--fail-on <sev>', 'error | warning | info | none (overrides [cli].fail-on)')
  .option('--rule <id>', 'Run only this rule (repeatable)', collect, [])
  .option('--explain <id>', 'Print a rule’s docs and exit')
  .option('--quiet', 'Print only error-severity diagnostics', false)
  .action(async (root: string, opts: CliOptions) => {
    try {
      if (opts.explain) {
        const rule = RULES.get(opts.explain);
        if (!rule) {
          console.error(`Unknown rule: ${opts.explain}`);
          process.exit(2);
        }
        console.log(`${rule.id} [${rule.category}, default ${rule.defaultSeverity}] — ${rule.docs}`);
        process.exit(0);
      }

      const config = await loadLintConfig(root, legacyLint(root), async (p) =>
        existsSync(p) ? readFileSync(p, 'utf-8') : undefined
      );
      const ruleSubset = subsetRules(opts.rule);
      const files = collectFiles(root);
      if (files.length === 0) {
        console.error(`modeler-lint: no .ttrm/.ttrg files under ${root}`);
        process.exit(2);
      }

      let diagnostics = lintAll(root, files, config, ruleSubset);

      if (opts.fix && config.applyFixes !== 'none') {
        diagnostics = applyFixesToFixpoint(root, files, config, ruleSubset);
      }

      report(diagnostics, config, opts);
      const failOn = opts.failOn ?? config.failOn;
      const failed = failOn !== 'none' && diagnostics.some((d) => RANK[d.severity] >= RANK[failOn]);
      process.exit(failed ? 1 : 0);
    } catch (err) {
      console.error(`modeler-lint failed: ${err instanceof Error ? err.message : String(err)}`);
      process.exit(2);
    }
  });

interface CliOptions {
  fix: boolean;
  format: string;
  failOn?: 'error' | 'warning' | 'info' | 'none';
  rule: string[];
  explain?: string;
  quiet: boolean;
}

function collect(value: string, prev: string[]): string[] {
  return [...prev, value];
}

function subsetRules(ids: string[]): Rule[] | undefined {
  if (!ids.length) return undefined;
  const want = new Set(ids);
  return [...RULES.values()].filter((r) => want.has(r.id));
}

function legacyLint(root: string): { strict?: boolean; requireDescriptions?: boolean } {
  const p = join(root, 'modeler.toml');
  if (!existsSync(p)) return {};
  try {
    const m = parseManifest(readFileSync(p, 'utf-8'));
    return { strict: m.lint?.strict, requireDescriptions: m.lint?.requireDescriptions };
  } catch {
    return {};
  }
}

interface LoadedProject {
  documents: Map<string, Document>;
  deps: { manifest: ReturnType<typeof resolveManifest>; symbols: ProjectSymbolTable; resolver: Resolver };
  graph: ReturnType<PackageGraphBuilder['build']>;
}

/** Parse `<root>/modeler.toml` if present, else fall back to defaults. */
function loadManifestFromRoot(root: string): ResolvedManifest {
  try {
    return resolveManifest(parseManifest(readFileSync(join(root, 'modeler.toml'), 'utf-8')), root);
  } catch {
    return resolveManifest(undefined, root);
  }
}

function loadProject(root: string, files: string[]): LoadedProject {
  const manifest = loadManifestFromRoot(root);
  const symbols = new ProjectSymbolTable();
  const documents = new Map<string, Document>();
  for (const file of files) {
    const ast = parseString(readFileSync(file, 'utf-8'), file).ast;
    if (!ast) continue;
    documents.set(file, ast);
    const packageName = effectivePackage(ast, file, root, manifest.packages);
    symbols.upsertDocument(file, ast, ast.modelDirective?.modelCode ?? '', effectiveSchemaId(ast.modelDirective?.schema, packageName, manifest), packageName);
    synthesizeMappings(symbols, file, ast);
  }
  const resolver = new Resolver(symbols, manifest.packages.root, manifest);
  const graph = new PackageGraphBuilder(symbols, documents).build();
  return { documents, deps: { manifest, symbols, resolver }, graph };
}

function lintAll(root: string, files: string[], config: ResolvedLintConfig, rules: Rule[] | undefined): LintDiagnostic[] {
  const project = loadProject(root, files);
  const projectByUri = lintProject(project.documents, project.graph, project.deps, config, rules);
  const out: LintDiagnostic[] = [];
  for (const [uri, ast] of project.documents) {
    out.push(...lintDocument(uri, ast, project.deps, config, rules));
    out.push(...(projectByUri.get(uri) ?? []));
  }
  // Config-level diagnostics (unknown rule, clamp, deprecation) once.
  out.push(...config.diagnostics);
  // Manifest-load diagnostics, preserving their individual codes and severities,
  // attributed to modeler.toml.
  out.push(...manifestDiagnostics(root, project.deps.manifest));
  return out;
}

/** validateManifest() diagnostics mapped onto LintDiagnostic, sourced at modeler.toml. */
function manifestDiagnostics(root: string, manifest: ResolvedManifest): LintDiagnostic[] {
  const tomlFile = join(root, 'modeler.toml');
  const codeFor = (c: ManifestDiagnostic['code']): LintCode => {
    switch (c) {
      case 'schema-name-collision': return DiagnosticCode.SchemaNameCollision;
      case 'unknown-package-schema': return DiagnosticCode.UnknownPackageSchema;
      case 'manifest/unknown-key': return 'ttrlint/manifest-unknown-key';
      default: { const exhaustive: never = c; return exhaustive; }
    }
  };
  return validateManifest(manifest).map((d) => ({
    ruleId: d.code,
    code: codeFor(d.code),
    severity: d.severity,
    message: d.message,
    source: { file: tomlFile, line: 1, column: 0, endLine: 1, endColumn: 0, offsetStart: 0, offsetEnd: 0 },
  }));
}

function applyFixesToFixpoint(root: string, files: string[], config: ResolvedLintConfig, rules: Rule[] | undefined): LintDiagnostic[] {
  const MAX_PASSES = 10;
  const texts = new Map(files.map((f) => [f, readFileSync(f, 'utf-8')]));
  const manifest = loadManifestFromRoot(root);

  for (let pass = 0; pass < MAX_PASSES; pass++) {
    // Rebuild the project from current in-memory texts each pass.
    const symbols = new ProjectSymbolTable();
    const documents = new Map<string, Document>();
    for (const [file, text] of texts) {
      const ast = parseString(text, file).ast;
      if (!ast) continue;
      documents.set(file, ast);
      const packageName = effectivePackage(ast, file, root, manifest.packages);
      symbols.upsertDocument(file, ast, ast.modelDirective?.modelCode ?? '', effectiveSchemaId(ast.modelDirective?.schema, packageName, manifest), packageName);
      synthesizeMappings(symbols, file, ast);
    }
    const resolver = new Resolver(symbols, manifest.packages.root, manifest);

    let anyApplied = false;
    for (const [file, ast] of documents) {
      const text = texts.get(file)!;
      const diags = lintDocument(file, ast, { manifest, symbols, resolver }, config, rules);
      const ctx = {
        scope: 'document' as const, uri: file, ast, text,
        refs: [], manifest, symbols, resolver, report: () => {},
      };
      const result = collectSafeFixes(diags, ctx);
      if (result.applied.length > 0) {
        texts.set(file, applyWorkspaceEditToText(text, result.edit, file));
        anyApplied = true;
      }
    }
    if (!anyApplied) break;
  }

  // Write back changed files, then re-lint for the remainder.
  for (const [file, text] of texts) writeFileSync(file, text, 'utf-8');
  return lintAll(root, files, config, rules);
}

function report(diagnostics: LintDiagnostic[], config: ResolvedLintConfig, opts: CliOptions): void {
  const shown = opts.quiet ? diagnostics.filter((d) => d.severity === 'error') : diagnostics;
  if (opts.format === 'json') {
    console.log(JSON.stringify(shown.map(toJson), null, 2));
    return;
  }
  if (shown.length === 0) {
    console.log('No problems found.');
    return;
  }
  for (const d of shown) {
    console.log(`${d.source.file}:${d.source.line}:${d.source.column + 1}  ${d.severity}  ${d.ruleId}  ${d.message}`);
  }
  const errors = diagnostics.filter((d) => d.severity === 'error').length;
  const warnings = diagnostics.filter((d) => d.severity === 'warning').length;
  console.log(`\n${diagnostics.length} problem(s) (${errors} error, ${warnings} warning)`);
}

function toJson(d: LintDiagnostic) {
  return {
    uri: d.source.file,
    ruleId: d.ruleId,
    code: d.code,
    severity: d.severity,
    message: d.message,
    range: {
      start: { line: d.source.line - 1, character: d.source.column },
      end: { line: d.source.endLine - 1, character: d.source.endColumn },
    },
  };
}

function collectFiles(root: string): string[] {
  const out: string[] = [];
  const walk = (dir: string): void => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (entry.name.startsWith('.') || entry.name === 'node_modules') continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.ttrm') || entry.name.endsWith('.ttrg')) out.push(full);
    }
  };
  const st = statSync(root);
  if (st.isFile()) return [root];
  walk(root);
  return out.sort();
}

program.parse();
