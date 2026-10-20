// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';

/**
 * CN-P2 S2.1 T5 — navigation over the conceptual model: goto from a `cn.`-headed binding slot, from a
 * `participants:` key (to the event's label), from a `concept { }` sugar slot and from a lexicon
 * `for:`; find-references on a concept lists its bindings, sugar, lexicon and `is_a` users; hover
 * names the concept. A complete project carries no unresolved reference.
 */
function createPairedConnection(): { client: lsp.Connection; server: lsp.Connection } {
  const a = new PassThrough({ objectMode: true });
  const b = new PassThrough({ objectMode: true });
  const client = lsp.createConnection(
    new lsp.StreamMessageReader(a as unknown as NodeJS.ReadableStream),
    new lsp.StreamMessageWriter(b as unknown as NodeJS.WritableStream),
  ) as lsp.Connection;
  const server = lsp.createConnection(
    new lsp.StreamMessageReader(b as unknown as NodeJS.ReadableStream),
    new lsp.StreamMessageWriter(a as unknown as NodeJS.WritableStream),
  ) as lsp.Connection;
  client.listen();
  server.listen();
  return { client, server };
}
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

const ROOT = 'file:///cnnav';
// Opened in this order, each file after the ones it refers to: the LSP lints a document when it
// opens or changes, so a reference into a later-opened file would be reported until then (ttr-core#174).
const FILES: Record<string, string> = {
  [`${ROOT}/concepts.ttrm`]: `package hartland.web
model cn
def kind Party { label: { en: "Party" } }
def kind Organization { is_a: Party }
def kind Warehouse { label: { en: "Distribution center" } }
def role Customer { of: Party, event: Order }
def event Order {
    involves: [Customer, Warehouse as fulfilling],
    period: { start: placed_date },
    qualities: [placed_date]
}
def quality placed_date { of: Order }
`,
  [`${ROOT}/er.ttrm`]: `package hartland.web
model er
def entity customer { attributes: [ def attribute customer_id { type: int, isKey: true } ] }
def entity warehouse { attributes: [ def attribute warehouse_id { type: int, isKey: true } ] }
def entity order {
    attributes: [ def attribute order_number { type: int, isKey: true }, def attribute placed_date { type: date } ]
}
def entity web_buyer {
    attributes: [ def attribute buyer_id { type: int, isKey: true } ],
    concept: { role: WebBuyer, event: Order }
}
def relation order_customer { from: er.entity.order, to: er.entity.customer }
def relation order_warehouse { from: er.entity.order, to: er.entity.warehouse }
`,
  [`${ROOT}/bindings.ttrm`]: `package hartland.web
model binding
def cn2er_role customer_g { role: cn.Customer, target: er.entity.customer }
def cn2er_event order_g {
    event: cn.Order,
    target: er.entity.order,
    participants: { customer: [er.relation.order_customer], fulfilling: [er.relation.order_warehouse] },
    period: { start: er.entity.order.placed_date }
}
`,
  [`${ROOT}/lexicon.ttrm`]: `package hartland.web
model lexicon locale en
def term order { for: cn.Order, forms: ["order", "orders"] }
`,
};

/** The position of the `nth` occurrence of `needle` in `uri`, `delta` characters into it. */
function at(uri: string, needle: string, delta = 0, nth = 0): lsp.Position {
  const text = FILES[uri];
  let i = -1;
  for (let k = 0; k <= nth; k++) i = text.indexOf(needle, i + 1);
  if (i < 0) throw new Error(`'${needle}' not in ${uri}`);
  const before = text.slice(0, i + delta).split('\n');
  return { line: before.length - 1, character: before[before.length - 1].length };
}

/** The 0-based line of the first occurrence of `needle` in `uri`. */
const lineOf = (uri: string, needle: string): number => at(uri, needle).line;

