// SPDX-License-Identifier: Apache-2.0
import { NumericScalar, isRecord, JsonPointer, type Json, type ForeignNode } from './input.js';
import { jsonText } from './scalars.js';
import { ProfileException } from './loading.js';
import type { PlannedOp, RecordPlan, CanonicalBuilder, Record } from './engine.js';
import { ProfileVocabulary } from './vocabulary.js';
import { LinkIndex, bytewise } from './links.js';

export function targetPath(op: PlannedOp): string[] {
  const to = op.value.to as { path?: string[]; property?: string };
  return to.path ?? [to.property!];
}
export const Missing = Symbol('Missing');
export interface Filled {
  value: Json | typeof Missing;
  inputs: boolean;
}
/** Missing-input containers disappear recursively, even when they contain literal siblings. */
export function fillTemplate(
  template: Json,
  input: (text: string) => ForeignNode | null | undefined,
  consume: (node: ForeignNode) => void
): Filled {
  if (Array.isArray(template) || isRecord(template)) {
    if (!Array.isArray(template) && Object.keys(template).length === 1 && 'input' in template) {
      const found = input(template.input as string);
      if (found) consume(found);
      return { value: found?.value ?? Missing, inputs: true };
    }
    const parts = Object.entries(template).map(
      ([key, value]) => [key, fillTemplate(value, input, consume)] as const
    );
    const inputs = parts.some(([, filled]) => filled.inputs);
    const present = parts.filter(([, filled]) => filled.value !== Missing);
    if (inputs && !present.some(([, filled]) => filled.inputs)) return { value: Missing, inputs };
    return {
      value: Array.isArray(template)
        ? present.map(([, filled]) => filled.value as Json)
        : Object.fromEntries(present.map(([key, filled]) => [key, filled.value as Json])),
      inputs,
    };
  }
  return { value: template, inputs: false };
}
const scalar = (value: Json): value is string | number | boolean | NumericScalar =>
  value instanceof NumericScalar || (value !== null && typeof value !== 'object');

