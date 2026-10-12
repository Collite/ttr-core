// SPDX-License-Identifier: Apache-2.0
// The layout document (VL contracts §1, V-1…V-5) — one named picture of a schema: the CARDS it shows
// and, optionally, where they sit. Format-neutral: `layout-yaml.ts` and `layout-ttr.ts` are its two
// serializations of equal standing; `resolve.ts` maps its refs onto the current model.
//
// Derived, never stored (V-2): `mode` (manual iff positions is non-empty), the edges, the hop edges,
// missing / ambiguous / ignored refs — and never the viewport (C1-b / invariant 6).

/** The schema a layout's cards come from. `md`/`cnc` are admitted by the type, untested (G-5). */
export type LayoutModel = 'er' | 'db' | 'md' | 'cnc';
export const LAYOUT_MODELS: readonly LayoutModel[] = ['er', 'db', 'md', 'cnc'];

export interface LayoutPoint {
  x: number;
  y: number;
}

export interface LayoutDocument {
  /** Identifier — the `.ttrg` graph-name rule; also the file stem (V-4). */
  name: string;
  model: LayoutModel;
  /** Cards (entities; tables / views) as ObjectRefs — full qnames when a viewer wrote them (V-1, V-15). */
  objects: string[];
  /** Manual positions, keyed by a ref that is also in `objects` (V-3); `{}` = auto layout. */
  positions: Record<string, LayoutPoint>;
  /** Notation id (`er.crow`, `db.table-classic`). Not carried by the `.ttrg` block (V-10). */
  skin?: string;
  /** Reserved — carried, not acted on in v1. */
  collapsed?: string[];
}

/** One finding against a document: `path` names the field (`objects[2]`, `positions.<ref>.x`). */
export interface LayoutIssue {
  path: string;
  message: string;
  /** 1-based source line, when the finding comes from a serialization. */
  line?: number;
}

