// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { RULES, ruleForCode, rulesByCategory } from '../registry.js';
import type { RuleCategory } from '../rule.js';

const VALID_CATEGORIES: RuleCategory[] = [
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
];
const VALID_SEVERITIES = new Set(['error', 'warning', 'info']);

describe('rule registry', () => {
  it('has unique rule ids', () => {
    const ids = [...RULES.values()].map((r) => r.id);
    expect(new Set(ids).size).toBe(ids.length);
    // The Map is keyed by id, so its key must equal the rule's id.
    for (const [key, rule] of RULES) expect(key).toBe(rule.id);
  });

  it('every rule has a valid category and default severity', () => {
    for (const rule of RULES.values()) {
      expect(VALID_CATEGORIES).toContain(rule.category);
      expect(VALID_SEVERITIES.has(rule.defaultSeverity)).toBe(true);
      // Rule codes are namespaced: ttr/* (core), md/* and the one er/* MD code, cn/* (0.17,
      // the conceptual model), plus the stable TTR-SEM-2xx grounding family (grammar 4.2 —
      // these are the cross-repo wire codes mirrored by ai-platform's proto enums).
      expect(/^(ttr|md|er|cn)\/|^TTR-SEM-\d{3}$/.test(rule.code)).toBe(true);
    }
  });

  it('maps at most one rule per non-shared DiagnosticCode', () => {
    const SHARED = new Set(['ttr/required-property-missing']);
    const counts = new Map<string, number>();
    for (const rule of RULES.values()) counts.set(rule.code, (counts.get(rule.code) ?? 0) + 1);
    for (const [code, n] of counts) {
      if (!SHARED.has(code)) expect(n, `code ${code} has ${n} rules`).toBeLessThanOrEqual(1);
    }
  });

  it('MH T1 — the collision rule is registered, project-scoped, warning by default', () => {
    const rule = RULES.get('lexicon-form-collides-with-name');
    expect(rule).toBeDefined();
    expect(rule!.code).toBe('ttr/lexicon-form-collides-with-name');
    expect(rule!.category).toBe('lexicon');
    expect(rule!.scope).toBe('project');
    expect(rule!.defaultSeverity).toBe('warning');
  });

  it('ruleForCode returns a rule whose code matches', () => {
    for (const rule of RULES.values()) {
      const found = ruleForCode(rule.code);
      expect(found).toBeDefined();
      expect(found!.code).toBe(rule.code);
    }
    expect(ruleForCode('ttr/does-not-exist' as never)).toBeUndefined();
  });

  it('rulesByCategory returns only rules of that category', () => {
    for (const cat of VALID_CATEGORIES) {
      for (const rule of rulesByCategory(cat)) expect(rule.category).toBe(cat);
    }
  });
});
