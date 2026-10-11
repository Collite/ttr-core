// SPDX-License-Identifier: Apache-2.0
// URL routing for the Designer shell (contracts §6). The URL is a truth surface:
// parsePath(formatPath(state)) === state. Path segments and query values may hold
// graph uris / qnames (dots, slashes, colons), so every segment/value is percent-encoded.
//
//   /w/:workspace/s/:subjectRef                                          # subject
//   /w/:workspace/s/:subjectRef?skin=md.er-dialect                       # subject + non-default skin
//   /w/:workspace/p/lineage?root=:ref&scope=neighborhood&dir=upstream    # perspective
//   /w/:workspace/p/binding?left=:erScope&sel=:entity
//   /                                                                    # none (no workspace)
//   /w/:workspace                                                        # none (workspace only)

import type { SkinId, UrlState } from './types.js';
import { currentAppPath, replaceAppPath } from './app-location.js';

function seg(value: string): string {
  return encodeURIComponent(value);
}

/** Build the `pathname + search` string for a URL state. */
export function formatPath(state: UrlState): string {
  switch (state.kind) {
    case 'none':
      return state.workspace === undefined ? '/' : `/w/${seg(state.workspace)}`;

    // `unknownSubject` is a parse-time classification, not a distinct URL — it formats
    // exactly like `subject` (same path, no skin surface).
    case 'unknownSubject':
      return `/w/${seg(state.workspace)}/s/${seg(state.subjectRef)}`;

    case 'subject': {
      const base = `/w/${seg(state.workspace)}/s/${seg(state.subjectRef)}`;
      // Truth surface: skin appears ONLY when a non-default skin is chosen.
      return state.skin === undefined ? base : `${base}?skin=${seg(state.skin)}`;
    }

    case 'perspective': {
      const base = `/w/${seg(state.workspace)}/p/${state.perspective}`;
      const query = new URLSearchParams(state.params).toString();
      return query.length === 0 ? base : `${base}?${query}`;
    }
  }
}

/**
 * Parse a `location.pathname + location.search` string back into a URL state.
 * Malformed paths degrade to `{ kind: 'none' }` — never throws.
 *
 * DS-SHELL-001: when `knownSubjectRefs` is supplied and a subject's ref is not in it,
 * the state is classified as `unknownSubject` (still no throw).
 */
export function parsePath(path: string, knownSubjectRefs?: ReadonlySet<string>): UrlState {
  const qIndex = path.indexOf('?');
  const pathname = qIndex === -1 ? path : path.slice(0, qIndex);
  const search = qIndex === -1 ? '' : path.slice(qIndex + 1);
  const query = new URLSearchParams(search);

  const parts = pathname.split('/').filter((p) => p.length > 0);

  // No `/w/` prefix → none.
  if (parts[0] !== 'w' || parts.length < 2) {
    return { kind: 'none' };
  }

  const workspace = decodeURIComponent(parts[1]);

  // `/w/:ws` only → none with workspace.
  if (parts.length === 2) {
    return { kind: 'none', workspace };
  }

  const facet = parts[2];

  if (facet === 's' && parts.length === 4) {
    const subjectRef = decodeURIComponent(parts[3]);
    if (knownSubjectRefs !== undefined && !knownSubjectRefs.has(subjectRef)) {
      return { kind: 'unknownSubject', workspace, subjectRef };
    }
    const skin = query.get('skin');
    if (skin === null) {
      return { kind: 'subject', workspace, subjectRef };
    }
    return { kind: 'subject', workspace, subjectRef, skin: skin as SkinId };
  }

  if (facet === 'p' && parts.length === 4) {
    const perspective = parts[3];
    if (perspective === 'binding' || perspective === 'lineage') {
      const params: Record<string, string> = {};
      for (const [k, v] of query.entries()) {
        params[k] = v;
      }
      return { kind: 'perspective', workspace, perspective, params };
    }
  }

  // Anything else is malformed → none.
  return { kind: 'none' };
}

// ---- thin browser sync (pure functions above are the core) ----
// These paths are APP paths: `app-location.ts` adds the base path the viewer is served under and
// carries the session parameters (`?veles=`, `?lang=`, …) across the rewrite (ttr-core#171).

/** Replace the current history entry with the URL for `state` (no-op outside a browser). */
export function syncUrl(state: UrlState): void {
  replaceAppPath(formatPath(state));
}

/** Read the current browser location into a URL state (returns `none` outside a browser). */
export function readUrl(known?: ReadonlySet<string>): UrlState {
  if (typeof window === 'undefined') return { kind: 'none' };
  return parsePath(currentAppPath(), known);
}
