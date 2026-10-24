// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
// Inspect every coordinate first. A network/auth error must not start a partial cut.
export function publishMissingArtifacts(artifacts, folder, run = execFileSync) {
  const missing = artifacts.filter((a) => {
    let output;
    try {
      output = run(
        'npm',
        [
          'view',
          `${a.name}@${a.version}`,
          'version',
          '--json',
          '--registry=https://registry.npmjs.org',
        ],
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }
      );
    } catch (error) {
      let details;
      try {
        details = JSON.parse(String(error.stdout || error.stderr));
      } catch {
        throw error;
      }
      if (details.error?.code === 'E404') return true;
      throw error;
    }
    if (JSON.parse(String(output)) !== a.version)
      throw new Error(`Unexpected registry version for ${a.name}@${a.version}`);
    console.log(`Reusing published ${a.name}@${a.version}`);
    return false;
  });
  for (const a of missing)
    run(
      'npm',
      [
        'publish',
        join(folder, a.file),
        '--access',
        'public',
        '--registry=https://registry.npmjs.org',
      ],
      { stdio: 'inherit' }
    );
}
