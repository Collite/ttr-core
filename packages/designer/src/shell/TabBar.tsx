// Subject tab bar (A-2 β / S-7). Tab title = kind-prefixed subject label; the active tab is
// highlighted; a preview tab renders ITALIC until pinned. Double-click or the pin affordance
// promotes a preview tab (S-7 promotion triggers).

import type { ShellState } from './types.js';

export function TabBar({
  state, onFocus, onPromote, onClose, onContextMenu,
}: {
  state: ShellState;
  onFocus: (id: string) => void;
  onPromote: (id: string) => void;
  onClose: (id: string) => void;
  /** a right-click on a tab — the shell's tab menu (the layout commands, VL V-33) */
  onContextMenu?: (id: string, clientX: number, clientY: number) => void;
}) {
  if (state.tabs.length === 0) return null;
  return (
    <div role="tablist" data-testid="tab-bar" style={{ display: 'flex', alignItems: 'stretch', background: '#f5f8fc', borderBottom: '1px solid #CBD8E6', flex: '0 0 auto', overflowX: 'auto' }}>
      {state.tabs.map((t) => {
        const active = t.id === state.activeTabId;
        return (
          <div
            key={t.id}
            role="tab"
            aria-selected={active}
            data-testid="subject-tab"
            data-preview={t.preview || undefined}
            data-active={active || undefined}
            onClick={() => onFocus(t.id)}
            onDoubleClick={() => onPromote(t.id)}
            onContextMenu={onContextMenu ? (e) => { e.preventDefault(); onContextMenu(t.id, e.clientX, e.clientY); } : undefined}
            title={t.subject.ref}
            style={{
              display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px', cursor: 'pointer',
              fontSize: 13, fontStyle: t.preview ? 'italic' : 'normal',
              color: active ? '#16283F' : '#4A4B4D',
              background: active ? '#fff' : 'transparent',
              borderRight: '1px solid #CBD8E6',
              borderBottom: active ? '2px solid #F2A200' : '2px solid transparent',
              fontWeight: active ? 'bold' : 'normal',
            }}
          >
            <span>{t.subject.label}</span>
            {t.layout?.dirty && <span data-testid="tab-dirty" title="unsaved changes" style={{ color: '#F2A200', fontWeight: 'bold' }}>*</span>}
            {t.preview && (
              <button
                data-testid="pin-tab"
                title="Pin tab"
                onClick={(e) => { e.stopPropagation(); onPromote(t.id); }}
                style={{ border: 'none', background: 'transparent', cursor: 'pointer', fontSize: 11, color: '#96989B', padding: 0 }}
              >
                📌
              </button>
            )}
            <button
              data-testid="close-tab"
              title="Close tab"
              onClick={(e) => { e.stopPropagation(); onClose(t.id); }}
              style={{ border: 'none', background: 'transparent', cursor: 'pointer', fontSize: 13, color: '#96989B', padding: 0 }}
            >
              ×
            </button>
          </div>
        );
      })}
    </div>
  );
}
