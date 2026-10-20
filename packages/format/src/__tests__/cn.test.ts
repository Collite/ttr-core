// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseString } from '@tatrman/parser';
import { formatDocument, DEFAULT_FORMAT_CONFIG, type FormatConfig } from '../index.js';

// Grammar 0.17 (CN-P1 S1.4 T2) — the cn layout: one canonical property order, `involves:` one per
// line past three, a binding's `participants:` one label per line, expression blocks byte-exact, the
// `concept { }` sugar laid out inside an entity or attribute, and no comment ever lost.
const CONFORMANCE = join(__dirname, '../../../../tests/conformance/fixtures');
const SAMPLE = join(__dirname, '../../../../samples/0.17/conceptual');

function fmt(src: string, config: FormatConfig = DEFAULT_FORMAT_CONFIG): string {
  const r = parseString(src, 'x.ttrm');
  expect(r.errors.filter((e) => e.severity === 'error')).toEqual([]);
  return formatDocument(r.ast!, src, config);
}

/**
 * The parse result without positions or comment trivia — what formatting must preserve. A cn body's
 * properties are compared by key: the layout reorders them, and their order carries no meaning (a
 * repeated key is `ttr/duplicate-property`). Left out, the gaps the formatter has always had: a
 * table's `management`, `changeSemantics` and `writeback` (Collite/ttr-core#212); an inline
 * `binding:` and a localized `description:` outside a cn body (Collite/ttr-core#219).
 */
function shape(src: string): unknown {
  const r = parseString(src, 'x.ttrm');
  const dropped = new Set(['source', 'lineOffsets', 'management', 'changeSemantics', 'writeback', 'binding', 'descriptionLocalized']);
  return JSON.parse(
    JSON.stringify(r.ast!.definitions, (k, v) => {
      if (dropped.has(k) || k.endsWith('Trivia') || k.endsWith('Source') || k.endsWith('Sources')) return undefined;
      if (k === 'properties' && Array.isArray(v)) {
        return [...v].sort((a: { key: string }, b: { key: string }) => a.key.localeCompare(b.key));
      }
      return v;
    }),
  );
}

