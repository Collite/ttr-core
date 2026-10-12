// SPDX-License-Identifier: Apache-2.0
// Shell tab + drill state (DS-P2 S1). Pure reducer functions over ShellState — tab = subject
// (A-2 β), preview-tab discipline (S-7: transient preview, promoted on pin/edit), in-tab drill
// breadcrumb (P-2, both faces). No React here; the shell components drive these.

import type { SkinId } from '@tatrman/canvas-core';
import type { LayoutPoint } from '../layout/layout-document.js';
import type { ShellState, Subject, SubjectTab, DrillSegment, LayoutSource, TabLayout } from './types.js';

export const emptyShell: ShellState = { tabs: [], activeTabId: null };

const tabIdFor = (subject: Subject, perspective?: 'binding' | 'lineage'): string =>
  perspective ? `${subject.ref}#${perspective}` : subject.ref;

function makeTab(subject: Subject, preview: boolean, perspective?: 'binding' | 'lineage'): SubjectTab {
  return { id: tabIdFor(subject, perspective), subject, perspective, drillPath: [], preview };
}

/**
 * Open (or focus) a subject. Existing tab ⇒ focus, never duplicate (and promote out of preview
 * if a non-preview open was requested). New tab: a preview open replaces the current preview
 * tab (only one transient preview at a time); a pinned open is added outright.
 */
export function openSubject(
  state: ShellState,
  subject: Subject,
  opts: { preview?: boolean; perspective?: 'binding' | 'lineage' } = {},
): ShellState {
  const preview = opts.preview ?? true;
  const id = tabIdFor(subject, opts.perspective);
  const existing = state.tabs.find((t) => t.id === id);
  if (existing) {
    const tabs = preview ? state.tabs : state.tabs.map((t) => (t.id === id ? { ...t, preview: false } : t));
    return { tabs, activeTabId: id };
  }
  const newTab = makeTab(subject, preview, opts.perspective);
  // a preview open evicts the previous (unpinned) preview tab
  const kept = preview ? state.tabs.filter((t) => !t.preview) : state.tabs;
  return { tabs: [...kept, newTab], activeTabId: id };
}

export function focusTab(state: ShellState, id: string): ShellState {
  if (!state.tabs.some((t) => t.id === id)) return state;
  return { ...state, activeTabId: id };
}

export function closeTab(state: ShellState, id: string): ShellState {
  const idx = state.tabs.findIndex((t) => t.id === id);
  if (idx < 0) return state;
  const tabs = state.tabs.filter((t) => t.id !== id);
  let activeTabId = state.activeTabId;
  if (activeTabId === id) {
    const next = tabs[idx] ?? tabs[idx - 1] ?? null;
    activeTabId = next?.id ?? null;
  }
  return { tabs, activeTabId };
}

/** pin/edit-gesture promotes a preview tab to a durable one (S-7). */
export function promoteTab(state: ShellState, id: string): ShellState {
  return { ...state, tabs: state.tabs.map((t) => (t.id === id ? { ...t, preview: false } : t)) };
}

/** navigating away from an UNPINNED preview tab drops it (preview discipline). */
export function dropPreviewIfLeaving(state: ShellState, newActiveId: string): ShellState {
  const leaving = state.tabs.find((t) => t.id === state.activeTabId);
  const tabs = leaving && leaving.preview && leaving.id !== newActiveId
    ? state.tabs.filter((t) => t.id !== leaving.id)
    : state.tabs;
  return { tabs, activeTabId: newActiveId };
}

export function setSkin(state: ShellState, id: string, skin: SkinId): ShellState {
  return { ...state, tabs: state.tabs.map((t) => (t.id === id ? { ...t, skin } : t)) };
}

/**
 * In-tab perspective switch (A-2 β): flip the SAME subject tab between its canvas (undefined) and
 * a derived perspective, WITHOUT changing the tab id — so the tab, its breadcrumb, and the
 * underlying canvas state (positions held by subject.ref) survive the round-trip. (Distinct from
 * a perspective pop-out, which openSubject()s a separate `ref#perspective` preview tab.)
 */
export function setPerspective(state: ShellState, id: string, perspective?: 'binding' | 'lineage'): ShellState {
  return updateTab(state, id, (t) => ({ ...t, perspective }));
}

// ---- in-tab drill (P-2 — same gesture family on both faces) ----
export function drillIn(state: ShellState, id: string, segment: DrillSegment): ShellState {
  return updateTab(state, id, (t) => ({ ...t, drillPath: [...t.drillPath, segment] }));
}

export function drillOut(state: ShellState, id: string): ShellState {
  return updateTab(state, id, (t) => ({ ...t, drillPath: t.drillPath.slice(0, -1) }));
}

/** breadcrumb click: pop to a level. index -1 = the subject root (empty drill path). */
export function drillTo(state: ShellState, id: string, index: number): ShellState {
  return updateTab(state, id, (t) => ({ ...t, drillPath: t.drillPath.slice(0, index + 1) }));
}

function updateTab(state: ShellState, id: string, fn: (t: SubjectTab) => SubjectTab): ShellState {
  return { ...state, tabs: state.tabs.map((t) => (t.id === id ? fn(t) : t)) };
}

export const activeTab = (state: ShellState): SubjectTab | null =>
  state.tabs.find((t) => t.id === state.activeTabId) ?? null;

