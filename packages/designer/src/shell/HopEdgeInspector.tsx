// SPDX-License-Identifier: Apache-2.0
// The inspector of a hop edge (VL V-26): which hidden card(s) the dashed `…` stands for, the two real
// relations each one joins, and **Show** per card (= check it into the layout). Read-only otherwise —
// the hop edge itself is derived and never saved (V-25).

import type { GetGraphResponse, ModelGraphEdge } from '@tatrman/lsp';
import { canvas as palette } from '@tatrman/tokens';

export interface InspectedHop {
  id: string;
  /** node ids of the two visible cards */
  fromNode: string;
  toNode: string;
  /** the hidden cards in between, as full qnames */
  via: string[];
}

const leaf = (qname: string): string => qname.slice(qname.lastIndexOf('.') + 1);

function edgeBetween(whole: GetGraphResponse, a: string, b: string): ModelGraphEdge | undefined {
  return whole.edges.find((e) => (e.fromNode === a && e.toNode === b) || (e.fromNode === b && e.toNode === a));
}

export function HopEdgeInspector({
  hop, whole, nodeOf, onShow, onClose,
}: {
  hop: InspectedHop;
  whole: GetGraphResponse;
  /** full qname → node id of `whole` */
  nodeOf: ReadonlyMap<string, string>;
  onShow: (fullQname: string) => void;
  onClose: () => void;
}) {
  const nodeById = new Map(whole.nodes.map((n) => [n.qname, n]));
  const labelOf = (id: string) => nodeById.get(id)?.label ?? leaf(id);
  const relation = (e: ModelGraphEdge | undefined) =>
    e ? `${leaf(e.qname)}: ${labelOf(e.fromNode)} → ${labelOf(e.toNode)}` : null;

  return (
    <aside
      data-testid="hop-inspector"
      role="complementary"
      aria-label="Hop edge"
      onKeyDown={(e) => { if (e.key === 'Escape') onClose(); }}
      style={{ width: 320, flex: '0 0 auto', background: '#fff', borderLeft: '1px solid #CBD8E6', display: 'flex', flexDirection: 'column', overflow: 'auto', fontSize: 12.5 }}
    >
      <div style={{ padding: '10px 14px', borderBottom: '1px solid #EDF2F9', display: 'flex', alignItems: 'baseline', gap: 8 }}>
        <strong style={{ color: palette.edgeHop }}>… hop edge</strong>
        <button onClick={onClose} aria-label="Close" style={{ marginLeft: 'auto', border: 'none', background: 'transparent', cursor: 'pointer', color: palette.muted, fontSize: 14 }}>×</button>
      </div>
      <div style={{ padding: '10px 14px' }}>
        <div data-testid="hop-ends" style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 11.5, color: palette.slate }}>
          {labelOf(hop.fromNode)} ⋯ {labelOf(hop.toNode)}
        </div>
        <p style={{ color: palette.muted, margin: '6px 0 10px' }}>
          A relation path through {hop.via.length === 1 ? 'a hidden card' : `${hop.via.length} hidden cards`}. Derived, never saved.
        </p>
        {hop.via.map((fq) => {
          const id = nodeOf.get(fq) ?? fq;
          const node = nodeById.get(id);
          const label = node?.label ?? leaf(fq);
          const identifier = node?.name ?? leaf(fq);
          return (
            <div key={fq} data-testid="hop-via" data-qname={fq} style={{ border: `1px solid ${palette.grid}`, borderRadius: 6, padding: '6px 8px', margin: '6px 0' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <b>{label}</b>
                {identifier !== label && <span style={{ color: palette.muted, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 11 }}>{identifier}</span>}
                <button
                  data-testid="hop-show"
                  onClick={() => onShow(fq)}
                  style={{ marginLeft: 'auto', border: `1px solid ${palette.grid}`, borderRadius: 5, background: '#fff', cursor: 'pointer', fontSize: 11.5, padding: '1px 8px' }}
                >
                  Show
                </button>
              </div>
              {[relation(edgeBetween(whole, hop.fromNode, id)), relation(edgeBetween(whole, id, hop.toNode))]
                .filter((r): r is string => r !== null)
                .map((r) => <div key={r} data-testid="hop-relation" style={{ color: palette.slate, fontSize: 11.5, marginTop: 2 }}>{r}</div>)}
            </div>
          );
        })}
      </div>
    </aside>
  );
}
