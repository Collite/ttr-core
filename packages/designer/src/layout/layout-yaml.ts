// SPDX-License-Identifier: Apache-2.0
// The YAML serialization of a layout — `tatrman-layout`, format version 1 (VL contracts §2, V-6…V-9).
// A Collite format with a published JSON Schema (`packages/package-sdk/schemas/layout.schema.json`).
//
// The writer emits text by hand (not through a YAML stringifier) because its bytes are a contract: a
// re-save of an unchanged layout is a zero diff, and every other writer of the format reproduces the
// same bytes from the shared vectors (V-5, V-46). The reader is the `yaml` package — any valid
// YAML 1.2 of the right shape is accepted (V-8).

import { isMap, isScalar, isSeq, LineCounter, parseDocument, type Node as YamlNode } from 'yaml';
import {
  canonicalize,
  stemWarning,
  validateDocument,
  type LayoutDocument,
  type LayoutIssue,
  type LayoutModel,
  type LayoutPoint,
  type LayoutReadResult,
  type ReadOptions,
} from './layout-document.js';

/** The docs page every written file links to (V-7). */
export const LAYOUT_DOCS_URL = 'https://docs.tatrman.org/model/layout-files/';
/** The first line of every written file (V-7). */
export const LAYOUT_YAML_HEADER = `# Tatrman layout — ${LAYOUT_DOCS_URL} (format 1)`;
/** The format version this reader and writer speak (V-6). */
export const LAYOUT_FORMAT_VERSION = 1;

const ROOT_KEYS = ['layout', 'name', 'model', 'objects', 'positions', 'skin', 'collapsed'] as const;
const POINT_KEYS = ['x', 'y'] as const;

/** Plain-scalar shape: the TTR id letters, digits, `_`, `.`, `-`; never a leading digit, dot or dash. */
const PLAIN = /^[A-Za-z_\u00C0-\u024F][A-Za-z0-9_.\u00C0-\u024F-]*$/;
/** Words a YAML 1.1 or 1.2 reader resolves to a boolean or null when plain (compared lowercased). */
const NOT_A_STRING = new Set(['true', 'false', 'yes', 'no', 'on', 'off', 'y', 'n', 'null']);

/**
 * One string as the writer spells it: plain when it is unambiguous to every YAML reader (1.1 and 1.2),
 * else double-quoted with `\` and `"` escaped. Refs carry no control characters (validateDocument), so
 * those are the only two escapes.
 */
export function yamlScalar(s: string): string {
  if (PLAIN.test(s) && !NOT_A_STRING.has(s.toLowerCase())) return s;
  return `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

export interface WriteYamlOptions {
  /** Extra comment lines after the first one (a generated layout's provenance, V-7); `# ` is prefixed. */
  header?: string[];
}

/** Serialize `doc` in the canonical YAML form (V-5, V-7). Canonicalizes first; never mutates `doc`. */
export function writeYaml(doc: LayoutDocument, opts: WriteYamlOptions = {}): string {
  const d = canonicalize(doc).doc;
  const lines = [LAYOUT_YAML_HEADER];
  for (const h of (opts.header ?? []).flatMap((l) => l.split('\n'))) lines.push(h ? `# ${h}` : '#');
  lines.push(`layout: ${LAYOUT_FORMAT_VERSION}`, `name: ${yamlScalar(d.name)}`, `model: ${yamlScalar(d.model)}`);
  if (d.objects.length === 0) lines.push('objects: []');
  else lines.push('objects:', ...d.objects.map((o) => `  - ${yamlScalar(o)}`));
  const keys = Object.keys(d.positions);
  if (keys.length > 0) {
    lines.push('positions:', ...keys.map((k) => `  ${yamlScalar(k)}: { x: ${d.positions[k].x}, y: ${d.positions[k].y} }`));
  }
  if (d.skin) lines.push(`skin: ${yamlScalar(d.skin)}`);
  if (d.collapsed && d.collapsed.length > 0) lines.push('collapsed:', ...d.collapsed.map((c) => `  - ${yamlScalar(c)}`));
  return lines.join('\n') + '\n';
}

