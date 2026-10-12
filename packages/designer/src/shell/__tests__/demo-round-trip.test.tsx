// SPDX-License-Identifier: Apache-2.0
// VL-P1 T11 — the demo round trip (VL plan, P1 definition of done): the worker backend over the REAL
// language server (in process) with the `v1.1-mini` demo — open `er · all_er`, show 3 of its 7 entities
// (two of them joined only through a hidden one), see exactly the expected real + hop edges, drag a card,
// Save as YAML, Open the saved file, and get the same document back, byte for byte; the truth chip states
// where the layout lives at every step.

import { describe, it, expect, vi, afterEach, beforeAll, afterAll } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PassThrough } from 'node:stream';
import * as lsp from 'vscode-languageserver/node';
import { createServerConnection } from '@tatrman/lsp/server';
import type { LayoutFile } from '@tatrman/lsp';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { ShellFrame } from '../ShellFrame.js';
import { buildCatalog } from '../catalog.js';
import { lspClientOver } from '../../lsp-connection.js';
import type { LspClient } from '../../lsp-client.js';
import { WorkerLspDataSource } from '../../data/worker-lsp-data-source.js';
import { makeViewStateStore } from '../../data/view-state-store-factory.js';
import { readYaml } from '../../layout/layout-yaml.js';
import { attr } from './layout-harness.js';

vi.mock('../../canvas/Kernel.js', async () => (await import('./layout-harness.js')).kernelMock);

const DEMO = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', '..', 'samples', 'v1.1-mini');
const ROOT = 'file:///v1.1-mini';
const fq = (name: string) => `billing.invoicing.er.entity.${name}`;

function demoFiles(dir = DEMO): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? demoFiles(p) : /\.(ttrm|ttrg)$/.test(f) ? [p] : [];
  });
}

let client: LspClient;
let close: () => void;

beforeAll(async () => {
  // the real server, in process: a paired connection instead of the browser worker
  const toServer = new PassThrough({ objectMode: true });
  const toClient = new PassThrough({ objectMode: true });
  const serverConn = lsp.createConnection(new lsp.StreamMessageReader(toServer), new lsp.StreamMessageWriter(toClient));
  const clientConn = lsp.createConnection(new lsp.StreamMessageReader(toClient), new lsp.StreamMessageWriter(toServer));
  createServerConnection(serverConn, { layoutStore: new Map<string, LayoutFile>() });
  serverConn.listen();
  clientConn.listen();
  close = () => { clientConn.dispose(); serverConn.dispose(); };
  client = await lspClientOver(
    { sendRequest: (m, p) => clientConn.sendRequest(m, p), sendNotification: (m, p) => clientConn.sendNotification(m, p), onNotification: (m, h) => clientConn.onNotification(m, h) },
    { transportKind: 'node', dispose: close },
  );
  await client.setProjectRoot(ROOT);
  for (const file of demoFiles()) await client.openDocument(`${ROOT}/${relative(DEMO, file)}`, readFileSync(file, 'utf8'));
});
afterAll(() => close());
afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

const row = (q: string) => screen.getAllByTestId('objects-row').find((r) => r.getAttribute('data-qname') === q)!;
const chip = () => screen.getByTestId('truth-chip-layout').textContent;
const tabs = () => screen.getAllByTestId('subject-tab').map((t) => t.textContent);

