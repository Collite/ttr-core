// SPDX-License-Identifier: Apache-2.0
// Explicit backend selection — never sniffed (P2).
//
// Precedence (also documented in packages/designer/README.md):
//   (1) `?server=ws://127.0.0.1:7270` → WsDesignerServerDataSource (the value is the
//       WS origin; the client appends `/ttrm`). Loopback-only (a modeling-time server).
//   (2) `?veles=<base>` → VelesReadApiDataSource, the read-only catalog view over the Veles
//       JSON read API (SV-P4·S2·T5). `<base>` is EITHER a same-origin absolute path
//       prefix (the in-chart deployment shape, e.g. `/veles`, so the browser stays
//       same-origin behind the viewer's ingress — no CORS) OR a full http(s) origin
//       (dev / cross-origin, e.g. `http://localhost:7260`). Read-only: no edit.
//   (3) otherwise → the worker LSP path (landing card / demo, exactly as today).
//
// A malformed value is a visible error, never a guess-and-fallback (P2). `?demo=`
// keeps its current meaning (worker path); combining `?server=`/`?veles=`/`?demo=`
// is an error — they select different backends.
//
// Why `?veles=` allows a non-loopback origin where `?server=` does not: `?server=`
// targets a *modeling-time* server that owns a local repo, so loopback is the only
// sane value; `?veles=` targets the *deployed* catalog service, which is inherently
// remote. The same-origin path-prefix form keeps the common (in-chart) case CORS-free;
// the full-origin form is an explicit operator/dev choice, CORS- and auth-gated by
// Veles itself (see T5 decisions doc).

// One user-facing `?veles=` concept, dispatched on URL scheme (RO-31 / VS-3):
//   - http(s) origin or same-origin path → the SV tatrman-server JSON read API (`VelesReadApiDataSource`);
//   - ws(s) origin → the platform Veles (`VelesTtrmDataSource`, WS ttrm/* + bearer).
// Both are `kind: 'veles'` (VS-2); the `transport` discriminant picks the adapter. The bearer for the
// ttrm transport comes from `?velesToken=` (dev-only, static v1 — never emitted in a deep link).
export type BackendSelection =
  | { kind: 'ws'; origin: string }
  | { kind: 'veles'; transport: 'read-api'; base: string }
  | { kind: 'veles'; transport: 'ttrm'; origin: string; token: string | null }
  | { kind: 'worker'; demo: string | null };

export class BackendSelectionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BackendSelectionError';
  }
}

function isLoopbackWsOrigin(value: string): boolean {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  if (url.protocol !== 'ws:' && url.protocol !== 'wss:') return false;
  if (url.hostname !== '127.0.0.1' && url.hostname !== 'localhost' && url.hostname !== '[::1]') return false;
  // Origin only — no path/query/fragment (the client owns `/ttrm`).
  return url.pathname === '/' || url.pathname === '';
}

/**
 * Normalize a `?veles=` value to a base the VelesReadApiDataSource concatenates endpoint
 * paths onto. Two accepted forms:
 *   - a same-origin absolute path prefix: starts with `/`, no query/fragment
 *     (e.g. `/veles` → `/veles`; `/` → ``). The browser resolves it against its
 *     own origin, so no CORS.
 *   - a full http(s) ORIGIN: scheme + host [+ port], no path/query/fragment
 *     (e.g. `http://localhost:7260`). Trailing slash stripped.
 * Anything else throws.
 */
