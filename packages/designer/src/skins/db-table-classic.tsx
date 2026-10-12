// db.table-classic — the physical table notation (E-3a default for db). Table cards showing
// column rows with key marks + types; FK edges. Migrated idiom from a-modeling-v0.html's db
// tab + the existing Cytoscape glyph renderer.

import type { CanvasNode, NodeRenderProps } from '@tatrman/canvas-core';
import { canvas as palette } from '@tatrman/tokens'; // canvas token family (contracts §6)
import type { DesignerSkin } from '../canvas/skin-component.js';
import { RowCard, cardSize } from './card-rows.js';
import { HOP_STYLE } from './hop-style.js';

function DbClassicNode({ node }: NodeRenderProps) {
  const mark = node.kind === 'view' ? '▤' : '▦';
  return <RowCard node={node} kindMark={mark} headerBg={palette.ink} showTypes={true} />;
}

export const dbTableClassic: DesignerSkin = {
  id: 'db.table-classic',
  face: 'modeling',
  modelKind: 'db',
  displayName: 'table-classic',
  description: 'Physical tables as classic column cards with keys + types',
  flow: { orientation: 'LR', layout: { nodeSpacing: 60, layerSpacing: 120 } },
  canvas: { background: palette.bg, grid: 'dots' },
  // a hop edge (VL V-26) is dashed, no arrow; a foreign key is solid with an arrow
  edgeStyle: (e) => (e.kind === 'hop' ? HOP_STYLE : { stroke: palette.edgeStroke, width: 1.6, marker: 'arrow' }),
  portGeometry: (port) => ({ shape: 'square', placement: port.direction === 'out' ? 'flow-out' : 'flow-in' }),
  declareAnchors: (size) => ({
    chrome: { x: 12, y: 4, align: 'tl' },
    status: { x: size.width - 4, y: 4, align: 'tr' },
    diagnostics: { x: 4, y: 4, align: 'tl' },
  }),
  renderNode: DbClassicNode,
  containerStyle: {},
  nodeSize: (node: CanvasNode) => cardSize(node, 224),
};
