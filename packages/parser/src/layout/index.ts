// SPDX-License-Identifier: Apache-2.0
// `@tatrman/parser/layout` — the layout document and its TTR serialization (VL contracts §1–§3): the
// document and its canonical form, the `.ttrg` writer, the `.ttrg` + `.ttrl` reader. It never loads the
// ANTLR runtime, so a host (the Studio Viewer) imports it without the generated parser. The YAML form and
// the resolution rule live with the viewer (`packages/designer/src/layout/`); the JVM twins are
// ttr-parser's `model.LayoutDocument` / `loader.TtrgGraphReader` and ttr-writer's `TtrgLayoutWriter`.

export {
  alphabeticalKey,
  canonicalize,
  compareAlphabetical,
  compareCodePoints,
  fileStem,
  modeOf,
  roundCoordinate,
  sortAlphabetical,
  stemWarning,
  validateDocument,
  IDENTIFIER,
  LAYOUT_MODELS,
  type LayoutDocument,
  type LayoutIssue,
  type LayoutModel,
  type LayoutPoint,
  type LayoutReadResult,
  type ReadOptions,
} from './layout-document.js';
export {
  isTtrId,
  mergeTtr,
  parseTtrg,
  readTtrg,
  readTtrl,
  ttrUnwritable,
  writeTtrg,
  TTR_NON_ID_WORDS,
  type RawTtrGraph,
  type TtrlCanvasBlock,
  type TtrlReadResult,
  type TtrReadResult,
} from './layout-ttr.js';
