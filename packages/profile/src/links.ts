// SPDX-License-Identifier: Apache-2.0
import { parseAllDocuments } from 'yaml';
import { readdirSync, realpathSync, existsSync, statSync } from 'node:fs';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import {
  ForeignTree,
  JsonPointer,
  isRecord,
  NumericScalar,
  Coordinates,
  type Json,
} from './input.js';
import { readProfileFile } from './file-input.js';
import { ProfileException, type ProfileSpan, type ProfileDefinition } from './loading.js';
import type { Record, RecordPlan, CanonicalBuilder, SourceEntry } from './engine.js';
import { ProfileVocabulary } from './vocabulary.js';

export interface LinkEntry {
  qname: string;
  kind: string;
  pkg: string;
  sourceName?: string | null;
  iri?: string | null;
  path?: string | null;
  record?: Record;
}
export function bytewise(a: string, b: string): number {
  const x = new TextEncoder().encode(a),
    y = new TextEncoder().encode(b);
  for (let i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return x[i] - y[i];
  return x.length - y.length;
}
export const Bytewise = { compare: bytewise };
const chain = (record: Record): Record[] => {
  const result = [record];
  while (result[0].parent) result.unshift(result[0].parent!);
  return result;
};
export const Qnames = {
  of(record: Record, pkg: string): string {
    const records = chain(record),
      top = records[0],
      model = ProfileVocabulary.model(top.kind);
    return [
      ...(pkg ? [pkg] : []),
      model,
      model === 'db' ? 'dbo' : top.kind,
      ...records.map((r) => r.id),
    ].join('.');
  },
  path(record: Record): string {
    return chain(record)
      .map((r) => r.id)
      .join('/');
  },
};
const span = (file: string, path = ''): ProfileSpan => ({
  file,
  line: 1,
  column: 1,
  endLine: 1,
  endColumn: 1,
  path,
});
const object = isRecord;
export const SourcesJsonReader = {
  read(root: string): LinkEntry[] {
    let top: string;
    try {
      top = realpathSync(root);
    } catch {
      throw new ProfileException(
        'ys/profile-invalid',
        span('.'),
        'Cannot walk for sidecars: the root is unreadable'
      );
    }
    const files: string[] = [];
    const walk = (directory: string) => {
      try {
        for (const entry of readdirSync(directory, { withFileTypes: true })) {
          const file = resolve(directory, entry.name);
          if (entry.isDirectory()) {
            if (
              !entry.name.startsWith('.') &&
              entry.name !== 'build' &&
              entry.name !== 'node_modules'
            )
              walk(file);
          } else if (
            entry.isFile() &&
            entry.name.endsWith('.sources.json') &&
            entry.name.length > '.sources.json'.length
          )
            files.push(relative(top, file).split(sep).join('/'));
        }
      } catch (error) {
        if (error instanceof ProfileException) throw error;
        const file = relative(top, directory).split(sep).join('/') || '.';
        throw new ProfileException(
          'ys/profile-invalid',
          span(file),
          `Cannot walk for sidecars: ${file} is unreadable`
        );
      }
    };
    walk(top);
    return files.sort().flatMap((file) => {
      const invalid = (detail: string): never => {
        throw new ProfileException('ys/profile-invalid', span(file), detail);
      };
      let value: Json;
      try {
        value = JSON.parse(readProfileFile(resolve(top, file), file)) as Json;
      } catch (error) {
        return invalid(`Cannot read sidecar JSON: ${String(error)}`);
      }
      if (!object(value) || !Array.isArray(value.entries))
        return invalid('Sidecar needs an entries list');
      return value.entries.map((entry, i) => {
        if (!object(entry)) return invalid(`Sidecar entry ${i} is not an object`);
        const text = (key: string): string | undefined => {
          const v = entry[key];
          if (v === null || v === undefined) return undefined;
          if (typeof v !== 'string') return invalid(`Sidecar entry ${i} ${key} is not a string`);
          return v;
        };
        return {
          qname: text('qname') ?? invalid(`Sidecar entry ${i} needs a qname`),
          kind: text('kind') ?? invalid(`Sidecar entry ${i} needs a kind`),
          pkg: basename(file).slice(0, -'.sources.json'.length),
          sourceName: text('sourceName'),
          iri: text('foreignId'),
          path: text('locator'),
        };
      });
    });
  },
};
export class LinkIndex {
  private keys?: Map<string, Map<string, LinkEntry[]>>;
  constructor(
    readonly run: LinkEntry[],
    readonly sidecars: () => LinkEntry[] = () => []
  ) {}
  resolve(by: string, value: string, kind?: string | null): LinkEntry[] {
    if (!this.keys) {
      const keys = new Map<string, Map<string, LinkEntry[]>>();
      const entries = [...this.run, ...this.sidecars()];
      for (const key of ['sourceName', 'iri', 'path'] as const) {
        const map = new Map<string, LinkEntry[]>();
        for (const entry of entries) {
          const value = entry[key];
          if (value !== null && value !== undefined)
            map.set(value, [...(map.get(value) ?? []), entry]);
        }
        keys.set(key, map);
      }
      this.keys = keys;
    }
    const found = new Set<string>();
    return (this.keys.get(by)?.get(value) ?? []).filter((e) => {
      if ((kind != null && e.kind !== kind) || found.has(e.qname)) return false;
      found.add(e.qname);
      return true;
    });
  }
  qname(value: string): LinkEntry[] {
    return this.run.filter((e) => e.qname === value);
  }
  sources(): SourceEntry[] {
    return this.run.map((e) => ({
      qname: e.qname,
      kind: e.kind,
      pkg: e.pkg,
      foreignId: e.iri ?? null,
      sourceName: e.sourceName ?? null,
      locator: e.path ?? null,
    }));
  }
  static build(
    plans: RecordPlan[],
    packages: Map<RecordPlan, string>,
    root?: string,
    sourceName?: JsonPointer
  ): LinkIndex {
    return new LinkIndex(
      plans.flatMap((plan) =>
        plan.records.map((record) => {
          const pkg = packages.get(plan)!;
          const name = sourceName?.resolve(record.source)?.value;
          return {
            qname: Qnames.of(record, pkg),
            kind: record.kind,
            pkg,
            sourceName: typeof name === 'string' ? name : record.id,
            path: Qnames.path(record),
            record,
          };
        })
      ),
      () => (root ? SourcesJsonReader.read(root) : [])
    );
  }
}
export interface OverlayEntry {
  key: string;
  property: string;
  value: Json;
  keyLocation: ProfileSpan;
  valueLocation: ProfileSpan;
}
export interface Overridden {
  qname: string;
  property: string;
  from: Json;
  to: Json;
}
export interface RenameEntry {
  from: string;
  to: string;
  since: string;
}
export const Renames = {
  read(root?: string): RenameEntry[] {
    if (!root) return [];
    const file = resolve(root, 'renames.yaml');
    if (!existsSync(file) || !statSync(file).isFile()) return [];
    let source: string;
    try {
      source = readProfileFile(file, 'renames.yaml');
    } catch (error) {
      if (error instanceof ProfileException) throw error;
      throw new ProfileException(
        'ys/profile-invalid',
        span('renames.yaml'),
        'Cannot read renames.yaml'
      );
    }
    // Empty/comment-only ledgers contain no document and therefore no renames.
    if (source.split(/\r\n?|\n/).every((line) => /^\s*(?:#.*)?$/.test(line))) return [];
    // This ledger uses Kotlin's plain Load, not the dialect reader's safety policy.
    source = source.replace(/\r(?!\n)/g, '\n');
    const documents = parseAllDocuments(source, { schema: 'core', merge: false });
    const error = documents[0]?.errors[0] ?? documents[0]?.warnings[0];
    const offset = error?.pos[0] ?? documents[1]?.range?.[0];
    if (offset !== undefined) {
      const mark = new Coordinates(source, 'renames.yaml').span([offset, offset]);
      throw new ProfileException(
        'ys/profile-invalid',
        {
          file: 'renames.yaml',
          line: mark.line,
          column: mark.column + 1,
          endLine: mark.line,
          endColumn: mark.column + 1,
          path: '',
        },
        'renames.yaml is not YAML: ' + (error?.message ?? 'Expected a single document')
      );
    }
    let value: unknown;
    try {
      value = documents[0]?.toJS({ maxAliasCount: 50 }) ?? null;
    } catch (error) {
      throw new ProfileException(
        'ys/profile-invalid',
        span('renames.yaml'),
        'renames.yaml is not YAML: ' + String(error)
      );
    }
    const invalid = (path: string, detail: string): never => {
      throw new ProfileException('ys/profile-invalid', span('renames.yaml', path), detail);
    };
    if (value === null) return [];
    if (!Array.isArray(value)) return invalid('', 'renames.yaml must be a list');
    return value.map((entry: unknown, i: number) => {
      if (!object(entry) || Object.keys(entry).sort().join(',') !== 'from,since,to')
        return invalid(`/${i}`, 'rename requires from, to, since');
      const text = (key: string): string =>
        typeof entry[key] === 'string' && entry[key].trim()
          ? (entry[key] as string)
          : invalid(`/${i}/${key}`, `rename ${key} must be a nonempty string`);
      return { from: text('from'), to: text('to'), since: text('since') };
    });
  },
};
const cnStereotypes = new Set([
  'kind',
  'subkind',
  'variant',
  'role',
  'event',
  'phase',
  'quality',
  'relation_type',
]);
/** Only the emitted keyword changes: the index and record identity are deliberately stable. */
function rekind(record: Record, value: Json): string | undefined {
  return !record.parent &&
    ProfileVocabulary.model(record.kind) === 'cn' &&
    typeof value === 'string' &&
    cnStereotypes.has(value)
    ? value
    : undefined;
}
export const OverlayApplier = {
  apply(
    entries: OverlayEntry[],
    index: LinkIndex,
    builder: CanonicalBuilder,
    root?: string
  ): Overridden[] {
    let renames: RenameEntry[] | undefined;
    const overridden: Overridden[] = [];
    for (const entry of entries) {
      const direct = index.qname(entry.key).filter((e) => e.record);
      let target = direct[0];
      if (!target) {
        const seen = new Set<string>();
        let named = index
          .resolve('sourceName', entry.key)
          .flatMap((e) => (e.record ? [e] : index.qname(e.qname).filter((e) => e.record)))
          .filter((e) => {
            if (seen.has(e.qname)) return false;
            seen.add(e.qname);
            return true;
          });
        const narrow = (test: (e: LinkEntry) => boolean) => {
          const filtered = named.filter(test);
          if (filtered.length) named = filtered;
        };
        narrow((e) => entry.property === 'kind' && rekind(e.record!, entry.value) !== undefined);
        narrow((e) => ProfileVocabulary.defines(e.record!, entry.property));
        if (named.length > 1) narrow((e) => ProfileVocabulary.model(e.kind) !== 'binding');
        if (named.length > 1)
          throw new ProfileException(
            'xm/link-ambiguous',
            entry.keyLocation,
            `Overlay key ${entry.key} matches ${named.map((e) => e.qname).join(', ')}; key the overlay by one of these qnames`
          );
        target = named[0];
      }
      if (!target) {
        renames ??= Renames.read(root);
        const rename = renames.find((r) => r.from === entry.key);
        throw new ProfileException(
          'xm/overlay-orphan',
          entry.keyLocation,
          `Overlay key ${entry.key} resolves to no record${rename ? `; renamed to ${rename.to}` : ''}`,
          rename ? { delta: { renamed: { ...rename } } } : undefined
        );
      }
      const keyword = entry.property === 'kind' ? rekind(target.record!, entry.value) : undefined;
      if (keyword !== undefined) {
        const previous = builder.rekind(target.record!, keyword, target.qname, entry.valueLocation);
        if (previous !== keyword)
          overridden.push({
            qname: target.qname,
            property: 'kind',
            from: previous,
            to: entry.value,
          });
      } else {
        const previous = builder.overlay(
          target.record!,
          entry.property,
          entry.value,
          entry.valueLocation
        );
        if (!jsonEqual(previous, entry.value))
          overridden.push({
            qname: target.qname,
            property: entry.property,
            from: previous ?? null,
            to: entry.value,
          });
      }
    }
    return overridden;
  },
};
/** Kotlin JsonObject equality ignores insertion order; array order remains significant. */
export function jsonEqual(a: Json | undefined, b: Json | undefined): boolean {
  if (a === b) return true;
  if (a instanceof NumericScalar || b instanceof NumericScalar)
    return a instanceof NumericScalar && b instanceof NumericScalar && a.text === b.text;
  if (
    a === undefined ||
    b === undefined ||
    a === null ||
    b === null ||
    typeof a !== 'object' ||
    typeof b !== 'object'
  )
    return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  const keys = Object.keys(a);
  return (
    keys.length === Object.keys(b).length &&
    keys.every(
      (key) =>
        Object.hasOwn(b, key) &&
        jsonEqual((a as { [key: string]: Json })[key], (b as { [key: string]: Json })[key])
    )
  );
}
/** Confined path overlays and inline overlays retain separate key and value positions. */
export const OverlayLoader = {
  load(profile: ProfileDefinition, root?: string): OverlayEntry[] {
    const overlay = profile.overlay;
    if (overlay === undefined || overlay === null) return [];
    const entries = (
      value: Json,
      prefix: string,
      location: (path: string, key: boolean) => ProfileSpan
    ): OverlayEntry[] => {
      if (!object(value))
        throw new ProfileException(
          'ys/profile-invalid',
          location(prefix, false),
          'Overlay must be a mapping'
        );
      const escape = (s: string) => s.replace(/~/g, '~0').replace(/\//g, '~1');
      const authoredEntries = (mapping: { [key: string]: Json }, at: string) =>
        Object.entries(mapping).sort(([a], [b]) => {
          const first = location(`${at}/${escape(a)}`, true);
          const second = location(`${at}/${escape(b)}`, true);
          return first.line - second.line || first.column - second.column;
        });
      return authoredEntries(value, prefix).flatMap(([key, properties]) => {
        const keyPath = `${prefix}/${escape(key)}`;
        if (!object(properties))
          throw new ProfileException(
            'ys/profile-invalid',
            location(keyPath, false),
            `Overlay entry ${key} must be a mapping`
          );
        return authoredEntries(properties, keyPath).map(([property, value]) => ({
          key,
          property,
          value,
          keyLocation: location(keyPath, true),
          valueLocation: location(`${keyPath}/${escape(property)}`, false),
        }));
      });
    };
    const at = profile.locations.get('/overlay')!;
    if (object(overlay))
      return entries(
        overlay,
        '/overlay',
        (path, key) =>
          (key ? profile.keyLocations.get(path) : undefined) ?? profile.locations.get(path) ?? at
      );
    if (typeof overlay !== 'string')
      throw new ProfileException('ys/profile-invalid', at, 'Overlay must be a mapping or path');
    const fileLabel = profile.locations.get('')!.file;
    const declaring = isAbsolute(fileLabel)
      ? fileLabel
      : root
        ? resolve(root, fileLabel)
        : undefined;
    if (!declaring)
      throw new ProfileException(
        'ys/profile-not-found',
        at,
        'A path overlay needs a root or an absolute profile file'
      );
    let boundary: string, file: string;
    try {
      boundary = realpathSync(root ?? dirname(declaring));
      file = realpathSync(resolve(dirname(declaring), overlay));
    } catch {
      throw new ProfileException('ys/profile-not-found', at, `Overlay not found: ${overlay}`);
    }
    const rel = relative(boundary, file);
    if (rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel) || !statSync(file).isFile())
      throw new ProfileException(
        'ys/profile-not-found',
        at,
        `Overlay is outside root or is not a file: ${overlay}`
      );
    let source: string;
    try {
      source = readProfileFile(file);
    } catch (error) {
      if (error instanceof ProfileException) throw error;
      throw new ProfileException('ys/profile-not-found', at, `Overlay not found: ${overlay}`);
    }
    const tree = ForeignTree.read(source, file);
    return entries(tree.root.value, '', (path, key) => tree.data.span(path, key));
  },
};
export { LinkOp } from './values.js';
