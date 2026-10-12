// The shell (DS-P2) — the dual-spine frame that hosts the modeling canvases. Catalog spine
// (primary) + file rail (secondary) · subject tabs (tab = subject, A-2 β) · in-tab drill breadcrumb
// (P-2) · the canvas host (SkinnedCanvas for the active subject) · the text drawer (peek) · the ⌘K
// command registry. No Model/Process mode switch (A-4 α): the tab's subject decides the canvas kind.
// URL round-trips the active view (contracts §6).
//
// DM-P2.S3 — this is the OPEN Studio Viewer port of modeler's ShellFrame:
//   • data seam: consumes `ModelDataSource` (contracts §1), never a transport/`LspClient`. Runs
//     against Worker (full) / WS / Veles (honestly degraded) behind the one interface.
//   • view-state: layout persists through the injected `ViewStateStore` (DM-P2.S1), selected by the
//     backend's `capabilities.layoutPersist` — Worker in-file / WS `.ttrl` / Veles `file` (the tab).
//   • FO-21: edit affordances render ONLY through an injected `ShellEditContext` — ABSENT here, so
//     the Viewer ships no edit code (add/remove/applyGraphEdit + pickers are DM-P3 authoring). The
//     processing face (program tabs) is a placeholder until DM-P4.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { BindingMapData, DisplayMode, GetGraphResponse, LineageRootRef, ModelGraphEdge } from '@tatrman/lsp';
import { generateBindingRibbon, generateLineage, type LineageScope, type LineageDirection, type ObjectKind } from '@tatrman/perspectives';
import type { ModelDataSource } from '../data/model-data-source.js';
import type { ViewStateStore } from '@tatrman/canvas-core';
import { getGraphResponseToModelGraph } from '../model/graph-adapter.js';
import { applyGraphFixtures } from '../model/graph-fixtures.js';
import { buildBindingHints } from '../model/binding-adapter.js';
import { composeLineageModel } from '../model/lineage-adapter.js';
import { SkinnedCanvas, type CanvasViewChange } from '../canvas/SkinnedCanvas.js';
import { ProcessingCanvas } from '../canvas/ProcessingCanvas.js';
import type { SlotValidateResult } from './edit-context.js';
import { fixtureProcessingSource, type ProcessingGraphSource } from '../model/processing-source.js';
import { absentRunSource, type RunSource } from '../model/run-source.js';
import { DerivedCanvas } from '../perspectives/index.js';
import type { ShellEditContext } from './edit-context.js';
import type { CatalogGroup, CatalogItem, ShellState, Subject, SubjectKind, SubjectTab, TabLayout } from './types.js';
import {
  emptyShell, openSubject, promoteTab, closeTab, dropPreviewIfLeaving, drillIn, drillTo, activeTab, setPerspective,
  setLayoutObjects, setLayoutPositions, setLayoutSkin, toLayoutTab, openLayoutTab, markSaved, hasUnsavedLocalLayout,
} from './shell-state.js';
import { ViewMenu, type ViewMenuEntry, type ViewMenuTarget } from './ViewMenu.js';
import { projectLayout, layoutIndexOf, hiddenNeighbours, hiddenClosure, visibleNeighbours, type HopEdge, type LayoutGraphIndex, type LayoutProjection } from '../model/layout-projection.js';
import { placeNew, centreOf, type Point, type Size } from '../model/placement.js';
import { modelGraphToCanvas, layoutAuto, type CanvasGraph, type SchemaCode } from '@tatrman/canvas-core';
import { createSkinRegistry } from '../skins/index.js';
import type { DesignerSkin } from '../canvas/skin-component.js';
import { compareAlphabetical } from '../layout/layout-document.js';
import { ObjectsPanel, type MissingRef } from './ObjectsPanel.js';
import { readLayoutFiles, normalizeForTab, LAYOUT_FILE_ACCEPT } from '../layout/open-files.js';
import { resolveRefs } from '../layout/resolve.js';
import { modelKindHint } from '../data/capability-hints.js';
import { pickFiles as pickFilesFromDisk, downloadFile as downloadToDisk } from '../fs/file-system.js';
import { SaveAsDialog, layoutFileOf, layoutTextOf, type LayoutFormat } from './SaveAsDialog.js';
import { getRuntimeConfig } from '../runtime-config.js';
import type { LayoutDocument } from '../layout/layout-document.js';
import type { ModelKind } from '../data/model-data-source.js';
import type { LayoutPoint } from '../layout/layout-document.js';
import { HopEdgeInspector } from './HopEdgeInspector.js';
import { layoutChip } from './layout-chip.js';
import { CatalogSpine } from './CatalogSpine.js';
import { FileRail } from './FileRail.js';
import { TabBar } from './TabBar.js';
import { Breadcrumb } from './Breadcrumb.js';
import { TextDrawer, type DrawerNode } from './TextDrawer.js';
import { CommandRegistry } from './commands.js';
import { CommandPalette, useCmdKShortcut } from './CommandPalette.js';
import { syncUrl, parsePath, formatPath } from './url.js';
import { tabUrl } from './popout.js';
import { federationIntentFromPath, federationPathForTab, askUrlForTab } from './federation-link.js';
import { absoluteUrlFor, currentAppPath, replaceAppPath } from './app-location.js';
import { CopyLinkButton } from './CopyLinkButton.js';
import { color, radius, space, fontSize } from '@tatrman/tokens';

/** The active subject, lifted up so the host chrome (title, display-mode pills) can reflect it
 *  without owning the shell's tab/graph state. */
export interface ActiveSubject {
  ref: string;
  label: string;
  kind: SubjectKind;
  imports: string[];
  missingObjects: string[];
  viewportDisplayMode?: DisplayMode;
}

export interface ShellFrameProps {
  /** the generalized data seam (contracts §1) — Worker/WS/Veles behind one interface. */
  dataSource: ModelDataSource;
  workspace: string;
  catalog: CatalogGroup[];
  files: string[];
  displayMode: DisplayMode;
  getSourceText?: (uri: string) => string | undefined;
  onError?: (msg: string) => void;
  /** a passing notice (`Expanded: +3`, `Every neighbour is already visible`) — the host's info toast. */
  onNotice?: (msg: string) => void;
  /** Open layout…'s file picker (VL V-31) — the browser's by default; a test injects its own. */
  pickFiles?: (accept: string, multiple: boolean) => Promise<File[]>;
  /** Save as…'s download (VL V-32) — the browser's by default; a test injects its own. */
  downloadFile?: (fileName: string, content: string, mimeType: string) => Promise<void>;
  /** fires when the active tab (or its loaded graph) changes — drives the host header. */
  onActiveChange?: (active: ActiveSubject | null) => void;
  /** view-state persistence (FO-31, DM-P2.S1) — selected by capabilities.layoutPersist. Absent ⇒
   *  positions are session-only (no store wired). */
  viewState?: ViewStateStore;
  /** FO-21 edit seam (contracts §4). ABSENT in the open Viewer ⇒ edit-absent (DS-EDIT-001);
   *  the FO-P0.S4 loader supplies it on the commercial build (DM-P3). */
  editContext?: ShellEditContext;
  /** Studio→Iris base URL (FO §3 "ask about this", FO-P1.S5). ABSENT ⇒ the affordance is hidden
   *  (the open Viewer has no Iris); a commercial/federated shell passes it. Kantheon serves the
   *  `/ask` endpoint (seam demand C-2) — we only emit the §3 context link. */
  irisBaseUrl?: string;
  /** the processing graph source (contracts §5, DM-P4). Absent ⇒ the fixture hero. The live
   *  `TtrpServerProcessingSource` (:9257) plugs in behind the same interface when configured. */
  processingSource?: ProcessingGraphSource;
  /** the processing run backend (contracts §5, DM-P4). Absent ⇒ run controls disabled-with-hint
   *  (DS-RUN-001). The live `TtrpServerRunSource` (:9257) is wired by `App` when configured. */
  runSource?: RunSource;
  /** the read-only `ttrp/validate` capability (contracts §2) the AuthorPanel's Validate chip drives
   *  (FO-A1 W4). Forwarded to the ProcessingCanvas → the doors slot. Absent ⇒ Validate degrades
   *  (A1-CAP-002). Production wiring (a `TtrpLspClient.validate` closure over the program uri) rides
   *  the live draft-source path; undefined in the fixture path. */
  validateProgram?: () => Promise<SlotValidateResult>;
}

const subjectOf = (item: CatalogItem): Subject => ({
  ref: item.ref, kind: item.kind, schemaCode: item.schemaCode, label: item.label,
});

const toolbarButton = { border: '1px solid #CBD8E6', borderRadius: 6, background: '#fff', cursor: 'pointer', fontSize: 12, padding: '3px 10px', color: '#33506e' } as const;
/** a toolbar button's style, greyed out when there is nothing to act on */
const toolbarStyle = (enabled: boolean, primary = false) => ({
  ...toolbarButton,
  ...(primary && enabled ? { background: '#16283F', color: '#fff', borderColor: '#16283F' } : {}),
  ...(enabled ? {} : { opacity: 0.45, cursor: 'default' }),
});

/** No positions (a stable object, so the canvas does not re-run its layout on every render). */
const NO_POSITIONS: Record<string, LayoutPoint> = {};

