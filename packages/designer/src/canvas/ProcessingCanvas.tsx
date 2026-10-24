// SPDX-License-Identifier: Apache-2.0
// DM-P4.S2 (ported from modeler DS-P5.S1) — the processing canvas render module, the sibling of
// SkinnedCanvas for the processing face. Fetches a ProcessingGraph from the source (fixture or the
// live TtrpServerProcessingSource, S4), maps it to the kernel model (processingGraphToCanvas),
// resolves the stage/script skin, lays out (deterministic auto — processing is auto-only), and
// renders the kernel. Drill-in reuses the DS-P2 seam. Run (a read — mutates no model doc) is OPEN.
//
// **FO-21 (DM-P4 Finding B):** the modeler original inlined `InsertionDoors` + `editEnabled` +
// `onApplyGraphEdit` — that is EDIT. This OPEN port ships NO edit code: the insertion doors mount
// ONLY through the marker-free `renderInsertionDoors?` slot, which the authoring extension supplies
// (via `ShellEditContext.renderProcessingDoors`). Absent in the open build ⇒ no doors, no `applyGraphEdit`.
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  processingGraphToCanvas, layoutAuto,
  type CanvasGraph, type DiagnosticsState, type Positions, type RunStatus, type SkinId, type Theme, type ProcessingGraph,
} from '@tatrman/canvas-core';
import { createSkinRegistry } from '../skins/index.js';
import type { DesignerSkin } from './skin-component.js';
import type { ProcessingGraphSource } from '../model/processing-source.js';
import type { ArrowTable, RunSource } from '../model/run-source.js';
import { CanvasKernel } from './Kernel.js';
import { RecipeListView } from './RecipeListView.js';
import type { RecipeSummary } from '../model/ttrp/types.js';
import { ResultDrawer } from './ResultDrawer.js';
import { ProcessingDrillContext } from '../skins/processing-nodes.js';
import { canvas as palette } from '@tatrman/tokens'; // canvas token family (contracts §6)
import type { SlotValidateResult } from '../shell/edit-context.js';

/** An edge offered as a C1-d insertion target. Row-less, op-name-free — the shell forwards these
 *  opaquely to the authoring doors slot; the OPEN component never names an edit op (FO-21). */
export interface ProcInsertionEdge {
  edgeId: string;
  from: { node: string; port: string };
  to: { node: string; port: string };
  role: 'data' | 'control' | 'transfer';
}

/** A step (processing node) offered to the per-step doors + AuthorPanel. Op-name-free; the shell
 *  forwards these opaquely (FO-21). */
export interface ProcStep {
  id: string;
  kind: string;
  label: string;
}

/** What the OPEN ProcessingCanvas hands the (extension-supplied) insertion-doors slot. Shape-matches
 *  `ShellEditContext.ProcessingDoorsSlotProps` so the shell can forward `renderProcessingDoors`.
 *  FO-A1 W4 (P4.S1) adds the per-step door + AuthorPanel inputs onto the SAME slot. */
export interface ProcessingInsertionSlot {
  edges: ProcInsertionEdge[];
  midpointOf: (edgeId: string) => { x: number; y: number };
  selectedEdgeId: string | null;
  openPaletteRef?: { current: (() => void) | null };
  nodes: ProcStep[];
  selectedNodeId: string | null;
  positionOf: (nodeId: string) => { x: number; y: number };
  programRef: string;
  validate?: () => Promise<SlotValidateResult>;
  preview?: () => void;
  onApplied(): void;
}

