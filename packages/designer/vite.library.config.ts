// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('.', import.meta.url));
let workerReference: string;
export default defineConfig({
  plugins: [
    react(),
    {
      name: 'prebuilt-worker',
      enforce: 'pre',
      buildStart() {
        workerReference = this.emitFile({
          type: 'asset',
          fileName: 'browser.worker.js',
          source: readFileSync(resolve(root, '../lsp/dist/browser.worker.js')),
        });
      },
      resolveId(id, importer) {
        if (id === './worker-url.js' && importer?.endsWith('/lsp-client.ts'))
          return '\0ttr-prebuilt-worker';
      },
      load(id) {
        if (id === '\0ttr-prebuilt-worker')
          return `export default import.meta.ROLLUP_FILE_URL_${workerReference};`;
      },
      resolveFileUrl({ fileName }) {
        return `new URL(${JSON.stringify('./' + fileName)}, import.meta.url).href`;
      },
    },
  ],
  build: {
    outDir: 'dist/library',
    lib: {
      entry: {
        index: resolve(root, 'src/library-entry.ts'),
        extensions: resolve(root, 'src/extensions.ts'),
      },
      formats: ['es'],
      cssFileName: 'designer',
    },
    rollupOptions: {
      external: (id) => /^(react|react-dom)(\/|$)/.test(id) || /^@tatrman\//.test(id),
      output: { entryFileNames: '[name].js' },
    },
    sourcemap: true,
  },
});
