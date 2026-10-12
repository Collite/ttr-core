// SPDX-License-Identifier: Apache-2.0
// VL-P1 T10 — the truth chip's layout part and DM-CAP-003 (VL contracts V-29): one statement per source.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import type { DataSourceCapabilities } from '../../data/model-data-source.js';
import { layoutChip } from '../layout-chip.js';
import { ShellFrame } from '../ShellFrame.js';
import type { SubjectTab, TabLayout } from '../types.js';
import { fakeVeles, SCHEMA_CATALOG } from './layout-harness.js';

vi.mock('../../canvas/Kernel.js', async () => (await import('./layout-harness.js')).kernelMock);
afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

const caps = (layoutPersist: DataSourceCapabilities['layoutPersist']): DataSourceCapabilities =>
  ({ edit: false, modelKinds: ['db', 'er'], bindings: false, perspectives: false, layoutPersist, graphShape: 'structural' });
const tab = (layout?: Partial<TabLayout>): SubjectTab => ({
  id: 't', subject: { ref: 'er', kind: 'schema', schemaCode: 'er', label: 'er' }, drillPath: [], preview: false,
  ...(layout ? { layout: { doc: { name: 'sales', model: 'er', objects: [], positions: {} }, source: 'new', dirty: true, ...layout } } : {}),
});

describe('layoutChip (V-29)', () => {
  it('names where the layout lives, per source', () => {
    expect(layoutChip(tab({ source: 'new' }), caps('file'))?.text).toBe('layout: file · unsaved');
    expect(layoutChip(tab({ source: 'file', fileName: 'sales.yaml', dirty: false }), caps('file'))?.text).toBe('layout: file · sales.yaml');
    expect(layoutChip(tab({ source: 'file', fileName: 'sales.ttrg', dirty: true }), caps('file'))?.text).toBe('layout: file · sales.ttrg · unsaved');
    expect(layoutChip(tab({ source: 'catalog', dirty: false }), caps('file'))?.text).toBe('layout: catalog · sales');
    expect(layoutChip(tab({ source: 'catalog', dirty: true }), caps('file'))?.text).toBe('layout: catalog · sales (edited, unsaved)');
  });

  it('a plain subject: session only on a catalog (DM-CAP-003 as the tooltip); nothing new on the worker / WS', () => {
    expect(layoutChip(tab(), caps('file'))).toEqual({
      text: 'layout: session only', title: 'This backend does not store layouts — Save as… downloads them. (DM-CAP-003)',
    });
    expect(layoutChip(tab(), caps('in-file'))).toBeNull();
    expect(layoutChip(tab(), caps('sidecar'))).toBeNull();
    expect(layoutChip({ ...tab(), subject: { ref: 'p', kind: 'program', label: 'program p' } }, caps('file'))).toBeNull();
  });
});

describe('the chip in the shell', () => {
  it('follows the tab: whole schema → new → saved', async () => {
    render(<ShellFrame dataSource={fakeVeles().source} workspace="veles" catalog={SCHEMA_CATALOG} files={[]} displayMode="just-names" downloadFile={async () => {}} />);
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await waitFor(() => expect(screen.getByTestId('truth-chip-layout')).toHaveTextContent('layout: session only'));
    fireEvent.click(screen.getByTestId('objects-none'));
    await waitFor(() => expect(screen.getByTestId('truth-chip-layout')).toHaveTextContent('layout: file · unsaved'));
    fireEvent.click(screen.getByTestId('layout-save-as'));
    const dialog = await screen.findByTestId('save-as-dialog');
    fireEvent.change(within(dialog).getByTestId('save-as-name'), { target: { value: 'empty_view' } });
    fireEvent.click(within(dialog).getByTestId('save-as-format-yaml'));
    fireEvent.click(within(dialog).getByTestId('save-as-save'));
    await waitFor(() => expect(screen.getByTestId('truth-chip-layout')).toHaveTextContent('layout: file · empty_view.yaml'));
  });
});