/** Parse YAML text into a layout document (V-6, V-8). */
export function readYaml(text: string, opts: ReadOptions = {}): LayoutReadResult {
  const lc = new LineCounter();
  const ydoc = parseDocument(text, { lineCounter: lc, uniqueKeys: true, version: '1.2', prettyErrors: false });
  const lineOf = (n: YamlNode | null | undefined): number | undefined =>
    n?.range ? lc.linePos(n.range[0]).line : undefined;

  if (ydoc.errors.length > 0) {
    return {
      ok: false,
      warnings: [],
      errors: ydoc.errors.map((e) => ({
        path: '',
        message: e.message.split('\n')[0],
        ...(e.linePos ? { line: e.linePos[0].line } : {}),
      })),
    };
  }
  const root = ydoc.contents;
  if (!isMap(root)) {
    return { ok: false, warnings: [], errors: [{ path: '', message: 'a layout file is a YAML mapping (layout, name, model, objects, …)', line: lineOf(root) }] };
  }

  // key → [key node, value node], in document order
  const entries = new Map<string, { key: YamlNode; value: YamlNode | null }>();
  const errors: LayoutIssue[] = [];
  for (const pair of root.items) {
    const key = pair.key as YamlNode;
    const k = isScalar(key) ? String(key.value) : String(key);
    entries.set(k, { key, value: (pair.value as YamlNode | null) ?? null });
  }

  // the format version first — a newer file is refused whole, never partially read (V-6)
  const version = entries.get('layout');
  if (!version) {
    errors.push({ path: 'layout', message: 'missing required key `layout` (the format version, 1)' });
  } else {
    const v = isScalar(version.value) ? version.value.value : undefined;
    if (typeof v === 'number' && Number.isInteger(v) && v > LAYOUT_FORMAT_VERSION) {
      return { ok: false, warnings: [], errors: [{ path: 'layout', message: `layout format ${v} is newer than this reader`, line: lineOf(version.key) }] };
    }
    if (v !== LAYOUT_FORMAT_VERSION) {
      errors.push({ path: 'layout', message: `layout must be ${LAYOUT_FORMAT_VERSION}`, line: lineOf(version.value ?? version.key) });
    }
  }
  for (const [k, { key }] of entries) {
    if (!(ROOT_KEYS as readonly string[]).includes(k)) errors.push({ path: k, message: `unknown key \`${k}\``, line: lineOf(key) });
  }

  const lines = new Map<string, number | undefined>();
  const str = (path: string, node: YamlNode | null | undefined): string | undefined => {
    lines.set(path, lineOf(node));
    if (isScalar(node) && typeof node.value === 'string') return node.value;
    errors.push({ path, message: `${path} must be a string`, line: lineOf(node) });
    return undefined;
  };
  const required = (k: string): { key: YamlNode; value: YamlNode | null } | undefined => {
    const e = entries.get(k);
    if (!e) errors.push({ path: k, message: `missing required key \`${k}\`` });
    return e;
  };
  const strList = (path: string, node: YamlNode | null): string[] => {
    if (!isSeq(node)) {
      errors.push({ path, message: `${path} must be a list`, line: lineOf(node) });
      return [];
    }
    return node.items.flatMap((item, i) => str(`${path}[${i}]`, item as YamlNode) ?? []);
  };

  const nameEntry = required('name');
  const name = nameEntry ? str('name', nameEntry.value) : undefined;
  const modelEntry = required('model');
  const model = modelEntry ? str('model', modelEntry.value) : undefined;
  const objectsEntry = required('objects');
  const objects = objectsEntry ? strList('objects', objectsEntry.value) : [];

  const positions: Record<string, LayoutPoint> = {};
  const posEntry = entries.get('positions');
  if (posEntry) {
    if (!isMap(posEntry.value)) {
      errors.push({ path: 'positions', message: 'positions must be a mapping of ref → { x, y }', line: lineOf(posEntry.value ?? posEntry.key) });
    } else {
      for (const pair of posEntry.value.items) {
        const keyNode = pair.key as YamlNode;
        const ref = str(`positions.${isScalar(keyNode) ? String(keyNode.value) : '?'}`, keyNode);
        if (ref === undefined) continue;
        const path = `positions.${ref}`;
        lines.set(path, lineOf(keyNode));
        const point = pair.value as YamlNode | null;
        if (!isMap(point)) {
          errors.push({ path, message: `${path} must be { x, y }`, line: lineOf(point ?? keyNode) });
          continue;
        }
        const xy: Partial<LayoutPoint> = {};
        for (const pp of point.items) {
          const ck = pp.key as YamlNode;
          const axis = isScalar(ck) ? String(ck.value) : String(ck);
          if (!(POINT_KEYS as readonly string[]).includes(axis)) {
            errors.push({ path: `${path}.${axis}`, message: `unknown key \`${axis}\``, line: lineOf(ck) });
            continue;
          }
          const v = isScalar(pp.value) ? (pp.value as { value: unknown }).value : undefined;
          if (typeof v !== 'number') {
            errors.push({ path: `${path}.${axis}`, message: `${axis} must be a number`, line: lineOf(pp.value as YamlNode) });
            continue;
          }
          xy[axis as 'x' | 'y'] = v;
        }
        for (const axis of POINT_KEYS) {
          if (xy[axis] === undefined && !errors.some((e) => e.path === `${path}.${axis}`)) {
            errors.push({ path: `${path}.${axis}`, message: `missing ${axis}`, line: lineOf(point) });
          }
        }
        if (xy.x !== undefined && xy.y !== undefined) positions[ref] = { x: xy.x, y: xy.y };
      }
    }
  }
  const skinEntry = entries.get('skin');
  const skin = skinEntry ? str('skin', skinEntry.value) : undefined;
  const collapsedEntry = entries.get('collapsed');
  const collapsed = collapsedEntry ? strList('collapsed', collapsedEntry.value) : undefined;

  if (errors.length > 0 || name === undefined || model === undefined) return { ok: false, errors, warnings: [] };

  const doc: LayoutDocument = { name, model: model as LayoutModel, objects, positions };
  if (skin !== undefined) doc.skin = skin;
  if (collapsed !== undefined) doc.collapsed = collapsed;
  const v = validateDocument(doc);
  if (!v.ok) return { ok: false, errors: v.errors.map((e) => ({ ...e, line: lines.get(e.path) })), warnings: v.warnings };
  return { ok: true, doc: canonicalize(doc).doc, warnings: [...v.warnings, ...stemWarning(opts.fileName, name)] };
}
