// SPDX-License-Identifier: Apache-2.0
import { uriFor } from '../fs/source-uri.js';
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
  await client.setProjectRoot(root, manifest, { deferDiagnostics: true });
  const paths = [...files.keys()].filter(
    (p) => isModelFile(p) && (!opts?.under || p.startsWith(opts.under))
  );
  // The removal guard reads JS roster bytes, but the worker must never parse them as TTR.
  const rosters = [...files.keys()].filter(p => /^model\/tests\/.+\.mjs$/.test(p));
  try {
    await Promise.all(
      [...paths, ...rosters].map((p) => client.openDocument(uriFor(root, p), files.get(p)!))
    );
  } finally {
    // Close the batch even if an open fails: local folder loaders may reuse their client.
    // References resolve against the complete symbol universe at this notification barrier;
    // final project diagnostics replace all partial results before catalog reads start.
    const result = await client.setProjectRoot(root, manifest);
    if (result?.manifest) opts?.onManifest?.(result.manifest);
  }
  return paths;
}
