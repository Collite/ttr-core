// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

import { copySamples, listSampleFiles } from './scripts/copy-samples';

const SAMPLE_NAME = 'v1.1-mini';
const samplesSrc = path.resolve(__dirname, `../../samples/${SAMPLE_NAME}`);

function copySamplesPlugin() {
  return {
    name: 'copy-samples',
    // Dev server: serve /samples/<name>/* straight from the source tree, since
    // closeBundle only fires on `vite build`. Without this, the dev server's SPA
    // fallback returns index.html for these requests and the demo loader's
    // JSON.parse chokes on `<!DOCTYPE ...>`.
    configureServer(server: import('vite').ViteDevServer) {
      const prefix = `/samples/${SAMPLE_NAME}`;
      server.middlewares.use(prefix, (req, res, next) => {
        if (!fs.existsSync(samplesSrc)) return next();
        const urlPath = decodeURIComponent((req.url ?? '/').split('?')[0]);
        if (urlPath === '/' || urlPath === '/index.json') {
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify(listSampleFiles(samplesSrc)));
          return;
        }
        const filePath = path.join(samplesSrc, urlPath);
        // Guard against path traversal escaping the samples dir.
        if (
          path.relative(samplesSrc, filePath).startsWith('..') ||
          !fs.existsSync(filePath) ||
          !fs.statSync(filePath).isFile()
        ) {
          return next();
        }
        res.end(fs.readFileSync(filePath));
      });
    },
    closeBundle() {
      const samplesDest = path.resolve(__dirname, `./dist/samples/${SAMPLE_NAME}`);
      if (!fs.existsSync(samplesSrc)) return;
      copySamples(samplesSrc, samplesDest);
    },
  };
}

// Dev-only twin of the container image's same-origin catalog proxy (ttr-core#169): with
// `VELES_PROXY_TARGET=http://localhost:7260`, `/veles/*` on the dev server reaches a local Veles, so
// `?veles=/veles` works without CORS — the same URL shape the deployed viewer uses.
const velesProxyTarget = process.env.VELES_PROXY_TARGET;

export default defineConfig({
  plugins: [react(), copySamplesPlugin()],
  // `/` by default; `/<sub-path>/` for a fixed sub-path build; `./` for the container image, whose
  // base path is chosen at runtime (the entrypoint writes `<base href>` + config.json).
  base: process.env.DESIGNER_BASE_URL ?? '/',
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    ...(velesProxyTarget
      ? { proxy: { '/veles': { target: velesProxyTarget, changeOrigin: true, rewrite: (p: string) => p.replace(/^\/veles/, '') } } }
      : {}),
  },
});