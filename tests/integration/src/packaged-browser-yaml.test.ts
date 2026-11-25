// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import { createContext, runInContext } from 'node:vm';
import { setImmediate } from 'node:timers';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { chromium, type Browser } from 'playwright-core';
import type { GetGraphResponse, ModelGraph } from '@tatrman/lsp';
import type { PublishDiagnosticsParams } from 'vscode-languageserver-protocol';

const workerFile =
  process.env.TTR_WORKER_FILE ??
  new URL('../../../packages/lsp/dist/package/browser.worker.js', import.meta.url);
const fixtureRoot = new URL('../../conformance/dialect/demo-cs/', import.meta.url);
const fixture = (name: string) => readFileSync(new URL(name, fixtureRoot), 'utf8');
const root = 'file:///workspace';
const graphPath = 'graphs/customer.ttrg';
const graphSource = 'graph customer { model: er, objects: [demo.er.entity.Customer] }';
const modelPath = 'model/01-sequence.yaml';
const profilePath = 'model/profiles/base.yaml';
const reference =
  'package demo\nmodel er\ndef entity Customer { description: "Zákazník", attributes: [def attribute kod { type: string }] }';
const tree = fixture('tree/01-sequence.yaml');
const baseProfile = fixture('tree/profiles/base.yaml');

type Message = {
  jsonrpc?: '2.0';
  id?: number;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: unknown;
};

const allowEval = process.env.TTR_WORKER_ALLOW_EVAL === '1';
const realBrowser = process.env.TTR_WORKER_CHROMIUM === '1';
let browser: Browser | undefined;
let host: Server | undefined;
let origin: string;

beforeAll(async () => {
  if (!realBrowser) return;
  host = createServer((request, response) => {
    const worker = request.url === '/worker.js';
    response.setHeader('Content-Type', worker ? 'text/javascript' : 'text/html');
    response.setHeader(
      'Content-Security-Policy',
      `default-src 'none'; script-src 'self'${allowEval ? " 'unsafe-eval'" : ''}; worker-src 'self'`
    );
    response.end(
      worker ? readFileSync(workerFile) : '<!doctype html><title>Packaged worker test</title>'
    );
  });
  await new Promise<void>((resolve, reject) => {
    host!.once('error', reject);
    host!.listen(0, '127.0.0.1', resolve);
  });
  const address = host.address();
  if (!address || typeof address === 'string') throw new Error('Worker fixture server has no port');
  origin = `http://127.0.0.1:${address.port}`;
  // Playwright's default headless Chromium executable is the standalone headless shell.
  browser = await chromium.launch({
    headless: true,
    executablePath: process.env.TTR_WORKER_CHROMIUM_SHELL,
  });
});

afterAll(async () => {
  await browser?.close();
  if (host?.listening) await new Promise<void>((resolve) => host!.close(() => resolve()));
});

function vmWorker(receive: (message: Message) => void) {
  const timers = new Set<ReturnType<typeof setTimeout>>();
  const context = createContext(
    {
      URL,
      TextDecoder,
      TextEncoder,
      setTimeout(fn: () => void, delay: number) {
        const timer = setTimeout(() => {
          timers.delete(timer);
          fn();
        }, delay);
        timers.add(timer);
        return timer;
      },
      clearTimeout(timer: ReturnType<typeof setTimeout>) {
        timers.delete(timer);
        clearTimeout(timer);
      },
      addEventListener() {},
      postMessage: (message: Message) => receive(structuredClone(message)),
    },
    {
      // Equivalent string-compilation restriction for the default, browser-free CI lane.
      codeGeneration: { strings: allowEval, wasm: false },
    }
  );
  runInContext('globalThis.self = globalThis;', context);
  runInContext(readFileSync(workerFile, 'utf8'), context, { filename: String(workerFile) });
  return {
    send(message: Message) {
      const json = JSON.stringify(message);
      setImmediate(() =>
        context.onmessage({
          data: runInContext(`JSON.parse(${JSON.stringify(json)})`, context),
        })
      );
    },
    close() {
      for (const timer of timers) clearTimeout(timer);
    },
  };
}

