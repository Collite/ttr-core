// SPDX-License-Identifier: Apache-2.0
// Shell contracts (DS-P2 / contracts §6). Subject tabs, the catalog spine, drill state, the
// URL state, and the command registry share these shapes. A "subject" is a catalog object
// (a schema graph, a cube, a concept, a program) — NEVER a file (A-2 β), and there is no
// Model/Process mode (A-4 α): a tab's subject decides its canvas kind.

import type { SkinId, SchemaCode } from '@tatrman/canvas-core';
import type { LayoutDocument } from '../layout/layout-document.js';
export type { SkinId } from '@tatrman/canvas-core'; // re-exported for shell consumers

export type SubjectKind = 'schema' | 'cube' | 'concept' | 'program';

export interface Subject {
  /** canonical id — a .ttrg graph uri (schema subjects) or an object qname (cube/concept/program) */
  ref: string;
  kind: SubjectKind;
  schemaCode?: SchemaCode; // for schema/cube/concept canvases
  /** kind-prefixed display label per the prototype: 'er · sales', 'cube Sales', 'program monthly_sales' */
  label: string;
}

export interface DrillSegment {
  id: string; // container id / node qname drilled into
  label: string;
}

export interface SubjectTab {
  id: string; // stable tab id (subject.ref [+ perspective])
  subject: Subject;
  skin?: SkinId; // per-canvas skin choice (E-4)
  perspective?: 'binding' | 'lineage'; // in-tab perspective switch (A-2 β) — stubs in P2
  drillPath: DrillSegment[]; // in-tab drill breadcrumb (P-2, both faces)
  preview: boolean; // S-7 preview-tab discipline: transient italic until pinned
  /** the working layout (VL contracts §5) — present on a layout tab; absent on a plain subject. */
  layout?: TabLayout;
}

/** Where a tab's layout came from (VL V-29, V-35): the catalog, a local file, or nowhere yet. */
export type LayoutSource = 'catalog' | 'file' | 'new';

/**
 * The working layout of a tab (VL V-22, V-28, V-30). `doc.objects` holds the resolved cards as full
 * qnames and the unresolved refs verbatim (missing / ambiguous stay until removed, V-15); `doc.positions`
 * is keyed the same way and is non-empty only in manual mode (V-2). Lives in tab memory only (⚑VL-10).
 */
export interface TabLayout {
  doc: LayoutDocument;
  source: LayoutSource;
  /** the file it was opened from or saved as (`sales.yaml`) — source `file` only. */
  fileName?: string;
  dirty: boolean;
}

export interface ShellState {
  tabs: SubjectTab[];
  activeTabId: string | null;
}

// ---- catalog (search-first, grouped by kind) ----
export interface CatalogItem {
  ref: string;
  qname: string;
  kind: SubjectKind;
  label: string;
  schemaCode?: SchemaCode;
  packageName?: string | null;
  /** a layout the catalog serves (VL V-34): opened through `getLayoutDoc(name)`, its ref is the name */
  layout?: { name: string; missingCount: number };
}

export interface CatalogGroup {
  kind: SubjectKind;
  label: string; // 'Schemas', 'Cubes', 'Concepts', 'Programs'
  items: CatalogItem[];
}

// ---- URL state (contracts §6; parse(format(state)) = state) ----
export type UrlState =
  | { kind: 'none'; workspace?: string }
  | { kind: 'subject'; workspace: string; subjectRef: string; skin?: SkinId }
  | { kind: 'perspective'; workspace: string; perspective: 'binding' | 'lineage'; params: Record<string, string> }
  | { kind: 'unknownSubject'; workspace: string; subjectRef: string }; // DS-SHELL-001

// ---- command registry (⌘K parity with the toolbar, E-4/D-6) ----
export interface Command {
  id: string;
  title: string;
  group?: string;
  /** the toolbar action id this command mirrors — parity is asserted structurally */
  toolbarAction?: string;
  /** listed but not runnable here (`layout.saveAs` on a program tab — VL V-33) */
  disabled?: boolean;
  run: () => void;
}
