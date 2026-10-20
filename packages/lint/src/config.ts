// SPDX-License-Identifier: Apache-2.0
import { parse as parseToml } from 'smol-toml';
import type { SourceLocation } from '@tatrman/parser';
import type { LintDiagnostic, RuleCategory, RuleId, Severity } from './rule.js';
import { RULES } from './registry.js';
import { presetSeverity, PRESET_NAMES, type PresetName } from './presets.js';

export interface RawLintConfig {
  extends?: PresetName;
  rules?: Record<string, Severity>;
  categories?: Partial<Record<RuleCategory, Severity>>;
  cli?: { 'fail-on'?: Exclude<Severity, 'off'> | 'none' };
  fix?: { apply?: 'safe' | 'none' };
}

export interface ResolvedLintConfig {
  /** Effective severity after precedence + correctness clamp. */
  severityOf(ruleId: RuleId): Severity;
  failOn: 'error' | 'warning' | 'info' | 'none';
  applyFixes: 'safe' | 'none';
  /** ttrlint/* config-level diagnostics, reported on `.ttrlint.toml`. */
  diagnostics: LintDiagnostic[];
}

/** Legacy `modeler.toml [lint]` knobs, used as a fallback when there's no `.ttrlint.toml`. */
export interface LegacyLint {
  strict?: boolean;
  requireDescriptions?: boolean;
}

