// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import assert from 'node:assert/strict';
import { publishMissingArtifacts } from './family-publish.mjs';
// Never called for PRs or bare (internal) lsp tags.
assert.match(process.env.GITHUB_REF_NAME ?? '', /^lsp\/v\d+\.\d+\.\d+-RELEASE$/);
const folder = resolve(process.argv[2]);
const { artifacts } = JSON.parse(readFileSync(join(folder, 'manifest.json'), 'utf8'));
publishMissingArtifacts(artifacts, folder);
