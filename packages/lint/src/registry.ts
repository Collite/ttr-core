// SPDX-License-Identifier: Apache-2.0
import type { DiagnosticCode } from '@tatrman/parser';
import type { Rule, RuleCategory, RuleId } from './rule.js';
import { ALL_RULES } from './rules/index.js';

const CATEGORIES: ReadonlySet<RuleCategory> = new Set<RuleCategory>([
  'correctness',
  'references',
  'imports',
  'packages',
  'areas',
  'graph',
  'style',
  'md',
  'semantics',
  'lexicon',
  'cn',
]);

const SEVERITIES: ReadonlySet<string> = new Set(['error', 'warning', 'info']);

/**
 * Codes intentionally shared by more than one rule id (design §5.5): six
 * `required-property-missing` conditions each get a distinct rule id so per-rule
 * config is meaningful, while external consumers still see the one code.
 * `ruleForCode` returns the first-registered rule for such a code.
 */
const SHARED_CODES: ReadonlySet<string> = new Set([
  'ttr/required-property-missing',
]);

function buildRegistry(rules: readonly Rule[]): Map<RuleId, Rule> {
  const map = new Map<RuleId, Rule>();
  const codeCounts = new Map<string, number>();
  for (const r of rules) {
    if (map.has(r.id)) throw new Error(`Duplicate lint rule id: ${r.id}`);
    if (!CATEGORIES.has(r.category)) throw new Error(`Rule ${r.id}: invalid category "${r.category}"`);
    if (!SEVERITIES.has(r.defaultSeverity)) {
      throw new Error(`Rule ${r.id}: invalid defaultSeverity "${r.defaultSeverity}"`);
    }
    const n = (codeCounts.get(r.code) ?? 0) + 1;
    codeCounts.set(r.code, n);
    if (n > 1 && !SHARED_CODES.has(r.code)) {
      throw new Error(`More than one rule maps to code ${r.code} (not in SHARED_CODES)`);
    }
    map.set(r.id, r);
  }
  return map;
}

export const RULES: ReadonlyMap<RuleId, Rule> = buildRegistry(ALL_RULES);

/** The rule for a wire `code`. For a shared code, the first-registered rule. */
export function ruleForCode(code: DiagnosticCode): Rule | undefined {
  for (const r of RULES.values()) if (r.code === code) return r;
  return undefined;
}

export function rulesByCategory(cat: RuleCategory): Rule[] {
  return [...RULES.values()].filter((r) => r.category === cat);
}
