// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { parseString } from '@tatrman/parser';
import { DiagnosticCode } from '@tatrman/parser';
import { desugarLexicon } from '../lexicon/desugar.js';

// RG-P4.S1.T3 — desugar (RS-9 T1). Inline `lexicon{}` sugar desugars to canonical
// `term` entries identical (in the resolved vocabulary sense) to hand-written
// canonical `def term` entries. The conflict rule mirrors the binding pattern —
// inline + canonical entries synthesize into ONE namespace tagged by origin
// (`mappingSource` precedent in `mapping-synthesizer.ts`), but for vocabulary the
// merge is a union-of-forms per target with a diagnostic on exact-duplicate forms.

/** The vocabulary projection used for equivalence: (kind, target, sorted forms). */
function vocab(entry: { entryKind: string; target: string; forms?: string[] }) {
  return { entryKind: entry.entryKind, target: entry.target, forms: [...(entry.forms ?? [])].sort() };
}

const CANONICAL = `model lexicon
def term trzba { for: md.measure.net, forms: ["tržba", "obrat", "utržit"] }
`;

const INLINE = `model md
def measure net { domain: md.Money, class: additive, aggregation: sum, lexicon { terms: ["tržba", "obrat", "utržit"] } }
`;

describe('lexicon desugar — inline↔canonical equivalence (RS-9 T1)', () => {
  it('inline `lexicon{terms}` on a measure desugars to a canonical term entry set', () => {
    const canonical = desugarLexicon(parseString(CANONICAL, 'file:///lex.ttrm').ast!);
    const inline = desugarLexicon(parseString(INLINE, 'file:///md.ttrm').ast!);

    // Both produce exactly one term entry targeting md.measure.net with the same forms.
    expect(canonical.entries).toHaveLength(1);
    expect(inline.entries).toHaveLength(1);
    expect(vocab(inline.entries[0])).toEqual(vocab(canonical.entries[0]));
    expect(inline.entries[0].target).toBe('md.measure.net');
    expect(inline.entries[0].entryKind).toBe('term');
  });

  it('inline entries are tagged origin=inline; canonical entries origin=canonical', () => {
    const inline = desugarLexicon(parseString(INLINE, 'file:///md.ttrm').ast!);
    const canonical = desugarLexicon(parseString(CANONICAL, 'file:///lex.ttrm').ast!);
    expect(inline.entries[0].origin).toBe('inline');
    expect(canonical.entries[0].origin).toBe('canonical');
  });

  it('inline sugar defaults to the base/deployment locale (undefined); canonical rides the unit header locale (RS-11)', () => {
    const inline = desugarLexicon(parseString(INLINE, 'file:///md.ttrm').ast!);
    expect(inline.entries[0].locale).toBeUndefined();

    const withLocale = desugarLexicon(
      parseString('model lexicon locale cs\ndef term t { for: md.measure.net, forms: ["tržba"] }', 'file:///lex.ttrm').ast!,
    );
    expect(withLocale.entries[0].locale).toBe('cs');
  });
});

describe('lexicon desugar — merge + conflict (binding discipline)', () => {
  it('canonical + inline entries for the same target merge (union of forms) in byTarget', () => {
    const src = `model md
def measure net { domain: md.Money, lexicon { terms: ["obrat"] } }
def term extra { for: md.measure.net, forms: ["tržba", "utržit"] }
`;
    const a = desugarLexicon(parseString(src, 'file:///md.ttrm').ast!);
    const merged = a.byTarget.get('md.measure.net') ?? [];
    const forms = new Set(merged.flatMap((e) => e.forms ?? []));
    expect(forms).toEqual(new Set(['obrat', 'tržba', 'utržit']));
  });

  it('an exact-duplicate surface form for one target warns (LexiconDuplicateForm)', () => {
    const src = `model md
def measure net { domain: md.Money, lexicon { terms: ["tržba"] } }
def term extra { for: md.measure.net, forms: ["tržba"] }
`;
    const a = desugarLexicon(parseString(src, 'file:///md.ttrm').ast!);
    const dup = a.diagnostics.filter((d) => d.code === DiagnosticCode.LexiconDuplicateForm);
    expect(dup).toHaveLength(1);
    expect(dup[0].severity).toBe('warning');
  });
});

describe('lexicon desugar — inline patterns/examples (idPart keys, v4.4)', () => {
  it('inline `lexicon { patterns, examples }` desugars to pattern/example entries', () => {
    const src = `model er
def entity q { lexicon { terms: ["dotaz"], patterns: ["název .*"], examples: ["Kolik?"] } }
`;
    const a = desugarLexicon(parseString(src, 'file:///er.ttrm').ast!);
    expect(a.entries.find((e) => e.entryKind === 'pattern')?.match).toBe('název .*');
    expect(a.entries.find((e) => e.entryKind === 'example')?.text).toBe('Kolik?');
    expect(a.entries.find((e) => e.entryKind === 'term')?.forms).toEqual(['dotaz']);
  });
});

