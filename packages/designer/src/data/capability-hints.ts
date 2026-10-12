// SPDX-License-Identifier: Apache-2.0
// DM-CAP-* honest-degradation helpers (Designer Merge, DM-P1 / contracts §1.2, §6).
//
// The shell renders capabilities, never assumes them. When the active backend cannot serve a
// read the user reached for, these helpers return a disabled-with-hint signal (never a dead
// control, never silent absence — the DS "nothing invisible" P-3 invariant, extended to the
// backend matrix). The shell (DM-P2) turns a non-null hint into a visible, disabled affordance.

import type { DataSourceCapabilities, ModelKind } from './model-data-source.js';

export type CapCode = 'DM-CAP-001' | 'DM-CAP-002' | 'DM-CAP-003' | 'DM-CAP-004';

export interface CapabilityHint {
  readonly code: CapCode;
  readonly severity: 'hint' | 'info' | 'warning';
  readonly message: string;
}

/** DM-CAP-001 — a perspective (binding/lineage) was reached for on a backend that can't generate it. */
export function perspectiveHint(caps: DataSourceCapabilities): CapabilityHint | null {
  if (caps.perspectives) return null;
  return {
    code: 'DM-CAP-001',
    severity: 'hint',
    message: caps.bindings
      ? 'Perspectives are unavailable on this backend.'
      : 'Perspectives need a bindings-capable backend (open a project locally to use them).',
  };
}

/**
 * DM-CAP-002, first case (§1.1a) — a model kind the active backend does not serve at all.
 * The second case (a kind served *structurally* — no slot bodies) is `structuralGraphHint` below.
 */
export function modelKindHint(caps: DataSourceCapabilities, kind: ModelKind): CapabilityHint | null {
  if (caps.modelKinds.includes(kind)) return null;
  return {
    code: 'DM-CAP-002',
    severity: 'hint',
    message: `This backend serves ${caps.modelKinds.join('/')} — not ${kind}.`,
  };
}

/**
 * DM-CAP-002, second case (§1.1a — the graph-SHAPE axis) — a kind the backend *does* serve, but only
 * as a row-less structural graph (WS `ttrm-adapter`, Veles browse): the skin's structural marks
 * render, slot bodies (rows/PK-FK, measures, cnc props) are absent. Honest partial render, not a
 * failure. Returns null for a `'rich'` backend (Worker) or a kind not served at all (that's
 * `modelKindHint`'s DM-CAP-002 first case).
 */
export function structuralGraphHint(caps: DataSourceCapabilities, kind: ModelKind): CapabilityHint | null {
  if (caps.graphShape !== 'structural') return null;
  if (!caps.modelKinds.includes(kind)) return null;
  return {
    code: 'DM-CAP-002',
    severity: 'hint',
    message: `${kind} renders structural-only on this backend — no slot data (open a project locally for full detail).`,
  };
}

/** DM-CAP-003 — the active backend does not persist layout (auto-layout only); say so, never silently. */
export function layoutPersistHint(caps: DataSourceCapabilities): CapabilityHint | null {
  if (caps.layoutPersist !== 'file') return null;
  return {
    code: 'DM-CAP-003',
    severity: 'info',
    message: 'Layout is auto-arranged on this backend and is not saved.',
  };
}

/** DM-CAP-004 — a capability was advertised but the call failed; surface it, never swallow it. */
export function backendFailureHint(capability: string, detail: string): CapabilityHint {
  return {
    code: 'DM-CAP-004',
    severity: 'warning',
    message: `${capability} was expected on this backend but failed: ${detail}`,
  };
}
