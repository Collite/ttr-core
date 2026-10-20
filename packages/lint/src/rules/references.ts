// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode } from '@tatrman/parser';
import type { Document, Reference, Definition } from '@tatrman/parser';
import { defaultSchemaForKind, enclosingQnameOf, isCnContextRef, packageOfImport, MODEL_CODES } from '@tatrman/semantics';
import type { Resolver } from '@tatrman/semantics';
import { buildAddImportEdit, replaceRangeEdit } from '@tatrman/edit';
import type { DocumentRuleContext, Rule } from '../rule.js';
import { isMdKind } from './md.js';

// Ported from Validator.validateReferences. Uses ctx.refs (computed once by the
// runner) rather than recomputing collectAllReferences. The old `strict`
// error/warning flip for unresolved-reference is now config (P3); the rule
// reports unconditionally at its default (warning).

interface RefResolution {
  ref: Reference;
  res: ReturnType<Resolver['resolveReference']>;
}

function resolveAll(ctx: DocumentRuleContext): RefResolution[] {
  const ast: Document = ctx.ast;
  const directiveSchema = ast.modelDirective?.modelCode;
  const namespace = ast.modelDirective?.schema ?? '';
  const packageName = ast.packageDecl?.name ?? '';
  const out: RefResolution[] = [];
  for (const { ref, ownerDef } of ctx.refs) {
    // MD cross-references resolve against the MD namespaces (role-aware) in the
    // `md-unknown-ref` rule, not the generic resolver — skip them here. A cn ref in
    // an md host's `concept { }` sugar is not an MD cross-reference: it stays.
    if (isMdKind((ownerDef as Definition).kind) && !isCnContextRef(ref)) continue;
    const schemaCode = directiveSchema ?? defaultSchemaForKind((ownerDef as Definition).kind);
    const enclosingQname = enclosingQnameOf(ownerDef, schemaCode, namespace, packageName);
    // The ref itself: a cn slot's ref carries its context.
    const res = ctx.resolver.resolveReference(
      ref,
      { schemaCode, namespace, enclosingQname, imports: ast.imports, packageName }
    );
    out.push({ ref, res });
  }
  return out;
}

const unresolvedReference: Rule = {
  id: 'unresolved-reference',
  code: DiagnosticCode.UnresolvedReference,
  category: 'references',
  scope: 'document',
  defaultSeverity: 'warning',
  docs: 'A cross-reference could not be resolved to any definition.',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const { ref, res } of resolveAll(ctx)) {
      // A soft cn ref (a bearer-local `qualities:` / `period:` name) that names no concept is an
      // implicit quality, not a dangling reference.
      if (isCnContextRef(ref) && ref.soft) continue;
      if (!res.resolved && res.reason !== 'ambiguous') {
        ctx.report({
          source: ref.source,
          message: `Unresolved reference: '${ref.path}' (tried ${res.tried.map((a) => a.candidate).join(', ')})`,
        });
      }
    }
  },
};

const ambiguousReference: Rule = {
  id: 'ambiguous-reference',
  code: DiagnosticCode.AmbiguousReference,
  category: 'references',
  scope: 'document',
  defaultSeverity: 'error',
  docs: 'A reference matches multiple definitions via wildcard imports.',
  // Qualifying the reference to one candidate is a judgment call → suggestion.
  fix: {
    kind: 'suggestion',
    title: 'Qualify the reference to the first candidate',
    build(_ctx, d) {
      const data = d.data as { qualified?: string; refLine?: number; refCol?: number; refEndLine?: number; refEndCol?: number } | undefined;
      if (!data?.qualified || data.refLine === undefined) return { documentChanges: [] };
      const range = {
        start: { line: data.refLine - 1, character: data.refCol ?? 0 },
        end: { line: (data.refEndLine ?? data.refLine) - 1, character: data.refEndCol ?? 0 },
      };
      return replaceRangeEdit(d.source.file, range, data.qualified);
    },
  },
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const { ref, res } of resolveAll(ctx)) {
      if (!res.resolved && res.reason === 'ambiguous') {
        const loc = res.candidates?.[0]?.source ?? ref.source;
        ctx.report({
          source: loc,
          message: `Ambiguous reference: '${ref.path}' matches ${res.candidates?.length ?? 0} definitions via wildcard imports`,
          data: {
            qualified: res.candidates?.[0]?.qname,
            refLine: ref.source.line,
            refCol: ref.source.column,
            refEndLine: ref.source.endLine,
            refEndCol: ref.source.endColumn,
          },
        });
      }
    }
  },
};

