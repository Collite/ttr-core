// SPDX-License-Identifier: Apache-2.0
// transitional (VS-6): retire when tatrman-server Veles is strangled (PL-P7). Renamed from
// `VelesDataSource` under RO-31 — this targets the SV (tatrman-server) JSON read API; the platform
// Veles (WS ttrm/* + bearer) is `VelesTtrmDataSource`. Two Veles servers, one client seam (VS-1/VS-2).
//
// Veles data source (SV-P4·S2·T5): the READ-ONLY catalog view over the Veles JSON
// read API. Veles serves the model as browsable JSON at /model/{index,graph,object,
// search} (services/veles read routes), so this is a thin `fetch` client returning
// the `ttrm/*` read DTOs verbatim — no transport-specific mapping.
//
// Read-only by design (`capabilities.edit = false`): Veles is a served catalog, not a
// modeling repo, so there is no setLayout/mutation surface (contrast
// WsDesignerServerDataSource). Two gaps recorded in the T5 decisions doc:
//   - `.ttrl` layout (gap ③): Veles serves no sidecars, so `getLayout` is intentionally
//     NOT implemented (it is optional on ModelDataSource) — the viewer auto-lays-out.
//   - change notification (gap ⑤): Veles has no server push, so `onModelChanged` POLLS
//     the existing `GET /status` model_version and fires when it changes.

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
import type { TtrmSearchHit } from './ttrm-types.js';
import type { GetGraphResponse } from '@tatrman/lsp';
import { StructuralGraphCache, describeTtrmNode, ttrmToGetGraphResponse, type ObjectDescription } from './structural-graph.js';

export interface VelesDataSourceOptions {
  /** Injected for tests; defaults to global `fetch`. */
  fetchImpl?: typeof fetch;
  /** `onModelChanged` poll interval (ms). Default 15000. */
  pollIntervalMs?: number;
  /** Injected timer for tests; defaults to window.setInterval/clearInterval. */
  setIntervalImpl?: (cb: () => void, ms: number) => ReturnType<typeof setInterval>;
  clearIntervalImpl?: (h: ReturnType<typeof setInterval>) => void;
  /** The language model labels and descriptions are shown in (ttr-core#170); default `en`. */
  lang?: string;
}

export class VelesReadError extends Error {
  constructor(
    readonly status: number,
    readonly path: string,
    message: string,
  ) {
    super(message);
    this.name = 'VelesReadError';
  }
}

export class VelesReadApiDataSource implements ModelDataSource {
  // Deployed read-only catalog: serves db/er browse graphs; no md/cnc, no bindings, no sidecar
  // (auto-layout only → DM-CAP-003). The thinnest backend — honest degradation covers the rest.
  readonly capabilities: DataSourceCapabilities = {
    edit: false,
    modelKinds: ['db', 'er'],
    bindings: false,
    perspectives: false,
    layoutPersist: 'file', // the tab holds the layout; Save as… downloads it (VL V-29)
    graphShape: 'structural', // thin browse graph, no slot bodies (§1.1a) → DM-CAP-002
  } as const;
  private readonly base: string;
  private readonly fetchImpl: typeof fetch;
  private readonly pollIntervalMs: number;
  private readonly setIntervalImpl: (cb: () => void, ms: number) => ReturnType<typeof setInterval>;
  private readonly clearIntervalImpl: (h: ReturnType<typeof setInterval>) => void;
  private readonly served: StructuralGraphCache;

  /** @param base a same-origin path prefix (e.g. `/veles`, or `` for the origin root)
   *  or a full http(s) origin (e.g. `http://localhost:7260`). Endpoint paths are
   *  concatenated onto it; the browser resolves a relative base against its origin. */
  constructor(base: string, opts: VelesDataSourceOptions = {}) {
    this.base = base;
    this.fetchImpl = opts.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.pollIntervalMs = opts.pollIntervalMs ?? 15000;
    this.setIntervalImpl =
      opts.setIntervalImpl ?? ((cb, ms) => setInterval(cb, ms));
    this.clearIntervalImpl = opts.clearIntervalImpl ?? ((h) => clearInterval(h));
    this.served = new StructuralGraphCache(opts.lang);
  }

