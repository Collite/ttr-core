// SPDX-License-Identifier: Apache-2.0
// The Studio Viewer entry (Designer Merge, DM-P2.S4). Selects a backend (P2, never sniffed),
// builds the ModelDataSource + its ViewStateStore, loads the catalog, and mounts the DS ShellFrame.
// The OPEN build ships NO edit code: `editContext` is undefined everywhere here, so the shell is
// edit-absent (FO-21); the FO-P0.S4 license loader supplies an authoring context on the commercial
// build (DM-P3). Cytoscape and the flat SV designer are retired (DM-⚑a) — React Flow is the engine.

import { useEffect, useMemo, useRef, useState } from 'react';
import type { DisplayMode } from '@tatrman/lsp';
import type { ViewStateStore } from '@tatrman/canvas-core';
import { ShellFrame, type ActiveSubject } from './shell/ShellFrame';
import { buildCatalog } from './shell/catalog';
import type { CatalogGroup } from './shell/types';
import type { ModelDataSource } from './data/model-data-source';
import { WorkerLspDataSource } from './data/worker-lsp-data-source';
import { WsDesignerServerDataSource } from './data/ws-designer-server-data-source';
import { VelesReadApiDataSource } from './data/veles-read-api-data-source';
import { mountDesignerPanels } from './ext/panels-host';
import { VelesTtrmDataSource } from './data/veles-ttrm-data-source';
import { makeViewStateStore, type ViewStateStoreIO } from './data/view-state-store-factory';
import type { PrefsRecord } from './data/view-state-store';
import { selectBackend, serverWorkspaceName, BackendSelectionError, type BackendSelection } from './data/select-data-source';
import { ManifestProgramSource } from './model/ttrp/manifest-program-source';
import type { ProcessingGraphSource } from './model/processing-source';
import { useAuthoringContext } from './ext/use-authoring-context.js';
import { createLspClient, type LspClient } from './lsp-client';
import { getRuntimeConfig } from './runtime-config';
import { loadDemoFiles } from './fs/demo-loader';
import { loadProjectViaFileSystemAccessAPI, type ProjectFiles } from './fs/file-system';
import { applyWorkspaceEdit } from './lsp/apply-workspace-edit';
import { ErrorBoundary } from './components/ErrorBoundary';
import { ToastContainer, makeToast, type ToastMessage } from './Toast';

function isModelFile(relativePath: string): boolean {
  return relativePath.endsWith('.ttrm') || relativePath.endsWith('.ttrg');
}

function resolveBackend(): BackendSelection | { kind: 'error'; message: string } {
  try {
    return selectBackend(window.location.search, getRuntimeConfig());
  } catch (err) {
    if (err instanceof BackendSelectionError) return { kind: 'error', message: err.message };
    throw err;
  }
}

/** The server-backed selections (everything except worker). */
type ServerSelection = Extract<BackendSelection, { kind: 'ws' | 'veles' }>;

/** A fatal screen. The title names WHAT failed: a bad backend selection, a failed load, or an
 *  unreachable backend are different problems and must not share one heading (ttr-core#112). */
function BackendErrorScreen({ message, title = 'Invalid backend selection' }: { message: string; title?: string }) {
  return (
    <div className="flex flex-col items-center justify-center h-screen gap-4 p-12 bg-gray-50">
      <div className="bg-white border border-red-300 rounded-xl shadow-lg p-8 max-w-md text-center">
        <h2 data-testid="error-title" className="text-xl font-bold text-red-700 mb-2">{title}</h2>
        <p className="text-sm text-slate-600 break-words">{message}</p>
      </div>
    </div>
  );
}

/** A ready Studio: the DS shell over a resolved data source + view-state store. Edit-absent in the
 *  OPEN build (no authoring loader registered → `editContext` undefined, FO-21); the COMMERCIAL build
 *  registers a loader that resolves a `ShellEditContext` here (FO-P0.S4). */
