// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as lsp from 'vscode-languageserver/node';
import { PassThrough } from 'stream';
import { createServerConnection } from '@tatrman/lsp/server';

/**
 * CN-P2 S2.1 T5 — navigation over the conceptual model: goto from a `cn.`-headed binding slot, from a
 * `participants:` key (to the event's label), from a `concept { }` sugar slot and from a lexicon
 * `for:`; find-references on a concept lists its bindings, sugar, lexicon and `is_a` users; hover
 * names the concept. A complete project carries no unresolved reference. S2.2 T6: a concept a
 * `concept { }` block declares is reached at the block. S2.7 T4: hover carries the concept's hygiene row
 * (stereotype, label, grounding, grade); rename reaches every cn ref, sugar slot, binding and lexicon
 * `for:`, and keeps an unlabelled participant's label written out.
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
def term web_buyer { for: cn.WebBuyer, forms: ["web buyer"] }
`,
};

/** The position of the `nth` occurrence of `needle` in `uri`, `delta` characters into it. */
function at(uri: string, needle: string, delta = 0, nth = 0): lsp.Position {
  return posIn(FILES, uri, needle, delta, nth);
}

function posIn(files: Record<string, string>, uri: string, needle: string, delta = 0, nth = 0): lsp.Position {
  const text = files[uri];
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

  it('goto to a concept a `concept { }` block declares lands on its name in the block (CN-P2 S2.2; review-119 M-5)', async () => {
    const loc = await definition(LEXICON, at(LEXICON, 'cn.WebBuyer', 3));
    expect(loc?.uri).toBe(ER);
    expect(loc?.range.start).toEqual(at(ER, 'role: WebBuyer', 6));
    const hover = (await client.sendRequest('textDocument/hover', {
      textDocument: { uri: LEXICON },
      position: at(LEXICON, 'cn.WebBuyer', 3),
    })) as lsp.Hover | null;
    const text = hover && typeof hover.contents === 'object' && 'value' in hover.contents ? hover.contents.value : '';
    expect(text).toContain('hartland.web.cn.concept.WebBuyer');
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

  // ---- CN-P2 S2.7 T4 ----------------------------------------------------------------------------

  async function hoverText(uri: string, position: lsp.Position): Promise<string> {
    const hover = (await client.sendRequest('textDocument/hover', { textDocument: { uri }, position })) as lsp.Hover | null;
    return hover && typeof hover.contents === 'object' && 'value' in hover.contents ? hover.contents.value : '';
  }

  it('hover on a concept: its stereotype, label, grounding and grade (the hygiene row)', async () => {
    const customer = await hoverText(BINDINGS, at(BINDINGS, 'cn.Customer', 4));
    expect(customer).toContain('- **Stereotype:** role');
    expect(customer).toContain('- **Grounding:** `er.entity.customer` *(cn2er_role)*');
    expect(customer).toContain('- **Grade:** er-grounded — lands on `er.entity.customer`');
    const warehouse = await hoverText(CONCEPTS, at(CONCEPTS, 'Warehouse as fulfilling', 2));
    expect(warehouse).toContain('- **Stereotype:** kind');
    expect(warehouse).toContain('- **Label:** en: Distribution center');
    expect(warehouse).toContain('- **Grade:** ungrounded');
    expect(warehouse).not.toContain('**Grounding:**');
  });

  /** A rename's edits as `file:line:col text`, sorted. */
  async function rename(uri: string, position: lsp.Position, newName: string): Promise<string[]> {
    const edit = (await client.sendRequest('textDocument/rename', { textDocument: { uri }, position, newName })) as lsp.WorkspaceEdit;
    return (edit.documentChanges ?? [])
      .flatMap((c) => ('edits' in c ? c.edits.map((e) => `${c.textDocument.uri.split('/').pop()}:${e.range.start.line}:${e.range.start.character} ${e.newText}`) : []))
      .sort();
  }

  it('rename a concept: the def, its cn refs, a sugar slot, a binding (the `cn.` head kept) and a lexicon `for:`', async () => {
    const pos = (uri: string, needle: string, delta: number) => {
      const p = at(uri, needle, delta);
      return `${uri.split('/').pop()}:${p.line}:${p.character}`;
    };
    expect(await rename(CONCEPTS, at(CONCEPTS, 'def event Order', 10), 'Purchase')).toEqual(
      [
        `${pos(CONCEPTS, 'def event Order', 10)} Purchase`,
        `${pos(CONCEPTS, 'event: Order', 7)} Purchase`,
        `${pos(CONCEPTS, 'of: Order', 4)} Purchase`,
        `${pos(ER, 'event: Order', 7)} Purchase`,
        `${pos(BINDINGS, 'cn.Order', 0)} cn.Purchase`,
        `${pos(LEXICON, 'cn.Order', 0)} cn.Purchase`,
      ].sort(),
    );
  });

  it('rename keeps a participant label the binding reads: `involves: [Customer]` → `Buyer as customer`', async () => {
    const edits = await rename(CONCEPTS, at(CONCEPTS, 'def role Customer', 9), 'Buyer');
    expect(edits).toContain(`concepts.ttrm:${lineOf(CONCEPTS, 'involves: [Customer')}:15 Buyer as customer`);
    // the binding's `participants: { customer: … }` key still names the label — nothing to edit there
    expect(edits.filter((e) => e.startsWith('bindings.ttrm'))).toEqual([`bindings.ttrm:${lineOf(BINDINGS, 'cn.Customer')}:34 cn.Buyer`]);
    // a labelled participant keeps its own label: renaming Warehouse touches only the ref
    expect(await rename(CONCEPTS, at(CONCEPTS, 'def kind Warehouse', 9), 'Depot')).toEqual(
      [
        `concepts.ttrm:${lineOf(CONCEPTS, 'def kind Warehouse')}:9 Depot`,
        `concepts.ttrm:${lineOf(CONCEPTS, 'Warehouse as fulfilling')}:${at(CONCEPTS, 'Warehouse as fulfilling').character} Depot`,
      ].sort(),
    );
  });
});

// ---- CN-P2 review-119 — rename, the sugar's declaring slot, participant labels, named concept imports ----

const ROOT119 = 'file:///cnnav119';
const CORE119 = `${ROOT119}/core.ttrm`;
const OTHER119 = `${ROOT119}/other.ttrm`;
const CONCEPTS119 = `${ROOT119}/concepts.ttrm`;
const ER119 = `${ROOT119}/er.ttrm`;
const BINDINGS119 = `${ROOT119}/bindings.ttrm`;
const LEXICON119 = `${ROOT119}/lexicon.ttrm`;
const IMPORTS119 = `${ROOT119}/imports.ttrm`;
// In dependency order (ttr-core#174).
const FILES119: Record<string, string> = {
  [CORE119]: `package hartland.core
model cn
def kind Supplier { label: { en: "Supplier" } }
`,
  [OTHER119]: `package hartland.other
model cn
def kind Supplier { label: { en: "Wholesaler" } }
`,
  [CONCEPTS119]: `package hartland.web
model cn
def kind Party { label: { en: "Party" } }
def kind Store { label: { en: "Store" } }
def role Shopper { of: Party, event: Visit }
def event Visit { involves: [Shopper, Store as seller], period: { at: visited_at } }
`,
  [ER119]: `package hartland.web
model er
def entity party { attributes: [ def attribute party_id { type: int, isKey: true } ], concept { kind: Party } }
def entity store { attributes: [ def attribute store_id { type: int, isKey: true } ] }
def entity visit { attributes: [ def attribute visit_id { type: int, isKey: true } ] }
def entity web_return {
    attributes: [ def attribute return_id { type: int, isKey: true } ],
    concept { event: WebReturn, involves: [Shopper, Store] }
}
def relation visit_party { from: er.entity.visit, to: er.entity.party }
def relation visit_store { from: er.entity.visit, to: er.entity.store }
`,
  [BINDINGS119]: `package hartland.web
model binding
def cn2er_event visit_g {
    event: cn.Visit,
    target: er.entity.visit,
    participants: { shopper: [er.relation.visit_party], seller: [er.relation.visit_store] }
}
`,
  [LEXICON119]: `package hartland.web
model lexicon locale en
def term web_return { for: cn.WebReturn, forms: ["web return"] }
`,
  [IMPORTS119]: `package hartland.web
import hartland.core.Supplier
import hartland.other.*
model cn
def kind Vendor { is_a: Supplier }
`,
};

describe('CN-P2 review-119 — rename, the sugar declaring slot, labels and named concept imports', () => {
  let client: lsp.Connection;
  let server: lsp.Connection;
  const diagnostics = new Map<string, lsp.Diagnostic[]>();
  const at119 = (uri: string, needle: string, delta = 0) => posIn(FILES119, uri, needle, delta);
  const pos119 = (uri: string, needle: string, delta = 0) => {
    const p = at119(uri, needle, delta);
    return `${uri.split('/').pop()}:${p.line}:${p.character}`;
  };

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
      rootUri: ROOT119,
      workspaceFolders: [{ uri: ROOT119, name: 'cnnav119' }],
      capabilities: {},
    });
    client.sendNotification('initialized', {});
    for (const [uri, text] of Object.entries(FILES119)) {
      client.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'ttr', version: 1, text } });
    }
    await sleep(500);
  });
  afterAll(() => {
    client.dispose();
    server.dispose();
  });

  async function rename(uri: string, position: lsp.Position, newName: string): Promise<string[]> {
    const edit = (await client.sendRequest('textDocument/rename', { textDocument: { uri }, position, newName })) as lsp.WorkspaceEdit;
    return (edit.documentChanges ?? [])
      .flatMap((c) => ('edits' in c ? c.edits.map((e) => `${c.textDocument.uri.split('/').pop()}:${e.range.start.line}:${e.range.start.character} ${e.newText}`) : []))
      .sort();
  }
  async function hoverText(uri: string, position: lsp.Position): Promise<string> {
    const hover = (await client.sendRequest('textDocument/hover', { textDocument: { uri }, position })) as lsp.Hover | null;
    return hover && typeof hover.contents === 'object' && 'value' in hover.contents ? hover.contents.value : '';
  }

  it('H-2: a block naming a cn def concept in its declaring slot is a reference to it — goto, hover, references, rename', async () => {
    const goto = (await client.sendRequest('textDocument/definition', {
      textDocument: { uri: ER119 },
      position: at119(ER119, 'kind: Party', 7),
    })) as lsp.Location;
    expect(goto.uri).toBe(CONCEPTS119);
    expect(goto.range.start.line).toBe(at119(CONCEPTS119, 'def kind Party').line);
    expect(await hoverText(ER119, at119(ER119, 'kind: Party', 7))).toContain('**hartland.web.cn.concept.Party** *(concept)*');
    const refs = (await client.sendRequest('textDocument/references', {
      textDocument: { uri: CONCEPTS119 },
      position: at119(CONCEPTS119, 'def kind Party', 9),
      context: { includeDeclaration: false },
    })) as lsp.Location[];
    expect(refs.map((r) => `${r.uri.split('/').pop()}:${r.range.start.line}:${r.range.start.character}`).sort()).toEqual(
      [pos119(CONCEPTS119, 'of: Party', 4), pos119(ER119, 'kind: Party', 6)].sort(),
    );
    expect(await rename(CONCEPTS119, at119(CONCEPTS119, 'def kind Party', 9), 'Agent')).toEqual(
      [
        `${pos119(CONCEPTS119, 'def kind Party', 9)} Agent`,
        `${pos119(CONCEPTS119, 'of: Party', 4)} Agent`,
        `${pos119(ER119, 'kind: Party', 6)} Agent`,
      ].sort(),
    );
  });

  it('M-5: renaming a concept only a block declares edits its name, not the slot', async () => {
    expect(await rename(LEXICON119, at119(LEXICON119, 'cn.WebReturn', 4), 'OnlineReturn')).toEqual(
      [`${pos119(ER119, 'event: WebReturn', 7)} OnlineReturn`, `${pos119(LEXICON119, 'cn.WebReturn')} cn.OnlineReturn`].sort(),
    );
    // F2 on the block's declaring name renames that concept, not the entity around it
    const prepared = (await client.sendRequest('textDocument/prepareRename', {
      textDocument: { uri: ER119 },
      position: at119(ER119, 'event: WebReturn', 9),
    })) as { range: lsp.Range; placeholder: string };
    expect(prepared.placeholder).toBe('WebReturn');
    expect(prepared.range.start).toEqual(at119(ER119, 'event: WebReturn', 7));
  });

  it('M-5: renaming a default label from a `participants:` key writes the label out, never the participant ref', async () => {
    expect(await rename(BINDINGS119, at119(BINDINGS119, 'shopper:', 2), 'client')).toEqual(
      [
        `${pos119(BINDINGS119, 'shopper:')} client`,
        `${pos119(CONCEPTS119, 'involves: [Shopper', 'involves: [Shopper'.length)}  as client`,
      ].sort(),
    );
  });

  it('L-7(c): F2 on a written label renames the label, not the event that involves it', async () => {
    const prepared = (await client.sendRequest('textDocument/prepareRename', {
      textDocument: { uri: CONCEPTS119 },
      position: at119(CONCEPTS119, 'Store as seller', 11),
    })) as { range: lsp.Range; placeholder: string };
    expect(prepared).toEqual({
      range: { start: at119(CONCEPTS119, 'seller'), end: at119(CONCEPTS119, 'seller', 'seller'.length) },
      placeholder: 'seller',
    });
    expect(await rename(CONCEPTS119, at119(CONCEPTS119, 'Store as seller', 11), 'vendor')).toEqual(
      [`${pos119(CONCEPTS119, 'seller')} vendor`, `${pos119(BINDINGS119, 'seller:')} vendor`].sort(),
    );
  });

  it("M-6: the conflict check stays in the target's namespace", async () => {
    // an er entity may take a participant label's name, and a concept an er entity's
    expect(await rename(ER119, at119(ER119, 'def entity store', 11), 'seller')).toContain(`${pos119(ER119, 'def entity store', 11)} seller`);
    expect(await rename(CONCEPTS119, at119(CONCEPTS119, 'def kind Party', 9), 'party')).toContain(
      `${pos119(CONCEPTS119, 'def kind Party', 9)} party`,
    );
    // a concept never takes a concept's name in its package; a label never a sibling label's
    await expect(rename(CONCEPTS119, at119(CONCEPTS119, 'def kind Store', 9), 'Party')).rejects.toThrow(
      /a symbol with that name already exists \(hartland\.web\.cn\.concept\.Party\)/,
    );
    await expect(rename(CONCEPTS119, at119(CONCEPTS119, 'Store as seller', 11), 'shopper')).rejects.toThrow(
      /hartland\.web\.cn\.concept\.Visit\.shopper/,
    );
  });

  it('M-7: a named concept import is used, and the editor resolves through it — hover, goto, rename', async () => {
    expect((diagnostics.get(IMPORTS119) ?? []).filter((d) => d.code === 'ttr/unused-import')).toEqual([]);
    expect((diagnostics.get(IMPORTS119) ?? []).filter((d) => d.code === 'ttr/ambiguous-reference')).toEqual([]);
    expect(await hoverText(IMPORTS119, at119(IMPORTS119, 'is_a: Supplier', 6))).toContain('**hartland.core.cn.concept.Supplier**');
    const goto = (await client.sendRequest('textDocument/definition', {
      textDocument: { uri: IMPORTS119 },
      position: at119(IMPORTS119, 'is_a: Supplier', 6),
    })) as lsp.Location;
    expect(goto.uri).toBe(CORE119);
    expect(await rename(CORE119, at119(CORE119, 'def kind Supplier', 9), 'Provider')).toEqual(
      [
        `${pos119(CORE119, 'def kind Supplier', 9)} Provider`,
        `${pos119(IMPORTS119, 'hartland.core.Supplier')} hartland.core.Provider`,
        `${pos119(IMPORTS119, 'is_a: Supplier', 6)} Provider`,
      ].sort(),
    );
  });
});
