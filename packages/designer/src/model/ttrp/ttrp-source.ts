// SPDX-License-Identifier: Apache-2.0
// DM-P4.S4: the LIVE processing/run sources over the ttrp/* backend (:9257), behind the SAME
// ProcessingGraphSource / RunSource interfaces the fixtures implement — the merged ProcessingCanvas
// never sees the transport (contracts §5). The fixtures are kept for dev + component tests; these are
// the deployed path.
//
// **Finding A (honest no-display).** tatrman's ttrp backend has no streaming and no Arrow: `ttrp/run`
// is a single request/response returning `{ runId, exitCode, out: string[] }`. The DS RunSource wants
// `run(): AsyncIterable<RunEvent>` + `readDisplayResult(): ArrowTable`. So `TtrpServerRunSource` wraps
// the single-shot call as a two-event walk (`running` → `done`|`failed` by exitCode) and — because
// there is no Arrow display sink server-side — emits `done` WITHOUT a `sinkRef`, so the canvas shows
// the done badge but opens no result drawer; `readDisplayResult` throws (never reached). This is the
// DS honest-degradation posture, not a defect: a real live Arrow display sink is a Kotlin-side effort
// (server-side watch-row), and the fixture path carries the rich hero result meanwhile.
import type { ProcessingGraph } from '@tatrman/canvas-core';
import type { GetGraphResult } from './types.js';
import { ttrpToProcessingGraph } from './to-processing-graph.js';
import type { ProcessingGraphSource } from '../processing-source.js';
import type { ArrowTable, RunEvent, RunSource } from '../run-source.js';

/** The narrow read+run surface these sources need (the lifted `TtrpLspClient` satisfies it; a fake
 *  satisfies it in tests). */
export interface TtrpReadRunClient {
  getGraph(uri?: string): Promise<GetGraphResult>;
  run(uri?: string): Promise<{ runId: string; exitCode: number; out: string[] }>;
}

/** Live ProcessingGraphSource: `ttrp/getGraph` → the DS ProcessingGraph via the shape adapter. */
export class TtrpServerProcessingSource implements ProcessingGraphSource {
  constructor(private readonly client: TtrpReadRunClient, private readonly uri?: string) {}

  async getRecipeSummary(_programRef: string): Promise<import('./types.js').RecipeSummary | null> {
    return (await this.client.getGraph(this.uri)).recipe ?? null;
  }

  async getProgramGraph(_programRef: string): Promise<ProcessingGraph> {
    const result = await this.client.getGraph(this.uri);
    return ttrpToProcessingGraph(result, 'program');
  }

  async getContainerGraph(containerRef: string): Promise<ProcessingGraph> {
    const result = await this.client.getGraph(this.uri);
    const key = containerRef.split('.').pop() ?? containerRef;
    return ttrpToProcessingGraph(result, key);
  }
}

/** Live RunSource over `ttrp/run` (single-shot). Honest no-display per Finding A. */
export class TtrpServerRunSource implements RunSource {
  readonly available = true;
  constructor(private readonly client: TtrpReadRunClient, private readonly uri?: string) {}

  async *run(_programRef: string): AsyncIterable<RunEvent> {
    yield { status: 'running' };
    try {
      const result = await this.client.run(this.uri);
      // no sinkRef: the live backend yields no Arrow display sink (`out` is string[]) — the canvas
      // lands on the done badge with no drawer (honest no-display).
      yield result.exitCode === 0
        ? { status: 'done' }
        : { status: 'failed', diagnostics: { errorCount: 1, warnCount: 0 } };
    } catch {
      yield { status: 'failed', diagnostics: { errorCount: 1, warnCount: 0 } };
    }
  }

  async readDisplayResult(_sinkRef: string): Promise<ArrowTable> {
    // never reached (run emits no sinkRef); explicit so misuse is loud rather than fabricating a table.
    throw new Error('no-display: the live ttrp/run backend produces no Arrow display result (Finding A)');
  }
}
