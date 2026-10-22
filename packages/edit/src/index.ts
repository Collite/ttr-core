// SPDX-License-Identifier: Apache-2.0
export type { WorkspaceEdit, TextEdit } from 'vscode-languageserver-types';
export type { AddObjectParams, RemoveObjectParams, CreateGraphParams, SetLayoutParams, RemoveObjectOutcome } from './graph-edits.js';
export { buildAddObjectEdit, buildRemoveObjectEdit, buildRemoveObjectWithConsequences, buildCreateGraphContent, buildCreateGraphEdit, buildSetLayoutEdit, serializeLayoutBlock } from './graph-edits.js';
export type { RenameSymbolEditParams } from './rename-symbol.js';
export { buildRenameSymbolEdit, sourceLocationToRange } from './rename-symbol.js';
export type { RenamePackageEditParams } from './rename-package.js';
export { buildRenamePackageEdit } from './rename-package.js';
export type { ImportEditResult } from './import-edits.js';
export { buildImportTextEdit } from './import-edits.js';
export {
  textDocEdit,
  removeLineEdit,
  insertAtTopEdit,
  replaceRangeEdit,
  insertEdit,
  buildAddImportEdit,
  applyWorkspaceEditToText,
} from './lint-fixes.js';
export type { GraphOp, PortRef, EdgeRole, EdgeInsertionTarget } from './graph-ops.js';
export { buildInsertNodeOnEdge, buildSetArgOp, buildSetSourceOp, buildRemoveObjectOp, insertionNodeId } from './graph-ops.js';
export { synthesize } from './synth/index.js';
export type { EditArgs, EditArguments, EditOp, RefusalCode, RefusalRef, SynthContext, SynthResult } from './synth/index.js';
