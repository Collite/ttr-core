<!-- SPDX-License-Identifier: Apache-2.0 -->
# Deploy the Studio Viewer

*How-to. Run the read-only model viewer next to a Veles catalog.*

The **Studio Viewer** is the open, read-only view of a Tatrman model. In *catalog mode* it reads the
model a Veles instance serves, through Veles' JSON read API, and draws the ER and DB graphs, with an
inspector and search on top. It never writes anything to the server: there is no edit, and no call
outside the read routes listed below. A picture you arrange — which cards, where — is a *layout*, and
it leaves the viewer only as a download (see [Layouts](#layouts-pick-place-save)); git is how it is shared.

It ships as one container image: a static bundle behind nginx.

```
ghcr.io/collite/ttr-viewer:<version>
```

| | |
|---|---|
| Process | nginx, unprivileged (uid `101`) |
| Port | `8080` (`VIEWER_PORT`) |
| Health | `GET /healthz` → `200 ok`, outside the base path |
| Platforms | `linux/amd64`, `linux/arm64` |
| Writes | `/tmp` only. Read-only root filesystem works; mount an `emptyDir` at `/tmp`. |

## Configure it

Everything is environment, read once when the container starts. Nothing is baked in at build time,
and nothing depends on a query parameter. The entrypoint refuses a value it cannot use (exit code
`64`, with the reason on stderr) instead of starting with a guess.

| Variable | Default | Meaning |
|---|---|---|
| `VIEWER_BASE_PATH` | `/` | The path prefix the viewer is served under, e.g. `/models/`. The bare prefix redirects to its slash form, and nothing outside the prefix is served. |
| `VIEWER_CATALOG_URL` | `<base>veles` | The catalog the **browser** reads: a same-origin path (`/models/veles`), an `http(s)://` origin, or a `ws(s)://` origin (the platform transport). `none` means no catalog: the viewer opens on its local landing card. |
| `VIEWER_CATALOG_UPSTREAM` | unset | Veles' HTTP origin, e.g. `http://veles.ttr-server.svc:7260`. When set, nginx proxies `<base>veles/` to it, **read routes only** (see below). When unset, `<base>veles/` answers `503` with a JSON error. |
| `VIEWER_DEFAULT_LANG` | unset | The language for model labels and descriptions when neither the URL nor the user picked one. Unset means the browser's language, then `en`. |
| `VIEWER_LANGUAGES` | unset | Extra languages the language picker offers, comma-separated (`en,cs`). The model's own labels add theirs. |
| `VIEWER_LAYOUT_FORMAT` | `yaml` | The format **Save as…** offers first: `yaml` or `ttr`. Once a user picks one, their choice wins in that browser. |
| `VIEWER_PORT` | `8080` | The listen port. |

The entrypoint renders these into two files the app reads:

- `<base>config.json` — `{ "basePath", "veles", "lang", "languages", "layoutFormat" }`, fetched once
  before the first render.
- `<base>index.html` — with `<base href="<base>">`, so every asset URL resolves under the prefix.

### URLs

The viewer keeps its state in the address bar, under the base path:

```
https://apps.example.com/models/w/<workspace>/s/<schema>      e.g. …/models/w/veles/s/er
```

A reload or a bookmark of that URL opens the same graph. Only the base path the image was given is
served; deep links below it all get the app.

For local use, `?veles=<catalog>` still selects a catalog explicitly and wins over the configured one,
e.g. `http://localhost:5173/?veles=http://localhost:7260` against the dev server. The address bar keeps
it on every rewrite, so reloads keep the catalog.

## Labels and language

The viewer shows the model the way its authors labelled it, not by identifier. An entity's card reads
its `displayLabel` in the viewer's language, *Product* for the Hartland model's `item`, and falls back
to the identifier when no label exists in that language. Attributes become rows of their entity's card
(choose **With types** under *Detail* to see them), and relations become the edges between cards, with
their cardinality.

Select a card to open the inspector. It shows the label, the identifier and qualified name
(`item`, `er.entity.item`), the plural label (*Products*), the description, and every attribute with
its own description. A description in the viewer's language wins; failing that, the plain-form
description, then English, then the first one the model has.

The language is chosen when the page loads, first match wins:

1. `?lang=cs` in the URL, which the address bar keeps;
2. the user's own choice from the **Language** picker in the header (remembered in that browser);
3. `VIEWER_DEFAULT_LANG`;
4. the browser's language;
5. `en`.

The picker offers the languages the model's labels carry, plus `VIEWER_LANGUAGES`. The viewer's own
buttons and headings stay English.

!!! note "Needs a Veles that serves the labels"
    The labels, descriptions and relation endpoints are fields of Veles' JSON read API from the
    release after `0.11.7` (Collite/ttr-server#185). Against an older Veles the viewer still works,
    but it shows identifiers, and relations appear as cards of their own instead of edges.

## Layouts: pick, place, save

A schema opens whole: every entity (or table) the catalog serves, laid out automatically. To draw a
smaller picture, use the **Objects** panel on the left of the canvas — every card of the schema, grouped
by package, each with a checkbox. Uncheck what you do not need; search narrows the list (label,
identifier, qualified name, package), and **All · None · Neighbours · Invert** act on it. The first such
change turns the tab into a new layout, `er · untitled`, marked `*` while it has unsaved changes.

- **Relations through hidden cards.** When two shown cards are joined only through a card you hid, a
  dashed **`…`** edge connects them (`… ×2` when there are several such cards). Click it: the inspector
  names the hidden card(s) and the two relations each joins, with **Show** to bring one back.
- **Right-click a card** for **Expand (+n)** (add its direct neighbours), **Expand all levels…** (everything
  reachable through hidden cards, after a confirmation that says how many), **Hide**, **Hide others**
  (keep the card and its visible neighbours) and **Inspect**. Shift-click selects several cards; the menu
  then acts on all of them. Each row of the panel offers Expand and Hide on hover too.
- **Drag** cards where you want them. A card you check later is placed beside its nearest visible
  neighbour, and nothing you placed moves. **Re-layout** arranges everything shown automatically.
- **Save as…** downloads the layout as **YAML** (`<name>.yaml`) or **TTR** (`<name>.ttrg`), showing the
  exact text first. The name follows the graph-name rule: letters, digits and underscore, not starting
  with a digit. The TTR form does not carry the notation (skin); the dialog says so.
- **Open layout…** (or drop a file on the canvas) reads a `.yaml`, or a `.ttrg` together with its `.ttrl`
  sidecar, and opens it in a new tab. Objects can be named in any of the forms the
  [layout format](../model/layout-files.md) accepts, bare names included; a name the model does not have
  is listed under **Missing** in the panel, kept until you remove it.

The truth chip above the canvas says where the picture lives: `layout: file · unsaved`,
`layout: file · sales.yaml`, `layout: catalog · sales`. Nothing of this is stored on the server; leaving
the page with unsaved changes asks first. A downloaded layout file is shared the way the model is: commit
it next to the model, and a catalog that serves layouts lists it in the spine as `er · <name>` for
everyone, with a link of its own. A layout opened from your disk has no shareable link.

## The catalog proxy: read routes only

The viewer's catalog calls are same-origin `fetch`es to `<base>veles/…`. With
`VIEWER_CATALOG_UPSTREAM` set, the image's nginx forwards exactly these, `GET` only:

| Route | Used for |
|---|---|
| `/model/index` | packages, schemas, counts, model version |
| `/model/graph` | a schema's graph |
| `/model/object` | one object's detail |
| `/model/search` | search |
| `/model/layouts` | the layouts committed next to the model (the spine's `er · <name>` entries) |
| `/model/layout` | one layout, resolved against the model |
| `/status` | polled for a new model version |

Everything else under `<base>veles/` is a `404` at the viewer, in particular `POST /refresh`, `/admin/*`,
`/model/export`, `/model/export/dot` and `/metrics`; a read route asked with anything but `GET` is a `403`.
The matches are exact, so `/model/export` cannot ride in on a `/model/` prefix.

### Layouts the catalog serves

A layout file committed under the model root — a `.ttrg` the viewer's **Save as…** wrote, with its
`.ttrl` sidecar when there is one — is served to every viewer of that model: Veles reads the layout
files with each model load, resolves their cards against that model and answers `/model/layouts` and
`/model/layout?name=` from them. The model's own files load as before; a layout file is never read as
model. A layout file that cannot be read (not UTF-8 text) or does not parse is left out and listed in
`/status` under `issues` (`layout layouts/sales.ttrg: …`; a problem in the sidecar names the `.ttrl`
and its line). When two files declare the same layout name, neither is served — each is listed, naming
the other — so which picture a viewer gets never depends on file order. The model is served regardless.
The sidecar is read as the viewer reads it: a property a layout does not use (`viewport`, …) is skipped.
There is no write route: committing the file to the model's repository is how a layout is published.

The proxy also drops the `Cookie` header. The session cookie belongs to the edge (next section), and
Veles reads none. Identity headers an authenticating proxy adds (`X-Forwarded-User`,
`X-Auth-Request-*`) pass through unchanged.

!!! warning "Keep Veles itself off the edge"
    Veles' HTTP port also serves `/refresh` (unauthenticated, cluster-internal by design) and the
    model export. Expose the **viewer**, never Veles. Restrict Veles' HTTP port to in-cluster callers
    with a NetworkPolicy, so that the viewer is the only browser-facing path to it.

## The auth seam: a cookie-session edge

The viewer cannot attach a bearer token. It is a static app with no OIDC client of its own, and its
catalog calls are same-origin `fetch` with `credentials: 'include'`: the browser sends its cookies and
nothing else. So **the supported setup is an authenticating proxy at the edge that logs the browser in
and keeps a cookie session**, e.g. [oauth2-proxy](https://oauth2-proxy.github.io/oauth2-proxy/) in
front of the viewer, or your ingress controller's external-auth feature pointed at one:

```
browser ──cookie──▶ edge (OIDC login, session cookie) ──▶ ttr-viewer :8080 ──read routes──▶ Veles :7260
```

- The edge protects **everything** under the base path, `<base>veles/…` included. An unauthenticated
  request is redirected to the IdP; the session cookie then carries every request after it.
- The viewer adds no authorization of its own. Whoever passes the edge reads the whole catalog the
  upstream Veles serves. Scope the catalog by what that Veles loads, not by the viewer.
- `/healthz` sits outside the base path, so probes need no session.

### Reference: Kubernetes with ingress-nginx and oauth2-proxy

The viewer and its catalog proxy run in one pod. oauth2-proxy (deployed separately, with your IdP's
client) guards the ingress:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ttr-viewer
spec:
  replicas: 1
  selector:
    matchLabels: { app: ttr-viewer }
  template:
    metadata:
      labels: { app: ttr-viewer }
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 101
      containers:
        - name: viewer
          image: ghcr.io/collite/ttr-viewer:<version>
          env:
            - { name: VIEWER_BASE_PATH, value: /models/ }
            - { name: VIEWER_CATALOG_UPSTREAM, value: http://veles.ttr-server.svc.cluster.local:7260 }
            - { name: VIEWER_DEFAULT_LANG, value: en }
          ports:
            - { name: http, containerPort: 8080 }
          readinessProbe: { httpGet: { path: /healthz, port: http } }
          livenessProbe: { httpGet: { path: /healthz, port: http } }
          securityContext:
            readOnlyRootFilesystem: true
            allowPrivilegeEscalation: false
            capabilities: { drop: [ALL] }
          volumeMounts:
            - { name: tmp, mountPath: /tmp }
      volumes:
        - name: tmp
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: ttr-viewer
spec:
  selector: { app: ttr-viewer }
  ports:
    - { name: http, port: 80, targetPort: http }
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ttr-viewer
  annotations:
    # every request under /models/ needs an oauth2-proxy session (cookie); none → IdP login
    nginx.ingress.kubernetes.io/auth-url: "https://$host/oauth2/auth"
    nginx.ingress.kubernetes.io/auth-signin: "https://$host/oauth2/start?rd=$escaped_request_uri"
spec:
  ingressClassName: nginx
  rules:
    - host: apps.example.com
      http:
        paths:
          - path: /models/
            pathType: Prefix
            backend:
              service: { name: ttr-viewer, port: { name: http } }
```

nginx resolves `VIEWER_CATALOG_UPSTREAM`'s host name when it starts. A Kubernetes Service name
resolves even before its pods are ready, but a name that does not exist at all stops the container.

### Reference: your own edge routes the catalog

If your edge already routes `<base>veles/` to Veles, leave `VIEWER_CATALOG_UPSTREAM` unset. The edge
then owns the allow-list. This is the nginx equivalent of the image's own proxy, for a viewer at `/`:

```nginx
location = /veles/model/index   { limit_except GET { deny all; } proxy_pass http://veles:7260/model/index;   proxy_set_header Cookie ""; }
location = /veles/model/graph   { limit_except GET { deny all; } proxy_pass http://veles:7260/model/graph;   proxy_set_header Cookie ""; }
location = /veles/model/object  { limit_except GET { deny all; } proxy_pass http://veles:7260/model/object;  proxy_set_header Cookie ""; }
location = /veles/model/search  { limit_except GET { deny all; } proxy_pass http://veles:7260/model/search;  proxy_set_header Cookie ""; }
location = /veles/model/layouts { limit_except GET { deny all; } proxy_pass http://veles:7260/model/layouts; proxy_set_header Cookie ""; }
location = /veles/model/layout  { limit_except GET { deny all; } proxy_pass http://veles:7260/model/layout;  proxy_set_header Cookie ""; }
location = /veles/status        { limit_except GET { deny all; } proxy_pass http://veles:7260/status;        proxy_set_header Cookie ""; }
location ^~ /veles/             { return 404; }
```

## Try it locally: the Hartland demo model

Veles serves a model from a directory with its filesystem source. The public demo model
([Collite/ttr-demo](https://github.com/Collite/ttr-demo), under `model/`) works as is:

```bash
git clone https://github.com/Collite/ttr-demo.git
docker network create ttr-viewer-demo

docker run -d --name veles --network ttr-viewer-demo \
  -v "$PWD/ttr-demo/model:/model:ro" \
  -e METADATA_TTR_ROOT=/model \
  -e JAVA_TOOL_OPTIONS='-Dmetadata.sources.fixture.enabled=true -Dmetadata.sources.github-model.enabled=false' \
  ghcr.io/collite/veles:<version>

docker run -d --name viewer --network ttr-viewer-demo -p 8080:8080 \
  -e VIEWER_CATALOG_UPSTREAM=http://veles:7260 \
  ghcr.io/collite/ttr-viewer:<version>
```

Open <http://localhost:8080/> and choose **er** in the catalog: the Hartland ER graph opens, with cards
that read *Product*, *Store*, *Customer Address*, and so on. Open <http://localhost:8080/?lang=cs> and the
same cards read *Produkt*, *Prodejna*, *Adresa zákazníka*. Add `-e VIEWER_BASE_PATH=/models/` to the
viewer to serve it under <http://localhost:8080/models/> instead.

Use a Veles that serves the labels (see [Labels and language](#labels-and-language)). With `0.11.7`
the graph shows identifiers.

## Verify

```bash
curl -fsS http://localhost:8080/healthz                       # ok
curl -fsS http://localhost:8080/config.json                   # the rendered runtime config
curl -fsS http://localhost:8080/veles/model/index             # the catalog, through the proxy
curl -fsS http://localhost:8080/veles/model/layouts           # the layouts the catalog serves
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/veles/refresh   # 404: not a read route
```
