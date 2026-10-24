# HOWTO

End-user-flavoured instructions for the two shipping artefacts: the Designer and the VS Code extension. For developer-facing docs (architecture, contributing, plan), see [`docs/`](docs/) and the per-package READMEs.

> **Prerequisites for everything below:** Node.js 20+, pnpm 11+ (the repo pins `pnpm@11.1.3` via `packageManager` — Corepack picks it up automatically), and a clone of this repo with `pnpm install` **and `pnpm -r build`** run once. `pnpm install` alone is not enough: the Designer imports workspace packages (`@tatrman/canvas-core`, `@tatrman/lsp`, …) through their `dist/` entry points, so an unbuilt checkout fails at startup with `Failed to resolve entry for package "@tatrman/canvas-core"`.

---

## 1. Running the Designer

The Designer is a static React app. Three ways to run it.

### 1.1 Local dev server (hot reload)

```bash
pnpm --filter "@tatrman/designer^..." build   # once — builds the workspace deps into dist/
pnpm --filter @tatrman/designer dev
```

Opens [http://localhost:5173](http://localhost:5173). Vite watches for source changes and reloads.

* **Load a project**: click **Load Project Folder** (works in all evergreen browsers) and select a folder containing `.ttrm` files plus a `modeler.toml` — for example, `samples/v1.1-mini/` from this repo. Chromium-based browsers can also use **Open Folder** (File System Access API) for direct folder access.
* **Try the demo**: click **Open Demo (v1.1-mini)** on the landing card, or visit [http://localhost:5173/?demo=v1.1-mini](http://localhost:5173/?demo=v1.1-mini).

> `v1.1-mini` is the only sample the dev server serves — `SAMPLE_NAME` in `packages/designer/vite.config.ts` selects it, and requests for any other `/samples/<name>/` fall through to the SPA fallback (the demo loader then chokes on `<!DOCTYPE …>`). The older flat-layout `samples/v1-metadata/` predates the v1.1 `package` declarations; use it only as a v1-format reference.

### 1.2 Local production build (serve the static bundle)

```bash
pnpm --filter @tatrman/designer build      # outputs to packages/designer/dist/
npx http-server packages/designer/dist -p 8080
```

Open [http://localhost:8080/?demo=v1.1-mini](http://localhost:8080/?demo=v1.1-mini). The build includes the bundled LSP web worker and the sample project copied into `dist/samples/v1.1-mini/`.

### 1.3 The deployed Viewer (container image)

The Designer is not published to GitHub Pages: repo Pages serves the docs site (`docs-publish.yml`). The deployable form is the **Studio Viewer image**, `ghcr.io/collite/ttr-viewer`, a static bundle behind unprivileged nginx that reads the model a Veles catalog serves. `release-viewer.yml` publishes it from a `viewer/v<x.y.z>` tag (`just publish viewer`). Its base path, catalog URL and default language are environment variables read when the container starts, so one image serves any deployment.

How to run it, configure it, and put it behind a login: [Deploy the Studio Viewer](docs-site/docs/operate/studio-viewer.md).

To build and try it locally against a Veles on your machine:

```bash
gh auth token | docker login ghcr.io -u <github-user> --password-stdin   # the base images are private GHCR mirrors
just viewer-image                                                         # → ttr-viewer:dev
docker run --rm -p 8080:8080 -e VIEWER_CATALOG_UPSTREAM=http://host.docker.internal:7260 ttr-viewer:dev
```

Then open [http://localhost:8080/](http://localhost:8080/).

### 1.4 What the Designer does

* **Schema toggle (`db` / `er`)** — switch which schema's graph is rendered. The other schema's graph is cached, so toggling back is instant.
* **Display-mode toggle (`just-names` / `with-types` / `with-constraints`)** — controls how much detail appears in each node. Doesn't trigger an auto-layout; positions are preserved.
* **Inspector panel** — click any node or edge to see its details, source `file:line` (clickable to copy), tags, and "Referenced by" links (clickable to navigate).
* **Layout persistence** — positions, viewport, and display mode round-trip to disk in Node mode (VS Code workspace) via `<project>/.modeler/layout.ttrl`. In browser mode they stay in memory; use **Export Layout** in the header to download the current `.ttrl` for re-import or to commit alongside your project.

Edit mode (round-tripping graph edits back into `.ttrm` text) is **not** in v1 — the Designer is read-only.

---

## 2. The VS Code extension

The extension is a thin shim around the shared `@tatrman/lsp` server. All language understanding (diagnostics, hover, go-to-def, find-references, workspace symbols) lives in the LSP.

### 2.1 Run the extension from this repo (development host)

This is the simplest path and the one most contributors use.

1. `pnpm install && pnpm -r build` (once, after cloning).
2. Open `packages/vscode-ext` in VS Code (not the repo root — the folder containing the extension's `package.json`).
3. Press **F5**. VS Code opens a second window labelled **[Extension Development Host]** with this version of the extension loaded.
4. In that second window, open any `.ttrm` file (e.g. from `samples/v1.1-mini/`) to exercise highlighting and the LSP.

After pulling a new version of the repo:

```bash
git pull
pnpm install                      # picks up any new deps
pnpm -r build                     # rebuilds @tatrman/lsp + ttr-modeler-vsc
```

Then back in VS Code: stop the running Extension Development Host (close the window or `⇧F5`) and press **F5** again. The new build is loaded.

### 2.2 Install the dev build into your real VS Code

If you want the extension active in your everyday editor (not just the development host), point VS Code at the source folder directly.

```bash
# Build first.
pnpm -r build

# Install by symlink so 'git pull && pnpm -r build' is enough to refresh.
ln -s "$(pwd)/packages/vscode-ext" ~/.vscode/extensions/modeler-vscode-ext-dev
```

Restart VS Code (or run **Developer: Reload Window** from the command palette). The extension is now active for every window, not just the dev host. **To refresh** after a code change, run `pnpm -r build` and then **Developer: Reload Window** in VS Code.

Removing the dev install:

```bash
rm ~/.vscode/extensions/modeler-vscode-ext-dev
```

Then reload VS Code.

> On Windows the install location is `%USERPROFILE%\.vscode\extensions\` and you need a directory junction (`mklink /J`) instead of a symlink. WSL users: install on the WSL side under `~/.vscode-server/extensions/`.

### 2.3 Distributing the extension to other users

> ⚠ **Not yet production-ready.** The current build is wired for the dev cycle (LSP resolved via pnpm workspace symlinks). Producing a sharable `.vsix` that runs on a machine without this repo cloned needs two pieces of work that **have not been done yet**:
>
> 1. **Bundle the LSP into the extension.** `extension.ts` does `require.resolve('@tatrman/lsp/server-stdio')`, which only resolves because pnpm has symlinked `@tatrman/lsp` into `packages/vscode-ext/node_modules/`. A `.vsix` won't contain that symlink, and the LSP bundle itself externalizes `@tatrman/parser`, `@tatrman/semantics`, `@tatrman/edit` (see `packages/lsp/package.json` `bundle-stdio` script), so the LSP can't be shipped as-is either. The cleanest fix is to extend `bundle-stdio` to drop the workspace `--external:` flags so the LSP becomes a self-contained ESM bundle, then copy that bundle into `packages/vscode-ext/dist/` at build time and point `extension.ts` at the local copy.
> 2. **Add a publisher.** `packages/vscode-ext/package.json` has no `publisher` field; `vsce package` will refuse to package without one (or you must pass `--no-rewrite-relative-links` workarounds). Decide on a marketplace publisher id (created via [aka.ms/vscode-create-publisher](https://aka.ms/vscode-create-publisher)) and add `"publisher": "<id>"` to `package.json`.

Once those two pieces land, packaging follows the standard VS Code flow:

```bash
# One-time, globally:
pnpm dlx @vscode/vsce --version    # or: npm install -g @vscode/vsce

# Per release:
pnpm -r build
cd packages/vscode-ext
pnpm dlx @vscode/vsce package      # produces tatrman-modeler-0.1.0.vsix
```

The resulting `.vsix` is a single file that other users can install in three ways:

**A. From the VS Code UI:**
1. **Extensions** panel (`⇧⌘X` / `Ctrl+Shift+X`).
2. Click the `…` menu at the top of the panel → **Install from VSIX…**.
3. Pick the `.vsix` file.
4. Reload when prompted.

**B. From the command palette:**
1. **Extensions: Install from VSIX…**
2. Pick the `.vsix`.

**C. From the command line:**
```bash
code --install-extension path/to/tatrman-modeler-0.1.0.vsix
```

To **refresh users to a newer version**, ship them the new `.vsix` and either:
- they run `code --install-extension path/to/tatrman-modeler-0.2.0.vsix` (VS Code replaces the old version), or
- they use **Install from VSIX…** again with the newer file.

VS Code surfaces the new version in the Extensions panel after a reload; there's no automatic update path for sideloaded `.vsix` files. If you want automatic updates, publish to the [VS Code Marketplace](https://marketplace.visualstudio.com/) (`vsce publish`) or to [Open VSX](https://open-vsx.org/) — both require the same publisher setup as packaging.

### 2.4 Confirming the extension works end-to-end

The repo includes a headless smoke suite that boots a real VS Code instance and exercises five LSP behaviours (language detection, clean diagnostics, go-to-definition, unresolved-reference diagnostic, workspace symbols):

```bash
pnpm --filter ttr-modeler-vsc test:smoke
# expected: TC1–TC5 all ✓
```

First run downloads ~130 MB of Electron into `packages/vscode-ext/.vscode-test/`; subsequent runs reuse the cached install. On Linux CI runners with no display, prefix with `xvfb-run -a`.

If you're suspicious that an extension change broke something, run `test:smoke` before reloading the dev host — it's the same set of asserts you'd have manually clicked through.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Designer landing card never appears in dev mode | LSP worker failed to load — check the browser console | `pnpm --filter @tatrman/lsp build` and reload |
| Designer demo button shows "Failed to load demo: Demo manifest not found" | `samples/v1.1-mini/index.json` missing from the build output, or a `?demo=` name other than `SAMPLE_NAME` | `pnpm --filter @tatrman/designer build` (the Vite plugin writes it on `closeBundle`); in dev, use `?demo=v1.1-mini` |
| Dev server exits with `Failed to resolve entry for package "@tatrman/canvas-core"` | Workspace deps never built — Vite resolves them through `dist/`, which `pnpm install` does not create | `pnpm --filter "@tatrman/designer^..." build` from the repo root, then re-run `dev` |
| F5 in `packages/vscode-ext` doesn't open the dev host | Build artefacts missing | `pnpm -r build` first; F5 uses `dist/extension.js` |
| Extension Development Host loads but `.ttrm` files show no diagnostics | LSP server failed to start — open **Output → TTR Language Server** to see why | Most often: forgot to `pnpm --filter @tatrman/lsp build` after pulling |
| Symlink dev install isn't picked up | VS Code didn't pick up the symlink at startup | **Developer: Reload Window**; if still missing, restart VS Code entirely |
| `code --install-extension` reports "EACCES" | Permission on `~/.vscode/extensions/` | `chown -R "$USER" ~/.vscode/extensions/` |
| Smoke test times out on first run | Electron still downloading | Wait it out, or pre-download with `pnpm exec node -e "require('@vscode/test-electron').downloadAndUnzipVSCode('1.96.0')"` |

---

## Where to go next

* The Designer's own [README](packages/designer/README.md) has more on its toggles, env vars, deploy workflow, and the future embed story.
* The extension's [README](packages/vscode-ext/README.md) documents the smoke test cases and how the package handles the CJS/ESM split with the LSP.
* The [LSP README](packages/lsp/README.md) lists every method (standard + custom `modeler/*`) the server exposes.
* [docs/v1/design/architecture.md](docs/v1/design/architecture.md) §11 has the Designer ↔ LSP control-flow diagram for the deployed shape.

## Export a model (graph.json · defs.json · cn.ttl · xmi)

Install the `ttr-metadata-cli` distribution from the internal grammar bundle, or build locally:

```bash
./gradlew :packages:kotlin:ttr-metadata-cli:installDist
packages/kotlin/ttr-metadata-cli/build/install/ttr-metadata/bin/ttr-metadata export /path/to/world --format graph.json --out /tmp/model-export
```

The checkout must contain `modeler.toml` and `model/`. All formats use the same parsed definitions as Veles `/model/export`.
Each successful export writes its files and `.snapshot` (one version line) under `--out`.

```bash
ttr-metadata export /path/to/world --model world --format defs.json --out /tmp/definitions
ttr-metadata export /path/to/world --model cn --format ttl --profile owl --out /tmp/concepts
ttr-metadata export /path/to/world --format xmi --profile ttr-mdg/1 --out /tmp/ea
ttr-metadata export /path/to/world --format graph.json --since /tmp/previous/graph.json --out /tmp/current
ttr-metadata export /path/to/world --model cn,er --format ttr --out /tmp/ttr --tar
```

`--model` defaults to `world`; selectors are `cn`, `md`, `er`, `db`, `lexicon`, `bindings`, the binding families
`cn2md|cn2er|cn2db|md2er|md2db|er2db`, or comma-separated combinations. `--package` and `--area` scope before projection.
`--format` and `--out` are required. `graph.json`, `defs.json`, `dot`, `ttl`, `xmi` and canonical `ttr` are implemented.
Turtle profiles are `skos`, `owl`, `both` (default); XMI defaults to `ttr-mdg/1`. `yaml`, `ossie`, `uc`, `tmdl`,
`pdm`, `ldm`, `cdm` and XMI `ea-erd/1` are registered for later implementation.

Single files are named `graph.json`, `defs.json`, `model.dot`, `cn.ttl`, or `model.xmi`. TTR writes a directory tree;
`--tar` additionally writes `model.tar.gz` for a bundle. Archives contain zero timestamps. `defs.json` includes only
public connection descriptors (`id`, `database`, `schema`, `locators`) from the snapshot's `connections.toml`.
Authored `renames.yaml` contributes to graph deltas; the CLI reads the previous graph's snapshot and ids.
A delta describes removals from the whole snapshot, even when the new projection is scoped; requesting the current
snapshot yields empty arrays. For complete removal tracking, save a world `graph.json` as the previous input.

Exit codes: `0` success, `1` load/write/sidecar failure, `2` `xm/format-cannot-carry`, `3` `xm/since-unknown`,
`64` usage or a reserved `xm/format-not-built`. Cannot-carry includes MD/ER/DB/bindings/lexicon as Turtle,
lexicon as XMI, and scoped models as DOT (DOT supports `world`). Diagnostics go to stderr.
See [TTR vocabulary](docs/ns/ttr.md) and [EA import and MDG installation](docs/ea.md).