describe('CN-P2 S2.1 — navigation over the conceptual model', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;
  const diagnostics = new Map<string, lsp.Diagnostic[]>();

  beforeAll(async () => {
    const p = createPairedConnection();
    client = p.client;
    server = p.server;
    client.onNotification('textDocument/publishDiagnostics', (params: lsp.PublishDiagnosticsParams) => {
      diagnostics.set(params.uri, params.diagnostics);
    });
    createServerConnection(server);
    await client.sendRequest('initialize', {
      processId: null,
      rootUri: ROOT,
      workspaceFolders: [{ uri: ROOT, name: 'cnnav' }],
      capabilities: {},
    });
    client.sendNotification('initialized', {});
    for (const [uri, text] of Object.entries(FILES)) {
      client.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'ttr', version: 1, text } });
    }
    await sleep(500);
  });
  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  const BINDINGS = `${ROOT}/bindings.ttrm`;
  const CONCEPTS = `${ROOT}/concepts.ttrm`;
  const ER = `${ROOT}/er.ttrm`;
  const LEXICON = `${ROOT}/lexicon.ttrm`;

  async function definition(uri: string, position: lsp.Position): Promise<lsp.Location | null> {
    const r = (await client.sendRequest('textDocument/definition', { textDocument: { uri }, position })) as
      | lsp.Location
      | lsp.Location[]
      | null;
    return Array.isArray(r) ? (r[0] ?? null) : r;
  }

  it('a complete project carries no unresolved reference', async () => {
    for (const uri of Object.keys(FILES)) {
      const unresolved = (diagnostics.get(uri) ?? []).filter((d) => d.code === 'ttr/unresolved-reference');
      expect(unresolved, uri).toEqual([]);
    }
  });

  it('goto from `role: cn.Customer` lands on the concept, not the er entity', async () => {
    const loc = await definition(BINDINGS, at(BINDINGS, 'cn.Customer', 4));
    expect(loc?.uri).toBe(CONCEPTS);
    expect(loc?.range.start.line).toBe(lineOf(CONCEPTS, 'def role Customer'));
  });

  it("goto from a `participants:` key lands on the event's label", async () => {
    const loc = await definition(BINDINGS, at(BINDINGS, 'fulfilling:', 2));
    expect(loc?.uri).toBe(CONCEPTS);
    expect(loc?.range.start.line).toBe(lineOf(CONCEPTS, 'Warehouse as fulfilling'));
  });

  it('goto from a sugar slot (`event: Order` in an entity) and from a lexicon `for:`', async () => {
    const fromSugar = await definition(ER, at(ER, 'event: Order', 8));
    expect(fromSugar?.uri).toBe(CONCEPTS);
    expect(fromSugar?.range.start.line).toBe(lineOf(CONCEPTS, 'def event Order'));
    const fromTerm = await definition(LEXICON, at(LEXICON, 'cn.Order', 3));
    expect(fromTerm?.uri).toBe(CONCEPTS);
    expect(fromTerm?.range.start.line).toBe(lineOf(CONCEPTS, 'def event Order'));
  });

  it('find-references on a concept lists its binding, sugar, lexicon and concept users', async () => {
    const refs = (await client.sendRequest('textDocument/references', {
      textDocument: { uri: CONCEPTS },
      position: at(CONCEPTS, 'def event Order', 10),
      context: { includeDeclaration: false },
    })) as lsp.Location[];
    const where = refs.map((r) => `${r.uri.split('/').pop()}:${r.range.start.line}`).sort();
    expect(where).toEqual(
      [
        `bindings.ttrm:${lineOf(BINDINGS, 'event: cn.Order')}`,
        `concepts.ttrm:${lineOf(CONCEPTS, 'of: Party, event: Order')}`,
        `concepts.ttrm:${lineOf(CONCEPTS, 'def quality placed_date')}`,
        `er.ttrm:${lineOf(ER, 'event: Order')}`,
        `lexicon.ttrm:${lineOf(LEXICON, 'cn.Order')}`,
      ].sort(),
    );
    const partyRefs = (await client.sendRequest('textDocument/references', {
      textDocument: { uri: CONCEPTS },
      position: at(CONCEPTS, 'def kind Party', 9),
      context: { includeDeclaration: false },
    })) as lsp.Location[];
    expect(partyRefs.map((r) => r.range.start.line).sort()).toEqual(
      [lineOf(CONCEPTS, 'is_a: Party'), lineOf(CONCEPTS, 'of: Party')].sort(),
    );
  });

  it('hover on a cn ref names the concept', async () => {
    const hover = (await client.sendRequest('textDocument/hover', {
      textDocument: { uri: BINDINGS },
      position: at(BINDINGS, 'cn.Order', 4),
    })) as lsp.Hover | null;
    const text = hover && typeof hover.contents === 'object' && 'value' in hover.contents ? hover.contents.value : '';
    expect(text).toContain('hartland.web.cn.concept.Order');
    expect(text).toContain('(concept)');
  });
});
