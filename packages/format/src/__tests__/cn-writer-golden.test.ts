// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseString } from '@tatrman/parser';
import { formatDocument } from '../index.js';

// CN-P2 S2.4 — the Kotlin writer (`ttr-writer` `TtrRenderer`) prints the cn defs in this formatter's canonical
// order and layout, so `modeler-fmt` leaves every def it writes as it is. `cn-writer-golden.ttrm` is what the writer
// renders (pinned by `CnRoundTripSpec.kt`): one of each stereotype and binding kind, every property, the broken
// layouts. The one difference is the file's: the writer separates its defs (and `package` from `model`) by one
// newline, for every kind; the formatter by a blank line — so the comparison ignores blank lines.
const squash = (s: string) => s.replace(/\n{2,}/g, '\n');
const GOLDEN = join(__dirname, '../../../kotlin/ttr-writer/src/test/resources/cn/cn-writer-golden.ttrm');

describe('CN-P2 S2.4 — the writer’s cn output is already formatted', () => {
  it('modeler-fmt leaves the writer golden unchanged', () => {
    const src = readFileSync(GOLDEN, 'utf8');
    const r = parseString(src, 'cn-writer-golden.ttrm');
    expect(r.errors.filter((e) => e.severity === 'error')).toEqual([]);
    expect(squash(formatDocument(r.ast!, src))).toBe(squash(src));
  });
});
