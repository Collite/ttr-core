// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode } from '@tatrman/parser';
import type { Document } from '@tatrman/parser';
import { analyzeSemantics } from '@tatrman/semantics';
import type { SemanticsAnalysis, ProjectSymbolTable } from '@tatrman/semantics';
import type { Rule, DocumentRuleContext } from '../rule.js';

// Grounding Phase 1 (grammar 4.2) — surface the ttr-semantics `semantics { }`
// validator (analyzeSemantics) as lint rules. Each TTR-SEM-2xx code is its own
// rule id (so per-rule severity config is meaningful, the md-rules pattern); the
// shared analysis runs once per (document, symbols) pass and is memoised so the
// twelve rules don't each re-walk the AST.

interface Memo {
  symbols: ProjectSymbolTable | undefined;
  analysis: SemanticsAnalysis;
}
const cache = new WeakMap<Document, Memo>();

function analysisFor(ctx: DocumentRuleContext): SemanticsAnalysis {
  const memo = cache.get(ctx.ast);
  if (memo && memo.symbols === ctx.symbols) return memo.analysis;
  const analysis = analyzeSemantics(ctx.ast, ctx.symbols);
  cache.set(ctx.ast, { symbols: ctx.symbols, analysis });
  return analysis;
}

/**
 * Build one document-scoped rule that reports the analysis diagnostics of `code`.
 *
 * `severity` defaults to error, which every Grounding Phase 1 code is. MS added the first
 * warning-level one (a deprecation is advice, not a defect), so it is a parameter now.
 */
function semRule(
  id: string,
  code: DiagnosticCode,
  docs: string,
  severity: 'error' | 'warning' = 'error',
): Rule {
  return {
    id,
    code,
    category: 'semantics',
    scope: 'document',
    defaultSeverity: severity,
    docs,
    check(ctx) {
      if (ctx.scope !== 'document') return;
      for (const d of analysisFor(ctx).diagnostics) {
        if (d.code !== code) continue;
        ctx.report({ source: d.source, message: d.message, data: d.suggestion ? { suggestion: d.suggestion } : undefined });
      }
    },
  };
}

export const SEMANTICS_RULES: Rule[] = [
  semRule('semantics-unknown-key', DiagnosticCode.SemUnknownKey, 'A semantics block carries a key not valid for its kind/role.'),
  semRule('semantics-unknown-role', DiagnosticCode.SemUnknownRole, 'A semantics block declares a role outside the closed vocabulary.'),
  semRule('semantics-unknown-kind', DiagnosticCode.SemUnknownKind, 'A semantics block declares an entity/table kind outside the closed vocabulary.'),
  semRule('semantics-duplicate-key', DiagnosticCode.SemDuplicateKey, 'A semantics block repeats a key.'),
  semRule('semantics-misplaced-keyword', DiagnosticCode.SemMisplacedKeyword, "`kind` on an attribute/column, or `role` on an entity/table."),
  semRule('semantics-type-constraint', DiagnosticCode.SemTypeConstraint, "A role's declared attribute/column type violates its type constraint."),
  semRule('semantics-completeness', DiagnosticCode.SemCompleteness, 'An entity/table kind is missing a required role (or has too many).'),
  semRule('semantics-multiple-event-date', DiagnosticCode.SemMultipleEventDate, 'An entity/table has more than one event_date.'),
  semRule('semantics-bad-period-ref', DiagnosticCode.SemBadPeriodRef, 'A `period:` reference is dangling or not a period_table kind.'),
  semRule('semantics-bad-currency-ref', DiagnosticCode.SemBadCurrencyRef, 'A `currency:` reference is dangling or not a currency_code sibling.'),
  semRule('semantics-geo-pair', DiagnosticCode.SemGeoPair, 'geo_lat/geo_lon must appear together (or use geo_point instead).'),
  semRule('semantics-valid-pair', DiagnosticCode.SemValidPair, 'valid_from/valid_to must appear as a pair (both or neither).'),
  // MS (vocabulary v3) — the mention facet.
  semRule('semantics-mention-ref-unresolved', DiagnosticCode.SemMentionRefUnresolved, 'A `name:`/`code:`/measure reference does not name an attribute of its own entity/table.'),
  semRule('semantics-measure-not-numeric', DiagnosticCode.SemMeasureNotNumeric, 'A declared measure is not a numeric attribute/column.'),
  semRule('semantics-measure-duplicate', DiagnosticCode.SemMeasureDuplicate, 'The same attribute is listed twice in `measures:`.'),
  semRule('semantics-bad-aggregation', DiagnosticCode.SemBadAggregation, 'A measure aggregation is outside the closed vocabulary (sum|avg|min|max|count|last).'),
  semRule('semantics-mention-shape', DiagnosticCode.SemMentionShape, 'A `name:`/`code:`/`measures:` value is not the shape that key takes.'),
  semRule('semantics-legacy-mention-mismatch', DiagnosticCode.SemLegacyMentionMismatch, 'Legacy `nameAttribute:`/`codeAttribute:` disagrees with the semantics block.'),
  // LP review-103 (D4) — the code attribute's value pattern.
  semRule('semantics-bad-code-pattern', DiagnosticCode.SemBadCodePattern, '`code_pattern:` is not a valid (Java) regular expression, is empty, or sits in a block with no `code:`.'),
  // The one warning: a deprecation is advice about style, not a defect in the model.
  semRule('semantics-legacy-mention-deprecated', DiagnosticCode.SemLegacyMentionDeprecated, 'Legacy `nameAttribute:`/`codeAttribute:` is superseded by `semantics { name: · code: }` — quoting a value reads the column from the semantics block, and the legacy property only as a fallback.', 'warning'),
];
