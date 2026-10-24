# Studio hosting

## Stage 1: same-origin iframe

The viewer image serves a standalone viewer at its configured `VIEWER_BASE_PATH` (default `/`) and
the Studio iframe at `/m/_designer/index.html?backend=host`. Both entries use the same JavaScript,
CSS and worker assets. `/m/_designer/` is reserved for the iframe. A host on another origin is not
supported by this protocol.

Set the iframe height in the host. Hosted roots fill that height, force light form controls, omit the
local-project landing/FSA loader, and do not install a `beforeunload` prompt. The source drawer and structured edit seam are open (SO D-19); canvas forms are supplied by commercial extensions.

Send init from the iframe's `load` handler. Configuration comes from this message, without a
`config.json` round trip. The frame's parent listener is installed before `load` fires.

```js
frame.onload = () => frame.contentWindow.postMessage({
  type: 'ttr.host.init',
  workspace: 'hartland',      // required world name, non-empty and no slash
  basePath: '/m/',             // the parent rail's route prefix, with trailing slash
  api: '/api/model',           // same-origin absolute path, no query or fragment
  token: bearer,               // held in memory; never put a bearer in the iframe URL
  scope: { kind: 'served' },   // or { kind: 'change', id: '42' }
  lang: 'en',                 // en | cs; also reflected into the frame's ?lang=
  theme: 'light',
}, location.origin);
frame.src = '/m/_designer/index.html?backend=host#/';
```

The frame checks both `event.source === window.parent` and `event.origin === location.origin`.
It ignores malformed or untrusted messages. The host must check the same origin and that the source
is this iframe's `contentWindow` before accepting replies. Always supply an explicit target origin.
An iframe session has one API/scope; replace the iframe when switching scopes. Repeated init is
idempotent. After a failed initial load, refresh retries it.

| Direction | Message | Meaning |
|---|---|---|
| Host → frame | `{ type: 'ttr.host.init', basePath, workspace, api, token, scope, lang, theme }` | Fetch, configure worker, open model documents, mount shell. |
| Host → frame | `{ type: 'ttr.host.token', token }` | Replace the bearer for the next fetch, including during initial loading. |
| Host → frame | `{ type: 'ttr.host.navigate', location, sha? }` | Open an app path (`/w/…`, optionally prefixed `#`). If `sha` differs, re-fetch first. |
| Host → frame | `{ type: 'ttr.host.refresh' }` | Re-fetch the current scope. The host owns polling. |
| Frame → host | `{ type: 'ttr.frame.ready' }` | First shell render committed after loading; emitted once per session. Canvas placement may finish afterwards. |
| Frame → host | `{ type: 'ttr.frame.location', location }` | Current app path, initially and when the active view changes. |
| Frame → host | `{ type: 'ttr.frame.error', code: 'host/load-failed', message }` | Snapshot fetch/catalog failure. Response bodies and credentials are not included. |
| Frame → host | `{ type: 'ttr.frame.error', code: 'host/bad-location', message }` | Malformed location; an initial bad hash falls back to the configured workspace and loading continues. |

TypeScript unions are exported from `@tatrman/designer-core`. Dirty/changedFiles/collect messages
are implemented by the P1 save seam described below.

### BFF snapshot

The frame fetches `GET {api}/served/files` or `GET {api}/changes/{encodeURIComponent(id)}/files`
with `Authorization: Bearer <token>`, `Accept: application/json`, and `cache: no-store`. Redirects
are rejected. Return JSON:

```json
{ "sha": "immutable-commit-sha", "files": { "modeler.toml": "…", "model/er/items.ttrm": "…" } }
```

Paths are repository-relative, with no leading slash, backslash, empty segment, `.` or `..` segment.
The map becomes `ProjectFiles { rootName: 'repo', files: Map<path,text> }`. The frame sets the worker
root to `file:///repo` with the root `modeler.toml`, then opens `.ttrm`/`.ttrg` under `model/**` in parallel and sets the root again. This second pass
reindexes references and republishes diagnostics against all loaded symbols, independently of file order.
`model/layouts/**` stays in the file map for the shared layout/sidecar reader.

