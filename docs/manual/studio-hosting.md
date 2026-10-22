# Studio hosting

## Stage 1: same-origin iframe

The viewer image serves a standalone viewer at its configured `VIEWER_BASE_PATH` (default `/`) and
the Studio iframe at `/m/_designer/index.html?backend=host`. Both entries use the same JavaScript,
CSS and worker assets. `/m/_designer/` is reserved for the iframe. A host on another origin is not
supported by this protocol.

Set the iframe height in the host. Hosted roots fill that height, force light form controls, omit the
local-project landing/FSA loader, and do not install a `beforeunload` prompt. The open P0 viewer is
read-only; document dirty/collect messages arrive with the P1 save seam.

Send init from the iframe's `load` handler. Configuration comes from this message, without a
`config.json` round trip. The frame's parent listener is installed before `load` fires.

```js
frame.onload = () => frame.contentWindow.postMessage({
  type: 'ttr.host.init',
  basePath: '/m/',             // the parent rail's route prefix, with trailing slash
  api: '/api/model',           // same-origin absolute path, no query or fragment
  token: bearer,               // held in memory; never put a bearer in the iframe URL
  scope: { kind: 'served' },   // or { kind: 'change', id: '42' }
  lang: 'en',                 // en | cs; also reflected into the frame's ?lang=
  theme: 'light',
}, location.origin);
frame.src = '/m/_designer/index.html?backend=host#/w/hartland/s/md';
```

The frame checks both `event.source === window.parent` and `event.origin === location.origin`.
It ignores malformed or untrusted messages. The host must check the same origin and that the source
is this iframe's `contentWindow` before accepting replies. Always supply an explicit target origin.
An iframe session has one API/scope; replace the iframe when switching scopes. Repeated init is
idempotent. After a failed initial load, refresh retries it.

| Direction | Message | Meaning |
|---|---|---|
| Host → frame | `{ type: 'ttr.host.init', basePath, api, token, scope, lang, theme }` | Fetch, configure worker, open model documents, mount shell. |
| Host → frame | `{ type: 'ttr.host.token', token }` | Replace the bearer for the next fetch, including during initial loading. |
| Host → frame | `{ type: 'ttr.host.navigate', location, sha? }` | Open an app path (`/w/…`, optionally prefixed `#`). If `sha` differs, re-fetch first. |
| Host → frame | `{ type: 'ttr.host.refresh' }` | Re-fetch the current scope. The host owns polling. |
| Frame → host | `{ type: 'ttr.frame.ready' }` | First shell render committed after loading; emitted once per session. Canvas placement may finish afterwards. |
| Frame → host | `{ type: 'ttr.frame.location', location }` | Current app path, initially and when the active view changes. |
| Frame → host | `{ type: 'ttr.frame.error', code: 'host/load-failed', message }` | Snapshot/config/navigation failure. Response bodies and credentials are not included. |

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
root to `file:///repo` with the root `modeler.toml`, then opens `.ttrm`/`.ttrg` under `model/**`.
`model/layouts/**` stays in the file map for the shared layout/sidecar reader.

An unchanged SHA leaves the worker, tabs and cached reads intact. A changed SHA loads a replacement
worker and remounts the shell at the current hash; deleted documents disappear from symbols and
graphs. Fetched SHA contents must be immutable. Refresh does not preserve local layout drafts across
a changed SHA. Fetch/manifest failures retain the previous snapshot; a host-visible error reports
the failure. The frame does not poll or retry on a timer.

### Route mapping

`basePath` configures the parent route prefix; it does not relocate already loaded assets. The hosted
entry's HTML base stays `/m/_designer/`. Navigation rewrites only its hash, using `replaceState`.
Copy-link/popout URLs point to the parent so a shared link can obtain authentication and init again.

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
checks ready within three seconds of init, real MD/ER/sales canvases, outbound/inbound locations and
token refresh. Screenshots default to `/tmp/so-hosted-smoke/`. Set `PW_EXECUTABLE`, `OUT_DIR`, or
`TRACE_RPC=1` to select Chromium, the output folder, or worker request timing output. No cluster or
Git provider is contacted.

The container's hosted entry and asset paths are also checked by `release-viewer.yml` before release.
