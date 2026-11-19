// SPDX-License-Identifier: Apache-2.0
import { realpathSync, statSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import Ajv2020Module from 'ajv/dist/2020.js';
import type { ErrorObject, ValidateFunction } from 'ajv';
import { ForeignData, isRecord, pointer, type Json, type RecordValue } from './input.js';
import { NumericScalar } from './scalars.js';
import { readProfileFile } from './file-input.js';
import {
  profileSchema,
  runningGrammar,
  canonicalMetadata,
  canonicalProfileText,
} from './generated/resources.js';

export interface ProfileSpan {
  file: string;
  line: number;
  column: number;
  endLine: number;
  endColumn: number;
  path: string;
}
export class ProfileException extends Error {
  constructor(
    readonly code: string,
    readonly location: ProfileSpan,
    readonly detail: string,
    readonly suggestion?: RecordValue
  ) {
    super(
      `${location.file}:${location.line}:${location.column}: ${code} ${location.path}: ${detail}`
    );
    this.name = 'ProfileException';
  }
}
export interface LocatedOp {
  value: RecordValue;
  location: ProfileSpan;
  locations: Map<string, ProfileSpan>;
}
export interface ProfileDefinition {
  name: string;
  major: bigint;
  grammarRange: GrammarRange;
  extends?: string;
  overlay?: Json;
  writable: boolean;
  operations: LocatedOp[];
  locations: Map<string, ProfileSpan>;
  keyLocations: Map<string, ProfileSpan>;
  file: string;
  unknown?: 'error' | 'ignore';
}
export interface ProfileDeclaration {
  profile: string;
  grammarRange: GrammarRange;
  location: ProfileSpan;
  grammarLocation: ProfileSpan;
}
type Version = [bigint, bigint, bigint];
const version = (text: string): Version => {
  if (!/^[0-9]+\.[0-9]+(?:\.[0-9]+)?$/.test(text))
    throw new Error('Expected a version with two or three numeric components');
  const parts = text.split('.').map(BigInt);
  return [parts[0], parts[1], parts[2] ?? 0n];
};
/** Comparator sets: whitespace means AND, || means OR. No npm shorthands. */
export class GrammarRange {
  static get runningGrammar(): string {
    return runningGrammar;
  }
  private constructor(
    readonly text: string,
    private readonly alternatives: [string, Version][][]
  ) {}
  toString(): string {
    return this.text;
  }
  static parse(text: string): GrammarRange {
    const alternatives = text.split('||').map((set) => {
      const value = set.trim();
      if (!value) throw new Error('Empty grammar comparator set');
      let end = 0;
      const comparators: [string, Version][] = [];
      for (const match of value.matchAll(/(?:(>=|<=|>|<|=)\s*)?([0-9]+\.[0-9]+(?:\.[0-9]+)?)/g)) {
        const start = match.index;
        if (!(end === 0 && start === 0) && !(start > end && /^\s+$/.test(value.slice(end, start))))
          throw new Error('Comparators must be separated by whitespace');
        end = start + match[0].length;
        comparators.push([match[1] || '=', version(match[2])]);
      }
      if (!comparators.length || end !== value.length)
        throw new Error(`Unsupported grammar range: ${text}`);
      return comparators;
    });
    return new GrammarRange(text, alternatives);
  }
  admits(grammar = runningGrammar): boolean {
    const actual = version(grammar);
    return this.alternatives.some((set) =>
      set.every(([operator, expected]) => {
        let cmp = 0;
        for (let i = 0; i < 3; i++)
          if (actual[i] !== expected[i]) {
            cmp = actual[i] > expected[i] ? 1 : -1;
            break;
          }
        return operator === '>'
          ? cmp > 0
          : operator === '>='
            ? cmp >= 0
            : operator === '<'
              ? cmp < 0
              : operator === '<='
                ? cmp <= 0
                : cmp === 0;
      })
    );
  }
}

type AjvConstructor = new (options: object) => { compile(schema: object): ValidateFunction };
const Ajv = ((Ajv2020Module as unknown as { default?: AjvConstructor }).default ??
  Ajv2020Module) as AjvConstructor;
const validators = new Map<number, ValidateFunction>();
function invalid(data: ForeignData, path: string, detail: string, key = true): never {
  throw new ProfileException('ys/profile-invalid', data.span(path, key), detail);
}
function validate(
  data: ForeignData,
  branch: number,
  value: Json = data.value,
  prefix = ''
): RecordValue {
  let validator = validators.get(branch);
  if (!validator) {
    const root = profileSchema as unknown as { oneOf: object[]; [key: string]: unknown };
    const { oneOf, ...rest } = root;
    validator = new Ajv({ allErrors: true, strict: false, ownProperties: true }).compile({
      ...rest,
      ...oneOf[branch],
    });
    validators.set(branch, validator);
  }
  if (!validator(JSON.parse(JSON.stringify(value)))) {
    const errorPath = (e: ErrorObject) =>
      e.keyword === 'additionalProperties'
        ? pointer(e.instancePath, String(e.params.additionalProperty))
        : e.instancePath;
    const error = [...(validator.errors ?? [])].sort(
      (a, b) => b.instancePath.length - a.instancePath.length
    )[0];
    invalid(data, prefix + (error ? errorPath(error) : ''), error?.message ?? 'Invalid profile');
  }
  return value as RecordValue;
}
function range(data: ForeignData, value: string, path: string): GrammarRange {
  try {
    return GrammarRange.parse(value);
  } catch (error) {
    return invalid(
      data,
      path,
      error instanceof Error ? error.message : 'Invalid grammar range',
      false
    );
  }
}

export class ProfileReader {
  static read(source: string, file = '<profile>'): ProfileDefinition {
    const data = new ForeignData(source, file);
    const value = validate(data, 1);
    const operations = value.operations as RecordValue[];
    operations.forEach((op, index) => {
      const to = isRecord(op.to) ? op.to : undefined;
      if (to && to.kind !== undefined && op.in !== to.kind)
        invalid(data, `/operations/${index}/to/kind`, `to.kind must equal in (${op.in})`);
      if (op.op === 'link' && typeof op.kind === 'string' && to) {
        const path = Array.isArray(to.path) ? to.path : [to.property];
        const last = path[path.length - 1];
        const metadata = canonicalMetadata as unknown as {
          kinds: { kind: string }[];
          nestedKinds: { kind: string }[];
        };
        if (
          typeof last === 'string' &&
          [...metadata.kinds, ...metadata.nestedKinds].some((k) => k.kind === last) &&
          last !== op.kind
        )
          invalid(
            data,
            `/operations/${index}/kind`,
            `kind ${op.kind} disagrees with the target ${path.join('.')}, which accepts ${last} records`
          );
      }
      if (Array.isArray(op.files))
        op.files.forEach((pattern, i) => {
          try {
            new FileSelector([String(pattern)]);
          } catch (error) {
            invalid(
              data,
              `/operations/${index}/files/${i}`,
              error instanceof Error ? error.message : 'Invalid file selector',
              false
            );
          }
        });
    });
    const locations = new Map([...data.paths].map((path) => [path, data.span(path)]));
    const identity = value.profile as string,
      slash = identity.lastIndexOf('/');
    return {
      name: identity.slice(0, slash),
      major: BigInt(identity.slice(slash + 1)),
      file,
      grammarRange: range(data, value.grammar as string, '/grammar'),
      extends: value.extends as string | undefined,
      overlay: value.overlay,
      writable: value.writable === true,
      unknown: value.unknown as 'error' | 'ignore' | undefined,
      operations: operations.map((op, i) => {
        const path = `/operations/${i}`;
        return {
          value: op,
          location: data.span(path),
          locations: new Map(
            [...locations].filter(([p]) => p === path || p.startsWith(`${path}/`))
          ),
        };
      }),
      locations,
      keyLocations: new Map(
        [...data.paths]
          .filter((path) => path.startsWith('/overlay/'))
          .map((path) => [path, data.span(path, true)])
      ),
    };
  }
}

export type FileHeader =
  | { type: 'absent' }
  | { type: 'canonical'; version: string }
  | { type: 'declared'; declaration: ProfileDeclaration };
export class DeclarationReader {
  private static declaration(data: ForeignData, value: Json, prefix: string): ProfileDeclaration {
    const object = validate(data, 0, value, prefix);
    return {
      profile: object.profile as string,
      grammarRange: range(data, object.grammar as string, `${prefix}/grammar`),
      location: data.span(`${prefix}/profile`),
      grammarLocation: data.span(`${prefix}/grammar`),
    };
  }
  static readTree(source: string, file = 'ttr.yaml'): ProfileDeclaration {
    const data = new ForeignData(source, file);
    return this.declaration(data, data.value, '');
  }
  static header(source: string, file = '<yaml>'): FileHeader {
    const data = new ForeignData(source, file, '/ttr');
    if (!isRecord(data.value) || data.value.ttr == null) return { type: 'absent' };
    const header = data.value.ttr;
    if (!isRecord(header) && !Array.isArray(header))
      return {
        type: 'canonical',
        version: header instanceof NumericScalar ? header.text : String(header),
      };
    // Use authored keys: JS reorders integer property names.
    if (data.keys.keys().next().value !== '/ttr')
      invalid(data, '/ttr', 'ttr header must be the first mapping key');
    if (!isRecord(header)) invalid(data, '/ttr', 'ttr header must be a mapping {profile, grammar}');
    return { type: 'declared', declaration: this.declaration(data, header, '/ttr') };
  }
  static readFile(source: string, file = '<yaml>'): ProfileDeclaration | null {
    const header = this.header(source, file);
    return header.type === 'declared' ? header.declaration : null;
  }
  static select(
    source: string,
    file: string,
    tree: ProfileDeclaration | null = null
  ): ProfileDeclaration | null {
    const header = this.header(source, file);
    return header.type === 'declared'
      ? header.declaration
      : header.type === 'canonical'
        ? null
        : tree;
  }
}

/** Shared real-path confinement for profile and overlay files. */
export function confinedFile(
  label: string,
  ref: string,
  at: ProfileSpan,
  boundary: string,
  candidate: (() => string) | string
): string {
  try {
    const real = realpathSync(typeof candidate === 'string' ? candidate : candidate());
    const rel = relative(boundary, real);
    if (rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel) || !statSync(real).isFile())
      throw new ProfileException(
        'ys/profile-not-found',
        at,
        `${label} is outside root or is not a file: ${ref}`
      );
    return real;
  } catch (error) {
    if (error instanceof ProfileException) throw error;
    throw new ProfileException('ys/profile-not-found', at, `${label} not found: ${ref}`);
  }
}
function checkRange(range: GrammarRange, at: ProfileSpan): void {
  if (!range.admits())
    throw new ProfileException(
      'ys/profile-grammar-mismatch',
      at,
      `${range.text} excludes running grammar ${runningGrammar}`
    );
}
const versionedReference = /^.+\/[0-9]+$/;
let canonicalProfile: ProfileDefinition | undefined;
const canonical = () =>
  (canonicalProfile ??= ProfileReader.read(
    canonicalProfileText,
    '<bundled>/profiles/canonical.yaml'
  ));

