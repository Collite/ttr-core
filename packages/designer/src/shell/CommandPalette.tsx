// ⌘K command palette (DS-P2.S2 / contracts §6). A controlled overlay: an input that filters
// the registry and a results list. Enter runs the top result; clicking a row runs that command;
// Escape closes. `useCmdKShortcut` wires the global ⌘K / Ctrl+K opener.

import { useEffect, useState } from 'react';
import { usePageEnvironment } from '../page/page-context.js';
import type { CommandRegistry } from './commands.js';

export function CommandPalette({
  registry,
  open,
  onClose,
}: {
  registry: CommandRegistry;
  open: boolean;
  onClose: () => void;
}) {
  const [query, setQuery] = useState('');

  // Reset the query each time the palette opens so it starts blank.
  useEffect(() => {
    if (open) setQuery('');
  }, [open]);

  if (!open) return null;

  const results = registry.find(query);

  const runAndClose = (id: string): void => {
    registry.execute(id);
    onClose();
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const top = results.find((c) => !c.disabled);
      if (top) runAndClose(top.id);
    }
  };

  return (
    <div className="cmdk-overlay" role="dialog" aria-modal="true" onClick={onClose}>
      <div className="cmdk-panel" onClick={(e) => e.stopPropagation()}>
        <input
          data-testid="cmdk-input"
          className="cmdk-input"
          autoFocus
          type="text"
          placeholder="Type a command…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={onKeyDown}
        />
        <ul className="cmdk-list" role="listbox">
          {results.map((c) => (
            <li
              key={c.id}
              data-testid="cmdk-item"
              data-command={c.id}
              className="cmdk-item"
              role="option"
              aria-disabled={c.disabled || undefined}
              onClick={() => { if (!c.disabled) runAndClose(c.id); }}
              style={c.disabled ? { opacity: 0.45, cursor: 'default' } : undefined}
            >
              {c.title}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

/** Registers a window keydown listener for ⌘K / Ctrl+K that calls `onOpen`; cleans up on unmount. */
export function useCmdKShortcut(onOpen: () => void): void {
  const page = usePageEnvironment();
  useEffect(() => {
    const handler = (event: KeyboardEvent): void => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        if (page && (!(event.target instanceof Node) || !page.root.current?.contains(event.target))) return;
        event.preventDefault();
        onOpen();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onOpen, page]);
}
