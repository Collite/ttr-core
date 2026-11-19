// SPDX-License-Identifier: Apache-2.0
/** Desktop adapter. Kept off the browser-safe lint entry point. */
import { existsSync, readdirSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  parseYamlDocument,
  type ParseResult,
  type ParseError,
  type Document,
  type SourceLocation,
} from '@tatrman/parser';
import {
  DeclarationReader,
  ProfileResolver,
  ProfileEngine,
  ForeignTree,
  ProfileException,
  ForeignData,
  isRecord,
  type ProfileDefinition,
  type ProfileSpan,
} from '@tatrman/profile';

export const isYamlFile = (uri: string): boolean => /\.ya?ml$/i.test(uri);
const fileUri = (file: string): string =>
  file.startsWith('file:') ? file : pathToFileURL(file).href;
const pathOf = (uri: string): string =>
  uri.startsWith('file:') ? fileURLToPath(uri) : resolve(uri);
const empty = (uri: string): ParseResult => ({ sourceFile: uri, errors: [] });

/** Nearest marker discovery is cached per workspace; invalidate on profile or marker changes. */
export class YamlWorkspaceLoader {
  private markerSignature = '';
  private readonly markers = new Map<string, string | undefined>();
  private readonly roots: string[];
  constructor(
    roots: string[],
    private readonly profile?: string | boolean
  ) {
    this.roots = roots.map(pathOf);
  }
  invalidate(): void {
    this.markers.clear();
  }
  private marker(file: string, read: (file: string) => string | undefined): string | undefined {
    let directory = dirname(file);
    const boundary = this.roots
      .filter((root) => directory === root || directory.startsWith(root + sep))
      .sort((a, b) => b.length - a.length)[0];
    if (!boundary) return undefined;
    const visited: string[] = [];
    let found: string | undefined;
    while (directory === boundary || directory.startsWith(boundary + sep)) {
      if (this.markers.has(directory)) {
        found = this.markers.get(directory);
        break;
      }
      visited.push(directory);
      const candidate = join(directory, 'ttr.yaml');
      if (read(candidate) !== undefined) {
        found = candidate;
        break;
      }
      if (directory === boundary) break;
      directory = dirname(directory);
    }
    for (const dir of visited) this.markers.set(dir, found);
    return found;
  }
  load(
    buffers: ReadonlyMap<string, string> = new Map()
  ): Map<string, ParseResult & { text: string }> {
    const texts = new Map<string, string>();
    const aliases = new Map<string, string>();
    const physical = (file: string): string => {
      try {
        return realpathSync(file);
      } catch {
        return resolve(file);
      }
    };
    for (const uri of buffers.keys())
      if (uri.startsWith('file:')) aliases.set(physical(pathOf(uri)), uri);
    const uriOf = (file: string): string =>
      file.startsWith('file:') ? file : (aliases.get(physical(file)) ?? fileUri(file));
    const read = (file: string): string | undefined => {
      const uri = uriOf(file);
      if (buffers.has(uri)) return buffers.get(uri);
      try {
        return new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(file));
      } catch (error) {
        if (!existsSync(file)) return undefined;
        if (!(error instanceof TypeError)) throw error;
        throw new ProfileException(
          'ys/profile-invalid',
          { file, line: 1, column: 1, endLine: 1, endColumn: 1, path: '' },
          'Malformed UTF-8'
        );
      }
    };
    const walk = (directory: string): void => {
      let entries;
      try {
        entries = readdirSync(directory, { withFileTypes: true });
      } catch {
        return;
      }
      for (const entry of entries) {
        const file = join(directory, entry.name);
        if (entry.isDirectory() && !['.git', 'node_modules', 'build', 'dist'].includes(entry.name))
          walk(file);
        else if (entry.isFile() && isYamlFile(file)) {
          // Read admitted models below; plain YAML must remain untouched, including malformed bytes.
          const uri = uriOf(file);
          aliases.set(physical(file), uri);
          texts.set(uri, '');
        }
      }
    };
    this.roots.forEach(walk);
    for (const [uri, text] of buffers)
      if (uri.startsWith('file:') && isYamlFile(uri)) texts.set(uri, text);
    const signature = [...texts.keys()].filter(uri => /\/ttr\.yaml$/.test(uri)).sort().map(uri => {
      try { return uri + '\0' + read(pathOf(uri)); } catch { return uri + '\0<invalid>'; }
    }).join('\0');
    if (signature !== this.markerSignature) { this.invalidate(); this.markerSignature = signature; }
    const out = new Map<string, ParseResult>();
    const excluded = new Set<string>();
    const failures = new Set<string>();
    const safeRead = (uri: string): string => {
      try {
        return read(pathOf(uri)) ?? '';
      } catch {
        return '';
      }
    };
    const groups = new Map<
      string,
      { root: string; chain: ProfileDefinition[]; trees: ForeignTree[] }
    >();
    const failure = (error: ProfileException, fallback: string): void => {
      const uri = error.location.file.startsWith('<') ? fallback : uriOf(error.location.file);
      const identity = `${uri}:${error.location.path}:${error.code}:${error.detail}`;
      if (failures.has(identity)) return;
      failures.add(identity);
      const text = buffers.get(uri) ?? safeRead(uri);
      const source = spanSource(error.location, uri, text);
      const result = out.get(uri) ?? empty(uri);
      result.errors.push({
        code: error.code as ParseError['code'],
        severity: 'error',
        message: error.detail,
        source,
      });
      out.set(uri, result);
    };
    for (const uri of texts.keys()) {
      const file = pathOf(uri);
      if (/\/(?:ttr|ttr-profile)\.ya?ml$/.test(file)) continue;
      let marker: string | undefined;
      try {
        marker = this.marker(file, read);
        // Only a literal ttr header can opt in outside a marker tree.
        const text = read(file) ?? '';
        if (!marker && !this.profile && !/^ttr\s*:/m.test(text)) continue;
        const boundary = marker
          ? dirname(marker)
          : (this.roots
              .filter((root) => file.startsWith(root + sep))
              .sort((a, b) => b.length - a.length)[0] ?? dirname(file));
        const header = DeclarationReader.header(text, file);
        if (header.type === 'canonical') {
          out.set(uri, parseYamlDocument(text, uri));
          continue;
        }
        let treeDeclaration = null;
        if (header.type === 'absent' && marker) {
          const source = read(marker)!;
          const value = new ForeignData(source, marker).value;
          if (isRecord(value) && value.profile !== undefined)
            treeDeclaration = DeclarationReader.readTree(source, marker);
        }
        const declaration = header.type === 'declared' ? header.declaration : treeDeclaration;
        if (!declaration && typeof this.profile !== 'string') continue;
        const resolver = new ProfileResolver(
          boundary,
          new Map(),
          () => null,
          (path) => read(path)!
        );
        const chain =
          typeof this.profile === 'string' && header.type === 'absent'
            ? resolver.resolve(this.profile, marker ?? join(boundary, 'ttr.yaml'))
            : resolver.resolve(declaration);
        chain.forEach((profile) => {
          if (!profile.file.startsWith('<')) excluded.add(uriOf(profile.file));
        });
        const key = boundary + '\0' + chain.map((p) => p.file).join('\0');
        let group = groups.get(key);
        if (!group) {
          group = { root: boundary, chain, trees: [] };
          groups.set(key, group);
        }
        group.trees.push(
          ForeignTree.read(text, relative(boundary, file).split(sep).join('/'), uri)
        );
        out.set(uri, empty(uri));
      } catch (error) {
        if (!(error instanceof ProfileException)) throw error;
        if (!marker && !this.profile && error.detail === 'Malformed UTF-8') continue;
        failure(error, uri);
        out.set(uri, out.get(uri) ?? empty(uri));
      }
    }
    for (const uri of excluded) out.delete(uri);
    for (const group of groups.values()) {
      let pending = group.trees.filter((tree) => !excluded.has(tree.label));
      while (pending.length) {
        try {
          const applied = ProfileEngine.apply(pending, group.chain, group.root, true);
          for (const document of applied.documents) {
            const uri = uriOf(join(group.root, document.file!));
            const previous = out.get(uri) ?? empty(uri);
            const ast = authoredAst(document.ast, pending);
            if (previous.ast)
              previous.ast = {
                ...previous.ast,
                definitions: [...previous.ast.definitions, ...ast.definitions],
              };
            else previous.ast = ast;
            out.set(uri, previous);
          }
          for (const warning of applied.report.warnings) {
            const uri = uriOf(warning.location.file);
            const result = out.get(uri) ?? empty(uri);
            result.errors.push({
              code: (warning.key !== undefined
                ? 'ys/unknown-key'
                : 'ys/key-missing') as ParseError['code'],
              severity: 'warning',
              message: warning.message,
              source: spanSource(warning.location, uri, safeRead(uri)),
            });
            out.set(uri, result);
          }
          break;
        } catch (error) {
          if (!(error instanceof ProfileException)) throw error;
          failure(error, pending[0].label);
          const next = pending.filter((tree) => uriOf(error.location.file) !== tree.label);
          if (next.length === pending.length) break;
          pending = next;
        }
      }
    }
    return new Map([...out].map(([uri, result]) => [uri, { ...result, text: safeRead(uri) }]));
  }
}

