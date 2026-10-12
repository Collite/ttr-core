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

import { useEffect, useMemo, useRef, useState } from 'react';
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
  setLayoutObjects, setLayoutPositions, toLayoutTab,
} from './shell-state.js';
import { projectLayout, layoutIndexOf, type HopEdge, type LayoutGraphIndex, type LayoutProjection } from '../model/layout-projection.js';
import { LayoutDocumentStore } from '../data/view-state-store.js';
import type { LayoutPoint } from '../layout/layout-document.js';
import { HopEdgeInspector } from './HopEdgeInspector.js';
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

export function ShellFrame({ dataSource, workspace, catalog, files, displayMode, getSourceText, onError, onActiveChange, viewState, editContext, irisBaseUrl, processingSource, runSource, validateProgram }: ShellFrameProps) {
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
  const schemaLoadingRef = useRef<Set<string>>(new Set());
  // the hop edge open in the inspector (VL V-26), by edge id; derived, cleared when it disappears.
  const [selectedHop, setSelectedHop] = useState<string | null>(null);
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

  // the layout tabs' store (VL V-28): positions / skin / collapsed live in the tab's layout; a write
  // marks it dirty. Keyed by tab id.
  const layoutStore = useMemo(() => new LayoutDocumentStore({
    get: (tabId) => {
      const l = shellRef.current.tabs.find((t) => t.id === tabId)?.layout;
      return l ? { positions: l.doc.positions, skin: l.doc.skin, collapsed: l.doc.collapsed } : undefined;
    },
    set: (tabId, st) => setShell((s) => setLayoutPositions(s, tabId, st.positions, { collapsed: st.collapsed })),
  }), []);

  async function ensureSchemaGraph(code: string) {
    if (schemaGraphs[code] !== undefined || schemaLoadingRef.current.has(code) || !dataSource.getSchemaGraph) return;
    if (code !== 'db' && code !== 'er' && code !== 'md' && code !== 'cnc') return;
    if (graphs[code]) { setSchemaGraphs((m) => ({ ...m, [code]: graphs[code] })); return; } // a catalog's schema subject
    schemaLoadingRef.current.add(code);
    try {
      const g = await dataSource.getSchemaGraph(code);
      setSchemaGraphs((m) => ({ ...m, [code]: g }));
    } catch (e) {
      onError?.(`Failed to read the ${code} schema: ${e}`);
      setSchemaGraphs((m) => ({ ...m, [code]: null }));
    } finally {
      schemaLoadingRef.current.delete(code);
    }
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
  const projection = useMemo<LayoutProjection | null>(
    () => (tabLayout && wholeGraph && wholeIndex ? projectLayout(wholeGraph, tabLayout.doc, { hops: 1 }, wholeIndex) : null),
    [tabLayout?.doc, wholeGraph, wholeIndex],
  );
  // node positions of a layout tab, by the canvas's node ids (the doc keys them by full qname)
  const layoutNodePositions = useMemo(() => {
    if (!tabLayout || !projection || !wholeIndex) return null;
    const out: Record<string, LayoutPoint> = {};
    for (const [ref, p] of Object.entries(tabLayout.doc.positions)) {
      const id = wholeIndex.nodeOf.get(projection.resolved.get(ref) ?? ref);
      if (id !== undefined) out[id] = p;
    }
    return out;
  }, [tabLayout?.doc.positions, projection, wholeIndex]);
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
  function applyObjects(nextCards: string[], placed: Record<string, LayoutPoint> = {}, keepUnresolved = true) {
    if (!tab || tab.subject.kind !== 'schema' || !tab.subject.schemaCode) return;
    const unresolved = tab.layout && keepUnresolved && projection ? [...projection.missing, ...projection.ambiguous.map((a) => a.ref)] : [];
    const objects = [...new Set([...nextCards, ...unresolved])];
    if (tab.layout) { setShell((s) => setLayoutObjects(s, tab.id, objects, placed)); return; }
    const held = positions[tab.subject.ref] ?? {};
    const seeded: Record<string, LayoutPoint> = {};
    for (const [id, p] of Object.entries(held)) seeded[wholeIndex?.fullQnameOf.get(id) ?? id] = p;
    const layout: TabLayout = {
      doc: { name: seedLayoutName(tab), model: tab.subject.schemaCode as TabLayout['doc']['model'], objects: [], positions: {} },
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

  // a drag on a layout tab → its positions, by full qname (V-28), keeping the missing cards' ones
  function persistLayoutView(tabId: string, change: CanvasViewChange) {
    const l = shellRef.current.tabs.find((t) => t.id === tabId)?.layout;
    if (!l || !wholeIndex) return;
    const next: Record<string, LayoutPoint> = { ...l.doc.positions };
    for (const [id, p] of Object.entries(change.positions)) {
      const fq = wholeIndex.fullQnameOf.get(id);
      if (fq) next[fq] = { x: p.x, y: p.y };
    }
    void layoutStore.write(tabId, { skin: l.doc.skin ?? 'er.crow', mode: 'manual', nodes: next, collapsed: l.doc.collapsed ?? [] });
  }

  const currentGraph = useMemo(() => {
    if (tab?.subject.kind !== 'schema') return null;
    if (!tabLayout) return graphs[tab.subject.ref] ?? null;
    return projection ? { ...projection.graph, edges: [...projection.graph.edges, ...projection.hopEdges.map(hopAsEdge)] } : null;
  }, [tab?.subject.kind, tab?.subject.ref, tabLayout, projection, graphs]);
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
        />
        {tab && (
          <div style={{ display: 'flex', alignItems: 'center', paddingRight: 12 }}>
            <Breadcrumb tab={tab} onDrillTo={(i) => setShell((s) => drillTo(s, s.activeTabId!, i))} />
            {(() => {
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
        <div style={{ flex: 1, minHeight: 0, position: 'relative' }}>
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
              nodePositions={tabLayout ? layoutNodePositions ?? {} : positions[tab.subject.ref] ?? {}}
              canvasKey={tabLayout ? tab.id : tab.subject.ref}
              selectedQname={selected?.qname ?? null}
              selectedEdgeId={inspectedHop?.id ?? null}
              onEdgeSelect={(id) => {
                if (id && id.startsWith('hop:')) { setSelectedHop(id); setDrawerOpen(false); } else setSelectedHop(null);
              }}
              onNodeSelect={(q) => { setSelectedHop(null); void onSelectNode(q); }}
              renderNodeMenu={canEdit && editContext
                ? (qname, close) => editContext.renderNodeMenu({ qname, graphRef: tab.subject.ref, onApplied: () => void refetchGraph(tab.subject.ref), onClose: close })
                : undefined}
              onPersistView={(change) => (tabLayout ? persistLayoutView(tab.id, change) : void persistView(tab.subject.ref, change))}
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
    </div>
  );
}