An unchanged SHA leaves the worker, tabs and cached reads intact. A changed SHA loads a replacement
worker and remounts the shell at the current hash; deleted documents disappear from symbols and
graphs. Fetched SHA contents must be immutable. Refresh does not preserve local layout drafts across
a changed SHA. Fetch/catalog failures retain the previous snapshot; a host-visible error reports the failure.
Navigation accompanying a failed refresh still opens on that previous snapshot. A replacement is
committed only after its catalog loads; the retired worker is disposed after the new shell commits.
Malformed TOML loads with defaults plus `lsp/manifest-invalid` (Error), rather than blanking the frame. The frame does not poll or retry on a timer.

### Route mapping

`basePath` configures the parent route prefix; it does not relocate already loaded assets. The hosted
entry's HTML base stays `/m/_designer/`. Navigation rewrites only its hash, using `replaceState`.
Copy-link/popout URLs point to the parent so a shared link can obtain authentication and init again.

The workspace always comes from `init.workspace`. Empty hashes and `#/` start at `/w/<workspace>`.
Initial and inbound `/w/<other>/…` paths are rewritten to that workspace, keeping the remainder.
A hash with invalid percent encoding posts `host/bad-location`, starts at `/w/<workspace>`, and
continues loading. An init without a valid workspace is ignored. The host owns change-scope rail
wrapping (`/m/c/<changeId>/…`); the frame reports its configured world location.

| Host rail (`basePath: '/m/'`) | Frame hash / `location` payload |
|---|---|
| `/m/hartland` | `#/w/hartland` / `/w/hartland` |
| `/m/hartland/s/md` | `#/w/hartland/s/md` / `/w/hartland/s/md` |
| `/m/hartland/s/layout%3Asales` | `#/w/hartland/s/layout%3Asales` / `/w/hartland/s/layout%3Asales` |
| `/m/hartland/s/er?skin=er.crow` | `#/w/hartland/s/er?skin=er.crow` / `/w/hartland/s/er?skin=er.crow` |
| `/m/hartland/p/lineage?root=…` | `#/w/hartland/p/lineage?root=…` / `/w/hartland/p/lineage?root=…` |

Mirror frame locations by removing the `/w/` prefix and prepending `basePath`. Inbound rail navigation
does the inverse and sends `ttr.host.navigate`. Keep the rail query inside the frame hash; `backend`
and `lang` remain in the iframe document query. Do not echo an unchanged location back to the frame.

### Local smoke

Build the LSP and Designer first, then start `pnpm --filter @tatrman/designer-core exec vite preview
--host 127.0.0.1 --port 5187`. From the repository root:

```sh
node packages/designer/scripts/make-host-smoke-files.mjs ../hartland /tmp/served-files.json
node packages/designer/scripts/hosted-world-smoke.mjs ../hartland http://127.0.0.1:5187/
```

The builder reads `git ls-files model/ modeler.toml` and the checkout HEAD. The browser runner serves
`scripts/host-smoke.html` as a fake parent and intercepts the BFF endpoint with that snapshot. It
checks ready within three seconds of init from `#/`, real MD/ER/sales canvases, workspace rewriting,
parent Copy links, token refresh, and a changed-SHA remount without errors. Screenshots default to `/tmp/so-hosted-smoke/`. Set `PW_EXECUTABLE`, `OUT_DIR`, or
`TRACE_RPC=1` to select Chromium, the output folder, or worker request timing output. No cluster or
Git provider is contacted.

The container's hosted entry and asset paths are also checked by `release-viewer.yml` before release.

### Saving authored source (S1.3)

The fetched SHA and file map form the immutable base. The active data source owns an editable text cache;
`changedFiles()` compares whole file contents by repo-rooted path, including newly created files and deletion
tombstones `{path,text:null}`. Nothing writes to the BFF or disk from the frame.

After cache changes the frame posts `{type:'ttr.frame.dirty',changed:N}` (200 ms debounce). Collect is an immediate
read of completed cache writes: send `{type:'ttr.host.collect'}` and receive
`{type:'ttr.frame.changedFiles',files:[{path,text}]}`. Repeated edits to one path count once. The host saves those
whole files using its current base SHA. Collect does not wait for an in-flight edit; disable Save while authoring
is pending or await the edit promise before collecting.

