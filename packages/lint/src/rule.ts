// SPDX-License-Identifier: Apache-2.0
import type { SourceLocation, Document, Reference, Definition, DiagnosticCode } from '@tatrman/parser';
import type { ResolvedManifest, ProjectSymbolTable, Resolver, PackageGraph } from '@tatrman/semantics';
import type { WorkspaceEdit } from '@tatrman/edit';

export type Severity = 'error' | 'warning' | 'info' | 'off';
export type RuleCategory =
  | 'correctness'
  | 'references'
  | 'imports'
  | 'packages'
  | 'areas'
  | 'graph'
  | 'style'
  | 'md'
  | 'semantics'
  | 'lexicon'
  | 'cn';
export type RuleScope = 'document' | 'project';
export type RuleId = string; // kebab, no `ttr/` prefix

/**
 * A wire code: either a parser `DiagnosticCode` (`ttr/*`) emitted by a rule, or
 * a lint-tool code (`ttrlint/*`, contracts §5.6) emitted by the runner/config
 * for suppression/config problems. Kept as a union so both flow through one type.
 */
export type LintCode = DiagnosticCode | `ttrlint/${string}`;

export interface LintDiagnostic {
  ruleId: RuleId;
  code: LintCode; // ttr/* from a rule, or ttrlint/* tool diagnostic
  severity: Exclude<Severity, 'off'>;
  message: string;
  source: SourceLocation;
  data?: unknown; // carried to fix.build
}

export interface Fix {
  /** 'safe' fixes are applied by `--fix`; 'suggestion' fixes are CodeAction-only. */
  kind: 'safe' | 'suggestion';
  title: string;
  build(ctx: RuleContext, d: LintDiagnostic): WorkspaceEdit;
}

export interface Rule {
  id: RuleId;
  code: DiagnosticCode;
  category: RuleCategory;
  scope: RuleScope;
  defaultSeverity: Exclude<Severity, 'off'>;
  docs: string;
  check(ctx: RuleContext): void;
  fix?: Fix;
}

/** A reference paired with the top-level definition that owns it. */
export interface OwnedReference {
  ref: Reference;
  ownerDef: Definition;
}

interface BaseContext {
  manifest: ResolvedManifest;
  symbols: ProjectSymbolTable;
  resolver: Resolver;
  /**
   * A rule reports here. Severity is normally stamped by the runner from
   * config; a rule may pass an explicit `severity` when its severity is driven
   * by a non-lint config knob (e.g. `[packages].layout` for package-mismatch
   * rules, PD1.5/1.6). The override still honours suppression and the
   * correctness clamp.
   */
  report(d: {
    source: SourceLocation;
    message: string;
    data?: unknown;
    severity?: Exclude<Severity, 'off'>;
  }): void;
}

export interface DocumentRuleContext extends BaseContext {
  scope: 'document';
  uri: string;
  ast: Document;
  /** `collectAllReferences(ast)`, computed once by the runner and shared. */
  refs: ReadonlyArray<OwnedReference>;
  /** Raw document text — populated when building fixes (absent during `check`). */
  text?: string;
}

export interface ProjectRuleContext extends BaseContext {
  scope: 'project';
  packageGraph: PackageGraph;
  documents: ReadonlyMap<string, Document>;
  /**
   * Per-pass memo shared across every project rule in one `lintProject` call.
   * Lets a family of rules build an expensive project-wide index (e.g. the MD
   * binding model / map graph) once instead of once per rule. Created fresh by
   * the runner each pass, so it never goes stale across edits.
   */
  cache: Map<string, unknown>;
}

export type RuleContext = DocumentRuleContext | ProjectRuleContext;
