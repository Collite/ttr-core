<!-- SPDX-License-Identifier: Apache-2.0 -->
# Deploy the Studio Viewer

*How-to. Run the read-only model viewer next to a Veles catalog.*

The **Studio Viewer** is the open, read-only view of a Tatrman model. In *catalog mode* it reads the
model a Veles instance serves, through Veles' JSON read API, and draws the ER and DB graphs, with an
inspector and search on top. It never writes anything: there is no edit, no layout save, and no call
outside the read routes listed below.

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
| `VIEWER_PORT` | `8080` | The listen port. |

The entrypoint renders these into two files the app reads:

- `<base>config.json` — `{ "basePath", "veles", "lang", "languages" }`, fetched once before the first
  render.
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

## The catalog proxy: read routes only

The viewer's catalog calls are same-origin `fetch`es to `<base>veles/…`. With
`VIEWER_CATALOG_UPSTREAM` set, the image's nginx forwards exactly these, `GET` (and `HEAD`) only:

| Route | Used for |
|---|---|
| `/model/index` | packages, schemas, counts, model version |
| `/model/graph` | a schema's graph |
| `/model/object` | one object's detail |
| `/model/search` | search |
| `/status` | polled for a new model version |

Everything else under `<base>veles/` is a `404` at the viewer, in particular `POST /refresh`, `/admin/*`,
`/model/export`, `/model/export/dot` and `/metrics`. The matches are exact, so `/model/export` cannot
ride in on a `/model/` prefix.

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
location = /veles/model/index  { limit_except GET { deny all; } proxy_pass http://veles:7260/model/index;  proxy_set_header Cookie ""; }
location = /veles/model/graph  { limit_except GET { deny all; } proxy_pass http://veles:7260/model/graph;  proxy_set_header Cookie ""; }
location = /veles/model/object { limit_except GET { deny all; } proxy_pass http://veles:7260/model/object; proxy_set_header Cookie ""; }
location = /veles/model/search { limit_except GET { deny all; } proxy_pass http://veles:7260/model/search; proxy_set_header Cookie ""; }
location = /veles/status       { limit_except GET { deny all; } proxy_pass http://veles:7260/status;       proxy_set_header Cookie ""; }
location ^~ /veles/            { return 404; }
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

Open <http://localhost:8080/> and choose **er** in the catalog: the Hartland ER graph opens. Add
`-e VIEWER_BASE_PATH=/models/` to the viewer to serve it under <http://localhost:8080/models/> instead.

## Verify

```bash
curl -fsS http://localhost:8080/healthz                       # ok
curl -fsS http://localhost:8080/config.json                   # the rendered runtime config
curl -fsS http://localhost:8080/veles/model/index             # the catalog, through the proxy
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/veles/refresh   # 404: not a read route
```
