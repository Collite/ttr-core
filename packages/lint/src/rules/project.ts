// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode } from '@tatrman/parser';
import type { Rule } from '../rule.js';

// Ported from Validator.validateProject (duplicate-definition) and
// Validator.validateDuplicateBindings (duplicate-binding). Both are project
// scope and report against each duplicate's own source location.

const duplicateDefinition: Rule = {
  id: 'duplicate-definition',
  code: DiagnosticCode.DuplicateDefinition,
  category: 'correctness',
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'A qualified name is defined more than once.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    for (const dup of ctx.symbols.duplicates()) {
      // An inline-mapping er2db duplicate is the inline-vs-explicit pairing and
      // is reported by duplicate-mapping instead — skip it here.
      const hasInline = dup.entries.some((e) => e.mappingSource === 'inline');
      if (hasInline) {
        const k = dup.entries[0].kind;
        if (k === 'er2dbEntity' || k === 'er2dbAttribute' || k === 'er2dbRelation') continue;
      }
      // 0.17 — a concept a `concept { }` block declares where a cn def (or another block) declares
      // it too is the sugar merge, or a second grounding: `cn/duplicate-grounding`'s, not this rule's.
      const entries = dup.entries.filter((e) => e.cnOrigin === undefined);
      if (entries.length < 2) continue;
      for (const entry of entries) {
        const others = entries
          .filter((e) => !(e.documentUri === entry.documentUri && e.source.line === entry.source.line))
          .map((e) => `${e.documentUri}:${e.source.line}`)
          .join(', ');
        ctx.report({
          source: entry.source,
          message: `Duplicate definition of '${dup.qname}' (also at ${others})`,
        });
      }
    }
  },
};

const duplicateMapping: Rule = {
  id: 'duplicate-mapping',
  code: DiagnosticCode.DuplicateBinding,
  category: 'correctness',
  scope: 'project',
  defaultSeverity: 'error',
  docs: 'An er2db mapping target is declared in more than one place.',
  check(ctx) {
    if (ctx.scope !== 'project') return;
    for (const qname of ctx.symbols.allQnames()) {
      const entries = ctx.symbols.getAll(qname);
      if (entries.length < 2) continue;
      const firstKind = entries[0].kind;
      const isEr2db =
        firstKind === 'er2dbEntity' || firstKind === 'er2dbAttribute' || firstKind === 'er2dbRelation';
      if (!isEr2db) continue;
      const sources = new Set(entries.map((e) => e.mappingSource ?? 'explicit'));
      if (!sources.has('inline')) continue;
      for (const e of entries) {
        const others = entries
          .filter((other) => !(other.documentUri === e.documentUri && other.source.line === e.source.line))
          .map((o) => `${o.documentUri}:${o.source.line}`)
          .join(', ');
        ctx.report({
          source: e.source,
          message: `Duplicate mapping for "${qname}" — declared in ${entries.length} places: ${others}`,
        });
      }
    }
  },
};

export const PROJECT_RULES: Rule[] = [duplicateDefinition, duplicateMapping];
