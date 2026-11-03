// SPDX-License-Identifier: Apache-2.0
// fs wrapper for the .ttrg → .ttrl migration (T2, TP-5). Walks a project root,
// plans every `.ttrg` file via `planTtrlExtraction` (pure), and — unless
// dryRun — writes the sidecar + the stripped `.ttrg` as one paired write per
// file (never one without the other).

import { planTtrlExtraction, isSkip, type TtrlMigrationResult, type TtrlMigrationSkip } from './ttrl-migrate.js';

// `dist`/`build` are gitignored generated output (caught at T5's real run: a stale
// bundled copy of the v1.1-mini samples under the Designer's dist/ (then in this repo) was found and
// would have been "migrated" as if it were source — never touch generated output).
const SKIP_DIRS = new Set(['.modeler', 'node_modules', '.git', 'dist', 'build']);

export interface TtrlMigrationPlan {
  results: TtrlMigrationResult[];
  skips: TtrlMigrationSkip[];
}

/** Plan over an in-memory file set (pure — no fs). */
export function planTtrlMigration(files: Array<{ path: string; text: string }>): TtrlMigrationPlan {
  const results: TtrlMigrationResult[] = [];
  const skips: TtrlMigrationSkip[] = [];
  for (const file of files) {
    if (!file.path.endsWith('.ttrg')) continue;
    const r = planTtrlExtraction(file.path, file.text);
    if (isSkip(r)) skips.push(r);
    else results.push(r);
  }
  return { results, skips };
}

/** Walk a project root, plan, and (unless dryRun) write every sidecar + stripped .ttrg pair. */
export async function runTtrlMigration(projectRoot: string, opts: { dryRun?: boolean } = {}): Promise<TtrlMigrationPlan> {
  const { readFile, writeFile } = await import('node:fs/promises');
  const files: Array<{ path: string; text: string }> = [];
  await walk(projectRoot, files, readFile);
  const plan = planTtrlMigration(files);

  if (!opts.dryRun) {
    for (const r of plan.results) {
      await writeFile(r.ttrgPath, r.ttrgAfter, 'utf-8');
      await writeFile(r.sidecarPath, r.ttrl, 'utf-8');
    }
  }
  return plan;
}

async function walk(
  dir: string,
  out: Array<{ path: string; text: string }>,
  readFile: (typeof import('node:fs/promises'))['readFile'],
): Promise<void> {
  const { readdir } = await import('node:fs/promises');
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
      if (SKIP_DIRS.has(entry.name)) continue;
      await walk(full, out, readFile);
    } else if (entry.isFile() && entry.name.endsWith('.ttrg')) {
      out.push({ path: full, text: await readFile(full, 'utf-8') });
    }
  }
}
