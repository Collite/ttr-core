// SPDX-License-Identifier: Apache-2.0
export { lintDocument, lintProject, lintInputDiagnostics } from './runner.js';
export type { LintDeps } from './runner.js';
export {
  resolveLintConfig,
  parseLintConfig,
  loadLintConfig,
  recommendedConfig,
} from './config.js';
export type { ResolvedLintConfig, RawLintConfig, LegacyLint, ConfigFileReader } from './config.js';
export { presetSeverity, PRESET_NAMES } from './presets.js';
export type { PresetName } from './presets.js';
export { RULES, ruleForCode, rulesByCategory } from './registry.js';
export { collectSafeFixes } from './fix.js';
export type { FixResult } from './fix.js';
export { buildSuppressionIndex } from './suppression.js';
export type { SuppressionIndex } from './suppression.js';
export type {
  Severity,
  RuleCategory,
  RuleScope,
  RuleId,
  LintDiagnostic,
  Fix,
  Rule,
  OwnedReference,
  DocumentRuleContext,
  ProjectRuleContext,
  RuleContext,
} from './rule.js';
