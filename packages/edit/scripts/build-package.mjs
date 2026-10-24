// SPDX-License-Identifier: Apache-2.0
import { build } from 'esbuild';
import { generateDtsBundle } from 'dts-bundle-generator';
import { writeFileSync, mkdirSync } from 'node:fs';
// The rest of the workspace publishes in P3. Bundle this package's closure now,
// including declarations, so npm consumers need no private @tatrman packages.
mkdirSync('dist/package', { recursive: true });
await build({ entryPoints: ['src/index.ts'], outfile: 'dist/package/index.js', bundle: true,
  format: 'esm', platform: 'browser', target: 'es2022', external: ['node:fs', 'node:path', 'fs/promises', 'fs', 'path'],
  banner: { js: '// SPDX-License-Identifier: Apache-2.0' }, legalComments: 'inline' });
const [types] = generateDtsBundle([{ filePath: 'src/index.ts',
  libraries: { inlinedLibraries: ['@tatrman/parser', '@tatrman/semantics', '@tatrman/format',
    '@tatrman/sql', '@tatrman/grammar', '@tatrman/md-catalog'], allowedTypesLibraries: [] },
  output: { noBanner: true, exportReferencedTypes: false },
}], { preferredConfigPath: 'tsconfig.json' });
if (/from ['"]@tatrman\//.test(types)) throw new Error('Private workspace type escaped into the package.');
writeFileSync('dist/package/index.d.ts', '// SPDX-License-Identifier: Apache-2.0\n' + types);
