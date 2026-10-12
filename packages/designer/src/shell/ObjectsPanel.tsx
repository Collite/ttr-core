// SPDX-License-Identifier: Apache-2.0
// The Objects panel (VL contracts §5.1, V-18…V-21): every card of the open subject's schema, grouped by
// package, with a checkbox each — checking shows the card, unchecking hides it. Fed from the
// whole-schema graph the shell already holds; it issues no backend read of its own (V-21).
//
// Rows: checkbox · label (`displayLabel` in the viewer's language, else the identifier) · the identifier,
// muted, when it differs · the card's relation count; hover actions Expand / Hide. Groups: a tri-state
// checkbox with `k / n`, collapsible. Search on label, identifier, qname and package (Esc clears).
// Header: All · None · Neighbours · Invert — All / None act on the search result when there is one
// (V-20). Missing (n) on top: refs the model lacks, unchecked and struck through, each with Remove (V-17).

import { useEffect, useMemo, useRef, useState } from 'react';
import type { GetGraphResponse, ModelGraphNode } from '@tatrman/lsp';
import { canvas as palette } from '@tatrman/tokens';
import { compareAlphabetical } from '@tatrman/parser/layout';
import type { LayoutGraphIndex } from '../model/layout-projection.js';

export interface MissingRef {
  ref: string;
  /** set when the ref is ambiguous: the full qnames it could mean */
  candidates?: string[];
}

export interface ObjectsPanelProps {
  whole: GetGraphResponse;
  index: LayoutGraphIndex;
  /** the full qnames the tab shows */
  shown: ReadonlySet<string>;
  missing: readonly MissingRef[];
  /** show exactly these cards (full qnames) */
  onSetCards: (cards: string[]) => void;
  /** check the hidden cards one real edge away from the shown ones (the shell places them) */
  onNeighbours: () => void;
  onRemoveMissing: (ref: string) => void;
  onExpand: (fullQname: string) => void;
  onHide: (fullQname: string) => void;
}

const NO_PACKAGE = '(no package)';

interface Row {
  fq: string;
  node: ModelGraphNode;
  degree: number;
}

function matches(row: Row, pkg: string, q: string): boolean {
  return [row.node.label, row.node.name, row.node.qname, row.fq, pkg].some((s) => s.toLowerCase().includes(q));
}

