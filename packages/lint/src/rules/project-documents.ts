// SPDX-License-Identifier: Apache-2.0
import type { CnDocument } from '@tatrman/semantics';
import type { ProjectRuleContext } from '../rule.js';

/** The effective document contexts shared by CN and explicit MD/ER analysis, once per lint pass. */
export function cnDocumentsFor(ctx: ProjectRuleContext): CnDocument[] {
  const cached = ctx.cache.get('project:cnDocuments') as CnDocument[] | undefined;
  if (cached) return cached;
  const docs: CnDocument[] = [...ctx.documents].map(([uri, ast]) => ({
    uri, ast,
    ...(ctx.symbols.documentContext(uri) ?? {
      schemaCode: ast.modelDirective?.modelCode ?? '', namespace: ast.modelDirective?.schema ?? '',
      packageName: ast.packageDecl?.name ?? '',
    }),
  }));
  ctx.cache.set('project:cnDocuments', docs);
  return docs;
}
