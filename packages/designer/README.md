# @tatrman/designer

**Studio Viewer** — the open, read-only view of TTR (Tatrman) models (v1: schema renderer), backed by the Tatrman LSP running as a browser Web Worker, or by a Veles catalog (read-only). React 19 + Vite + React Flow (`@xyflow/react`) + Tailwind, with the shared canvas primitives and ELK layout in [`@tatrman/canvas-core`](../canvas-core). Ships as the `ghcr.io/collite/ttr-viewer` container image (see [Container image](#container-image-catalog-mode)). (`@tatrman/designer` is the package/codename; **Studio Viewer** is the product name — FO-33.)

> **Screenshot:** `docs/img/designer.png` (capture from the dev server with `samples/v1.1-mini/` loaded — the screenshot lands here after the first deploy).

## What's in v1

- LSP integration for model loading (browser worker transport, no server round-trip)
- `db` schema rendering: tables + foreign-key edges, ELK `layered` auto-layout
- `er` schema rendering: entities + relations with cardinality glyphs (Crow's foot)
- Inspector panel with symbol details, source-file:line copy, and clickable Referenced-by navigation
- Layout persistence: node positions, per-schema viewport, and display mode round-trip
- Demo mode via `?demo=v1.1-mini` query parameter
- Layout export (browser mode) via the **Export Layout** button
- A container image for catalog-mode deployments next to Veles (`ghcr.io/collite/ttr-viewer`)

## Quick start

```bash
# From the repo root — the workspace deps must exist as dist/ before Vite can
# resolve them. Skipping this step fails with
# "Failed to resolve entry for package @tatrman/canvas-core".
pnpm install
pnpm --filter "@tatrman/designer^..." build   # or: pnpm -r build

cd packages/designer
pnpm run dev      # http://localhost:5173/?demo=v1.1-mini
pnpm run build    # outputs to dist/ (with samples copied via the Vite plugin)
```

## Backend selection (worker vs. server)

Which backend serves the model is **explicit, never sniffed** (P2):

| URL | Backend | Mode |
|---|---|---|
| `http://localhost:5173/` | Worker LSP (this browser) | full edit |
| `http://localhost:5173/?demo=v1.1-mini` | Worker LSP + demo project | full edit |
| `http://localhost:5173/?server=ws://127.0.0.1:7270` | `ttr-designer-server` over WS | **read-only** |
| `http://localhost:5173/?veles=http://localhost:7260` | Veles JSON read API (dev) | **read-only (catalog)** |
| `https://…/?veles=/veles` | Veles behind the viewer's ingress (in-chart) | **read-only (catalog)** |
| `https://…/` (image, `VIEWER_CATALOG_URL`) | the runtime config's catalog (default `<base>veles`) | **read-only (catalog)** |

Precedence (see `src/data/select-data-source.ts`): (1) `?server=<ws-origin>` → the
WS `WsDesignerServerDataSource` (the client appends `/ttrm`); (2) `?veles=<base>` →
the `VelesDataSource` catalog view (SV-P4·S2·T5); (3) the runtime config's `veles`
(`config.json`, written by the container image from `VIEWER_CATALOG_URL`, ttr-core#169);
(4) otherwise the worker path. Anything in the URL wins over the config file.
The `?server=` value must be a **loopback** WS origin (`ws://127.0.0.1…` /
`ws://localhost…`, no path — S24); anything else is a visible error, never a
guess-and-fallback. Combining `?server=`/`?veles=`/`?demo=` is an error (they select
different backends).

The `?veles=` value is EITHER a same-origin absolute path prefix (`/veles`, the
in-chart shape — the browser stays same-origin behind the viewer's ingress, no CORS)
OR a full http(s) origin (`http://localhost:7260`, dev/cross-origin — the operator's
explicit choice, CORS- and auth-gated by Veles). Unlike `?server=`, a non-loopback
origin is allowed here because Veles is the *deployed* catalog service, inherently
remote — see `docs/features/import-schema/...` / the T5 decisions note in this repo.
**Veles read mode is read-only** (a served catalog, not a modeling repo): schema/package
browse + object inspect + search; no editing, no `.ttrg`/layout.

**The address bar** (`src/shell/app-location.ts`, ttr-core#171): once a graph is open the shell
rewrites the URL to `<base>w/<workspace>/s/<subject>`. The rewrite stays under the base path the
viewer is served from, and it keeps the session parameters `?veles=`, `?server=`, `?demo=`,
`?manifest=` and `?lang=`, so a reload or a bookmark reopens the same backend and graph. `?velesToken=`
is the one exception: a dev-only bearer, never written back into a URL. A server backend's workspace
segment is a short name (`veles`, `localhost:7260`), not the raw backend value.

**Server (read-only) mode** talks the `ttrm/*` JSON-RPC protocol to a locally-running
[`ttr-designer-server`](../kotlin/ttr-designer-server/README.md):

```bash
./gradlew :packages:kotlin:ttr-designer-server:run --args='--repo <abs-path-to-repo> --port 7270'
```

The server owns the repo, so there is no landing card / File System Access flow — the
index tree, graph, and read-only search come straight over the socket, and a
`ttrm/modelChanged` notification (file-watch → 200 ms debounce → reload) live-updates
the canvas. `capabilities.edit === false` hides every edit affordance.

## Labels and language (catalog mode)

Against Veles (`?veles=` or the runtime config), the graph is labelled the way the model's authors
labelled it (ttr-core#170): a card reads `displayLabel[lang]`, falling back to the identifier;
attributes / columns are rows of their card (`src/data/structural-graph.ts` folds them via their
DEFINES edges); relations and foreign keys are the edges between cards. The inspector shows the label,
the identifier and qname, the plural, the description (Veles' D7 chain: language → plain form → `en` →
first) and each member with its description. The language (`src/i18n/labels.ts`): `?lang=` → the
header picker's saved choice → the runtime config's `lang` → the browser → `en`; a change remounts the
catalog view in place. The fields come from Veles' JSON read API (ttr-server#185); an older Veles yields
identifiers and relation cards, never an error. The UI chrome itself stays English.

## Loading a project (worker mode)

Two entry points, depending on the browser:

- **Load Project Folder** — hidden `<input webkitdirectory>`; works in all evergreen browsers (Chrome / Edge / Firefox / Safari). Click, select a folder containing `.ttrm` and `modeler.toml` files.
- **Open Folder** — File System Access API (Chromium-only). Direct folder access without the upload dialog. Falls back to the `webkitdirectory` path on unsupported browsers.

## Toggles

- **Schema toggle (`db` / `er`)** — switches which schema's graph is rendered. The graph for the inactive schema is cached in the reducer, so toggling back is instant (no LSP round-trip).
- **Display-mode toggle (`just-names` / `with-types` / `with-constraints`)** — controls how much detail appears inside each entity/table node. Defaults: `with-types` for `db`, `just-names` for `er`. Changing display mode does **not** trigger an auto-layout — only the labels redraw, so positions are preserved.

## Layout persistence

Layouts round-trip through the LSP's `modeler/setLayout` / `modeler/getLayout` custom methods. Behaviour depends on which transport is running:

- **Node mode (VS Code extension):** node positions are written into each `.ttrg` graph file's `layout` block (v1.1 decision D4 — there is no separate `.ttrl` sidecar). The LSP synthesizes the edit; hosts apply it.
- **Browser mode (worker):** the browser has no filesystem. Layouts are kept in an in-memory `Map` keyed by project root and lost on tab close. Use **Export Layout** to download the current `.ttrg` for re-import or to commit alongside the project.

Save triggers (Canvas):

| Event | Debounce |
|---|---|
| `dragfreeon` (user released a dragged node) | 500ms |
| `viewport` (pan / zoom) | 750ms |
| `layoutstop` (auto-layout finished) | immediate |
| Display-mode change | immediate |

On project open, `useLayoutSync` fetches the saved layout and `applyPositions` restores node positions before auto-layout runs — if any positions exist, auto-layout is skipped.

## Demo mode

Open the deployed Designer with `?demo=v1.1-mini` to load the bundled sample project from `/samples/v1.1-mini/` without any upload. The landing card's **Open Demo (v1.1-mini)** button does the same.

The sample is chosen by `SAMPLE_NAME` in [`vite.config.ts`](vite.config.ts) — one name, wired to both the dev-server middleware and the `closeBundle` copy into `dist/samples/`. `?demo=<anything-else>` is not served: the request hits the SPA fallback and the loader's `JSON.parse` fails on the returned HTML. Point `SAMPLE_NAME` at a different `samples/` directory to switch demos.

## Environment variables

Build time:

| Variable | Default | Meaning |
|---|---|---|
| `DESIGNER_BASE_URL` | `/` | Vite's asset base. `/` or a fixed sub-path (`/models/`) for a static host; `./` for the container image, whose base path is chosen at runtime. The worker URL resolves correctly under all three. |
| `VELES_PROXY_TARGET` | unset | Dev server only: proxy `/veles/*` to this Veles origin (e.g. `http://localhost:7260`), so `?veles=/veles` works without CORS, the same shape the image uses. |

Runtime: `config.json` beside `index.html`, read once before the first render (`src/runtime-config.ts`).
Absent ⇒ the defaults (build base, no catalog, browser language).

```json
{ "basePath": "/models/", "veles": "/models/veles", "lang": "en", "languages": ["en", "cs"] }
```

Local serve of the built site:

```bash
DESIGNER_BASE_URL='/' pnpm run build
npx http-server dist -p 8080
# open http://localhost:8080/?demo=v1.1-mini
```

## Container image (catalog mode)

`Dockerfile` builds `ghcr.io/collite/ttr-viewer`: this bundle (built with `DESIGNER_BASE_URL=./`) behind
unprivileged nginx on port 8080, with `/healthz`, for linux/amd64 + linux/arm64. `docker/entrypoint.sh`
reads the environment when the container starts and renders `config.json`, `index.html` (with
`<base href>`) and the nginx config into `/tmp`. `VIEWER_CATALOG_UPSTREAM` turns on a same-origin proxy
at `<base>veles/` that forwards Veles' read routes and nothing else (`docker/catalog-proxy.conf.template`).

- Build locally from the repo root: `just viewer-image` (needs `docker login ghcr.io` — the base images
  are private GHCR mirrors).
- Release: `just publish viewer` pushes a `viewer/v<x.y.z>` tag; `.github/workflows/release-viewer.yml`
  smoke-tests and pushes the image.
- Deploying it (configuration, the read-only proxy, the cookie-session auth seam, reference configs):
  [`docs-site/docs/operate/studio-viewer.md`](../../docs-site/docs/operate/studio-viewer.md).

## Architecture notes

- LSP runs in a browser Web Worker (`@tatrman/lsp/browser?worker`). The Designer never owns model state directly — it sends LSP requests and renders the responses.
- The "Export Layout" button is only shown when `transportKind === 'browser'`. The VS Code extension has direct filesystem access via the LSP server, so the affordance isn't needed there.
- Phase-3 reducer state lives in `src/state/designer-reducer.ts`; LSP integration is split into `useProjectGraph` (fetches `getModelGraph` per active schema) and `useLayoutSync` (load on project open).

## Embedding the Designer (v1.x — not yet)

A future v1.x release will publish a `<script>`-tag embed for hosting the Designer inside third-party documentation sites. The wire-up is described in [docs/v1/design/architecture.md](../../docs/v1/design/architecture.md) §10 and [docs/v1/plan/implementation-plan.md](../../docs/v1/plan/implementation-plan.md) §11. Out of scope for Phase 3.
