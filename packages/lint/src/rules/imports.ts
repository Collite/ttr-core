// SPDX-License-Identifier: Apache-2.0
import { DiagnosticCode } from '@tatrman/parser';
import type { Definition } from '@tatrman/parser';
import { defaultSchemaForKind, packageOfImport, type SymbolEntry } from '@tatrman/semantics';
import { removeLineEdit } from '@tatrman/edit';
import type { DocumentRuleContext, Rule } from '../rule.js';

/** A safe fix that deletes the import line the diagnostic sits on. */
const deleteImportLineFix = (title: string): Rule['fix'] => ({
  kind: 'safe',
  title,
  build: (_ctx, d) => removeLineEdit(d.source.file, d.source.line - 1),
});

// Ported from Validator.validateImports, split into three rules. The used-target
// computation uses ctx.refs (shared) instead of recomputing references.

const wildcardWithNoMatches: Rule = {
  id: 'wildcard-with-no-matches',
  code: DiagnosticCode.WildcardWithNoMatches,
  category: 'imports',
  scope: 'document',
  defaultSeverity: 'warning',
  docs: 'A wildcard import matches no definitions.',
  fix: deleteImportLineFix('Remove wildcard import'),
  check(ctx) {
    if (ctx.scope !== 'document') return;
    for (const imp of ctx.ast.imports ?? []) {
      if (imp.wildcard && ctx.symbols.getByPackage(imp.target).length === 0) {
        ctx.report({
          source: imp.source,
          message: `Wildcard import '${imp.target}.*' has no matching definitions`,
        });
      }
    }
  },
};

const duplicateImport: Rule = {
  id: 'duplicate-import',
  code: DiagnosticCode.DuplicateImport,
  category: 'imports',
  scope: 'document',
  defaultSeverity: 'warning',
  docs: 'The same import target appears more than once.',
  fix: deleteImportLineFix('Remove duplicate import'),
  check(ctx) {
    if (ctx.scope !== 'document') return;
    const seen = new Set<string>();
    for (const imp of ctx.ast.imports ?? []) {
      if (seen.has(imp.target)) {
        ctx.report({ source: imp.source, message: `Duplicate import of '${imp.target}'` });
      } else {
        seen.add(imp.target);
      }
    }
  },
};

/**
 * The package part of an import that reaches `symbol`: the key less its name, as an element's named
 * import is written (`import <pkg>.er.entity.<name>`). 0.17: a concept — or an event's participant
 * label under it — is imported by package and name (`import hartland.core.Customer`), so it reads the
 * symbol's package (review-119 M-7).
 */
function importedPackageOf(symbol: SymbolEntry): string {
  return symbol.schemaCode === 'cn' ? symbol.packageName : symbol.qname.slice(0, symbol.qname.lastIndexOf('.'));
}

/** Targets actually used by a resolved reference (named- or wildcard-import). */
function usedTargets(ctx: DocumentRuleContext): Set<string> {
  const ast = ctx.ast;
  const directiveSchema = ast.modelDirective?.modelCode;
  const namespace = ast.modelDirective?.schema ?? '';
  const packageName = ast.packageDecl?.name ?? '';
  const imports = ast.imports ?? [];
  const used = new Set<string>();
  for (const { ref, ownerDef } of ctx.refs) {
    const schemaCode = directiveSchema ?? defaultSchemaForKind((ownerDef as Definition).kind);
    // The ref itself: a cn slot's ref carries its context (0.17), so it resolves among concepts.
    const res = ctx.resolver.resolveReference(ref, { schemaCode, namespace, imports, packageName });
    if (res.resolved && (res.viaStep === 'named-import' || res.viaStep === 'wildcard-import')) {
      used.add(importedPackageOf(res.symbol));
    }
  }
  return used;
}

const unusedImport: Rule = {
  id: 'unused-import',
  code: DiagnosticCode.UnusedImport,
  category: 'imports',
  scope: 'document',
  defaultSeverity: 'warning',
  docs: 'A named import is never referenced.',
  fix: deleteImportLineFix('Remove unused import'),
  check(ctx) {
    if (ctx.scope !== 'document') return;
    const used = usedTargets(ctx);
    for (const imp of ctx.ast.imports ?? []) {
      if (imp.wildcard) continue;
      const pkg = packageOfImport(imp);
      if (pkg && !used.has(pkg)) {
        ctx.report({ source: imp.source, message: `Import '${imp.target}' is not referenced` });
      }
    }
  },
};

export const IMPORT_RULES: Rule[] = [wildcardWithNoMatches, duplicateImport, unusedImport];
