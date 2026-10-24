// SPDX-License-Identifier: Apache-2.0
import { createServerConnection } from './server.js';
import { createConnection, ProposedFeatures } from 'vscode-languageserver/lib/node/main.js';
import { findProjectRoot, loadProject, loadStockVocabularies } from '@tatrman/semantics/node-only';
// Desktop-only: the SQL parsers + ref extraction. E11 keeps these out of the
// browser Worker bundle, so this import lives in the stdio entry — never server.ts.
import { parseSql, extract } from '@tatrman/sql';
import type { SqlDialect } from '@tatrman/parser';

const connection = createConnection(ProposedFeatures.all, process.stdin, process.stdout);

createServerConnection(connection, {
  analyzeSqlBlock(value: string, dialect: SqlDialect) {
    const { tree } = parseSql(value, dialect);
    return tree ? extract(tree, dialect) : undefined;
  },
  async readConfigFile(path: string): Promise<string | undefined> {
    try {
      const fs = await import('node:fs/promises');
      return await fs.readFile(path, 'utf-8');
    } catch {
      return undefined;
    }
  },
  async loadManifest(rootUri: string): Promise<import('@tatrman/semantics').ResolvedManifest> {
    const docPath = rootUri.startsWith('file://') ? rootUri.slice(7) : rootUri;
    const root = await findProjectRoot(docPath, docPath);
    const project = await loadProject(root);
    return project.manifest;
  },
  async scanProjectFiles(root: string) {
    const { pathToFileURL } = await import('node:url');
    const { readFile, readdir } = await import('node:fs/promises');
    const { join } = await import('node:path');
    const project = await loadProject(root);
    const out: Array<{ uri: string; text: string }> = [];
    for (const file of project.ttrFiles) {
      try {
        out.push({ uri: pathToFileURL(file).href, text: await readFile(file, 'utf-8') });
      } catch {
        // skip files that vanish between the walk and the read
      }
    }
    // Keep non-TTR test rosters as source bytes for the synthesizer's orphan guard.
    // Do not follow symlinks out of the checkout; a missing tests directory is normal.
    const rosters = async (dir: string): Promise<void> => {
      let entries;
      try { entries = await readdir(dir, { withFileTypes: true }); } catch { return; }
      for (const entry of entries) {
        const file = join(dir, entry.name);
        if (entry.isDirectory()) await rosters(file);
        else if (entry.isFile() && entry.name.endsWith('.mjs')) {
          try { out.push({ uri: pathToFileURL(file).href, text: await readFile(file, 'utf-8') }); } catch { /* vanished */ }
        }
      }
    };
    await rosters(join(root, 'model', 'tests'));
    return out;
  },
  async readProjectFile(uri: string): Promise<string | undefined> {
    if (!uri.startsWith('file://')) return undefined;
    try {
      const { fileURLToPath } = await import('node:url');
      const { readFile } = await import('node:fs/promises');
      return await readFile(fileURLToPath(uri), 'utf-8');
    } catch {
      return undefined;
    }
  },
  async loadStock() {
    const vocabs = await loadStockVocabularies(['cnc-roles']);
    const out: Array<{ uri: string; ast: import('@tatrman/parser').Document; schemaCode: string; namespace: string }> = [];
    for (const [name, ast] of vocabs) {
      // TODO(pkg-schema-defaults): stock-vocab files always declare `schema cnc`,
      // so this default never fires; presentation-layer, out of scope for the
      // schema-by-kind fix. Should later use defaultSchemaForKind.
      const schemaCode = ast.modelDirective?.modelCode ?? 'cnc';
      const namespace = ast.modelDirective?.schema ?? 'role';
      out.push({ uri: `stock://${name}.ttrm`, ast, schemaCode, namespace });
    }
    return out;
  },
});

connection.listen();
