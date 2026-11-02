// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { chmod, lstat, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { unifiedDiff } from './text-diff.js';

export interface YamlMigrationOptions {
  from: string;
  to: string;
  /** Executable Kotlin bridge launcher (installDist); executed directly, without a shell. */
  bridge: string;
  dryRun?: boolean;
  timeoutMs?: number;
}

export interface YamlMigrationWrite {
  path: string;
  before: string;
  after: string;
  diff: string;
}

/** Kotlin alone reads YAML and validates markers/versions. TypeScript owns the file-write policy. */
export async function runYamlMigration(
  files: string[],
  options: YamlMigrationOptions
): Promise<{ writes: YamlMigrationWrite[] }> {
  if (!files.length) throw new Error('At least one YAML file is required');
  if (!options.bridge)
    throw new Error('A Kotlin bridge executable is required (--bridge or TTR_YAML_BRIDGE)');
  const paths = files.map((file) => resolve(file));
  const writes: YamlMigrationWrite[] = [];
  const modes = new Map<string, number>();
  const identities = new Map<string, string>();
  // Reject duplicate identities before any bridge call, including directory symlink aliases.
  for (const path of paths) {
    const stat = await lstat(path);
    if (!stat.isFile() || stat.nlink !== 1)
      throw new Error(`${path}: expected a regular file with one link`);
    const identity = `${stat.dev}:${stat.ino}`;
    const previous = identities.get(identity);
    if (previous) throw new Error(`Duplicate migration file: ${previous} and ${path}`);
    identities.set(identity, path);
    modes.set(path, stat.mode);
  }
  // Validate every input before staging or replacing any file, including during a dry run.
  for (const path of paths) {
    const before = decodeUtf8(path, await readFile(path));
    let response: unknown;
    try {
      const stdout = execFileSync(options.bridge, [], {
        input: JSON.stringify({
          operation: 'migrate',
          from: options.from,
          to: options.to,
          source: before,
        }),
        encoding: 'utf-8',
        maxBuffer: 64 * 1024 * 1024,
        timeout: options.timeoutMs ?? 60_000,
        stdio: ['pipe', 'pipe', 'pipe'],
      });
      response = JSON.parse(stdout);
    } catch (error) {
      const detail = error as {
        stderr?: Buffer | string;
        message?: string;
        signal?: string;
        code?: string;
        status?: number;
      };
      const causes: string[] = [];
      if (detail.code === 'ETIMEDOUT')
        causes.push(`bridge timed out after ${(options.timeoutMs ?? 60_000) / 1000} s`);
      if (detail.code === 'ENOBUFS') causes.push('bridge output exceeded maxBuffer (ENOBUFS)');
      if (detail.signal) causes.push(`bridge killed by ${detail.signal}`);
      if (typeof detail.status === 'number' && detail.status !== 2)
        causes.push(`bridge exited ${detail.status}`);
      const diagnostic = detail.stderr?.toString().trim();
      throw new Error(
        `${path}: ${[diagnostic, ...causes].filter(Boolean).join('; ') || detail.message || 'Kotlin bridge failed'}`
      );
    }
    if (
      !response ||
      typeof response !== 'object' ||
      Array.isArray(response) ||
      Object.keys(response).sort().join(',') !== 'changed,source' ||
      typeof (response as { source?: unknown }).source !== 'string' ||
      typeof (response as { changed?: unknown }).changed !== 'boolean'
    )
      throw new Error(`${path}: invalid Kotlin bridge response`);
    const { source: after, changed } = response as { source: string; changed: boolean };
    if (changed !== (before !== after))
      throw new Error(`${path}: inconsistent Kotlin bridge changed flag`);
    if (changed)
      writes.push({
        path,
        before,
        after,
        diff: options.dryRun ? unifiedDiff(path, before, after) : '',
      });
  }
  if (options.dryRun) return { writes };
  const staged: { write: YamlMigrationWrite; temp: string }[] = [];
  try {
    // Same-directory temp + rename keeps each replacement atomic. Batch validation is all-or-nothing;
    // filesystem failures during the replacement phase are reported and may require re-running the batch.
    for (const write of writes) {
      const temp = `${write.path}.migrate-${randomUUID()}`;
      staged.push({ write, temp });
      await writeFile(temp, write.after, {
        encoding: 'utf-8',
        flag: 'wx',
        // Keep a private input's temporary copy private until chmod restores its exact mode.
        mode: modes.get(write.path),
      });
      await chmod(temp, modes.get(write.path)!);
    }
    const checkUnchanged = async (write: YamlMigrationWrite) => {
      const stat = await lstat(write.path);
      const current = decodeUtf8(write.path, await readFile(write.path));
      if (!stat.isFile() || stat.nlink !== 1 || current !== write.before) {
        throw new Error(`${write.path}: input changed while planning migration`);
      }
    };
    for (const { write } of staged) await checkUnchanged(write);
    for (const { write, temp } of staged) {
      await checkUnchanged(write);
      await rename(temp, write.path);
    }
  } finally {
    await Promise.all(staged.map(({ temp }) => rm(temp, { force: true })));
  }
  return { writes };
}

function decodeUtf8(path: string, bytes: Uint8Array): string {
  try {
    return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
  } catch {
    throw new Error(`${path}: not valid UTF-8`);
  }
}