After a successful save, re-send the original `ttr.host.init` with `sha:<new SHA>`. The frame keeps whole-file edits made after collect and reports their dirty count; without later edits it posts dirty 0. Unchanged SHAs preserve local
edits; failed refreshes preserve the previous cache and dirty count. Init's API/scope/workspace remain fixed for the
iframe session. A different scope still requires a new iframe.

The drawer saves exact definition text through `modeler/edit {op:'set-source'}`. Parse refusals leave the buffer
and canvas intact; successful writes refresh the model and show returned diagnostics inline. Error diagnostics do
not undo a successful edit; Propose has its own strict gate. The open build adds no canvas forms.

An extension can import `editModel`, `ModelerEditParams` and `ModelerEditResult` from
`@tatrman/designer-core/extensions` and call `editModel(ctx.dataSource,{op,args,dryRun?})`. The data source serializes
requests, applies each returned edit exactly once to the pre-request text, then acknowledges full text to the LSP.
Do not apply the response again. Read-only backends have no authoring hook.

For the real Hartland/Chromium authoring smoke after a workspace build:
`node packages/designer/scripts/studio-save-smoke.mjs ../hartland /tmp/so-save-smoke`.
The runner serves a static parent with a test-only extension entry; production adds no test bridge.

Open drawer source editing is available only with a host persistence path (`capabilities.save = 'host'`).
A local-folder Viewer remains read-only in the open drawer; its in-memory edit API alone is not a save path.
An editable extension's `saveNode` takes precedence and owns persistence for its supported model kinds.

`refresh` and `navigate` accept `discard?: boolean`. A different SHA with unsaved edits is refused as
`frame/unsaved-edits`; current files, snapshot and navigation stay intact. Prompt the author to save/discard,
and send `discard:true` only after an explicit discard choice. A post-save init rebases pending files onto the
new immutable base. `frame/edit-conflict` retains the old snapshot when a pending path changed beyond the
expected last-collected saved text. Undoing a collected change back to the old base is also preserved.
Wait for edits to settle and serialize collect/save/re-init. A collection racing an acknowledgement must be
repeated after that acknowledgement settles. Same-SHA refresh preserves changes.

Source saves retain validated author text without formatter reprinting, keeping top-level kind/name and
refusing referenced-member removal. The enclosing file's line endings and neighboring definitions survive;
trailing definition whitespace is trimmed at the splice. Renames use `textDocument/rename`.

## Stage 2: embedded DesignerPage

`@tatrman/designer-core` exports a React page, its TypeScript props, `ShellEditContext`, and the
stage 1 message types. `@tatrman/designer-core/extensions` exports the open extension contracts
and `editModel`. This stage supplies the built library for internal consumers; the family release
is delivered as the Designer 0.1.0 family. Internal 0.x releases suffice unless a public release is explicitly requested.

```tsx
import { useRef } from 'react';
import { DesignerPage, type CollectFiles } from '@tatrman/designer-core';
import '@tatrman/designer-core/designer.css';

function Model({ snapshot }) {
  const collect = useRef<CollectFiles>(null);
  return <div style={{ height: 700 }}>
    <DesignerPage files={snapshot} workspace="hartland" basePath="/m/" lang="en"
      theme="dark" location="/w/hartland/s/md" collect={collect}
      onLocation={path => { /* update the host's router */ }}
      onDirty={changed => { /* update the host's save affordance */ }}
      onError={error => { /* show error.code / error.message */ }} />
  </div>;
}
```

`files` is `{ sha: string, files: Record<string,string> }`, or an async zero-argument loader
returning that shape. Paths and file contents follow the BFF snapshot rules above. Optional
`manifest` overrides `modeler.toml` in the supplied snapshot. Supply the complete repository
snapshot, including layouts and JS rosters; only `model/**` model documents are opened in the LSP.
`workspace` defaults to `repo` and changing it starts a new session. `basePath` has leading and
trailing slashes; it controls shared rail links, while `location` and `onLocation` use app paths
(`/w/:workspace/s/:subject`). The component neither changes browser history nor fetches config.json,
and does not set document-level language or styles. The root's `lang` follows the prop. Each mount
owns its worker, file cache, navigation and extension loader. A host may mount multiple pages.
A mirrored `onLocation` value passed back as `location` is ignored when already current, preserving
per-subject notation. Invalid locations report `host/bad-location` once per invalid input and keep the
current location (the workspace root initially); valid input afterwards recovers normally.
Invalid `basePath` or `workspace` props are programmer errors and throw during render.
Keyboard shortcuts are scoped to the page containing the event target; host inputs stay untouched.

