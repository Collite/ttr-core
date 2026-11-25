// SPDX-License-Identifier: Apache-2.0
import * as path from 'pathe';
import type { ProfileFiles, WorkspaceEntry } from '@tatrman/profile';
import { YamlWorkspaceLoader } from './yaml.js';

function virtualPath(uri: string): string | undefined {
  try {
    const url = new URL(uri);
    if (
      url.protocol !== 'file:' ||
      url.host ||
      url.search ||
      url.hash ||
      /%(?:2f|5c)/i.test(url.pathname)
    )
      return undefined;
    return path.resolve('/', decodeURIComponent(url.pathname));
  } catch {
    return undefined;
  }
}

class WorkspaceBuffers {
  private texts = new Map<string, string>();
  private directories = new Map<string, Set<string>>();

  update(buffers: ReadonlyMap<string, string>): boolean {
    const next = new Map<string, string>();
    for (const [uri, text] of buffers) {
      const file = virtualPath(uri);
      if (file !== undefined) next.set(file, text);
    }
    const changed =
      next.size !== this.texts.size || [...next.keys()].some((file) => !this.texts.has(file));
    this.texts = next;
    if (changed) {
      this.directories = new Map();
      for (const file of next.keys()) {
        let child = file;
        let directory = path.dirname(child);
        while (child !== directory) {
          let entries = this.directories.get(directory);
          if (!entries) this.directories.set(directory, (entries = new Set()));
          if (entries.has(child)) break;
          entries.add(child);
          child = directory;
          directory = path.dirname(child);
        }
      }
    }
    return changed;
  }

  read = (file: string): string | undefined => this.texts.get(file);

  readonly files: ProfileFiles = {
    path,
    realpath: (file) => {
      if (this.texts.has(file) || this.directories.has(file)) return file;
      throw new Error(`No workspace path: ${file}`);
    },
    isFile: (file) => this.texts.has(file),
    exists: (file) => this.texts.has(file) || this.directories.has(file),
    entries: (directory): WorkspaceEntry[] =>
      [...(this.directories.get(directory) ?? [])].map((file) => ({
        name: path.basename(file),
        isDirectory: () => this.directories.has(file),
        isFile: () => this.texts.has(file),
      })),
    read: (file) => {
      const text = this.read(file);
      if (text === undefined) throw new Error(`No workspace file: ${file}`);
      return text;
    },
  };
}

/** Only the client's open buffers form this worker's virtual workspace. */
export class BrowserYamlWorkspace {
  private readonly buffers = new WorkspaceBuffers();
  private readonly loader: YamlWorkspaceLoader;

  constructor(roots: readonly string[]) {
    this.loader = new YamlWorkspaceLoader(
      roots.filter((uri) => virtualPath(uri) !== undefined),
      {
        files: this.buffers.files,
        pathOf: (uri) => virtualPath(uri)!,
        fileUri: (file) => `file://${file.split('/').map(encodeURIComponent).join('/')}`,
        read: this.buffers.read,
      }
    );
  }

  load(buffers: ReadonlyMap<string, string>) {
    const local = new Map([...buffers].filter(([uri]) => virtualPath(uri) !== undefined));
    if (this.buffers.update(local)) this.loader.invalidate();
    return this.loader.load(local);
  }

  dependsOnBuffer(uri: string): boolean {
    const file = virtualPath(uri);
    return file !== undefined && this.loader.dependsOnPath(file);
  }

  language(uri: string, text: string): 'ttr-yaml' | 'yaml' {
    return virtualPath(uri) === undefined ? 'yaml' : this.loader.language(uri, text, false);
  }
}
