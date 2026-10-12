// SPDX-License-Identifier: Apache-2.0
// The view-state seam, reconciled across the three backends (Designer Merge, DM-P2.S1 /
// contracts §3, inherited from DM-P1). One `ViewStateStore` (`@tatrman/canvas-core`, contracts §7)
// with three impls, selected by the active backend's `capabilities.layoutPersist`:
//
//   in-file  → WorkerLayoutStore    (modeler/getLayout+setLayout → the .ttrg `layout` block)
//   sidecar  → WsSidecarStore       (ttrm/getLayout+setLayout → the .ttrl sidecar on disk)
//   file     → LayoutDocumentStore  (the tab's layout in memory; Save as… downloads it — VL V-28/V-29)
//
// Ported from modeler DS-v1 `canvas/view-state-store.ts` (`TtrgLayoutBlockStore` → `WorkerLayoutStore`,
// @modeler/*→@tatrman/*) and extended with the WS/.ttrl + Veles/none impls that DM-P1 refined here as
// shell-coupled. IO is injected so every store unit-tests with plain in-memory fakes — no live LSP/WS.
//
// Invariants (DS, unchanged): the viewport is NEVER surfaced in view-state and NEVER written from it
// (invariant 6 / C1-b — `write` is a read-modify-write preserving the existing viewport + edges);
// positions survive a skin switch (C1-b-iv); skin/mode/collapsed the .ttrg block can't carry live in
// workspace-local prefs, flagged `skinStorage:'prefs'` so the truth chip stays honest (GQ-1).

import type { CanvasViewState, ViewStateStore, SkinId } from '@tatrman/canvas-core';
import type { LayoutFile } from '@tatrman/lsp';
import type { TtrmLayoutPayload, TtrmLayoutCanvas } from './ttrm-types.js';

const FALLBACK_SKIN = 'er.crow';

// ── in-file (.ttrg layout block) — the Worker backend ───────────────────────

/** Node-position + viewport + edge persistence — the modeler/getLayout+setLayout pair. */
export interface LayoutIO {
  getLayout(canvasKey: string): Promise<LayoutFile>;
  setLayout(canvasKey: string, layout: LayoutFile): Promise<void>;
}

/** The fields the `.ttrg` layout block can't carry yet (GQ-1) live in workspace-local prefs. */
export type PrefsRecord = { skin?: string; mode?: 'auto' | 'manual'; collapsed?: string[] };

export interface PrefsIO {
  get(canvasKey: string): PrefsRecord | undefined;
  set(canvasKey: string, v: PrefsRecord): void;
}

export interface WorkerLayoutStoreOptions {
  defaultSkin?: string;
}

export class WorkerLayoutStore implements ViewStateStore {
  readonly #io: LayoutIO;
  readonly #prefs: PrefsIO;
  readonly #defaultSkin: string;

  constructor(io: LayoutIO, prefs: PrefsIO, opts?: WorkerLayoutStoreOptions) {
    this.#io = io;
    this.#prefs = prefs;
    this.#defaultSkin = opts?.defaultSkin ?? FALLBACK_SKIN;
  }

  async read(canvasKey: string): Promise<CanvasViewState> {
    const layout = await this.#io.getLayout(canvasKey);
    const prefs = this.#prefs.get(canvasKey);

    // Positions map verbatim; viewport is deliberately dropped (invariant 6 / C1-b).
    const nodes: CanvasViewState['nodes'] = { ...layout.nodes };

    // mode: prefs pins it if present, else derive from whether any positions exist.
    const mode: 'auto' | 'manual' =
      prefs?.mode ?? (Object.keys(nodes).length > 0 ? 'manual' : 'auto');

    const skin = prefs?.skin ?? this.#defaultSkin;
    const collapsed = prefs?.collapsed ?? [];

    return {
      skin,
      mode,
      nodes,
      collapsed,
      // skin/mode/collapsed are sourced from prefs because the .ttrg block can't hold them (GQ-1).
      skinStorage: 'prefs',
    };
  }

  async write(canvasKey: string, vs: CanvasViewState): Promise<void> {
    // Read-modify-write: preserve the existing viewport + edges; only positions come from
    // view-state. NEVER write a viewport from view-state (invariant 6 / C1-b).
    const cur = await this.#io.getLayout(canvasKey);
    await this.#io.setLayout(canvasKey, {
      ...cur,
      version: 1,
      nodes: { ...vs.nodes },
    });

    // Fields the .ttrg block can't carry go to workspace-local prefs (GQ-1).
    this.#prefs.set(canvasKey, {
      skin: vs.skin,
      mode: vs.mode,
      collapsed: vs.collapsed,
    });
  }
}

// ── sidecar (.ttrl) — the WS ttr-designer-server backend ────────────────────

/** The `.ttrl` sidecar read/write pair — the `ttrm/getLayout`+`ttrm/setLayout` methods. */
export interface TtrmLayoutIO {
  getLayout(uri: string): Promise<TtrmLayoutPayload>;
  setLayout(uri: string, canvases: TtrmLayoutCanvas[]): Promise<void>;
}

export interface WsSidecarStoreOptions {
  defaultSkin?: string;
}

/**
 * A `canvasKey` addresses one canvas inside one `.ttrg` document — `<uri>#<canvasName>`. The `.ttrl`
 * sidecar is per-document and holds ALL of the document's canvases, so read/write target one canvas
 * within the payload, preserving the siblings (read-modify-write).
 */