const VALID_CATEGORIES: ReadonlySet<string> = new Set<RuleCategory>([
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

const RANK: Record<Severity, number> = { off: 0, info: 1, warning: 2, error: 3 };

function configLoc(file: string): SourceLocation {
  return { file, line: 1, column: 0, endLine: 1, endColumn: 0, offsetStart: 0, offsetEnd: 0 };
}

function diag(
  code: `ttrlint/${string}`,
  severity: Exclude<Severity, 'off'>,
  message: string,
  file: string
): LintDiagnostic {
  return { ruleId: code, code, severity, message, source: configLoc(file) };
}

/** Parse `.ttrlint.toml` content into a `RawLintConfig` (no validation here). */
export function parseLintConfig(content: string): RawLintConfig {
  return parseToml(content) as RawLintConfig;
}

/**
 * Resolve a raw config (and legacy fallback) into effective severities + config
 * diagnostics. Pure — no filesystem. Precedence (low→high, §5.3):
 *   defaultSeverity → `extends` preset → `[categories]` → `[rules]`.
 * Correctness rules clamp up to `error` when a config layer puts them below it
 * (§6.5); a user-authored downgrade also yields `ttrlint/clamped-severity`.
 */
export function resolveLintConfig(
  raw: RawLintConfig | undefined,
  legacy: LegacyLint = {},
  configFile = '.ttrlint.toml'
): ResolvedLintConfig {
  const diagnostics: LintDiagnostic[] = [];
  const hasFile = raw !== undefined;
  const legacyHasFlags = !!(legacy.strict || legacy.requireDescriptions);

  // Effective preset.
  let preset: PresetName = 'recommended';
  if (hasFile) {
    if (raw!.extends !== undefined) {
      if (PRESET_NAMES.has(raw!.extends)) preset = raw!.extends;
      else diagnostics.push(diag('ttrlint/unknown-rule', 'warning', `Unknown preset '${raw!.extends}' in extends`, configFile));
    }
    if (legacyHasFlags) {
      diagnostics.push(
        diag(
          'ttrlint/deprecated-lint-config',
          'info',
          '.ttrlint.toml is present; the modeler.toml [lint] settings are ignored and can be removed',
          configFile
        )
      );
    }
  } else {
    preset = legacy.strict ? 'strict' : 'recommended';
  }

  const rules = raw?.rules ?? {};
  const categories = raw?.categories ?? {};

  for (const id of Object.keys(rules)) {
    if (!RULES.has(id)) diagnostics.push(diag('ttrlint/unknown-rule', 'warning', `Unknown rule id '${id}'`, configFile));
  }
  for (const cat of Object.keys(categories)) {
    if (!VALID_CATEGORIES.has(cat)) diagnostics.push(diag('ttrlint/unknown-category', 'warning', `Unknown category '${cat}'`, configFile));
  }

  // requireDescriptions back-compat applies only when there is no .ttrlint.toml.
  const legacyRuleOverrides: Record<string, Severity> = {};
  if (!hasFile && legacy.requireDescriptions) legacyRuleOverrides['missing-description'] = 'warning';

  const cache = new Map<string, Severity>();
  const severityOf = (ruleId: RuleId): Severity => {
    const cached = cache.get(ruleId);
    if (cached !== undefined) return cached;
    const rule = RULES.get(ruleId);
    if (!rule) {
      cache.set(ruleId, 'off');
      return 'off';
    }
    let sev: Severity = rule.defaultSeverity;
    let configured = false;

    const ps = presetSeverity(preset, ruleId);
    if (ps !== undefined) { sev = ps; configured = true; }
    if (ruleId in legacyRuleOverrides) { sev = legacyRuleOverrides[ruleId]; configured = true; }
    const cs = categories[rule.category];
    if (cs !== undefined) { sev = cs; configured = true; }
    const rs = rules[ruleId];
    if (rs !== undefined) { sev = rs; configured = true; }

    if (rule.category === 'correctness' && configured && RANK[sev] < RANK.error) {
      const userLowered = (cs !== undefined && RANK[cs] < RANK.error) || (rs !== undefined && RANK[rs] < RANK.error);
      if (userLowered) {
        diagnostics.push(
          diag('ttrlint/clamped-severity', 'info', `Correctness rule '${ruleId}' cannot be lowered below error; clamped`, configFile)
        );
      }
      sev = 'error';
    }
    cache.set(ruleId, sev);
    return sev;
  };

  // Eagerly evaluate every rule so clamp diagnostics are fully populated.
  for (const id of RULES.keys()) severityOf(id);

  return {
    severityOf,
    failOn: raw?.cli?.['fail-on'] ?? 'error',
    applyFixes: raw?.fix?.apply ?? 'safe',
    diagnostics,
  };
}

/** The `recommended` config (back-compat: legacy `[lint]` flags + per-rule overrides). */
export function recommendedConfig(opts: {
  strict?: boolean;
  requireDescriptions?: boolean;
  overrides?: Record<string, Severity>;
} = {}): ResolvedLintConfig {
  const raw: RawLintConfig | undefined = opts.overrides ? { rules: opts.overrides } : undefined;
  return resolveLintConfig(raw, { strict: opts.strict, requireDescriptions: opts.requireDescriptions });
}

/** Reads a config file's contents, or resolves undefined when it doesn't exist. */
export type ConfigFileReader = (path: string) => Promise<string | undefined>;

/**
 * Discover `.ttrlint.toml` at `projectRoot`, parse + resolve it. `readFile` is
 * injected (node fs in the stdio host / CLI) so this module stays free of any
 * `fs` import — that keeps the browser LSP worker bundle clean. With no
 * `readFile` (browser) or an absent/unparseable file, falls back to
 * `recommendedConfig(legacy)`.
 */
export async function loadLintConfig(
  projectRoot: string,
  legacy: LegacyLint = {},
  readFile?: ConfigFileReader
): Promise<ResolvedLintConfig> {
  if (!readFile) return recommendedConfig(legacy);
  const configFile = joinPath(projectRoot, '.ttrlint.toml');
  let content: string | undefined;
  try {
    content = await readFile(configFile);
  } catch {
    return recommendedConfig(legacy);
  }
  if (content === undefined) return recommendedConfig(legacy);
  try {
    return resolveLintConfig(parseLintConfig(content), legacy, configFile);
  } catch (err) {
    return {
      severityOf: (id) => RULES.get(id)?.defaultSeverity ?? 'off',
      failOn: 'error',
      applyFixes: 'safe',
      diagnostics: [diag('ttrlint/unknown-rule', 'warning', `Failed to parse .ttrlint.toml: ${err instanceof Error ? err.message : String(err)}`, configFile)],
    };
  }
}

function joinPath(root: string, name: string): string {
  if (!root) return name;
  return root.endsWith('/') ? root + name : root + '/' + name;
}