async function chromiumWorker(receive: (message: Message) => void, fail: (error: Error) => void) {
  const page = await browser!.newPage();
  await page.goto(origin);
  await page.exposeFunction('receive', receive);
  await page.exposeFunction('workerFailed', (message: string) => fail(new Error(message)));
  await page.evaluate(() => {
    const bridge = window as typeof window & {
      receive(message: Message): void;
      workerFailed(message: string): void;
      deliver(message: Message): void;
    };
    const worker = new Worker('/worker.js', { type: 'module' });
    worker.onmessage = ({ data }) => bridge.receive(data);
    worker.onerror = ({ message }) => bridge.workerFailed(message);
    bridge.deliver = (message) => worker.postMessage(message);
  });
  return {
    async send(message: Message) {
      await page.evaluate((data) => {
        (window as typeof window & { deliver(message: Message): void }).deliver(data);
      }, message);
    },
    close: () => page.close(),
  };
}

async function workspace() {
  const pending = new Map<number, { reply(message: Message): void; fail(error: Error): void }>();
  const diagnostics = new Map<string, PublishDiagnosticsParams['diagnostics']>();
  let nextId = 0;
  let failure: Error | undefined;
  function fail(error: Error) {
    failure = error;
    for (const request of pending.values()) request.fail(error);
  }
  function receive(message: Message) {
    if (message.method === 'textDocument/publishDiagnostics') {
      const p = message.params as PublishDiagnosticsParams;
      diagnostics.set(p.uri, p.diagnostics);
    } else if (message.id !== undefined) pending.get(message.id)?.reply(message);
  }
  const worker = realBrowser ? await chromiumWorker(receive, fail) : vmWorker(receive);
  // Keep browser notifications and requests in the same order as postMessage.
  let posted = Promise.resolve();
  const send = (message: Message) => {
    posted = posted.then(() => worker.send({ ...message, jsonrpc: '2.0' })).catch(fail);
  };
  async function request<T>(method: string, params: unknown): Promise<T> {
    if (failure) throw failure;
    const id = ++nextId;
    return new Promise<T>((resolve, reject) => {
      const cleanup = () => {
        clearTimeout(timeout);
        pending.delete(id);
      };
      const timeout = setTimeout(() => {
        cleanup();
        reject(new Error(`Worker request did not reply: ${method}`));
      }, 5000);
      pending.set(id, {
        reply({ result, error }) {
          cleanup();
          if (error) reject(new Error(`${method}: ${JSON.stringify(error)}`));
          else resolve(result as T);
        },
        fail(error) {
          cleanup();
          reject(error);
        },
      });
      send({ id, method, params });
    });
  }
  const notify = (method: string, params: unknown) => send({ method, params });
  const close = () => worker.close();

  try {
    await request('initialize', { processId: null, rootUri: null, capabilities: {} });
    notify('initialized', {});
    await request('modeler/setProjectRoot', {
      projectRoot: root,
      manifest: '',
      deferDiagnostics: true,
    });
  } catch (error) {
    await close();
    throw error;
  }
  const uri = (path: string) => `${root}/${path}`;
  return {
    close,
    diagnostics: (path: string) => diagnostics.get(uri(path)),
    open(path: string, text: string) {
      notify('textDocument/didOpen', {
        textDocument: {
          uri: uri(path),
          languageId: /\.ttr[mg]$/.test(path) ? 'ttr' : 'yaml',
          version: 1,
          text,
        },
      });
    },
    change(path: string, text: string) {
      notify('textDocument/didChange', {
        textDocument: { uri: uri(path), version: 2 },
        contentChanges: [{ text }],
      });
    },
    finishLoad: () => request('modeler/setProjectRoot', { projectRoot: root, manifest: '' }),
    namedGraph: () => request<GetGraphResponse>('modeler/getGraph', { uri: uri(graphPath) }),
    graph: () =>
      request<ModelGraph>('modeler/getModelGraph', {
        textDocument: { uri: root },
        schema: 'er',
      }),
  };
}

type Workspace = Awaited<ReturnType<typeof workspace>>;

