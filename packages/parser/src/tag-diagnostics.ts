// SPDX-License-Identifier: Apache-2.0
import type { ParseError, SourceLocation, TaggedBlockValue } from './ast.js';
import { DiagnosticCode } from './diagnostics.js';
import { resolveTag, resolveExpressionTag } from './tag-registry.js';

export function expressionTagDiagnostic(
  tag: string,
  key: string,
  source: SourceLocation
): ParseError | undefined {
  if (resolveExpressionTag(tag)) return undefined;
  return resolveTag(tag)
    ? {
        code: DiagnosticCode.LanguageTagMismatch,
        severity: 'error',
        source,
        message: `'${key}:' holds a TTR-P expression — the '${tag}' tag names another language (use """pl or no tag)`,
      }
    : {
        code: DiagnosticCode.UnknownLanguageTag,
        severity: 'warning',
        source,
        message: `'${tag}' on the opening line of '${key}:' reads as a language tag; the block is kept as plain text (start the expression on the next line, or tag it """pl)`,
      };
}

export function unknownTagDiagnostic(tag: string, source: SourceLocation): ParseError {
  return {
    code: DiagnosticCode.UnknownLanguageTag,
    severity: 'warning',
    source,
    message: `Unknown embedded-language tag '${tag}'`,
  };
}

export function queryTagDiagnostics(
  language: string | undefined,
  block: Pick<TaggedBlockValue, 'tag' | 'language' | 'tagSource'>
): ParseError[] {
  if (language === undefined) return [];
  const diagnostics: ParseError[] = [];
  if (language !== block.language)
    diagnostics.push({
      code: DiagnosticCode.LanguageTagMismatch,
      severity: 'error',
      source: block.tagSource,
      message: `'language: ${language}' disagrees with the block tag '${block.tag}' (${block.language})`,
    });
  diagnostics.push({
    code: DiagnosticCode.DeprecatedLanguageProperty,
    severity: 'warning',
    source: block.tagSource,
    message: `'language' on query is deprecated; it is inferred from the '${block.tag}' block tag`,
  });
  return diagnostics;
}
