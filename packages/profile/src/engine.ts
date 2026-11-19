// SPDX-License-Identifier: Apache-2.0
import {
  parseYamlDocument,
  type SourceLocation,
  type Document as AstDocument,
} from '@tatrman/parser';
import {
  ForeignTree,
  ForeignNode,
  isRecord,
  NumericScalar,
  JsonPointer,
  type Json,
} from './input.js';
import {
  ProfileException,
  type ProfileSpan,
  type ProfileDefinition,
  type LocatedOp,
  FileSelector,
  GrammarRange,
} from './loading.js';
import { ProfileVocabulary, normalizeCanonical, canonicalWrite } from './vocabulary.js';
import { ValuesOp, ShapeOp, EachOp, JoinPairsOp, targetPath } from './values.js';
import {
  LinkIndex,
  LinkOp,
  Qnames,
  OverlayLoader,
  OverlayApplier,
  type Overridden,
} from './links.js';

import { entries, jsonText } from './scalars.js';

type ObjectValue = { [key: string]: Json };
const object = (v: Json | undefined): ObjectValue => (isRecord(v) ? v : {});
const PACKAGE_PATH = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*(?![\s\S])/;
const escape = (v: string) => v.replace(/~/g, '~0').replace(/\//g, '~1');
const resolve = (p: string, n: ForeignNode, includeNull = false) =>
  new JsonPointer(p).resolve(n, includeNull);
function fail(code: string, node: ForeignNode, message: string, key = true): never {
  throw new ProfileException(code, node.span(key), message);
}
export interface Ignored {
  location: ProfileSpan;
  reason?: string | null;
  record?: string | null;
}
export interface ProfileWarning {
  location: ProfileSpan;
  message: string;
  record?: string | null;
  in?: string;
  key?: string;
  count?: number;
}
export interface SourceEntry {
  qname: string;
  kind: string;
  pkg: string;
  foreignId: string | null;
  sourceName: string | null;
  locator: string | null;
}
export interface RecordOrigin {
  qname: string;
  kind: string;
  pkg: string;
  file: string;
  path: string;
}
export class Record {
  constructor(
    readonly source: ForeignNode,
    readonly kind: string,
    readonly id: string,
    readonly parent: Record | null = null
  ) {}
}
export class PlannedOp {
  readonly value: ObjectValue;
  readonly name: string;
  readonly scope: string | undefined;
  readonly at: JsonPointer;
  constructor(readonly located: LocatedOp) {
    this.value = located.value;
    this.name = String(this.value.op);
    this.scope = typeof this.value.in === 'string' ? this.value.in : undefined;
    this.at = new JsonPointer(String(this.value.at ?? ''));
  }
  matches(tree: ForeignTree): boolean {
    return (
      !Array.isArray(this.value.files) ||
      new FileSelector(this.value.files as string[]).matches(tree.file)
    );
  }
  missing(plan: RecordPlan, record: Record, target: string[]): void {
    if (this.value.required !== 'warn') return;
    plan.warnings.push({
      location: record.source.span(),
      message: `ys/key-missing: ${record.id} has no ${String(this.value.at ?? '')}, so ${record.kind}.${target.join('.')} is not written`,
    });
  }
}
export class ConsumptionLedger {
  private consumed = new Set<string>();
  private opened = new Set<string>();
  open(node: ForeignNode): void {
    let path = node.path;
    for (;;) {
      this.opened.add(path);
      if (!path) break;
      path = path.slice(0, path.lastIndexOf('/'));
    }
  }
  consume(node: ForeignNode): void {
    this.open(node);
    if (!node.keySelection) this.consumed.add(node.path);
  }
  check(root: ForeignNode, records: Record[] = [], unknown = 'error'): ProfileWarning[] {
    const warnings: ProfileWarning[] = [];
    const groups = new Map<string, { warning: ProfileWarning; seen: Set<string> }>();
    const drop = (node: ForeignNode, key: boolean) => {
      if (unknown !== 'ignore')
        fail(
          'ys/unknown-key',
          node,
          key ? `Unconsumed key ${node.mappingKey}` : `Unconsumed value ${node.path}`,
          key
        );
      let owner: Record | undefined;
      for (const record of records)
        if (
          node.path.startsWith(record.source.path + '/') &&
          (!owner || record.source.path.length > owner.source.path.length)
        )
          owner = record;
      const relative = node.path.slice(owner?.source.path.length ?? 0).split('/');
      // Only actual sequence indices are wildcarded; numeric mapping keys retain their spelling.
      let current = root;
      const indexed = new Set<string>();
      for (const part of node.path.split('/').slice(1)) {
        if (Array.isArray(current.value)) indexed.add(current.path + '/' + part);
        const next = resolve('/' + part, current, true);
        if (!next) break;
        current = next;
      }
      let path = owner?.source.path ?? '';
      const normalized = relative
        .map((part, i) => {
          if (!i) return '';
          path += '/' + part;
          return indexed.has(path) ? '*' : part;
        })
        .join('/');
      const id = JSON.stringify([node.tree.label, owner?.kind, normalized]);
      let group = groups.get(id);
      if (!group) {
        const warning: ProfileWarning = {
          location: node.span(node.mappingKey !== undefined),
          message: 'not in this profile',
          ...(owner ? { in: owner.kind } : {}),
          key: normalized,
          count: 0,
        };
        group = { warning, seen: new Set() };
        groups.set(id, group);
        warnings.push(warning);
      }
      group.seen.add(owner?.source.path ?? node.path);
      group.warning.count = group.seen.size;
    };
    const visit = (node: ForeignNode) => {
      if (this.consumed.has(node.path)) return;
      if (Array.isArray(node.value)) node.entries().forEach(visit);
      else if (isRecord(node.value))
        for (const child of node.entries()) {
          if (!this.consumed.has(child.path) && !this.opened.has(child.path)) drop(child, true);
          else visit(child);
        }
      else if (node.value !== null) drop(node, false);
    };
    visit(root);
    return warnings;
  }
}
export class ConflictLedger {
  private targets = new Map<Record, [Record, string[]]>();
  private writes = new Map<Record, string[][]>();
  register(record: Record, root: Record, path: string[]): void {
    this.targets.set(record, [root, path]);
  }
  claim(record: Record, path: string[], source: ForeignNode): void {
    const [root, prefix] = this.targets.get(record) ?? [record, []];
    const target = [...prefix, ...path];
    const writes = this.writes.get(root) ?? [];
    if (
      writes.some(
        (old) =>
          old.slice(0, target.length).every((p, i) => target[i] === p) ||
          target.slice(0, old.length).every((p, i) => old[i] === p)
      )
    )
      fail(
        'ys/profile-conflict',
        source,
        `Two operations write ${record.kind}.${record.id}/${path.join('/')}`
      );
    writes.push(target);
    this.writes.set(root, writes);
  }
}
export class RecordPlan {
  readonly consumption = new ConsumptionLedger();
  readonly conflicts = new ConflictLedger();
  readonly warnings: ProfileWarning[] = [];
  readonly records: Record[];
  readonly skipped: Ignored[];
  constructor(
    readonly tree: ForeignTree,
    private readonly operations: PlannedOp[]
  ) {
    const found = new Map<string, Record>();
    const madeBy = new Map<Record, number>();
    const skips = new Map<string, { node: ForeignNode; ignored: Ignored }>();
    const refusals = new Map<string, ProfileException>();
    const evaluated = new Set<string>();
    let changed: boolean;
    do {
      changed = false;
      operations.forEach((op, index) => {
        if (op.name !== 'kinds' || !op.matches(tree)) return;
        const contexts =
          op.scope === undefined ? [null] : [...found.values()].filter((r) => r.kind === op.scope);
        for (const parent of contexts) {
          const context = parent?.source ?? tree.root;
          const evaluation = `${index}:${context.path}`;
          if (evaluated.has(evaluation)) continue;
          evaluated.add(evaluation);
          const selection = op.at.resolve(context, true);
          if (!selection) continue;
          this.consumption.open(selection);
          if (selection.value === null) continue;
          if (op.value.at !== '' && !Array.isArray(selection.value) && !isRecord(selection.value)) {
            if (!refusals.has(selection.path))
              refusals.set(
                selection.path,
                new ProfileException(
                  'ys/profile-invalid',
                  selection.span(true),
                  'kinds.at must select a sequence or mapping'
                )
              );
            continue;
          }
          for (const source of op.value.at === '' ? [selection] : selection.entries()) {
            try {
              const mapping = object(op.value.mapping);
              const kindText = typeof op.value.kind === 'string' ? op.value.kind : undefined;
              const candidates: [ForeignNode, string][] = [];
              if (kindText !== undefined) {
                const parts = kindText.split('/').slice(1);
                const star = parts.indexOf('*');
                const list = resolve(
                  star < 0
                    ? kindText
                    : parts
                        .slice(0, star)
                        .map((p) => '/' + p)
                        .join(''),
                  source
                );
                if (list) {
                  const nodes = Array.isArray(list.value)
                    ? list.entries().map((n) =>
                        star < 0
                          ? n
                          : resolve(
                              parts
                                .slice(star + 1)
                                .map((p) => '/' + p)
                                .join(''),
                              n
                            )
                      )
                    : star < 0
                      ? [list]
                      : [];
                  for (const node of nodes)
                    if (node && typeof node.value === 'string') candidates.push([node, node.value]);
                }
              }
              const selected = candidates.find(([, k]) => Object.hasOwn(mapping, k));
              const pointed =
                kindText !== undefined && !kindText.split('/').includes('*')
                  ? resolve(kindText, source, true)
                  : undefined;
              const foreignKind =
                kindText === undefined
                  ? Object.keys(mapping).length === 1
                    ? Object.keys(mapping)[0]
                    : undefined
                  : selected?.[1];
              const skip = (reason: string) => {
                const id = resolve(String(op.value.id ?? '/$key'), source)?.value;
                if (!skips.has(source.path))
                  skips.set(source.path, {
                    node: source,
                    ignored: {
                      location: source.span(true),
                      reason,
                      ...(typeof id === 'string' ? { record: id } : {}),
                    },
                  });
              };
              if (foreignKind !== undefined && mapping[foreignKind] === null) {
                skip(`kind mapping null (${kindText ?? 'kind'} = ${foreignKind})`);
                continue;
              }
              if (foreignKind === undefined && op.value.unmapped === 'skip') {
                skip(
                  candidates.length
                    ? `kind unmapped (${candidates.map((c) => c[1]).join(', ')})`
                    : `kind missing (${kindText ?? 'null'})`
                );
                continue;
              }
              const kind = foreignKind === undefined ? undefined : mapping[foreignKind];
              if (typeof kind !== 'string')
                fail(
                  'ys/unknown-kind',
                  selected?.[0] ?? candidates[0]?.[0] ?? pointed ?? source,
                  candidates.length
                    ? `Unmapped record kind ${candidates.map((c) => c[1]).join(', ')}`
                    : `Unmapped record kind: no string at ${kindText ?? ''}`
                );
              const idNode = resolve(String(op.value.id ?? '/$key'), source);
              const id = idNode?.value;
              if (typeof id !== 'string')
                fail('ys/record-id-missing', source, 'Record needs a string id', false);
              if (parent?.source.path === source.path)
                fail('ys/profile-conflict', source, 'A record cannot nest inside itself');
              const key = JSON.stringify([source.path, kind]);
              if (found.has(key))
                fail('ys/profile-conflict', source, 'Two kinds operations select the same record');
              this.consumption.open(source);
              const kindNode = pointed ?? selected?.[0] ?? candidates[0]?.[0];
              if (kindNode) this.consumption.consume(kindNode);
              if (idNode) this.consumption.consume(idNode);
              const record = new Record(source, kind, id, parent);
              found.set(key, record);
              madeBy.set(record, index);
              changed = true;
            } catch (e) {
              if (!(e instanceof ProfileException)) throw e;
              if (!refusals.has(source.path)) refusals.set(source.path, e);
            }
          }
        }
      });
    } while (changed);
    const recorded = new Set([...found.values()].map((r) => r.source.path));
    const skippedPaths = [...skips.keys()].filter((p) => !recorded.has(p));
    const beneath = (p: string) => skippedPaths.some((s) => p.startsWith(s + '/'));
    for (const [p, e] of refusals) if (!beneath(p)) throw e;
    const order = new Map<string, number>();
    const walk = (n: ForeignNode) => {
      order.set(n.path, order.size);
      n.entries().forEach(walk);
    };
    walk(tree.root);
    this.records = [...found.values()]
      .filter((r) => !beneath(r.source.path))
      .sort(
        (a, b) =>
          order.get(a.source.path)! - order.get(b.source.path)! || madeBy.get(a)! - madeBy.get(b)!
      );
    this.skipped = [...skips.entries()]
      .filter(([p]) => skippedPaths.includes(p) && !beneath(p))
      .map(([, s]) => {
        this.consumption.consume(s.node);
        return s.ignored;
      });
  }
  nestedKinds(parent: Record): string[] {
    return [
      ...new Set(
        this.operations
          .filter((op) => op.name === 'kinds' && op.scope === parent.kind && op.matches(this.tree))
          .flatMap((op) =>
            Object.values(object(op.value.mapping)).filter(
              (k): k is string => typeof k === 'string'
            )
          )
      ),
    ];
  }
}

export interface CanonicalDocument {
  value: ObjectValue;
  ast: AstDocument;
}
export class EmittedDocument {
  readonly value: ObjectValue;
  constructor(
    readonly canonical: CanonicalDocument,
    readonly locations: Map<string, ProfileSpan>,
    readonly file?: string
  ) {
    this.value = {
      ttr: canonical.value.ttr,
      package: canonical.value.package,
      imports: canonical.value.imports ?? [],
      ...canonical.value,
    };
  }
  get ast(): AstDocument {
    return this.canonical.ast;
  }
  write(): string {
    return canonicalWrite(this.canonical.value);
  }
}
export class CanonicalBuilder {
  private properties = new Map<Record, ObjectValue>();
  private origins = new Map<Record, Map<string, ProfileSpan>>();
  private children = new Map<Record, Map<string, Record[]>>();
  private imports = new Map<string, Set<string>>();
  private rekinds = new Map<Record, string>();
  private rekindOrigins = new Map<Record, [string, ProfileSpan]>();
  constructor(
    private readonly plans: RecordPlan[],
    private readonly packages: Map<RecordPlan, string>,
    private readonly perFile = false
  ) {
    const identities = new Set<string>();
    for (const plan of plans) {
      for (const record of plan.records) {
        this.properties.set(record, {});
        this.origins.set(record, new Map());
        if (!record.parent) {
          const identity = [
            packages.get(plan)!,
            ProfileVocabulary.model(record.kind),
            ProfileVocabulary.semanticsKind(record.kind),
            record.id,
          ];
          const key = JSON.stringify(identity);
          if (identities.has(key))
            fail(
              'ys/profile-conflict',
              record.source,
              `Repeated canonical definition ${identity.join('.')}`
            );
          identities.add(key);
        } else {
          const slot = ProfileVocabulary.nestedSlot(record.parent, record);
          const slots = this.children.get(record.parent) ?? new Map<string, Record[]>();
          const siblings = slots.get(slot) ?? [];
          if (siblings.some((r) => r.kind === record.kind && r.id === record.id))
            fail('ys/profile-conflict', record.source, `Repeated nested identity ${record.id}`);
          siblings.push(record);
          slots.set(slot, siblings);
          this.children.set(record.parent, slots);
        }
      }
      const register = (record: Record, root: Record, prefix: string[]) => {
        plan.conflicts.register(record, root, prefix);
        plan.conflicts.claim(record, ['@id'], record.source);
        for (const [slot, children] of this.children.get(record) ?? [])
          children.forEach((child, i) => register(child, root, [...prefix, slot, String(i)]));
      };
      plan.records.filter((r) => !r.parent).forEach((r) => register(r, r, []));
    }
  }
  write(plan: RecordPlan, record: Record, path: string[], value: Json, source: ForeignNode): void {
    if (
      path.length > 1 &&
      plan
        .nestedKinds(record)
        .some(
          (kind) =>
            ProfileVocabulary.nestedSlotOrNull(
              record,
              new Record(record.source, kind, record.id, record)
            ) === path[0]
        )
    ) {
      for (const child of this.children.get(record)?.get(path[0]) ?? [])
        this.write(plan, child, path.slice(1), value, source);
      return;
    }
    plan.conflicts.claim(record, path, source);
    let current = this.properties.get(record)!;
    for (const key of path.slice(0, -1)) {
      current[key] = object(current[key]);
      current = current[key] as ObjectValue;
    }
    Object.defineProperty(current, path.at(-1)!, {
      value,
      writable: true,
      enumerable: true,
      configurable: true,
    });
    this.origins.get(record)!.set('/' + path.map(escape).join('/'), source.span(true));
  }
  private group(plan: RecordPlan, record: Record): [string | null, string, string] {
    while (record.parent) record = record.parent;
    return [
      this.perFile ? plan.tree.file : null,
      this.packages.get(plan)!,
      ProfileVocabulary.model(record.kind),
    ];
  }
  import(plan: RecordPlan, record: Record, pkg: string): void {
    const key = JSON.stringify(this.group(plan, record));
    const imports = this.imports.get(key) ?? new Set();
    imports.add(pkg + '.*');
    this.imports.set(key, imports);
  }
  rekind(record: Record, keyword: string, qname: string, origin: ProfileSpan): string {
    const previous = this.rekinds.get(record) ?? record.kind;
    if (keyword === record.kind) {
      this.rekinds.delete(record);
      this.rekindOrigins.delete(record);
    } else {
      this.rekinds.set(record, keyword);
      this.rekindOrigins.set(record, [qname, origin]);
    }
    return previous;
  }
  overlay(record: Record, property: string, value: Json, origin: ProfileSpan): Json | undefined {
    if (this.children.get(record)?.has(property))
      throw new ProfileException(
        'ys/profile-invalid',
        origin,
        `An overlay cannot replace nested records at ${property}`
      );
    const properties = this.properties.get(record)!;
    const previous = properties[property];
    Object.defineProperty(properties, property, {
      value,
      writable: true,
      enumerable: true,
      configurable: true,
    });
    const key = '/' + escape(property);
    const origins = this.origins.get(record)!;
    for (const path of origins.keys()) if (path.startsWith(key + '/')) origins.delete(path);
    origins.set(key, origin);
    return previous;
  }
  documents(): EmittedDocument[] {
    const groups = new Map<string, Record[]>();
    for (const plan of this.plans)
      for (const record of plan.records.filter((r) => !r.parent)) {
        const key = JSON.stringify(this.group(plan, record));
        const records = groups.get(key) ?? [];
        records.push(record);
        groups.set(key, records);
      }
    return [...groups].map(([key, records]) => {
      const [file, pkg, model] = JSON.parse(key) as [string | null, string, string];
      const locations = new Map<string, ProfileSpan>();
      const authored = new Set<string>();
      const definition = (record: Record, path: string, nested: boolean): ObjectValue => {
        const propertyKey = ProfileVocabulary.storageMember(record) ? 'fields' : 'properties';
        locations.set(path, record.source.span());
        for (const [target, origin] of this.origins.get(record)!) {
          const at = `${path}/${propertyKey}${target}`;
          locations.set(at, origin);
          authored.add(at);
        }
        const props = { ...this.properties.get(record)! };
        for (const [slot, items] of this.children.get(record) ?? [])
          props[slot] = items.map((child, i) =>
            definition(child, `${path}/${propertyKey}/${escape(slot)}/${i}`, true)
          );
        const member = ProfileVocabulary.member(record);
        return {
          ...(!nested || (member && !ProfileVocabulary.storageMember(record))
            ? { kind: this.rekinds.get(record) ?? record.kind }
            : {}),
          ...(member?.named !== false ? { id: record.id } : {}),
          [propertyKey]: props,
        };
      };
      const unnormalized: ObjectValue = {
        ttr: GrammarRange.runningGrammar,
        package: pkg,
        imports: [...(this.imports.get(key) ?? [])].sort(),
        model,
        defs: records.map((record, i) => definition(record, `/defs/${i}`, false)),
      };
      const raw = normalizeCanonical(unnormalized) as ObjectValue;
      const normalizedPaths = canonicalPaths(unnormalized, raw);
      const originalLocations = [...locations];
      locations.clear();
      for (const [path, span] of originalLocations)
        locations.set(normalizedPaths.get(path) ?? path, span);
      const originalAuthored = [...authored];
      authored.clear();
      for (const path of originalAuthored) authored.add(normalizedPaths.get(path) ?? path);
      const json = jsonText(raw);
      if ([...json].length > 6_000_000)
        fail(
          'ys/profile-invalid',
          records[0].source,
          `Canonical document ${pkg}/${model} exceeds 6000000 code points`,
          false
        );
      const parsed = parseYamlDocument(json, records[0].source.tree.label);
      // Index canonical JSON's scalar/container ranges, in code points, to recover the parser's canonical pointers.
      const ranges = jsonRanges(raw);
      const exactRanges = new Map(ranges.map((r) => [`${r.start}:${r.end}`, r.path]));
      const pointerAt = (source: SourceLocation) =>
        exactRanges.get(`${source.offsetStart}:${source.offsetEnd}`) ??
        ranges
          .filter((r) => r.start <= source.offsetStart && r.end >= source.offsetEnd)
          .sort((a, b) => b.path.length - a.path.length)[0]?.path ??
        '';
      const enclosing = (path: string, dialectOnly = false): [string, ProfileSpan] | undefined => {
        let at = path;
        for (;;) {
          const span = locations.get(at);
          if (span && (!dialectOnly || this.plans.some((p) => p.tree.label === span.file)))
            return [at, span];
          if (!at) break;
          at = at.slice(0, at.lastIndexOf('/'));
        }
        return locations.has('/defs/0') ? ['/defs/0', locations.get('/defs/0')!] : undefined;
      };
      const error = parsed.diagnostics.find((d) => d.severity === 'error');
      if (!parsed.ast || error) {
        const path = error ? pointerAt(error.source) : '';
        const nearest = enclosing(path);
        const origin =
          nearest && authored.has(nearest[0])
            ? nearest[1]
            : ([...locations].find(([p]) => authored.has(p) && p.startsWith(path + '/'))?.[1] ??
              nearest?.[1] ??
              records[0].source.span());
        const record = records[Number(/^\/defs\/(\d+)/.exec(path)?.[1])];
        const rekind = record && this.rekindOrigins.get(record);
        const suffix = rekind
          ? `; the overlay re-kinded ${rekind[0]} from ${record.kind} to ${this.rekinds.get(record)} at ${rekind[1].file}:${rekind[1].line}:${rekind[1].column}`
          : '';
        let message = error?.message ?? 'Invalid canonical document';
        const type = /^must be (string|object|array|number|integer|boolean|null)$/.exec(
          message
        )?.[1];
        if (type) {
          // The parser supplies Ajv's shorthand; profile refusals use Kotlin's located type text.
          const value = path
            .split('/')
            .slice(1)
            .reduce<Json | undefined>((value, key) => {
              const decoded = key.replace(/~1/g, '/').replace(/~0/g, '~');
              return Array.isArray(value)
                ? value[Number(decoded)]
                : value instanceof Map
                  ? value.get(decoded)
                  : isRecord(value)
                    ? value[decoded]
                    : undefined;
            }, raw);
          const found =
            value === null
              ? 'null'
              : value instanceof NumericScalar
                ? 'number'
                : Array.isArray(value)
                  ? 'array'
                  : typeof value;
          message = `${path}: ${found} found, ${type} expected`;
        }
        throw new ProfileException('ys/profile-invalid', origin, message + suffix);
      }
      const relocate = (source: SourceLocation): SourceLocation => {
        const nearest = enclosing(pointerAt(source), true);
        if (!nearest) return source;
        const span = nearest[1];
        const tree = this.plans.find((p) => p.tree.label === span.file)!.tree;
        const keySource = tree.data.source(span.path, true);
        return keySource.line === span.line && keySource.column + 1 === span.column
          ? keySource
          : tree.data.source(span.path);
      };
      const copy = (value: unknown): unknown => {
        if (!value || typeof value !== 'object') return value;
        if (Array.isArray(value)) return value.map(copy);
        const node = value as { [key: string]: unknown };
        if (
          typeof node.offsetStart === 'number' &&
          typeof node.offsetEnd === 'number' &&
          typeof node.file === 'string'
        )
          return relocate(node as unknown as SourceLocation);
        const result = Object.fromEntries(
          Object.entries(node)
            .filter(([k]) => k !== 'leadingTrivia' && k !== 'trailingTrivia')
            .map(([k, v]) => [k, copy(v)])
        );
        if (node.source && (node.kind === 'embeddedBlock' || node.kind === 'taggedBlock')) {
          const source = node.source as SourceLocation;
          const pointer = pointerAt(source);
          const nearest = enclosing(pointer);
          const tree = nearest && this.plans.find((p) => p.tree.label === nearest[1].file)?.tree;
          const text = node.kind === 'embeddedBlock' ? String(node.text) : String(node.value);
          const content =
            tree && nearest
              ? authoredContent(tree, nearest[1].path + pointer.slice(nearest[0].length), text)
              : undefined;
          if (content) {
            if (node.kind === 'embeddedBlock')
              Object.assign(result, {
                textSource: content.source,
                lineOffsets: content.lines,
                indentWidth: content.indent,
                textMapping: content.mapping,
                escapes: [],
              });
            else
              Object.assign(result, { valueSource: content.source, indentWidth: content.indent });
          } else {
            // An overlay has no authored dialect scalar: retain all generated text coordinates together.
            for (const field of [
              'textSource',
              'valueSource',
              'lineOffsets',
              'indentWidth',
              'textMapping',
              'escapes',
            ])
              if (field in node) result[field] = node[field];
          }
        }
        return result;
      };
      return new EmittedDocument(
        { value: raw, ast: copy(parsed.ast) as AstDocument },
        locations,
        file ?? undefined
      );
    });
  }
}

/** JSON.stringify range index without relying on UTF-16 offsets from JSON/YAML libraries. */
function jsonRanges(raw: Json): { path: string; start: number; end: number }[] {
  let offset = 0;
  const ranges: { path: string; start: number; end: number }[] = [];
  const visit = (value: Json, path: string) => {
    const start = offset;
    if (value !== null && typeof value === 'object' && !(value instanceof NumericScalar)) {
      offset++;
      entries(value).forEach(([key, child], i) => {
        if (i) offset++;
        if (!Array.isArray(value)) offset += [...JSON.stringify(key)].length + 1;
        visit(child, path + '/' + escape(key));
      });
      offset++;
    } else offset += [...jsonText(value)].length;
    ranges.push({ path, start, end: offset });
  };
  visit(raw, '');
  return ranges;
}

export interface ApplyReport {
  codes: { [key: string]: string };
  overridden: Overridden[];
  warnings: ProfileWarning[];
}
export class ApplyResult {
  constructor(
    readonly documents: EmittedDocument[],
    readonly ignored: Ignored[],
    readonly report: ApplyReport,
    readonly sources: SourceEntry[] = [],
    readonly origins: RecordOrigin[] = []
  ) {}
  reportJson(): ObjectValue {
    const located = (item: Ignored | ProfileWarning): ObjectValue => ({
      file: item.location.file,
      line: item.location.line,
      column: item.location.column,
      path: item.location.path,
      ...(item.record != null ? { record: item.record } : {}),
    });
    return {
      documents: this.documents.map((d) => ({
        package: d.value.package,
        model: d.value.model,
        imports: d.value.imports,
        defs: (d.value.defs as Json[]).length,
      })),
      ignored: this.ignored.map((i) => ({ ...located(i), reason: i.reason ?? null })),
      overridden: this.report.overridden.map((i) => ({
        qname: i.qname,
        property: i.property,
        from: i.from,
        to: i.to,
      })),
      ...(this.report.warnings.length
        ? {
            warnings: this.report.warnings.map((w) => ({
              ...located(w),
              ...(w.in !== undefined ? { in: w.in } : {}),
              ...(w.key !== undefined ? { key: w.key, count: w.count! } : {}),
              message: w.message,
            })),
          }
        : {}),
    };
  }
}
const distinct = <T>(items: T[]): T[] => [
  ...new Map(items.map((item) => [JSON.stringify(item), item])).values(),
];
export class ProfileEngine {
  static apply(
    trees: ForeignTree[],
    profile: ProfileDefinition[],
    root?: string,
    perFile = false,
    sourceName?: string
  ): ApplyResult {
    const overlays = profile.flatMap((p) => OverlayLoader.load(p, root));
    const ops = profile.flatMap((p) => p.operations).map((op) => new PlannedOp(op));
    const ignored: Ignored[] = [];
    const codes: { [key: string]: string } = {};
    for (const op of ops.filter((o) => o.name === 'codes')) Object.assign(codes, op.value.mapping);
    const plans: RecordPlan[] = [];
    for (const tree of trees) {
      const ignores = ops.filter(
        (op) =>
          op.name === 'ignore' &&
          op.scope === undefined &&
          !Object.hasOwn(op.value, 'at') &&
          op.matches(tree)
      );
      if (ignores.length) {
        for (const op of ignores)
          ignored.push({
            location: tree.root.span(),
            reason: op.value.reason as string | undefined,
          });
        continue;
      }
      const plan = new RecordPlan(tree, ops);
      const value = object(tree.root.value);
      if (tree.root.entries()[0]?.mappingKey === 'ttr' && isRecord(value.ttr))
        plan.consumption.consume(tree.root.child('ttr', value.ttr));
      plans.push(plan);
    }
    const packages = new Map<RecordPlan, string>();
    for (const plan of plans) {
      const selections = ops
        .filter((op) => op.name === 'package' && op.matches(plan.tree))
        .map((op) => {
          const selected = Object.hasOwn(op.value, 'at')
            ? op.at.resolve(plan.tree.root, true)
            : undefined;
          if (selected) plan.consumption.consume(selected);
          if (op.value.verbatim === true) {
            if (!plan.records.length) return '';
            const written = selected?.value;
            const path = typeof written === 'string' ? written.replace(/\//g, '.') : undefined;
            if (path !== undefined && PACKAGE_PATH.test(path)) return path;
            throw new ProfileException(
              'ys/package-invalid',
              plan.records[0].source.span(true),
              typeof written === 'string'
                ? `"${written}" is no TTR package path (verbatim: lower-case names, \`/\` → \`.\`)`
                : `no package path at ${String(op.value.at)}`
            );
          }
          const file = plan.tree.file.split('/').at(-1)!;
          const key = Object.hasOwn(op.value, 'at')
            ? typeof selected?.value === 'string'
              ? selected.value
              : undefined
            : file.includes('.')
              ? file.slice(0, file.lastIndexOf('.'))
              : file;
          const pkg = key === undefined ? undefined : object(op.value.mapping)[key];
          const result = pkg ?? op.value.default;
          if (typeof result !== 'string')
            throw new ProfileException(
              'ys/value-unmapped',
              { file: plan.tree.label, line: 1, column: 1, endLine: 1, endColumn: 1, path: '' },
              key === undefined
                ? `Unmapped package key: no string at ${op.value.at}`
                : `Unmapped package key ${key}`
            );
          return result;
        });
      if (selections.length > 1)
        fail(
          'ys/profile-conflict',
          plan.tree.root,
          'Two package operations select the same file',
          false
        );
      if (!selections.length && plan.records.length)
        throw new ProfileException(
          'ys/value-unmapped',
          { file: plan.tree.label, line: 1, column: 1, endLine: 1, endColumn: 1, path: '' },
          `No package operation for ${plan.tree.file}`
        );
      packages.set(plan, selections[0] ?? '');
    }
    const builder = new CanonicalBuilder(plans, packages, perFile);
    const index = LinkIndex.build(
      plans,
      packages,
      root,
      sourceName ? new JsonPointer(sourceName) : undefined
    );
    const unknown = [...profile].reverse().find((p) => p.unknown !== undefined)?.unknown ?? 'error';
    for (const plan of plans) {
      ignored.push(...plan.skipped);
      for (const op of ops.filter((o) => o.matches(plan.tree))) {
        switch (op.name) {
          case 'kinds':
          case 'package':
          case 'codes':
            break;
          case 'rename':
          case 'nest':
          case 'unnest':
            for (const record of plan.records.filter((r) => r.kind === op.scope)) {
              const source = op.at.resolve(record.source, true);
              if (!source) {
                op.missing(plan, record, targetPath(op));
                continue;
              }
              plan.consumption.consume(source);
              if (source.value === null) op.missing(plan, record, targetPath(op));
              else builder.write(plan, record, targetPath(op), source.value, source);
            }
            break;
          case 'ignore': {
            const contexts =
              op.scope === undefined
                ? [plan.tree.root]
                : plan.records.filter((r) => r.kind === op.scope).map((r) => r.source);
            for (const context of contexts) {
              const source = op.at.resolve(context, true);
              if (!source) continue;
              const record = plan.records.find(
                (r) => r.source.path === source.path || r.source.path.startsWith(source.path + '/')
              );
              if (record)
                fail(
                  'ys/profile-conflict',
                  source,
                  `ignore covers record ${record.kind}.${record.id}`
                );
              plan.consumption.consume(source);
              ignored.push({
                location: source.span(true),
                reason: op.value.reason as string | undefined,
              });
            }
            break;
          }
          case 'values':
            new ValuesOp(op).apply(plan, builder);
            break;
          case 'shape':
            new ShapeOp(op).apply(plan, builder);
            break;
          case 'each':
            new EachOp(op).apply(plan, builder);
            break;
          case 'join-pairs':
            new JoinPairsOp(op).apply(plan, builder);
            break;
          case 'link':
            new LinkOp(op, index, packages.get(plan)!).apply(plan, builder);
            break;
          default:
            throw new ProfileException(
              'ys/profile-invalid',
              op.located.location,
              `Unknown operation ${op.name}`
            );
        }
      }
      plan.warnings.push(...plan.consumption.check(plan.tree.root, plan.records, unknown));
    }
    const overridden = OverlayApplier.apply(overlays, index, builder, root);
    return new ApplyResult(
      builder.documents(),
      distinct(ignored),
      { codes, overridden, warnings: distinct(plans.flatMap((p) => p.warnings)) },
      index.sources(),
      plans.flatMap((plan) =>
        plan.records.map((r) => ({
          qname: Qnames.of(r, packages.get(plan)!),
          kind: r.kind,
          pkg: packages.get(plan)!,
          file: plan.tree.label,
          path: r.source.path,
        }))
      )
    );
  }
}

/** Embedded text keeps its own coordinate system, rather than only moving the AST node's outer span. */
function authoredContent(
  tree: ForeignTree,
  path: string,
  text: string
):
  | {
      source: SourceLocation;
      lines: number[];
      indent: number;
      mapping: { offsets: number[]; lineStarts: number[]; startLine: number };
    }
  | undefined {
  let scalarPath = path + '/block';
  let scalar = tree.data.node(scalarPath);
  if (!scalar || !('value' in scalar) || scalar.value !== text) {
    scalarPath = path;
    scalar = tree.data.node(path);
  }
  if (!scalar || !('value' in scalar) || scalar.value !== text || !scalar.range) return undefined;
  const [start, end] = scalar.range;
  const raw = tree.data.text.slice(start, end);
  const coordinates = tree.data.coordinates;
  let offsets: number[] = [];
  let indent = 0;
  if ('type' in scalar && scalar.type === 'BLOCK_LITERAL') {
    const starts: number[] = [];
    for (let i = start; i < end; i++) if (tree.data.text[i] === '\n') starts.push(i + 1);
    const logical = text.split('\n');
    const first = Math.max(
      0,
      logical.findIndex((l) => l.trim())
    );
    const physicalStart = starts[first] ?? end;
    const physical = tree.data.text.slice(
      physicalStart,
      tree.data.text.indexOf('\n', physicalStart) < 0
        ? end
        : tree.data.text.indexOf('\n', physicalStart)
    );
    indent = Math.max(
      0,
      (physical.match(/^ */)?.[0].length ?? 0) - (logical[first].match(/^ */)?.[0].length ?? 0)
    );
    logical.forEach((line, i) => {
      let at = Math.min(end, (starts[i] ?? end) + (line.trim() ? indent : 0));
      for (const point of line) {
        offsets.push(at);
        at += point.length;
      }
      offsets.push(Math.min(end, at));
    });
  } else if ('type' in scalar && scalar.type === 'QUOTE_DOUBLE') {
    let decoded = '';
    const units: number[] = [];
    for (let i = 1; i < raw.length - 1; i++) {
      const at = i;
      let value = raw[i];
      if (value === '\\') {
        const escape = raw[++i];
        const escapes: { [key: string]: string } = {
          '0': '\0',
          a: '\x07',
          b: '\b',
          e: '\x1b',
          f: '\f',
          n: '\n',
          r: '\r',
          t: '\t',
          v: '\v',
          N: '\x85',
          _: '\xa0',
          L: '\u2028',
          P: '\u2029',
          ' ': ' ',
          '"': '"',
          '/': '/',
          '\\': '\\',
        };
        if (Object.hasOwn(escapes, escape)) value = escapes[escape];
        else if (['x', 'u', 'U'].includes(escape)) {
          const size = escape === 'x' ? 2 : escape === 'u' ? 4 : 8;
          value = String.fromCodePoint(parseInt(raw.slice(i + 1, i + size + 1), 16));
          i += size;
        } else return undefined;
      } else if (value === '\n' || value === '\r') return undefined;
      else {
        value = String.fromCodePoint(raw.codePointAt(i)!);
        i += value.length - 1;
      }
      decoded += value;
      for (let j = 0; j < value.length; j++) units.push(start + at);
    }
    if (decoded !== text) return undefined;
    let unit = 0;
    for (const point of text) {
      offsets.push(units[unit]);
      unit += point.length;
    }
    offsets.push(end - 1);
  } else if (!raw.includes('\n') && (raw === text || raw.slice(1, -1) === text)) {
    let at = start + (raw === text ? 0 : 1);
    for (const point of text) {
      offsets.push(at);
      at += point.length;
    }
    offsets.push(at);
  } else return undefined;
  const mapped = offsets.map((i) => coordinates.points[i]);
  const lines: number[] = [];
  let index = 0;
  for (const line of text.split('\n')) {
    lines.push(mapped[index] ?? mapped.at(-1)!);
    index += [...line].length + 1;
  }
  const source = coordinates.span([offsets[0], offsets.at(-1)!]);
  const scalarSource = tree.data.source(scalarPath);
  const lineStarts = [scalarSource.offsetStart - scalarSource.column];
  for (let i = start; i < end; i++)
    if (tree.data.text[i] === '\n') lineStarts.push(coordinates.points[i + 1]);
  return {
    source,
    lines,
    indent,
    mapping: { offsets: mapped, lineStarts, startLine: scalarSource.line },
  };
}

/** Follow the normalization's actual container shapes. Numeric object keys are never treated as list indices. */
function canonicalPaths(before: Json, after: Json): Map<string, string> {
  const result = new Map<string, string>();
  const walk = (a: Json, b: Json, from: string, to: string) => {
    result.set(from, to);
    if (
      !a ||
      typeof a !== 'object' ||
      !b ||
      typeof b !== 'object' ||
      a instanceof NumericScalar ||
      b instanceof NumericScalar
    )
      return;
    if (isRecord(a) && Array.isArray(b)) {
      b.forEach((entry, i) => {
        if (isRecord(entry) || entry instanceof Map)
          for (const [key, value] of entries(entry))
            if (Object.hasOwn(a, key))
              walk(a[key], value, from + '/' + escape(key), to + '/' + i + '/' + escape(key));
      });
    } else if (Array.isArray(a) && Array.isArray(b))
      a.forEach((value, i) => {
        if (i < b.length) walk(value, b[i], from + '/' + i, to + '/' + i);
      });
    else if (!Array.isArray(a) && !Array.isArray(b))
      for (const [key, value] of entries(a)) {
        const selected = b instanceof Map ? b.get(key) : isRecord(b) ? b[key] : undefined;
        if (selected !== undefined)
          walk(value, selected, from + '/' + escape(key), to + '/' + escape(key));
      }
  };
  walk(before, after, '', '');
  return result;
}
