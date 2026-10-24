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

TypeScript unions are exported from `packages/designer/src/host/host-channel.ts`; the public
`designer-core` package export comes in SO-core P3. Dirty/changedFiles/collect types are reserved for P1.

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

Build the LSP and Designer first, then start `pnpm --filter @tatrman/designer exec vite preview
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
`@tatrman/designer/extensions` and call `editModel(ctx.dataSource,{op,args,dryRun?})`. The data source serializes
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