`collect.current()` synchronously returns sorted whole-file changes (including null-text deletions)
and records the exact collected base. Serialize collect, saving, and the next snapshot acknowledgement.
After a successful save, pass a new snapshot object with a new immutable SHA, or a new loader function
that returns it. Same-SHA updates are idempotent, so changed bytes or manifest settings require a new
SHA. Late edits remain dirty after acknowledgement. Overlapping incoming changes report
`frame/edit-conflict`; dirty replacement without a matching collection reports `frame/unsaved-edits`.
The previous shell and files remain available on either refusal or loader failure. Prompt in the host;
remount with a different React key only after an explicit discard choice. Dirty callbacks are debounced
200 ms. Use a new input object/function to retry a failed loader; unmount terminates the worker.

The `extensions` prop is a list of `{ manifest, licenseClient?, activate }`. A manifest contains
`name`, `version`, `license: 'none' | 'platform'`, and `requires: string[]`. The core's existing
license gate runs before `activate({ dataSource }, grants)`; activation resolves a `ShellEditContext`
or null. The first supplied context is used. A missing grant/client or failed license request never
activates the extension. Keep the extension array stable across renders. The page's loader is scoped
to its React context, rather than registering a process-global loader that could affect another page.
The standalone commercial entry may still use `registerAuthoringLoader`.

Import the stylesheet once. Every library selector, including React Flow and Tailwind variables,
is rooted under `.ttr-designer`; the library disables Tailwind preflight and supplies its own minimal
reset. Its root fills the host's explicit height. `theme="light" | "dark"` controls root color-scheme,
shell surfaces and canvas theme without changing host styles. The standalone viewer keeps its full reset.

The LSP build exports the self-contained module asset `@tatrman/lsp/browser.worker.js`. The Designer
library copies those exact bytes beside its entry and creates a module Worker from a relative URL
using `import.meta.url`. No consumer imports `?worker`, compiles LSP sources, or supplies a worker plugin.
Keep `browser.worker.js` beside the library's JavaScript when copying files manually. Vite and webpack
asset pipelines must retain this URL asset; custom JS-only copy rules need to include it (and the CSS).
The standalone Vite entry uses `?url` to reference the already-built asset. A bare package string inside
`new URL('@tatrman/lsp/browser.worker.js', import.meta.url)` is not a native package resolver and is not
used by the library. React and ReactDOM 19 are host peer dependencies; install them once in the host.

