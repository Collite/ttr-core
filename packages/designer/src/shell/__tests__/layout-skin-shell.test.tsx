// SPDX-License-Identifier: Apache-2.0
// VL-P1 — the skin lives in the tab's layout (V-28; ttr-core#180 #3): an opened layout's skin reaches the
// canvas (one this viewer lacks shows substituted, and Save as… keeps it); the notation picker's choice
// lands in the layout and marks it dirty; a drag leaves the skin alone; a plain subject's pick travels
// into New layout.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { ShellFrame } from '../ShellFrame.js';
import { fakeVeles, SCHEMA_CATALOG } from './layout-harness.js';

vi.mock('../../canvas/Kernel.js', async () => (await import('./layout-harness.js')).kernelMock);

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

const yaml = (skin?: string) => [
  '# Tatrman layout — https://docs.tatrman.org/model/layout-files/ (format 1)',
  'layout: 1',
  'name: picked',
  'model: er',
  'objects:',
  '  - hartland.er.entity.item',
  '  - hartland.er.entity.store_sales',
  ...(skin ? [`skin: ${skin}`] : []),
  '',
].join('\n');

type Saved = { fileName: string; content: string };
function mount(picked: File[] = []) {
  const saved: Saved[] = [];
  render(
    <ShellFrame dataSource={fakeVeles().source} workspace="w" catalog={SCHEMA_CATALOG} files={[]} displayMode="just-names"
      pickFiles={async () => picked} downloadFile={async (fileName, content) => { saved.push({ fileName, content }); }} />,
  );
  return saved;
}
async function openFile(skin?: string) {
  const saved = mount([new File([yaml(skin)], 'picked.yaml')]);
  fireEvent.click(screen.getByTestId('layout-open'));
  await screen.findByTestId('mock-kernel');
  return saved;
}
async function saveYaml(saved: Saved[], name?: string) {
  const before = saved.length;
  fireEvent.click(screen.getByTestId('layout-save-as'));
  const dialog = await screen.findByTestId('save-as-dialog');
  if (name) fireEvent.change(within(dialog).getByTestId('save-as-name'), { target: { value: name } });
  fireEvent.click(within(dialog).getByTestId('save-as-format-yaml'));
  fireEvent.click(within(dialog).getByTestId('save-as-save'));
  await waitFor(() => expect(saved).toHaveLength(before + 1));
  return saved[before].content;
}
const persisted = () => new Promise((r) => setTimeout(r, 450));

describe('the skin lives in the layout (V-28)', () => {
  it("an opened layout's skin reaches the canvas — one this viewer lacks shows substituted, and Save as… keeps it", async () => {
    const saved = await openFile('er.chen');
    expect(screen.getByTestId('truth-chip')).toHaveTextContent('skin=er.crow');
    expect(screen.getByTestId('skin-fallback')).toHaveTextContent('substituted (er.chen not available here)');
    expect(await saveYaml(saved)).toMatch(/^skin: er\.chen$/m);
  });

  it("the picker's choice lands in the layout: the tab turns dirty and Save as… writes it; a drag leaves the skin alone", async () => {
    const saved = await openFile();
    fireEvent.click(screen.getByTestId('mock-drag'));
    await persisted();
    await waitFor(() => expect(screen.getByTestId('tab-dirty')).toBeInTheDocument());
    expect(await saveYaml(saved)).not.toMatch(/^skin:/m); // no default skin written behind the user's back
    await waitFor(() => expect(screen.queryByTestId('tab-dirty')).toBeNull());
    fireEvent.change(screen.getByTestId('skin-picker'), { target: { value: 'er.crow' } });
    await waitFor(() => expect(screen.getByTestId('tab-dirty')).toBeInTheDocument());
    expect(await saveYaml(saved)).toMatch(/^skin: er\.crow$/m);
  });

  it("a plain subject's pick travels into New layout", async () => {
    const saved = mount();
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.getAttribute('data-ref') === 'er')!);
    await screen.findByTestId('mock-kernel');
    fireEvent.change(screen.getByTestId('skin-picker'), { target: { value: 'er.crow' } });
    fireEvent.click(screen.getByTestId('layout-new'));
    await waitFor(() => expect(screen.getAllByTestId('subject-tab')).toHaveLength(2));
    expect(await saveYaml(saved, 'whole_er')).toMatch(/^skin: er\.crow$/m);
  });
});

it('hosted skin URLs and Copy retain a chosen skin, and selecting the default removes it (#291)', async () => {
  window.history.replaceState(null, '', '/?backend=host#/w/w/s/er?skin=er.chen');
  mount();
  await screen.findByTestId('mock-kernel');
  await waitFor(() => expect(window.location.hash).toContain('skin=er.chen'));
  expect(screen.getByTestId('copy-federation-link')).toHaveAttribute('data-federation-url', expect.stringContaining('skin=er.chen'));
  fireEvent.change(screen.getByTestId('skin-picker'), { target: { value: 'er.crow' } });
  await waitFor(() => expect(window.location.hash).not.toContain('skin='));
  expect(screen.getByTestId('copy-federation-link').getAttribute('data-federation-url')).not.toContain('skin=');
});
