// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process';

/** Internal and public cuts use the same version; schema expectations must name its immutable source. */
export function canonicalYamlReleaseTag(root, version) {
  const internal = `grammar/v${version}`;
  const published = `${internal}-RELEASE`;
  const commit = (tag) => {
    try {
      return execFileSync('git', ['rev-parse', '--verify', `refs/tags/${tag}^{commit}`], {
        cwd: root,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
      }).trim();
    } catch {
      return undefined;
    }
  };
  const internalCommit = commit(internal);
  const publishedCommit = commit(published);
  if (internalCommit && publishedCommit && internalCommit !== publishedCommit) {
    throw new Error(
      `Conflicting release tags: ${internal} (${internalCommit}) and ${published} (${publishedCommit})`
    );
  }
  if (internalCommit) return internal;
  if (publishedCommit) return published;
  throw new Error(`Missing release tags: ${internal} and ${published}`);
}
