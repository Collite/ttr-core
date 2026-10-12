// SPDX-License-Identifier: Apache-2.0
// VelesTtrmDataSource (PL-P1.S8, RO-31/VS-2): the READ-ONLY adapter over the PLATFORM Veles
// (tatrman-platform, strangler ②) — WS `ttrm/*` JSON-RPC on `/v1/ttrm`, H-2 bearer on the handshake.
// Selected by `?veles=ws(s)://…` (VS-3). Distinct from `VelesReadApiDataSource` (the transitional SV
// JSON read API over http(s), VS-6) and from `WsDesignerServerDataSource` (loopback modeling server).
//
// The platform Veles copied the designer-server `ttrm/*` READ handlers verbatim (S7.T2), but NOT the
// layout/getGraph/listGraphs registrars — so this adapter reads via `ttrm/{getModelIndex,getModelGraph,
// getObject,search}` (WS, push `ttrm/modelChanged`) and SYNTHESIZES `getGraph`/`listCatalog` from those
// (as the read-api does). No sidecar layout (`layoutPersist:'none'`). `capabilities.edit = false`.

import type {
  ModelDataSource,
  DataSourceCapabilities,
  ModelIndex,
  ModelGraphPayload,
  ObjectDetail,
  SearchHit,
  SearchParams,
  GraphScope,
  Disposable,
  CatalogListing,
  ModelKind,
} from './model-data-source.js';
import type { TtrmSearchHit, TtrmStatus, LayoutSummary, LayoutsResponse, ResolvedLayout } from './ttrm-types.js';
import type { GetGraphResponse } from '@tatrman/lsp';
import { StructuralGraphCache, describeTtrmNode, ttrmToGetGraphResponse, type ObjectDescription } from './structural-graph.js';
import {
  JsonRpcWsClient,
  TtrmRpcError,
  type JsonRpcWsClientOptions,
} from './json-rpc-ws-client.js';

/** JSON-RPC code the ttrm server returns when no model is loaded yet (contracts §4). */
const RPC_MODEL_NOT_LOADED = -32000;

export const VELES_TTRM_PROTOCOL_VERSION = 1;

/**
 * Typed NotLoaded signal (VS / S8.T1): a `-32000` from the server surfaces as this — the shell renders
 * a "model not loaded yet" state and retries on `ttrm/modelChanged`, rather than treating it as an
 * opaque transport failure. It carries the last-seen `modelVersion` (null while unloaded).
 */
export class ModelNotLoadedError extends Error {
  constructor(readonly modelVersion: string | null = null) {
    super('veles: model not loaded');
    this.name = 'ModelNotLoadedError';
  }
}

export class ProtocolVersionMismatchError extends Error {
  constructor(
    readonly expected: number,
    readonly actual: number,
  ) {
    super(`veles ttrm protocolVersion ${actual} is not supported (expected ${expected})`);
    this.name = 'ProtocolVersionMismatchError';
  }
}

export interface VelesTtrmDataSourceOptions {
  /** Bearer token for the H-2 ingress; static in v1. // PL-P1: IdP flow post-v1 ⚑ */
  token?: string;
  /** Passed through to the JSON-RPC WS client (test seam / timeout). `bearerToken` is set from `token`. */
  wsClientOptions?: Omit<JsonRpcWsClientOptions, 'bearerToken'>;
  /** The language model labels and descriptions are shown in (ttr-core#170); default `en`. */
  lang?: string;
  /** The layout routes are plain GETs on the same host (VL V-36); injected for tests, defaults to `fetch`. */
  fetchImpl?: typeof fetch;
}

export class VelesTtrmDataSource implements ModelDataSource {
  // Same read surface as the loopback WS server (shared handlers), read-only, structural graph, and
  // NO sidecar layout (the platform Veles serves no layout methods) → honest degradation covers it.
  readonly capabilities: DataSourceCapabilities = {
    edit: false,
    modelKinds: ['db', 'er', 'cnc'],
    bindings: false,
    perspectives: false,
    layoutPersist: 'file', // the tab holds the layout; Save as… downloads it (VL V-29)
    graphShape: 'structural',
  } as const;

  private readonly client: JsonRpcWsClient;
  private status: TtrmStatus | null = null;
  private readonly served: StructuralGraphCache;
  private readonly httpOrigin: string;
  private readonly token: string | undefined;
  private readonly fetchImpl: typeof fetch;

  /** @param origin the platform Veles WS origin, e.g. `wss://veles.example`; `/v1/ttrm` is appended. */
  constructor(origin: string, opts: VelesTtrmDataSourceOptions = {}) {
    const url = origin.replace(/\/$/, '') + '/v1/ttrm';
    this.client = new JsonRpcWsClient(url, { ...opts.wsClientOptions, bearerToken: opts.token });
    this.served = new StructuralGraphCache(opts.lang);
    this.httpOrigin = origin.replace(/\/$/, '').replace(/^ws/i, 'http');
    this.token = opts.token;
    this.fetchImpl = opts.fetchImpl ?? ((input, init) => globalThis.fetch(input, init));
  }

