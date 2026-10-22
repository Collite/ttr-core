// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode } from '@tatrman/parser';
import { mdHopReport } from '@tatrman/semantics';
import type { MdHopReport } from '@tatrman/semantics';
import { validationFor } from './cn.js';
import { cnDocumentsFor } from './project-documents.js';
import type { Rule } from '../rule.js';

/** Project analysis is shared by the rule family; the runner applies configured severity and suppression. */
function hopRule(id: string, code: DiagnosticCode, severity: Rule['defaultSeverity']): Rule {
  return { id, code, category: 'md', scope: 'project', defaultSeverity: severity,
    docs: 'The resolved MD → ER hop and derived warehouse roles. Hop findings default to concept-grounded MD elements; ttr md hop --all reports the whole model.',
    check(ctx) {
      if (ctx.scope !== 'project') return;
      const docs = cnDocumentsFor(ctx);
      let report = ctx.cache.get('md:hop') as MdHopReport | undefined;
      if (!report) { report = mdHopReport(docs, ctx.resolver, false, undefined, validationFor(ctx)); ctx.cache.set('md:hop', report); }
      for (const issue of report.issues) {
        if (issue.slug !== code) continue;
        const direct = ctx.symbols.get(issue.element);
        if (direct) {
          ctx.report({ source: { ...direct.source, file: direct.documentUri }, message: issue.message });
          continue;
        }
        // Grain refs are scoped by their cubelet and have no independent symbol.
        let prefix = issue.element;
        while (prefix.includes('.')) {
          prefix = prefix.slice(0, prefix.lastIndexOf('.'));
          const owner = ctx.symbols.get(prefix);
          if (!owner) continue;
          ctx.report({ source: { ...owner.source, file: owner.documentUri }, message: issue.message });
          break;
        }
      }
    },
  };
}
export const MD_HOP_RULES = [
  hopRule('md-er-unmapped', DiagnosticCode.MdErUnmapped, 'warning'),
  hopRule('md-er-ambiguous', DiagnosticCode.MdErAmbiguous, 'warning'),
  hopRule('md-er-hop-override', DiagnosticCode.MdErHopOverride, 'info'),
  hopRule('md-er-lineage-opaque', DiagnosticCode.MdErLineageOpaque, 'info'),
  hopRule('md-role-claim-mismatch', DiagnosticCode.MdRoleClaimMismatch, 'warning'),
];
