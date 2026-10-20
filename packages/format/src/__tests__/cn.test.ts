// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseString } from '@tatrman/parser';
import { formatDocument, DEFAULT_FORMAT_CONFIG } from '../index.js';

// Grammar 0.17 (CN-P1 S1.2) — until the cn layout lands (S1.4 T2) the formatter keeps the cn defs and
// every host of the `concept { }` sugar verbatim: nothing of a concept body is dropped, the output
// re-parses to the same definitions, and formatting is idempotent.
const CONFORMANCE = join(__dirname, '../../../../tests/conformance/fixtures');

function fmt(src: string): string {
  const r = parseString(src, 'x.ttrm');
  expect(r.errors).toEqual([]);
  return formatDocument(r.ast!, src, DEFAULT_FORMAT_CONFIG);
}

/**
 * The parse result without positions or comment trivia — what formatting must preserve. A table's
 * `writeback` reservation is left out: the formatter has never printed it (EN-P1, 0.10 — not a cn
 * gap; fixture 79 uses it only to show the cn nouns as object keys).
 */
function shape(src: string): unknown {
  const r = parseString(src, 'x.ttrm');
  return JSON.parse(
    JSON.stringify(r.ast!.definitions, (k, v) =>
      k === 'source' || k === 'lineOffsets' || k === 'writeback' || k.endsWith('Trivia') || k.endsWith('Source') ? undefined : v,
    ),
  );
}

describe('formatter — the conceptual model (0.17)', () => {
  for (const name of ['75-cn-concepts.ttrm', '76-cn-bindings.ttrm', '77-cn-sugar.ttrm', '78-cn-lexicon.ttrm', '79-cn-nouns-as-ids.ttrm']) {
    it(`${name}: re-parses to the same definitions and is idempotent`, () => {
      const src = readFileSync(join(CONFORMANCE, name), 'utf-8');
      const once = fmt(src);
      expect(shape(once)).toEqual(shape(src));
      expect(fmt(once)).toBe(once);
    });
  }

  it('keeps a concept def, a binding and a sugar host byte-for-byte', () => {
    const defs = [
      'def phase OverdueInvoice {\n    of: Invoicing, // the bearer\n    definition: """pl\n        invoice_status = \'open\'\n    """\n}',
      'def cn2er_event transfer_g { event: cn.StockTransfer, participants: { from: [er.relation.r] } }',
    ];
    const cn = `model cn\n\n${defs[0]}\n`;
    expect(fmt(cn)).toBe(cn);
    const binding = `model binding\n\n${defs[1]}\n`;
    expect(fmt(binding)).toBe(binding);
    const sugar = 'model er\n\ndef entity invoice {\n    attributes: [def attribute id { type: int }],\n    concept { kind: Invoice }\n}\n';
    expect(fmt(sugar)).toBe(sugar);
  });

  it('prints a lexicon entry\'s `default:` and `sense_priority:`', () => {
    const out = fmt('model lexicon locale en\n\ndef term t { for: cn.Customer, forms: ["customer"], default: true, sense_priority: 2 }\n');
    expect(out).toContain('default: true');
    expect(out).toContain('sense_priority: 2');
  });
});