const unimportedReference: Rule = {
  id: 'unimported-reference',
  code: DiagnosticCode.UnimportedReference,
  category: 'references',
  scope: 'document',
  defaultSeverity: 'info',
  docs: 'A reference resolves via package search but its package is not imported.',
  fix: {
    kind: 'safe',
    title: 'Add the missing import',
    build(ctx, d) {
      const target = (d.data as { target?: string } | undefined)?.target;
      if (ctx.scope !== 'document' || ctx.text === undefined || !target) return { documentChanges: [] };
      return buildAddImportEdit(d.source.file, ctx.text, ctx.ast, target) ?? { documentChanges: [] };
    },
  },
  check(ctx) {
    if (ctx.scope !== 'document') return;
    const ast = ctx.ast;
    const packageName = ast.packageDecl?.name ?? '';
    if (!packageName) return;
    const importedPkgs = new Set((ast.imports ?? []).map((imp) => packageOfImport(imp)));
    for (const { ref, res } of resolveAll(ctx)) {
      if (res.resolved && res.viaStep === 'fully-qualified') {
        const resolvedPackage = res.symbol.packageName;
        if (resolvedPackage && resolvedPackage !== packageName && !importedPkgs.has(resolvedPackage)) {
          ctx.report({
            source: ref.source,
            message: `Reference to '${res.symbol.qname}' resolves via package search; consider adding an import`,
            data: { target: resolvedPackage },
          });
        }
      }
    }
  },
};

// D6 — the schema slot is db-only. A file whose `model` is a logical/binding
// layer (er/md/cnc/cn/binding) must not carry a `schema` directive; the directive's
// schema id is meaningless there and is ignored by the symbol table.
const SCHEMALESS_MODELS: ReadonlySet<string> = new Set(['er', 'md', 'cnc', 'cn', 'binding']);

const schemaOnLogicalModel: Rule = {
  id: 'schema-on-logical-model',
  code: DiagnosticCode.SchemaOnLogicalModel,
  category: 'correctness',
  scope: 'document',
  defaultSeverity: 'warning',
  docs: 'A `schema` directive targets an er/md/cnc/cn/binding model, which has no schema slot (D6).',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    const d = ctx.ast.modelDirective;
    if (!d || !d.schema) return;
    if (!SCHEMALESS_MODELS.has(d.modelCode)) return;
    ctx.report({
      source: d.source,
      message: `'schema ${d.schema}' has no effect on a '${d.modelCode}' model — the schema slot is db-only (D6).`,
    });
  },
};

// D10 — when `[lint] require-qualified-refs` is enabled, flag a reference that
// resolved only because it was a unique match after the search widened past the
// file's own model/schema (the resolver's fully-qualified step), within the same
// package (cross-package widening is the separate `unimported-reference` rule).
const requireQualifiedRefs: Rule = {
  id: 'require-qualified-refs',
  code: DiagnosticCode.RequireQualifiedRefs,
  category: 'references',
  scope: 'document',
  defaultSeverity: 'warning',
  docs: 'A reference resolved only via cross-schema unique-match; enable [lint] require-qualified-refs to flag it (D10).',
  check(ctx) {
    if (ctx.scope !== 'document') return;
    if (!ctx.manifest.lint?.requireQualifiedRefs) return;
    const fileModel = ctx.ast.modelDirective?.modelCode;
    const filePackage = ctx.ast.packageDecl?.name ?? '';
    for (const { ref, res } of resolveAll(ctx)) {
      if (!res.resolved) continue;
      // Already model-qualified (`db.…`, `er.…`) → the author was explicit.
      if ((MODEL_CODES as ReadonlySet<string>).has(ref.parts[0])) continue;
      // A cn slot names its model: the slot is the qualification.
      if (isCnContextRef(ref)) continue;
      // Cross-package widening is the separate `unimported-reference` rule.
      if (res.symbol.packageName !== filePackage) continue;
      // Only a cross-model/schema match needs qualifying; same-model is fine.
      if (!fileModel || res.symbol.schemaCode === fileModel) continue;
      ctx.report({
        source: ref.source,
        message: `Reference '${ref.path}' resolved by cross-schema unique-match to '${res.symbol.qname}'; qualify it with its model/schema.`,
      });
    }
  },
};

export const REFERENCE_RULES: Rule[] = [
  unresolvedReference,
  ambiguousReference,
  unimportedReference,
  schemaOnLogicalModel,
  requireQualifiedRefs,
];