  /** A layout route (VL V-36): a GET on the same host over http(s), the bearer when there is one. */
  private async getLayoutJson<T>(path: string): Promise<T> {
    const res = await this.fetchImpl(this.httpOrigin + path, {
      headers: { Accept: 'application/json', ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}) },
    });
    if (!res.ok) throw new Error(`veles ${path} → ${res.status}`);
    return (await res.json()) as T;
  }

  /** The layouts committed next to the model (VL V-36 — `GET /model/layouts`). */
  async listLayouts(): Promise<LayoutSummary[]> {
    return (await this.getLayoutJson<LayoutsResponse>('/model/layouts')).layouts;
  }

  /** One layout, resolved by the catalog (VL V-36 — `GET /model/layout?name=`). */
  getLayoutDoc(name: string): Promise<ResolvedLayout> {
    return this.getLayoutJson<ResolvedLayout>(`/model/layout?name=${encodeURIComponent(name)}`);
  }

  /** Connect (bearer on the handshake) and verify the ttrm handshake. */
  async connect(): Promise<TtrmStatus> {
    await this.client.connect();
    const status = await this.client.request<TtrmStatus>('ttrm/getStatus');
    if (status.protocolVersion !== VELES_TTRM_PROTOCOL_VERSION) {
      this.client.close();
      throw new ProtocolVersionMismatchError(VELES_TTRM_PROTOCOL_VERSION, status.protocolVersion);
    }
    this.status = status;
    return status;
  }

  getStatus(): TtrmStatus | null {
    return this.status;
  }

  getModelIndex(): Promise<ModelIndex> {
    return this.mapNotLoaded(() => this.client.request<ModelIndex>('ttrm/getModelIndex'));
  }

  getModelGraph(scope?: GraphScope): Promise<ModelGraphPayload> {
    const params: Record<string, unknown> = {};
    const scopeObj: Record<string, unknown> = {};
    if (scope?.package) scopeObj.package = scope.package;
    if (scope?.schema) scopeObj.schema = scope.schema;
    if (Object.keys(scopeObj).length > 0) params.scope = scopeObj;
    if (scope?.edgeTypes?.length) params.edgeTypes = scope.edgeTypes;
    return this.mapNotLoaded(() => this.client.request<ModelGraphPayload>('ttrm/getModelGraph', params));
  }

  getObject(qname: string): Promise<ObjectDetail> {
    return this.mapNotLoaded(() => this.client.request<ObjectDetail>('ttrm/getObject', { qname }));
  }

  async search(q: SearchParams): Promise<SearchHit[]> {
    const query = q.query.trim();
    if (!query) return [];
    const res = await this.mapNotLoaded(() =>
      this.client.request<{ hits: TtrmSearchHit[] }>('ttrm/search', {
        query,
        ...(q.algorithm ? { algorithm: q.algorithm } : {}),
        ...(q.limit ? { limit: q.limit } : {}),
      }),
    );
    return res.hits;
  }

  // The platform Veles has no ttrm/getGraph or ttrm/listGraphs — synthesize from getModelGraph/Index,
  // the read-api mapping (§1.1a structural graph).
  async getGraph(ref: string): Promise<GetGraphResponse | null> {
    const g = await this.getModelGraph({ schema: ref });
    this.served.remember(ref, g.nodes, g.edges);
    return ttrmToGetGraphResponse(ref, g.nodes, g.edges, [], { lang: this.served.lang });
  }

  /** The whole-schema graph (VL V-21) — on a catalog it is the schema subject's own graph. */
  getSchemaGraph(schema: ModelKind): Promise<GetGraphResponse | null> {
    return this.getGraph(schema);
  }

  /** The inspector read (ttr-core#170) — see `VelesReadApiDataSource.describe`. */
  async describe(qname: string): Promise<ObjectDescription | null> {
    const cached = this.served.describe(qname);
    if (cached) return cached;
    const detail = await this.getObject(qname);
    return describeTtrmNode(detail.object, [], this.served.lang);
  }

  knownLanguages(): string[] {
    return this.served.languages();
  }

  async listCatalog(): Promise<CatalogListing> {
    const [index, layouts] = await Promise.all([this.getModelIndex(), this.listLayouts().catch(() => [] as LayoutSummary[])]);
    return {
      graphs: [
        ...index.schemas.map((schema) => ({ uri: schema, name: schema, schema })),
        ...layouts.map((l) => ({ uri: l.name, name: l.name, schema: l.schema, layout: { missingCount: l.missingCount } })),
      ],
      symbols: [],
    };
  }

  /** Server push: the platform Veles broadcasts `ttrm/modelChanged` on a canon swap. */
  onModelChanged(cb: (version: string) => void): Disposable {
    const unsub = this.client.onNotification('ttrm/modelChanged', (params) => {
      const v = (params as { modelVersion?: string } | undefined)?.modelVersion;
      if (typeof v === 'string') {
        if (this.status) this.status = { ...this.status, modelVersion: v };
        cb(v);
      }
    });
    return { dispose: unsub };
  }

  dispose(): void {
    this.client.close();
  }

  /** Map a `-32000` (model-not-loaded) RPC error to the typed [ModelNotLoadedError]; re-throw the rest. */
  private async mapNotLoaded<T>(fn: () => Promise<T>): Promise<T> {
    try {
      return await fn();
    } catch (err) {
      if (err instanceof TtrmRpcError && err.code === RPC_MODEL_NOT_LOADED) {
        throw new ModelNotLoadedError(this.status?.modelVersion ?? null);
      }
      throw err;
    }
  }
}
