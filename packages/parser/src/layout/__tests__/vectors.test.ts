// SPDX-License-Identifier: Apache-2.0
// The shared layout vectors (VL contracts V-46): `packages/package-sdk/schemas/layout-vectors/`, read in
// place. The document half: every `document.json` is canonical; the TTR writer reproduces `layout.ttrg`
// byte for byte — or, with no `layout.ttrg`, refuses (A-VL-5); the readers give the document back. The
// YAML form and the resolution are held to the same vectors by the viewer (`@tatrman/designer`), the JVM
// implementation by ttr-parser / ttr-writer / ttr-metadata.

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import { canonicalize, type LayoutDocument } from '../layout-document.js';
import { mergeTtr, readTtrg, ttrUnwritable, writeTtrg } from '../layout-ttr.js';

const VECTORS = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', 'package-sdk', 'schemas', 'layout-vectors');
const cases = readdirSync(VECTORS).filter((d) => statSync(join(VECTORS, d)).isDirectory()).sort();
const text = (c: string, f: string) => readFileSync(join(VECTORS, c, f), 'utf8');
const json = <T>(c: string, f: string): T => JSON.parse(text(c, f)) as T;

/** What a `.ttrg` carries: no skin, no collapsed (V-10). */
const ttrPart = ({ name, model, objects, positions }: LayoutDocument) => ({ name, model, objects, positions });

describe('layout vectors — the document and its TTR form', () => {
  it('has the contracted cases (≥ 8)', () => {
    expect(cases.length).toBeGreaterThanOrEqual(8);
    for (const required of ['minimal', 'positions', 'diacritics', 'bare-names', 'ambiguous', 'missing', 'not-an-id', 'relation-ignored', 'sidecar-wins', 'db-tables']) {
      expect(cases).toContain(required);
    }
  });

  describe.each(cases)('%s', (c) => {
    const doc = json<LayoutDocument>(c, 'document.json');

    it('document.json is canonical', () => {
      expect(canonicalize(doc).doc).toEqual(doc);
    });

    if (existsSync(join(VECTORS, c, 'layout.ttrg'))) {
      it('writeTtrg(document) == layout.ttrg, byte for byte; readTtrg gives the document back (what TTR carries)', () => {
        expect(ttrUnwritable(doc)).toEqual([]);
        expect(writeTtrg(doc)).toBe(text(c, 'layout.ttrg'));
        const r = readTtrg(text(c, 'layout.ttrg'));
        expect(r.ok && r.doc).toEqual(ttrPart(doc));
      });
    } else {
      it('no layout.ttrg: the document has no TTR form — writeTtrg refuses, naming the refs (A-VL-5)', () => {
        const refs = ttrUnwritable(doc);
        expect(refs.length).toBeGreaterThan(0);
        expect(() => writeTtrg(doc)).toThrow(`${doc.name} has no TTR form: ${refs.map((r) => JSON.stringify(r)).join(', ')}`);
      });
    }

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
