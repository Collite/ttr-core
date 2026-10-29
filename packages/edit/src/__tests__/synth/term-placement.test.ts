// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { parseManifest, resolveManifest } from '@tatrman/semantics';
import { synthesize, type EditArgs, type EditOp, type SynthContext } from '../../synth/index.js';
import { applyWorkspaceEditToText } from '../../lint-fixes.js';

// ttr-core#326 — where `add-term` puts a term when the caller names no file.
function context(files: Record<string, string>, rootUri = ''): SynthContext {
  return { rootUri, files, manifest: resolveManifest(parseManifest('[packages]\nlayout="off"\n'), '') };
}
function place(ctx: SynthContext, lang: 'en' | 'cs', target: string): { file: string; text: string } {
  const r = synthesize('add-term', { lang, term: `${target} ${lang}`, target }, ctx);
  expect(r.ok, JSON.stringify(r)).toBe(true);
  if (!r.ok) throw new Error(r.message);
  expect(r.touched).toHaveLength(1);
  const file = r.touched[0];
  return { file, text: applyWorkspaceEditToText(ctx.files[file] ?? '', r.edit, file) };
}
const shop = (model: string, body = '') => `package shop\nmodel ${model}\n${body}`;
const without = (files: Record<string, string>, key: string) => Object.fromEntries(Object.entries(files).filter(([k]) => k !== key));
// Runs the steps one after another, each on the files the last one left; returns every touched file's final text.
function steps(files: Record<string, string>, ...ops: [EditOp, EditArgs][]): Record<string, string> {
  let world = files;
  const touched = new Set<string>();
  for (const [op, args] of ops) {
    const r = synthesize(op, args, context(world));
    expect(r.ok, JSON.stringify(r)).toBe(true);
    if (!r.ok) throw new Error(r.message);
    world = { ...world };
    for (const path of r.touched) { world[path] = applyWorkspaceEditToText(world[path] ?? '', r.edit, path); touched.add(path); }
  }
  return Object.fromEntries([...touched].sort().map(path => [path, world[path]]));
}

