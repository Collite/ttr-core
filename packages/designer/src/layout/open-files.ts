// SPDX-License-Identifier: Apache-2.0
// Open layout… (VL contracts V-31): the files a user picks (or drops) → one layout document. YAML
// (`.yaml` / `.yml`), or TTR — a `.ttrg`, with its `.ttrl` sidecar when picked together (the sidecar's
// positions win, V-11). A `.ttrl` alone names no graph and is refused, naming the file it needs.

import type { LayoutDocument, LayoutIssue } from './layout-document.js';
import { fileStem } from './layout-document.js';
import { readYaml } from './layout-yaml.js';
import { mergeTtr } from './layout-ttr.js';
import type { Resolution } from './resolve.js';

export interface LayoutFileInput {
  name: string;
  text: string;
}

export type OpenedLayout =
  | { ok: true; doc: LayoutDocument; fileName: string; positionsFrom: 'in-file' | 'sidecar' | null; warnings: LayoutIssue[] }
  | { ok: false; error: string };

/** The file extensions Open layout… offers (V-9, V-31). */
export const LAYOUT_FILE_ACCEPT = '.yaml,.yml,.ttrg,.ttrl';

const ext = (name: string): string => /\.([^.]+)$/.exec(name)?.[1]?.toLowerCase() ?? '';

function describe(fileName: string, issues: LayoutIssue[]): string {
  const what = issues.map((e) => `${e.line !== undefined ? `line ${e.line}: ` : ''}${e.message}`).join('; ');
  return `${fileName}: ${what}`;
}

/** Read the picked files as one layout (V-31). */
export function readLayoutFiles(files: readonly LayoutFileInput[]): OpenedLayout {
  if (files.length === 0) return { ok: false, error: 'No file picked.' };
  const unknown = files.find((f) => !['yaml', 'yml', 'ttrg', 'ttrl'].includes(ext(f.name)));
  if (unknown) return { ok: false, error: `${unknown.name} is not a layout file (expected .yaml, .yml, .ttrg or .ttrl).` };
  const ttrg = files.filter((f) => ext(f.name) === 'ttrg');
  const ttrl = files.filter((f) => ext(f.name) === 'ttrl');
  const yaml = files.filter((f) => ext(f.name) === 'yaml' || ext(f.name) === 'yml');
  if (ttrg.length + yaml.length > 1) return { ok: false, error: 'Open one layout at a time (a .yaml, or a .ttrg with its .ttrl).' };
  if (ttrl.length > 1) return { ok: false, error: 'Pick one .ttrl sidecar with its .ttrg.' };

  if (ttrg.length === 1) {
    const g = ttrg[0];
    const r = mergeTtr(g.text, ttrl[0]?.text, { fileName: g.name });
    if (!r.ok) return { ok: false, error: describe(r.errors[0]?.path === 'ttrl' && ttrl[0] ? ttrl[0].name : g.name, r.errors) };
    return { ok: true, doc: r.doc, fileName: g.name, positionsFrom: r.positionsFrom, warnings: r.warnings };
  }
  if (yaml.length === 1) {
    if (ttrl.length > 0) return { ok: false, error: `${ttrl[0].name} is a sidecar of a .ttrg graph, not of ${yaml[0].name}.` };
    const y = yaml[0];
    const r = readYaml(y.text, { fileName: y.name });
    if (!r.ok) return { ok: false, error: describe(y.name, r.errors) };
    return { ok: true, doc: r.doc, fileName: y.name, positionsFrom: Object.keys(r.doc.positions).length > 0 ? 'in-file' : null, warnings: r.warnings };
  }
  const side = ttrl[0];
  return { ok: false, error: `${side.name} is a .ttrl sidecar — it names no graph on its own. Open it together with ${fileStem(side.name)}.ttrg.` };
}

/**
 * The document a tab holds after resolution (§4): every resolved ref as its full qname (so a viewer save
 * writes form 1, V-15), every missing / ambiguous ref verbatim (kept until removed), ignored relation /
 * foreign-key refs dropped (V-1 — Save as… writes cards only); positions keyed the same way.
 */
export function normalizeForTab(doc: LayoutDocument, r: Resolution): LayoutDocument {
  const ignored = new Set(r.ignored);
  const objects = [...new Set(doc.objects.filter((o) => !ignored.has(o)).map((o) => r.resolved.get(o) ?? o))];
  const kept = new Set(objects);
  const positions: LayoutDocument['positions'] = {};
  for (const [ref, p] of Object.entries(doc.positions)) {
    const key = r.resolved.get(ref) ?? ref;
    if (kept.has(key) && !(key in positions)) positions[key] = p;
  }
  const out: LayoutDocument = { ...doc, objects, positions };
  if (doc.collapsed) out.collapsed = [...new Set(doc.collapsed.map((c) => r.resolved.get(c) ?? c))].filter((c) => kept.has(c));
  return out;
}
