// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import Ajv2020Module from 'ajv/dist/2020.js';
import { parse } from 'yaml';
import { describe, expect, it } from 'vitest';

type AjvCtor = new (opts: { strict: boolean }) => { compile(schema: object): (data: unknown) => boolean };
const AjvClass = ((Ajv2020Module as unknown as { default?: AjvCtor }).default ??
  (Ajv2020Module as unknown as AjvCtor)) as AjvCtor;
const schema = JSON.parse(readFileSync(new URL('../../schemas/report.schema.json', import.meta.url), 'utf8'));
const example = parse(
  readFileSync(
    new URL('../../../kotlin/ttr-report/src/test/resources/reports/returns_review_q.yaml', import.meta.url),
    'utf8'
  )
);

describe('report.schema.json', () => {
  it('compiles as strict draft 2020-12 and accepts the same C-8 fixture as the Kotlin reader', () => {
    expect(schema.$id).toBe('https://tatrman.org/schemas/report/v1');
    expect(new AjvClass({ strict: true }).compile(schema)(example)).toBe(true);
  });

  it('refuses logical-query parameters and nested unknown keys for external consumers', () => {
    const validate = new AjvClass({ strict: true }).compile(schema);
    expect(validate({ ...example, params: [{ name: 'year', kind: 'int' }] })).toBe(false);
    expect(validate({ ...example, history: { keep: '90d', typo: true } })).toBe(false);
    expect(validate({ ...example, program: 'db.query.x', params: [{ name: 'year', kind: 'int' }] })).toBe(true);
  });

  it('is exposed by the package SDK without a JVM dependency', () => {
    const manifest = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8'));
    expect(manifest.exports['./schemas/report.schema.json']).toBe('./schemas/report.schema.json');
  });
});
