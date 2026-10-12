// The modeling canvas render module (DS-P1.S2) — replaces the Cytoscape Canvas. Maps a
// ModelGraph → CanvasGraph, resolves the skin (per kind, pickable), lays out (loaded
// positions or deterministic auto), and renders the kernel with the base layer. Wires the
// modeling affordances onto React Flow: node select, remove (context menu → lsp), drag →
// layout persist. NO cytoscape import (the DS-P0 verdict engine is RF).
//
// DM-P2.S2 seam rewire: the canvas no longer talks to `LspClient.setLayout` directly. It EMITS a
// view change (`onPersistView`) and the shell (DM-P2.S3) persists it through the ViewStateStore
// selected by `capabilities.layoutPersist` (DM-P2.S1) — Worker in-file / WS `.ttrl` / Veles `file` (the tab).
// This is what makes the one canvas run against all three backends.

import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { DisplayMode, ModelGraph } from '@tatrman/lsp';
import {
  modelGraphToCanvas, layoutAuto, type BindingHint, type CanvasGraph, type Positions, type SkinId, type SchemaCode,
} from '@tatrman/canvas-core';
import { createSkinRegistry } from '../skins/index.js';
import type { DesignerSkin } from './skin-component.js';
import { CanvasKernel } from './Kernel.js';
import { canvas as palette } from '@tatrman/tokens'; // canvas token family (contracts §6)

/** A view change the canvas emits for the shell to persist through its ViewStateStore (DM-P2.S1/S3). */
export interface CanvasViewChange {
  positions: Positions;
  displayMode: DisplayMode;
  mode: 'auto' | 'manual';
}

export interface SkinnedCanvasProps {
  graph: ModelGraph | null;
  displayMode: DisplayMode;
  nodePositions: Record<string, { x: number; y: number }>;
  /** the ViewStateStore key for this canvas (the graph uri); null ⇒ persistence is skipped. */
  canvasKey: string | null;
  selectedQname?: string | null;
  /** the selected edge (a hop edge open in the inspector — VL V-26) */
  selectedEdgeId?: string | null;
  /** an edge was clicked (a hop edge's pill included); null = cleared */
  onEdgeSelect?: (id: string | null) => void;
  /** skin id from view-state (may be unknown ⇒ DS-SKIN-002 fallback + truth-chip note) */
  initialSkin?: SkinId;
  onNodeSelect: (qname: string | null) => void;
  /** FO-21: the node context menu is injected by the shell's authoring context (absent in OPEN →
   *  no menu, read-only). The label/ops live in the extension, never in this bundle. */
  renderNodeMenu?: (qname: string, close: () => void) => ReactNode;
  /** emit a view change to persist (positions/displayMode/mode); the shell writes it to the store. */
  onPersistView?: (change: CanvasViewChange) => void;
  onDrillIn?: (id: string, label: string) => void;
  /** S-5 show-bindings decoration (er canvas only): ghost table/query chips under bound entities.
   *  Controlled by the parent (session-local, never persisted); the toggle registers in ⌘K there. */
  bindingHints?: Record<string, BindingHint>;
  showBindings?: boolean;
  onToggleShowBindings?: () => void;
}

/** displayMode projection: 'just-names' shows headers only; otherwise rows render. */
function project(cg: CanvasGraph, mode: DisplayMode): CanvasGraph {
  if (mode !== 'just-names') return cg;
  return { ...cg, nodes: cg.nodes.map((n) => ({ ...n, slotData: { ...n.slotData, rows: [] } })) };
}

