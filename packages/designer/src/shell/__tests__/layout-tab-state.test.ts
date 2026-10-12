// SPDX-License-Identifier: Apache-2.0
// VL-P1 T1 — the working layout on the tab (VL contracts V-22, V-28, V-30, V-35): pure reducers.

import { describe, it, expect } from 'vitest';
import {
  emptyShell, openSubject, openLayoutTab, toLayoutTab, setLayoutObjects, setLayoutPositions, markSaved,
  hasUnsavedLocalLayout, layoutRef, activeTab,
} from '../shell-state.js';
import type { ShellState, TabLayout } from '../types.js';

const ITEM = 'hartland.er.entity.item';
const STORE = 'hartland.er.entity.store';
const SALES = 'hartland.er.entity.store_sales';

function layout(over: Partial<TabLayout> = {}, docOver: Partial<TabLayout['doc']> = {}): TabLayout {
  return { doc: { name: 'sales_overview', model: 'er', objects: [ITEM, STORE], positions: {}, ...docOver }, source: 'catalog', dirty: false, ...over };
}

describe('layout tab refs (V-35)', () => {
  it('a catalog layout is addressed by layout:, a local one by file: / new: — never the bare name (A-VL-4)', () => {
    expect(layoutRef('catalog', 'sales_overview')).toBe('layout:sales_overview');
    expect(layoutRef('file', 'sales_overview')).toBe('file:sales_overview');
    expect(layoutRef('new', 'untitled')).toBe('new:untitled');
  });
});

describe('openLayoutTab', () => {
  it('opens a durable tab labelled <model> · <name> and focuses it', () => {
    const s = openLayoutTab(emptyShell, layout());
    const t = activeTab(s)!;
    expect(t.subject).toEqual({ ref: 'layout:sales_overview', kind: 'schema', schemaCode: 'er', label: 'er · sales_overview' });
    expect(t.preview).toBe(false);
    expect(t.layout?.source).toBe('catalog');
  });

  it('re-opening an open catalog layout focuses it and keeps its unsaved edits', () => {
    let s = openLayoutTab(emptyShell, layout());
    s = setLayoutObjects(s, s.activeTabId!, [ITEM]);
    s = openSubject(s, { ref: 'er', kind: 'schema', schemaCode: 'er', label: 'er' }, { preview: false });
    s = openLayoutTab(s, layout());
    expect(s.tabs).toHaveLength(2);
    expect(activeTab(s)!.layout!.doc.objects).toEqual([ITEM]);
    expect(activeTab(s)!.layout!.dirty).toBe(true);
  });

  it('two local files with the same name get two tabs', () => {
    let s = openLayoutTab(emptyShell, layout({ source: 'file', fileName: 'a.yaml' }));
    s = openLayoutTab(s, layout({ source: 'file', fileName: 'a.ttrg' }));
    expect(s.tabs.map((t) => t.id)).toEqual(['file:sales_overview', 'file:sales_overview~2']);
    expect(s.tabs.map((t) => t.subject.ref)).toEqual(['file:sales_overview', 'file:sales_overview']);
  });
});

describe('toLayoutTab (V-22 — the whole schema becomes er · untitled*)', () => {
  it('turns the plain tab in place, keeps it focused, under the new subject', () => {
    let s: ShellState = openSubject(emptyShell, { ref: 'db', kind: 'schema', schemaCode: 'db', label: 'db' }, { preview: false });
    s = openSubject(s, { ref: 'er', kind: 'schema', schemaCode: 'er', label: 'er' }, { preview: false });
    s = toLayoutTab(s, 'er', layout({ source: 'new', dirty: true }, { name: 'untitled' }));
    expect(s.tabs.map((t) => t.id)).toEqual(['db', 'new:untitled']);
    expect(activeTab(s)!.subject.label).toBe('er · untitled');
    expect(activeTab(s)!.layout!.dirty).toBe(true);
  });
});

describe('setLayoutObjects / setLayoutPositions (V-19, V-28)', () => {
  it('replaces the cards, drops the positions of removed cards, adds placed ones, marks dirty', () => {
    let s = openLayoutTab(emptyShell, layout({}, { positions: { [ITEM]: { x: 1, y: 2 }, [STORE]: { x: 3, y: 4 } } }));
    s = setLayoutObjects(s, s.activeTabId!, [ITEM, SALES], { [SALES]: { x: 9, y: 9 } });
    const l = activeTab(s)!.layout!;
    expect(l.doc.objects).toEqual([ITEM, SALES]);
    expect(l.doc.positions).toEqual({ [ITEM]: { x: 1, y: 2 }, [SALES]: { x: 9, y: 9 } });
    expect(l.dirty).toBe(true);
  });

  it('positions (a drag) replace the held ones and mark dirty; skin / collapsed only when given', () => {
    let s = openLayoutTab(emptyShell, layout({}, { skin: 'er.crow' }));
    s = setLayoutPositions(s, s.activeTabId!, { [ITEM]: { x: 5, y: 6 } });
    expect(activeTab(s)!.layout!.doc).toMatchObject({ positions: { [ITEM]: { x: 5, y: 6 } }, skin: 'er.crow' });
    expect(activeTab(s)!.layout!.dirty).toBe(true);
  });

  it('is a no-op on a tab without a layout', () => {
    const s = openSubject(emptyShell, { ref: 'er', kind: 'schema', schemaCode: 'er', label: 'er' });
    expect(setLayoutObjects(s, 'er', [ITEM])).toEqual(s);
  });
});

describe('markSaved (V-32) and the unload guard (V-30)', () => {
  it('after Save as the tab is the clean local file, renamed', () => {
    let s = openLayoutTab(emptyShell, layout({ source: 'new', dirty: true }, { name: 'untitled' }));
    expect(hasUnsavedLocalLayout(s)).toBe(true);
    s = markSaved(s, s.activeTabId!, 'sales_overview', 'sales_overview.yaml');
    const t = activeTab(s)!;
    expect(t.id).toBe('file:sales_overview');
    expect(t.subject.label).toBe('er · sales_overview');
    expect(t.layout).toMatchObject({ source: 'file', fileName: 'sales_overview.yaml', dirty: false });
    expect(t.layout!.doc.name).toBe('sales_overview');
    expect(hasUnsavedLocalLayout(s)).toBe(false);
  });

  it('an edited catalog layout does not arm the guard — a reload re-reads the catalog', () => {
    let s = openLayoutTab(emptyShell, layout());
    s = setLayoutObjects(s, s.activeTabId!, [ITEM]);
    expect(hasUnsavedLocalLayout(s)).toBe(false);
  });
});
