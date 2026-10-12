// SPDX-License-Identifier: Apache-2.0
// The truth chip's layout part (VL contracts V-29): where the picture on the canvas lives — a catalog
// layout as served, a local file (and whether it has unsaved edits), or nowhere yet. A plain subject on a
// backend that stores no layouts says so (DM-CAP-003); on the worker / WS backends it says nothing new.

import type { DataSourceCapabilities } from '../data/model-data-source.js';
import { layoutPersistHint } from '../data/capability-hints.js';
import type { SubjectTab } from './types.js';

export interface LayoutChip {
  text: string;
  /** the longer explanation, as the chip's tooltip */
  title?: string;
}

export function layoutChip(tab: SubjectTab | null, caps: DataSourceCapabilities): LayoutChip | null {
  if (!tab || tab.subject.kind !== 'schema') return null;
  const l = tab.layout;
  if (l) {
    switch (l.source) {
      case 'new':
        return { text: 'layout: file · unsaved', title: 'A new layout — Save as… downloads it.' };
      case 'file':
        return { text: `layout: file · ${l.fileName ?? `${l.doc.name}.yaml`}${l.dirty ? ' · unsaved' : ''}`, title: l.dirty ? 'Edited since it was opened or saved — Save as… downloads it.' : undefined };
      case 'catalog':
        return l.dirty
          ? { text: `layout: catalog · ${l.doc.name} (edited, unsaved)`, title: 'Your edits live in this tab only; a reload shows the committed layout. Save as… downloads them.' }
          : { text: `layout: catalog · ${l.doc.name}` };
    }
  }
  const hint = layoutPersistHint(caps);
  return hint ? { text: 'layout: session only', title: `${hint.message} (${hint.code})` } : null;
}