export class ProfileResolver {
  readonly root: string;
  private readonly catalog: Map<string, string>;
  constructor(
    root: string,
    catalog: ReadonlyMap<string, string> | Record<string, string> = new Map(),
    private readonly shipped: (identity: string) => string | null | undefined = () => null
  ) {
    this.root = realpathSync(root);
    this.catalog = catalog instanceof Map ? new Map(catalog) : new Map(Object.entries(catalog));
  }
  static readonly SHIPPED_LABEL = '<shipped>/profiles/';
  static shippedLabel(identity: string): string {
    return `${this.SHIPPED_LABEL}${identity}.yaml`;
  }
  static isBundled(ref: string): boolean {
    return (
      ref === 'canonical' ||
      (versionedReference.test(ref) &&
        ref.slice(0, ref.lastIndexOf('/')) === 'canonical' &&
        BigInt(ref.slice(ref.lastIndexOf('/') + 1)) === 1n)
    );
  }
  static bundled(reference: string | ProfileDeclaration): ProfileDefinition[] | null {
    const ref = typeof reference === 'string' ? reference : reference.profile;
    if (!this.isBundled(ref)) return null;
    if (typeof reference !== 'string')
      checkRange(reference.grammarRange, reference.grammarLocation);
    const profile = canonical();
    checkRange(profile.grammarRange, profile.locations.get('/grammar')!);
    return [profile];
  }
  resolve(declaration: ProfileDeclaration | null): ProfileDefinition[];
  resolve(reference: string, declaringFile: string): ProfileDefinition[];
  resolve(
    reference: string | ProfileDeclaration | null,
    declaringFile?: string
  ): ProfileDefinition[] {
    if (reference === null) return this.resolve('canonical/1', resolve(this.root, 'ttr.yaml'));
    if (typeof reference !== 'string') {
      checkRange(reference.grammarRange, reference.grammarLocation);
      return this.resolveAt(reference.profile, reference.location.file, reference.location);
    }
    const file = declaringFile ?? resolve(this.root, 'ttr.yaml');
    return this.resolveAt(reference, file, {
      file,
      line: 1,
      column: 1,
      endLine: 1,
      endColumn: 1,
      path: '/profile',
    });
  }
  private resolveAt(
    reference: string,
    declaringFile: string,
    location: ProfileSpan
  ): ProfileDefinition[] {
    const chain: ProfileDefinition[] = [],
      visiting = new Set<string>();
    const visit = (
      ref: string,
      from: string,
      at: ProfileSpan,
      depth: number,
      fromShipped: boolean
    ): void => {
      const bundled = ProfileResolver.isBundled(ref);
      const identity = versionedReference.test(ref) && !ref.startsWith('.') && !isAbsolute(ref);
      let shippedText: string | null | undefined;
      if (!bundled) {
        if (fromShipped) {
          shippedText = identity ? this.shipped(ref) : null;
          if (shippedText == null)
            throw new ProfileException(
              'ys/profile-not-found',
              at,
              `A shipped profile extends only shipped profiles; none is named ${ref}`
            );
        } else if (identity && !this.catalog.has(ref)) shippedText = this.shipped(ref);
      }
      let path: string | undefined;
      if (!bundled && shippedText == null)
        path = confinedFile('Profile', ref, at, this.root, () => {
          if (this.catalog.has(ref)) return resolve(this.root, this.catalog.get(ref)!);
          if (!ref.startsWith('.') && !isAbsolute(ref) && !/\.ya?ml$/.test(ref))
            throw new ProfileException('ys/profile-not-found', at, `No catalog entry for ${ref}`);
          return resolve(dirname(resolve(this.root, from)), ref);
        });
      const key = path ?? `${ProfileResolver.SHIPPED_LABEL}${ref}`;
      if (visiting.has(key))
        throw new ProfileException('ys/profile-cycle', at, `Profile cycle through ${ref}`);
      visiting.add(key);
      if (depth > 32)
        throw new ProfileException('ys/profile-invalid', at, 'Profile chain exceeds 32 profiles');
      let profile: ProfileDefinition;
      if (bundled) profile = canonical();
      else if (shippedText != null)
        profile = ProfileReader.read(shippedText, ProfileResolver.shippedLabel(ref));
      else {
        let source: string;
        try {
          source = readProfileFile(path!);
        } catch (error) {
          if (error instanceof ProfileException) throw error;
          throw new ProfileException('ys/profile-not-found', at, `Cannot read profile ${ref}`);
        }
        profile = ProfileReader.read(source, path!);
      }
      if (shippedText != null || (!bundled && this.catalog.has(ref))) {
        const slash = ref.lastIndexOf('/');
        const matches = versionedReference.test(ref)
          ? ref.slice(0, slash) === profile.name && BigInt(ref.slice(slash + 1)) === profile.major
          : ref === profile.name;
        if (!matches)
          throw new ProfileException(
            'ys/profile-invalid',
            profile.locations.get('/profile')!,
            `${shippedText != null ? 'Shipped profile' : 'Catalog entry'} ${ref} loads ${profile.name}/${profile.major}`
          );
      }
      checkRange(profile.grammarRange, profile.locations.get('/grammar')!);
      if (profile.extends !== undefined)
        visit(
          profile.extends,
          path ?? from,
          profile.locations.get('/extends')!,
          depth + 1,
          shippedText != null
        );
      chain.push(profile);
    };
    visit(reference, declaringFile, location, 1, false);
    return chain;
  }
}