export interface ProcessingCanvasProps {
  source: ProcessingGraphSource;
  programRef: string;
  /** breadcrumb container ids (the DS-P2 drill seam). Empty ⇒ orchestration (getProgramGraph). */
  drillPath: string[];
  selectedId?: string | null;
  initialSkin?: SkinId;
  onSelect?: (id: string | null) => void;
  /** region drill (⌕ / double-click) — the shell pushes a breadcrumb segment. */
  onDrillIn?: (id: string, label: string) => void;
  /** the run backend (contracts §5). Absent/unavailable ⇒ run controls disabled-with-hint (DS-RUN-001). */
  runSource?: RunSource;
  /** the shell assigns the run trigger here so its ⌘K command can fire the same run (E-4 parity). */
  runRef?: { current: (() => void) | null };
  /** the processing-edit insertion doors — supplied ONLY by the authoring extension (FO-21). Absent
   *  in the OPEN build ⇒ no edit doors render. */
  renderInsertionDoors?: (slot: ProcessingInsertionSlot) => ReactNode;
  /** the ⌘K insertion-palette opener ref, forwarded to the doors slot (bridged only when armed). */
  insertPaletteRef?: { current: (() => void) | null };
  /** the read-only `ttrp/validate` capability (contracts §2) for the AuthorPanel's Validate chip —
   *  validates the program BY URI (no client-side draft text). Open-safe (validate is a read). Absent
   *  ⇒ the AuthorPanel degrades to A1-CAP-002. The live wire (a `TtrpLspClient.validate` closure over
   *  the program uri) lands with the P4.S2 draft-source path; undefined here in the fixture/open path. */
  validateProgram?: () => Promise<SlotValidateResult>;
}

const themeForSkin = (id: SkinId): Theme => (id === 'script' ? 'stage-navy' : 'ice');

type RunState = { runStatus?: RunStatus; diagnostics?: DiagnosticsState };

