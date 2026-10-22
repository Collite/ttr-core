// SPDX-License-Identifier: Apache-2.0
import type { LspClient } from '../lsp-client.js';

export function isModelFile(path: string): boolean {
  return !path.startsWith('model/layouts/') && /\.ttr[mg]$/.test(path);
}

export async function openFileMap(
  client: LspClient,
  root: string,
  files: ReadonlyMap<string, string>,
  opts?: { under?: string }
): Promise<string[]> {
  const manifest = files.get('modeler.toml');
  await client.setProjectRoot(root, manifest);
  const paths = [...files.keys()].filter(
    (p) => isModelFile(p) && (!opts?.under || p.startsWith(opts.under))
  );
  await Promise.all(
    paths.map((p) => client.openDocument(`${root.replace(/\/$/, '')}/${p}`, files.get(p)!))
  );
  // Like loadProjectIntoSymbols' two-pass load: references can resolve only after all symbols exist.
  // This request is also the notification barrier and republishes final cross-file diagnostics.
  await client.setProjectRoot(root, manifest);
  return paths;
}