/** The default skin of a schema and the canvas graph it draws — node sizes for placement, ELK for Re-layout. */
function defaultSkinFor(kind: string): DesignerSkin {
  const registry = createSkinRegistry();
  return registry.resolve(registry.defaultSkin('modeling', kind as SchemaCode)) as DesignerSkin;
}
function canvasOf(g: GetGraphResponse, displayMode: DisplayMode): CanvasGraph {
  const cg = modelGraphToCanvas(applyGraphFixtures(getGraphResponseToModelGraph(g)));
  return displayMode === 'just-names' ? { ...cg, nodes: cg.nodes.map((n) => ({ ...n, slotData: { ...n.slotData, rows: [] } })) } : cg;
}

/** A hop edge as the canvas draws it: one more edge kind, dashed (VL V-26). */
function hopAsEdge(h: HopEdge): ModelGraphEdge {
  return {
    id: h.id, qname: h.id, kind: 'hop', via: h.via, fromNode: h.fromNode, toNode: h.toNode,
    fromCardinality: null, toCardinality: null, sourceUri: '', sourceLocation: { line: 0, column: 0 },
  };
}

/** The layout a plain tab becomes on its first selection edit (V-22): the whole schema ⇒ `untitled`,
 *  a project graph ⇒ its own name, either way a new, unsaved layout. */
function seedLayoutName(tab: SubjectTab): string {
  if (tab.subject.ref === tab.subject.schemaCode) return 'untitled';
  const name = tab.subject.label.split(' · ').pop() ?? '';
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : 'untitled';
}