describe('the v1.1-mini demo, end to end (VL-P1 DoD)', () => {
  it('check 3 of 7 → real + hop edges → drag → Save as YAML → Open → the same document, byte for byte', async () => {
    const dataSource = new WorkerLspDataSource(client, { projectRoot: ROOT });
    const viewState = makeViewStateStore('in-file', {
      kind: 'in-file',
      layout: { getLayout: (k) => client.getLayout(k), setLayout: async (k, l) => { await client.setLayout(k, l); } },
      prefs: { get: () => undefined, set: () => {} },
    });
    const { graphs, symbols } = await dataSource.listCatalog();
    const saved: Array<{ fileName: string; content: string; mimeType: string }> = [];
    let toOpen: File[] = [];
    render(
      <ShellFrame dataSource={dataSource} viewState={viewState} workspace="v1.1-mini" catalog={buildCatalog(graphs, symbols)} files={[]}
        displayMode="just-names" pickFiles={async () => toOpen}
        downloadFile={async (fileName, content, mimeType) => { saved.push({ fileName, content, mimeType }); }} />,
    );

    // the project graph `er · all_er` — every entity of the schema in the panel, all shown
    fireEvent.click(screen.getAllByTestId('catalog-item').find((b) => b.textContent === 'er · all_er')!);
    await screen.findByTestId('objects-panel');
    await waitFor(() => expect(screen.getByTestId('objects-count')).toHaveTextContent('7 / 7'));
    expect(screen.getAllByTestId('objects-group').map((g) => g.getAttribute('data-pkg'))).toEqual(['billing.invoicing', 'billing.products']);
    expect(screen.queryByTestId('truth-chip-layout')).toBeNull(); // the worker's chip is unchanged on a plain graph

    // show 3 of the 7: item, store, division — store and division are joined only through market
    fireEvent.click(screen.getByTestId('objects-none'));
    for (const name of ['item', 'store', 'division']) fireEvent.click(within(row(fq(name))).getByTestId('objects-check'));
    await waitFor(() => expect(attr('nodes')).toBe([fq('division'), fq('item'), fq('store')].join(',')));
    expect(tabs()).toEqual(['er · all_er*×']);
    expect(chip()).toBe(' · layout: file · unsaved');
    expect(attr('edges')).toBe('');
    expect(attr('hops')).toBe(`${fq('division')}|${fq('store')}|${fq('market')}`);

    // drag one card: the layout turns manual with the canvas's positions
    fireEvent.click(screen.getByTestId('mock-drag'));
    await new Promise((r) => setTimeout(r, 450)); // the canvas's persist debounce

    // Save as YAML
    fireEvent.click(screen.getByTestId('layout-save-as'));
    const dialog = await screen.findByTestId('save-as-dialog');
    expect((within(dialog).getByTestId('save-as-name') as HTMLInputElement).value).toBe('all_er');
    fireEvent.change(within(dialog).getByTestId('save-as-name'), { target: { value: 'demo_trio' } });
    fireEvent.click(within(dialog).getByTestId('save-as-format-yaml'));
    fireEvent.click(within(dialog).getByTestId('save-as-save'));
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0].fileName).toBe('demo_trio.yaml');
    expect(saved[0].mimeType).toBe('application/yaml');
    const first = readYaml(saved[0].content);
    if (!first.ok) throw new Error(JSON.stringify(first.errors));
    expect(first.doc.objects).toEqual([fq('division'), fq('item'), fq('store')]);
    expect(Object.keys(first.doc.positions)).toEqual([fq('division'), fq('item'), fq('store')]);
    await waitFor(() => expect(chip()).toBe(' · layout: file · demo_trio.yaml'));

    // Open the saved file: the same document, the same picture, and saving it again gives the same bytes
    toOpen = [new File([saved[0].content], 'demo_trio.yaml')];
    fireEvent.click(screen.getByTestId('layout-open'));
    await waitFor(() => expect(tabs()).toEqual(['er · demo_trio×', 'er · demo_trio×']));
    await waitFor(() => expect(attr('nodes')).toBe([fq('division'), fq('item'), fq('store')].join(',')));
    expect(attr('hops')).toBe(`${fq('division')}|${fq('store')}|${fq('market')}`);
    const shown = JSON.parse(attr('positions')) as Record<string, { x: number; y: number }>;
    expect(shown).toEqual(first.doc.positions);
    expect(chip()).toBe(' · layout: file · demo_trio.yaml');
    fireEvent.click(screen.getByTestId('layout-save-as'));
    fireEvent.click(within(await screen.findByTestId('save-as-dialog')).getByTestId('save-as-save'));
    await waitFor(() => expect(saved).toHaveLength(2));
    expect(saved[1]).toEqual(saved[0]);
  });
});
