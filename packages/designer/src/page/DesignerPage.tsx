// SPDX-License-Identifier: Apache-2.0
import { useEffect, useImperativeHandle, useMemo, useRef, useState, type Ref } from 'react';
import { createLspClient } from '../lsp-client.js';
import { Studio } from '../App.js';
import { BffFilesDataSource, SnapshotEditError, type Snapshot } from '../data/bff-files-data-source.js';
import type { ChangedFile } from '../fs/changed-files.js';
import { buildCatalog } from '../shell/catalog.js';
import type { CatalogGroup } from '../shell/types.js';
import { makeViewStateStore } from '../data/view-state-store-factory.js';
import { PropsHost, type PropsHostCallbacks } from '../host/props-host.js';
import { hostAppLocation, toBrowserPath } from '../shell/app-location.js';
import { isBasePath } from '../runtime-config.js';
import { gateExtension, type ExtensionManifest, type LicenseClient } from '../ext/extension-loader.js';
import type { AuthoringLoader } from '../ext/authoring-registry.js';
import { PageContext, type PageEnvironment } from './page-context.js';

export type CollectFiles = () => ChangedFile[];
export interface AuthoringExtension {
  manifest: ExtensionManifest;
  licenseClient?: LicenseClient;
  /** Called only after the core license gate allows the extension. */
  activate: (context: Parameters<AuthoringLoader>[0], grants: string[]) => ReturnType<AuthoringLoader>;
}
export interface DesignerPageProps extends PropsHostCallbacks {
  files: Snapshot | (() => Promise<Snapshot>);
  manifest?: string;
  /** Mark this input as a Save acknowledgement and fence it to the committed SHA. An empty marker counts as none. */
  expectedSha?: string;
  basePath: string;
  lang: 'en' | 'cs';
  theme?: 'light' | 'dark';
  /** Stable identity for this mounted page; changing it starts a new session. */
  workspace?: string;
  /** App path, e.g. /w/hartland/s/md. The host owns browser routing. */
  location?: string;
  collect?: Ref<CollectFiles>;
  extensions?: readonly AuthoringExtension[];
  /** Host-controlled source grant; enable only when this session can persist its edits. */
  sourceAuthoring?: boolean;
}
interface Session { workspace: string; source: BffFilesDataSource; catalog: CatalogGroup[]; sha: string; path: string; installed(): void }

