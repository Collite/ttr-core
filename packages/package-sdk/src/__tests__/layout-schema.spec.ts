// SPDX-License-Identifier: Apache-2.0
//
// VL-P0 T3 — the published layout JSON Schema (VL contracts V-6). `schemas/layout.schema.json` is the
// language-agnostic artifact a third-party writer of the format validates against; its docs-site copy
// (served at the schema's `$id`) must never drift from it.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import Ajv2020Module from 'ajv/dist/2020.js';
import { parse } from 'yaml';

type Validate = ((data: unknown) => boolean) & {
  errors?: Array<{ instancePath: string; keyword: string; params: Record<string, unknown> }> | null;
};
type AjvCtor = new (opts?: { allErrors?: boolean; strict?: boolean }) => { compile(schema: object): Validate };
const AjvClass = ((Ajv2020Module as unknown as { default?: AjvCtor }).default ??
  (Ajv2020Module as unknown as AjvCtor)) as AjvCtor;

const schemaPath = fileURLToPath(new URL('../../schemas/layout.schema.json', import.meta.url));
const docsCopyPath = fileURLToPath(new URL('../../../../docs-site/docs/model/layout.schema.json', import.meta.url));
const schemaText = readFileSync(schemaPath, 'utf8');
const validate = new AjvClass({ allErrors: true, strict: true }).compile(JSON.parse(schemaText));

function errorsOf(yamlText: string) {
  const ok = validate(parse(yamlText));
  return ok ? [] : (validate.errors ?? []).map((e) => ({ instancePath: e.instancePath, keyword: e.keyword, params: e.params }));
}

const VALID = `layout: 1
name: sales_overview
model: er
objects:
  - hartland.er.entity.item
  - hartland.er.entity.store
positions:
  hartland.er.entity.item: { x: 320, y: -40 }
skin: er.crow
collapsed: []
`;

describe('layout.schema.json', () => {
  it('is a strict-mode-clean 2020-12 schema whose $id is the docs URL it is served at', () => {
    const schema = JSON.parse(schemaText);
    expect(schema.$schema).toBe('https://json-schema.org/draft/2020-12/schema');
    expect(schema.$id).toBe('https://docs.tatrman.org/model/layout.schema.json');
  });

  it('the docs-site copy is byte-identical', () => {
    expect(readFileSync(docsCopyPath, 'utf8')).toBe(schemaText);
  });

  it('accepts the documented shape, and the minimal one', () => {
    expect(errorsOf(VALID)).toEqual([]);
    expect(errorsOf('layout: 1\nname: a\nmodel: db\nobjects: []\n')).toEqual([]);
  });

  it('rejects an unknown root key at the root', () => {
    expect(errorsOf(VALID + 'skinn: er.crow\n')).toEqual([
      { instancePath: '', keyword: 'additionalProperties', params: { additionalProperty: 'skinn' } },
    ]);
  });

  it('rejects an unknown key inside a position, at that position', () => {
    expect(errorsOf(VALID.replace('{ x: 320, y: -40 }', '{ x: 320, y: -40, z: 1 }'))).toEqual([
      { instancePath: '/positions/hartland.er.entity.item', keyword: 'additionalProperties', params: { additionalProperty: 'z' } },
    ]);
  });

  it('rejects a name outside the identifier rule, at /name', () => {
    expect(errorsOf(VALID.replace('name: sales_overview', 'name: sales-overview'))).toEqual([
      { instancePath: '/name', keyword: 'pattern', params: { pattern: '^[A-Za-z_][A-Za-z0-9_]*$' } },
    ]);
  });

  it('rejects any format version but 1, at /layout', () => {
    expect(errorsOf(VALID.replace('layout: 1', 'layout: 2'))).toEqual([
      { instancePath: '/layout', keyword: 'const', params: { allowedValue: 1 } },
    ]);
  });
});
