// SPDX-License-Identifier: Apache-2.0
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { ForeignTree } from './input.js';
import { ProfileReader } from './loading.js';
import { ProfileEngine } from './engine.js';

const root = fileURLToPath(
  new URL('../../../tests/conformance/dialect/demo-cs-links/operands/', import.meta.url)
);
const read = (file: string) => readFileSync(file, 'utf8');
const cases = readdirSync(root, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name)
  .sort();

describe('S5.3a operands: Kotlin-produced canonical bytes, reports and refusals', () => {
  test('all four behaviours and their refusal/null variants are present', () => {
    expect(cases).toEqual([
      'as-ref',
      'as-ref-names-refused',
      'package-empty',
      'package-invalid',
      'package-missing',
      'package-null',
      'package-trailing-newline',
      'required-silent',
      'required-warn',
      'run-first',
      'run-only',
      'verbatim',
      'verbatim-default',
      'verbatim-dotted',
      'verbatim-mapping',
      'verbatim-no-records',
      'xmi-multi',
    ]);
  });
  for (const name of cases) {
    test(`${name}: matches the Kotlin oracle`, () => {
      const directory = join(root, name);
      const tree = join(directory, 'tree');
      const expected = join(directory, 'expected');
      const config = JSON.parse(read(join(directory, 'case.json'))) as {
        sourceName?: string;
        trees?: Array<{ input: string; file?: string; label?: string }>;
      };
      const run = () =>
        ProfileEngine.apply(
          (
            config.trees ??
            readdirSync(tree)
              .filter((file) => file.endsWith('.yaml'))
              .sort()
              .map((input) => ({ input }))
          ).map((item) =>
            ForeignTree.read(
              read(join(tree, item.input)),
              item.file ?? item.input,
              item.label ?? item.input
            )
          ),
          [ProfileReader.read(read(join(directory, 'profile.yaml')), 'profile.yaml')],
          tree,
          true,
          config.sourceName
        );
      const error = join(expected, 'error.json');
      if (existsSync(error)) {
        const oracle = JSON.parse(read(error));
        let caught: unknown;
        try {
          run();
        } catch (failure) {
          caught = failure;
        }
        expect(caught).toMatchObject({
          code: oracle.code,
          message: oracle.message,
          location: {
            file: oracle.file,
            line: oracle.line,
            column: oracle.column,
            path: oracle.path,
          },
        });
        return;
      }
      const result = run();
      const actual = new Map(
        result.documents.map((document) => [
          `${String(document.value.package)}.${String(document.value.model)}.yaml`,
          Buffer.from(document.write()),
        ])
      );
      expect(actual.size).toBe(result.documents.length);
      const twins = readdirSync(expected)
        .filter((file) => file.endsWith('.yaml'))
        .sort();
      expect([...actual.keys()].sort()).toEqual(twins);
      for (const file of twins)
        expect(actual.get(file), file).toEqual(readFileSync(join(expected, file)));
      expect(result.reportJson()).toEqual(JSON.parse(read(join(expected, 'report.json'))));
    });
  }
});