export function ProcessingCanvas({
  source, programRef, drillPath, selectedId, initialSkin, onSelect, onDrillIn, runSource, runRef,
  renderInsertionDoors, insertPaletteRef, validateProgram,
}: ProcessingCanvasProps) {
  const registry = useMemo(() => createSkinRegistry(), []);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);

  // skin: default (stage, E-3a), changeable via the picker. Unknown id ⇒ default + note.
  const [chosen, setChosen] = useState<SkinId | null>(null);
  const requested = chosen ?? initialSkin;
  const fellBack = !!requested && !registry.resolve(requested);
  const skinId: SkinId = fellBack || !requested ? registry.defaultSkin('processing') : requested;
  const skin = registry.resolve(skinId) as DesignerSkin;

  // fetch the graph for the current drill level (orchestration or a container drill-in). `reload`
  // bumps to refetch after an applied edit (the doors slot's onApplied).
  const drillKey = drillPath.join('/');
  const [reload, setReload] = useState(0);
  const [recipe, setRecipe] = useState<RecipeSummary | null>(null);
  useEffect(() => {
    let alive = true;
    setRecipe(null);
    source.getRecipeSummary?.(programRef).then(value => { if (alive) setRecipe(value); }).catch(() => { if (alive) setRecipe(null); });
    return () => { alive = false; };
  }, [source, programRef, reload]);
  const [pg, setPg] = useState<ProcessingGraph | null>(null);
  useEffect(() => {
    let alive = true;
    const p = drillPath.length === 0
      ? source.getProgramGraph(programRef)
      : source.getContainerGraph(drillPath[drillPath.length - 1]);
    p.then((g) => { if (alive) setPg(g); }).catch(() => { if (alive) setPg(null); });
    return () => { alive = false; };
  }, [source, programRef, drillKey, reload]);

  const canvasGraph: CanvasGraph | null = useMemo(() => (pg ? processingGraphToCanvas(pg) : null), [pg]);

  // deterministic auto-layout (processing is auto-only — no manual persist).
  const [positions, setPositions] = useState<Positions | null>(null);
  useEffect(() => {
    if (!canvasGraph || !skin) { setPositions(null); return; }
    let alive = true;
    layoutAuto(canvasGraph, { orientation: skin.flow.orientation, sizeOf: (n) => skin.nodeSize(n) })
      .then((p) => { if (alive) setPositions(p); });
    return () => { alive = false; };
  }, [canvasGraph, skinId]);

  const roster = registry.roster('processing');
  const derived = !!canvasGraph?.derived;
  const containerIds = useMemo(() => new Set(canvasGraph?.containers.map((c) => c.id) ?? []), [canvasGraph]);

  // ---- insertion targets (edges as C1-d targets) — forwarded to the (optional) doors slot ----
  const insertionEdges: ProcInsertionEdge[] = useMemo(
    () => (canvasGraph?.edges ?? []).map((e) => ({ edgeId: e.id, from: e.from, to: e.to, role: e.role as ProcInsertionEdge['role'] })),
    [canvasGraph],
  );
  const midpointOf = (edgeId: string): { x: number; y: number } => {
    const e = canvasGraph?.edges.find((ed) => ed.id === edgeId);
    if (!e || !positions || !skin) return { x: 0, y: 0 };
    const center = (nodeId: string) => {
      const n = canvasGraph!.nodes.find((nd) => nd.id === nodeId);
      const p = positions[nodeId] ?? { x: 0, y: 0 };
      const s = n ? skin.nodeSize(n) : { width: 0, height: 0 };
      return { x: p.x + s.width / 2, y: p.y + s.height / 2 };
    };
    const a = center(e.from.node); const b = center(e.to.node);
    return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
  };

  // ---- steps (nodes as per-step door targets) + a screen anchor — forwarded to the (optional) slot ----
  const procSteps: ProcStep[] = useMemo(
    () => (canvasGraph?.nodes ?? []).map((n) => ({ id: n.id, kind: n.kind, label: n.label ?? n.id })),
    [canvasGraph],
  );
  // the step's top-right corner (px, canvas-relative): where the per-step toolbar / diagnostic badge sits.
  const positionOf = (nodeId: string): { x: number; y: number } => {
    const n = canvasGraph?.nodes.find((nd) => nd.id === nodeId);
    if (!n || !positions || !skin) return { x: 0, y: 0 };
    const p = positions[nodeId] ?? { x: 0, y: 0 };
    const s = skin.nodeSize(n);
    return { x: p.x + s.width, y: p.y };
  };

  // ---- run / display ----
  const displayNodes = useMemo(() => (canvasGraph?.nodes ?? []).filter((n) => n.kind === 'display'), [canvasGraph]);
  const [runStates, setRunStates] = useState<Record<string, RunState>>({});
  const [previews, setPreviews] = useState<Record<string, { rows: number }>>({});
  const [result, setResult] = useState<{ sinkRef: string; table: ArrowTable; preview: boolean } | null>(null);
  const running = Object.values(runStates).some((s) => s.runStatus === 'running');
  const canRun = !!runSource?.available && drillPath.length === 0 && displayNodes.length > 0;

  // a new program / drill level clears prior run state and supersedes any in-flight run (its late
  // events must not write onto the new canvas — the DS "live path" hazard).
  const runGen = useRef(0);
  useEffect(() => { runGen.current += 1; setRunStates({}); setPreviews({}); setResult(null); }, [programRef, drillKey]);

  // `preview` marks a DRAFT preview-run (§3): identical run + Arrow path, but the ResultDrawer badges
  // it and it never implies a save. The AuthorPanel's Preview chip drives this via the slot.
  const runProgram = useRef<(preview?: boolean) => void>(() => {});
  runProgram.current = (preview = false) => {
    if (!runSource?.available || running || displayNodes.length === 0) return;
    const gen = runGen.current;
    const ids = displayNodes.map((n) => n.id);
    const set = (rs: RunState) => setRunStates(Object.fromEntries(ids.map((id) => [id, rs])));
    void (async () => {
      for await (const ev of runSource.run(programRef)) {
        if (gen !== runGen.current) return; // superseded by a drill / program change — stop writing
        if (ev.status === 'idle') continue;
        set({ runStatus: ev.status, diagnostics: ev.diagnostics });
        if (ev.status === 'done' && ev.sinkRef) {
          try {
            const table = await runSource.readDisplayResult(ev.sinkRef);
            if (gen !== runGen.current) return;
            setResult({ sinkRef: ev.sinkRef, table, preview });
            setPreviews(Object.fromEntries(displayNodes.map((n) => [n.id, { rows: table.numRows }])));
          } catch { /* readDisplayResult failure (e.g. live no-display) leaves the done badge without a preview */ }
        }
      }
    })();
  };
  // expose the trigger so the shell's ⌘K command fires the SAME run (E-4 parity).
  useEffect(() => { if (runRef) runRef.current = () => runProgram.current(); return () => { if (runRef) runRef.current = null; }; }, [runRef]);

  if (recipe && drillPath.length === 0) return <RecipeListView recipe={recipe} />;

  if (!canvasGraph || !positions || !skin) {
    return <div data-testid="processing-canvas-empty" style={{ padding: 20, color: palette.muted }}>laying out…</div>;
  }

  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column' }} data-testid="processing-canvas">
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '6px 12px', background: palette.nodeFill, borderBottom: `1px solid ${palette.grid}`, flex: '0 0 auto', fontSize: 12.5 }}>
        <span style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '.06em', color: palette.muted }}>Skin</span>
        <select
          data-testid="skin-picker"
          value={skinId}
          onChange={(e) => setChosen(e.target.value)}
          style={{ font: 'inherit', fontSize: 12.5, padding: '3px 8px', border: `1px solid ${palette.grid}`, borderRadius: 6 }}
        >
          {roster.map((s) => (
            <option key={s.id} value={s.id}>{s.displayName}</option>
          ))}
        </select>

        {drillPath.length === 0 && (
          <>
            <button
              data-testid="run-button"
              disabled={!canRun}
              onClick={() => runProgram.current()}
              title={
                !runSource?.available ? 'No run backend — connect a platform to run (DS-RUN-001)'
                  : displayNodes.length === 0 ? 'This program has no display sink to preview'
                    : 'Run this program'
              }
              style={{
                font: 'inherit', fontSize: 12.5, padding: '3px 12px', borderRadius: 6,
                border: `1px solid ${canRun ? palette.ok : palette.grid}`,
                background: canRun ? palette.ok : palette.nodeFillMuted, color: canRun ? palette.nodeFill : palette.muted,
                cursor: canRun ? 'pointer' : 'not-allowed',
              }}
            >
              {running ? '▶ running…' : '▶ Run'}
            </button>
            {!runSource?.available ? (
              <span data-testid="ds-run-001" style={{ fontSize: 11, color: palette.warnInk }}>
                no run backend — connect a platform to run
              </span>
            ) : displayNodes.length === 0 ? (
              <span data-testid="no-display-hint" style={{ fontSize: 11, color: palette.muted }}>
                no display sink to preview
              </span>
            ) : null}
          </>
        )}

        <span data-testid="truth-chip" style={{ marginLeft: 'auto', fontSize: 11.5, color: palette.muted, border: `1px dashed ${palette.grid}`, padding: '3px 10px', borderRadius: 10 }}>
          face=processing · skin={skinId} · mode=auto
          {derived && <span data-testid="derived-note" style={{ color: palette.warnInk }}> · derived (read-only)</span>}
          {fellBack && <span data-testid="skin-fallback" style={{ color: palette.warnInk }}> · substituted ({requested} unknown)</span>}
        </span>
      </div>

      <div style={{ flex: 1, minHeight: 0, position: 'relative' }}>
        <ProcessingDrillContext.Provider value={(id, label) => { if (containerIds.has(id)) onDrillIn?.(id, label); }}>
          <CanvasKernel
            graph={canvasGraph}
            registry={registry}
            skinId={skinId}
            positions={positions}
            selectedId={selectedId ?? null}
            derived={derived}
            theme={themeForSkin(skinId)}
            runStates={runStates}
            previews={previews}
            onSelect={onSelect}
            onEdgeSelect={setSelectedEdgeId}
            onDrillIn={(id, label) => { if (containerIds.has(id)) onDrillIn?.(id, label); }}
          />
        </ProcessingDrillContext.Provider>

        {/* the processing doors (edge insertion + per-step toolbar + AuthorPanel) mount ONLY through
            the authoring extension's slot (FO-21). Not on a derived fragment (fully read-only). Absent
            in the OPEN build ⇒ no edit surface. Gated on having steps (an empty canvas returns early
            above), so an edge-less-but-authorable program still gets the AuthorPanel. */}
        {!derived && procSteps.length > 0 && renderInsertionDoors?.({
          edges: insertionEdges,
          midpointOf,
          selectedEdgeId,
          openPaletteRef: insertPaletteRef,
          nodes: procSteps,
          selectedNodeId: selectedId ?? null,
          positionOf,
          programRef,
          validate: validateProgram,
          preview: canRun ? () => runProgram.current(true) : undefined,
          onApplied: () => setReload((r) => r + 1),
        })}

        {/* run results live in the drawer + the base-layer preview chip — never in-canvas cards (D-5). */}
        {result && <ResultDrawer sinkRef={result.sinkRef} table={result.table} preview={result.preview} onClose={() => setResult(null)} />}
      </div>
    </div>
  );
}