/** Gitignore subset used by profile files selectors; no re-inclusion. */
export class FileSelector {
  private readonly rules: { regex: RegExp; directory: boolean }[];
  constructor(patterns: string[]) {
    this.rules = patterns.flatMap((raw) => {
      let end = raw.length;
      while (end > 0 && raw[end - 1] === ' ') {
        let slash = end - 2;
        while (slash >= 0 && raw[slash] === '\\') slash--;
        if ((end - 2 - slash) % 2 === 1) break;
        end--;
      }
      const pattern = raw.slice(0, end);
      if (pattern.startsWith('!')) throw new Error("'!' (re-include) is not supported");
      if (!pattern.trim() || pattern.startsWith('#')) return [];
      const directory = pattern.endsWith('/');
      const body = pattern.replace(/^\//, '').replace(/\/$/, '');
      return [
        {
          regex: new RegExp(
            (pattern.startsWith('/') || body.includes('/') ? '^' : '^(?:.*/)?') +
              FileSelector.glob(body) +
              '$',
            'u'
          ),
          directory,
        },
      ];
    });
  }
  matches(path: string): boolean {
    const parts = path.split('/');
    return this.rules.some(
      (rule) =>
        parts.slice(1).some((_, i) => rule.regex.test(parts.slice(0, i + 1).join('/'))) ||
        (!rule.directory && rule.regex.test(path))
    );
  }
  private static glob(pattern: string): string {
    const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    let out = '';
    for (let i = 0; i < pattern.length; ) {
      const c = pattern[i++];
      if (c === '*') {
        if (
          (i === 1 || pattern[i - 2] === '/') &&
          pattern[i] === '*' &&
          (i + 1 === pattern.length || pattern[i + 1] === '/')
        ) {
          i++;
          if (i < pattern.length) {
            i++;
            out += '(?:.*/)?';
          } else out += '.*';
        } else out += '[^/]*';
      } else if (c === '\\') {
        if (i === pattern.length) throw new Error('Trailing glob escape');
        out += escape(pattern[i++]);
      } else if (c === '?') out += '[^/]';
      else if (c === '[') {
        const end = pattern.indexOf(']', i);
        if (end <= i) throw new Error('Unclosed or empty glob character class');
        let body = pattern.slice(i, end),
          negate = '';
        if (body.startsWith('!') || body.startsWith('^')) {
          negate = '^';
          body = body.replace(/^!/, '').replace(/^\^/, '');
        }
        if (!body.length) throw new Error('Empty glob character class');
        out += `(?=[^/])[${negate}${body.replace(/\\/g, '\\\\')}]`;
        i = end + 1;
      } else out += escape(c);
    }
    return out;
  }
}
