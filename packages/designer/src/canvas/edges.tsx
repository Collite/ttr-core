// Kernel edges. D-4 β convention is kernel-owned geometry (data enters on the flow axis,
// control on the cross axis — enforced by the source/target Handle the mapper wires); the
// skin only restyles (stroke/dash/width) via skin.edgeStyle. One custom edge type handles
// both; the role decides base geometry, the skin decides paint.

import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';
import type { EdgeStyleSpec, Theme } from '@tatrman/canvas-core';
import { canvas as palette } from '@tatrman/tokens'; // canvas token family (contracts §6)

export interface CEdgeData {
  style: EdgeStyleSpec;
  theme: Theme;
  cardinality?: { from?: string; to?: string };
  /** `hop` — a derived path through hidden cards (VL V-26) */
  kind?: 'hop';
  /** hop edges: how many hidden cards the `…` stands for */
  viaCount?: number;
  /** the hop pill is the edge's handle: a click selects it, a right-click opens its view menu */
  onSelect?: () => void;
  onContextMenu?: (clientX: number, clientY: number) => void;
  [key: string]: unknown;
}

// crow's-foot glyph for a cardinality (er.crow); other skins can ignore by not carrying cardinality
function crowFoot(card: string | undefined): string {
  if (!card) return '';
  if (card === '1') return '‖';
  if (card === '0..1') return '○|';
  if (card === '0..*' || card === '*' || card === 'n') return '{';
  if (card === '1..*') return '|{';
  return card;
}

function CardEnd({ x, y, card, testid, stroke }: { x: number; y: number; card?: string; testid: string; stroke: string }) {
  if (!card) return null;
  return (
    <EdgeLabelRenderer>
      <div
        data-testid={testid}
        data-card={card}
        style={{
          position: 'absolute', transform: `translate(-50%,-50%) translate(${x}px,${y}px)`,
          color: stroke, fontSize: 12, fontWeight: 'bold', pointerEvents: 'none',
        }}
      >
        {crowFoot(card)}
      </div>
    </EdgeLabelRenderer>
  );
}

/** A hop edge (VL V-26): dashed, no cardinality glyphs, the `…` / `… ×n` pill at the midpoint. */
function HopEdgeView(props: EdgeProps, spec: EdgeStyleSpec, data: CEdgeData) {
  const [path, labelX, labelY] = getBezierPath({
    sourceX: props.sourceX, sourceY: props.sourceY, targetX: props.targetX, targetY: props.targetY,
    sourcePosition: props.sourcePosition, targetPosition: props.targetPosition,
  });
  return (
    <>
      <BaseEdge
        id={props.id}
        path={path}
        className="hop-edge-path"
        interactionWidth={14}
        style={{ stroke: spec.stroke, strokeWidth: props.selected ? spec.width + 0.8 : spec.width, strokeDasharray: spec.dash }}
      />
      <EdgeLabelRenderer>
        <div
          data-testid="hop-edge"
          data-edge-id={props.id}
          data-via-count={data.viaCount ?? 1}
          className="hop-edge nodrag nopan"
          title="A path through hidden cards — click to see them"
          onClick={(e) => { e.stopPropagation(); data.onSelect?.(); }}
          onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); data.onContextMenu?.(e.clientX, e.clientY); }}
          style={{
            position: 'absolute', transform: `translate(-50%,-50%) translate(${labelX}px,${labelY}px)`, pointerEvents: 'all', cursor: 'pointer',
            background: palette.nodeFill, color: spec.stroke, border: `${props.selected ? 1.6 : 0.8}px dashed ${spec.stroke}`,
            borderRadius: 8, padding: '0 7px', fontSize: 11, fontWeight: 500, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          }}
        >
          {String(props.label ?? '…')}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

export function CanvasEdgeView(props: EdgeProps) {
  const data = (props.data ?? {}) as CEdgeData;
  const { style, theme, cardinality } = data;
  const spec: EdgeStyleSpec = style ?? { stroke: palette.edgeData, width: 2 };
  if (data.kind === 'hop') return HopEdgeView(props, spec, data);
  const [path, labelX, labelY] = getBezierPath({
    sourceX: props.sourceX, sourceY: props.sourceY, targetX: props.targetX, targetY: props.targetY,
    sourcePosition: props.sourcePosition, targetPosition: props.targetPosition,
  });
  return (
    <>
      <BaseEdge
        id={props.id}
        path={path}
        markerEnd={props.markerEnd}
        style={{ stroke: spec.stroke, strokeWidth: spec.width, strokeDasharray: spec.dash }}
      />
      {cardinality?.from && <CardEnd x={props.sourceX} y={props.sourceY} card={cardinality.from} testid="edge-card-source" stroke={spec.stroke} />}
      {cardinality?.to && <CardEnd x={props.targetX} y={props.targetY} card={cardinality.to} testid="edge-card-target" stroke={spec.stroke} />}
      {props.label && (
        <EdgeLabelRenderer>
          <div
            data-testid="edge-label"
            style={{
              position: 'absolute', transform: `translate(-50%,-50%) translate(${labelX}px,${labelY}px)`,
              background: theme === 'stage-navy' ? palette.ink : palette.nodeFill, color: theme === 'stage-navy' ? palette.bg : palette.edgeLabel,
              border: `0.8px solid ${spec.stroke}`, borderRadius: 8, padding: '1px 6px', fontSize: 10,
            }}
          >
            {String(props.label)}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