function normalizeVelesBase(value: string, what = '`?veles=` value'): string {
  if (value.startsWith('/')) {
    // A `//host`-style value is a PROTOCOL-RELATIVE URL, not a same-origin path — the browser would
    // resolve it against the attacker-chosen host. The doc promises the path branch stays same-origin,
    // so reject it here (a full cross-origin backend must use the explicit `http(s)://` form below).
    if (value.startsWith('//')) {
      throw new BackendSelectionError(
        `Invalid ${what}: ${JSON.stringify(value)}. ` +
          'A leading `//` is a protocol-relative URL (cross-origin); use an explicit http(s):// origin instead.',
      );
    }
    if (/[?#]/.test(value)) {
      throw new BackendSelectionError(
        `Invalid ${what}: ${JSON.stringify(value)}. ` +
          'A same-origin path prefix must carry no query or fragment (e.g. /veles).',
      );
    }
    return value.replace(/\/$/, '');
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new BackendSelectionError(
      `Invalid ${what}: ${JSON.stringify(value)}. ` +
        'Expected a same-origin path prefix (e.g. /veles) or an http(s) origin (e.g. http://localhost:7260).',
    );
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new BackendSelectionError(
      `Invalid ${what}: ${JSON.stringify(value)}. Expected an http(s) origin.`,
    );
  }
  if ((url.pathname !== '/' && url.pathname !== '') || url.search || url.hash) {
    throw new BackendSelectionError(
      `Invalid ${what}: ${JSON.stringify(value)}. ` +
        'Expected an ORIGIN only (no path/query/fragment), e.g. http://localhost:7260.',
    );
  }
  return value.replace(/\/$/, '');
}

/**
 * Normalize a `?veles=ws(s)://…` value to a bare origin (`VelesTtrmDataSource` appends `/v1/ttrm`).
 * Origin only — scheme + host [+ port], no path/query/fragment; trailing slash stripped. A non-loopback
 * origin is allowed (the platform Veles is the *deployed*, inherently-remote service). Anything else throws.
 */
function normalizeVelesWsOrigin(value: string, what = '`?veles=` value'): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new BackendSelectionError(
      `Invalid ${what}: ${JSON.stringify(value)}. Expected a ws(s) origin, e.g. wss://veles.example.`,
    );
  }
  if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
    throw new BackendSelectionError(
      `Invalid ${what}: ${JSON.stringify(value)}. Expected a ws(s) origin.`,
    );
  }
  if ((url.pathname !== '/' && url.pathname !== '') || url.search || url.hash) {
    throw new BackendSelectionError(
      `Invalid ${what}: ${JSON.stringify(value)}. ` +
        'Expected an ORIGIN only (no path/query/fragment) — the client owns /v1/ttrm.',
    );
  }
  return value.replace(/\/$/, '');
}

/** The part of the runtime config backend selection reads (see `runtime-config.ts`). */
export interface BackendDefaults {
  /** the deployment's catalog — the same values `?veles=` accepts; null = none. */
  veles: string | null;
}

/** Dispatch a Veles value on its scheme (VS-3): ws(s) → the platform ttrm transport, else the read API. */
function velesSelection(value: string, token: string | null, what: string): BackendSelection {
  if (/^wss?:\/\//i.test(value)) {
    return { kind: 'veles', transport: 'ttrm', origin: normalizeVelesWsOrigin(value, what), token };
  }
  return { kind: 'veles', transport: 'read-api', base: normalizeVelesBase(value, what) };
}

export function selectBackend(search: string | URLSearchParams, defaults?: BackendDefaults): BackendSelection {
  const params = typeof search === 'string' ? new URLSearchParams(search) : search;
  const server = params.get('server');
  const veles = params.get('veles');
  const demo = params.get('demo');

  const selectors = [server, veles].filter((v) => v !== null).length;
  if (selectors > 1 || (selectors === 1 && demo !== null)) {
    throw new BackendSelectionError(
      '`?server=`, `?veles=`, and `?demo=` select different backends — specify only one.',
    );
  }

  if (veles !== null) {
    // VS-3 scheme dispatch. A ws(s) value → the platform Veles (ttrm + bearer); anything else → the
    // SV read API (unchanged). The bearer is an explicit dev param, never guessed.
    return velesSelection(veles, params.get('velesToken'), '`?veles=` value');
  }

  if (server !== null) {
    if (!isLoopbackWsOrigin(server)) {
      throw new BackendSelectionError(
        `Invalid \`?server=\` value: ${JSON.stringify(server)}. ` +
          'Expected a loopback WS origin, e.g. ws://127.0.0.1:7270 (no path).',
      );
    }
    return { kind: 'ws', origin: server.replace(/\/$/, '') };
  }

  // The deployment default (runtime config) — only when the URL chose nothing, `?demo=` included.
  if (demo === null && defaults?.veles) {
    return velesSelection(defaults.veles, params.get('velesToken'), 'catalog URL in config.json (VIEWER_CATALOG_URL)');
  }

  return { kind: 'worker', demo };
}

/**
 * The workspace segment of a server backend's URLs (`/w/<workspace>/s/…`): a short, stable name —
 * the host of an origin, the last segment of a same-origin path (`/models/veles` → `veles`) — rather
 * than the raw backend value, which percent-encodes into an unreadable path segment (ttr-core#171).
 * The backend itself rides the URL as `?veles=` / `?server=`, or comes from the runtime config.
 */
export function serverWorkspaceName(props: Extract<BackendSelection, { kind: 'ws' | 'veles' }>): string {
  const raw = props.kind === 'ws' || props.transport === 'ttrm' ? props.origin : props.base;
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(raw)) {
    try {
      return new URL(raw).host;
    } catch {
      /* fall through to the path form */
    }
  }
  return raw.split('/').filter((seg) => seg.length > 0).pop() ?? 'catalog';
}
