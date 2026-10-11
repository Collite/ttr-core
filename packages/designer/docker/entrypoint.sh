#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# ttr-viewer entrypoint (ttr-core#169). Reads the runtime configuration from the environment ONCE,
# refuses a value it cannot use, renders the runtime files into /tmp/ttr-viewer, and execs nginx in
# the foreground. Writes nothing outside /tmp, so the root filesystem can be read-only.
#
#   VIEWER_BASE_PATH         path prefix the viewer is served under            default /
#   VIEWER_CATALOG_URL       the catalog the BROWSER reads (as `?veles=`)      default <base>veles; `none` = no catalog
#   VIEWER_CATALOG_UPSTREAM  Veles' origin; nginx proxies <base>veles/ to it   default unset (503)
#   VIEWER_DEFAULT_LANG      model-label language when the URL/user set none   default unset (browser, then en)
#   VIEWER_LANGUAGES         extra languages the picker offers, e.g. en,cs     default unset
#   VIEWER_PORT              listen port                                       default 8080
#
# Rendered: index.html (with <base href>), config.json (read by the app before its first render),
# nginx.conf + the two snippets it includes.

set -eu

TEMPLATES=/usr/share/ttr-viewer/templates
OUT=${VIEWER_RUNTIME_DIR:-/tmp/ttr-viewer}

die() {
    echo "ttr-viewer: $*" >&2
    exit 64
}

# matches ERE VALUE — the whole value matches, and it is one line (no smuggled newline).
matches() {
    case "$2" in
        *'
'*) return 1 ;;
    esac
    printf '%s\n' "$2" | grep -Eqx "$1"
}

PORT=${VIEWER_PORT:-8080}
matches '[0-9]{1,5}' "$PORT" || die "VIEWER_PORT must be a port number, got '$PORT'"

# Base path: leading and trailing slash, URL-safe segments, no `.`/`..` segments.
BASE=${VIEWER_BASE_PATH:-/}
case "$BASE" in /*) ;; *) BASE="/$BASE" ;; esac
case "$BASE" in */) ;; *) BASE="$BASE/" ;; esac
matches '/([A-Za-z0-9._~-]+/)*' "$BASE" || die "VIEWER_BASE_PATH must be a path like /models/, got '${VIEWER_BASE_PATH:-}'"
case "$BASE" in */./* | */../*) die "VIEWER_BASE_PATH must not contain . or .. segments, got '$BASE'" ;; esac

# The catalog URL the browser fetches: a same-origin path, an http(s) origin, or a ws(s) origin
# (exactly what `?veles=` accepts). Default: this image's own proxy under the base path.
CATALOG=${VIEWER_CATALOG_URL-"${BASE}veles"}
if [ "$CATALOG" = none ] || [ -z "$CATALOG" ]; then
    CATALOG=
elif ! matches '(/[A-Za-z0-9._~/-]*|(https?|wss?)://[A-Za-z0-9.-]+(:[0-9]{1,5})?/?)' "$CATALOG"; then
    die "VIEWER_CATALOG_URL must be a same-origin path (/veles) or an http(s)/ws(s) origin, got '$CATALOG'"
fi

# The upstream nginx proxies <base>veles/ to: an http(s) origin, no path.
UPSTREAM=${VIEWER_CATALOG_UPSTREAM:-}
UPSTREAM=${UPSTREAM%/}
if [ -n "$UPSTREAM" ] && ! matches 'https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?' "$UPSTREAM"; then
    die "VIEWER_CATALOG_UPSTREAM must be an http(s) origin without a path (http://veles:7260), got '$UPSTREAM'"
fi

LANG_TAG='[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*'
DEFAULT_LANG=${VIEWER_DEFAULT_LANG:-}
if [ -n "$DEFAULT_LANG" ] && ! matches "$LANG_TAG" "$DEFAULT_LANG"; then
    die "VIEWER_DEFAULT_LANG must be a language tag (en, cs), got '$DEFAULT_LANG'"
fi
LANGUAGES=$(printf '%s' "${VIEWER_LANGUAGES:-}" | tr -d ' ')
if [ -n "$LANGUAGES" ] && ! matches "$LANG_TAG(,$LANG_TAG)*" "$LANGUAGES"; then
    die "VIEWER_LANGUAGES must be comma-separated language tags (en,cs), got '$LANGUAGES'"
fi

mkdir -p "$OUT/client_temp" "$OUT/proxy_temp" "$OUT/fastcgi_temp" "$OUT/uwsgi_temp" "$OUT/scgi_temp"

# --- index.html: the bundle's relative asset URLs resolve against <base href> ---------------------
sed "s|<head>|<head><base href=\"$BASE\" />|" "$TEMPLATES/index.html" > "$OUT/index.html"
grep -q "<base href=\"$BASE\" />" "$OUT/index.html" || die "could not write <base href> into index.html"

# --- config.json — every value above is validated, none can carry a quote or a backslash ---------
json_or_null() {
    if [ -n "$1" ]; then printf '"%s"' "$1"; else printf 'null'; fi
}
LANG_LIST=
if [ -n "$LANGUAGES" ]; then
    LANG_LIST=$(printf '"%s"' "$LANGUAGES" | sed 's/,/","/g')
fi
cat > "$OUT/config.json" <<EOF
{
  "basePath": "$BASE",
  "veles": $(json_or_null "$CATALOG"),
  "lang": $(json_or_null "$DEFAULT_LANG"),
  "languages": [$LANG_LIST]
}
EOF

# --- nginx.conf and its two snippets ---------------------------------------------------------------
# The base path as an nginx regex (dots escaped) — doubled backslash, since it passes through sed.
BASE_RE=$(printf '%s' "$BASE" | sed 's/[.]/\\\\./g')
render() {
    sed -e "s|@@PORT@@|$PORT|g" \
        -e "s|@@BASE_RE@@|$BASE_RE|g" \
        -e "s|@@BASE@@|$BASE|g" \
        -e "s|@@UPSTREAM@@|$UPSTREAM|g" \
        "$1"
}
render "$TEMPLATES/nginx.conf.template" > "$OUT/nginx.conf"

if [ "$BASE" = / ]; then
    : > "$OUT/outside-base.conf"
else
    cat > "$OUT/outside-base.conf" <<EOF
location = ${BASE%/} {
    return 301 $BASE;
}
location / {
    return 404;
}
EOF
fi

if [ -n "$UPSTREAM" ]; then
    render "$TEMPLATES/catalog-proxy.conf.template" > "$OUT/catalog.conf"
else
    render "$TEMPLATES/catalog-unset.conf.template" > "$OUT/catalog.conf"
fi

echo "ttr-viewer: base path $BASE, catalog ${CATALOG:-none}, upstream ${UPSTREAM:-none}, port $PORT" >&2
exec nginx -c "$OUT/nginx.conf" -g 'daemon off;'
