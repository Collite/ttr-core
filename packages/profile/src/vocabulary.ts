// SPDX-License-Identifier: Apache-2.0
import { modelForKind } from '@tatrman/semantics';
import { canonicalMetadata } from './generated/resources.js';
import { isRecord, type Json } from './input.js';
import { NumericScalar, entries as outputEntries, jsonText } from './scalars.js';
import { ProfileException } from './loading.js';

interface Expr {
  symbol?: string;
  node?: Expr;
  seq?: Expr[];
  alt?: Expr[];
}
interface Property {
  key: string;
  value: Expr;
  rule: string;
}
interface Member {
  kind: string;
  body: string;
  named?: boolean;
}
interface Body {
  properties: Property[];
  members: Member[];
  ordered: boolean;
  fields?: unknown;
}
interface Metadata {
  kinds: Member[];
  nestedKinds: Member[];
  bodies: { [key: string]: Body };
  rules: { [key: string]: string };
}
const metadata = canonicalMetadata as Metadata;
const kinds = new Map([...metadata.nestedKinds, ...metadata.kinds].map((k) => [k.kind, k.body]));
interface ProfileRecord {
  kind: string;
  parent: ProfileRecord | null;
  source: { span(key?: boolean): import('./loading.js').ProfileSpan };
}
const concepts = new Set([
  'kind',
  'subkind',
  'variant',
  'event',
  'phase',
  'quality',
  'relation_type',
]);
const cnStereotypes = new Set([...concepts, 'role']);
const symbols = (expr: Expr): string[] =>
  expr.symbol
    ? [expr.symbol]
    : expr.node
      ? symbols(expr.node)
      : (expr.seq ?? expr.alt ?? []).flatMap(symbols);
function reaches(rule: string, body: string, seen = new Set<string>()): boolean {
  if (rule === body) return true;
  if (seen.has(rule)) return false;
  seen.add(rule);
  return (metadata.rules[rule] ?? '')
    .split(/[^A-Za-z0-9_]+/)
    .some((s) => s !== rule && /^[a-z]/.test(s) && reaches(s, body, seen));
}
function bodyName(record: ProfileRecord): string {
  return member(record)?.body ?? kinds.get(record.kind)!;
}
function member(record: ProfileRecord): Member | undefined {
  return record.parent
    ? metadata.bodies[bodyName(record.parent)]?.members.find((m) => m.kind === record.kind)
    : undefined;
}
export const ProfileVocabulary = {
  cnStereotypes,
  model(kind: string): string {
    if (['term', 'pattern', 'example'].includes(kind)) return 'lexicon';
    const ast =
      kind === 'domain'
        ? 'mdDomain'
        : kind === 'map'
          ? 'mdMap'
          : concepts.has(kind)
            ? 'concept'
            : kind.startsWith('cn2')
              ? kind
              : kind.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
    return modelForKind(ast);
  },
  semanticsKind: (kind: string): string => (concepts.has(kind) ? 'concept' : kind),
  referentKind: (path: string[]): string | undefined =>
    kinds.has(path.at(-1) ?? '') ? path.at(-1) : undefined,
  defines: (record: ProfileRecord, property: string): boolean =>
    metadata.bodies[bodyName(record)]?.properties.some((p) => p.key === property) ?? false,
  member,
  storageMember: (record: ProfileRecord): boolean =>
    !!record.parent && bodyName(record.parent) === 'storageDef',
  rekind(record: ProfileRecord, value: Json): string | undefined {
    return typeof value === 'string' &&
      !record.parent &&
      this.model(record.kind) === 'cn' &&
      cnStereotypes.has(value)
      ? value
      : undefined;
  },
  nestedSlotOrNull(parent: ProfileRecord, child: ProfileRecord): string | undefined {
    const name = bodyName(parent),
      body = metadata.bodies[name],
      childBody = bodyName(child);
    if (body?.members.some((m) => m.body === childBody))
      return name === 'storageDef' ? 'schemas' : 'members';
    const props =
      body?.properties.filter((p) => symbols(p.value).some((s) => reaches(s, childBody))) ?? [];
    return props.length === 1 ? props[0].key : undefined;
  },
  nestedSlot(parent: ProfileRecord, child: ProfileRecord): string {
    const slot = this.nestedSlotOrNull(parent, child);
    if (slot) return slot;
    throw new ProfileException(
      'ys/profile-invalid',
      child.source.span(),
      `${child.kind} cannot nest unambiguously under ${parent.kind}`
    );
  },
};