For Vite hosts, preserve the package-relative worker URL during development and prebundle React's and ELK's
CommonJS entries (production uses Vite's ordinary asset handling):

```ts
// vite.config.ts
export default defineConfig({
  optimizeDeps: {
    exclude: ['@tatrman/designer-core'],
    include: ['react', 'react/jsx-runtime', 'react-dom', 'react-dom/client', '@tatrman/designer-core > @tatrman/canvas-core > elkjs/lib/elk.bundled.js'],
  },
});
```

The exclusion prevents Vite from relocating `import.meta.url` into its `.vite/deps` cache without
copying the worker. This is dependency optimization configuration; no worker plugin is required.
Webpack hosts must keep the emitted worker as an asset when resolving `new URL(..., import.meta.url)`.

The lockstep family is `designer-core`, `canvas-core`, `lsp`, `parser`, `tokens`, `perspectives`,
`deep-links`, and `edit`, all at **0.1.0**. `perspectives` and `deep-links` are runtime imports.
`canvas-core` is pure TypeScript; React is only a peer of Designer. The LSP tarball bundles its private
compiler modules and their declaration types; consumers need no private workspace packages.

After merge, `just publish designer set 0.1.0` creates the internal `designer/v0.1.0` GitHub release.
Download all `.tgz` assets, verify `SHA256SUMS`, then install them together:

```sh
npm install ./tatrman-*.tgz react@^19 react-dom@^19
```

`manifest.json` records the source commit, file lists and hashes. Bare Designer tags do not publish
to npm. An explicit public release is a separate maintainer action; see `PUBLISHING.md`.


### pnpm hosts: internal release artifacts

Platform P4 consumes the internal release under ruling R144-A(a). Download all eight release
assets into `vendor/designer-0.1.0/` in the host repository, verify their release hashes before
installing, and retain the manifest/checksums with them:

```sh
mkdir -p vendor/designer-0.1.0
gh release download designer/v0.1.0 --repo Collite/ttr-core --dir vendor/designer-0.1.0
(cd vendor/designer-0.1.0 && shasum -a 256 -c SHA256SUMS)
```

In the host package.json, depend on the same eight `file:vendor/designer-0.1.0/tatrman-<name>-0.1.0.tgz`
artifacts (use relative paths from each consuming package). Pin transitive references in the
workspace root's **pnpm-workspace.yaml**, not `pnpm.overrides` in package.json (ignored by pnpm 11):

```yaml
overrides:
  '@tatrman/designer-core': file:vendor/designer-0.1.0/tatrman-designer-core-0.1.0.tgz
  '@tatrman/canvas-core': file:vendor/designer-0.1.0/tatrman-canvas-core-0.1.0.tgz
  '@tatrman/lsp': file:vendor/designer-0.1.0/tatrman-lsp-0.1.0.tgz
  '@tatrman/parser': file:vendor/designer-0.1.0/tatrman-parser-0.1.0.tgz
  '@tatrman/tokens': file:vendor/designer-0.1.0/tatrman-tokens-0.1.0.tgz
  '@tatrman/perspectives': file:vendor/designer-0.1.0/tatrman-perspectives-0.1.0.tgz
  '@tatrman/deep-links': file:vendor/designer-0.1.0/tatrman-deep-links-0.1.0.tgz
  '@tatrman/edit': file:vendor/designer-0.1.0/tatrman-edit-0.1.0.tgz
```

Run `pnpm install` after adding React/ReactDOM 19 peers to the host. This form is exercised by
`scripts/designer-consumer-smoke.mjs` using isolated pnpm dependencies, the Vite settings above,
strict declarations and both dev/production browsers. The nested ELK include works without npm
hoisting. Exact public release-asset URLs may replace the tarball specs in **both** direct dependencies
and overrides, for example
`https://github.com/Collite/ttr-core/releases/download/designer/v0.1.0/tatrman-lsp-0.1.0.tgz`;
verify the downloaded bytes against that release's SHA256SUMS first. ttr-core is public, so asset URLs
need no registry auth. Only released, hash-checked tarball specs are permitted: source `file:` paths,
`workspace:`, `link:` and git dependencies remain forbidden in Platform's dependency rules.

### Hosted commercial extension and Save acknowledgement

A Studio change session may add `features: ['modeler']` and `extensions: ['authoring']` to init.
Only that combination on a change scope imports the fixed same-origin module
`/m/_designer/ext/authoring.js`. The Studio image supplies it; the open viewer image does not.
The module exports `activate({dataSource, bindingsRequired})`, returning a `ShellEditContext` or null.
It uses `dataSource.edit` for the typed `modeler/edit` operations and `listCatalog` for picker data.
The frame sets `globalThis.__ttrExtensionRuntime = {react, jsx}` before import, so the separate
extension build can share the frame's React dispatcher. No bearer is supplied to the extension.
Removing the grant from a repeated init immediately removes the mounted extension context.
The BFF independently enforces the signed modeler licence on every persisted mutation.

Ready has additive fields: `{sha, capabilities: ['save-ack'], manifest: {bindings: {required}}}`.
The binding policy comes from the worker's resolved manifest, with a conservative true fallback
when an older worker does not report it. After collect and a successful BFF save, repeat init
with the returned `sha`. The frame fetches that exact head, preserves post-collect whole-file edits,
and replies `{type:'ttr.frame.ack',sha,changed}`. An unexpected head or overlapping remote edit
refuses with `frame/edit-conflict` before replacing the old snapshot. The host serializes
collect/commit/ack and uses the acknowledged `changed` count; HTTP success alone never clears dirty.
Successful refresh and SHA navigation also acknowledge the installed SHA and remaining dirty count.
