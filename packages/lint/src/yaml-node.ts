// SPDX-License-Identifier: Apache-2.0
/** Desktop adapter. Kept off the browser-safe lint entry point. */
import { existsSync, readFileSync, statSync } from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { ProfileException } from '@tatrman/profile';
import { diskProfileFiles } from '@tatrman/profile/node-only';
import { YamlWorkspaceLoader as WorkspaceLoader } from './yaml.js';

export { isYamlFile } from './yaml.js';

class DiskBuffers {
  private readonly disk = new Map<string, { stamp: string; text: string }>();
  read(file: string): string | undefined {
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

  invalidate(file: string): void {
    this.disk.delete(file);
  }
}

export class YamlWorkspaceLoader extends WorkspaceLoader {
  constructor(roots: string[], profile?: string | boolean) {
    const disk = new DiskBuffers();
    super(
      roots,
      {
        files: diskProfileFiles,
        pathOf: (uri) => (uri.startsWith('file:') ? fileURLToPath(uri) : path.resolve(uri)),
        fileUri: (file) => (file.startsWith('file:') ? file : pathToFileURL(file).href),
        read: (file) => disk.read(file),
        invalidate: (file) => disk.invalidate(file),
      },
      profile
    );
  }
}
