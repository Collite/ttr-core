// SPDX-License-Identifier: Apache-2.0
import { uriFor } from '../fs/source-uri.js';
import { useId, useState } from 'react';
import type { WorkerDiagnostic } from '@tatrman/lsp';
type SourceFile = { path: string; text: string };
/** Open text authoring. Apply changes the one worker cache; Studio Save persists that cache. */
export function SourceFilesDrawer({
  visible = true,
  files,
  write,
  onDiagnostics,
  onClose,
}: {
  visible?: boolean;
  files: () => SourceFile[];
  write: (file: SourceFile & { expectedText?: string }) => Promise<WorkerDiagnostic[]>;
  onDiagnostics?: (diagnostics: WorkerDiagnostic[]) => void;
  onClose: () => void;
}) {
  const id = useId();
  const [selected, setSelected] = useState('');
  const [path, setPath] = useState('model/er/new.ttrm');
  const [text, setText] = useState('');
  const [baseline, setBaseline] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [diagnostics, setDiagnostics] = useState<WorkerDiagnostic[]>([]);
  const options = files();
  const canLeave = () => text === baseline || window.confirm('Discard unapplied source text?');
  const close = () => {
    if (!busy && canLeave()) onClose();
  };
  const apply = async () => {
    if (busy || !visible) return;
    if (selected && files().find((file) => file.path === selected)?.text !== baseline) {
      setError("This file changed since you opened it — reload it into the editor.");
      return;
    }
    setError(undefined);
    if (!selected && options.some((file) => file.path === path)) {
      setError('That source path already exists. Select it to edit the file.');
      return;
    }
    setBusy(true);
    try {
      const values = await write({ path, text, ...(selected ? { expectedText: baseline } : {}) });
      setBaseline(text);
      setDiagnostics(values);
      onDiagnostics?.(values);
      if (
        !values.some(
          (value) =>
            value.severity === 1 &&
            value.uri === uriFor('file:///repo', path)
        )
      )
        onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <aside
      role="dialog"
      aria-label="Source files"
      style={{
        position: 'absolute',
        inset: '0 0 0 auto',
        width: 'min(680px, 100%)',
        zIndex: 1000,
        background: '#fff',
        color: '#16283f',
        colorScheme: 'light',
        borderLeft: '1px solid #cbd8e6',
        padding: 16,
        display: visible ? 'flex' : 'none',
        flexDirection: 'column',
        gap: 8,
        overflowY: 'auto',
        boxShadow: '-6px 0 20px #16283f20',
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault();
          close();
        }
      }}
    >
      <header style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h2>Source files</h2>
        <button type="button" disabled={busy} onClick={close}>
          Close source files
        </button>
      </header>
      <label htmlFor={id + '-file'}>File</label>
      <select
        id={id + '-file'}
        value={selected}
        disabled={busy}
        onChange={(event) => {
          if (!canLeave()) return;
          const value = event.target.value;
          const file = options.find((file) => file.path === value);
          setSelected(value);
          setPath(file?.path ?? 'model/er/new.ttrm');
          setText(file?.text ?? '');
          setBaseline(file?.text ?? '');
          setError(undefined);
          setDiagnostics([]);
        }}
      >
        <option value="">New source file</option>
        {options.map((file) => (
          <option key={file.path} value={file.path}>
            {file.path}
          </option>
        ))}
      </select>
      <label htmlFor={id + '-path'}>Source path</label>
      <input
        id={id + '-path'}
        value={path}
        disabled={busy || !!selected}
        onChange={(event) => setPath(event.target.value)}
      />
      <label htmlFor={id + '-text'}>Source text</label>
      <textarea
        id={id + '-text'}
        value={text}
        disabled={busy}
        spellCheck={false}
        onChange={(event) => setText(event.target.value)}
        style={{
          flex: 1,
          minHeight: 160,
          width: '100%',
          fontFamily: 'monospace',
          fontSize: 13,
          border: '1px solid #cbd8e6',
          padding: 8,
        }}
      />
      <p>Apply text to the model, then Save the change in Studio to persist it.</p>
      {error && <p role="alert">{error}</p>}
      {diagnostics.length > 0 && (
        <div role="status" style={{ maxHeight: 140, overflow: 'auto' }}>
          {diagnostics.map((value, index) => (
            <p key={index}>
              {value.uri}:{value.range.start.line + 1} · {value.message}
            </p>
          ))}
        </div>
      )}
      <button
        type="button"
        disabled={busy || !path || !text.trim() || text === baseline}
        onClick={() => void apply()}
      >
        {busy ? 'Applying source…' : 'Apply to model'}
      </button>
    </aside>
  );
}
