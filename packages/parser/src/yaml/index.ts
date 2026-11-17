// SPDX-License-Identifier: Apache-2.0
import type { ParseError, ParseResult } from '../ast.js';
import { parseString } from '../walker.js';
import { DiagnosticCode } from '../diagnostics.js';
import { YamlInput, YamlFailure, YamlCoordinates } from './input.js';
import { YamlBridge } from './bridge.js';
import { YamlLocations } from './locations.js';

export interface YamlParseResult extends ParseResult {
  diagnostics: ParseError[];
}

/** Canonical YAML 1.2 only. Invalid syntax/schema yields located diagnostics and no AST.
 * All ranges count Unicode code points, like parseString; embeddedBlockLocation maps decoded scalar text. */
export function parseYamlDocument(text: string, uri = '<yaml>'): YamlParseResult {
  const diagnostics: ParseError[] = [];
  let input: YamlInput | undefined;
  let coordinates: YamlCoordinates | undefined;
  try {
    coordinates = new YamlCoordinates(text, uri);
    input = new YamlInput(text, uri, coordinates);
    input.validate();
    const writer = new YamlBridge(input);
    const bridge = writer.write();
    for (const path of input.pl)
      if (!writer.embedded.has(path))
        throw new YamlFailure(path, '!pl is allowed only at an embedded-block position');
    const native = parseString(bridge.text, uri);
    const locations = new YamlLocations(input, bridge, diagnostics);
    for (const error of native.errors)
      diagnostics.push({
        ...error,
        code:
          error.severity === 'error' && (!error.code || error.code === DiagnosticCode.ParseError)
            ? DiagnosticCode.YamlValue
            : error.code,
        source: locations.source(error.source),
      });
    if (!native.ast || diagnostics.some((d) => d.severity === 'error' && d.code?.startsWith('ys/')))
      return { diagnostics, errors: diagnostics, sourceFile: uri };
    const ast = locations.copy(native.ast);
    return { ast, diagnostics, errors: diagnostics, sourceFile: uri };
  } catch (error) {
    if (!(error instanceof YamlFailure)) throw error;
    const [start, end] = error.span ?? [0, Math.min(text.length, 1)];
    diagnostics.push({
      code: error.code,
      severity: 'error',
      message: error.message,
      source:
        input && !error.span
          ? input.source(error.path)
          : (coordinates?.span([start, end]) ?? {
              file: uri,
              line: 1,
              column: 0,
              endLine: 1,
              endColumn: 0,
              offsetStart: 0,
              offsetEnd: 0,
            }),
    });
    return { diagnostics, errors: diagnostics, sourceFile: uri };
  }
}