describe('lexicon desugar — nested carriers (attributes / columns)', () => {
  it('inline `lexicon{}` on a NESTED entity attribute produces a term entry targeting parent.attr', () => {
    const src = `model er
def entity acct { attributes: [def attribute status { lexicon { terms: ["stav", "účet"] } }] }
`;
    const a = desugarLexicon(parseString(src, 'file:///er.ttrm').ast!);
    const term = a.entries.find((e) => e.entryKind === 'term' && e.target.endsWith('.status'));
    expect(term, 'nested-attribute lexicon must not be silently dropped').toBeDefined();
    expect(new Set(term!.forms)).toEqual(new Set(['stav', 'účet']));
    expect(term!.origin).toBe('inline');
  });

  it('inline `lexicon{}` on a NESTED table column produces a term entry targeting parent.column', () => {
    const src = `model db
def table t { columns: [def column c { type: int, lexicon { terms: ["kolona"] } }] }
`;
    const a = desugarLexicon(parseString(src, 'file:///db.ttrm').ast!);
    const term = a.entries.find((e) => e.entryKind === 'term' && e.target.endsWith('.c'));
    expect(term, 'nested-column lexicon must not be silently dropped').toBeDefined();
    expect(term!.forms).toEqual(['kolona']);
  });

  it('legacy `search{}` on a nested attribute migrates (with deprecation) — not dropped', () => {
    const src = `model er
def entity acct { attributes: [def attribute status { search { aliases: ["stav"] } }] }
`;
    const a = desugarLexicon(parseString(src, 'file:///er.ttrm').ast!);
    const term = a.entries.find((e) => e.entryKind === 'term' && e.target.endsWith('.status'));
    expect(term?.origin).toBe('legacy');
    expect(a.diagnostics.some((d) => d.code === DiagnosticCode.LexiconLegacyAliases)).toBe(true);
  });
});

describe('lexicon desugar — entry shape + placement diagnostics', () => {
  it('pattern/example canonical entries carry match/text and desugar with their kind', () => {
    const src = `model lexicon
def pattern nazev { for: db.query.by_name, match: "název .*" }
def example q1 { for: md.cubelet.sales, text: "Kolik jsme utržili" }
`;
    const a = desugarLexicon(parseString(src, 'file:///lex.ttrm').ast!);
    const pattern = a.entries.find((e) => e.entryKind === 'pattern')!;
    const example = a.entries.find((e) => e.entryKind === 'example')!;
    expect(pattern.match).toBe('název .*');
    expect(pattern.target).toBe('db.query.by_name');
    expect(example.text).toBe('Kolik jsme utržili');
  });

  it('a term with no `for:` target → LexiconMissingTarget', () => {
    const a = desugarLexicon(parseString('model lexicon\ndef term t { forms: ["x"] }', 'file:///lex.ttrm').ast!);
    expect(a.diagnostics.some((d) => d.code === DiagnosticCode.LexiconMissingTarget)).toBe(true);
  });

  it('a term with no `forms:` → LexiconEntryFieldMissing; a pattern needs match; an example needs text', () => {
    const a = desugarLexicon(parseString('model lexicon\ndef term t { for: md.measure.net }', 'file:///lex.ttrm').ast!);
    expect(a.diagnostics.some((d) => d.code === DiagnosticCode.LexiconEntryFieldMissing)).toBe(true);
  });

  it('a lexicon def outside `model lexicon` → LexiconWrongModelKind (world-validate precedent)', () => {
    // A bare `def term` in a db model is misplaced (canonical lexicon entries live
    // in `model lexicon`); the inline sugar is the in-model carrier instead.
    const a = desugarLexicon(parseString('model db\ndef term t { for: md.measure.net, forms: ["x"] }', 'file:///db.ttrm').ast!);
    expect(a.diagnostics.some((d) => d.code === DiagnosticCode.LexiconWrongModelKind)).toBe(true);
  });

  it('`locale` on a non-lexicon model → LexiconLocaleOnNonLexicon', () => {
    const a = desugarLexicon(parseString('model db locale cs\ndef table t { }', 'file:///db.ttrm').ast!);
    expect(a.diagnostics.some((d) => d.code === DiagnosticCode.LexiconLocaleOnNonLexicon)).toBe(true);
  });
});

it('retains canonical index policy and leaves ordinary and inline entries absent', () => {
  const canonical = desugarLexicon(parseString(`model lexicon
    def term secure { for: er.entity.Customer, indexSecurity: applied }
    def term ordinary { for: er.entity.Customer }
  `).ast!);
  expect(canonical.entries[0].indexSecurity).toBe('applied');
  expect(canonical.entries[1].indexSecurity).toBeUndefined();
  expect(desugarLexicon(parseString(INLINE).ast!).entries[0].indexSecurity).toBeUndefined();
});