  private async getJson<T>(path: string): Promise<T> {
    let res: Response;
    try {
      res = await this.fetchImpl(this.base + path, {
        headers: { Accept: 'application/json' },
        credentials: 'include',
      });
    } catch (err) {
      throw new VelesReadError(0, path, `Cannot reach Veles at ${this.base}${path}: ${String(err)}`);
    }
    if (!res.ok) {
      let detail = '';
      try {
        const body = (await res.json()) as { error?: string };
        if (body?.error) detail = `: ${body.error}`;
      } catch {
        /* non-JSON error body */
      }
      throw new VelesReadError(res.status, path, `Veles ${path} → ${res.status}${detail}`);
    }
    return (await res.json()) as T;
  }

  getModelIndex(): Promise<ModelIndex> {
    return this.getJson<ModelIndex>('/model/index');
  }

  getModelGraph(scope?: GraphScope): Promise<ModelGraphPayload> {
    const q = new URLSearchParams();
    if (scope?.schema) q.set('schema', scope.schema);
    if (scope?.package) q.set('package', scope.package);
    const qs = q.toString();
    return this.getJson<ModelGraphPayload>(`/model/graph${qs ? `?${qs}` : ''}`);
  }

  getObject(qname: string): Promise<ObjectDetail> {
    return this.getJson<ObjectDetail>(`/model/object?qname=${encodeURIComponent(qname)}`);
  }

  async listCatalog(): Promise<CatalogListing> {
    // Veles browses by schema (no per-graph listing, no symbols) — synthesize one catalog entry per
    // served schema so the spine opens; cube/concept/program groups stay empty (honest degradation).
    const index = await this.getModelIndex();
    return {
      graphs: index.schemas.map((schema) => ({ uri: schema, name: schema, schema })),
      symbols: [],
    };
  }

  /**
   * The shell read (DM-P2.S3): Veles serves a schema browse graph, mapped to the structural
   * `GetGraphResponse` (§1.1a → `DM-CAP-002`). `ref` is a schema hint (db/er — the deployed reach).
   */
  async getGraph(ref: string): Promise<GetGraphResponse | null> {
    const g = await this.getModelGraph({ schema: ref });
    this.served.remember(ref, g.nodes, g.edges);
    return ttrmToGetGraphResponse(ref, g.nodes, g.edges, [], { lang: this.served.lang });
  }

  /** The whole-schema graph (VL V-21) — on a catalog it is the schema subject's own graph. */
  getSchemaGraph(schema: ModelKind): Promise<GetGraphResponse | null> {
    return this.getGraph(schema);
  }

  /**
   * The inspector read on this backend (ttr-core#170): the object's label, identifier, plural and
   * description in the viewer's language, with its attributes / columns — from the graph already
   * served when it holds the object, else from `/model/object`.
   */
  async describe(qname: string): Promise<ObjectDescription | null> {
    const cached = this.served.describe(qname);
    if (cached) return cached;
    const detail = await this.getObject(qname);
    return describeTtrmNode(detail.object, [], this.served.lang);
  }

  /** The languages the served graphs' labels and descriptions carry (the language picker's options). */
  knownLanguages(): string[] {
    return this.served.languages();
  }

  async search(q: SearchParams): Promise<SearchHit[]> {
    const query = q.query.trim();
    if (!query) return [];
    const params = new URLSearchParams({ query });
    if (q.limit) params.set('limit', String(q.limit));
    const res = await this.getJson<{ hits: TtrmSearchHit[] }>(`/model/search?${params.toString()}`);
    return res.hits;
  }

  /**
   * Poll `GET /status` and fire `cb(modelVersion)` when it changes (Veles has no
   * server push — gap ⑤). The first observed version seeds the baseline silently;
   * only subsequent changes notify. A failed poll is swallowed (best-effort — a
   * transient blip must not tear down the view).
   */
  onModelChanged(cb: (version: string) => void): Disposable {
    let last: string | null = null;
    const tick = () => {
      void this.getJson<{ model_version?: string }>('/status')
        .then((s) => {
          const v = s.model_version ?? '';
          if (last === null) {
            last = v;
          } else if (v !== last) {
            last = v;
            cb(v);
          }
        })
        .catch(() => {
          /* transient — try again next tick */
        });
    };
    const handle = this.setIntervalImpl(tick, this.pollIntervalMs);
    return { dispose: () => this.clearIntervalImpl(handle) };
  }
}
