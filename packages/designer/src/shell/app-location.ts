// SPDX-License-Identifier: Apache-2.0
// The address bar, split into what the shell owns and what it only carries (ttr-core#171).
//
//   <origin> <base path> <app path>      ? <app query> & <session query>
//            /models/    w/veles/s/er    ? skin=…      & veles=/models/veles&lang=cs
//
// The shell's URL grammar (`url.ts`, contracts §6) speaks APP paths: `/w/:ws/s/:ref`, `/s/viewer?…`.
// This module maps them to and from the browser location. It strips and re-adds the base path the
// viewer is served under (the runtime config's `basePath`), and it carries the session parameters
// that chose the backend and the language across every rewrite. Without both, a viewer under a
// sub-path navigated out of its own prefix, and a reload of a rewritten URL lost the catalog.

import { getRuntimeConfig } from '../runtime-config.js';

/** Query parameters that choose the session (backend, language): kept on every rewrite. */
export const STICKY_PARAMS = ['veles', 'server', 'demo', 'manifest', 'lang'] as const;

/**
 * Session parameters the app reads but never writes back. `velesToken` is a dev-only bearer for the
 * ttrm transport: it is never emitted into a URL the shell produces, so a rewrite drops it.
 */
const SESSION_ONLY_PARAMS = ['velesToken'] as const;

const SESSION_PARAMS: ReadonlySet<string> = new Set<string>([...STICKY_PARAMS, ...SESSION_ONLY_PARAMS]);

/** `/models/w/a/s/b` → `/w/a/s/b` under `/models/`. A path outside the base is returned as is. */
export function stripBasePath(pathname: string, basePath: string): string {
  if (basePath === '/') return pathname === '' ? '/' : pathname;
  if (pathname === basePath.slice(0, -1)) return '/';
  if (pathname.startsWith(basePath)) return `/${pathname.slice(basePath.length)}`;
  return pathname;
}

/** The app path of a browser location: base path stripped, session parameters removed. */
export function toAppPath(pathname: string, search: string, basePath: string): string {
  const appPathname = stripBasePath(pathname, basePath);
  const query = new URLSearchParams(search);
  for (const key of SESSION_PARAMS) query.delete(key);
  const qs = query.toString();
  return qs ? `${appPathname}?${qs}` : appPathname;
}

/** The sticky parameters of a browser query string, in their canonical order. */
export function stickyParams(search: string): Array<[string, string]> {
  const query = new URLSearchParams(search);
  const out: Array<[string, string]> = [];
  for (const key of STICKY_PARAMS) {
    const value = query.get(key);
    if (value !== null) out.push([key, value]);
  }
  return out;
}

/** A query value, percent-encoded but keeping `/` and `:` readable (both legal in a query). */
function encodeQueryValue(value: string): string {
  return encodeURIComponent(value).replace(/%2F/gi, '/').replace(/%3A/gi, ':');
}

/** The browser `pathname + search` for an app path: base path prefixed, sticky parameters appended. */
export function toBrowserPath(appPath: string, basePath: string, sticky: Array<[string, string]>): string {
  const qIndex = appPath.indexOf('?');
  const appPathname = qIndex === -1 ? appPath : appPath.slice(0, qIndex);
  const appQuery = qIndex === -1 ? '' : appPath.slice(qIndex + 1);
  const pathname = basePath + appPathname.replace(/^\/+/, '');
  const stickyQuery = sticky
    .filter(([key]) => !new URLSearchParams(appQuery).has(key))
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeQueryValue(value)}`)
    .join('&');
  const query = [appQuery, stickyQuery].filter((part) => part.length > 0).join('&');
  return query ? `${pathname}?${query}` : pathname;
}

// ---- thin browser bindings (the pure functions above are the core) ----

/** The current location as an app path (`/w/…/s/…?skin=…`), or `/` outside a browser. */
export function currentAppPath(): string {
  if (typeof window === 'undefined') return '/';
  return toAppPath(window.location.pathname, window.location.search, getRuntimeConfig().basePath);
}

/** The browser path for an app path, carrying the current session parameters. */
export function browserPathFor(appPath: string): string {
  const search = typeof window === 'undefined' ? '' : window.location.search;
  return toBrowserPath(appPath, getRuntimeConfig().basePath, stickyParams(search));
}

/** The absolute URL for an app path (a copyable link): origin + base path + app path + session. */
export function absoluteUrlFor(appPath: string): string {
  const origin = typeof window === 'undefined' ? '' : window.location.origin;
  return origin + browserPathFor(appPath);
}

/** Replace the current history entry with the given app path (no-op outside a browser). */
export function replaceAppPath(appPath: string): void {
  if (typeof window === 'undefined') return;
  const next = browserPathFor(appPath);
  if (next !== window.location.pathname + window.location.search) window.history.replaceState(null, '', next);
}

/** Set (or, with `null`, drop) one sticky parameter in the address bar, keeping everything else. */
export function setStickyParam(key: (typeof STICKY_PARAMS)[number], value: string | null): void {
  if (typeof window === 'undefined') return;
  const query = new URLSearchParams(window.location.search);
  if (value === null) query.delete(key);
  else query.set(key, value);
  const next = toBrowserPath(currentAppPath(), getRuntimeConfig().basePath, stickyParams(`?${query.toString()}`));
  window.history.replaceState(null, '', next);
}
