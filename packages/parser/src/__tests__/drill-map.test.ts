// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '../index.js';
import type { DrillMapDef } from '../ast.js';

describe('grammar v2.2 — drill_map', () => {
  it('parses a full drill_map block', () => {
    const r = parseString(
      [
        'package returns',
        'model query schema drill',
        '',
        'def drill_map reason_to_return_detail {',
        '  from: query.query.returns_by_reason,',
        '  to:   query.query.return_detail,',
        '  args: { order_number: "cr_order_number" },',
        '  display: { en: "Return detail", cs: "Detail reklamace" },',
        '  override: true,',
        '}',
        '',
      ].join('\n'),
    );

    expect(r.errors).toEqual([]);
    expect(r.ast?.definitions).toHaveLength(1);
    const def = r.ast!.definitions[0] as DrillMapDef;
    expect(def.kind).toBe('drillMap');
    expect(def.name).toBe('reason_to_return_detail');
    expect(def.from?.path).toBe('query.query.returns_by_reason');
    expect(def.to?.path).toBe('query.query.return_detail');
    expect(def.args).toHaveLength(1);
    expect(def.args[0].name).toBe('order_number');
    expect(def.args[0].value.value).toBe('cr_order_number');
    expect(def.display?.entries['cs']).toBe('Detail reklamace');
    expect(def.display?.entries['en']).toBe('Return detail');
    expect(def.overrideAuto).toBe(true);
  });

  it('parses a minimal drill_map (no display, no override)', () => {
    const r = parseString(
      [
        'package returns',
        'model query schema drill',
        '',
        'def drill_map warehouse_to_return_detail {',
        '  from: query.query.returns_by_warehouse,',
        '  to:   query.query.return_detail,',
        '  args: { order_number: "cr_order_number" },',
        '}',
        '',
      ].join('\n'),
    );

    expect(r.errors).toEqual([]);
    const def = r.ast!.definitions[0] as DrillMapDef;
    expect(def.display).toBeUndefined();
    expect(def.overrideAuto).toBeUndefined();
  });

  it('rejects an unknown property key inside the drill_map block', () => {
    // `frmo:` typo — expected to be a parse error since the block grammar only
    // accepts the listed drillMapProperty alternatives.
    const r = parseString(
      [
        'package returns',
        'model query schema drill',
        '',
        'def drill_map x {',
        '  frmo: query.query.a,',
        '  to:   query.query.b,',
        '  args: { p: "C" },',
        '}',
        '',
      ].join('\n'),
    );

    expect(r.errors.length).toBeGreaterThan(0);
  });

  it('accepts an empty args block', () => {
    // Validation of "args keys must be parameters on `to`" is enforced by the
    // metadata-side validator, not the parser.
    const r = parseString(
      [
        'package returns',
        'model query schema drill',
        '',
        'def drill_map x {',
        '  from: query.query.a,',
        '  to:   query.query.b,',
        '  args: {},',
        '}',
        '',
      ].join('\n'),
    );

    expect(r.errors).toEqual([]);
    const def = r.ast!.definitions[0] as DrillMapDef;
    expect(def.args).toHaveLength(0);
  });
});
