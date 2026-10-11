// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '../index.js';
import type { SchemaDef, WorldDef } from '../ast.js';

// Grammar 0.14 — a top-level `def schema <name> { columns: [...] }` (a named row schema, the TTR-P
// action-display shape) parses onto SchemaDef: columns in declared order, written exactly like a
// table's columns. The world-storage `def schema` stays a separate production.
const example = `package shop.actions

def schema notify {
    description: "Send a notification to a recipient",
    columns: [
        def column recipient { type: text },
        def column order_id  { type: int },
        def column amount    { type: decimal, optional: true },
        def column due       { type: date, optional: true, description: "payment due date" },
    ]
}

def schema flag_order { tags: ["action"], columns: [ def column order_id { type: int } ] }
`;

describe('def schema (grammar 0.14)', () => {
  it('parses to SchemaDef with ordered, typed, optional-marked columns', () => {
    const { ast, errors } = parseString(example, 'schemas.ttrm');
    expect(errors).toEqual([]);
    const schemas = ast!.definitions.filter((d): d is SchemaDef => d.kind === 'schema');
    expect(schemas.map((s) => s.name)).toEqual(['notify', 'flag_order']);
    const notify = schemas[0];
    expect(notify.description?.value).toBe('Send a notification to a recipient');
    expect(notify.columns!.map((c) => c.name)).toEqual(['recipient', 'order_id', 'amount', 'due']);
    expect(notify.columns!.map((c) => (c.type?.kind === 'simple' ? c.type.name : c.type?.typeName))).toEqual([
      'text',
      'int',
      'decimal',
      'date',
    ]);
    expect(notify.columns!.map((c) => c.optional ?? false)).toEqual([false, false, true, true]);
    expect(schemas[1].tags).toEqual(['action']);
  });

  it('keeps the world-storage `def schema` a separate production', () => {
    const { ast, errors } = parseString(
      'model world\ndef world w { def storage files { type: local_dir, def schema sales { customer: string } } }',
      'w.ttrm',
    );
    expect(errors).toEqual([]);
    const w = ast!.definitions[0] as WorldDef;
    expect(w.storages[0].schemas.map((s) => s.name)).toEqual(['sales']);
    expect(ast!.definitions.some((d) => d.kind === 'schema')).toBe(false);
  });
});
