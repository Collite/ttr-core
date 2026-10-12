// SPDX-License-Identifier: Apache-2.0
// VL-P0 T6 — the shared layout vectors (contracts V-46): `packages/package-sdk/schemas/layout-vectors/`.
// The TS writer reproduces every vector byte for byte; both readers give back the document; resolution
// gives back `expect.json`. The JVM implementation asserts the same against the same files.

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import { canonicalize, type LayoutDocument } from '../layout-document.js';
import { readYaml, writeYaml } from '../layout-yaml.js';
import { mergeTtr, readTtrg, writeTtrg } from '../layout-ttr.js';
import { resolutionToJson, resolveRefs, type LayoutModelIndex } from '../resolve.js';

const VECTORS = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', 'package-sdk', 'schemas', 'layout-vectors');
const cases = readdirSync(VECTORS).filter((d) => statSync(join(VECTORS, d)).isDirectory()).sort();
const text = (c: string, f: string) => readFileSync(join(VECTORS, c, f), 'utf8');
const json = <T>(c: string, f: string): T => JSON.parse(text(c, f)) as T;

/** What a `.ttrg` carries: no skin, no collapsed (V-10). */
const ttrPart = ({ name, model, objects, positions }: LayoutDocument) => ({ name, model, objects, positions });

describe('layout vectors', () => {
  it('has the contracted cases (≥ 8)', () => {
    expect(cases.length).toBeGreaterThanOrEqual(8);
    for (const required of ['minimal', 'positions', 'diacritics', 'bare-names', 'ambiguous', 'missing', 'relation-ignored', 'sidecar-wins', 'db-tables']) {
      expect(cases).toContain(required);
    }
  });

  describe.each(cases)('%s', (c) => {
    const doc = json<LayoutDocument>(c, 'document.json');

    it('document.json is canonical', () => {
      expect(canonicalize(doc).doc).toEqual(doc);
    });

    it('writeYaml(document) == layout.yaml, byte for byte; readYaml gives the document back', () => {
      expect(writeYaml(doc)).toBe(text(c, 'layout.yaml'));
      const r = readYaml(text(c, 'layout.yaml'), { fileName: `${doc.name}.yaml` });
      expect(r.ok && r.doc).toEqual(doc);
    });

    it('writeTtrg(document) == layout.ttrg, byte for byte; readTtrg gives the document back (what TTR carries)', () => {
      expect(writeTtrg(doc)).toBe(text(c, 'layout.ttrg'));
      const r = readTtrg(text(c, 'layout.ttrg'));
      expect(r.ok && r.doc).toEqual(ttrPart(doc));
    });

    it('resolution of document.objects against model.json == expect.json', () => {
      const index = json<LayoutModelIndex>(c, 'model.json');
      expect(resolutionToJson(resolveRefs(doc.objects, index, doc.model))).toEqual(json(c, 'expect.json'));
    });

    if (existsSync(join(VECTORS, c, 'input.ttrg'))) {
      it('input.ttrg (+ input.ttrl) reads to the document', () => {
        const sidecar = existsSync(join(VECTORS, c, 'input.ttrl')) ? text(c, 'input.ttrl') : undefined;
        const r = mergeTtr(text(c, 'input.ttrg'), sidecar);
        expect(r.ok && r.doc).toEqual(doc);
        expect(r.ok && r.positionsFrom).toBe(sidecar ? 'sidecar' : 'in-file');
      });
    }
  });
});
