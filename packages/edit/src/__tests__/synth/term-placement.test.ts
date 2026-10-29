// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { parseManifest, resolveManifest } from '@tatrman/semantics';
import { synthesize, type SynthContext } from '../../synth/index.js';
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

  it.each(['model/er/c#.ttrm', 'model/er/a?.ttrm', 'model/er/50%25-off.ttrm', 'model/er/-draft.ttrm'])(
    'falls back to the target\'s model code for an unusable stem: %s', target => {
      const ctx = context({ [target]: shop('er', 'def entity a {}\n') });
      expect(place(ctx, 'en', 'er.entity.a').file).toBe('model/lexicon/en/er.ttrm');
    });
});
