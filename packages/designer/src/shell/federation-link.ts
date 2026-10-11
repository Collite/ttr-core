// SPDX-License-Identifier: Apache-2.0
//
// Federation bridge (FO-P1.S5, FO contracts §3). Two URL layers coexist by design:
//
//   • the LIVE address bar is the internal shell grammar (§6, `./url.ts`) — richer:
//     workspace + skin + perspective scope/direction. It is designer-private.
//   • the FEDERATION link (§3, `@tatrman/deep-links`) is the stable, copyable,
//     workspace-agnostic projection shared ACROSS Studio surfaces/apps (Viewer,
//     Planner, Data Entry, Iris). It is lossy on purpose: it captures "which
//     object / which perspective", not the internal scope detail.
//
// This module maps between the two — producing a copyable federation URL for the
// active tab, and resolving an inbound federation link to a shell open intent. It
// is pure and carries no edit code (FO-21 safe).

import { buildDeepLink, parseDeepLink, type AskContext, type DeepLink } from '@tatrman/deep-links';
import type { SubjectTab } from './types.js';

/** What an inbound federation link asks the shell to open. `planner-form`/`entry`/
 *  `ask` are OTHER apps' surfaces — they resolve to null here. */
export type FederationOpenIntent =
  | { kind: 'subject'; ref: string }
  | { kind: 'lineage'; root: string }
  | { kind: 'process'; program: string };

/** The object qname a lineage cell ref roots at: `sales.Order:42/total` → `sales.Order`. */
function objectOfCell(cell: string): string {
  const colon = cell.indexOf(':');
  return colon === -1 ? cell : cell.slice(0, colon);
}

/** Project the active tab onto the shareable federation grammar (§3), or null when
 *  the tab has no federation row (e.g. the binding perspective in v1, or no tab). */
export function federationLinkForTab(tab: SubjectTab | null | undefined): DeepLink | null {
  if (!tab) return null;
  const ref = tab.subject.ref;
  if (tab.perspective === 'lineage') return { kind: 'lineage', cell: ref };
  if (tab.perspective === 'binding') return null; // no §3 row for binding in v1
  if (tab.subject.kind === 'program') return { kind: 'process', program: ref };
  return { kind: 'viewer', object: ref };
}

/** The federation APP path for the active tab (`/s/viewer?object=…`), or null when there is
 *  nothing shareable. A surface served under a base path prefixes it (`app-location.ts`). */
export function federationPathForTab(tab: SubjectTab | null | undefined): string | null {
  const link = federationLinkForTab(tab);
  return link ? buildDeepLink(link) : null;
}

/** The absolute, copyable federation URL for the active tab (`prefix` + path), or
 *  null when there is nothing shareable. The surface prepends its own origin — plus its base
 *  path when it is served under one. */
export function federationUrlForTab(tab: SubjectTab | null | undefined, origin: string): string | null {
  const path = federationPathForTab(tab);
  return path ? `${origin}${path}` : null;
}

/** The "Ask about this" payload (§3) for the active view, or null when there is no
 *  tab to ask about. v1 is object-scoped (the open subject); cell/run refinement rides
 *  a later selection-aware pass. */
export function askContextForTab(tab: SubjectTab | null | undefined): AskContext | null {
  if (!tab) return null;
  return { source: 'studio', object: tab.subject.ref };
}

/** The absolute Studio→Iris "ask" URL for the active view, or null when there is
 *  nothing to ask about. `irisBaseUrl` absent at the call site = affordance hidden. */
export function askUrlForTab(tab: SubjectTab | null | undefined, irisBaseUrl: string): string | null {
  const context = askContextForTab(tab);
  if (!context) return null;
  const base = irisBaseUrl.replace(/\/$/, '');
  return `${base}${buildDeepLink({ kind: 'ask', context })}`;
}

/** Resolve an inbound `/s/viewer|lineage|process` federation link to a shell open
 *  intent. Returns null for other apps' surfaces or an unroutable/foreign path. */
export function openIntentForFederationLink(link: DeepLink): FederationOpenIntent | null {
  switch (link.kind) {
    case 'viewer':
      return { kind: 'subject', ref: link.object };
    case 'lineage':
      if (link.cell !== undefined) return { kind: 'lineage', root: objectOfCell(link.cell) };
      return null; // a run= drill has no schema root to open in the Viewer (DS-P4)
    case 'process':
      return { kind: 'process', program: link.program };
    default:
      return null; // planner-form | entry | ask → not a Viewer surface
  }
}

/** Try to read a path+query as a federation open intent; null if it isn't one.
 *  Never throws — a malformed or non-federation path is simply "not for us". */
export function federationIntentFromPath(path: string): FederationOpenIntent | null {
  try {
    return openIntentForFederationLink(parseDeepLink(path));
  } catch {
    return null;
  }
}