export function parseCanvasKey(canvasKey: string): { uri: string; canvas: string } {
  const hash = canvasKey.lastIndexOf('#');
  return hash === -1
    ? { uri: canvasKey, canvas: 'main' }
    : { uri: canvasKey.slice(0, hash), canvas: canvasKey.slice(hash + 1) };
}

export class WsSidecarStore implements ViewStateStore {
  readonly #io: TtrmLayoutIO;
  readonly #defaultSkin: string;

  constructor(io: TtrmLayoutIO, opts?: WsSidecarStoreOptions) {
    this.#io = io;
    this.#defaultSkin = opts?.defaultSkin ?? FALLBACK_SKIN;
  }

  async read(canvasKey: string): Promise<CanvasViewState> {
    const { uri, canvas } = parseCanvasKey(canvasKey);
    const payload = await this.#io.getLayout(uri);
    const found = payload.canvases.find((c) => c.key === canvas);
    if (!found) {
      // No sidecar entry yet → auto view-state (the sidecar carries skin when present).
      return { skin: this.#defaultSkin, mode: 'auto', nodes: {}, collapsed: [], skinStorage: 'ttrg' };
    }
    const nodes: CanvasViewState['nodes'] = {};
    for (const n of found.nodes) nodes[n.qname] = { x: n.x, y: n.y };
    return {
      skin: (found.skin ?? this.#defaultSkin) as SkinId,
      mode: found.mode,
      nodes,
      collapsed: [...found.collapsed],
      // the .ttrl sidecar carries skin/mode/collapsed directly (no GQ-1 prefs split).
      skinStorage: 'ttrg',
    };
  }

  async write(canvasKey: string, vs: CanvasViewState): Promise<void> {
    const { uri, canvas } = parseCanvasKey(canvasKey);
    const cur = await this.#io.getLayout(uri);
    const entry: TtrmLayoutCanvas = {
      key: canvas,
      skin: vs.skin,
      mode: vs.mode,
      nodes: Object.entries(vs.nodes).map(([qname, p]) => ({ qname, x: p.x, y: p.y })),
      collapsed: [...vs.collapsed],
    };
    // Read-modify-write: replace this canvas, keep the document's other canvases untouched.
    const others = cur.canvases.filter((c) => c.key !== canvas);
    await this.#io.setLayout(uri, [...others, entry]);
  }
}

// ── file — the catalog backends: the layout lives in the tab, Save as… downloads it ──

/** One canvas's layout state as the tab holds it (VL V-28): positions, skin, collapsed. */
export interface LayoutDocState {
  positions: Record<string, { x: number; y: number }>;
  skin?: string;
  collapsed?: string[];
}

/** Where a `LayoutDocumentStore` keeps its state: the shell binds it to the tab's layout (`set` marks it dirty). */
export interface LayoutDocIO {
  get(canvasKey: string): LayoutDocState | undefined;
  set(canvasKey: string, state: LayoutDocState): void;
}

/** A session-only `LayoutDocIO` (a plain map) that remembers which canvases were written since load. */
export function memoryLayoutDocIO(): LayoutDocIO & { isDirty(canvasKey: string): boolean } {
  const states = new Map<string, LayoutDocState>();
  const dirty = new Set<string>();
  return {
    get: (key) => states.get(key),
    set: (key, state) => {
      states.set(key, state);
      dirty.add(key);
    },
    isDirty: (key) => dirty.has(key),
  };
}

export interface LayoutDocumentStoreOptions {
  defaultSkin?: string;
}

/**
 * The `'file'` store (VL V-28, V-29): the backend stores no layouts, so the open tab holds its
 * positions / skin / collapsed in memory and **Save as…** downloads them. `read` maps the held state to a
 * view-state (mode is derived — manual iff positions exist, V-2); `write` replaces it, which marks the
 * layout dirty. Never a viewport (C1-b).
 */
export class LayoutDocumentStore implements ViewStateStore {
  readonly #io: LayoutDocIO;
  readonly #defaultSkin: string;

  constructor(io: LayoutDocIO = memoryLayoutDocIO(), opts?: LayoutDocumentStoreOptions) {
    this.#io = io;
    this.#defaultSkin = opts?.defaultSkin ?? FALLBACK_SKIN;
  }

  async read(canvasKey: string): Promise<CanvasViewState> {
    const state = this.#io.get(canvasKey);
    const nodes: CanvasViewState['nodes'] = { ...(state?.positions ?? {}) };
    return {
      skin: (state?.skin ?? this.#defaultSkin) as SkinId,
      mode: Object.keys(nodes).length > 0 ? 'manual' : 'auto',
      nodes,
      collapsed: [...(state?.collapsed ?? [])],
      skinStorage: 'prefs',
    };
  }

  async write(canvasKey: string, vs: CanvasViewState): Promise<void> {
    this.#io.set(canvasKey, { positions: { ...vs.nodes }, skin: vs.skin, collapsed: [...vs.collapsed] });
  }
}

/**
 * view-state node entries whose qname is absent from the current graph — orphaned layout
 * (DS-CANV-001). Surviving nodes are unaffected; the decay is surfaced, never silently
 * dropped (P-3).
 */
export function detectOrphanedLayout(
  vs: CanvasViewState,
  graph: { nodes: { id: string }[] },
): string[] {
  const present = new Set(graph.nodes.map((n) => n.id));
  return Object.keys(vs.nodes).filter((key) => !present.has(key));
}
