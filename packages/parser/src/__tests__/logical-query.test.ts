// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '../index.js';

/**
 * LQ-T0·S1 — a logical query (`def query` in a `model er` file, project
 * `ttr-translator/logical-queries`, contracts L-1/L-2) takes no parameters (ruling LQ-5):
 * a parametrized query stays a db query. The walker flags it; nothing else about a query
 * changes with the model it sits in.
 */
const LOGICAL = 'model er\n\ndef query q {\n  sourceText: """sql\nSELECT sale.revenue FROM sale\n"""\n}\n';
const LOGICAL_PARAMS =
  'model er\n\ndef query q {\n  sourceText: """sql\nSELECT sale.revenue FROM sale\n""",\n' +
  '  parameters: [ { name: since, type: date } ]\n}\n';

describe('LQ — logical query (def query under model er)', () => {
  it('parses as an ordinary query with the er model directive', () => {
    const r = parseString(LOGICAL);
    expect(r.errors).toEqual([]);
    expect(r.ast?.modelDirective?.modelCode).toBe('er');
    expect(r.ast?.definitions[0].kind).toBe('query');
  });

  it('a logical query with parameters is flagged ttr/logical-query-parameters (a warning, as in Kotlin)', () => {
    const r = parseString(LOGICAL_PARAMS);
    const d = r.errors.filter((e) => e.code === 'ttr/logical-query-parameters');
    expect(d).toHaveLength(1);
    // a warning: an error would make whole-file tools (format, migrate) refuse the file
    expect(d[0].severity).toBe('warning');
    expect(r.errors.filter((e) => e.severity === 'error')).toEqual([]);
    expect(d[0].message).toContain("'q'");
    // the query still walks — the diagnostic does not drop the definition
    expect(r.ast?.definitions[0].kind).toBe('query');
  });

  it('the same parameters on a db query, or a directive-less one, are fine', () => {
    for (const src of [LOGICAL_PARAMS.replace('model er', 'model db'), LOGICAL_PARAMS.replace('model er\n\n', '')]) {
      expect(parseString(src).errors.map((e) => e.code)).not.toContain('ttr/logical-query-parameters');
    }
  });
});