describe('formatter — the conceptual model (0.17)', () => {
  // Every conformance fixture, 01–79: the old corpus and the cn one. 01–74 format byte-for-byte as
  // before 0.17, but for the three the 0.17 fixes touch (18 `drill_map`; 59 — comments above a typed
  // property, which the parser hangs on the def: they printed after its `}`, merged into one, and now
  // print before it, each on its own line; 62 `locale`).
  const corpus = readdirSync(CONFORMANCE).filter((f) => f.endsWith('.ttrm')).sort();
  it('covers the whole corpus', () => {
    expect(corpus.length).toBeGreaterThanOrEqual(76);
  });
  for (const name of corpus) {
    it(`${name}: re-parses to the same definitions and is idempotent`, () => {
      const src = readFileSync(join(CONFORMANCE, name), 'utf-8');
      const once = fmt(src);
      expect(shape(once)).toEqual(shape(src));
      expect(fmt(once)).toBe(once);
    });
  }

  it('prints a concept body in the canonical order; unlisted keys keep their source order', () => {
    const src = 'model cn\n\ndef phase P { zeta: 1, basis: occurrence, definition: """x""", of: Order, label: { en: "P" }, '
      + 'alpha: 2, sources: ["a"], description: "d", is_a: Q }\n';
    expect(fmt(src)).toBe('model cn\n\ndef phase P { description: "d", label: { en: "P" }, sources: ["a"], is_a: Q, of: Order, '
      + 'definition: """x""", basis: occurrence, zeta: 1, alpha: 2 }\n');
  });

  it('prints a binding as description · tags · the concept · target · period · the path', () => {
    const src = 'model binding\n\ndef cn2er_quality g { filter: """w = 1""", target: er.entity.i.q, tags: ["t"], quality: cn.q }\n';
    expect(fmt(src)).toBe('model binding\n\ndef cn2er_quality g { tags: ["t"], quality: cn.q, target: er.entity.i.q, filter: """w = 1""" }\n');
  });

  it('keeps `involves:` on one line up to three participants, one per line past that', () => {
    expect(fmt('model cn\n\ndef event E {\n    involves: [ A, B as b, C ]\n}\n'))
      .toBe('model cn\n\ndef event E {\n    involves: [A, B as b, C]\n}\n');
    expect(fmt('model cn\n\ndef event E { involves: [ A, B as from, B as to, C ] }\n'))
      .toBe('model cn\n\ndef event E {\n    involves: [\n        A,\n        B as from,\n        B as to,\n        C\n    ]\n}\n');
  });

  it('breaks a binding\'s `participants:` one label per line', () => {
    const src = 'model binding\n\ndef cn2er_event g { event: cn.E, target: er.entity.e, participants: { a: [er.relation.r], b: [er.relation.s, er.relation.t] } }\n';
    expect(fmt(src)).toBe(`model binding

def cn2er_event g {
    event: cn.E,
    target: er.entity.e,
    participants: {
        a: [er.relation.r],
        b: [er.relation.s, er.relation.t]
    }
}
`);
  });

  it('prints a free-form list of plain items like `tags:`; anything else as written', () => {
    const out = fmt('model cn\n\ndef event E { qualities: [ a,b ,  c ], relations: [ { type: t, to: X } ] }\n');
    expect(out).toContain('qualities: [a, b, c]');
    expect(out).toContain('relations: [ { type: t, to: X } ]');
  });

  it('never reflows an expression block', () => {
    const block = '"""pl\n          a   +    b\n       -  c   \n    """';
    const src = `model cn\n\ndef quality q {\n    definition: ${block},\n    of: X\n}\n`;
    const out = fmt(src);
    expect(out).toBe(`model cn\n\ndef quality q {\n    of: X,\n    definition: ${block}\n}\n`);
    expect(fmt(out)).toBe(out);
  });

  it('keeps leading and trailing comments with their property as it moves', () => {
    const src = `model cn

def kind K {
    grounds: md.K, // g
    // the label
    label: { en: "K" }
}
`;
    expect(fmt(src)).toBe(`model cn

def kind K {
    // the label
    label: { en: "K" },
    grounds: md.K // g
}
`);
  });

  it('keeps a canonical def with its comments byte-for-byte', () => {
    const src = `model cn

def phase OverdueInvoice {
    // the bearer
    of: Invoicing, // an event
    definition: """pl
        invoice_status = 'open'
    """
}
`;
    expect(fmt(src)).toBe(src);
  });

  it('keeps a comment on a participant, breaking the list', () => {
    const src = 'model cn\n\ndef event E {\n    involves: [ A, // first\n        B ]\n}\n';
    expect(fmt(src)).toBe('model cn\n\ndef event E {\n    involves: [\n        A, // first\n        B\n    ]\n}\n');
  });

  it('keeps a comment inside a property — with its value, or with the property', () => {
    const after = 'model cn\n\ndef kind K { grounds: md.K, label: /* after the colon */ { en: "K" } }\n';
    expect(fmt(after)).toBe('model cn\n\ndef kind K { label: /* after the colon */ { en: "K" }, grounds: md.K }\n');
    // Before the colon the parser hands it to the node that ends before it on its line (here
    // `grounds: md.K`); it stays with that node.
    const before = 'model cn\n\ndef kind K { grounds: md.K, label /* before the colon */ : { en: "K" } }\n';
    const out = fmt(before);
    expect(out).toBe('model cn\n\ndef kind K {\n    label: { en: "K" },\n    grounds: md.K /* before the colon */\n}\n');
    expect(fmt(out)).toBe(out);
  });

  it('keeps a def verbatim when the layout cannot place a comment', () => {
    const src = 'model cn\n\ndef event E { period: { at: d }, involves: [ A /* inside a participant */ as a, B ] }\n';
    expect(fmt(src)).toBe(src);
  });

  it('prints the sugar of an entity / attribute as `concept { … }`, its stereotype first', () => {
    const src = `model er

def entity customer { concept: { event: Order, role: Customer } }

def entity order { concept { period: { start: a, end: b }, event: Order } }

def entity item {
    attributes: [def attribute cat { type: text, concept: { label: { en: "Cat" }, quality: cat } }]
}
`;
    expect(fmt(src)).toBe(`model er

def entity customer { concept { role: Customer, event: Order } }

def entity order { concept { event: Order, period: { start: a, end: b } } }

def entity item {
    attributes: [
        def attribute cat { type: text, concept { quality: cat, label: { en: "Cat" } } }
    ]
}
`);
  });

  it('breaks the sugar when it does not fit the width, or carries a comment', () => {
    const wide = fmt('model er\n\ndef entity order {\n    concept { event: Order, period: { start: placed_date, end: delivered_date, end_open: open }, label: { en: "Order" } }\n}\n');
    expect(wide).toBe(`model er

def entity order {
    concept {
        event: Order,
        label: { en: "Order" },
        period: { start: placed_date, end: delivered_date, end_open: open }
    }
}
`);
    const commented = 'model er\n\ndef entity order {\n    concept {\n        event: Order, // the event\n        period: { at: d }\n    }\n}\n';
    expect(fmt(commented)).toBe(commented);
    expect(fmt(wide)).toBe(wide);
  });

  it('keeps an md host — and its sugar — verbatim (the md model has no layout)', () => {
    const src = 'model md\n\ndef dimension Product {\n    key: sku,\n    concept: { label: { en: "Product" }, kind: Item }\n}\n\ndef domain ReasonCode { type: text }\n';
    expect(fmt(src)).toBe(src);
  });

  it('follows the separator setting', () => {
    const one = 'model cn\n\ndef kind K { label: { en: "K" }, grounds: md.K }\n';
    expect(fmt(one, { ...DEFAULT_FORMAT_CONFIG, separator: 'newline' }))
      .toBe('model cn\n\ndef kind K {\n    label: { en: "K" },\n    grounds: md.K\n}\n');
    const broken = 'model cn\n\ndef kind K {\n    grounds: md.K\n    label: { en: "K" }\n}\n';
    expect(fmt(broken, { ...DEFAULT_FORMAT_CONFIG, separator: 'comma' })).toBe(one);
  });

  // The cn sample is written in the canonical layout — formatting it changes nothing.
  for (const name of ['concepts.ttrm', 'bindings.ttrm', 'lexicon-en.ttrm', 'lexicon-cs.ttrm']) {
    it(`formats samples/0.17/conceptual/${name} unchanged`, () => {
      const src = readFileSync(join(SAMPLE, name), 'utf-8');
      expect(fmt(src)).toBe(src);
    });
  }

  // review-117 H-1: `propSep` is `:` or `=`; a sliced value must not keep the `=`.
  it('normalises `key = value` to `key: value` in a cn body, a binding and the sugar', () => {
    expect(fmt('model cn\n\ndef kind K { of = Thing, definition = """x = 1""", period = { at: d } }\n'))
      .toBe('model cn\n\ndef kind K { of: Thing, period: { at: d }, definition: """x = 1""" }\n');
    expect(fmt('model binding\n\ndef cn2er_event g { event = cn.E, target: er.entity.e, participants = { a = [er.relation.r] } }\n'))
      .toBe('model binding\n\ndef cn2er_event g {\n    event: cn.E,\n    target: er.entity.e,\n    participants: {\n        a: [er.relation.r]\n    }\n}\n');
    expect(fmt('model er\n\ndef entity o { concept = { event = Order } }\n')).toBe('model er\n\ndef entity o { concept { event: Order } }\n');
  });

  // review-117 H-2: the parser's offsets count code points; an emoji before a def shifted every slice.
  it('slices correctly after a character outside the BMP', () => {
    const cn = 'model cn\n\ndef kind Cart { label: { en: "🛒 Cart" } }\n\ndef kind Item { of: Thing, grounds: md.Product, qualities: [weight] }\n';
    expect(fmt(cn)).toBe(cn);
    const md = 'model md\n\n// 🛒 web orders\ndef domain Money { type: decimal }\n';
    expect(fmt(md)).toBe(md);
    const block = 'model cn\n\ndef phase B { of: Order, definition: """x = \'😀\' and y = 1""" }\n';
    expect(fmt(block)).toBe(block);
    const typed = 'model db schema dbo\n\ndef table t { description: "🛒 carts", columns: [def column id { type: int }] }\n';
    expect(fmt(typed)).toContain('description: "🛒 carts"');
  });

  // review-117 M-1: a sugar that could break must not break inside a one-line host.
  it('keeps a one-line host on one line, its sugar too, however wide', () => {
    const src = 'model er\n\ndef entity e { concept { kind: Short, label: { en: "A much longer label to push this line past the width limit of one hundred" } } }\n';
    expect(fmt(src)).toBe(src);
  });

  // review-117 L-9(a): a comment before a def's `}` stays there — after the `}` it merged with the def's
  // own trailing comment into one.
  it('keeps a comment written before a closing brace inside the def', () => {
    const cn = 'model cn\n\ndef kind K {\n    grounds: md.K\n    // before the brace\n} // after the brace\n';
    expect(fmt(cn)).toBe(cn);
    const entity = 'model er\n\ndef entity e {\n    description: "x"\n    // inside\n} // outside\n';
    expect(fmt(entity)).toBe(entity);
  });

  // review-118 L-2: a comment inside the sugar breaks its host, under every separator setting.
  it('breaks the host of a sugar that carries a comment, even with `separator: comma`', () => {
    const src = 'model er\n\ndef entity e {\n    concept {\n        kind: E\n        // the kind\n    }\n}\n';
    const expected = 'model er\n\ndef entity e {\n    concept {\n        kind: E\n        // the kind\n    }\n}\n';
    expect(fmt(src)).toBe(expected);
    expect(fmt(src, { ...DEFAULT_FORMAT_CONFIG, separator: 'comma' })).toBe(expected);
  });

  it('prints a lexicon entry\'s `default:` and `sense_priority:`, and keeps the unit locale', () => {
    const out = fmt('model lexicon locale en\n\ndef term t { for: cn.Customer, forms: ["customer"], default: true, sense_priority: 2 }\n');
    expect(out).toBe('model lexicon locale en\n\ndef term t { for: cn.Customer, forms: ["customer"], default: true, sense_priority: 2 }\n');
  });
});
