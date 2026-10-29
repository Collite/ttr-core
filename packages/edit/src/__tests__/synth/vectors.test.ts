// SPDX-License-Identifier: Apache-2.0
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseManifest, resolveManifest } from '@tatrman/semantics';
import { parseString } from '@tatrman/parser';
import { synthesize, type EditOp, type EditArgs } from '../../synth/index.js';
import { applyWorkspaceEditToText } from '../../lint-fixes.js';

const vectors = fileURLToPath(new URL('../../../__tests__/synth/vectors/', import.meta.url));
function files(root: string, prefix = root): Record<string, string> {
  return Object.fromEntries(readdirSync(root, { withFileTypes: true }).flatMap(e => {
    const path = join(root, e.name);
    return e.isDirectory() ? Object.entries(files(path, prefix)) : [[relative(prefix, path), readFileSync(path, 'utf8')]];
  }));
}
describe.each(['LF', 'CRLF'])('Studio synthesizer vectors (%s)', ending => {
  for (const op of readdirSync(vectors)) for (const name of readdirSync(join(vectors, op))) {
    it(`${op}/${name}`, () => {
      const dir = join(vectors, op, name);
      const convert = (texts: Record<string,string>) => Object.fromEntries(Object.entries(texts).map(([uri,text]) => [uri,ending === 'CRLF' ? text.replace(/\r?\n/g,'\r\n') : text]));
      const world = convert(files(join(dir, 'world')));
      const command = JSON.parse(readFileSync(join(dir, 'op.json'), 'utf8')) as { op: EditOp; args: EditArgs };
      const manifest = resolveManifest(parseManifest(world['modeler.toml']), '');
      const before = JSON.stringify(world);
      const result = synthesize(command.op, command.args, { files: world, rootUri: '', manifest });
      expect(JSON.stringify(world)).toBe(before);
      if (readdirSync(dir).includes('refusal.json')) {
        expect(result).toEqual(JSON.parse(readFileSync(join(dir, 'refusal.json'), 'utf8')));
        if (op === 'remove-definition' && name === 'refuse-would-orphan') {
          expect(result.ok).toBe(false);
          if (!result.ok) expect(result.refs?.map(r => r.kind).sort()).toEqual(['binding', 'lexicon', 'relation', 'roster']);
        }
        return;
      }
      expect(result.ok, JSON.stringify(result)).toBe(true);
      if (!result.ok) return;
      const expected = readdirSync(dir).includes('expected') ? convert(files(join(dir, 'expected'))) : {};
      expect(result.touched).toEqual(Object.keys(expected).sort());
      const actual: Record<string, string> = {};
      for (const path of result.touched) {
        actual[path] = applyWorkspaceEditToText(world[path] ?? '', result.edit, path);
        if (path.endsWith('.ttrm')) expect(parseString(actual[path], path).errors.filter(e => e.severity === 'error')).toEqual([]);
      }
      expect(actual).toEqual(expected);
    });
  }
  // ttr-core#326 — the Studio's forms pass no `file`: the entity, then one add-term per locale. That path must
  // write exactly what the inline vector writes, files and bytes.
  it('insert-entity/on-query two-step (no file, then add-term per locale)', () => {
    const dir = join(vectors, 'insert-entity', 'on-query');
    const convert = (texts: Record<string,string>) => Object.fromEntries(Object.entries(texts).map(([uri,text]) => [uri,ending === 'CRLF' ? text.replace(/\r?\n/g,'\r\n') : text]));
    let world = convert(files(join(dir, 'world')));
    const manifest = resolveManifest(parseManifest(world['modeler.toml']), '');
    const target = 'hartland.er.entity.returns_review';
    const steps: { op: EditOp; args: EditArgs }[] = [
      { op: 'insert-entity', args: { name: 'returns_review', on: 'er.query.returns_review' } },
      { op: 'add-term', args: { lang: 'en', term: 'returns review', target } },
      { op: 'add-term', args: { lang: 'cs', term: 'přehled reklamací', target } },
    ];
    const touched = new Set<string>();
    for (const step of steps) {
      const result = synthesize(step.op, step.args, { files: world, rootUri: '', manifest });
      expect(result.ok, JSON.stringify(result)).toBe(true);
      if (!result.ok) return;
      world = { ...world };
      for (const path of result.touched) { world[path] = applyWorkspaceEditToText(world[path] ?? '', result.edit, path); touched.add(path); }
    }
    const expected = convert(files(join(dir, 'expected')));
    expect([...touched].sort()).toEqual(Object.keys(expected).sort());
    expect(Object.fromEntries(Object.keys(expected).map(path => [path, world[path]]))).toEqual(expected);
  });
});