// ---- the working layout (VL contracts §5: V-22, V-28, V-30, V-35) ----

/** A layout tab's subject ref (V-35): a catalog layout is addressed by its name, a local one by `file:` / `new:`. */
export function layoutRef(source: LayoutSource, name: string): string {
  return source === 'catalog' ? name : `${source}:${name}`;
}

/** The subject a layout tab shows: `<model> · <name>`. */
export function layoutSubject(layout: TabLayout): Subject {
  const { doc, source } = layout;
  return { ref: layoutRef(source, doc.name), kind: 'schema', schemaCode: doc.model, label: `${doc.model} · ${doc.name}` };
}

/** `base`, or `base~2`, `base~3`… — the first id no open tab uses (two local files may share a name). */
function freeTabId(state: ShellState, base: string, except?: string): string {
  const taken = new Set(state.tabs.filter((t) => t.id !== except).map((t) => t.id));
  if (!taken.has(base)) return base;
  for (let n = 2; ; n++) if (!taken.has(`${base}~${n}`)) return `${base}~${n}`;
}

/**
 * Open a layout as a durable tab and focus it (V-31, V-34). A catalog layout already open is focused,
 * never reset — its unsaved edits survive; a local file always gets its own tab.
 */
export function openLayoutTab(state: ShellState, layout: TabLayout): ShellState {
  const subject = layoutSubject(layout);
  if (layout.source === 'catalog') {
    const open = state.tabs.find((t) => t.layout?.source === 'catalog' && t.subject.ref === subject.ref);
    if (open) return { ...state, activeTabId: open.id };
  }
  const tab: SubjectTab = { id: freeTabId(state, subject.ref), subject, drillPath: [], preview: false, layout };
  return { tabs: [...state.tabs, tab], activeTabId: tab.id };
}

/**
 * Turn a plain subject tab into a layout tab in place (V-22: the whole schema's first selection edit
 * makes it `er · untitled*`). The tab keeps its position in the bar; its id follows the new subject.
 */
export function toLayoutTab(state: ShellState, tabId: string, layout: TabLayout): ShellState {
  const subject = layoutSubject(layout);
  const id = freeTabId(state, subject.ref, tabId);
  return {
    tabs: state.tabs.map((t) => (t.id === tabId ? { ...t, id, subject, perspective: undefined, preview: false, layout } : t)),
    activeTabId: state.activeTabId === tabId ? id : state.activeTabId,
  };
}

function updateLayout(state: ShellState, tabId: string, fn: (l: TabLayout) => TabLayout): ShellState {
  return updateTab(state, tabId, (t) => (t.layout ? { ...t, layout: fn(t.layout) } : t));
}

/**
 * The selection changed (V-19): `objects` replaces the cards; positions of cards no longer shown are
 * dropped, `placed` (new cards' positions) are added. Marks the layout dirty.
 */
export function setLayoutObjects(
  state: ShellState,
  tabId: string,
  objects: string[],
  placed: Record<string, LayoutPoint> = {},
): ShellState {
  return updateLayout(state, tabId, (l) => {
    const keep = new Set(objects);
    const positions: Record<string, LayoutPoint> = {};
    for (const [ref, p] of Object.entries(l.doc.positions)) if (keep.has(ref)) positions[ref] = p;
    for (const [ref, p] of Object.entries(placed)) if (keep.has(ref)) positions[ref] = p;
    const collapsed = l.doc.collapsed?.filter((ref) => keep.has(ref));
    return { ...l, dirty: true, doc: { ...l.doc, objects: [...objects], positions, ...(collapsed ? { collapsed } : {}) } };
  });
}

/** Positions changed (a drag, Re-layout — V-28): replaces them, and the skin / collapsed when given. Marks dirty. */
export function setLayoutPositions(
  state: ShellState,
  tabId: string,
  positions: Record<string, LayoutPoint>,
  extra: { skin?: string; collapsed?: string[] } = {},
): ShellState {
  return updateLayout(state, tabId, (l) => {
    const doc = { ...l.doc, positions: { ...positions } };
    if (extra.skin !== undefined) doc.skin = extra.skin;
    if (extra.collapsed !== undefined) doc.collapsed = [...extra.collapsed];
    return { ...l, dirty: true, doc };
  });
}

/** Save as… succeeded (V-32): the tab is now the local file `fileName`, named `name`, clean. */
export function markSaved(state: ShellState, tabId: string, name: string, fileName: string): ShellState {
  const tab = state.tabs.find((t) => t.id === tabId);
  if (!tab?.layout) return state;
  const layout: TabLayout = { doc: { ...tab.layout.doc, name }, source: 'file', fileName, dirty: false };
  const subject = layoutSubject(layout);
  const id = freeTabId(state, subject.ref, tabId);
  return {
    tabs: state.tabs.map((t) => (t.id === tabId ? { ...t, id, subject, layout } : t)),
    activeTabId: state.activeTabId === tabId ? id : state.activeTabId,
  };
}

/** Whether leaving the page loses work (V-30): a `file` / `new` layout with unsaved edits. */
export function hasUnsavedLocalLayout(state: ShellState): boolean {
  return state.tabs.some((t) => t.layout && t.layout.dirty && t.layout.source !== 'catalog');
}