describe('term placement without a file', () => {
  describe('a package keeps its terms in its own lexicon folder', () => {
    const multi = {
      'model/er/x.ttrm': 'package a\nmodel er\ndef entity foo {}\n',
      'model/lexicon/en/x.ttrm': 'package a\nmodel lexicon locale en\ndef term foo_en { for: er.entity.foo, forms: ["foo"] }\n',
      'model/b/er/x.ttrm': 'package b\nmodel er\ndef entity bar {}\n',
      'model/b/lexicon/en/terms.ttrm': 'package b\nmodel lexicon locale en\n',
    };
    it.each([
      ['beside another package\'s file of the same name', multi],
      ['with no root file at all', without(multi, 'model/lexicon/en/x.ttrm')],
    ])('%s', (_, files) => {
      const { file, text } = place(context(files), 'en', 'b.er.entity.bar');
      expect(file).toBe('model/b/lexicon/en/x.ttrm');
      expect(text).toContain('package b\n');
    });
    it('gives way to the package\'s first file when another package holds the derived path', () => {
      const ctx = context({ ...without(multi, 'model/b/lexicon/en/terms.ttrm'), 'model/lexicon/en/b-terms.ttrm': 'package b\nmodel lexicon locale en\n' });
      expect(place(ctx, 'en', 'b.er.entity.bar').file).toBe('model/lexicon/en/b-terms.ttrm');
    });
  });

  it('counts only a def term as holding the target\'s terms, never an example', () => {
    const ctx = context({
      'model/er/r.ttrm': shop('er', 'def entity r {}\n'),
      'model/lexicon/en/examples.ttrm': shop('lexicon locale en', 'def example r_q { for: er.entity.r, text: "how many r" }\n'),
    });
    expect(place(ctx, 'en', 'er.entity.r').file).toBe('model/lexicon/en/r.ttrm');
    expect(place(ctx, 'cs', 'er.entity.r').file).toBe('model/lexicon/cs/r.ttrm');
  });

  describe('the twin of a file in another locale', () => {
    it('keeps the holder\'s subfolder', () => {
      const ctx = context({
        'model/er/s.ttrm': shop('er', 'def entity s {}\n'),
        'model/lexicon/en/topics/sales.ttrm': shop('lexicon locale en', 'def term s_en { for: er.entity.s, forms: ["s"] }\n'),
      });
      const { file, text } = place(ctx, 'cs', 'er.entity.s');
      expect(file).toBe('model/lexicon/cs/topics/sales.ttrm');
      expect(text).toContain('model lexicon locale cs\n');
    });
    it('keeps an encoded holder key\'s folder', () => {
      const root = 'file:///My%20Model';
      const ctx = context({
        [`${root}/model/er/s.ttrm`]: shop('er', 'def entity s {}\n'),
        [`${root}/model/lexicon/en/sales%20team.ttrm`]: shop('lexicon locale en', 'def term s_en { for: er.entity.s, forms: ["s"] }\n'),
      }, root);
      expect(place(ctx, 'cs', 'er.entity.s').file).toBe(`${root}/model/lexicon/cs/sales%20team.ttrm`);
    });
    it('is skipped for a holder with no locale, and the stem names the file', () => {
      const ctx = context({
        'model/er/s.ttrm': shop('er', 'def entity s {}\n'),
        'model/lexicon/shared.ttrm': shop('lexicon', 'def term s_any { for: er.entity.s, forms: ["s"] }\n'),
      });
      expect(place(ctx, 'cs', 'er.entity.s').file).toBe('model/lexicon/cs/s.ttrm');
    });
  });

  it.each(['model/er/c#.ttrm', 'model/er/a?.ttrm', 'model/er/50%25-off.ttrm', 'model/er/-draft.ttrm', 'model/er/a%5Cb.ttrm'])(
    'falls back to the target\'s model code for an unusable stem: %s', target => {
      const ctx = context({ [target]: shop('er', 'def entity a {}\n') });
      expect(place(ctx, 'en', 'er.entity.a').file).toBe('model/lexicon/en/er.ttrm');
    });

  describe('a package with no lexicon file in that locale', () => {
    const a = {
      'model/er/x.ttrm': 'package a\nmodel er\ndef entity foo {}\n',
      'model/lexicon/en/x.ttrm': 'package a\nmodel lexicon locale en\ndef term foo_en { for: er.entity.foo, forms: ["foo"] }\n',
      'model/b/er/x.ttrm': 'package b\nmodel er\ndef entity bar {}\n',
    };
    it('takes the folder of its file in another locale', () => {
      const { file, text } = place(context({ ...a, 'model/b/lexicon/cs/terms.ttrm': 'package b\nmodel lexicon locale cs\n' }), 'en', 'b.er.entity.bar');
      expect(file).toBe('model/b/lexicon/en/x.ttrm');
      expect(text).toContain('package b\n');
    });
    it('without any lexicon file, steps around another package\'s stem file to the model code', () => {
      expect(place(context(a), 'en', 'b.er.entity.bar').file).toBe('model/lexicon/en/er.ttrm');
    });
  });

  it('compares a raw root with encoded keys decoded, and spells a new key like them', () => {
    const ctx = context({
      'file:///My%20Model/model/er/s.ttrm': shop('er', 'def entity s {}\n'),
      'file:///My%20Model/model/lexicon/en/a.ttrm': shop('lexicon locale en'),
    }, 'file:///My Model');
    expect(place(ctx, 'en', 'er.entity.s').file).toBe('file:///My%20Model/model/lexicon/en/s.ttrm');
  });

  // Inline terms place en, then cs, in one run; each must see what the other wrote, as the two-step path sees it on disk.
  describe('inline terms land where the two-step path puts them', () => {
    const q = shop('er', 'def query rq { sourceText: "select 1 as id" }\n');
    const lexicon = (locale: string, pkg = 'shop') => `package ${pkg}\nmodel lexicon locale ${locale}\n`;
    const worlds: [string, Record<string, string>][] = [
      ['locales share a folder', { 'model/er/r.ttrm': q, 'model/lexicon/terms-cs.ttrm': lexicon('cs'), 'model/lexicon/terms-en.ttrm': lexicon('en') }],
      ['the en files sit in a subfolder', { 'model/er/r.ttrm': q, 'model/lexicon/en/topics/a.ttrm': lexicon('en'), 'model/lexicon/cs/b.ttrm': lexicon('cs') }],
      ['en gives way to the package\'s first file', { 'model/er/r.ttrm': q, 'model/lexicon/en/r.ttrm': lexicon('en', 'other'), 'model/lexicon/en/aaa.ttrm': lexicon('en'), 'model/lexicon/cs/aaa.ttrm': lexicon('cs') }],
    ];
    it.each(worlds)('%s', (_, files) => {
      const inline = steps(files, ['insert-entity', { name: 'rr', on: 'er.query.rq', terms: { en: 'rr en', cs: 'rr cs' } }]);
      const twoStep = steps(files,
        ['insert-entity', { name: 'rr', on: 'er.query.rq' }],
        ['add-term', { lang: 'en', term: 'rr en', target: 'er.entity.rr' }],
        ['add-term', { lang: 'cs', term: 'rr cs', target: 'er.entity.rr' }]);
      expect(inline).toEqual(twoStep);
    });
    it('never puts a cs term into the en file it just created', () => {
      const out = steps(worlds[0][1], ['insert-entity', { name: 'rr', on: 'er.query.rq', terms: { en: 'rr en', cs: 'rr cs' } }]);
      expect(out['model/lexicon/r.ttrm']).toContain('model lexicon locale en\n');
      expect(out['model/lexicon/r.ttrm']).toContain('rr_en');
      expect(out['model/lexicon/r.ttrm']).not.toContain('rr_cs');
      expect(out['model/lexicon/terms-cs.ttrm']).toContain('def term rr_cs');
    });
    it('steps around a file of another model that the same run created', () => {
      const out = steps({ 'model/er/r.ttrm': q }, ['insert-entity', { name: 'rr', on: 'er.query.rq', file: 'model/lexicon/en/rr.ttrm', terms: { en: 'rr en' } }]);
      expect(out['model/lexicon/en/rr.ttrm']).not.toContain('def term');
      expect(out['model/lexicon/en/er.ttrm']).toContain('def term rr_en');
    });
  });
});
