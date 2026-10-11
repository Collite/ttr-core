// SPDX-License-Identifier: Apache-2.0
// Model labels in the viewer's language (ttr-core#170). The catalog serves every language a model
// authors — `displayLabel: { en: "Product", cs: "Produkt" }`, `description: { en: …, cs: … }`
// (ttr-server#185) — and the viewer picks one at runtime. This module is that choice, plus the
// fallbacks:
//
//   label        displayLabel[lang] → the identifier (`item`)
//   description  [lang] → the plain form → [en] → the first by language code → none  (Veles' D7 chain)
//
// The language itself: `?lang=` → the user's saved choice → the deployment default (runtime
// config) → the browser's languages → `en`.
//
// The viewer's own chrome (buttons, headings) stays English; translating it is a separate item.

import { getRuntimeConfig, isLanguageTag } from '../runtime-config.js';

/** A `{ language tag: text }` map, as Veles serves `displayLabel` / `descriptionLocalized`. */
export type LocalizedText = Readonly<Record<string, string>>;

export const DEFAULT_LANGUAGE = 'en';

/** localStorage key of the language a user picked in the header. */
export const LANGUAGE_STORAGE_KEY = 'ttr-viewer.lang';

const primary = (tag: string): string => tag.toLowerCase().split('-')[0];

/**
 * The text for `lang` in a localized map: the exact tag first (case-insensitive), then the same
 * primary language (`cs-CZ` finds `cs`, `pt` finds `pt-BR`). Blank entries count as absent.
 */
export function pickLanguage(text: LocalizedText | undefined, lang: string): string | undefined {
  if (!text) return undefined;
  const entries = Object.entries(text).filter(([, value]) => typeof value === 'string' && value.trim() !== '');
  const wanted = lang.toLowerCase();
  const exact = entries.find(([key]) => key.toLowerCase() === wanted);
  if (exact) return exact[1];
  const samePrimary = entries.find(([key]) => primary(key) === primary(wanted));
  return samePrimary?.[1];
}

/** A node's label: its `displayLabel` in `lang`, else its identifier. */
export function localizedLabel(identifier: string, displayLabel: LocalizedText | undefined, lang: string): string {
  return pickLanguage(displayLabel, lang) ?? identifier;
}

/**
 * A description in `lang`, by Veles' D7 chain: the requested language → the plain form → `en` →
 * the first entry by language code → none. At most one of the two forms is ever authored.
 */
export function localizedDescription(
  plain: string | undefined,
  localized: LocalizedText | undefined,
  lang: string,
): string | undefined {
  const requested = pickLanguage(localized, lang);
  if (requested) return requested;
  if (plain && plain.trim() !== '') return plain;
  const english = pickLanguage(localized, DEFAULT_LANGUAGE);
  if (english) return english;
  const first = Object.keys(localized ?? {})
    .sort()
    .map((key) => localized![key])
    .find((value) => typeof value === 'string' && value.trim() !== '');
  return first;
}

/** Every language a set of localized maps carries, lowercased, sorted. */
export function languagesOf(texts: ReadonlyArray<LocalizedText | undefined>): string[] {
  const out = new Set<string>();
  for (const text of texts) for (const key of Object.keys(text ?? {})) if (isLanguageTag(key)) out.add(key.toLowerCase());
  return [...out].sort();
}

export interface LanguageSources {
  /** `?lang=` */
  query?: string | null;
  /** the user's saved choice (the header picker) */
  saved?: string | null;
  /** the deployment default (`VIEWER_DEFAULT_LANG` → config.json `lang`) */
  configured?: string | null;
  /** `navigator.languages` */
  browser?: readonly string[];
}

/** The viewer's language: the first valid of `?lang=` → saved → configured → the browser's → `en`. */
export function resolveLanguage(sources: LanguageSources): string {
  for (const candidate of [sources.query, sources.saved, sources.configured]) {
    if (candidate && isLanguageTag(candidate)) return candidate.toLowerCase();
  }
  for (const tag of sources.browser ?? []) {
    // the browser says `cs-CZ`; models key their labels by language (`cs`).
    if (tag && isLanguageTag(tag)) return primary(tag);
  }
  return DEFAULT_LANGUAGE;
}

function readSaved(): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(LANGUAGE_STORAGE_KEY);
  } catch {
    return null; // storage blocked (private mode, sandbox) — the choice just doesn't persist
  }
}

/** Remember the user's language for the next visit (best effort). */
export function saveLanguage(lang: string): void {
  try {
    if (typeof localStorage !== 'undefined') localStorage.setItem(LANGUAGE_STORAGE_KEY, lang);
  } catch {
    /* storage blocked — the URL still carries ?lang= */
  }
}

/** The language this page load starts in. */
export function initialLanguage(): string {
  const query = typeof window === 'undefined' ? null : new URLSearchParams(window.location.search).get('lang');
  const browser = typeof navigator === 'undefined' ? [] : navigator.languages ?? [navigator.language];
  return resolveLanguage({ query, saved: readSaved(), configured: getRuntimeConfig().lang, browser });
}