function Studio({
  dataSource, viewState, catalog, files, workspace, processingSource,
}: {
  dataSource: ModelDataSource;
  viewState?: ViewStateStore;
  catalog: CatalogGroup[];
  files: string[];
  workspace: string;
  processingSource?: ProcessingGraphSource;
}) {
  const editContext = useAuthoringContext(dataSource);
  const [displayMode, setDisplayMode] = useState<DisplayMode>('just-names');
  const [active, setActive] = useState<ActiveSubject | null>(null);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const addToast = (message: string, kind: 'error' | 'info' = 'error') => setToasts((p) => [...p, makeToast(message, kind)]);
  const dismissToast = (id: string) => setToasts((p) => p.filter((t) => t.id !== id));

  // apply a saved subject's display mode once, when a different subject becomes active.
  const displayModeRef = useRef<string | null>(null);
  const onActiveChange = (a: ActiveSubject | null) => {
    setActive(a);
    if (a?.viewportDisplayMode && a.ref !== displayModeRef.current) {
      displayModeRef.current = a.ref;
      setDisplayMode(a.viewportDisplayMode);
    }
  };

  return (
    <div className="flex flex-col h-screen bg-gray-50">
      <header className="flex items-center gap-4 px-4 py-2 border-b border-slate-200 bg-white" data-testid="studio-header">
        <span className="font-semibold text-slate-800">{active?.label ?? workspace}</span>
        <div className="ml-auto flex items-center gap-2 text-xs">
          <span className="uppercase tracking-wide text-slate-400">Detail</span>
          <select
            data-testid="display-mode"
            value={displayMode}
            onChange={(e) => setDisplayMode(e.target.value as DisplayMode)}
            className="border border-slate-300 rounded px-2 py-1"
          >
            <option value="just-names">Names</option>
            <option value="with-types">With types</option>
          </select>
        </div>
      </header>
      <ErrorBoundary label="shell" resetKey={workspace}>
        <ShellFrame
          dataSource={dataSource}
          workspace={workspace}
          catalog={catalog}
          files={files}
          displayMode={displayMode}
          viewState={viewState}
          onActiveChange={onActiveChange}
          onError={addToast}
          editContext={editContext ?? undefined}
          processingSource={processingSource}
        />
      </ErrorBoundary>
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />
    </div>
  );
}

/** A loading / landing shell while a backend is being established. */
function Splash({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center h-screen gap-6 p-12 bg-gray-50">
      <div className="bg-white border border-slate-300 rounded-xl shadow-lg p-10 max-w-md text-center">
        <h2 className="text-2xl font-bold text-gray-800 mb-3">Studio Viewer</h2>
        {children}
      </div>
    </div>
  );
}

