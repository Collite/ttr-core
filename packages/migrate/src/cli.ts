#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { Command } from 'commander';
import { runMigration, resolvePackages, serializeArtifact, runPhase0, runQnameMigration, migrateLexicon, runTtrlMigration, type MigrateReport } from './index.js';
import { readFile, writeFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { unifiedDiff } from './text-diff.js';

const program = new Command();

program
  .name('modeler')
  .description('Tatrman Modeler CLI');

program
  .command('migrate-to-packages')
  .description('Migrate a v1 project (no packages) to v1.1 (packages, imports, per-graph .ttrg files)')
  .argument('<project-root>', 'Root of the project to migrate (directory containing modeler.toml)')
  .option('--dry-run', 'Show what would be done without writing any files', false)
  .option('--commit-ttrl-removal', 'Delete .modeler/layout.ttrl after successful migration', false)
  .option('--wildcard-threshold <N>', 'Minimum symbols from a package to use wildcard import (default 3)', '3')
  .option('--verbose', 'Print detailed progress', false)
  .action(async (projectRoot, opts) => {
    const args = {
      projectRoot,
      dryRun: opts.dryRun ?? false,
      commitTtrlRemoval: opts.commitTtrlRemoval ?? false,
      verbose: opts.verbose ?? false,
      wildcardThreshold: parseInt(opts.wildcardThreshold ?? '3', 10),
    };

    try {
      const { report, writes } = await runMigration(args);

      if (args.dryRun) {
        console.log('=== DRY RUN — no files written ===\n');
      }

      console.log(`Files touched: ${report.filesTouched.length}`);
      if (args.verbose) for (const f of report.filesTouched) console.log(`  ${f}`);

      console.log(`\nPackages created: ${report.packagesCreated.length}`);
      if (args.verbose) for (const p of report.packagesCreated) console.log(`  ${p}`);

      console.log(`\nImports inserted: ${report.importsInserted.length}`);
      if (args.verbose) for (const imp of report.importsInserted) {
        const form = imp.isWildcard ? '.*' : `.${imp.schema}.${imp.namespace}.${imp.defName}`;
        console.log(`  ${imp.uri}: import ${imp.package}${form}`);
      }

      console.log(`\n.ttrg files created: ${report.ttrgFilesCreated.length}`);
      if (args.verbose) for (const g of report.ttrgFilesCreated) console.log(`  ${g}`);

      if (report.ambiguousReferences.length > 0) {
        console.log(`\nAmbiguous references (manual resolution required):`);
        for (const amb of report.ambiguousReferences) {
          console.log(`  ${amb.uri}:${amb.line}: "${amb.ref}" — candidates: ${amb.candidates.join(', ')}`);
        }
      }

      if (args.dryRun) {
        console.log(`\nWould write ${writes.length} file(s)`);
        process.exit(0);
      }

      console.log(`\nWrote ${writes.length} file(s)`);
      await writeReport(projectRoot, report);

      if (report.ambiguousReferences.length > 0) {
        console.log('\nAmbiguous references require manual disambiguation. Fix and re-run.');
        process.exit(1);
      }

      process.exit(0);
    } catch (err) {
      console.error('Migration failed:', err);
      process.exit(2);
    }
  });

async function writeReport(projectRoot: string, report: MigrateReport): Promise<void> {
  try {
    const { mkdir, writeFile } = await import('node:fs/promises');
    const { join } = await import('node:path');
    const dir = join(projectRoot, '.modeler');
    await mkdir(dir, { recursive: true });
    await writeFile(join(dir, 'migrate-report.json'), JSON.stringify(report, null, 2), 'utf-8');
  } catch {
    // non-fatal
  }
}

program
  .command('resolve-packages')
  .description('Emit the deterministic resolved-packages.json artifact (packages, entities, areas)')
  .argument('<project-root>', 'Root of the model project (directory containing modeler.toml)')
  .option('--out <file>', 'Output path (default <project-root>/.modeler/resolved-packages.json)')
  .option('--check', 'Compare the on-disk artifact to a freshly-generated one; exit non-zero on drift', false)
  .option('--verbose', 'Print a summary to stderr', false)
  .action(async (projectRoot, opts) => {
    try {
      const { join } = await import('node:path');
      const outPath: string = opts.out ?? join(projectRoot, '.modeler', 'resolved-packages.json');

      // A file the parser rejected contributes nothing to the artifact. Say which, always — not
      // only under --verbose: a build that quietly ignores a model file is how a project ends up
      // believing it declares something it does not.
      const skipped: string[] = [];
      const artifact = await resolvePackages(projectRoot, (path, errors) => {
        skipped.push(path);
        console.error(`resolve-packages: SKIPPED ${path} — it does not parse (${errors[0]?.message ?? 'parse error'})`);
      });
      const serialized = serializeArtifact(artifact);

      if (opts.verbose) {
        console.error(
          `resolve-packages: ${artifact.packages.length} package(s), ${artifact.entities.length} entit(y/ies), ${artifact.areas.length} area(s)` +
            (skipped.length ? `, ${skipped.length} file(s) skipped` : '')
        );
      }

      if (opts.check) {
        const { readFile } = await import('node:fs/promises');
        let onDisk: string;
        try {
          onDisk = await readFile(outPath, 'utf-8');
        } catch {
          console.error(`resolve-packages --check: no artifact at ${outPath}; run 'modeler resolve-packages' to generate it.`);
          process.exit(3);
          return;
        }
        if (onDisk !== serialized) {
          console.error(`resolve-packages --check: ${outPath} is stale. Re-run 'modeler resolve-packages --out ${outPath}'.`);
          process.exit(3);
          return;
        }
        if (opts.verbose) console.error(`resolve-packages --check: ${outPath} is up to date.`);
        process.exit(0);
        return;
      }

      const { mkdir, writeFile } = await import('node:fs/promises');
      const { dirname } = await import('node:path');
      await mkdir(dirname(outPath), { recursive: true });
      await writeFile(outPath, serialized, 'utf-8');
      if (opts.verbose) console.error(`resolve-packages: wrote ${outPath}`);
      process.exit(0);
    } catch (err) {
      console.error('resolve-packages failed:', err);
      process.exit(2);
    }
  });

program
  .command('phase0')
  .description('Migrate a pre-3.0 project to grammar 3.0: *.ttr→*.ttrm, schema map→binding, inline mapping:→binding:, .ttrd domain block→def area .ttrm')
  .argument('<project-root>', 'Root of the project to migrate')
  .option('--dry-run', 'Show what would change without writing or deleting files', false)
  .action(async (projectRoot, opts) => {
    try {
      const result = await runPhase0(projectRoot, { dryRun: opts.dryRun ?? false });
      if (opts.dryRun) console.log('=== DRY RUN — no files written ===\n');
      console.log(`Files written:  ${result.writes.length}`);
      for (const w of result.writes) console.log(`  + ${w.path}`);
      console.log(`Files removed:  ${result.deletes.length}`);
      for (const d of result.deletes) console.log(`  - ${d}`);
      process.exit(0);
    } catch (err) {
      console.error('phase0 migration failed:', err);
      process.exit(2);
    }
  });

program
  .command('migrate-qnames')
  .description('Rewrite legacy canonical keys to the v4.0 uniform shape (package.model.schema?.kind.name) in graph objects + .ttrg layout keys')
  .argument('<project-root>', 'Root of the project to migrate (directory with model files)')
  .option('--dry-run', 'Show what would change without writing any files', false)
  .option('--verbose', 'Print the full key map and per-file changes', false)
  .action(async (projectRoot, opts) => {
    try {
      const plan = await runQnameMigration(projectRoot, { dryRun: opts.dryRun ?? false });
      if (opts.dryRun) console.log('=== DRY RUN — no files written ===\n');
      console.log(`Key remappings: ${plan.keyMap.size}`);
      if (opts.verbose) {
        for (const [from, to] of [...plan.keyMap].sort()) console.log(`  ${from}  →  ${to}`);
      }
      console.log(`\nFiles ${opts.dryRun ? 'to change' : 'changed'}: ${plan.writes.length}`);
      if (opts.verbose) for (const w of plan.writes) console.log(`  ${w.path}`);
      if (plan.manifestWrite) {
        console.log(`\nmodeler.toml: legacy [schemas] lifted to named bindings.`);
      }
      // --dry-run prints a unified diff per changed file (plan Phase 7 §4).
      if (opts.dryRun) {
        const diffs = [...plan.writes.map((w) => w.diff), ...(plan.manifestWrite ? [plan.manifestWrite.diff] : [])].filter(Boolean);
        if (diffs.length > 0) console.log(`\n${diffs.join('\n\n')}`);
      }
      if (plan.reparseFailures.length > 0) {
        console.error(`\nRe-parse FAILED for ${plan.reparseFailures.length} file(s) — not written:`);
        for (const f of plan.reparseFailures) console.error(`  ${f}`);
        process.exit(1);
      }
      process.exit(0);
    } catch (err) {
      console.error('migrate-qnames failed:', err);
      process.exit(2);
    }
  });

program
  .command('migrate-lexicon')
  .description('Rewrite legacy `search {}` vocabulary (aliases/patterns/examples) into inline `lexicon {}` sugar (RS-32). Locale-keyed keywords + entity top-level aliases are left for manual migration — the deprecation warnings guide them.')
  .argument('<project-root>', 'Root of the project to migrate (directory with .ttrm files)')
  .option('--dry-run', 'Show a unified diff per changed file without writing', false)
  .action(async (projectRoot: string, opts: { dryRun?: boolean }) => {
    try {
      async function walk(dir: string): Promise<string[]> {
        const out: string[] = [];
        for (const e of await readdir(dir, { withFileTypes: true })) {
          if (e.name === '.modeler' || e.name === 'node_modules' || e.name === '.git') continue;
          const full = join(dir, e.name);
          if (e.isDirectory()) out.push(...(await walk(full)));
          else if (e.isFile() && e.name.endsWith('.ttrm')) out.push(full);
        }
        return out;
      }
      const files = await walk(projectRoot);
      let changed = 0;
      for (const file of files) {
        const before = await readFile(file, 'utf-8');
        const after = migrateLexicon(before, file);
        if (after === before) continue;
        changed++;
        if (opts.dryRun) console.log(unifiedDiff(file, before, after));
        else await writeFile(file, after, 'utf-8');
      }
      console.log(`${opts.dryRun ? 'Would change' : 'Changed'} ${changed} file(s).`);
      process.exit(0);
    } catch (err) {
      console.error('migrate-lexicon failed:', err);
      process.exit(2);
    }
  });

program
  .command('migrate-to-ttrl-sidecar')
  .description(
    'Migrate .ttrg files off their in-file `layout` property onto a paired `.ttrl` sidecar (TP-5 T2, C1-c). Idempotent — already-migrated files are a clean no-op.'
  )
  .argument('<project-root>', 'Root to scan for .ttrg files')
  .option('--dry-run', 'Show what would change (diff per file) without writing any files', false)
  .option('--verbose', 'Print per-file skip reasons in addition to the summary', false)
  .action(async (projectRoot, opts) => {
    try {
      const plan = await runTtrlMigration(projectRoot, { dryRun: opts.dryRun ?? false });
      if (opts.dryRun) console.log('=== DRY RUN — no files written ===\n');

      console.log(`.ttrg files migrated: ${plan.results.length}`);
      for (const r of plan.results) {
        const note = r.viewportDropped ? '  (viewport dropped — not part of .ttrl v1)' : '';
        console.log(`  ${r.ttrgPath} -> ${r.sidecarPath} (${r.nodeCount} node${r.nodeCount === 1 ? '' : 's'})${note}`);
        if (opts.dryRun) console.log(r.diff);
      }

      const reparseFailed = plan.skips.filter((s) => s.reason === 'reparse-failed');
      const parseErrors = plan.skips.filter((s) => s.reason === 'parse-error');
      const noLayout = plan.skips.filter((s) => s.reason === 'no-layout-property' || s.reason === 'no-graph-block');
      console.log(`\nSkipped (already migrated / no layout / not a graph file): ${noLayout.length}`);
      if (opts.verbose) for (const s of noLayout) console.log(`  ${s.ttrgPath}: ${s.reason}`);

      if (parseErrors.length > 0) {
        console.error(`\nParse errors (left untouched): ${parseErrors.length}`);
        for (const s of parseErrors) console.error(`  ${s.ttrgPath}: ${s.detail}`);
      }
      if (reparseFailed.length > 0) {
        console.error(`\nRe-parse verification FAILED — not written: ${reparseFailed.length}`);
        for (const s of reparseFailed) console.error(`  ${s.ttrgPath}: ${s.detail}`);
        process.exit(1);
      }

      console.log(opts.dryRun ? `\nWould write ${plan.results.length * 2} file(s)` : `\nWrote ${plan.results.length * 2} file(s)`);
      process.exit(0);
    } catch (err) {
      console.error('migrate-to-ttrl-sidecar failed:', err);
      process.exit(2);
    }
  });

// `pnpm --filter @tatrman/migrate cli -- <cmd>` forwards the literal `--`
// separator into argv; drop a single leading one so the documented invocation
// (and the built `modeler-migrate` bin) both parse identically.
const argv = process.argv.slice();
const sep = argv.indexOf('--', 2);
if (sep !== -1) argv.splice(sep, 1);
program.parse(argv);