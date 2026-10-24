// The canvas kernel (P-2) on the DS-P0 verdict engine (React Flow). Renders a CanvasGraph
// through a registered skin: CanvasGraph → RF primitives, selection/drag events, D-4 edge
// geometry (kernel-owned via port handles), base-layer chrome at anchors. The kernel is the
// ONLY place that knows about React Flow — skins emit contract-level output, never RF
// primitives, so the SVG-kernel fallback stays survivable.

import { useEffect, useMemo, useRef, type MutableRefObject } from 'react';
import {
  ReactFlow, ReactFlowProvider, Background, Controls, MarkerType, useReactFlow,
  type Node, type Edge,
} from '@xyflow/react';
import { usePageEnvironment } from '../page/page-context.js';
import { SURFACE } from '@tatrman/canvas-core';
import type { CanvasGraph, DiagnosticsState, NodeBaseState, Positions, RunStatus, SkinId, SkinRegistry, Theme } from '@tatrman/canvas-core';
import type { DesignerSkin } from './skin-component.js';
import { CanvasNodeView, type CNodeData } from './CanvasNode.js';
import { CanvasEdgeView, type CEdgeData } from './edges.js';
import { DerivedBanner } from './base/BaseLayer.js';
import { canvas as palette } from '@tatrman/tokens'; // canvas token family (contracts §6)

const nodeTypes = { cnode: CanvasNodeView };
const edgeTypes = { cedge: CanvasEdgeView };

export interface KernelProps {
  graph: CanvasGraph;
  registry: SkinRegistry;
  skinId: SkinId;
  positions: Positions;
  selectedId?: string | null;
  /** further selected nodes (shift-click multi-select — VL V-27) */
  selectedIds?: readonly string[];
  /** the selected edge (a hop edge in the inspector) — drawn heavier */
  selectedEdgeId?: string | null;
  orphanIds?: string[];
  derived?: boolean;
  theme?: Theme;
  /** per-node run state (processing face) — display nodes walk idle→running→done on the canvas. */
  runStates?: Record<string, { runStatus?: RunStatus; diagnostics?: DiagnosticsState }>;
  /** per-node display preview (S-6 base-layer chip) — row count now, sparkline post-v1. */
  previews?: Record<string, { rows: number }>;
  /** a node was clicked (`additive` — shift held: add to / remove from a multi-selection, VL V-27); null = cleared */
  onSelect?: (id: string | null, additive?: boolean) => void;
  /** edge selection (processing insertion targets) — the palette/⌘K insert onto the selected edge. */
  onEdgeSelect?: (id: string | null) => void;
  /** a right-click on an edge (a hop edge's pill included) — the view menu (VL V-27) */
  onEdgeContextMenu?: (id: string, clientX: number, clientY: number) => void;
  onNodeDrag?: (id: string, pos: { x: number; y: number }) => void;
  onNodeContextMenu?: (id: string, clientX: number, clientY: number) => void;
  /** drill into a node/container (double-click) — the shell pushes a breadcrumb segment (P-2) */
  onDrillIn?: (id: string, label: string) => void;
  /** filled with a reader of the visible area's centre, in canvas coordinates (placement — VL V-19) */
  viewportCenterRef?: MutableRefObject<(() => { x: number; y: number } | null) | null>;
}

export function CanvasKernel(props: KernelProps) {
  return (
    <ReactFlowProvider>
      <KernelInner {...props} />
    </ReactFlowProvider>
  );
}

/**
 * React Flow's edge right-click → the view menu, for a hop edge only: a real edge has no view items
 * (VL V-27), so its right-click keeps the browser's own menu instead of opening an empty one.
 */
export function edgeContextMenuHandler(onEdgeContextMenu?: (id: string, clientX: number, clientY: number) => void) {
  return (ev: { preventDefault(): void; clientX: number; clientY: number }, e: { id: string; data?: { kind?: string } }) => {
    if (!onEdgeContextMenu || e.data?.kind !== 'hop') return;
    ev.preventDefault();
    onEdgeContextMenu(e.id, ev.clientX, ev.clientY);
  };
}