/** Worker backend: local LSP over a Web Worker; load a project/demo, then mount the shell. */
function WorkerStudio({ demo }: { demo: string | null }) {
  const [ready, setReady] = useState<{ dataSource: ModelDataSource; viewState: ViewStateStore; catalog: CatalogGroup[]; files: string[]; workspace: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(demo !== null);
  const [clientReady, setClientReady] = useState(false);
  const clientRef = useRef<LspClient | null>(null);
  const diagnosticsRef = useRef<Array<{ uri: string; messages: string[] }>>([]);
  const docTextCache = useRef<Map<string, string>>(new Map());
  const prefsMap = useRef<Map<string, PrefsRecord>>(new Map());
  const startedRef = useRef(false);

  const getText = (uri: string) => docTextCache.current.get(uri);
  const openDoc = async (uri: string, content: string) => {
    docTextCache.current.set(uri, content);
    await clientRef.current!.openDocument(uri, content);
  };

  const buildStudio = async (client: LspClient, projectRoot: string, workspace: string, files: string[]) => {
    const dataSource = new WorkerLspDataSource(client, { projectRoot });
    const io: ViewStateStoreIO = {
      kind: 'in-file',
      layout: {
        getLayout: (key) => client.getLayout(key),
        setLayout: async (key, layout) => {
          const edit = await client.setLayout(key, layout);
          if (edit) await applyWorkspaceEdit(edit, getText, openDoc);
        },
      },
      prefs: {
        get: (key) => prefsMap.current.get(key),
        set: (key, v) => prefsMap.current.set(key, { ...prefsMap.current.get(key), ...v }),
      },
    };
    const viewState = makeViewStateStore('in-file', io);
    const { graphs, symbols } = await dataSource.listCatalog();
    setReady({ dataSource, viewState, catalog: buildCatalog(graphs, symbols), files, workspace });
  };

  // establish the worker LSP once. `clientReady` must be state, not just the ref:
  // assigning a ref triggers no re-render, so the demo effect below would never
  // re-run once the client arrived and the app would sit on "Loading…" forever.
  useEffect(() => {
    let cancelled = false;
    createLspClient().then((client) => {
      if (cancelled) { client.dispose(); return; }
      // Model diagnostics are NOT app errors. They are normal output, and during a
      // demo/folder load they are also transient: `openDoc` fires for every file
      // concurrently, so a file that references a symbol from a not-yet-opened file
      // publishes an "unresolved reference" that resolves itself moments later.
      // Routing them into `setError` blanked the whole app on the first one (under
      // the heading "Invalid backend selection", no less). `error` stays reserved for
      // backend-selection and load failures; no surface renders these yet.
      client.onDiagnostics((uri, messages) => {
        if (!messages.length) return;
        diagnosticsRef.current.push({ uri, messages });
        console.warn('[lsp] diagnostics', uri, messages);
      });
      clientRef.current = client;
      setClientReady(true);
    });
    return () => { cancelled = true; clientRef.current?.dispose(); clientRef.current = null; setClientReady(false); };
  }, []);

  // auto-load a ?demo= project, once the client exists
  useEffect(() => {
    if (!demo || startedRef.current) return;
    const client = clientRef.current;
    if (!client) return; // wait for the client effect
    startedRef.current = true;
    (async () => {
      try {
        const files = await loadDemoFiles(demo);
        await client.setProjectRoot(`file:///${demo}`);
        const modelPaths = Array.from(files.files.keys()).filter(isModelFile);
        await Promise.all(modelPaths.map((p) => openDoc(`file:///${demo}/${p}`, files.files.get(p)!)));
        await buildStudio(client, `file:///${demo}`, demo, modelPaths);
      } catch (e) {
        setError(`Failed to load demo: ${e}`);
      } finally {
        setLoading(false);
      }
    })();
    // `clientReady` is the trigger: it flips to true in the same tick the ref is
    // assigned, so this fires exactly once the client is usable. Depending on the
    // ref alone would never re-run it.
  }, [clientReady, demo]);

  const loadFolder = async (files: ProjectFiles) => {
    const client = clientRef.current;
    if (!client) return;
    setLoading(true);
    try {
      await client.setProjectRoot(`file:///${files.rootName}`);
      const modelPaths = Array.from(files.files.keys()).filter(isModelFile);
      await Promise.all(modelPaths.map((p) => openDoc(`file:///${files.rootName}/${p}`, files.files.get(p)!)));
      await buildStudio(client, `file:///${files.rootName}`, files.rootName, modelPaths);
    } catch (e) {
      setError(`Failed to load project: ${e}`);
    } finally {
      setLoading(false);
    }
  };

  if (error && !ready) return <BackendErrorScreen title="Could not load the project" message={error} />;
  if (ready) return <Studio {...ready} />;
  if (loading) return <Splash><p className="text-gray-500">Loading…</p></Splash>;
  return (
    <Splash>
      <p className="text-gray-500 mb-8">Open a local project folder or explore a demo.</p>
      <div className="flex flex-col gap-3">
        <button
          onClick={async () => { const f = await loadProjectViaFileSystemAccessAPI(); if (f) await loadFolder(f); }}
          className="px-6 py-3 bg-sky-500 text-white rounded-lg hover:bg-sky-600 font-medium transition-colors"
        >
          Load Project Folder
        </button>
        <button
          onClick={() => { const p = new URLSearchParams(window.location.search); p.set('demo', 'v1.1-mini'); window.location.search = p.toString(); }}
          className="px-6 py-3 bg-slate-100 text-gray-700 border border-slate-300 rounded-lg hover:bg-slate-200 font-medium transition-colors"
        >
          Open Demo (v1.1-mini)
        </button>
      </div>
    </Splash>
  );
}

/** Server backends (WS / Veles): read-only deployed catalogs; connect, list, mount. */
function ServerStudio(props: ServerSelection) {
  const [ready, setReady] = useState<{ dataSource: ModelDataSource; viewState: ViewStateStore; catalog: CatalogGroup[] } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let disposed = false;
    // The concrete source, captured so cleanup can tear down its live WS (a data class holds a socket
    // open for the page lifetime otherwise; under StrictMode's dev double-mount that leaks a connection).
    let created: { dispose?: () => void } | null = null;
    (async () => {
      try {
        let dataSource: ModelDataSource;
        let io: ViewStateStoreIO;
        if (props.kind === 'ws') {
          const ws = new WsDesignerServerDataSource(props.origin);
          created = ws;
          await ws.connect();
          dataSource = ws;
          io = { kind: 'sidecar', layout: { getLayout: (uri) => ws.getLayout(uri), setLayout: async (uri, canvases) => { await ws.setLayout(uri, canvases); } } };
        } else if (props.transport === 'ttrm') {
          // Platform Veles: WS ttrm/* + bearer on the handshake (VS-2). No sidecar layout.
          const v = new VelesTtrmDataSource(props.origin, { token: props.token ?? undefined });
          created = v;
          await v.connect();
          dataSource = v;
          io = { kind: 'none' };
        } else {
          const r = new VelesReadApiDataSource(props.base);
          created = r as unknown as { dispose?: () => void };
          dataSource = r;
          io = { kind: 'none' };
        }
        const { graphs, symbols } = await dataSource.listCatalog();
        if (disposed) {
          created?.dispose?.(); // unmounted mid-connect — don't leak the socket we just opened
          return;
        }
        setReady({ dataSource, viewState: makeViewStateStore(io.kind, io), catalog: buildCatalog(graphs, symbols) });
      } catch (e) {
        if (!disposed) setError(`Failed to reach the ${props.kind} backend: ${e}`);
      }
    })();
    return () => { disposed = true; created?.dispose?.(); };
  }, []);

  const label = serverWorkspaceName(props);

  // TTR-P program-graph panel (S9.T2): when `?manifest=<ref>` is present on a Veles backend, render the
  // static bundle manifest fetched from Veles's stored-manifest endpoint (`GET /v1/manifests/{ref}`).
  const processingSource = useMemo<ProcessingGraphSource | undefined>(() => {
    if (props.kind !== 'veles') return undefined;
    const ref = new URLSearchParams(window.location.search).get('manifest');
    if (!ref) return undefined;
    // The manifest endpoint is HTTP on the Veles host — for the ttrm (wss) backend, derive the http base.
    const httpBase = props.transport === 'ttrm' ? props.origin.replace(/^ws/i, 'http') : props.base;
    const token = props.transport === 'ttrm' ? props.token ?? undefined : undefined;
    return new ManifestProgramSource(httpBase, { token, defaultRef: ref });
  }, []);

  // §10 Designer extensions (PL-P2.S8): on a Veles backend, load the advertised platform extensions
  // (runs + column-lineage panels) and mount their panels into a side dock. A non-veles backend or an
  // empty roster leaves the dock empty (VS-5). Isolated from the Studio shell — a failed load never
  // blocks the editor.
  const panelsHostRef = useRef<HTMLDivElement>(null);
  // Narrow the backend union to its veles httpBase + bearer BEFORE the effect, so the deps are plain
  // primitives (R3-e): a refreshed token / changed origin then re-mounts instead of serving the stale one.
  const veles = props.kind === 'veles' ? props : null;
  const httpBase = veles ? (veles.transport === 'ttrm' ? veles.origin.replace(/^ws/i, 'http') : veles.base) : null;
  const bearer = veles && veles.transport === 'ttrm' ? veles.token ?? null : null;
  useEffect(() => {
    const host = panelsHostRef.current;
    if (httpBase === null || !ready || !host) return;
    const ctx = {
      dataSource: ready.dataSource,
      backend: { kind: 'veles' as const, baseUrl: httpBase, capabilities: ready.dataSource.capabilities },
      auth: { token: async () => bearer },
    };
    let mounted: { dispose: () => void } | null = null;
    let cancelled = false;
    void mountDesignerPanels(host, ctx).then((m) => {
      if (cancelled) m.dispose();
      else mounted = m;
    });
    return () => {
      cancelled = true;
      mounted?.dispose();
    };
    // The per-mount container in panels-host makes a cancelled mount's dispose remove only its own dock, so
    // a re-mount (on a token/origin change) can't blank the live one.
  }, [ready, httpBase, bearer]);

  if (error) return <BackendErrorScreen title={props.kind === 'ws' ? 'Could not reach the designer server' : 'Could not reach the catalog'} message={error} />;
  if (!ready) return <Splash><p className="text-gray-500">Connecting to the {props.kind} backend…</p></Splash>;
  return (
    <>
      <Studio dataSource={ready.dataSource} viewState={ready.viewState} catalog={ready.catalog} files={[]} workspace={label} processingSource={processingSource} />
      <aside ref={panelsHostRef} className="ttr-designer-panels" aria-label="Designer extension panels" />
    </>
  );
}

function App() {
  const backend = useMemo(resolveBackend, []);
  if (backend.kind === 'error') return <BackendErrorScreen message={backend.message} />;
  if (backend.kind === 'ws' || backend.kind === 'veles') return <ServerStudio {...backend} />;
  return <WorkerStudio demo={backend.demo} />;
}

export default App;
