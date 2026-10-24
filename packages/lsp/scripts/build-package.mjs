// SPDX-License-Identifier: Apache-2.0
import { build } from 'esbuild';
import { generateDtsBundle } from 'dts-bundle-generator';
import { cpSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
const manifest = JSON.parse(readFileSync('package.json', 'utf8'));
const outdir = 'dist/package';
rmSync(outdir, { recursive: true, force: true });
mkdirSync(outdir, { recursive: true });
const external = Object.keys(manifest.dependencies);
await build({
  entryPoints: ['src/index.ts', 'src/server.ts', 'src/server-stdio.ts'],
  outdir,
  bundle: true,
  platform: 'node',
  format: 'esm',
  target: 'es2022',
  external,
  legalComments: 'inline',
  banner: { js: '// SPDX-License-Identifier: Apache-2.0' },
});
cpSync('dist/browser.worker.js', `${outdir}/browser.worker.js`);
cpSync('dist/server-browser.js', `${outdir}/server-browser.js`);
cpSync('../semantics/src/stock', `${outdir}/stock`, { recursive: true });
const privateTypes = [
  'parser',
  'semantics',
  'format',
  'sql',
  'grammar',
  'md-catalog',
  'lint',
  'edit',
].map((n) => `@tatrman/${n}`);
for (const name of ['index', 'server', 'server-stdio', 'server-browser']) {
  const [types] = generateDtsBundle(
    [
      {
        filePath: `src/${name}.ts`,
        libraries: {
          inlinedLibraries: privateTypes,
          allowedTypesLibraries: ['node'],
        },
        output: { noBanner: true, exportReferencedTypes: false },
      },
    ],
    { preferredConfigPath: 'tsconfig.json' }
  );
  if (/['"]@tatrman\//.test(types)) throw new Error(`Private declaration dependency in ${name}`);
  writeFileSync(`${outdir}/${name}.d.ts`, '// SPDX-License-Identifier: Apache-2.0\n' + types);
}
