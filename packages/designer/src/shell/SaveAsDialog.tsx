// SPDX-License-Identifier: Apache-2.0
// Save as… (VL contracts V-32): name the layout, pick YAML or TTR, see the exact text, download it.
// The name is the file stem and the `.ttrg` graph name, so the Identifier rule is enforced as you type
// (V-4); a TTR keyword is a valid name but not a valid graph name, so TTR warns (VL-P0 Q1). The TTR form
// does not carry the skin (V-10) — the dialog says so when the layout has one. A ref that is not a TTR
// name has no TTR form at all (A-VL-5): the dialog names it and offers YAML.

import { useMemo, useState } from 'react';
import { canvas as palette } from '@tatrman/tokens';
import { IDENTIFIER, validateDocument, type LayoutDocument } from '../layout/layout-document.js';
import { writeYaml } from '../layout/layout-yaml.js';
import { isTtrId, ttrUnwritable, writeTtrg } from '../layout/layout-ttr.js';

export type LayoutFormat = 'yaml' | 'ttr';

/** The file a format writes: `<name>.yaml` (application/yaml, V-9) or `<name>.ttrg` (⚑VL-3). */
export function layoutFileOf(name: string, format: LayoutFormat): { fileName: string; mimeType: string } {
  return format === 'yaml' ? { fileName: `${name}.yaml`, mimeType: 'application/yaml' } : { fileName: `${name}.ttrg`, mimeType: 'text/plain' };
}

/** The canonical text of `doc` named `name` (V-5, V-7, V-10). */
export function layoutTextOf(doc: LayoutDocument, name: string, format: LayoutFormat): string {
  const named = { ...doc, name };
  return format === 'yaml' ? writeYaml(named) : writeTtrg(named);
}

/**
 * Why `doc` named `name` cannot be saved as `format` — what the readers would refuse (V-1…V-4), and for
 * TTR a ref the grammar cannot spell (A-VL-5) — so Save as… never writes a file Open layout… or the TTR
 * parser rejects. Empty ⇒ it can be saved.
 */
export function layoutWriteProblems(doc: LayoutDocument, name: string, format: LayoutFormat): string[] {
  const problems = validateDocument({ ...doc, name }).errors.map((e) => e.message);
  if (format === 'ttr') {
    const refs = ttrUnwritable(doc);
    const shown = refs.slice(0, 5).map((r) => `“${r}”`).join(', ') + (refs.length > 5 ? ` and ${refs.length - 5} more` : '');
    if (refs.length > 0) problems.push(`TTR cannot spell ${shown} — a .ttrg lists TTR names only. Save as YAML, or remove ${refs.length === 1 ? 'it' : 'them'} from the layout.`);
  }
  return problems;
}

export function SaveAsDialog({
  doc, initialName, initialFormat, onSave, onCancel,
}: {
  /** the layout as it will be written (cards, missing refs, positions, skin) */
  doc: LayoutDocument;
  initialName: string;
  initialFormat: LayoutFormat;
  onSave: (name: string, format: LayoutFormat) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(initialName);
  const [format, setFormat] = useState<LayoutFormat>(initialFormat);
  const valid = IDENTIFIER.test(name);
  const problems = useMemo(() => layoutWriteProblems(doc, valid ? name : doc.name, format), [doc, name, valid, format]);
  const preview = useMemo(() => (problems.length > 0 ? '' : layoutTextOf(doc, valid ? name : doc.name, format)), [doc, name, valid, format, problems]);
  const keyword = valid && format === 'ttr' && !isTtrId(name);
  const savable = valid && problems.length === 0;

  return (
    <div data-testid="save-as-dialog" role="dialog" aria-modal="true" aria-label="Save layout as"
      onKeyDown={(e) => { if (e.key === 'Escape') onCancel(); }}
      style={{ position: 'fixed', inset: 0, background: 'rgba(22,40,63,.25)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 50 }}>
      <form
        onSubmit={(e) => { e.preventDefault(); if (savable) onSave(name, format); }}
        style={{ background: '#fff', borderRadius: 10, padding: 18, width: 560, maxWidth: '92vw', boxShadow: '0 10px 30px rgba(0,0,0,.2)', fontSize: 13, display: 'flex', flexDirection: 'column', gap: 10 }}
      >
        <strong style={{ fontSize: 14 }}>Save as…</strong>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          Name
          <input data-testid="save-as-name" autoFocus value={name} onChange={(e) => setName(e.target.value.trim())}
            aria-invalid={!valid}
            style={{ font: 'inherit', padding: '5px 8px', border: `1px solid ${valid || !name ? palette.grid : palette.err}`, borderRadius: 6 }} />
          {!valid && name && (
            <span data-testid="save-as-name-error" style={{ color: palette.err, fontSize: 12 }}>
              Letters, digits and underscore; must not start with a digit.
            </span>
          )}
        </label>
        <fieldset style={{ border: 'none', padding: 0, margin: 0, display: 'flex', gap: 16 }}>
          <legend style={{ marginBottom: 4 }}>Format</legend>
          {(['yaml', 'ttr'] as const).map((f) => (
            <label key={f} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
              <input type="radio" name="layout-format" data-testid={`save-as-format-${f}`} checked={format === f} onChange={() => setFormat(f)} />
              {f === 'yaml' ? 'YAML' : 'TTR'} <span style={{ color: palette.muted, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 11.5 }}>({layoutFileOf(valid ? name : 'name', f).fileName})</span>
            </label>
          ))}
        </fieldset>
        {format === 'ttr' && doc.skin && (
          <p data-testid="save-as-skin-note" style={{ margin: 0, color: palette.warnInk, fontSize: 12 }}>
            The TTR file does not carry the notation ({doc.skin}) — save as YAML to keep it.
          </p>
        )}
        {keyword && (
          <p data-testid="save-as-keyword-note" style={{ margin: 0, color: palette.warnInk, fontSize: 12 }}>
            “{name}” is a TTR keyword: <code>graph {name} {'{'}</code> will not parse. Pick another name, or save as YAML.
          </p>
        )}
        {problems.length > 0 && (
          <div data-testid="save-as-problems" style={{ color: palette.err, fontSize: 12 }}>
            {problems.map((p) => <p key={p} data-testid="save-as-problem" style={{ margin: 0 }}>{p}</p>)}
          </div>
        )}
        <pre data-testid="save-as-preview" style={{ margin: 0, maxHeight: 260, overflow: 'auto', background: palette.tableHeaderBg, border: `1px solid ${palette.divider}`, borderRadius: 6, padding: 10, fontSize: 11.5 }}>
          {preview}
        </pre>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <button type="button" data-testid="save-as-cancel" onClick={onCancel}
            style={{ border: `1px solid ${palette.grid}`, borderRadius: 6, background: '#fff', cursor: 'pointer', padding: '4px 12px' }}>Cancel</button>
          <button type="submit" data-testid="save-as-save" disabled={!savable}
            style={{ border: 'none', borderRadius: 6, background: savable ? '#16283F' : palette.muted, color: '#fff', cursor: savable ? 'pointer' : 'default', padding: '4px 12px' }}>Save</button>
        </div>
      </form>
    </div>
  );
}