function spanSource(span: ProfileSpan, uri: string, text: string): SourceLocation {
  const offset = (line: number, col: number): number =>
    text
      .split('\n')
      .slice(0, line - 1)
      .reduce((n, line) => n + [...line].length + 1, 0) + Math.max(0, col - 1);
  return {
    file: uri,
    line: span.line,
    column: span.column - 1,
    endLine: span.endLine,
    endColumn: span.endColumn - 1,
    offsetStart: offset(span.line, span.column),
    offsetEnd: offset(span.endLine, span.endColumn),
  };
}

/** Expand carried key positions to authored key+value ranges for editor hit testing. */
function authoredAst(ast: Document, trees: ForeignTree[]): Document {
  const spans = new Map<string, SourceLocation>();
  const key = (s: SourceLocation) => `${s.file}:${s.offsetStart}:${s.offsetEnd}`;
  for (const tree of trees)
    for (const path of tree.data.paths) {
      const value = tree.data.source(path),
        name = tree.data.source(path, true);
      const range = {
        ...name,
        endLine: value.endLine,
        endColumn: value.endColumn,
        offsetEnd: value.offsetEnd,
      };
      spans.set(key(value), range);
      spans.set(key(name), range);
    }
  const copy = (value: unknown, field = ''): unknown => {
    if (!value || typeof value !== 'object') return value;
    if (Array.isArray(value)) return value.map((child) => copy(child, field));
    if (ArrayBuffer.isView(value)) return value;
    if ('offsetStart' in value && 'file' in value)
      return field === 'source' ? (spans.get(key(value as SourceLocation)) ?? value) : value;
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, copy(v, k)]));
  };
  return copy(ast) as Document;
}