export function ShellFrame({ dataSource, workspace, catalog, files, displayMode, getSourceText, onError, onNotice, pickFiles = pickFilesFromDisk, downloadFile = downloadToDisk, onActiveChange, viewState, editContext, irisBaseUrl, processingSource, runSource, validateProgram }: ShellFrameProps) {
  const [shell, setShell] = useState<ShellState>(emptyShell);
  // processing face (DM-P4): the fixture source serves the hero graph in dev + tests; the live
  // TtrpServerProcessingSource plugs in behind the same interface. The run backend is a separate
  // axis (the :9257 ttrp server), absent by default (⇒ DS-RUN-001).
  const [procSel, setProcSel] = useState<string | null>(null);
  const procSource = useMemo(() => processingSource ?? fixtureProcessingSource(), [processingSource]);
  const activeRunSource = useMemo(() => runSource ?? absentRunSource(), [runSource]);
  const procRunRef = useRef<(() => void) | null>(null);
  const insertPaletteRef = useRef<(() => void) | null>(null);
  const [graphs, setGraphs] = useState<Record<string, GetGraphResponse | null>>({});
  // the whole-schema graph per schema code (VL V-21) — what the Objects panel lists, layouts resolve
  // against and are projected from. On a catalog it is the schema subject's own graph.
  const [schemaGraphs, setSchemaGraphs] = useState<Record<string, GetGraphResponse | null>>({});
  const schemaLoadingRef = useRef<Map<string, Promise<GetGraphResponse | null>>>(new Map());
  // the hop edge open in the inspector (VL V-26), by edge id; derived, cleared when it disappears.
  const [selectedHop, setSelectedHop] = useState<string | null>(null);
  // shift-click multi-selection (V-27): node ids the view menu's items apply to together — and exactly
  // what the canvas draws selected while it is non-empty. Per tab: a tab switch clears it.
  const [multiSel, setMultiSel] = useState<ReadonlySet<string>>(new Set());
  const selectedIds = useMemo(() => [...multiSel], [multiSel]);
  // Save as… (V-32): the tab being saved; the tab menu (V-33): where it was opened
  const [saveAsTab, setSaveAsTab] = useState<string | null>(null);
  const [tabMenu, setTabMenu] = useState<{ tabId: string; x: number; y: number } | null>(null);
  const tabMenuRef = useRef<HTMLDivElement>(null);
  // Expand all levels… asks first (V-27): the count, then apply or cancel
  const [confirm, setConfirm] = useState<{ text: string; onYes: () => void } | null>(null);
  // what the canvas shows the user, in canvas coordinates — where a card with no neighbour goes (V-19)
  const viewportCenterRef = useRef<(() => Point | null) | null>(null);
  const [positions, setPositions] = useState<Record<string, Record<string, { x: number; y: number }>>>({});
  const [selected, setSelected] = useState<DrawerNode | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [cmdkOpen, setCmdkOpen] = useState(false);
  const [bootHint, setBootHint] = useState<string | null>(null);
  // binding data (project-wide, C-2) — feeds the show-bindings decoration + the binding ribbon.
  const [bindings, setBindings] = useState<BindingMapData | null>(null);
  const [showBindings, setShowBindings] = useState(false); // S-5, session-local (never persisted)
  const [bindingSel, setBindingSel] = useState<string | null>(null); // ribbon expansion (per subject)
  // per-lineage-tab query state (scope/direction/root kind), keyed by tab id. Derived, never persisted.
  const [lineageState, setLineageState] = useState<Record<string, { scope: LineageScope; direction: LineageDirection; rootKind: ObjectKind; rootRef?: LineageRootRef }>>({});
  const loadingRef = useRef<Set<string>>(new Set());
  const bootedRef = useRef(false);
  // the edit gate (FO-21) — true only when an authoring context is injected AND grants it.
  const canEdit = !!editContext?.editable;
  // Snapshot the address bar on first render, BEFORE the URL-sync effect can rewrite it — the
  // deep-link boot parses this snapshot so it survives the catalog arriving a tick later. It is the
  // APP path: base path stripped, session parameters (`?veles=`, `?lang=`) removed (ttr-core#171).
  const initialUrlRef = useRef<string | null>(null);
  if (initialUrlRef.current === null) {
    initialUrlRef.current = currentAppPath();
  }

  const tab = activeTab(shell);
  const shellRef = useRef(shell);
  shellRef.current = shell;

  // the notation picked on a plain subject's canvas, by tab id — Save as… / New layout / the first
  // selection edit carry it (a layout tab keeps its own in the layout, V-28)
  const [subjectSkins, setSubjectSkins] = useState<Record<string, string>>({});

  /**
   * The whole graph of schema `code` (VL V-21): held, in flight, or read now — one read per schema. A
   * failed read is not kept: the next open (a tab, a layout) reads again.
   */
  function loadSchemaGraph(code: string): Promise<GetGraphResponse | null> {
    if (schemaGraphs[code] !== undefined) return Promise.resolve(schemaGraphs[code]);
    const inFlight = schemaLoadingRef.current.get(code);
    if (inFlight) return inFlight;
    if (!dataSource.getSchemaGraph || (code !== 'db' && code !== 'er' && code !== 'md' && code !== 'cnc')) return Promise.resolve(null);
    const held = graphs[code]; // a catalog's schema subject IS the whole schema
    const read: Promise<GetGraphResponse | null | 'failed'> = held
      ? Promise.resolve(held)
      : dataSource.getSchemaGraph(code).catch((e) => {
          onError?.(`Failed to read the ${code} schema: ${e}`);
          return 'failed' as const;
        });
    const p = read.then((g) => {
      if (g === 'failed') {
        schemaLoadingRef.current.delete(code);
        return null;
      }
      setSchemaGraphs((m) => ({ ...m, [code]: g }));
      return g;
    });
    schemaLoadingRef.current.set(code, p);
    return p;
  }
  function ensureSchemaGraph(code: string) {
    void loadSchemaGraph(code);
  }

  /**
   * Open a layout the catalog serves (V-34, V-36): read it, re-resolve it against the schema's whole graph
   * (a stale catalog cannot show a card the current graph lacks) and open a `catalog` tab. A layout the
   * catalog does not have (404) or cannot serve — or serves short of the §6 shape — is an error toast,
   * never a blank tab.
   */
  async function openCatalogLayout(name: string) {
    if (!dataSource.getLayoutDoc) return;
    try {
      const served = await dataSource.getLayoutDoc(name);
      const whole = await loadSchemaGraph(served.schema);
      if (!whole) { onError?.(`Could not open the layout ${name}: the ${served.schema} schema could not be read.`); return; }
      const objects = [...served.objects, ...served.missing, ...served.ambiguous.map((a) => a.ref)];
      const raw: LayoutDocument = { name: served.name, model: served.schema as LayoutDocument['model'], objects, positions: served.positions };
      if (served.skin) raw.skin = served.skin;
      if (served.collapsed.length > 0) raw.collapsed = served.collapsed;
      const res = resolveRefs([...objects, ...Object.keys(served.positions)], layoutIndexOf(whole).index, raw.model);
      setShell((s) => openLayoutTab(s, { doc: normalizeForTab(raw, res), source: 'catalog', dirty: false }));
    } catch (e) {
      onError?.(`Could not open the layout ${name}: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  /**
   * Open layout… (V-31): read the picked / dropped files, resolve them against the schema's whole graph
   * (read first when not held) and open a `file` tab. A model this backend does not serve, an unreadable
   * file or a lone `.ttrl` is an error toast — no tab.
   */
  async function openLayoutFiles(picked: readonly File[]) {
    if (picked.length === 0) return;
    const inputs = await Promise.all(picked.map(async (f) => ({ name: f.name, text: await f.text() })));
    const r = readLayoutFiles(inputs);
    if (!r.ok) { onError?.(`Cannot open the layout — ${r.error}`); return; }
    const model = r.doc.model;
    const kindHint = modelKindHint(dataSource.capabilities, model as ModelKind);
    if (kindHint) { onError?.(`Cannot open ${r.fileName}: ${kindHint.message}`); return; }
    const whole = await loadSchemaGraph(model);
    if (!whole) { onError?.(`Cannot open ${r.fileName}: the ${model} schema could not be read.`); return; }
    const res = resolveRefs([...r.doc.objects, ...Object.keys(r.doc.positions)], layoutIndexOf(whole).index, model);
    const doc = normalizeForTab(r.doc, res);
    setShell((s) => openLayoutTab(s, { doc, source: 'file', fileName: r.fileName, dirty: false }));
    const unresolved = doc.objects.filter((o) => !res.resolved.has(o) && ![...res.resolved.values()].includes(o)).length;
    onNotice?.(`Opened ${doc.name}: ${doc.objects.length - unresolved} cards${unresolved ? `, ${unresolved} missing` : ''}${r.positionsFrom === 'sidecar' ? ' · positions from the .ttrl sidecar' : ''}`);
  }

  // load a schema subject's graph (once) + its saved layout positions (from the ViewStateStore,
  // falling back to the graph's own in-file layout when no store is wired).
  async function ensureGraph(ref: string) {
    if (graphs[ref] !== undefined || loadingRef.current.has(ref)) return;
    loadingRef.current.add(ref);
    try {
      const g = await dataSource.getGraph(ref);
      setGraphs((m) => ({ ...m, [ref]: g }));
      // a catalog's schema subject IS the whole schema — keep one read (VL V-21)
      if (g && ref === g.schema && dataSource.getSchemaGraph) setSchemaGraphs((m) => (m[ref] !== undefined ? m : { ...m, [ref]: g }));
      let saved: Record<string, { x: number; y: number }> | null = null;
      if (viewState) {
        const vs = await viewState.read(ref).catch(() => null);
        if (vs && Object.keys(vs.nodes).length > 0) saved = vs.nodes;
      }
      if (!saved && g?.layout?.nodes && Object.keys(g.layout.nodes).length > 0) saved = g.layout.nodes;
      if (saved) setPositions((p) => ({ ...p, [ref]: saved! }));
    } catch (e) {
      onError?.(`Failed to open ${ref}: ${e}`);
      setGraphs((m) => ({ ...m, [ref]: null }));
    } finally {
      loadingRef.current.delete(ref);
    }
  }

  function openItem(item: CatalogItem, preview = true) {
    if (item.layout) { void openCatalogLayout(item.layout.name); return; }
    const subject = subjectOf(item);
    setShell((s) => openSubject(s, subject, { preview }));
    if (item.kind === 'schema') void ensureGraph(item.ref);
  }

  useEffect(() => {
    // a lineage tab roots at an object qname (not a graph uri) — don't fetch a graph for it; a layout
    // tab is projected from its schema's whole graph, it has no graph of its own.
    if (tab?.subject.kind === 'schema' && tab.perspective !== 'lineage' && !tab.layout) void ensureGraph(tab.subject.ref);
    if (tab?.subject.kind === 'schema' && tab.perspective !== 'lineage' && tab.subject.schemaCode) {
      // a plain schema subject's whole graph: once its own graph is in (a catalog shares the read)
      if (tab.layout || graphs[tab.subject.ref] !== undefined) void ensureSchemaGraph(tab.subject.schemaCode);
    }
  }, [tab?.subject.ref, tab?.perspective, tab?.layout ? 'layout' : 'plain', tab ? graphs[tab.subject.ref] : undefined]);

  // the tab menu closes on a press outside it and on Escape, like any menu
  useEffect(() => {
    if (!tabMenu) return;
    const outside = (e: Event) => { if (!tabMenuRef.current?.contains(e.target as Node)) setTabMenu(null); };
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') setTabMenu(null); };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [tabMenu]);

  // a multi-selection belongs to the tab it was made on (V-27) — another tab's ids must not leak into its menu
  useEffect(() => { setMultiSel(new Set()); }, [tab?.id]);

  // fetch the project-wide binding map once the catalog is available (C-2). Failure is soft:
  // no bindings ⇒ no show-bindings decoration and an empty ribbon. getBindings is absent on
  // WS/Veles (capabilities.bindings === false) → honest empty (DM-CAP-001 gates the perspective).
  useEffect(() => {
    if (catalog.length === 0 || bindings !== null) return;
    Promise.resolve(dataSource.getBindings?.())
      .then((b) => setBindings(b ?? { entities: [], attributes: [], queries: [] }))
      .catch(() => setBindings({ entities: [], attributes: [], queries: [] }));
  }, [catalog, bindings, dataSource]);

  const bindingHints = useMemo(() => (bindings ? buildBindingHints(bindings) : undefined), [bindings]);
  const perspectivesEnabled = dataSource.capabilities.perspectives;

  // Deep-link boot (contracts §6): once the catalog is available, parse the URL and open the
  // linked subject durably. An unknown ref is the DS-SHELL-001 hint, never a crash. Runs once.
  useEffect(() => {
    if (bootedRef.current || catalog.length === 0) return;
    bootedRef.current = true;
    const known = new Set<string>();
    for (const g of catalog) for (const it of g.items) known.add(it.ref);
    const url = parsePath(initialUrlRef.current ?? '', known);
    if (url.kind === 'subject') {
      const item = catalog.flatMap((g) => g.items).find((it) => it.ref === url.subjectRef);
      if (item) openItem(item, false);
    } else if (url.kind === 'unknownSubject') {
      setBootHint(`This link points to “${url.subjectRef}”, which isn't in this workspace (DS-SHELL-001).`);
    } else if (url.kind === 'perspective') {
      // a shared perspective link restores the exact scoped view in a fresh session (§6, A-2 γ).
      if (url.perspective === 'lineage' && url.params.root) {
        const root = url.params.root;
        openLineage(root, url.params.kind ?? 'measure', url.params.label ?? root.split('.').pop() ?? root);
        const scope = (url.params.scope as LineageScope) ?? 'neighborhood';
        const direction = (url.params.dir as LineageDirection) ?? 'upstream';
        updateLineage(`${root}#lineage`, { scope, direction });
      } else if (url.perspective === 'binding' && url.params.left) {
        const item = catalog.flatMap((g) => g.items).find((it) => it.ref === url.params.left);
        if (item) {
          openItem(item, false);
          setShell((s) => setPerspective(s, item.ref, 'binding'));
          if (url.params.sel) setBindingSel(url.params.sel);
        } else {
          setBootHint(`This link points to “${url.params.left}”, which isn't in this workspace (DS-SHELL-001).`);
        }
      }
    } else if (url.kind === 'none') {
      // Federation fallback (FO §3): an inbound cross-app deep link (`/s/viewer?object=…`,
      // `/s/lineage?cell=…`, `/s/process?program=…`) isn't an internal §6 path, so parsePath
      // yields `none`. Resolve it to the same open action a shared §6 link would take.
      const intent = federationIntentFromPath(initialUrlRef.current ?? '');
      if (intent?.kind === 'subject') {
        const item = catalog.flatMap((g) => g.items).find((it) => it.ref === intent.ref);
        if (item) openItem(item, false);
        else setBootHint(`This link points to “${intent.ref}”, which isn't in this workspace (DS-SHELL-001).`);
      } else if (intent?.kind === 'lineage') {
        openLineage(intent.root, 'measure', intent.root.split('.').pop() ?? intent.root);
      } else if (intent?.kind === 'process') {
        const item = catalog.flatMap((g) => g.items).find((it) => it.ref === intent.program && it.kind === 'program');
        if (item) openItem(item, false);
        else setBootHint(`This link points to the process “${intent.program}”, which isn't in this workspace (DS-SHELL-001).`);
      }
    }
  }, [catalog]);

  // URL round-trip: reflect the active tab in the address bar (a truth surface like the chip).
  useEffect(() => {
    if (!bootedRef.current) return;
    if (!tab) { syncUrl({ kind: 'none', workspace }); return; }
    // App paths throughout: replaceAppPath keeps the base path and the session parameters, so the
    // rewritten URL reloads into this same backend and graph (ttr-core#171).
    let path: string;
    if (tab.perspective === 'lineage') {
      const ls = lineageState[tab.id];
      path = formatPath({ kind: 'perspective', workspace, perspective: 'lineage', params: { root: tab.subject.ref, scope: ls?.scope ?? 'neighborhood', dir: ls?.direction ?? 'upstream', kind: ls?.rootKind ?? 'measure', label: tab.subject.label.replace(/^lineage · /, '') } });
    } else if (tab.perspective === 'binding') {
      path = formatPath({ kind: 'perspective', workspace, perspective: 'binding', params: { left: tab.subject.ref, ...(bindingSel ? { sel: bindingSel } : {}) } });
    } else {
      path = tabUrl(workspace, tab);
    }
    replaceAppPath(path);
  }, [tab, workspace, lineageState, bindingSel]);

  // ⌘K command registry — every toolbar action gets a command twin (E-4/D-6 parity)
  const registry = useMemo(() => new CommandRegistry(), []);
  useEffect(() => {
    const r = registry;
    for (const g of catalog) for (const item of g.items) {
      r.register({ id: `open:${item.ref}`, title: `Open ${item.label}`, group: 'Catalog', toolbarAction: 'open.subject', run: () => openItem(item, false) });
    }
    // the layout commands (V-33) — ⌘K twins of the layout toolbar; Save as… / New / Re-layout need a canvas
    const canvas = !!tab && tab.subject.kind === 'schema' && !tab.perspective;
    r.register({ id: 'layout.open', title: 'Open layout…', group: 'Layout', toolbarAction: 'layout.open', run: () => layoutActions.current.open() });
    r.register({ id: 'layout.saveAs', title: 'Save layout as…', group: 'Layout', toolbarAction: 'layout.saveAs', disabled: !canvas, run: () => layoutActions.current.saveAs() });
    r.register({ id: 'layout.new', title: 'New layout from this view', group: 'Layout', toolbarAction: 'layout.new', disabled: !canvas, run: () => layoutActions.current.newLayout() });
    r.register({ id: 'layout.relayout', title: 'Re-layout', group: 'Layout', toolbarAction: 'layout.relayout', disabled: !canvas, run: () => layoutActions.current.relayout() });
    r.unregister('perspective.binding');
    r.unregister('bindings.toggle');
    r.unregister('processing.run');
    r.unregister('insert.node');
    if (tab) {
      r.register({ id: 'pin.tab', title: 'Pin current tab', group: 'Tabs', toolbarAction: 'pin.tab', run: () => setShell((s) => promoteTab(s, s.activeTabId!)) });
      r.register({ id: 'drawer.open', title: 'Open text drawer', group: 'Drawer', toolbarAction: 'drawer.open', run: () => setDrawerOpen(true) });
      // processing face (DM-P4): the Run command mirrors the canvas Run button (E-4 parity), offered
      // only on a program tab with an available run backend. The insert-palette command is armed only
      // when the authoring extension contributes the doors slot (canEdit) — absent in the open build.
      if (tab.subject.kind === 'program' && activeRunSource.available) {
        r.register({ id: 'processing.run', title: 'Run program', group: 'Processing', toolbarAction: 'processing.run', run: () => procRunRef.current?.() });
      }
      if (tab.subject.kind === 'program' && canEdit && editContext?.renderProcessingDoors) {
        r.register({ id: 'insert.node', title: 'Insert node…', group: 'Processing', toolbarAction: 'insert.node', run: () => insertPaletteRef.current?.() });
      }
      // Authoring verbs (W3.S2): the authoring extension contributes ⌘K commands (Add/Remove/
      // Rename object…, Edit as text). Registered ONLY when a context is present — the open build
      // contributes none, so no commercial verb is named in the palette (FO-21). Bound to the
      // focused subject (the selected node, else null).
      if (editContext?.commands && tab) {
        const subject = selected ? { qname: selected.qname, graphRef: tab.subject.ref } : null;
        for (const ac of editContext.commands) {
          r.register({ id: `authoring:${ac.id}`, title: ac.title, group: ac.group ?? 'Authoring', run: () => ac.run(subject) });
        }
      }
      // binding perspective + show-bindings are er↔db only (C-2), and need a bindings-capable
      // backend (DM-CAP-001) — offered only when perspectives are available.
      if (tab.subject.schemaCode === 'er' && perspectivesEnabled) {
        r.register({
          id: 'perspective.binding', title: 'Switch to binding perspective', group: 'Perspective', toolbarAction: 'perspective.switch',
          run: () => setShell((s) => setPerspective(s, s.activeTabId!, activeTab(s)?.perspective === 'binding' ? undefined : 'binding')),
        });
        r.register({
          id: 'bindings.toggle', title: 'Toggle show bindings', group: 'View', toolbarAction: 'bindings.toggle',
          run: () => setShowBindings((v) => !v),
        });
      }
    }
    return () => {}; // registry lives for the frame's lifetime
  }, [catalog, tab, registry, canEdit, perspectivesEnabled, activeRunSource, editContext, selected]);
  useCmdKShortcut(() => setCmdkOpen(true));

  async function onSelectNode(qname: string | null) {
    if (!qname) { setSelected(null); return; }
    // A structural catalog (Veles) has no getSymbolDetail: it describes the object itself — the
    // label in the viewer's language beside the identifier, plural, description, members (#170).
    if (!dataSource.getSymbolDetail && dataSource.describe) {
      const d = await dataSource.describe(qname).catch(() => null);
      setSelected({
        qname, kind: d?.kind ?? 'object', label: d?.label ?? qname.split('.').pop() ?? qname,
        identifier: d?.identifier, labelPlural: d?.labelPlural, description: d?.description, members: d?.members,
      });
      setDrawerOpen(true);
      return;
    }
    const detail = await dataSource.getSymbolDetail?.(qname).catch(() => null);
    const src = detail?.sourceUri ? getSourceText?.(detail.sourceUri) : undefined;
    setSelected({
      qname, kind: detail?.kind ?? 'object', label: detail?.name ?? qname.split('.').pop() ?? qname,
      description: detail?.description ?? undefined, sourceText: src,
      sourceUri: detail?.sourceUri, sourceLine: detail?.sourceLine,
      // W2 (contracts §5): a member detail carries its lineage entry + semantic root kind.
      lineageRoot: detail?.lineageRoot,
      rootKind: detail?.member?.memberKind ?? detail?.kind,
    });
    setDrawerOpen(true);
  }

  async function refetchGraph(ref: string) {
    const g = await dataSource.getGraph(ref);
    setGraphs((m) => ({ ...m, [ref]: g }));
  }

  // persist a canvas view change through the ViewStateStore (read-modify-write to keep skin/collapsed).
  async function persistView(canvasKey: string, change: CanvasViewChange) {
    if (!viewState) return;
    try {
      const cur = await viewState.read(canvasKey).catch(() => null);
      await viewState.write(canvasKey, {
        skin: cur?.skin ?? 'er.crow',
        mode: change.mode,
        nodes: change.positions,
        collapsed: cur?.collapsed ?? [],
      });
    } catch (e) { onError?.(`Failed to save layout: ${e}`); }
  }

  // ---- the working layout (VL §5): a layout tab shows its projection of the schema's whole graph ----
  const tabLayout = tab?.subject.kind === 'schema' ? tab.layout : undefined;
  const schemaCode = tab?.subject.kind === 'schema' ? tab.subject.schemaCode : undefined;
  const wholeGraph = schemaCode ? schemaGraphs[schemaCode] ?? null : null;
  const wholeIndex = useMemo<LayoutGraphIndex | null>(() => (wholeGraph ? layoutIndexOf(wholeGraph) : null), [wholeGraph]);
  // the picture's contents follow the cards and the model only — a drag, a skin pick or a save keeps them
  const layoutObjects = tabLayout?.doc.objects;
  const layoutModel = tabLayout?.doc.model;
  const projection = useMemo<LayoutProjection | null>(
    () => (layoutObjects && layoutModel && wholeGraph && wholeIndex
      ? projectLayout(wholeGraph, { objects: layoutObjects, model: layoutModel }, { hops: 1 }, wholeIndex)
      : null),
    [layoutObjects, layoutModel, wholeGraph, wholeIndex],
  );
  // node sizes as the schema's default skin draws them (placement, V-19)
  const sizeOf = useMemo(() => {
    if (!wholeGraph) return null;
    const skin = defaultSkinFor(wholeGraph.schema);
    const nodes = new Map(canvasOf(wholeGraph, displayMode).nodes.map((n) => [n.id, n]));
    return (id: string): Size => {
      const n = nodes.get(id);
      return n ? skin.nodeSize(n) : { width: 200, height: 60 };
    };
  }, [wholeGraph, displayMode]);

  /**
   * Positions for `cards` (full qnames) not yet placed, beside their nearest visible neighbour (V-19).
   * `held` are the placed cards by full qname; empty ⇒ auto mode, which stays auto (nothing to place).
   */
  function placeCards(cards: readonly string[], held: Record<string, LayoutPoint>, shown: readonly string[], centre?: Point): Record<string, LayoutPoint> {
    if (!wholeGraph || !wholeIndex || !sizeOf || Object.keys(held).length === 0) return {};
    const toId = (fq: string) => wholeIndex.nodeOf.get(fq) ?? fq;
    const byId: Record<string, Point> = {};
    for (const [fq, p] of Object.entries(held)) byId[toId(fq)] = p;
    const visibleIds = new Set([...shown, ...cards].map(toId));
    const at = centre ?? viewportCenterRef.current?.() ?? centreOf(byId, sizeOf);
    const placed: Record<string, LayoutPoint> = {};
    for (const fq of cards) {
      const id = toId(fq);
      if (byId[id] || !wholeIndex.fullQnameOf.has(id)) continue;
      const nbs = visibleNeighbours(wholeGraph, visibleIds, id).filter((n) => byId[n]);
      const p = placeNew(id, byId, sizeOf, nbs, at);
      byId[id] = p;
      placed[fq] = p;
    }
    return placed;
  }

  // node positions of a layout tab, by the canvas's node ids (the doc keys them by full qname). A manual
  // layout with cards it has no position for (a hand-written file) places them for display (V-19).
  const layoutNodePositions = useMemo(() => {
    if (!tabLayout || !projection || !wholeIndex) return null;
    const out: Record<string, LayoutPoint> = {};
    const held: Record<string, LayoutPoint> = {};
    for (const [ref, p] of Object.entries(tabLayout.doc.positions)) {
      const fq = projection.resolved.get(ref) ?? ref;
      const id = wholeIndex.nodeOf.get(fq);
      if (id !== undefined) { out[id] = p; held[fq] = p; }
    }
    if (Object.keys(out).length === 0) return NO_POSITIONS;
    const shown = [...new Set(projection.resolved.values())];
    const unplaced = shown.filter((fq) => !held[fq]).sort(compareAlphabetical);
    if (unplaced.length > 0 && sizeOf) {
      for (const [fq, p] of Object.entries(placeCards(unplaced, held, shown, centreOf(out, sizeOf)))) out[wholeIndex.nodeOf.get(fq) ?? fq] = p;
    }
    return out;
  }, [tabLayout?.doc.positions, projection, wholeIndex, sizeOf]);
  const inspectedHop = selectedHop ? projection?.hopEdges.find((h) => h.id === selectedHop) ?? null : null;

  /** The full qnames a tab shows: its layout's resolved cards, or — a plain subject — its graph's nodes. */
  function shownCards(t: SubjectTab): string[] {
    if (t.layout) return projection && t === tab ? [...new Set(projection.resolved.values())] : t.layout.doc.objects;
    const g = graphs[t.subject.ref];
    return g ? g.nodes.map((n) => wholeIndex?.fullQnameOf.get(n.qname) ?? n.qname) : [];
  }

  /**
   * Set the active tab's cards (V-19). A plain subject becomes a new, unsaved layout on its first
   * selection edit (V-22) — its positions (session drags) come along; on a layout tab the refs the
   * model lacks (missing / ambiguous) stay until removed (V-15).
   */
  function applyObjects(nextCards: string[]) {
    if (!tab || tab.subject.kind !== 'schema' || !tab.subject.schemaCode) return;
    const unresolved = tab.layout && projection ? [...projection.missing, ...projection.ambiguous.map((a) => a.ref)] : [];
    const objects = [...new Set([...nextCards, ...unresolved])];
    const before = new Set(shownCards(tab));
    const added = nextCards.filter((fq) => !before.has(fq));
    if (tab.layout) {
      // the canvas's own view of a manual layout includes the cards placed for display only
      const held: Record<string, LayoutPoint> = {};
      if (layoutNodePositions && wholeIndex) for (const [id, p] of Object.entries(layoutNodePositions)) held[wholeIndex.fullQnameOf.get(id) ?? id] = p;
      const placed = Object.keys(tab.layout.doc.positions).length > 0 ? { ...held, ...placeCards(added, held, nextCards) } : {};
      setShell((s) => setLayoutObjects(s, tab.id, objects, placed));
      return;
    }
    const held = positions[tab.subject.ref] ?? {};
    const seeded: Record<string, LayoutPoint> = {};
    for (const [id, p] of Object.entries(held)) seeded[wholeIndex?.fullQnameOf.get(id) ?? id] = p;
    const placed = placeCards(added, seeded, nextCards);
    const skin = subjectSkins[tab.id];
    const layout: TabLayout = {
      doc: { name: seedLayoutName(tab), model: tab.subject.schemaCode as TabLayout['doc']['model'], objects: [], positions: {}, ...(skin ? { skin } : {}) },
      source: 'new',
      dirty: true,
    };
    const keep = new Set(objects);
    const start: Record<string, LayoutPoint> = {};
    if (Object.keys(seeded).length > 0) {
      for (const [fq, p] of Object.entries(seeded)) if (keep.has(fq)) start[fq] = p;
      Object.assign(start, placed);
    }
    layout.doc = { ...layout.doc, objects, positions: start };
    setShell((s) => toLayoutTab(s, tab.id, layout));
  }

  /** Check `cards` into the active tab (Show, Expand, the panel) — V-19. */
  function showCards(cards: string[]) {
    if (!tab) return;
    applyObjects([...new Set([...shownCards(tab), ...cards])]);
  }

  /** Expand (V-27) / Neighbours (V-20): check the hidden cards one real edge away from `of`. */
  function expandCards(of: string[]) {
    if (!tab || !wholeGraph || !wholeIndex) return;
    const shownIds = new Set(shownCards(tab).map((fq) => wholeIndex.nodeOf.get(fq) ?? fq));
    const ids = of.map((fq) => wholeIndex.nodeOf.get(fq) ?? fq);
    const add = hiddenNeighbours(wholeGraph, shownIds, ids).map((id) => wholeIndex.fullQnameOf.get(id) ?? id);
    if (add.length === 0) { onNotice?.('Every neighbour is already visible'); return; }
    showCards(add);
    onNotice?.(`Expanded: +${add.length}`);
  }

  /** Hide (V-27, the panel): uncheck `cards`. */
  function hideCards(cards: string[]) {
    if (!tab) return;
    const drop = new Set(cards);
    applyObjects(shownCards(tab).filter((fq) => !drop.has(fq)));
  }

  /** Remove a missing / ambiguous ref from the layout (V-17). */
  function removeRef(ref: string) {
    if (!tab?.layout) return;
    const id = tab.id;
    setShell((s) => {
      const l = s.tabs.find((t) => t.id === id)?.layout;
      return l ? setLayoutObjects(s, id, l.doc.objects.filter((o) => o !== ref)) : s;
    });
  }

  /** Re-layout (V-19): ELK over the cards shown, then manual with the result — the layout's or the session's. */
  async function relayout() {
    if (!tab || tab.subject.kind !== 'schema' || !currentGraph) return;
    const skin = defaultSkinFor(currentGraph.schema);
    const p = await layoutAuto(canvasOf(currentGraph, displayMode), { orientation: skin.flow.orientation, sizeOf: (n) => skin.nodeSize(n) });
    const tabId = tab.id;
    const ref = tab.subject.ref;
    if (tab.layout) {
      if (!wholeIndex) return;
      setShell((s) => {
        const l = s.tabs.find((t) => t.id === tabId)?.layout;
        if (!l) return s;
        const next: Record<string, LayoutPoint> = { ...l.doc.positions }; // the missing refs keep theirs
        for (const [id, pt] of Object.entries(p)) {
          const fq = wholeIndex.fullQnameOf.get(id);
          if (fq) next[fq] = { x: pt.x, y: pt.y };
        }
        return setLayoutPositions(s, tabId, next);
      });
    } else {
      setPositions((m) => ({ ...m, [ref]: p }));
      void persistView(ref, { positions: p, displayMode, mode: 'manual' });
    }
  }

  /**
   * The view menu's items (V-27) for a right-clicked card — applied to the shift-selection when the card
   * is in it — or a hop edge (Show <H> per hidden card).
   */
  function viewMenuItems(target: ViewMenuTarget): ViewMenuEntry[] {
    if (!tab || !wholeGraph || !wholeIndex) return [];
    const toFq = (id: string) => wholeIndex.fullQnameOf.get(id) ?? id;
    const nodeOf = new Map(wholeGraph.nodes.map((n) => [n.qname, n]));
    if (target.kind === 'edge') {
      const hop = projection?.hopEdges.find((h) => h.id === target.id);
      return (hop?.via ?? []).map((fq) => ({
        id: `show:${fq}`,
        label: `Show ${nodeOf.get(wholeIndex.nodeOf.get(fq) ?? fq)?.label ?? fq}`,
        run: () => showCards([fq]),
      }));
    }
    const set = multiSel.has(target.id) && multiSel.size > 1 ? [...multiSel] : [target.id];
    const many = set.length > 1 ? ` (${set.length} cards)` : '';
    const shownIds = new Set(shownCards(tab).map((fq) => wholeIndex.nodeOf.get(fq) ?? fq));
    const frontier = hiddenNeighbours(wholeGraph, shownIds, set);
    return [
      { id: 'expand', label: `Expand${many}`, hint: `+${frontier.length}`, disabled: frontier.length === 0, run: () => expandCards(set.map(toFq)) },
      {
        id: 'expand-all', label: 'Expand all levels…', hint: 'closure',
        run: () => {
          const add = hiddenClosure(wholeGraph, shownIds, set);
          if (add.length === 0) { onNotice?.('Nothing to add'); return; }
          setConfirm({
            text: `This adds ${add.length} card${add.length === 1 ? '' : 's'} (${shownIds.size + add.length} of ${wholeGraph.nodes.length} shown afterwards). On a connected model that is usually most of it.`,
            onYes: () => showCards(add.map(toFq)),
          });
        },
      },
      { separator: true },
      { id: 'hide', label: `Hide${many}`, run: () => { hideCards(set.map(toFq)); setMultiSel(new Set()); } },
      {
        id: 'hide-others', label: 'Hide others', hint: 'keep + neighbours',
        run: () => {
          const keep = new Set(set);
          for (const id of set) for (const n of visibleNeighbours(wholeGraph, shownIds, id)) keep.add(n);
          applyObjects([...keep].map(toFq));
        },
      },
      { separator: true },
      { id: 'inspect', label: 'Inspect', run: () => { setSelectedHop(null); void onSelectNode(target.id); } },
    ];
  }

  // ---- Save as… / New layout (V-32, V-33) ----

  /** The layout a tab would save: its working layout, or — a plain subject — the cards it shows. */
  function docForSave(t: SubjectTab): LayoutDocument | null {
    if (t.subject.kind !== 'schema' || !t.subject.schemaCode || t.perspective) return null;
    if (t.layout) return t.layout.doc;
    const objects = shownCards(t);
    const listed = new Set(objects);
    const held = positions[t.subject.ref] ?? {};
    const pos: Record<string, LayoutPoint> = {};
    for (const [id, p] of Object.entries(held)) {
      // a held position is not always a card shown (a stale node in the store, an id the whole graph
      // cannot map) — the file lists its positions' cards, or its reader refuses it (V-3)
      const fq = wholeIndex?.fullQnameOf.get(id) ?? id;
      if (listed.has(fq)) pos[fq] = p;
    }
    const skin = subjectSkins[t.id];
    return { name: seedLayoutName(t), model: t.subject.schemaCode as LayoutDocument['model'], objects, positions: pos, ...(skin ? { skin } : {}) };
  }

  const formatKey = `ttr-viewer:layout-format:${workspace}`;
  /** Save as…'s format: the user's last choice in this workspace → VIEWER_LAYOUT_FORMAT → yaml (V-32). */
  function preferredFormat(): LayoutFormat {
    try {
      const saved = window.localStorage.getItem(formatKey);
      if (saved === 'yaml' || saved === 'ttr') return saved;
    } catch { /* no storage — fall through */ }
    return getRuntimeConfig().layoutFormat;
  }

  async function saveLayoutAs(tabId: string, name: string, format: LayoutFormat) {
    const t = shellRef.current.tabs.find((x) => x.id === tabId);
    const doc = t ? docForSave(t) : null;
    if (!t || !doc) return;
    const { fileName, mimeType } = layoutFileOf(name, format);
    try {
      await downloadFile(fileName, layoutTextOf(doc, name, format), mimeType);
    } catch (e) {
      onError?.(`Could not save ${fileName}: ${e}`);
      return;
    }
    try { window.localStorage.setItem(formatKey, format); } catch { /* no storage — the choice is not remembered */ }
    setShell((s) => (t.layout
      ? markSaved(s, tabId, name, fileName)
      : toLayoutTab(s, tabId, { doc: { ...doc, name }, source: 'file', fileName, dirty: false })));
    onNotice?.(`Saved ${fileName}`);
  }

  /** New layout (V-33): a new, unsaved layout tab of what the current tab shows. */
  function newLayout() {
    if (!tab) return;
    const doc = docForSave(tab);
    if (!doc) return;
    setShell((s) => openLayoutTab(s, { doc: { ...doc, name: 'untitled' }, source: 'new', dirty: true }));
  }

  const layoutActions = useRef({ open: () => {}, saveAs: () => {}, newLayout: () => {}, relayout: () => {} });
  layoutActions.current = {
    open: () => void pickFiles(LAYOUT_FILE_ACCEPT, true).then(openLayoutFiles),
    saveAs: () => { if (tab && docForSave(tab)) setSaveAsTab(tab.id); },
    newLayout,
    relayout: () => void relayout(),
  };
  const canvasTab = !!tab && tab.subject.kind === 'schema' && !tab.perspective;

  // unsaved local work arms the browser's leave-page prompt (V-30); a catalog layout re-reads on reload
  const unsaved = hasUnsavedLocalLayout(shell);
  useEffect(() => {
    if (!unsaved) return;
    const guard = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [unsaved]);

  const missingRefs: MissingRef[] = useMemo(() => (projection
    ? [...projection.missing.map((ref) => ({ ref })), ...projection.ambiguous.map((a) => ({ ref: a.ref, candidates: a.candidates }))]
        .sort((a, b) => compareAlphabetical(a.ref, b.ref))
    : []), [projection]);

  // a drag on a layout tab → its positions, by full qname (V-28), keeping the missing cards' ones; the
  // skin and collapsed stay as they are. A display-mode switch re-emits the canvas's positions too: an
  // `auto` one is the canvas's own layout (the layout stays auto, V-2), a `manual` one that moves nothing
  // leaves the layout clean.
  function persistLayoutView(tabId: string, change: CanvasViewChange) {
    if (change.mode !== 'manual') return;
    const given = layoutNodePositions ?? NO_POSITIONS;
    if (Object.entries(change.positions).every(([id, p]) => given[id]?.x === p.x && given[id]?.y === p.y)) return;
    if (!wholeIndex) return;
    setShell((s) => {
      const l = s.tabs.find((t) => t.id === tabId)?.layout;
      if (!l) return s;
      const next: Record<string, LayoutPoint> = { ...l.doc.positions };
      for (const [id, p] of Object.entries(change.positions)) {
        const fq = wholeIndex.fullQnameOf.get(id);
        if (fq) next[fq] = { x: p.x, y: p.y };
      }
      return setLayoutPositions(s, tabId, next);
    });
  }

  // a drag on a plain subject → the store, and the shell's own copy: the first selection edit (V-22),
  // Save as… and New layout start from it (a backend without a store keeps the drags only here). An
  // `auto` change is a display-mode switch re-emitting the canvas's own layout — nothing to keep, and an
  // auto canvas with nodes is a `.ttrl` the designer server's own loader refuses.
  function persistSubjectView(ref: string, change: CanvasViewChange) {
    if (change.mode !== 'manual') return;
    setPositions((m) => ({ ...m, [ref]: change.positions }));
    void persistView(ref, change);
  }

  const isLayoutTab = !!tabLayout;
  const subjectGraph = tab?.subject.kind === 'schema' ? graphs[tab.subject.ref] : undefined;
  const currentGraph = useMemo(() => {
    if (tab?.subject.kind !== 'schema') return null;
    if (!isLayoutTab) return subjectGraph ?? null;
    return projection ? { ...projection.graph, edges: [...projection.graph.edges, ...projection.hopEdges.map(hopAsEdge)] } : null;
  }, [tab?.subject.kind, isLayoutTab, subjectGraph, projection]);

  // stable, so the kernel's React Flow edges are not rebuilt on every shell render
  const onEdgeSelect = useCallback((id: string | null) => {
    if (id && id.startsWith('hop:')) { setSelectedHop(id); setDrawerOpen(false); } else setSelectedHop(null);
  }, []);
  const modelGraph = useMemo(
    () => (currentGraph ? applyGraphFixtures(getGraphResponseToModelGraph(currentGraph)) : null),
    [currentGraph],
  );
  const currentImports = currentGraph?.imports ?? [];
  const missingObjects = currentGraph?.missingObjects ?? [];

  // is the active tab an er canvas showing the binding perspective? (C-2 — er↔db only)
  const isErTab = tab?.subject.kind === 'schema' && tab.subject.schemaCode === 'er';
  const bindingActive = isErTab && tab?.perspective === 'binding';
  const bindingRibbon = useMemo(() => {
    if (!bindingActive || !bindings) return null;
    return generateBindingRibbon({
      er: modelGraph ? { nodes: modelGraph.nodes.map((n) => ({ qname: n.qname, label: n.label })) } : { nodes: [] },
      db: { nodes: [] },
      bindings,
      ...(bindingSel ? { selectedEntity: bindingSel } : {}),
    });
  }, [bindingActive, bindings, modelGraph, bindingSel]);

  const lineageActive = tab?.perspective === 'lineage';
  const lineageModel = useMemo(() => composeLineageModel(bindings), [bindings]);
  const lstate = tab ? (lineageState[tab.id] ?? { scope: 'neighborhood' as const, direction: 'upstream' as const, rootKind: 'measure' as const }) : null;
  const lineageResult = useMemo(() => {
    if (!lineageActive || !tab || !lstate) return null;
    return generateLineage({
      query: { root: { qname: tab.subject.ref, kind: lstate.rootKind }, scope: lstate.scope, direction: lstate.direction },
      model: lineageModel,
    });
  }, [lineageActive, tab, lstate, lineageModel]);

  function openLineage(qname: string, kind: string, label: string, rootRef?: LineageRootRef) {
    const rootKind = (['column', 'attribute', 'measure', 'calc'].includes(kind) ? kind : 'measure') as ObjectKind;
    const subject: Subject = { ref: qname, kind: 'schema', label: `lineage · ${label}` };
    const tabId = `${qname}#lineage`;
    // W2: the member/chip entry converge here; the LineageRootRef (kind:'member' for a
    // member) is recorded so the lineage host reflects the entry classification (contracts §5).
    setLineageState((m) => ({ ...m, [tabId]: m[tabId] ?? { scope: 'neighborhood', direction: 'upstream', rootKind, rootRef } }));
    setShell((s) => openSubject(s, subject, { preview: true, perspective: 'lineage' }));
  }
  function updateLineage(tabId: string, patch: Partial<{ scope: LineageScope; direction: LineageDirection }>) {
    setLineageState((m) => ({ ...m, [tabId]: { ...(m[tabId] ?? { scope: 'neighborhood', direction: 'upstream', rootKind: 'measure' }), ...patch } }));
  }

  // lift the active subject up so the host header reflects it (title, display pills, restore).
  useEffect(() => {
    onActiveChange?.(
      tab
        ? {
            ref: tab.subject.ref, label: tab.subject.label, kind: tab.subject.kind,
            imports: currentImports, missingObjects,
            viewportDisplayMode: currentGraph?.layout?.viewport?.displayMode as DisplayMode | undefined,
          }
        : null,
    );
  }, [tab?.id, currentGraph]);

  return (
    <div style={{ display: 'flex', flex: 1, minHeight: 0 }} data-testid="shell-frame">
      <CatalogSpine groups={catalog} onOpen={(item) => openItem(item, true)} />
      <FileRail files={files} />
      {tab?.subject.kind === 'schema' && !tab.perspective && wholeGraph && wholeIndex && (!tabLayout || projection) && (
        <ObjectsPanel
          whole={wholeGraph}
          index={wholeIndex}
          shown={new Set(shownCards(tab))}
          missing={missingRefs}
          onSetCards={(cards) => applyObjects(cards)}
          onNeighbours={() => expandCards(shownCards(tab))}
          onRemoveMissing={removeRef}
          onExpand={(fq) => expandCards([fq])}
          onHide={(fq) => hideCards([fq])}
        />
      )}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        {/* FO-33 (contracts §7): the suite brand names "Tatrman Studio"; the OPEN build identifies as
            "Studio Viewer" (no editContext). The commercial module names (Studio Modeler/Designer)
            live in the authoring extension's surfaces, never here (FO-21). */}
        <div
          data-testid="suite-brand"
          style={{ display: 'flex', alignItems: 'baseline', gap: 8, padding: '4px 12px', borderBottom: '1px solid #EDF2F9', fontSize: 12 }}
        >
          <strong style={{ letterSpacing: '.02em', color: '#16283F' }}>Tatrman Studio</strong>
          {!editContext && (
            <span data-testid="build-name" style={{ fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '.06em', color: '#96989B' }}>Studio Viewer</span>
          )}
        </div>
        {bootHint && (
          <div
            data-testid="ds-shell-001-hint"
            style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px', background: '#FEF3C7', color: '#92400E', fontSize: 12.5, borderBottom: '1px solid #FDE68A' }}
          >
            <span style={{ flex: 1 }}>{bootHint}</span>
            <button onClick={() => setBootHint(null)} style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: '#92400E', fontSize: 14 }} aria-label="Dismiss">×</button>
          </div>
        )}
        <TabBar
          state={shell}
          onFocus={(id) => setShell((s) => dropPreviewIfLeaving(s, id))}
          onPromote={(id) => setShell((s) => promoteTab(s, id))}
          onClose={(id) => setShell((s) => closeTab(s, id))}
          onContextMenu={(id, x, y) => { setShell((s) => dropPreviewIfLeaving(s, id)); setTabMenu({ tabId: id, x, y }); }}
        />
        {tab && (
          <div style={{ display: 'flex', alignItems: 'center', paddingRight: 12 }}>
            <Breadcrumb tab={tab} onDrillTo={(i) => setShell((s) => drillTo(s, s.activeTabId!, i))} />
            {(() => {
              // a local layout is not on the server — its link would open nothing (V-35)
              if (tab.layout && tab.layout.source !== 'catalog') {
                return <CopyLinkButton url="" disabledReason="Saved files are not on the server — commit it to share." />;
              }
              const path = federationPathForTab(tab);
              const url = path && typeof window !== 'undefined' ? absoluteUrlFor(path) : null;
              return url ? <CopyLinkButton url={url} /> : null;
            })()}
            {(() => {
              // FO §3 "ask about this" — config-gated: no irisBaseUrl ⇒ no affordance (open Viewer).
              const askUrl = irisBaseUrl ? askUrlForTab(tab, irisBaseUrl) : null;
              return askUrl ? (
                <a
                  data-testid="ask-about-this"
                  href={askUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  title="Ask Iris about this view"
                  style={{ marginLeft: space.sm, display: 'inline-flex', alignItems: 'center', gap: space.xs + 2, border: `1px solid ${color.accentBorder}`, borderRadius: radius.sm, background: color.surface, color: color.accent, padding: `3px ${space.md - 2}px`, fontSize: fontSize.sm, textDecoration: 'none' }}
                >
                  💬 Ask about this
                </a>
              ) : null;
            })()}
          </div>
        )}
        {/* the layout actions (VL §5.4) — Open works with no tab open; the rest need a schema canvas */}
        <div data-testid="layout-toolbar" style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '5px 12px', borderBottom: '1px solid #EDF2F9' }}>
          <button data-testid="layout-new" title="A new layout of what this tab shows" disabled={!canvasTab}
            onClick={() => layoutActions.current.newLayout()} style={toolbarStyle(canvasTab)}>
            New layout
          </button>
          <button data-testid="layout-open" title="Open a layout file (.yaml, .ttrg + .ttrl) — or drop it on the canvas"
            onClick={() => layoutActions.current.open()} style={toolbarButton}>
            Open layout…
          </button>
          <button data-testid="layout-save-as" title="Download this layout as YAML or TTR" disabled={!canvasTab}
            onClick={() => layoutActions.current.saveAs()} style={toolbarStyle(canvasTab, true)}>
            Save as…
          </button>
          <button data-testid="layout-relayout" title="Arrange the cards shown automatically (they keep the new positions)"
            disabled={!(canvasTab && currentGraph)} onClick={() => void relayout()} style={toolbarStyle(!!(canvasTab && currentGraph))}>
            Re-layout
          </button>
        </div>
        {tab?.subject.kind === 'schema' && (
          <div
            data-testid="shell-subject-toolbar"
            style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px', borderBottom: '1px solid #E5E7EB' }}
          >
            {canEdit && editContext?.renderToolbarActions({
              graphRef: tab.subject.ref,
              currentImports,
              onApplied: () => void refetchGraph(tab.subject.ref),
            })}
            {isErTab && perspectivesEnabled && (
              <div data-testid="perspective-switch" role="group" style={{ display: 'inline-flex', border: '1px solid #CBD8E6', borderRadius: 6, overflow: 'hidden', fontSize: 12 }}>
                <button
                  data-testid="perspective-canvas"
                  aria-pressed={!tab.perspective}
                  onClick={() => setShell((s) => setPerspective(s, s.activeTabId!, undefined))}
                  style={{ padding: '3px 10px', border: 'none', cursor: 'pointer', background: !tab.perspective ? '#16283F' : '#fff', color: !tab.perspective ? '#fff' : '#33506e' }}
                >
                  Canvas
                </button>
                <button
                  data-testid="perspective-binding"
                  aria-pressed={tab.perspective === 'binding'}
                  onClick={() => setShell((s) => setPerspective(s, s.activeTabId!, 'binding'))}
                  style={{ padding: '3px 10px', border: 'none', cursor: 'pointer', background: tab.perspective === 'binding' ? '#16283F' : '#fff', color: tab.perspective === 'binding' ? '#fff' : '#33506e' }}
                >
                  Binding
                </button>
              </div>
            )}
            {isErTab && !perspectivesEnabled && (
              // DM-CAP-001: perspectives need a bindings-capable backend — visible, disabled, honest.
              <span data-testid="perspective-disabled" title="Perspectives need a bindings-capable backend (open a project locally)." style={{ fontSize: 12, color: '#96989B', border: '1px dashed #CBD8E6', borderRadius: 6, padding: '3px 10px' }}>
                Binding perspective unavailable
              </span>
            )}
            {missingObjects.length > 0 && (
              // read-only truth surface: how many subjects have decayed. Interactive removal is an
              // edit affordance contributed by the authoring extension (renderMissingObjects).
              <span
                data-testid="stale-count"
                className="text-xs bg-amber-100 text-amber-700 px-2 py-0.5 rounded border border-amber-300"
                title={`${missingObjects.length} object(s) no longer exist in the project`}
              >
                {missingObjects.length} stale
              </span>
            )}
            {canEdit && missingObjects.length > 0 && editContext?.renderMissingObjects({
              graphRef: tab.subject.ref,
              missingObjects,
              onApplied: () => void refetchGraph(tab.subject.ref),
            })}
          </div>
        )}
        <div
          data-testid="canvas-host"
          style={{ flex: 1, minHeight: 0, position: 'relative' }}
          // drop a layout file on the canvas = Open layout… (V-31)
          onDragOver={(e) => { if (e.dataTransfer?.types?.includes('Files')) e.preventDefault(); }}
          onDrop={(e) => {
            const dropped = Array.from(e.dataTransfer?.files ?? []);
            if (dropped.length === 0) return;
            e.preventDefault();
            void openLayoutFiles(dropped);
          }}
        >
          {!tab ? (
            <div data-testid="shell-no-tab" style={{ padding: 24, color: '#96989B' }}>Open a subject from the catalog.</div>
          ) : tab.subject.kind === 'program' ? (
            // the processing face (DM-P4). Read + run are OPEN; the insertion doors mount ONLY through
            // the authoring extension's `renderProcessingDoors` slot (FO-21) — absent here in the open build.
            <ProcessingCanvas
              source={procSource}
              programRef={tab.subject.ref}
              drillPath={tab.drillPath.map((d) => d.id)}
              selectedId={procSel}
              onSelect={setProcSel}
              onDrillIn={(id, label) => setShell((s) => drillIn(s, s.activeTabId!, { id, label }))}
              runSource={activeRunSource}
              runRef={procRunRef}
              renderInsertionDoors={canEdit && editContext?.renderProcessingDoors
                ? (slot) => editContext.renderProcessingDoors!(slot)
                : undefined}
              insertPaletteRef={insertPaletteRef}
              validateProgram={validateProgram}
            />
          ) : tab.subject.kind !== 'schema' ? (
            <div data-testid="shell-nonschema" style={{ padding: 24, color: '#96989B' }}>
              {tab.subject.label} — {tab.subject.kind} canvas arrives in a later phase.
            </div>
          ) : lineageActive && lineageResult && lstate ? (
            <DerivedCanvas
              result={{ kind: 'custom', view: 'lineage-layers', data: lineageResult }}
              bannerText={`Lineage · ${tab.subject.label.replace(/^lineage · /, '')} — derived, read-only.`}
              handlers={{
                lineage: {
                  scope: lstate.scope,
                  direction: lstate.direction,
                  handlers: {
                    onScopeChange: (scope) => updateLineage(tab.id, { scope }),
                    onDirectionChange: (direction) => updateLineage(tab.id, { direction }),
                    onOpenObject: (qname) => void onSelectNode(qname),
                    onRootAt: (qname, kind, label) => openLineage(qname, kind, label),
                  },
                },
              }}
            />
          ) : bindingActive && bindingRibbon ? (
            <DerivedCanvas
              result={{ kind: 'custom', view: 'binding-ribbon', data: bindingRibbon }}
              bannerText="Binding perspective — derived, read-only (er ↔ db)."
              handlers={{ onSelectEntity: setBindingSel }}
            />
          ) : !modelGraph ? (
            <div data-testid="shell-loading" style={{ padding: 24, color: '#96989B' }}>loading…</div>
          ) : (
            <SkinnedCanvas
              key={tab.id}
              graph={modelGraph}
              displayMode={displayMode}
              nodePositions={tabLayout ? layoutNodePositions ?? NO_POSITIONS : positions[tab.subject.ref] ?? NO_POSITIONS}
              viewportCenterRef={viewportCenterRef}
              canvasKey={tabLayout ? tab.id : tab.subject.ref}
              // the notation: a layout's own (V-28; one this viewer lacks shows substituted), else the session's pick
              initialSkin={tabLayout ? tabLayout.doc.skin : subjectSkins[tab.id]}
              onSkinChange={(skin) => (tabLayout
                ? setShell((s) => setLayoutSkin(s, tab.id, skin))
                : setSubjectSkins((m) => ({ ...m, [tab.id]: skin })))}
              selectedQname={multiSel.size > 0 ? null : selected?.qname ?? null}
              selectedEdgeId={inspectedHop?.id ?? null}
              onEdgeSelect={onEdgeSelect}
              onNodeSelect={(q, additive) => {
                setSelectedHop(null);
                if (additive && q) {
                  const single = selected?.qname;
                  setMultiSel((m) => {
                    const n = new Set(m);
                    // the first shift-click extends the selection already drawn — the card clicked before it
                    if (n.size === 0 && single && single !== q) n.add(single);
                    if (n.has(q)) n.delete(q); else n.add(q);
                    return n;
                  });
                  return;
                }
                setMultiSel(new Set());
                void onSelectNode(q);
              }}
              selectedIds={selectedIds}
              layoutChip={layoutChip(tab, dataSource.capabilities)}
              renderViewMenu={wholeGraph && wholeIndex
                ? (target, close) => <ViewMenu items={viewMenuItems(target)} onClose={close} />
                : undefined}
              renderNodeMenu={canEdit && editContext
                ? (qname, close) => editContext.renderNodeMenu({ qname, graphRef: tab.subject.ref, onApplied: () => void refetchGraph(tab.subject.ref), onClose: close })
                : undefined}
              onPersistView={(change) => (tabLayout ? persistLayoutView(tab.id, change) : persistSubjectView(tab.subject.ref, change))}
              onDrillIn={(id, label) => setShell((s) => drillIn(s, s.activeTabId!, { id, label }))}
              bindingHints={isErTab ? bindingHints : undefined}
              showBindings={isErTab && showBindings}
              onToggleShowBindings={() => setShowBindings((v) => !v)}
            />
          )}
        </div>
      </div>
      {inspectedHop && wholeGraph && wholeIndex && (
        <HopEdgeInspector
          hop={inspectedHop}
          whole={wholeGraph}
          nodeOf={wholeIndex.nodeOf}
          onShow={(fq) => { showCards([fq]); setSelectedHop(null); }}
          onClose={() => setSelectedHop(null)}
        />
      )}
      <TextDrawer
        open={drawerOpen && !inspectedHop}
        node={selected}
        onOpenInIde={(uri, line) => onError?.(`Open in IDE: ${uri}${line != null ? `:${line}` : ''} (host handoff)`)}
        onClose={() => setDrawerOpen(false)}
        onOpenLineage={openLineage}
        // member lineage needs Worker-side getSymbolDetail; WS/Veles degrade (A1-CAP-001, W2).
        memberLineageCapable={typeof dataSource.getSymbolDetail === 'function'}
        editEnabled={canEdit}
        // save routes through the ONE apply seam (the authoring context's generic saveNode) — the
        // shell never names the underlying edit op (FO-21). W3.S2 round-trip: a clean save refreshes
        // the canvas (onModelChanged → refetch); a rejected save returns the reason VERBATIM so the
        // drawer keeps the editor open and shows it (never a paraphrase, canvas untouched).
        onSaveEdit={async (n, text) => {
          if (!editContext?.editable) return { ok: true };
          const r = await editContext.saveNode(n.qname, text);
          if (r && !r.ok) return { ok: false, error: r.reason ?? 'Edit not applied.' };
          if (tab) await refetchGraph(tab.subject.ref); // onModelChanged → canvas refresh
          return { ok: true };
        }}
      />
      <CommandPalette registry={registry} open={cmdkOpen} onClose={() => setCmdkOpen(false)} />
      {tabMenu && (
        <div data-testid="tab-menu" ref={tabMenuRef} onMouseLeave={() => setTabMenu(null)}
          style={{ position: 'fixed', left: tabMenu.x, top: tabMenu.y, background: '#fff', border: '1px solid #CBD8E6', borderRadius: 6, boxShadow: '0 4px 12px rgba(0,0,0,.15)', zIndex: 20 }}>
          <ViewMenu
            onClose={() => setTabMenu(null)}
            items={(() => {
              const t = shell.tabs.find((x) => x.id === tabMenu.tabId);
              const canvas = !!t && t.subject.kind === 'schema' && !t.perspective;
              return [
                { id: 'layout.saveAs', label: 'Save layout as…', disabled: !canvas, run: () => layoutActions.current.saveAs() },
                { id: 'layout.new', label: 'New layout from this view', disabled: !canvas, run: () => layoutActions.current.newLayout() },
                { id: 'layout.relayout', label: 'Re-layout', disabled: !canvas, run: () => layoutActions.current.relayout() },
                { separator: true as const },
                { id: 'layout.open', label: 'Open layout…', run: () => layoutActions.current.open() },
              ];
            })()}
          />
        </div>
      )}
      {saveAsTab && (() => {
        const t = shell.tabs.find((x) => x.id === saveAsTab);
        const doc = t ? docForSave(t) : null;
        if (!t || !doc) return null;
        const initialName = t.layout ? (t.layout.doc.name === 'untitled' ? '' : t.layout.doc.name) : t.subject.ref === t.subject.schemaCode ? '' : doc.name;
        return (
          <SaveAsDialog
            doc={doc}
            initialName={initialName}
            initialFormat={preferredFormat()}
            onCancel={() => setSaveAsTab(null)}
            onSave={(name, format) => { setSaveAsTab(null); void saveLayoutAs(t.id, name, format); }}
          />
        );
      })()}
      {confirm && (
        <div data-testid="confirm-dialog" role="dialog" aria-modal="true"
          style={{ position: 'fixed', inset: 0, background: 'rgba(22,40,63,.25)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 50 }}
          onKeyDown={(e) => { if (e.key === 'Escape') setConfirm(null); }}>
          <div style={{ background: '#fff', borderRadius: 10, padding: 18, maxWidth: 420, boxShadow: '0 10px 30px rgba(0,0,0,.2)', fontSize: 13 }}>
            <p data-testid="confirm-text" style={{ margin: '0 0 14px' }}>{confirm.text}</p>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button data-testid="confirm-no" autoFocus onClick={() => setConfirm(null)}
                style={{ border: '1px solid #CBD8E6', borderRadius: 6, background: '#fff', cursor: 'pointer', padding: '4px 12px' }}>Cancel</button>
              <button data-testid="confirm-yes" onClick={() => { const yes = confirm.onYes; setConfirm(null); yes(); }}
                style={{ border: 'none', borderRadius: 6, background: '#16283F', color: '#fff', cursor: 'pointer', padding: '4px 12px' }}>Add them</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
