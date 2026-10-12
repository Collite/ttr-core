// SPDX-License-Identifier: Apache-2.0
// VL-P0 T6 — the shared layout vectors (contracts V-46): `packages/package-sdk/schemas/layout-vectors/`.
// The viewer's half: the YAML writer reproduces every vector byte for byte and its reader gives back the
// document; resolution gives back `expect.json`. The document and the TTR form are held to the same files
// by `@tatrman/parser/layout`'s tests, the JVM implementation by ttr-parser / ttr-writer / ttr-metadata.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import type { LayoutDocument } from '@tatrman/parser/layout';
import { readYaml, writeYaml } from '../layout-yaml.js';
import { resolutionToJson, resolveRefs, type LayoutModelIndex } from '../resolve.js';

const VECTORS = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', 'package-sdk', 'schemas', 'layout-vectors');
const cases = readdirSync(VECTORS).filter((d) => statSync(join(VECTORS, d)).isDirectory()).sort();
const text = (c: string, f: string) => readFileSync(join(VECTORS, c, f), 'utf8');
const json = <T>(c: string, f: string): T => JSON.parse(text(c, f)) as T;

describe('layout vectors — the YAML form and the resolution', () => {
  it('has the cases (≥ 8)', () => {
    expect(cases.length).toBeGreaterThanOrEqual(8);
  });

  describe.each(cases)('%s', (c) => {
    const doc = json<LayoutDocument>(c, 'document.json');

    it('writeYaml(document) == layout.yaml, byte for byte; readYaml gives the document back', () => {
      expect(writeYaml(doc)).toBe(text(c, 'layout.yaml'));
      const r = readYaml(text(c, 'layout.yaml'), { fileName: `${doc.name}.yaml` });
      expect(r.ok && r.doc).toEqual(doc);
    });

    it('resolution of document.objects against model.json == expect.json', () => {
      const index = json<LayoutModelIndex>(c, 'model.json');
      expect(resolutionToJson(resolveRefs(doc.objects, index, doc.model))).toEqual(json(c, 'expect.json'));
    });
  });
});