export function SkinnedCanvas({
  graph, displayMode, nodePositions, canvasKey, selectedQname, selectedEdgeId, onEdgeSelect, initialSkin, onNodeSelect, renderNodeMenu, onPersistView, onDrillIn,
  bindingHints, showBindings, onToggleShowBindings,
}: SkinnedCanvasProps) {
  const registry = useMemo(() => createSkinRegistry(), []);
  const kind: SchemaCode = (graph?.schemaCode as SchemaCode) ?? 'er';

  // S-5: the show-bindings decoration only applies to the er canvas.
  const bindingsToggleable = kind === 'er' && (!!bindingHints || !!onToggleShowBindings);
  const canvasGraph = useMemo(
    () => {
      if (!graph) return null;
      const cg = project(modelGraphToCanvas(graph), displayMode);
      return kind === 'er' && showBindings && bindingHints ? { ...cg, bindingHints } : cg;
    },
    [graph, displayMode, kind, showBindings, bindingHints],
  );

  // skin: default per kind, changeable via the picker. An id NOT in this kind's roster (unknown,
  // OR a known id of the wrong kind/face) ⇒ default + DS-SKIN-002 note. `resolve` is id-only and
  // kind-blind, so roster membership — not global resolution — is the correct fallback test.
  const roster = registry.roster('modeling', kind);
  const [skinChoice, setSkinChoice] = useState<Record<string, SkinId>>({});
  const requested = skinChoice[kind] ?? initialSkin;
  const fellBack = !!requested && !roster.some((s) => s.id === requested);
  const skinId: SkinId = fellBack || !requested ? registry.defaultSkin('modeling', kind) : requested;
  const skin = registry.resolve(skinId) as DesignerSkin;

  // positions: loaded (from .ttrg layout, skin-independent) else deterministic auto-layout.
  const [positions, setPositions] = useState<Positions | null>(null);
  const [mode, setMode] = useState<'auto' | 'manual'>('auto');
  const modeRef = useRef<'auto' | 'manual'>('auto'); // mirrors `mode` for reads inside the layout
  const workingPositions = useRef<Positions>({});     // effect (which excludes `mode` from deps)

  useEffect(() => {
    if (!canvasGraph || !skin) { setPositions(null); return; }
    const loaded = Object.keys(nodePositions).length > 0
      && canvasGraph.nodes.every((n) => nodePositions[n.id] || nodePositions[n.qname]);
    if (loaded) {
      const p: Positions = {};
      for (const n of canvasGraph.nodes) p[n.id] = nodePositions[n.id] ?? nodePositions[n.qname];
      workingPositions.current = p;
      setPositions(p);
      setMode('manual'); modeRef.current = 'manual';
      return;
    }
    // Positions survive a skin switch (C1-b-iv): if the user has manually placed a full working
    // set (drag, mode=manual), keep it rather than re-running auto-layout for the new skin. Only
    // auto-layout when we don't already own every node's position (fresh graph / added nodes).
    const haveFullWorking = canvasGraph.nodes.length > 0
      && canvasGraph.nodes.every((n) => workingPositions.current[n.id]);
    if (modeRef.current === 'manual' && haveFullWorking) {
      setPositions({ ...workingPositions.current });
      return;
    }
    let alive = true;
    setMode('auto'); modeRef.current = 'auto';
    layoutAuto(canvasGraph, { orientation: skin.flow.orientation, sizeOf: (n) => skin.nodeSize(n) })
      .then((p) => { if (alive) { workingPositions.current = p; setPositions(p); } });
    return () => { alive = false; };
    // re-layout when the graph or skin (orientation/size) changes; NOT on selection
  }, [canvasGraph, skinId]);

  // drag → update working positions + emit a persist-view (debounced). The shell writes it through
  // the ViewStateStore selected by the active backend's capabilities.layoutPersist (DM-P2.S1/S3).
  const persistTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onNodeDrag = (id: string, pos: { x: number; y: number }) => {
    workingPositions.current = { ...workingPositions.current, [id]: pos };
    setPositions({ ...workingPositions.current });
    setMode('manual'); modeRef.current = 'manual';
    if (!canvasKey) return;
    if (persistTimer.current) clearTimeout(persistTimer.current);
    persistTimer.current = setTimeout(() => {
      onPersistView?.({ positions: { ...workingPositions.current }, displayMode, mode: 'manual' });
    }, 400);
  };

  // displayMode change → emit a persist-view (positions + the new mode), matching the v1.1 modeler
  // behavior. Skips the initial load (no spurious persist on open).
  const prevMode = useRef<DisplayMode | null>(null);
  useEffect(() => {
    if (!canvasKey) { prevMode.current = displayMode; return; }
    if (prevMode.current === null) { prevMode.current = displayMode; return; } // first render after load
    if (prevMode.current === displayMode) return;
    prevMode.current = displayMode;
    onPersistView?.({ positions: { ...workingPositions.current }, displayMode, mode: modeRef.current });
  }, [displayMode, canvasKey]);

  // context-menu remove
  const [menu, setMenu] = useState<{ qname: string; x: number; y: number } | null>(null);

  if (!canvasGraph || !positions || !skin) {
    return <div data-testid="skinned-canvas-empty" style={{ padding: 20, color: palette.muted }}>laying out…</div>;
  }

  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column' }} data-testid="skinned-canvas" onClick={() => setMenu(null)}>
      {/* picker toolbar + truth chip (E-4) */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '6px 12px', background: palette.nodeFill, borderBottom: `1px solid ${palette.grid}`, flex: '0 0 auto', fontSize: 12.5 }}>
        <span style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '.06em', color: palette.muted }}>Notation</span>
        <select
          data-testid="skin-picker"
          value={skinId}
          onChange={(e) => setSkinChoice((c) => ({ ...c, [kind]: e.target.value }))}
          style={{ font: 'inherit', fontSize: 12.5, padding: '3px 8px', border: `1px solid ${palette.grid}`, borderRadius: 6 }}
        >
          {roster.map((s) => (
            <option key={s.id} value={s.id}>{s.displayName}</option>
          ))}
        </select>
        {bindingsToggleable && (
          <label data-testid="show-bindings-toggle" style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: palette.slate, cursor: 'pointer', userSelect: 'none' }} title="Ghost the bound db table under each entity (session-local)">
            <input
              type="checkbox"
              data-testid="show-bindings-checkbox"
              checked={!!showBindings}
              onChange={() => onToggleShowBindings?.()}
            />
            show bindings
          </label>
        )}
        <span data-testid="truth-chip" style={{ marginLeft: 'auto', fontSize: 11.5, color: palette.muted, border: `1px dashed ${palette.grid}`, padding: '3px 10px', borderRadius: 10 }}>
          canvas={kind} · skin={skinId} · mode={mode}
          {fellBack && <span data-testid="skin-fallback" style={{ color: palette.warnInk }}> · substituted ({requested} not available here)</span>}
          <span style={{ color: palette.muted }}> · skin: prefs (GQ-1)</span>
        </span>
      </div>

      <div style={{ flex: 1, minHeight: 0, position: 'relative' }}>
        <CanvasKernel
          graph={canvasGraph}
          registry={registry}
          skinId={skinId}
          positions={positions}
          selectedId={selectedQname ?? null}
          selectedEdgeId={selectedEdgeId ?? null}
          onSelect={onNodeSelect}
          onEdgeSelect={onEdgeSelect}
          onNodeDrag={onNodeDrag}
          // the node context menu is edit UI (FO-21) — only armed when the shell injects an authoring
          // menu renderer; the OPEN Viewer passes none, so right-click has no menu (read-only).
          onNodeContextMenu={renderNodeMenu ? (id, x, y) => setMenu({ qname: id, x, y }) : undefined}
          onDrillIn={onDrillIn}
        />
        {menu && renderNodeMenu && (
          <div
            data-testid="node-context-menu"
            style={{ position: 'fixed', left: menu.x, top: menu.y, background: palette.nodeFill, border: `1px solid ${palette.grid}`, borderRadius: 6, boxShadow: '0 4px 12px rgba(0,0,0,.15)', zIndex: 20, fontSize: 13, overflow: 'hidden' }}
          >
            {renderNodeMenu(menu.qname, () => setMenu(null))}
          </div>
        )}
      </div>
    </div>
  );
}