export function ObjectsPanel({ whole, index, shown, missing, onSetCards, onNeighbours, onRemoveMissing, onExpand, onHide }: ObjectsPanelProps) {
  const [query, setQuery] = useState('');
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const [hidden, setHidden] = useState(false);
  const q = query.trim().toLowerCase();

  const degree = useMemo(() => {
    const d = new Map<string, number>();
    for (const e of whole.edges) {
      if (e.fromNode === e.toNode) continue;
      d.set(e.fromNode, (d.get(e.fromNode) ?? 0) + 1);
      d.set(e.toNode, (d.get(e.toNode) ?? 0) + 1);
    }
    return d;
  }, [whole]);

  // groups by package, `(no package)` last; cards by label, then identifier
  const groups = useMemo(() => {
    const byPkg = new Map<string, Row[]>();
    for (const node of whole.nodes) {
      const pkg = node.pkg || NO_PACKAGE;
      const rows = byPkg.get(pkg) ?? [];
      rows.push({ fq: index.fullQnameOf.get(node.qname) ?? node.qname, node, degree: degree.get(node.qname) ?? 0 });
      byPkg.set(pkg, rows);
    }
    for (const rows of byPkg.values()) {
      rows.sort((a, b) => compareAlphabetical(a.node.label, b.node.label) || compareAlphabetical(a.node.name, b.node.name));
    }
    return [...byPkg.entries()].sort(([a], [b]) => (a === NO_PACKAGE ? 1 : b === NO_PACKAGE ? -1 : compareAlphabetical(a, b)));
  }, [whole, index, degree]);

  const allCards = useMemo(() => groups.flatMap(([, rows]) => rows.map((r) => r.fq)), [groups]);
  const filtered = useMemo(
    () => (q ? groups.flatMap(([pkg, rows]) => rows.filter((r) => matches(r, pkg, q)).map((r) => r.fq)) : allCards),
    [groups, allCards, q],
  );

  const set = (on: Iterable<string>, off: Iterable<string> = []) => {
    const next = new Set(shown);
    for (const fq of on) next.add(fq);
    for (const fq of off) next.delete(fq);
    onSetCards(allCards.filter((fq) => next.has(fq)));
  };

  if (hidden) {
    return (
      <div data-testid="objects-panel-collapsed" style={{ width: 22, flex: '0 0 auto', borderRight: `1px solid ${palette.grid}`, background: '#fbfdff' }}>
        <button data-testid="objects-panel-expand" title="Show the Objects panel" onClick={() => setHidden(false)}
          style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: palette.muted, width: '100%', padding: '8px 0' }}>›</button>
      </div>
    );
  }

  return (
    <section data-testid="objects-panel" aria-label="Objects" style={{ width: 250, flex: '0 0 auto', display: 'flex', flexDirection: 'column', minHeight: 0, borderRight: `1px solid ${palette.grid}`, background: '#fbfdff', fontSize: 12.5 }}>
      <div style={{ padding: '8px 10px 4px', display: 'flex', alignItems: 'baseline', gap: 6 }}>
        <h3 style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '.06em', color: palette.muted, margin: 0 }}>Objects</h3>
        <span data-testid="objects-count" style={{ color: palette.muted, fontSize: 11 }}>{allCards.filter((fq) => shown.has(fq)).length} / {allCards.length}</span>
        <button data-testid="objects-panel-collapse" title="Hide the Objects panel" onClick={() => setHidden(true)}
          style={{ marginLeft: 'auto', border: 'none', background: 'transparent', cursor: 'pointer', color: palette.muted }}>‹</button>
      </div>
      <div style={{ padding: '0 10px 6px' }}>
        <input
          data-testid="objects-search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Escape') setQuery(''); }}
          placeholder="Search label, identifier, package… (Esc)"
          style={{ width: '100%', padding: '4px 8px', border: `1px solid ${palette.grid}`, borderRadius: 6, font: 'inherit', fontSize: 12 }}
        />
        <div style={{ display: 'flex', gap: 4, marginTop: 6 }}>
          {[
            ['objects-all', 'All', () => set(filtered)],
            ['objects-none', 'None', () => set([], filtered)],
            ['objects-neighbours', 'Neighbours', onNeighbours],
            ['objects-invert', 'Invert', () => set(allCards.filter((fq) => !shown.has(fq)), allCards.filter((fq) => shown.has(fq)))],
          ].map(([id, label, run]) => (
            <button key={id as string} data-testid={id as string} onClick={run as () => void}
              title={id === 'objects-neighbours' ? 'Add every card one relation away from a shown card' : undefined}
              style={{ border: `1px solid ${palette.grid}`, borderRadius: 5, background: '#fff', cursor: 'pointer', fontSize: 11.5, padding: '1px 7px' }}>
              {label as string}
            </button>
          ))}
        </div>
      </div>
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: 12 }}>
        {missing.length > 0 && (
          <div data-testid="objects-missing" style={{ marginBottom: 4 }}>
            <div style={{ padding: '4px 10px', color: palette.warnInk, fontWeight: 600 }}>! Missing <span style={{ fontWeight: 400 }}>{missing.length}</span></div>
            {missing.map((m) => (
              <div key={m.ref} data-testid="objects-missing-row" data-ref={m.ref} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '2px 10px 2px 22px' }}>
                <input type="checkbox" checked={false} disabled aria-label={`${m.ref} (missing)`} />
                <span style={{ flex: 1, minWidth: 0, textDecoration: 'line-through', color: palette.muted, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 11.5, overflow: 'hidden', textOverflow: 'ellipsis' }}
                  title={m.candidates ? `ambiguous: ${m.candidates.join(', ')}` : 'not in the model'}>
                  {m.ref}
                </span>
                {m.candidates && <span style={{ color: palette.warnInk, fontSize: 11 }}>ambiguous</span>}
                <button data-testid="objects-remove" onClick={() => onRemoveMissing(m.ref)}
                  style={{ border: `1px solid ${palette.grid}`, borderRadius: 5, background: '#fff', cursor: 'pointer', fontSize: 11, padding: '0 6px' }}>Remove</button>
              </div>
            ))}
          </div>
        )}
        {groups.map(([pkg, rows]) => {
          const visibleRows = q ? rows.filter((r) => matches(r, pkg, q)) : rows;
          if (visibleRows.length === 0) return null;
          const on = rows.filter((r) => shown.has(r.fq)).length;
          const isCollapsed = collapsed.has(pkg) && !q;
          return (
            <div key={pkg} data-testid="objects-group" data-pkg={pkg}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '4px 10px', cursor: 'pointer' }}
                onClick={() => setCollapsed((c) => { const n = new Set(c); if (n.has(pkg)) n.delete(pkg); else n.add(pkg); return n; })}>
                <GroupCheckbox
                  checked={on === rows.length}
                  indeterminate={on > 0 && on < rows.length}
                  label={pkg}
                  onToggle={() => set(on === rows.length ? [] : rows.map((r) => r.fq), on === rows.length ? rows.map((r) => r.fq) : [])}
                />
                <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontWeight: 600, color: palette.ink, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>{pkg}</span>
                <span data-testid="objects-group-count" style={{ color: palette.muted, fontSize: 11 }}>{on} / {rows.length}</span>
              </div>
              {!isCollapsed && visibleRows.map((r) => {
                const checked = shown.has(r.fq);
                return (
                  <div key={r.fq} data-testid="objects-row" data-qname={r.fq} className="group" style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '1px 10px 1px 22px' }}>
                    <input type="checkbox" data-testid="objects-check" checked={checked} aria-label={r.node.label}
                      onChange={(e) => set(e.target.checked ? [r.fq] : [], e.target.checked ? [] : [r.fq])} />
                    <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={r.fq}>
                      <span data-testid="objects-label">{r.node.label}</span>
                      {r.node.name !== r.node.label && (
                        <span data-testid="objects-identifier" style={{ marginLeft: 5, color: palette.muted, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 11 }}>{r.node.name}</span>
                      )}
                    </span>
                    <span className="invisible group-hover:visible" style={{ display: 'inline-flex', gap: 3 }}>
                      <button data-testid="objects-expand" title="Expand: add the direct neighbours" onClick={() => onExpand(r.fq)}
                        style={{ border: `1px solid ${palette.grid}`, borderRadius: 4, background: '#fff', cursor: 'pointer', fontSize: 10.5, padding: '0 4px' }}>Expand</button>
                      <button data-testid="objects-hide" title={checked ? 'Hide this card' : 'Show this card'}
                        onClick={() => (checked ? onHide(r.fq) : set([r.fq]))}
                        style={{ border: `1px solid ${palette.grid}`, borderRadius: 4, background: '#fff', cursor: 'pointer', fontSize: 10.5, padding: '0 4px' }}>{checked ? 'Hide' : 'Show'}</button>
                    </span>
                    <span data-testid="objects-degree" title="relations" style={{ color: palette.muted, fontSize: 11, minWidth: 14, textAlign: 'right' }}>{r.degree}</span>
                  </div>
                );
              })}
            </div>
          );
        })}
      </div>
    </section>
  );
}

/** A package checkbox: checked, unchecked, or indeterminate (some of its cards shown). */
function GroupCheckbox({ checked, indeterminate, label, onToggle }: { checked: boolean; indeterminate: boolean; label: string; onToggle: () => void }) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => { if (ref.current) ref.current.indeterminate = indeterminate; }, [indeterminate]);
  return (
    <input
      ref={ref}
      type="checkbox"
      data-testid="objects-group-check"
      data-state={indeterminate ? 'mixed' : checked ? 'on' : 'off'}
      aria-checked={indeterminate ? 'mixed' : checked}
      aria-label={`Toggle the package ${label}`}
      checked={checked}
      onClick={(e) => e.stopPropagation()}
      onChange={onToggle}
    />
  );
}