export class ValuesOp {
  constructor(readonly op: PlannedOp) {}
  apply(plan: RecordPlan, builder: CanonicalBuilder): void {
    const mapping = (this.op.value.mapping ?? {}) as { [key: string]: Json };
    const fallback = this.op.value.default;
    const separator = this.op.value.split;
    for (const record of plan.records.filter((r) => r.kind === this.op.scope)) {
      const source = this.op.at.resolve(record.source, true);
      if (source) plan.consumption.consume(source);
      if (!source || source.value === null) {
        if (fallback !== undefined)
          builder.write(plan, record, targetPath(this.op), fallback, source ?? record.source);
        continue;
      }
      let value: Json;
      if (typeof separator === 'string') {
        if (typeof source.value !== 'string')
          throw new ProfileException(
            'ys/split-not-a-string',
            source.span(true),
            `split needs a string at ${source.path}`
          );
        const sep = separator.trim();
        value = source.value
          .split(sep ? sep : /\s+/)
          .map((s) => s.trim())
          .filter(Boolean);
      } else {
        if (!scalar(source.value))
          throw new ProfileException(
            'ys/value-unmapped',
            source.span(true),
            `values needs a scalar at ${source.path}`
          );
        const key = String(source.value);
        value = Object.hasOwn(mapping, key) ? mapping[key] : fallback!;
        if (value === undefined)
          throw new ProfileException(
            'ys/value-unmapped',
            source.span(true),
            `Unmapped value ${key}`
          );
      }
      builder.write(plan, record, targetPath(this.op), value, source);
    }
  }
}
export class ShapeOp {
  constructor(readonly op: PlannedOp) {}
  apply(plan: RecordPlan, builder: CanonicalBuilder): void {
    for (const record of plan.records.filter((r) => r.kind === this.op.scope)) {
      const base = this.op.at.resolve(record.source, true);
      if (!base) continue;
      plan.consumption.open(base);
      const filled = fillTemplate(
        this.op.value.template,
        (text) => new JsonPointer(text).resolve(base, true),
        (n) => plan.consumption.consume(n)
      );
      if (!filled.inputs) plan.consumption.consume(base);
      if (base.value !== null && filled.value !== Missing)
        builder.write(plan, record, targetPath(this.op), filled.value, base);
    }
  }
}
export class EachOp {
  constructor(readonly op: PlannedOp) {}
  apply(plan: RecordPlan, builder: CanonicalBuilder): void {
    const key =
      typeof this.op.value.key === 'string' ? new JsonPointer(this.op.value.key) : undefined;
    const missing = typeof this.op.value.missing === 'string' ? this.op.value.missing : undefined;
    for (const record of plan.records.filter((r) => r.kind === this.op.scope)) {
      const source = this.op.at.resolve(record.source, true);
      if (!source) continue;
      plan.consumption.consume(source);
      if (source.value === null) continue;
      if (!Array.isArray(source.value))
        throw new ProfileException(
          'ys/each-not-a-list',
          source.span(true),
          `each needs a list at ${source.path}`
        );
      const shaped = source
        .entries()
        .map<{ element: ForeignNode; value: Json | typeof Missing }>((element) => ({
          element,
          value: fillTemplate(
            this.op.value.shape,
            (text) =>
              text === '$item' || text.startsWith('$item/')
                ? new JsonPointer(text.slice(5)).resolve(element, true)
                : new JsonPointer(text).resolve(record.source, true),
            (n) => plan.consumption.consume(n)
          ).value,
        }));
      const list: Json[] = [];
      const keyed: { [key: string]: Json } = Object.create(null);
      const messages = new Set<string>();
      const warn = (message: string) => {
        if (messages.has(message)) return;
        messages.add(message);
        plan.warnings.push({ location: source.span(true), message, record: record.id });
      };
      for (const { element, value } of shaped) {
        if (value === Missing) continue;
        if (!key) {
          list.push(value);
          continue;
        }
        const selected = key.resolve(element)?.value;
        let name: string;
        if (typeof selected === 'string') name = selected;
        else {
          warn(`each key missing → ${missing ?? 'skipped'}`);
          if (missing === undefined) continue;
          name = missing;
        }
        if (!Object.hasOwn(keyed, name)) keyed[name] = value;
        else {
          warn(`each key repeated (${name})`);
          const text = (v: Json) => (typeof v === 'string' ? v : jsonText(v));
          if (bytewise(text(value), text(keyed[name])) < 0) keyed[name] = value;
        }
      }
      builder.write(plan, record, targetPath(this.op), key ? keyed : list, source);
    }
  }
}
export class JoinPairsOp {
  constructor(readonly op: PlannedOp) {}
  apply(plan: RecordPlan, builder: CanonicalBuilder): void {
    for (const record of plan.records.filter((r) => r.kind === this.op.scope)) {
      const source = this.op.at.resolve(record.source, true);
      if (!source) continue;
      plan.consumption.consume(source);
      if (source.value === null) continue;
      builder.write(plan, record, targetPath(this.op), joinPairs(source), source);
    }
  }
}
export function joinPairs(node: ForeignNode): Json[] {
  const refused = (detail: string): never => {
    throw new ProfileException('xm/join-pair-shape', node.span(), detail);
  };
  const text = node.value;
  if (typeof text !== 'string')
    return refused(`join-pairs needs a string \`a.x = b.y [AND …]\` at ${node.path}`);
  if (!text.trim())
    return refused(`join-pairs needs a non-empty \`a.x = b.y [AND …]\` at ${node.path}`);
  return text.split(/\s+AND\s+|\s+AND\s*$/i).map((chunk) => {
    const match =
      /^\s*([\p{L}\p{N}_]+\.[\p{L}\p{N}_]+)\s*=\s*([\p{L}\p{N}_]+\.[\p{L}\p{N}_]+)\s*$/u.exec(
        chunk
      );
    if (!match)
      return refused(
        `'${chunk.trim() || 'AND'}' in join '${text}' is not \`a.x = b.y\` (two-part references, equality only)`
      );
    return { from: { ref: `er.entity.${match[1]}` }, to: { ref: `er.entity.${match[2]}` } };
  });
}
export class LinkOp {
  constructor(
    readonly op: PlannedOp,
    readonly index: LinkIndex,
    readonly ownPackage: string
  ) {}
  apply(plan: RecordPlan, builder: CanonicalBuilder): void {
    const path = targetPath(this.op);
    const operand = typeof this.op.value.kind === 'string' ? this.op.value.kind : undefined;
    const kind = operand ?? ProfileVocabulary.referentKind(path);
    const by = this.op.value.by as 'sourceName' | 'iri' | 'path';
    const resolve = (node: ForeignNode, record: Record): string => {
      if (typeof node.value !== 'string')
        throw new ProfileException(
          'xm/link-unresolved',
          node.span(true),
          `link needs a string reference at ${node.path}`
        );
      const candidates = this.index.resolve(by, node.value, kind);
      if (!candidates.length && operand) {
        const other = this.index.resolve(by, node.value);
        if (other.length)
          throw new ProfileException(
            'xm/link-kind-missing',
            node.span(true),
            `${by} ${node.value} names no ${kind} record, only ${other.map((e) => `${e.qname} (${e.kind})`).join(', ')}`
          );
      }
      const accepted = kind ? ` among ${kind} records` : '';
      if (!candidates.length)
        throw new ProfileException(
          'xm/link-unresolved',
          node.span(true),
          `No record with ${by} ${node.value}${accepted}`
        );
      if (candidates.length > 1)
        throw new ProfileException(
          'xm/link-ambiguous',
          node.span(true),
          `${by} ${node.value}${accepted} matches ${candidates.map((e) => e.qname).join(', ')}`
        );
      const target = candidates[0];
      if (target.pkg && target.pkg !== this.ownPackage) builder.import(plan, record, target.pkg);
      return target.qname;
    };
    for (const record of plan.records.filter((r) => r.kind === this.op.scope)) {
      const source = this.op.at.resolve(record.source, true);
      if (!source) continue;
      plan.consumption.consume(source);
      if (source.value === null) continue;
      const value = Array.isArray(source.value)
        ? source.entries().map((n) => resolve(n, record))
        : resolve(source, record);
      builder.write(plan, record, path, value, source);
    }
  }
}
