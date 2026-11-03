# AGENTS.md

Scratchpad of durable, hard-won notes for agents and humans working on this
codebase. Bullet points only — explain *what's wrong, what's right, why*.
Organised by topic so individual sections can be lifted out as standalone
skills later.

---

## Vite + Web Worker + workspace packages

### `new URL('../relative/path', import.meta.url)` only works for files under the dev-server root

- The pattern `new Worker(new URL('./worker.js', import.meta.url))` is
  Vite-aware *only* when the worker lives under the Vite project root. With
  pnpm workspaces, a worker living in another package
  (`../../lsp/dist/server-browser.js`) resolves to a URL Vite's SPA fallback
  catches — the browser fetches the index.html and the Worker constructor
  fails parsing it as a module.
- Symptom: silent worker death in dev, LSP requests hang, downstream React
  rendering eventually crashes on bad/missing state.

### Use `?worker` query on the package import instead

- `import LspWorker from '@tatrman/lsp/browser?worker';` — Vite resolves the
  bare specifier through its module graph (which respects pnpm-workspace
  exports), then applies `?worker` semantics, then serves the file via
  `/@fs/...` correctly in dev and bundles it as a separate chunk in prod.
- Requires the target file to be self-contained (see next bullet) OR for
  every nested import to be resolvable by Vite too.

### Self-contained worker bundles for cross-package workers

- Esbuild's `--external:<spec>` leaves bare imports in the bundle. Fine for
  Node, broken for browser workers — the browser has no module resolver
  for `@workspace/pkg`, `fuzzysort`, `vscode-languageserver/browser.js`,
  etc.
- Rule of thumb for browser-targeted worker bundles: **only externalize
  Node built-ins** (`node:fs`, `node:path`, `fs/promises`, `fs`, `path`).
  Inline everything else (workspace deps, npm deps, vscode-* packages).
  The dynamic `await import('node:fs')` lines are fine as long as control
  never reaches them at runtime (guard with a flag like `opts.layoutStore`).

### Workers don't show up in normal Vite dep optimization

- Vite's dep pre-bundling does not crawl into worker entry points by
  default. If your worker bundle has external bare imports, Vite won't
  pre-bundle them either — they'll just be 404s at runtime.
- Solution is either `?worker` (Vite processes the file through its
  pipeline) or self-contained bundle (no resolution needed).

---

## Designer canvas

- Moved: the Designer and its canvas packages left this repo for ttr-platform
  (D-33, Studio Free). The three notes that were here described that code
  (workspace deps built before the Vite dev server, the one `SAMPLE_NAME`, a
  mocked render engine hiding render-time bugs); they stay in this file's git
  history.
