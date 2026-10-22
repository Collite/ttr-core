// SPDX-License-Identifier: Apache-2.0
import type { RefusalRef, SynthResult } from './types.js';

export type RefusalCode =
  | 'entity-needs-binding' | 'entity-exists' | 'source-unknown' | 'query-has-parameters'
  | 'attribute-needs-binding' | 'attribute-exists' | 'column-unknown' | 'entity-is-query-backed'
  | 'relation-exists' | 'entity-unknown' | 'fk-needs-binding'
  | 'node-unknown' | 'text-unparseable' | 'would-orphan' | 'term-exists' | 'target-unknown'
  | 'invalid-args' | 'ambiguous-reference';

/** Internal unwind only: all staged changes are discarded by the pure public entry point. */
export class Refusal extends Error {
  constructor(readonly code: RefusalCode, message: string, readonly refs?: RefusalRef[]) { super(message); }
  result(): SynthResult { return { ok: false, code: this.code, message: this.message, ...(this.refs ? { refs: this.refs } : {}) }; }
}