function openTree(workspace: Workspace) {
  // Open the model first so loading cannot depend on profile notification order.
  workspace.open(modelPath, tree);
  for (const path of [
    'ttr.yaml',
    'profiles/values.yaml',
    'profiles/demo-cs.yaml',
    'profiles/middle.yaml',
    'profiles/base.yaml',
  ])
    workspace.open(`model/${path}`, fixture(`tree/${path}`));
}

async function graphFor(load: (workspace: Workspace) => void) {
  const ws = await workspace();
  try {
    load(ws);
    ws.open(graphPath, graphSource);
    await ws.finishLoad();
    const projectGraph = await ws.graph();
    // The empty graph in the released baseline should fail the node assertion.
    if (projectGraph.nodes.length > 0) {
      const named = await ws.namedGraph();
      expect(named.missingObjects).toEqual([]);
      expect(named.nodes).toEqual(projectGraph.nodes.map(({ pkg, ...node }) => node));
      expect(named.edges).toEqual(projectGraph.edges);
    }
    return projectGraph;
  } finally {
    await ws.close();
  }
}

function content(graph: ModelGraph) {
  // Source positions and filenames refer to each authored variant.
  return {
    ...graph,
    nodes: graph.nodes.map(({ sourceUri, sourceLocation, ...node }) => node),
    edges: graph.edges.map(({ sourceUri, sourceLocation, ...edge }) => edge),
  };
}

describe('packaged browser worker YAML', () => {
  it('draws the reference .ttrm entity and attribute', async () => {
    const graph = await graphFor((ws) => ws.open('model/01-sequence.ttrm', reference));
    expect(graph.schemaCode).toBe('er');
    expect(graph.edges).toEqual([]);
    expect(graph.nodes).toMatchObject([
      {
        kind: 'entity',
        qname: 'demo.er.entity.Customer',
        name: 'Customer',
        rows: [
          { kind: 'attribute', qname: 'demo.er.entity.Customer.kod', name: 'kod', type: 'string' },
        ],
      },
    ]);
  });

  it.each([
    ['canonical', (ws: Workspace) => ws.open(modelPath, fixture('canonical/01-sequence.yaml'))],
    ['profile tree', openTree],
  ])('draws the same graph for the %s twin', async (_name, load) => {
    const expected = await graphFor((ws) => ws.open('model/01-sequence.ttrm', reference));
    const graph = await graphFor(load);
    expect(graph.nodes).toHaveLength(1);
    expect(graph.nodes[0].sourceUri).toBe(`${root}/${modelPath}`);
    expect(content(graph)).toEqual(content(expected));
  });

  it('publishes a schema diagnostic and ignores a plain YAML document outside the marker', async () => {
    const ws = await workspace();
    try {
      openTree(ws);
      ws.open('model/invalid.yaml', 'ttr: "0.22"\ndefs: invalid\n');
      ws.open('other/plain.yaml', tree);
      await ws.finishLoad();
      expect(ws.diagnostics('model/invalid.yaml')).toEqual([
        expect.objectContaining({ code: 'ys/schema', message: expect.stringContaining('array') }),
      ]);
      expect(ws.diagnostics('other/plain.yaml')).toBeUndefined();
      expect((await ws.graph()).nodes.map((node) => node.qname)).toEqual([
        'demo.er.entity.Customer',
      ]);
    } finally {
      await ws.close();
    }
  });

  it('refreshes the graph after model and inherited profile edits', async () => {
    const ws = await workspace();
    try {
      openTree(ws);
      await ws.finishLoad();
      expect((await ws.graph()).nodes[0]?.qname).toBe('demo.er.entity.Customer');
      ws.change(modelPath, tree.replace('Customer', 'Supplier'));
      expect((await ws.graph()).nodes[0]?.qname).toBe('demo.er.entity.Supplier');
      ws.change(profilePath, baseProfile.replace('default: demo', 'default: revised'));
      expect((await ws.graph()).nodes[0]?.qname).toBe('revised.er.entity.Supplier');
    } finally {
      await ws.close();
    }
  });
});
