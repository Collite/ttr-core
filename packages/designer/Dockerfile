# SPDX-License-Identifier: Apache-2.0
#
# Studio Viewer — the read-only Designer viewer as a static bundle behind nginx (ttr-core#169).
# Published as ghcr.io/collite/ttr-viewer by .github/workflows/release-viewer.yml on a
# `viewer/v<x.y.z>` tag. Deployment guide: docs-site/docs/operate/studio-viewer.md.
#
# Build from the REPO ROOT — the bundle is built from the workspace packages it imports:
#   docker build -f packages/designer/Dockerfile -t ttr-viewer:dev .
#
# Base images are ghcr.io/collite copies of the Docker official images, kept by Collite/ttr-server's
# .github/workflows/mirror-base-images.yml — never Docker Hub, whose anonymous pull limit has failed
# releases outright. Pulling them needs a `docker login ghcr.io` (they are not public).
#
# Runtime configuration is environment, read once at container start by the entrypoint — not a
# build-time constant and not a query parameter:
#   VIEWER_BASE_PATH         the path prefix the viewer is served under       (default /)
#   VIEWER_CATALOG_URL       the catalog the browser reads                    (default <base>veles; `none` = no catalog)
#   VIEWER_CATALOG_UPSTREAM  where nginx forwards <base>veles/ — read routes only (default unset → 503)
#   VIEWER_DEFAULT_LANG      the language for model labels                    (default: the browser's, then en)
#   VIEWER_LAYOUT_FORMAT     Save as…'s first format: yaml | ttr             (default: yaml)
#   VIEWER_LANGUAGES         extra languages the picker offers, comma-separated (default none)
#   VIEWER_PORT              the listen port                                  (default 8080)

ARG NODE_IMAGE=ghcr.io/collite/node:24-slim
ARG NGINX_IMAGE=ghcr.io/collite/nginx:alpine

# ===== build: the static bundle =====
# The bundle is arch-independent, so it is built ONCE on the build platform and copied into every
# target platform's runtime stage — a multi-arch build never runs pnpm under emulation.
FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS build

ENV PNPM_HOME=/pnpm \
    PATH=/pnpm:$PATH \
    CI=true
# pnpm comes from the root package.json's `packageManager` pin, through corepack.
RUN corepack enable

WORKDIR /src
# The lockfile alone fills the store, so this layer survives every source change.
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
RUN pnpm fetch --frozen-lockfile

# Named, not `COPY . .`: the .dockerignore keeps node_modules/dist/build output out, and this keeps
# the Kotlin/Python trees, docs and the IntelliJ plugin out of the layer altogether.
COPY tsconfig.base.json ./
COPY packages ./packages
COPY tests/integration/package.json ./tests/integration/package.json
COPY tests/conformance/package.json ./tests/conformance/package.json
COPY samples/v1.1-mini ./samples/v1.1-mini
RUN pnpm install --offline --frozen-lockfile --filter "@tatrman/designer..."
# `./` — a RELATIVE asset base. The base path is a runtime choice: the entrypoint writes it into
# index.html as `<base href>` (and into config.json), and every asset URL resolves against it.
RUN DESIGNER_BASE_URL=./ pnpm --filter "@tatrman/designer..." build
# Split the output here, so the runtime stage below is COPY-only (no RUN ⇒ no emulation needed for
# a foreign target platform). index.html is a TEMPLATE: the entrypoint renders it into /tmp.
RUN mkdir -p /out/templates \
    && cp -R packages/designer/dist /out/html \
    && mv /out/html/index.html /out/templates/index.html

# ===== runtime: nginx, unprivileged =====
FROM ${NGINX_IMAGE}

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="ttr-viewer" \
      org.opencontainers.image.description="Studio Viewer: the read-only Tatrman model viewer (catalog mode over Veles)" \
      org.opencontainers.image.source="https://github.com/Collite/ttr-core" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# The bundle is read-only content. index.html is NOT served from it: it is the template the
# entrypoint renders (with `<base href>`) into /tmp, the one writable path. The stock
# /etc/nginx/conf.d is never read — nginx runs on the rendered /tmp/ttr-viewer/nginx.conf.
COPY --from=build /out/html/ /usr/share/ttr-viewer/html/
COPY --from=build /out/templates/ /usr/share/ttr-viewer/templates/
COPY packages/designer/docker/nginx.conf.template \
     packages/designer/docker/catalog-proxy.conf.template \
     packages/designer/docker/catalog-unset.conf.template \
     /usr/share/ttr-viewer/templates/
# Executable in git (mode 100755); COPY keeps the mode.
COPY packages/designer/docker/entrypoint.sh /usr/local/bin/ttr-viewer-entrypoint

ENV VIEWER_PORT=8080 \
    VIEWER_BASE_PATH=/
EXPOSE 8080

# Non-root: the stock `nginx` user (uid 101). Everything the process writes — the rendered
# index.html / config.json / nginx.conf, the pid file, nginx's temp dirs — lives under /tmp, so the
# image also runs with a read-only root filesystem (mount an emptyDir at /tmp).
USER 101

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${VIEWER_PORT}/healthz" || exit 1

ENTRYPOINT ["/usr/local/bin/ttr-viewer-entrypoint"]
