// SPDX-License-Identifier: Apache-2.0
// Runtime configuration (ttr-core#169). A deployed viewer reads `config.json` from beside its
// index.html once, at startup, before the first render. The container image's entrypoint renders
// that file from its environment (`VIEWER_BASE_PATH`, `VIEWER_CATALOG_URL`, …), so one image serves
// any base path and any catalog without a rebuild.
//
// No config file (the Vite dev server, a plain static host) ⇒ the defaults: the build-time base
// (`DESIGNER_BASE_URL`, `/` by default), no catalog (the worker landing card), and no language
// preference. `?veles=` / `?server=` / `?demo=` in the URL still win over the file, so local use
// keeps working against a deployed bundle.

export interface RuntimeConfig {
  /** The path prefix the viewer is served under, with a leading and a trailing slash: `/`, `/models/`. */
  basePath: string;
  /**
   * The catalog the viewer opens when the URL selects no backend: any value `?veles=` accepts — a
   * same-origin path (`/veles`), an http(s) origin, or a ws(s) origin. `null` ⇒ no catalog (the
   * worker landing card).
   */
  veles: string | null;
  /** The default language for model labels and descriptions; `null` ⇒ the browser's language, then `en`. */
  lang: string | null;
  /** Languages the language picker offers on top of the ones the model's labels carry. */
  languages: string[];
}

/** A path prefix: `/`, or `/seg/…/seg/` with URL-safe segment characters. */
const BASE_PATH_RE = /^\/(?:[A-Za-z0-9._~-]+\/)*$/;
/** A BCP 47-ish language tag: `en`, `cs`, `pt-BR`. */
const LANG_RE = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/;

export function isBasePath(value: unknown): value is string {
  return typeof value === 'string' && BASE_PATH_RE.test(value);
}

export function isLanguageTag(value: unknown): value is string {
  return typeof value === 'string' && LANG_RE.test(value);
}

/**
 * The base path when no config file names one. A build with an absolute base
 * (`DESIGNER_BASE_URL=/models/`) knows its prefix; the container build uses a relative base
 * (`./`), whose prefix the entrypoint states twice — in `config.json` and as `<base href>` — so
 * the document's base URL is the fallback there.
 */
export function defaultBasePath(buildBase: string = import.meta.env.BASE_URL, documentBase?: string): string {
  if (isBasePath(buildBase)) return buildBase;
  const docBase = documentBase ?? (typeof document !== 'undefined' ? document.baseURI : undefined);
  if (docBase) {
    try {
      const path = new URL(docBase).pathname;
      const dir = path.endsWith('/') ? path : path.slice(0, path.lastIndexOf('/') + 1);
      if (isBasePath(dir)) return dir;
    } catch {
      /* not a URL — fall through */
    }
  }
  return '/';
}

export function defaultRuntimeConfig(): RuntimeConfig {
  return { basePath: defaultBasePath(), veles: null, lang: null, languages: [] };
}

/**
 * Validate a parsed `config.json` against the defaults. A bad field is dropped with a console
 * warning and its default kept: a typo in one env var must not take the whole viewer down, and the
 * entrypoint already refuses the values it can check.
 */
export function parseRuntimeConfig(raw: unknown, defaults: RuntimeConfig = defaultRuntimeConfig()): RuntimeConfig {
  const out: RuntimeConfig = { ...defaults, languages: [...defaults.languages] };
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return out;
  const r = raw as Record<string, unknown>;
  const warn = (field: string) => console.warn(`[viewer] config.json: ignoring an invalid "${field}": ${JSON.stringify(r[field])}`);

  if ('basePath' in r) {
    if (isBasePath(r.basePath)) out.basePath = r.basePath;
    else warn('basePath');
  }
  if ('veles' in r) {
    if (r.veles === null || (typeof r.veles === 'string' && r.veles.trim() === '')) out.veles = null;
    else if (typeof r.veles === 'string') out.veles = r.veles.trim();
    else warn('veles');
  }
  if ('lang' in r) {
    if (r.lang === null || r.lang === '') out.lang = null;
    else if (isLanguageTag(r.lang)) out.lang = r.lang.toLowerCase();
    else warn('lang');
  }
  if ('languages' in r) {
    if (Array.isArray(r.languages) && r.languages.every(isLanguageTag)) {
      out.languages = [...new Set((r.languages as string[]).map((l) => l.toLowerCase()))];
    } else {
      warn('languages');
    }
  }
  return out;
}

/** Where `config.json` lives: beside index.html, under the base path. */
export function configUrl(buildBase: string = import.meta.env.BASE_URL): string {
  if (isBasePath(buildBase)) return `${buildBase}config.json`;
  // relative build base (`./`) — resolve against the document's base URL (`<base href>` in the image).
  return typeof document !== 'undefined' ? new URL('config.json', document.baseURI).href : 'config.json';
}

/**
 * Fetch and parse `config.json`. Every failure — no file (404), the dev server's SPA fallback
 * answering with index.html, a network error, malformed JSON — yields the defaults: the file is
 * optional by design.
 */
export async function loadRuntimeConfig(fetchImpl: typeof fetch = globalThis.fetch?.bind(globalThis)): Promise<RuntimeConfig> {
  const defaults = defaultRuntimeConfig();
  if (!fetchImpl) return defaults;
  try {
    const res = await fetchImpl(configUrl(), { cache: 'no-store', headers: { Accept: 'application/json' } });
    if (!res.ok) return defaults;
    const text = await res.text();
    if (!text.trimStart().startsWith('{')) return defaults; // the SPA fallback's index.html
    return parseRuntimeConfig(JSON.parse(text), defaults);
  } catch {
    return defaults;
  }
}

let current: RuntimeConfig | null = null;

/** The config the app booted with (the defaults until `setRuntimeConfig` runs). */
export function getRuntimeConfig(): RuntimeConfig {
  if (current === null) current = defaultRuntimeConfig();
  return current;
}

/** Install the loaded config (main.tsx, before the first render) — or a test's. `null` resets. */
export function setRuntimeConfig(config: RuntimeConfig | null): void {
  current = config;
}
