// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { devNull, tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, test } from 'node:test';
import { canonicalYamlReleaseTag } from './canonical-yaml-release-tag.mjs';

let root;
const gitEnv = { ...process.env, GIT_CONFIG_GLOBAL: devNull, GIT_CONFIG_NOSYSTEM: '1' };
const repositoryVariables = ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE'];
const inherited = new Map(repositoryVariables.map((key) => [key, process.env[key]]));
for (const key of repositoryVariables) delete gitEnv[key];
const git = (...args) => execFileSync('git', args, { cwd: root, stdio: 'pipe', env: gitEnv });
beforeEach(() => {
  // The lookup also launches Git: isolate this test process from an enclosing checkout's overrides.
  for (const key of repositoryVariables) delete process.env[key];
  root = mkdtempSync(join(tmpdir(), 'yaml-release-tag-'));
  git('init', '--quiet');
  git(
    '-c',
    'user.name=Tag test',
    '-c',
    'user.email=tag@example.test',
    'commit',
    '--allow-empty',
    '-qm',
    'base'
  );
});
afterEach(() => {
  rmSync(root, { recursive: true, force: true });
  for (const [key, value] of inherited) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
});

test('uses the internal tag, including annotated release tags', () => {
  git(
    '-c',
    'user.name=Tag test',
    '-c',
    'user.email=tag@example.test',
    'tag',
    '-a',
    'grammar/v0.21.4',
    '-m',
    'internal'
  );
  assert.equal(canonicalYamlReleaseTag(root, '0.21.4'), 'grammar/v0.21.4');
});
test('falls back to the public release tag', () => {
  git('tag', 'grammar/v0.18.5-RELEASE');
  assert.equal(canonicalYamlReleaseTag(root, '0.18.5'), 'grammar/v0.18.5-RELEASE');
});
test('prefers internal when both tags resolve to the same commit', () => {
  git('tag', 'grammar/v0.21.4');
  git(
    '-c',
    'user.name=Tag test',
    '-c',
    'user.email=tag@example.test',
    'tag',
    '-a',
    'grammar/v0.21.4-RELEASE',
    '-m',
    'public'
  );
  assert.equal(canonicalYamlReleaseTag(root, '0.21.4'), 'grammar/v0.21.4');
});
test('refuses conflicting internal and public source commits', () => {
  git('tag', 'grammar/v0.21.4');
  git(
    '-c',
    'user.name=Tag test',
    '-c',
    'user.email=tag@example.test',
    'commit',
    '--allow-empty',
    '-qm',
    'different'
  );
  git('tag', 'grammar/v0.21.4-RELEASE');
  assert.throws(
    () => canonicalYamlReleaseTag(root, '0.21.4'),
    /Conflicting release tags: grammar\/v0.21.4 .*grammar\/v0.21.4-RELEASE/
  );
});
test('names both missing tags and does not resolve a similarly named branch', () => {
  git('branch', 'grammar/v0.21.4');
  assert.throws(() => canonicalYamlReleaseTag(root, '0.21.4'), {
    message: 'Missing release tags: grammar/v0.21.4 and grammar/v0.21.4-RELEASE',
  });
});