function KernelInner({
  graph, registry, skinId, positions, selectedId, selectedIds, selectedEdgeId, orphanIds, derived, theme = 'ice', runStates, previews, onSelect, onEdgeSelect, onEdgeContextMenu, onNodeDrag, onNodeContextMenu, onDrillIn,
  viewportCenterRef,
}: KernelProps) {
  const page = usePageEnvironment();
  if (page) theme = page.theme === 'dark' ? 'stage-navy' : 'ice';
  const labelOf = (id: string) => graph.nodes.find((n) => n.id === id)?.label ?? id;
  const rf = useReactFlow();
  const hostRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!viewportCenterRef) return;
    viewportCenterRef.current = () => {
      const r = hostRef.current?.getBoundingClientRect();
      if (!r || r.width === 0 || r.height === 0) return null;
      return rf.screenToFlowPosition({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
    };
    return () => { viewportCenterRef.current = null; };
  }, [rf, viewportCenterRef]);
  const skin = registry.resolve(skinId) as DesignerSkin | undefined;
  const orphanSet = useMemo(() => new Set(orphanIds ?? []), [orphanIds]);
  const multiSet = useMemo(() => new Set(selectedIds ?? []), [selectedIds]);

  const rfNodes: Node<CNodeData>[] = useMemo(() => {
    if (!skin) return [];
    return graph.nodes.map((n) => {
      const size = skin.nodeSize(n);
      const run = runStates?.[n.id];
      const state: NodeBaseState = {
        selected: selectedId === n.id || multiSet.has(n.id),
        // v1 has no focus concept distinct from selection; the selected node IS the focused one.
        // This drives cnc.bubbles' properties-on-focus (its only consumer) on the live canvas.
        focused: selectedId === n.id,
        readOnly: !!derived,
        derived: !!derived,
        orphanedLayout: orphanSet.has(n.id),
        runStatus: run?.runStatus,
        diagnostics: run?.diagnostics,
      };
      return {
        id: n.id,
        type: 'cnode',
        position: positions[n.id] ?? { x: 0, y: 0 },
        data: { node: n, skin, state, theme, bindingHint: graph.bindingHints?.[n.id], preview: previews?.[n.id] },
        selected: selectedId === n.id || multiSet.has(n.id),
        style: { width: size.width, height: size.height },
      };
    });
  }, [graph, skin, positions, selectedId, multiSet, orphanSet, derived, theme, runStates, previews]);

  const rfEdges: Edge<CEdgeData>[] = useMemo(() => {
    if (!skin) return [];
    return graph.edges.map((e) => {
      const style = skin.edgeStyle(e, { skin, theme });
      return {
        id: e.id,
        source: e.from.node,
        target: e.to.node,
        sourceHandle: e.from.port,
        targetHandle: e.to.port,
        type: 'cedge',
        label: e.label,
        selected: e.id === selectedEdgeId,
        data: e.kind === 'hop'
          ? {
              style, theme, kind: 'hop' as const, viaCount: e.via?.length ?? 1,
              onSelect: () => onEdgeSelect?.(e.id),
              onContextMenu: (x: number, y: number) => onEdgeContextMenu?.(e.id, x, y),
            }
          : { style, theme, cardinality: e.cardinality },
        markerEnd: style.marker === 'none' ? undefined : { type: MarkerType.ArrowClosed, color: style.stroke },
      };
    });
  }, [graph, skin, theme, selectedEdgeId, onEdgeSelect, onEdgeContextMenu]);

  if (!skin) {
    return <div data-testid="unknown-skin" style={{ padding: 16, color: palette.err }}>unknown skin: {skinId}</div>;
  }

  return (
    <div ref={hostRef} style={{ width: '100%', height: '100%', background: page?.theme === 'dark' ? SURFACE['stage-navy'] : skin.canvas.background, position: 'relative' }} data-testid="canvas-kernel" data-skin={skinId}>
      {derived && <DerivedBanner />}
      <ReactFlow
        nodes={rfNodes}
        edges={rfEdges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodeClick={(e, n) => onSelect?.(n.id, e.shiftKey)}
        onEdgeClick={(_, e) => onEdgeSelect?.(e.id)}
        onEdgeContextMenu={edgeContextMenuHandler(onEdgeContextMenu)}
        onPaneClick={() => { onSelect?.(null); onEdgeSelect?.(null); }}
        onNodeDragStop={(_, n) => onNodeDrag?.(n.id, n.position)}
        onNodeContextMenu={(e, n) => { e.preventDefault(); onNodeContextMenu?.(n.id, e.clientX, e.clientY); }}
        onNodeDoubleClick={(_, n) => onDrillIn?.(n.id, labelOf(n.id))}
        nodesDraggable={!derived}
        fitView
        proOptions={{ hideAttribution: true }}
        minZoom={0.1}
      >
        <Background color={skin.canvas.grid === 'none' ? 'transparent' : palette.grid} gap={22} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
