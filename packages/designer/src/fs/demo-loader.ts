// SPDX-License-Identifier: Apache-2.0
import { getRuntimeConfig } from '../runtime-config.js';

export interface DemoFiles {
  rootName: string;
  files: Map<string, string>;
}

export async function loadDemoFiles(demoName: string): Promise<DemoFiles> {
  // under the base path the viewer is served from (ttr-core#171) — `/samples/…` at the root.
  const base = `${getRuntimeConfig().basePath}samples/${demoName}/`;
  const indexRes = await fetch(`${base}index.json`);
  if (!indexRes.ok) throw new Error(`Demo manifest not found: ${base}index.json`);
  const filePaths: string[] = await indexRes.json();

  const files = new Map<string, string>();
  await Promise.all(
    filePaths.map(async (relPath) => {
      const url = `${base}${relPath}`;
      const res = await fetch(url);
      if (!res.ok) throw new Error(`Failed to fetch ${url}`);
      const text = await res.text();
      files.set(relPath, text);
    })
  );

  return { rootName: demoName, files };
}