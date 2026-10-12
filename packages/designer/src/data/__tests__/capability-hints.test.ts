// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import type { DataSourceCapabilities } from '../model-data-source.js';
import {
  perspectiveHint,
  modelKindHint,
  structuralGraphHint,
  layoutPersistHint,
  backendFailureHint,
} from '../capability-hints.js';

const WORKER: DataSourceCapabilities = {
  edit: true, modelKinds: ['db', 'er', 'md', 'cnc'], bindings: true, perspectives: true, layoutPersist: 'in-file', graphShape: 'rich',
};
const WS: DataSourceCapabilities = {
  edit: false, modelKinds: ['db', 'er', 'cnc'], bindings: false, perspectives: false, layoutPersist: 'sidecar', graphShape: 'structural',
};
const VELES: DataSourceCapabilities = {
  edit: false, modelKinds: ['db', 'er'], bindings: false, perspectives: false, layoutPersist: 'file', graphShape: 'structural',
};

describe('DM-CAP-* capability hints', () => {
  it('DM-CAP-001: perspectives ok on Worker, hinted on WS/Veles', () => {
    expect(perspectiveHint(WORKER)).toBeNull();
    expect(perspectiveHint(WS)?.code).toBe('DM-CAP-001');
    expect(perspectiveHint(VELES)?.code).toBe('DM-CAP-001');
    expect(perspectiveHint(WS)?.severity).toBe('hint');
  });

  it('DM-CAP-002: md served on Worker, hinted on WS/Veles; cnc served on WS not Veles', () => {
    expect(modelKindHint(WORKER, 'md')).toBeNull();
    expect(modelKindHint(WS, 'md')?.code).toBe('DM-CAP-002');
    expect(modelKindHint(VELES, 'md')?.code).toBe('DM-CAP-002');
    expect(modelKindHint(WS, 'cnc')).toBeNull();
    expect(modelKindHint(VELES, 'cnc')?.code).toBe('DM-CAP-002');
    expect(modelKindHint(WORKER, 'db')).toBeNull();
  });

  it('DM-CAP-002 (shape axis, §1.1a): rich on Worker, structural-only on WS/Veles for a served kind', () => {
    // Worker is rich → no structural hint even though it serves the kind.
    expect(structuralGraphHint(WORKER, 'db')).toBeNull();
    // WS serves db/er/cnc but structurally → hinted with DM-CAP-002.
    expect(structuralGraphHint(WS, 'db')?.code).toBe('DM-CAP-002');
    expect(structuralGraphHint(WS, 'cnc')?.severity).toBe('hint');
    // A kind WS doesn't serve at all is modelKindHint's job, not the shape hint's.
    expect(structuralGraphHint(WS, 'md')).toBeNull();
    // Veles: db/er served structurally.
    expect(structuralGraphHint(VELES, 'er')?.code).toBe('DM-CAP-002');
  });

  it('DM-CAP-003: layout saved on Worker (in-file) + WS (sidecar); on Veles the tab holds it, Save as… downloads it', () => {
    expect(layoutPersistHint(WORKER)).toBeNull();
    expect(layoutPersistHint(WS)).toBeNull();
    expect(layoutPersistHint(VELES)?.code).toBe('DM-CAP-003');
    expect(layoutPersistHint(VELES)?.severity).toBe('info');
    // VL V-29: the catalog stores no layouts, but every backend can download one
    expect(layoutPersistHint(VELES)?.message).toBe('This backend does not store layouts — Save as… downloads them.');
  });

  it('DM-CAP-004: a failed advertised capability surfaces, never swallowed', () => {
    const h = backendFailureHint('getBindings', 'timeout');
    expect(h.code).toBe('DM-CAP-004');
    expect(h.severity).toBe('warning');
    expect(h.message).toContain('getBindings');
    expect(h.message).toContain('timeout');
  });
});
