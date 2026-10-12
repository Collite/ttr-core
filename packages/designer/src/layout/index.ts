// SPDX-License-Identifier: Apache-2.0
// The layout module (VL-P0): one document, two serializations, one resolution rule (VL contracts §1–§4).

export {
  alphabeticalKey,
  canonicalize,
  compareAlphabetical,
  compareCodePoints,
  fileStem,
  modeOf,
  roundCoordinate,
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
export { readYaml, writeYaml, yamlScalar, LAYOUT_DOCS_URL, LAYOUT_FORMAT_VERSION, LAYOUT_YAML_HEADER, type WriteYamlOptions } from './layout-yaml.js';
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
export {
  resolutionToJson,
  resolveRefs,
  EDGE_KIND_SEGMENTS,
  type Ambiguity,
  type LayoutIndexEntry,
  type LayoutModelIndex,
  type Resolution,
} from './resolve.js';
