// SPDX-License-Identifier: Apache-2.0
import type { LspClient } from '../lsp-client.js';

export function isModelFile(path: string): boolean {
  return !path.startsWith('model/layouts/') && /\.ttr[mg]$/.test(path);
}

export async function openFileMap(
  client: LspClient,
  root: string,
  files: ReadonlyMap<string, string>,
  opts?: { under?: string; onManifest?: (manifest: import('@tatrman/lsp').ProjectRootResult['manifest']) => void }
): Promise<string[]> {
  const manifest = files.get('modeler.toml');
  await client.setProjectRoot(root, manifest);
  const paths = [...files.keys()].filter(
    (p) => isModelFile(p) && (!opts?.under || p.startsWith(opts.under))
  );
  // The removal guard reads JS roster bytes, but the worker must never parse them as TTR.
  const rosters = [...files.keys()].filter(p => /^model\/tests\/.+\.mjs$/.test(p));
  await Promise.all(
    [...paths, ...rosters].map((p) => client.openDocument(`${root.replace(/\/$/, '')}/${p}`, files.get(p)!))
  );
  // Like loadProjectIntoSymbols' two-pass load: references can resolve only after all symbols exist.
  // This request is also the notification barrier and republishes final cross-file diagnostics.
  const result = await client.setProjectRoot(root, manifest);
  if (result?.manifest) opts?.onManifest?.(result.manifest);
  return paths;
}