const object = isRecord;
const utf8 = (a: string, b: string): number => {
  const x = new TextEncoder().encode(a),
    y = new TextEncoder().encode(b);
  for (let i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return x[i] - y[i];
  return x.length - y.length;
};
function sorted(entries: [string, Json][], order: string[] = []): Map<string, Json> {
  const rank = (s: string) => {
    const i = order.indexOf(s);
    return i < 0 ? order.length : i;
  };
  return new Map(entries.sort(([a], [b]) => rank(a) - rank(b) || utf8(a, b)));
}
function data(value: Json): Json {
  return Array.isArray(value)
    ? value.map(data)
    : object(value) || value instanceof Map
      ? sorted(outputEntries(value).map(([k, v]) => [k, data(v)]))
      : value;
}
function definition(
  value: { [key: string]: Json },
  model: string,
  body = kinds.get(String(value.kind))!
): Json {
  return {
    ...(value.kind !== undefined ? { kind: value.kind } : {}),
    ...(value.id !== undefined ? { id: value.id } : {}),
    properties: properties(body, value.properties, model),
  };
}
function properties(name: string, value: Json, model: string): Json {
  const body = metadata.bodies[name];
  if (!body) return data(value);
  if ('fields' in body) return new Map(outputEntries(value).map(([k, v]) => [k, data(v)]));
  const props = new Map(body.properties.map((p) => [p.key, p]));
  const entries: [string, Json][] = (
    Array.isArray(value)
      ? value.flatMap((v) => Object.entries(v as { [key: string]: Json }))
      : outputEntries(value)
  ).map(([k, v]) => {
    if (
      body.members.length &&
      k === (name === 'storageDef' ? 'schemas' : 'members') &&
      Array.isArray(v)
    ) {
      return [
        k,
        v.map((m) => {
          const obj = m as { [key: string]: Json };
          return name === 'storageDef'
            ? {
                id: obj.id,
                fields: new Map(
                  outputEntries(obj.fields).map(([key, value]) => [key, data(value)])
                ),
              }
            : definition(obj, model, body.members.find((b) => b.kind === obj.kind)!.body);
        }),
      ];
    }
    if (
      props.get(k)?.rule === 'stepsProperty' &&
      Array.isArray(v) &&
      v.every((item) => object(item) && 'id' in item)
    )
      return [k, v.map((item) => definition(item as { [key: string]: Json }, model, 'autoBody'))];
    if (props.has(k)) return [k, property(props.get(k)!.value, v, model)];
    return [k, data(v)];
  });
  return body.ordered && !(name === 'roleDef' && model !== 'cn')
    ? entries.map(([k, v]) => ({ [k]: v }))
    : Object.fromEntries(
        sorted(
          entries,
          body.properties.map((p) => p.key)
        )
      );
}
function property(expr: Expr, value: Json, model: string): Json {
  const symbol = expr.symbol;
  if (symbol && metadata.bodies[symbol] && typeof value === 'object' && value !== null)
    return properties(symbol, value, model);
  const nested: { [key: string]: string } = {
    columnDefList: 'columnDef',
    attributeDefList: 'attributeDef',
    indexDefList: 'indexDef',
    constraintDefList: 'constraintDef',
    measureInlineList: 'measureDef',
  };
  if (symbol && nested[symbol] && Array.isArray(value))
    return value.map((v) => definition(v as { [key: string]: Json }, model, nested[symbol]));
  if (symbol === 'parameterDefList' && Array.isArray(value))
    return value.map((v) => properties('parameterInline', v, model));
  if (symbol === 'measuresValue' && Array.isArray(value) && object(value[0]))
    return value.map((v) => definition(v as { [key: string]: Json }, model, 'measureDef'));
  if (symbol === 'bindingValue' && object(value)) return properties('bindingBlock', value, model);
  if (symbol === 'restrictBlock' && object(value))
    return sorted(
      outputEntries(value).map(([k, v]) => [
        k,
        object(v) && !('ref' in v || 'triple' in v || 'call' in v || 'range' in v || 'object' in v)
          ? new Map(outputEntries(v).map(([a, b]) => [a, data(b)]))
          : data(v),
      ])
    );
  return data(value);
}
export function normalizeCanonical(raw: { [key: string]: Json }): { [key: string]: Json } {
  const result = { ...raw };
  for (const key of ['imports', 'defs', 'security'])
    if (Array.isArray(result[key]) && !result[key].length) delete result[key];
  if (Array.isArray(result.defs))
    result.defs = result.defs.map((v) =>
      definition(v as { [key: string]: Json }, String(result.model))
    );
  return Object.fromEntries(
    ['ttr', 'package', 'imports', 'model', 'schema', 'locale', 'graph', 'security', 'defs']
      .filter((k) => k in result)
      .map((k) => [k, result[k]])
  );
}
function quoted(value: string): string {
  return JSON.stringify(value).replace(
    /[\u007f-\u0084\u0086-\u009f\ufffe\uffff]/g,
    (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0')
  );
}
/** CanonicalYaml's deterministic, all-quoted scalar codec. */
export function canonicalWrite(raw: { [key: string]: Json }): string {
  let out = '';
  function emit(value: Json, indent: number): void {
    const pad = ' '.repeat(indent);
    const entries: [string, Json][] = Array.isArray(value)
      ? value.map((v) => ['-', v])
      : outputEntries(value);
    for (const [key, v] of entries) {
      const label = Array.isArray(value)
        ? '-'
        : /^[A-Za-z_][A-Za-z0-9_]*$/.test(key) && !/^(null|true|false)$/i.test(key)
          ? key + ':'
          : quoted(key) + ':';
      const prefix = pad + label + ' ';
      if (
        typeof v === 'string' &&
        v.includes('\n') &&
        !v.includes('\r') &&
        /\S/.test(v) &&
        !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u0084\u0086-\u009f\ufffe\uffff]/.test(v)
      ) {
        const trailing = v.match(/\n*$/)![0].length;
        out += prefix + (trailing === 0 ? '|2-' : trailing === 1 ? '|2' : '|2+') + '\n';
        const lines = v.split('\n');
        if (trailing) lines.pop();
        for (const line of lines) out += (line ? ' '.repeat(indent + 2) + line : '') + '\n';
      } else if (
        v === null ||
        typeof v !== 'object' ||
        v instanceof NumericScalar ||
        (v instanceof Map ? v.size : Object.keys(v).length) === 0
      )
        out += prefix + (typeof v === 'string' ? quoted(v) : jsonText(v)) + '\n';
      else {
        out += prefix.trimEnd() + '\n';
        emit(v, indent + 2);
      }
    }
  }
  emit(normalizeCanonical(raw), 0);
  return out;
}
