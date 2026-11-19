// SPDX-License-Identifier: Apache-2.0
/** Desktop adapter. Kept off the browser-safe lint entry point. */
import { existsSync, readdirSync, readFileSync, realpathSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  parseYamlDocument,
  type ParseResult,
  type ParseError,
  type Document,
  type PropertyValue,
  type SourceLocation,
} from '@tatrman/parser';
import {
  DeclarationReader,
  FileSelector,
  ProfileReader,
  ProfileResolver,
  ProfileEngine,
  ForeignTree,
  ProfileException,
  ForeignData,
  isRecord,
  type ProfileDefinition,
  type ProfileDeclaration,
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
  private files?: string[];
  private readonly disk = new Map<string, { stamp: string; text: string }>();
  private readonly headers = new Map<
    string,
    { text: string; header: ReturnType<typeof DeclarationReader.header> }
  >();
  private readonly trees = new Map<string, { text: string; rootPath: string; tree: ForeignTree }>();
  private readonly canonical = new Map<string, { text: string; result: ParseResult }>();
  private readonly declarations = new Map<
    string,
    { text: string; declaration: ProfileDeclaration | null }
  >();
  private readonly ignores = new Map<string, FileSelector>();
  private readonly chains = new Map<
    string,
    { chain: ProfileDefinition[]; dependencies: Map<string, string | undefined> }
  >();
  private readonly applied = new Map<
    string,
    { signature: string; results: Map<string, ParseResult> }
  >();
  private lastResults = new Set<string>();
  private readonly groupDependencies = new Set<string>();
  /** Disk notifications invalidate only affected dependencies; create/delete also invalidates the walk. */
  notify(changes: readonly { uri: string; type: number }[]): boolean {
    let relevant = false;
    for (const change of changes) {
      const file = pathOf(change.uri);
      if (!this.roots.some((root) => file.startsWith(root + sep))) continue;
      this.disk.delete(file);
      if (change.type !== 2) {
        this.files = undefined;
        this.markers.clear();
      }
      if (
        this.lastResults.has(change.uri) ||
        this.groupDependencies.has(physicalPath(file)) ||
        /(?:^|[/\\])(?:ttr\.yaml|model\.ignore|renames\.yaml)$/.test(file) ||
        [...this.chains.values()].some((c) =>
          [...c.dependencies.keys()].some((p) => physicalPath(p) === physicalPath(file))
        ) ||
        (change.type !== 2 && this.marker(file, (p) => this.read(p)) !== undefined)
      )
        relevant = true;
    }

    return relevant;
  }
  private read(file: string): string | undefined {
    try {
      const stat = statSync(file);
      const stamp = `${stat.mtimeMs}:${stat.ctimeMs}:${stat.size}`;
      const cached = this.disk.get(file);
      if (cached?.stamp === stamp) return cached.text;
      const text = new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(file));
      this.disk.set(file, { stamp, text });
      return text;
    } catch (error) {
      if (!existsSync(file)) return undefined;
      if (!(error instanceof TypeError)) throw error;
      throw new ProfileException(
        'ys/profile-invalid',
        { file, line: 1, column: 1, endLine: 1, endColumn: 1, path: '' },
        'Malformed UTF-8'
      );
    }
  }
  private header(text: string, file: string) {
    const cached = this.headers.get(file);
    if (cached?.text === text) return cached.header;
    const header = DeclarationReader.header(text, file);
    this.headers.set(file, { text, header });
    return header;
  }
  /** The desktop host asks this same admission path; it never parses a second header grammar. */
  language(uri: string, text: string, refreshMarkers = true): 'ttr-yaml' | 'yaml' {
    if (refreshMarkers) this.markers.clear();
    const file = pathOf(uri);
    if (
      !isYamlFile(file) ||
      /\/ttr\.yaml$/.test(file) ||
      [...this.chains.values()].some((c) =>
        c.chain.some((p) => !p.file.startsWith('<') && physicalPath(p.file) === physicalPath(file))
      )
    )
      return 'yaml';
    const marker = this.marker(file, (p) => this.read(p));
    const boundary = marker
      ? dirname(marker)
      : this.roots
          .filter((root) => file.startsWith(root + sep))
          .sort((a, b) => b.length - a.length)[0];
    if (boundary && this.excluded(file, boundary, (p) => this.read(p))) return 'yaml';
    try {
      const header = this.header(text, file);
      if (header.type !== 'absent') return 'ttr-yaml';
      if (marker) {
        const data = new ForeignData(this.read(marker)!, marker).value;
        if (isRecord(data) && data.profile !== undefined) return 'ttr-yaml';
      }
    } catch {
      if (marker || /^ttr\s*:/m.test(text)) return 'ttr-yaml';
    }
    return 'yaml';
  }
  private excluded(
    file: string,
    root: string,
    read: (file: string) => string | undefined
  ): boolean {
    const path = relative(root, file).split(sep).join('/');
    const parts = path.split('/');
    if (
      parts.some((p) => p.startsWith('.')) ||
      parts[0] === 'agents' ||
      parts[0] === 'profiles' ||
      parts[0] === 'layouts' ||
      ['ttr.yaml', 'renames.yaml'].includes(path)
    )
      return true;
    const ignore = read(join(root, 'model.ignore'));
    try {
      if (ignore === undefined) return false;
      let selector = this.ignores.get(ignore);
      if (!selector) {
        selector = new FileSelector(ignore.split(/\r\n|\r|\n/));
        this.ignores.set(ignore, selector);
      }
      return selector.matches(path);
    } catch (error) {
      throw new ProfileException(
        'ys/profile-invalid',
        {
          file: join(root, 'model.ignore'),
          line: 1,
          column: 1,
          endLine: 1,
          endColumn: 1,
          path: '',
        },
        String(error instanceof Error ? error.message : error)
      );
    }
  }
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
    this.files = undefined;
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
    const physical = physicalPath;
    for (const uri of buffers.keys())
      if (uri.startsWith('file:')) aliases.set(physical(pathOf(uri)), uri);
    const uriOf = (file: string): string =>
      file.startsWith('file:') ? file : (aliases.get(physical(file)) ?? fileUri(file));
    const read = (file: string): string | undefined => {
      const uri = uriOf(file);
      if (buffers.has(uri)) return buffers.get(uri);
      return this.read(file);
    };
    const found: string[] = [];
    const walk = (directory: string, inTree = false): void => {
      inTree ||= existsSync(join(directory, 'ttr.yaml')) || typeof this.profile === 'string';
      let entries;
      try {
        entries = readdirSync(directory, { withFileTypes: true });
      } catch {
        return;
      }
      for (const entry of entries) {
        const file = join(directory, entry.name);
        if (entry.name.startsWith('.')) continue;
        if (
          entry.isDirectory() &&
          (inTree || !['node_modules', 'build', 'dist'].includes(entry.name))
        )
          walk(file, inTree);
        else if (entry.isFile() && isYamlFile(file)) {
          // Read admitted models below; plain YAML must remain untouched, including malformed bytes.
          const uri = uriOf(file);
          aliases.set(physical(file), uri);
          found.push(uri);
        }
      }
    };
    if (!this.files) {
      this.roots.forEach((root) => walk(root));
      this.files = found;
    }
    for (const uri of this.files) {
      texts.set(uri, '');
      aliases.set(physical(pathOf(uri)), uri);
    }
    for (const [uri, text] of buffers)
      if (uri.startsWith('file:') && isYamlFile(uri)) texts.set(uri, text);
    const signature = [...texts.keys()]
      .filter((uri) => /\/ttr\.yaml$/.test(uri))
      .sort()
      .map((uri) => {
        try {
          return uri + '\0' + read(pathOf(uri));
        } catch {
          return uri + '\0<invalid>';
        }
      })
      .join('\0');
    if (signature !== this.markerSignature) {
      this.markers.clear();
      this.markerSignature = signature;
    }
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
      if (/\/ttr\.yaml$/.test(file)) continue;
      let marker: string | undefined;
      try {
        marker = this.marker(file, read);
        const boundary = marker
          ? dirname(marker)
          : (this.roots
              .filter((root) => file.startsWith(root + sep))
              .sort((a, b) => b.length - a.length)[0] ?? dirname(file));
        if (this.excluded(file, boundary, read)) continue;
        const text = read(file) ?? '';
        if (!marker && !this.profile && this.language(uri, text, false) === 'yaml') continue;
        const header = this.header(text, file);
        if (header.type === 'canonical') {
          let cached = this.canonical.get(uri);
          if (cached?.text !== text) {
            cached = { text, result: parseYamlDocument(text, uri) };
            this.canonical.set(uri, cached);
          }
          out.set(uri, cached.result);
          continue;
        }
        let treeDeclaration = null;
        if (header.type === 'absent' && marker) {
          const source = read(marker)!;
          let cached = this.declarations.get(marker);
          if (cached?.text !== source) {
            const value = new ForeignData(source, marker).value;
            cached = {
              text: source,
              declaration:
                isRecord(value) && value.profile !== undefined
                  ? DeclarationReader.readTree(source, marker)
                  : null,
            };
            this.declarations.set(marker, cached);
          }
          treeDeclaration = cached.declaration;
        }
        const declaration = header.type === 'declared' ? header.declaration : treeDeclaration;
        if (!declaration && typeof this.profile !== 'string') continue;
        const reference =
          typeof this.profile === 'string' && header.type === 'absent'
            ? this.profile
            : declaration!;
        const chainKey =
          boundary +
          '\0' +
          (typeof reference === 'string'
            ? reference
            : JSON.stringify([
                reference.profile,
                reference.grammarRange.text,
                reference.location.file,
              ]));
        let cachedChain = this.chains.get(chainKey);
        if (
          cachedChain &&
          [...cachedChain.dependencies].some(([path, text]) => read(path) !== text)
        )
          cachedChain = undefined;
        if (!cachedChain) {
          const dependencies = new Map<string, string | undefined>();
          const dependencyRead = (path: string) => {
            const text = read(path);
            dependencies.set(path, text);
            return text!;
          };
          const catalog = new Map<string, string>();
          // Identities resolve from the tree catalog, while CLI paths arrive absolute.
          for (const catalogUri of typeof reference === 'string' && !/\.ya?ml$/.test(reference)
            ? texts.keys()
            : []) {
            const path = pathOf(catalogUri);
            if (dirname(path) !== join(boundary, 'profiles')) continue;
            const profile = ProfileReader.read(dependencyRead(path), path);
            catalog.set(`${profile.name}/${profile.major}`, path);
          }
          const resolver = new ProfileResolver(boundary, catalog, () => null, dependencyRead);
          const chain =
            typeof reference === 'string'
              ? resolver.resolve(reference, reference)
              : resolver.resolve(reference);
          cachedChain = { chain, dependencies };
          this.chains.set(chainKey, cachedChain);
        }
        const chain = cachedChain.chain;
        // Whole-file ignore ops exclude content before it can contribute diagnostics.
        const rootPath = relative(boundary, file).split(sep).join('/');
        if (
          chain.some((p) =>
            p.operations.some(
              ({ value: op }) =>
                op.op === 'ignore' &&
                op.in === undefined &&
                op.at === undefined &&
                Array.isArray(op.files) &&
                new FileSelector(op.files as string[]).matches(rootPath)
            )
          )
        )
          continue;
        chain.forEach((profile) => {
          if (!profile.file.startsWith('<')) excluded.add(uriOf(profile.file));
        });
        const key = boundary + '\0' + chain.map((p) => p.file).join('\0');
        let group = groups.get(key);
        if (!group) {
          group = { root: boundary, chain, trees: [] };
          groups.set(key, group);
        }
        let cachedTree = this.trees.get(uri);
        if (cachedTree?.text !== text || cachedTree.rootPath !== rootPath) {
          cachedTree = { text, rootPath, tree: ForeignTree.read(text, rootPath, uri) };
          this.trees.set(uri, cachedTree);
        }
        group.trees.push(cachedTree.tree);
        out.set(uri, empty(uri));
      } catch (error) {
        if (!(error instanceof ProfileException)) throw error;
        if (!marker && !this.profile && error.detail === 'Malformed UTF-8') continue;
        failure(error, uri);
        out.set(uri, out.get(uri) ?? empty(uri));
      }
    }
    for (const uri of excluded) out.delete(uri);
    const dependencyText = (file: string): string | undefined => {
      try {
        return read(file);
      } catch (error) {
        if (!(error instanceof ProfileException)) throw error;
        // The engine owns this refusal; hashing a dependency must not throw outside its error path.
        return `<invalid:${error.code}:${error.detail}>`;
      }
    };
    this.groupDependencies.clear();
    for (const [key, group] of groups) {
      for (const profile of group.chain) {
        if (typeof profile.overlay === 'string')
          this.groupDependencies.add(physicalPath(resolve(dirname(profile.file), profile.overlay)));
      }
      const signature = JSON.stringify(
        [
          dependencyText(join(group.root, 'ttr.yaml')),
          dependencyText(join(group.root, 'renames.yaml')),
          group.chain.map((p) => [
            p.file,
            p.operations,
            p.overlay,
            typeof p.overlay === 'string'
              ? dependencyText(resolve(dirname(p.file), p.overlay))
              : null,
          ]),
          group.trees.map((t) => [t.label, this.trees.get(t.label)?.text]),
          [...this.chains.values()]
            .filter((c) => c.chain === group.chain)
            .flatMap((c) => [...c.dependencies]),
        ],
        (_key, value) => (typeof value === 'bigint' ? value.toString() : value)
      );
      const cached = this.applied.get(key);
      if (cached?.signature === signature) {
        for (const [uri, result] of cached.results) out.set(uri, result);
        continue;
      }
      const before = new Map(out);
      let pending = group.trees.filter((tree) => !excluded.has(tree.label));
      while (pending.length) {
        try {
          const applied = ProfileEngine.apply(pending, group.chain, group.root, true);
          const targets = new Set(applied.sources.map((source) => source.qname));
          for (const document of applied.documents) {
            const uri = uriOf(join(group.root, document.file!));
            const previous = out.get(uri) ?? empty(uri);
            const ast = authoredAst(document.ast, pending, targets);
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
              code: (warning.origin === 'each'
                ? 'other'
                : warning.origin === 'unknown'
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
      const results = new Map(
        [...out].filter(
          ([uri, result]) =>
            group.trees.some((t) => t.label === uri && !excluded.has(uri)) ||
            before.get(uri) !== result
        )
      );
      this.applied.set(key, { signature, results });
    }
    this.lastResults = new Set(out.keys());
    return new Map([...out].map(([uri, result]) => [uri, { ...result, text: safeRead(uri) }]));
  }
}

function physicalPath(file: string): string {
  try {
    return realpathSync(file);
  } catch {
    return resolve(file);
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
function authoredAst(ast: Document, trees: ForeignTree[], targets: ReadonlySet<string>): Document {
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
  // Default links retain qualified strings in canonical output. Reference-bearing
  // endpoint slots need an editor projection, without changing the engine's bytes.
  const endpoint = (value: PropertyValue): PropertyValue => {
    if (value.kind === 'string' && targets.has(value.value))
      return {
        kind: 'id',
        path: value.value,
        parts: value.value.split('.'),
        source: value.source,
      };
    if (value.kind === 'list') return { ...value, items: value.items.map(endpoint) };
    return value;
  };
  const copy = (value: unknown, field = ''): unknown => {
    if (!value || typeof value !== 'object') return value;
    if (Array.isArray(value)) return value.map((child) => copy(child, field));
    if (ArrayBuffer.isView(value)) return value;
    if ('offsetStart' in value && 'file' in value)
      return field === 'source' ? (spans.get(key(value as SourceLocation)) ?? value) : value;
    const result = Object.fromEntries(Object.entries(value).map(([k, v]) => [k, copy(v, k)]));
    if (result.kind === 'relation' || result.kind === 'fk') {
      for (const slot of ['from', 'to'])
        if (result[slot]) result[slot] = endpoint(result[slot] as PropertyValue);
    }
    return result;
  };
  return copy(ast) as Document;
}
