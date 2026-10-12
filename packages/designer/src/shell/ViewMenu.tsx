// SPDX-License-Identifier: Apache-2.0
// The view menu (VL contracts V-27, ⚑VL-13): what a right-click on a card or a hop edge offers in the
// open viewer — Expand (+n) · Expand all levels… · Hide · Hide others · Inspect; on a hop edge Show <H>.
// VIEW actions only (they change what the layout shows, never the model): a sibling of the FO-21 edit
// menu, never a replacement. The shell builds the items; this renders them.

import { canvas as palette } from '@tatrman/tokens';

/** What was right-clicked: a card (by node id), or an edge (a hop edge — by edge id). */
export type ViewMenuTarget = { kind: 'node'; id: string } | { kind: 'edge'; id: string };

export type ViewMenuEntry =
  | { id: string; label: string; hint?: string; disabled?: boolean; run: () => void }
  | { separator: true };

export function ViewMenu({ items, onClose }: { items: readonly ViewMenuEntry[]; onClose: () => void }) {
  return (
    <div data-testid="view-menu" role="menu" style={{ minWidth: 200, padding: '4px 0' }}>
      {items.map((it, i) =>
        'separator' in it ? (
          <hr key={`sep-${i}`} style={{ border: 'none', borderTop: `1px solid ${palette.divider}`, margin: '4px 0' }} />
        ) : (
          <button
            key={it.id}
            role="menuitem"
            data-testid="view-menu-item"
            data-item={it.id}
            disabled={it.disabled}
            onClick={(e) => { e.stopPropagation(); onClose(); it.run(); }}
            style={{
              display: 'flex', width: '100%', alignItems: 'baseline', gap: 12, border: 'none', background: 'transparent', textAlign: 'left',
              padding: '5px 12px', font: 'inherit', fontSize: 13, cursor: it.disabled ? 'default' : 'pointer',
              color: it.disabled ? palette.muted : palette.ink,
            }}
          >
            <span style={{ flex: 1 }}>{it.label}</span>
            {it.hint && <span style={{ fontSize: 11, color: palette.muted, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>{it.hint}</span>}
          </button>
        ),
      )}
    </div>
  );
}