/** A worker-backed Studio surface with instance-scoped routing, config and extensions. */
export function DesignerPage(props: DesignerPageProps) {
  const { workspace = 'repo', basePath, lang, theme = 'light' } = props;
  if (!isBasePath(basePath)) throw new Error('DesignerPage basePath must start and end with /.');
  if (!workspace || workspace.includes('/')) throw new Error('DesignerPage needs a workspace name.');
  const latest = useRef(props);
  const root = useRef<HTMLDivElement>(null);
  latest.current = props;
  const [session, setSession] = useState<Session | null>(null);
  const [failed, setFailed] = useState(false);
  const controller = useRef<{ workspace: string; source: BffFilesDataSource; load(): void } | null>(null);
  const route = useMemo(() => {
    let current = hostAppLocation('/', workspace);
    let lastBadLocation: string | null = null;
    const listeners = new Set<(path: string) => void>();
    return {
      current: () => current,
      replace: (path: string) => {
        path = hostAppLocation(path, workspace);
        if (path !== current) { current = path; latest.current.onLocation?.(path); }
      },
      navigate: (path: string) => {
        let normalized: string;
        try { normalized = hostAppLocation(path, workspace); }
        catch {
          if (lastBadLocation !== path) latest.current.onError?.({ code: 'host/bad-location', message: 'Invalid app location.' });
          lastBadLocation = path;
          return;
        }
        lastBadLocation = null;
        if (normalized === current) return;
        current = normalized;
        for (const listener of listeners) listener(current);
      },
      subscribe: (handler: (path: string) => void) => { listeners.add(handler); return () => { listeners.delete(handler); }; },
    };
  }, [workspace]);
  const authoringLoader = useMemo<AuthoringLoader | null>(() => {
    if (!props.extensions?.length) return null;
    return async context => {
      for (const extension of props.extensions!) {
        if (!root.current || latest.current.extensions !== props.extensions) return null;
        const gate = await gateExtension(extension.manifest, { licenseClient: extension.licenseClient });
        if (!root.current || latest.current.extensions !== props.extensions) return null;
        if (gate.allowed) {
          try {
            const result = await extension.activate(context, gate.grants);
            if (result) return result;
          } catch {
            // Optional authoring must never take model reading down.
            if (root.current && latest.current.extensions === props.extensions) latest.current.onError?.({ code: 'frame/extension-unavailable', message: 'Authoring is unavailable. The model remains readable; retry by reopening editing.' });
          }
        }
      }
      return null;
    };
  }, [props.extensions]);
  const environment = useMemo<PageEnvironment>(() => ({
    root,
    config: { basePath, lang, languages: ['en', 'cs'], veles: null, layoutFormat: 'yaml' },
    theme, authoringLoader,
    location: { ...route, absolute: path => window.location.origin + toBrowserPath(path.replace(/^\/w\//, '/'), basePath, []) },
  }), [basePath, lang, theme, authoringLoader, route]);
  useImperativeHandle(props.collect, () => () => controller.current?.workspace === (latest.current.workspace ?? 'repo') ? controller.current.source.collectChangedFiles() : [], []);
  useEffect(() => {
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const host = new PropsHost(() => latest.current);
    let request: { input: DesignerPageProps; sha?: string };
    const source = new BffFilesDataSource({ api: '/', token: '', scope: { kind: 'served' } }, {
      createClient: createLspClient,
      loadSnapshot: async () => {
        // The queue captures each input. A newer render cannot retarget an older Save.
        const current = request;
        const input = current.input;
        const snapshot = typeof input.files === 'function' ? await input.files() : input.files;
        current.sha ??= snapshot.sha;
        return input.manifest === undefined ? snapshot : { ...snapshot, files: { ...snapshot.files, 'modeler.toml': input.manifest } };
      },
    });
    let diagnosticsVersion = 0;
    const publishDiagnostics = async () => {
      const version = ++diagnosticsVersion;
      try {
        const values = await source.validatedDiagnostics();
        if (!disposed && version === diagnosticsVersion) host.send({ type: 'ttr.frame.diagnostics', diagnostics: values.map(value => ({
          uri: value.uri, severity: value.severity === 1 ? 'error' : value.severity === 2 ? 'warning' : 'info',
          code: String(value.code ?? ''), message: value.message, source: 'worker',
        })) });
      } catch {
        if (!disposed && version === diagnosticsVersion) host.send({ type: 'ttr.frame.diagnostics', diagnostics: [{
          uri: '', severity: 'error', code: 'worker/validation-unavailable',
          message: 'Worker validation did not finish. Check the worker connection before proposing.', source: 'worker',
        }] });
      }
    };
    const subscription = source.onFilesChanged(() => {
      ++diagnosticsVersion; // Invalidate an older validation even before the debounce fires.
      clearTimeout(timer);
      timer = setTimeout(() => {
        host.send({ type: 'ttr.frame.dirty', changed: source.changedFiles().length });
        void publishDiagnostics();
      }, 200);
    });
    let pending = Promise.resolve();
    let renderedSha: string | null = null;
    let readySent = false;
    let committedSha: string | null = null;
    const load = () => {
      const input = latest.current;
      const save = !!input.expectedSha;
      const current = { input, sha: (save ? input.expectedSha : undefined) ?? (typeof input.files === 'function' ? undefined : input.files.sha) };
      pending = pending.then(async () => {
        if (disposed) return;
        ++diagnosticsVersion;
        request = current;
        const changed = await source.refresh({ reason: save ? 'save' : 'refresh',
          deferDisposal: true, expectedSha: save ? input.expectedSha : undefined });
        if (disposed) return;
        const { graphs, symbols } = await source.listCatalog();
        if (disposed) return;
        setFailed(false);
        const sha = source.sha!;
        const installed = () => {
          if (disposed || renderedSha !== sha || source.sha !== sha) return;
          committedSha = sha;
          clearTimeout(timer);
          if (!readySent) {
            readySent = true;
            host.send({ type: 'ttr.frame.ready', sha, manifest: { bindings: { required: source.bindingsRequired } } });
          }
          const changed = source.changedFiles().length;
          host.send({ type: 'ttr.frame.dirty', changed });
          host.send({ type: 'ttr.frame.ack', sha, changed });
          void publishDiagnostics();
        };
        if (!changed && renderedSha === sha && committedSha === sha) {
          installed(); // Same-SHA refresh/retry still acknowledges its input and revalidates.
          return;
        }
        renderedSha = sha;
        setSession({ workspace, source, catalog: buildCatalog(graphs, symbols), sha, path: route.current(), installed });
      }).catch(error => {
        if (disposed) return;
        setFailed(true);
        host.send({ type: 'ttr.frame.error', code: error instanceof SnapshotEditError ? error.code : 'host/load-failed',
          message: error instanceof SnapshotEditError ? error.message : 'Could not load the model snapshot.',
          ...(current.sha ? { sha: current.sha } : {}) });
        // A refused/failed replacement leaves this worker installed. Its validation is still current.
        if (committedSha !== null && source.sha === committedSha) void publishDiagnostics();
      });
    };
    controller.current = { workspace, source, load };
    setSession(null);
    setFailed(false);
    load();
    return () => {
      disposed = true;
      clearTimeout(timer);
      subscription.dispose(); host.dispose(); source.dispose();
      if (controller.current?.source === source) controller.current = null;
    };
  }, [workspace, route]);
  const firstInput = useRef(props.files);
  const firstManifest = useRef(props.manifest);
  useEffect(() => {
    if (props.files === firstInput.current && props.manifest === firstManifest.current) return;
    firstInput.current = props.files; firstManifest.current = props.manifest;
    controller.current?.load();
  }, [props.files, props.manifest]);
  useEffect(() => { if (props.location !== undefined) route.navigate(props.location); }, [route, props.location]);
  useEffect(() => { session?.source.disposeRetired(); session?.installed(); }, [session]);
  const viewState = useMemo(() => makeViewStateStore('file', { kind: 'file' }), [workspace]);
  return <PageContext.Provider value={environment}>
    <div ref={root} className="ttr-designer" data-testid="designer-page" data-theme={theme} lang={lang} tabIndex={-1}
      onClick={event => {
        if (event.defaultPrevented || !(event.target instanceof Element)) return;
        // Non-focusable canvas clicks should keep subsequent shortcuts in this page.
        // Let buttons, fields, links and other focusable descendants retain their focus.
        const control = event.target.closest('a[href],button,input,select,textarea,summary,[tabindex],[contenteditable]:not([contenteditable="false"])');
        if (!control || control === event.currentTarget) event.currentTarget.focus({ preventScroll: true });
      }}
      style={{ height: '100%', width: '100%', minHeight: 0, colorScheme: theme }}>
      {session && session.workspace === workspace && session.source === controller.current?.source
        ? <Studio key={`${workspace}:${session.sha}`} dataSource={session.source} viewState={viewState} catalog={session.catalog}
          files={[]} workspace={workspace} initialAppPath={session.path} canAuthorSource={props.sourceAuthoring ?? false} />
        : <div className="hosted-status" role="status">{failed ? 'Could not load the model.' : 'Loading Studio…'}</div>}
    </div>
  </PageContext.Provider>;
}
