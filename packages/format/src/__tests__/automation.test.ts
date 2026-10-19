// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseString } from '@tatrman/parser';
import { formatDocument, DEFAULT_FORMAT_CONFIG } from '../index.js';

// Grammar 0.16 (AU-core-P0 S0.2 T7) — the formatter does not mangle the automation model: every
// value form survives verbatim, the output re-parses to the same definitions, and formatting is
// idempotent. A world keeps its body verbatim (it used to print `def world w {}`).
const AUTOMATION = join(__dirname, '../../../kotlin/ttr-parser/src/test/resources/automation');
const CONFORMANCE = join(__dirname, '../../../../tests/conformance/fixtures');

function fmt(src: string): string {
  const r = parseString(src, 'x.ttrm');
  expect(r.errors).toEqual([]);
  return formatDocument(r.ast!, src, DEFAULT_FORMAT_CONFIG);
}

/** The parse result without positions or comment trivia — what formatting must preserve. */
function shape(src: string): unknown {
  const r = parseString(src, 'x.ttrm');
  return JSON.parse(
    JSON.stringify(r.ast!.definitions, (k, v) => (k === 'source' || k.endsWith('Trivia') || k.endsWith('Source') ? undefined : v)),
  );
}

describe('formatter — the automation model (0.16)', () => {
  for (const file of [
    join(AUTOMATION, 'automation.ttrm'),
    join(AUTOMATION, 'programs.ttrm'),
    join(AUTOMATION, 'demo-world.ttrm'),
    join(CONFORMANCE, '71-automation.ttrm'),
    join(CONFORMANCE, '72-automation-world.ttrm'),
    join(CONFORMANCE, '73-states.ttrm'),
  ]) {
    const name = file.split('/').pop()!;
    it(`${name}: re-parses to the same definitions and is idempotent`, () => {
      const src = readFileSync(file, 'utf-8');
      const once = fmt(src);
      expect(shape(once)).toEqual(shape(src));
      expect(fmt(once)).toBe(once);
    });
  }

  it('keeps an automation def verbatim: every value form, source order, comments', () => {
    const src = `model automation

def action ask {
    // the sinks first: authored order is kept (typed properties are not hoisted)
    sinks: { native: task }
    columns: [def column who { type: role }] // who answers
    transition: order.state -> released { from: [placed, held] }
    set: order.held_for = who
    due: 2 working_days
    on_due: hold { order: order_number }
    program: *
}
`;
    expect(fmt(src)).toBe(src);
  });

  it('keeps a trailing comment on `transitions:` and on `states:`', () => {
    const src = `model er

def entity order {
    attributes: [
        def attribute state {
            type: text,
            states: [placed, held], // the lifecycle
            transitions: { placed: [held] } // held only from placed
        }
    ]
}
`;
    const out = fmt(src);
    expect(out).toContain('// the lifecycle');
    expect(out).toContain('// held only from placed');
    expect(fmt(out)).toBe(out);
  });

  it('keeps a world body verbatim', () => {
    const src = 'model world\n\ndef world w {\n    def identity a { principal: "p" }\n    def grant { who: role.ops, program: *, as: author }\n}\n';
    expect(fmt(src)).toBe(src);
  });
});
