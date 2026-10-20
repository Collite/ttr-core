// SPDX-License-Identifier: Apache-2.0
// v4.4 TTR-M lexicon surface (RG-P4, RS-9..11). The canonical, desugared shape
// that every consumer reads (snapshot, resolver registry, meta doors — RS-9 T3):
// inline `lexicon{}` sugar and standalone `def term|pattern|example` entries both
// project into this ONE shape before anything downstream sees them.

import type { DiagnosticCode } from '@tatrman/parser';
import type { Reference, SourceLocation } from '@tatrman/parser';

/**
 * A canonical lexicon entry — the desugar target. Inline sugar and standalone
 * canonical defs converge here (the binding pattern: one namespace, tagged by
 * `origin`). `locale === undefined` means the deployment/base locale (RS-11 —
 * inline sugar's default); an explicit `model lexicon locale <id>` sets it.
 */
export interface CanonicalLexiconEntry {
  entryKind: 'term' | 'pattern' | 'example';
  /** The entry def name (canonical) or the carrier def name (inline). */
  name: string;
  /** The resolved-ish target dotted path (e.g. `md.measure.net`). */
  target: string;
  /** Span-carrying target ref for goto/hover (canonical entries only). */
  targetRef?: Reference;
  /** Explicit unit locale, or undefined = base/deployment locale (RS-11). */
  locale?: string;
  /** `term` — surface forms. */
  forms?: string[];
  /** `pattern` — regex. */
  match?: string;
  /** `example` — utterance. */
  text?: string;
  /**
   * 0.17 — `default: true`: the default sense of a bare form (cn contracts §1.5; ≤ 1 per bare term per
   * locale, `cn/duplicate-default-sense`). On an inline entry, written in the `lexicon { }` block.
   */
  default?: boolean;
  /** 0.17 — `sense_priority: <int>`: higher wins (cn contracts §1.5, step 4 of the resolution order). */
  sensePriority?: number;
  /** How the entry was declared (the `mappingSource` precedent). `legacy` =
   * migrated from a deprecated `search{}` / entity `aliases` form (RS-32);
   * `valueLabels` = member vocabulary from a coded attribute (A4-β). */
  origin: 'canonical' | 'inline' | 'legacy' | 'valueLabels';
  source: SourceLocation;
}

export interface LexiconDiagnostic {
  code: DiagnosticCode;
  message: string;
  severity: 'error' | 'warning';
  source: SourceLocation;
}

export interface LexiconAnalysis {
  /** All entries, in document order (canonical + desugared-inline). */
  entries: CanonicalLexiconEntry[];
  /** Entries grouped by target path — the merged vocabulary view (union of forms). */
  byTarget: Map<string, CanonicalLexiconEntry[]>;
  diagnostics: LexiconDiagnostic[];
}
