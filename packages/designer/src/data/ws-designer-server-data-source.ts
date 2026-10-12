// SPDX-License-Identifier: Apache-2.0
// WS data source: speaks the `ttrm/*` protocol to ttr-designer-server (M3.1).
//
// FO-21 (FO-P0.S2.T4): this is the Studio Viewer's WS data source — READ +
// view-persistence only. `capabilities.edit` is `false`: the model-mutating
// methods (`addObjectToGraph`/`removeObjectFromGraph`/`createGraph`) moved to
// `tatrman-platform`'s authoring extension, and the edit routes that backed them
// move to the split-out `ttr-designer-edit-server` Kotlin module (FO-P0.S2.T5).
// What STAYS here is the read surface (`getModelIndex`/`getModelGraph`/`getObject`/
// `search`/`listGraphs`/`getGraph`) plus `getLayout`/`setLayout` — the latter is
// view-persistence (FO-31), read-half, so it stays even though it writes the
// `.ttrl` sidecar. On connect it verifies the handshake `protocolVersion === 1`
// (contracts §4); a mismatch is a hard error surfaced to the user.

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
  LayoutPayload,
  CatalogListing,
  ModelKind,
} from './model-data-source.js';
import type {
  TtrmStatus,
  TtrmSearchHit,
  TtrmLayoutCanvas,
  TtrmSetLayoutResult,
  TtrmGraphMetadata,
  TtrmGetGraphResponse,
} from './ttrm-types.js';
import type { GetGraphResponse } from '@tatrman/lsp';
import { JsonRpcWsClient, type JsonRpcWsClientOptions } from './json-rpc-ws-client.js';
import { ttrmToGetGraphResponse } from './structural-graph.js';

export const TTRM_PROTOCOL_VERSION = 1;

export class ProtocolVersionMismatchError extends Error {
  constructor(
    readonly expected: number,
    readonly actual: number,
  ) {
    super(`ttr-designer-server protocolVersion ${actual} is not supported (expected ${expected})`);
    this.name = 'ProtocolVersionMismatchError';
  }
}

export class WsDesignerServerDataSource implements ModelDataSource {
  // DM-P0.S2: the Kotlin server knows {db,er,binding,query,cnc} but returns the row-less
  // dependency-graph shape (ttrm-adapter), not the rich DS slot data (contracts §1.1a) — so kinds
  // it serves render structural-only (DM-CAP-002). No getBindings endpoint → perspectives off.
  // View-persistence writes the .ttrl sidecar (FO-31, read-half). edit:false (edit → platform).
  readonly capabilities: DataSourceCapabilities = {
    edit: false,
    modelKinds: ['db', 'er', 'cnc'],
    bindings: false,
    perspectives: false,
    layoutPersist: 'sidecar',
    graphShape: 'structural', // ttrm-adapter returns the row-less dependency graph (§1.1a) → DM-CAP-002
  } as const;
  private readonly client: JsonRpcWsClient;
  private status: TtrmStatus | null = null;

  /** @param origin WS origin, e.g. `ws://127.0.0.1:7270`; `/ttrm` is appended. */
  constructor(origin: string, opts: JsonRpcWsClientOptions = {}) {
    const url = origin.replace(/\/$/, '') + '/ttrm';
    this.client = new JsonRpcWsClient(url, opts);
  }

  /** Connect and verify the handshake. Rejects on protocolVersion mismatch. */
  async connect(): Promise<TtrmStatus> {
    await this.client.connect();
    const status = await this.client.request<TtrmStatus>('ttrm/getStatus');
    if (status.protocolVersion !== TTRM_PROTOCOL_VERSION) {
      this.client.close();
      throw new ProtocolVersionMismatchError(TTRM_PROTOCOL_VERSION, status.protocolVersion);
    }
    this.status = status;
    return status;
  }

  getStatus(): TtrmStatus | null {
    return this.status;
  }

  getModelIndex(): Promise<ModelIndex> {
    return this.client.request<ModelIndex>('ttrm/getModelIndex');
  }

  getModelGraph(scope?: GraphScope): Promise<ModelGraphPayload> {
    const params: Record<string, unknown> = {};
    const scopeObj: Record<string, unknown> = {};
    if (scope?.package) scopeObj.package = scope.package;
    if (scope?.schema) scopeObj.schema = scope.schema;
    if (Object.keys(scopeObj).length > 0) params.scope = scopeObj;
    if (scope?.edgeTypes?.length) params.edgeTypes = scope.edgeTypes;
    return this.client.request<ModelGraphPayload>('ttrm/getModelGraph', params);
  }

  getObject(qname: string): Promise<ObjectDetail> {
    return this.client.request<ObjectDetail>('ttrm/getObject', { qname });
  }

  async search(q: SearchParams): Promise<SearchHit[]> {
    const res = await this.client.request<{ hits: TtrmSearchHit[] }>('ttrm/search', {
      query: q.query,
      ...(q.algorithm ? { algorithm: q.algorithm } : {}),
      ...(q.limit ? { limit: q.limit } : {}),
    });
    return res.hits;
  }

  getLayout(uri: string): Promise<LayoutPayload> {
    return this.client.request<LayoutPayload>('ttrm/getLayout', { uri });
  }

  setLayout(uri: string, canvases: TtrmLayoutCanvas[]): Promise<TtrmSetLayoutResult> {
    return this.client.request<TtrmSetLayoutResult>('ttrm/setLayout', { uri, canvases });
  }

  async listGraphs(): Promise<TtrmGraphMetadata[]> {
    const res = await this.client.request<{ graphs: TtrmGraphMetadata[] }>('ttrm/listGraphs');
    return res.graphs;
  }

  async listCatalog(): Promise<CatalogListing> {
    // Graphs only — the WS server exposes no symbol listing yet (cube/concept/program groups are
    // empty on this backend; honest degradation, not a defect).
    const graphs = await this.listGraphs();
    return { graphs: graphs.map((g) => ({ uri: g.uri, name: g.name, schema: g.schema })), symbols: [] };
  }

  /** The raw `ttrm/getGraph` structural payload (row-less dependency graph). */
  getGraphRaw(uri: string): Promise<TtrmGetGraphResponse> {
    return this.client.request<TtrmGetGraphResponse>('ttrm/getGraph', { uri });
  }

  /** The whole-schema graph (VL V-21): every object of `schema`, mapped like `getGraph`. */
  async getSchemaGraph(schema: ModelKind): Promise<GetGraphResponse | null> {
    const g = await this.getModelGraph({ schema });
    return ttrmToGetGraphResponse(schema, g.nodes, g.edges);
  }

  /** The shell read (DM-P2.S3): map the structural payload up to `GetGraphResponse` (§1.1a). */
  async getGraph(ref: string): Promise<GetGraphResponse | null> {
    const raw = await this.getGraphRaw(ref);
    return ttrmToGetGraphResponse(raw.schema, raw.nodes, raw.edges, raw.missingObjects);
  }

  onModelChanged(cb: (version: string) => void): Disposable {
    const unsub = this.client.onNotification('ttrm/modelChanged', (params) => {
      const v = (params as { modelVersion?: string } | undefined)?.modelVersion;
      if (typeof v === 'string') cb(v);
    });
    return { dispose: unsub };
  }

  dispose(): void {
    this.client.close();
  }
}
