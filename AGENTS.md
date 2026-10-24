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

### Workspace deps must be built before the Vite dev server starts

- `packages/designer` imports `@tatrman/canvas-core`, `@tatrman/lsp`,
  `@tatrman/perspectives`, `@tatrman/tokens`, and `@tatrman/deep-links`
  through their `dist/` entry points (`"main": "./dist/index.js"`), so
  `pnpm install` alone leaves them unresolvable — `pnpm run dev` dies at
  dep-scan with `Failed to resolve entry for package "@tatrman/canvas-core"`.
- Run `pnpm --filter "@tatrman/designer-core^..." build` (or `pnpm -r build`) from
  the repo root once after cloning or after cleaning `dist/`.

### One `SAMPLE_NAME` drives both the dev server and the build

- `packages/designer/vite.config.ts` holds a single `SAMPLE_NAME` const wired
  to *both* the dev middleware serving `/samples/<name>/*` and the
  `closeBundle` copy into `dist/samples/`. `?demo=<any-other-name>` is not
  served: the request falls through to the SPA fallback and the demo loader's
  `JSON.parse` chokes on the returned `<!DOCTYPE …>`.
- Switching demos means editing that const, not just the URL.

### A mocked render engine hides every render-time bug

- Mocking the canvas engine (React Flow, ELK) in a unit test makes the test
  blind to everything only the real engine does: a node measured at 0×0, a
  layout that never settles, an edge pointing at a handle id that doesn't
  exist, a graph shape the engine renders as a pile of boxes. Mocked tests are
  necessary, not sufficient.
- Either exercise a real instance against a fixture (jsdom runs real React
  Flow once `src/test-setup.ts` stubs `ResizeObserver` and `matchMedia`), or
  budget for render-time bugs being found by looking at the running app — the
  dev server, or the built bundle / container image in a real browser.