/** The `.ttrg` graph-name rule (V-4, V-13): ASCII letters, digits, underscore; no leading digit. */
export const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** A ref is any non-empty string without control characters (the schema's `objectRef`). */
const CONTROL = /[\u0000-\u001F\u007F]/;

/**
 * Order two strings by Unicode code point (V-5) — not by locale, and not by UTF-16 code unit (the two
 * differ above U+FFFF). `č` (U+010D) sorts after `z` (U+007A).
 */
export function compareCodePoints(a: string, b: string): number {
  const ia = a[Symbol.iterator]();
  const ib = b[Symbol.iterator]();
  for (;;) {
    const ca = ia.next();
    const cb = ib.next();
    if (ca.done || cb.done) return ca.done === cb.done ? 0 : ca.done ? -1 : 1;
    const d = ca.value.codePointAt(0)! - cb.value.codePointAt(0)!;
    if (d !== 0) return d;
  }
}

/** Round a coordinate to an integer, halves toward +∞ — `floor(x + 0.5)`; never `-0` (V-3). */
export function roundCoordinate(v: number): number {
  return Math.floor(v + 0.5) || 0;
}

function sortedUnique(refs: readonly string[]): string[] {
  return [...new Set(refs)].sort(compareCodePoints);
}

function duplicateWarnings(objects: readonly string[]): LayoutIssue[] {
  const seen = new Set<string>();
  const warned = new Set<string>();
  const out: LayoutIssue[] = [];
  for (const ref of objects) {
    if (seen.has(ref) && !warned.has(ref)) {
      warned.add(ref);
      out.push({ path: 'objects', message: `duplicate object ${ref} (kept once)` });
    }
    seen.add(ref);
  }
  return out;
}

/**
 * The canonical form (V-5): objects de-duplicated and sorted by code point, positions keys sorted,
 * coordinates rounded, empty optional fields dropped. Idempotent; never mutates its input.
 */
export function canonicalize(doc: LayoutDocument): { doc: LayoutDocument; warnings: LayoutIssue[] } {
  const positions: Record<string, LayoutPoint> = {};
  for (const key of Object.keys(doc.positions ?? {}).sort(compareCodePoints)) {
    const p = doc.positions[key];
    positions[key] = { x: roundCoordinate(p.x), y: roundCoordinate(p.y) };
  }
  const out: LayoutDocument = { name: doc.name, model: doc.model, objects: sortedUnique(doc.objects), positions };
  if (doc.skin) out.skin = doc.skin;
  if (doc.collapsed && doc.collapsed.length > 0) out.collapsed = sortedUnique(doc.collapsed);
  return { doc: out, warnings: duplicateWarnings(doc.objects) };
}

function refErrors(path: string, ref: unknown): LayoutIssue[] {
  if (typeof ref !== 'string' || ref.length === 0) return [{ path, message: 'a ref must be a non-empty string' }];
  if (CONTROL.test(ref)) return [{ path, message: 'a ref must not contain control characters' }];
  return [];
}

/**
 * Check a document against V-1…V-4. Errors make it unreadable; warnings (duplicate objects) are
 * reported and repaired by `canonicalize`.
 */
export function validateDocument(doc: LayoutDocument): { ok: boolean; errors: LayoutIssue[]; warnings: LayoutIssue[] } {
  const errors: LayoutIssue[] = [];
  if (typeof doc.name !== 'string' || !IDENTIFIER.test(doc.name)) {
    errors.push({ path: 'name', message: `name must be an identifier — letters, digits, underscore, not starting with a digit (got ${JSON.stringify(doc.name)})` });
  }
  if (!LAYOUT_MODELS.includes(doc.model)) {
    errors.push({ path: 'model', message: `model must be one of ${LAYOUT_MODELS.join(', ')} (got ${doc.model})` });
  }
  doc.objects.forEach((ref, i) => errors.push(...refErrors(`objects[${i}]`, ref)));

  const listed = new Set(doc.objects);
  for (const [key, p] of Object.entries(doc.positions ?? {})) {
    if (!listed.has(key)) {
      errors.push({ path: `positions.${key}`, message: `positions key ${key} is not in objects` });
      continue;
    }
    for (const axis of ['x', 'y'] as const) {
      if (typeof p?.[axis] !== 'number' || !Number.isFinite(p[axis])) {
        errors.push({ path: `positions.${key}.${axis}`, message: `${axis} must be a finite number` });
      }
    }
  }
  (doc.collapsed ?? []).forEach((ref, i) => errors.push(...refErrors(`collapsed[${i}]`, ref)));
  if (doc.skin !== undefined && (typeof doc.skin !== 'string' || CONTROL.test(doc.skin))) {
    errors.push({ path: 'skin', message: 'skin must be a string without control characters' });
  }
  return { ok: errors.length === 0, errors, warnings: duplicateWarnings(doc.objects) };
}

/** `mode` is derived, never stored (V-2): manual iff the document carries positions. */
export function modeOf(doc: LayoutDocument): 'auto' | 'manual' {
  return Object.keys(doc.positions ?? {}).length > 0 ? 'manual' : 'auto';
}

/** What every layout reader returns: the canonical document, or the reasons it could not be read. */
export type LayoutReadResult =
  | { ok: true; doc: LayoutDocument; warnings: LayoutIssue[] }
  | { ok: false; errors: LayoutIssue[]; warnings: LayoutIssue[] };

export interface ReadOptions {
  /** The file the text came from — only to warn when its stem differs from the layout's name (V-4). */
  fileName?: string;
}

/** `downloads/sales (1).yaml` → `sales (1)`; the layout name a VL writer would have used for the file. */
export function fileStem(fileName: string): string {
  const base = fileName.split(/[\\/]/).pop() ?? fileName;
  return base.replace(/\.(ya?ml|ttrg|ttrl)$/i, '');
}

export function stemWarning(fileName: string | undefined, name: string): LayoutIssue[] {
  if (!fileName || fileStem(fileName) === name) return [];
  const base = fileName.split(/[\\/]/).pop() ?? fileName;
  return [{ path: 'name', message: `file name ${base} does not match the layout name ${name}` }];
}
