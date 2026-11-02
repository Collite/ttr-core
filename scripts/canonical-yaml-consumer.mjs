#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { canonicalYamlReleaseTag } from './canonical-yaml-release-tag.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const [version, flag, repository] = process.argv.slice(2);
if (
  !/^\d+\.\d+\.\d+(?:-SMOKE)?$/.test(version ?? '') ||
  (flag && (flag !== '--repository' || !repository)) ||
  (version?.endsWith('-SMOKE') && !repository)
) {
  throw new Error(
    'Usage: node scripts/canonical-yaml-consumer.mjs <major.minor.patch> [--repository <path>]'
  );
}
const scratch = join(root, 'build', 'canonical-yaml-consumers');
mkdirSync(scratch, { recursive: true });
const consumer = mkdtempSync(join(scratch, 'consumer-'));
cpSync(join(root, 'tests/consumers/canonical-yaml'), consumer, { recursive: true });
if (version.endsWith('-SMOKE')) {
  cpSync(join(root, 'packages/grammar/schemas'), join(consumer, 'schemas'), { recursive: true });
} else {
  const tag = canonicalYamlReleaseTag(root, version);
  mkdirSync(join(consumer, 'schemas'));
  for (const file of ['model-yaml.schema.json', 'profile.schema.json', 'canonical-metadata.json']) {
    const bytes = execFileSync('git', ['show', `${tag}:packages/grammar/schemas/${file}`], {
      cwd: root,
      maxBuffer: 64 * 1024 * 1024,
    });
    writeFileSync(join(consumer, 'schemas', file), bytes);
  }
}
const env = { ...process.env };
if (!repository) {
  env.GITHUB_ACTOR ||= execFileSync('gh', ['api', 'user', '--jq', '.login'], {
    encoding: 'utf8',
  }).trim();
  env.GITHUB_TOKEN ||= execFileSync('gh', ['auth', 'token'], { encoding: 'utf8' }).trim();
}
execFileSync(
  join(root, 'gradlew'),
  [
    '--no-daemon',
    '--max-workers=2',
    '--refresh-dependencies',
    '-p',
    consumer,
    `-PcodecVersion=${version}`,
    ...(repository ? [`-PartifactRepo=${resolve(repository)}`] : []),
    'run',
  ],
  { cwd: consumer, env, stdio: 'inherit' }
);
console.log(`Clean registry consumer: ${version}; ${consumer}`);
