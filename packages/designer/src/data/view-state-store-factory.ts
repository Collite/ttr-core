// SPDX-License-Identifier: Apache-2.0
// Select the ViewStateStore impl from the active backend's capability descriptor (DM-P2.S1 /
// contracts §3). The shell asks the factory for a store given `source.capabilities.layoutPersist`
// and the backend-appropriate IO; it never new's a store directly, so adding a backend is one arm.

import type { ViewStateStore } from '@tatrman/canvas-core';
import type { DataSourceCapabilities } from './model-data-source.js';
import {
  WorkerLayoutStore,
  WsSidecarStore,
  LayoutDocumentStore,
  type LayoutDocIO,
  type LayoutIO,
  type PrefsIO,
  type TtrmLayoutIO,
} from './view-state-store.js';

/** The IO a store needs, tagged by mechanism so the factory can pick without a cast. */
export type ViewStateStoreIO =
  | { kind: 'in-file'; layout: LayoutIO; prefs: PrefsIO }
  | { kind: 'sidecar'; layout: TtrmLayoutIO }
  | { kind: 'file'; layout?: LayoutDocIO };

export interface ViewStateStoreFactoryOptions {
  defaultSkin?: string;
}

/**
 * Build the store for `persist`. The `io.kind` must match `persist`; a mismatch is a wiring bug
 * (thrown, not silently degraded) so the shell can't, say, hand a sidecar IO to an in-file backend.
 */
export function makeViewStateStore(
  persist: DataSourceCapabilities['layoutPersist'],
  io: ViewStateStoreIO,
  opts?: ViewStateStoreFactoryOptions,
): ViewStateStore {
  if (persist !== io.kind) {
    throw new Error(`ViewStateStore IO kind '${io.kind}' does not match layoutPersist '${persist}'`);
  }
  switch (io.kind) {
    case 'in-file':
      return new WorkerLayoutStore(io.layout, io.prefs, opts);
    case 'sidecar':
      return new WsSidecarStore(io.layout, opts);
    case 'file':
      return new LayoutDocumentStore(io.layout, opts);
  }
}
