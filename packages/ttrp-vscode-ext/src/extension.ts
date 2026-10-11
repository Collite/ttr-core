// SPDX-License-Identifier: Apache-2.0
import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {
  LanguageClient,
  LanguageClientOptions,
  Executable,
  ServerOptions,
} from 'vscode-languageclient/node';
import { registerAssistCommands } from './assist/command';

// Thin shim (architecture §6 / CLAUDE.md): language registration, client lifecycle,
// and command → LSP-request forwarding ONLY. Anything that understands TTR-P lives in
// the Kotlin server (packages/kotlin/ttrp-lsp). See README §Why-a-second-extension.

let client: LanguageClient | undefined;

/**
 * Resolve the JVM launcher command for the Kotlin LSP. Order (README §Running):
 *  1. `ttrp.server.path` setting (a launcher script, or a `.jar` → `java -jar <path>`);
 *  2. dev default: the `installDist` launcher under the monorepo;
 *  3. packaged `.vsix`: the server dist bundled under `dist/server/`.
 * Missing everything → one actionable error naming the gradle command.
 */
function resolveServerExecutable(context: vscode.ExtensionContext): Executable {
  const configured = vscode.workspace.getConfiguration('ttrp').get<string>('server.path')?.trim();
  if (configured) {
    if (configured.endsWith('.jar')) {
      return { command: 'java', args: ['-jar', configured] };
    }
    return { command: configured, args: [] };
  }

  const launcherName = process.platform === 'win32' ? 'ttrp-lsp.bat' : 'ttrp-lsp';

  // Packaged .vsix: server dist copied under dist/server/ at package time.
  const bundled = context.asAbsolutePath(path.join('dist', 'server', 'bin', launcherName));
  if (fs.existsSync(bundled)) {
    return { command: bundled, args: [] };
  }

  // Dev default: installDist launcher, resolved relative to the monorepo root.
  const monorepoRoot = path.resolve(context.extensionPath, '..', '..');
  const dev = path.join(
    monorepoRoot,
    'packages',
    'kotlin',
    'ttrp-lsp',
    'build',
    'install',
    'ttrp-lsp',
    'bin',
    launcherName,
  );
  if (fs.existsSync(dev)) {
    return { command: dev, args: [] };
  }

  throw new Error(
    'TTR-P language server not found. Build it with:\n' +
      '  ./gradlew :packages:kotlin:ttrp-lsp:installDist\n' +
      'or set `ttrp.server.path` to a launcher script or a .jar.',
  );
}

export function activate(context: vscode.ExtensionContext): void {
  let executable: Executable;
  try {
    executable = resolveServerExecutable(context);
  } catch (err) {
    void vscode.window.showErrorMessage((err as Error).message);
    return;
  }

  const serverOptions: ServerOptions = { run: executable, debug: executable };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: 'file', language: 'ttrp' },
      { scheme: 'file', language: 'ttr-sql' },
      { scheme: 'file', language: 'ttr-pandas' },
      { scheme: 'file', language: 'ttrb' },
      { scheme: 'file', language: 'ttrb-cs' },
    ],
    outputChannelName: 'TTR-P Language Server',
    synchronize: {
      // World + `[ttrp]` manifest changes must reach the server; `.ttrl` stays out until Stage 5.2.
      // `.ttrb` / `.ttrb-cs` also back `container … from "<file>"` bodies — a save re-checks the programs.
      fileEvents: vscode.workspace.createFileSystemWatcher('**/*.{ttrp,ttr.sql,ttr.py,ttrb,ttrb-cs,ttrm,toml}'),
    },
  };

  client = new LanguageClient('ttrp', 'TTR-P Language Server', serverOptions, clientOptions);
  void client.start();

  context.subscriptions.push(
    vscode.commands.registerCommand('ttrp.build', () => forwardBuild()),
    vscode.commands.registerCommand('ttrp.run', () => forwardRun()),
    vscode.commands.registerCommand('ttrp.explain', () => forwardExplain()),
  );

  // The reference assist host (T7.2.6): generate → validate → repair, model at the HOST only.
  registerAssistCommands(context, () => client);
}

export function deactivate(): Thenable<void> | undefined {
  return client?.stop();
}

/** The active editor's {uri, version} — the payload every command forwards. */
function activeDoc(): { uri: string; version: number } | undefined {
  const editor = vscode.window.activeTextEditor;
  if (!editor) {
    void vscode.window.showWarningMessage('TTR-P: open a .ttrp file first.');
    return undefined;
  }
  return { uri: editor.document.uri.toString(), version: editor.document.version };
}

/** Re-read {uri, version} and replay once on a ContentModified (-32801) — contracts §4 client discipline. */
async function sendWithReplay<T>(method: string): Promise<T | undefined> {
  if (!client) return undefined;
  const doc = activeDoc();
  if (!doc) return undefined;
  try {
    return await client.sendRequest<T>(method, doc);
  } catch (err) {
    const code = (err as { code?: number }).code;
    if (code === -32801) {
      const fresh = activeDoc();
      if (fresh) return client.sendRequest<T>(method, fresh);
    }
    throw err;
  }
}

async function forwardBuild(): Promise<void> {
  const result = await sendWithReplay<{ bundlePath: string }>('ttrp/transpile');
  if (result?.bundlePath) {
    const pick = await vscode.window.showInformationMessage(
      `TTR-P: bundle built at ${result.bundlePath}`,
      'Reveal',
    );
    if (pick === 'Reveal') void vscode.commands.executeCommand('revealFileInOS', vscode.Uri.file(result.bundlePath));
  }
}

async function forwardRun(): Promise<void> {
  await vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: 'TTR-P: running…' },
    async () => {
      const result = await sendWithReplay<{ exitCode: number; out: string[] }>('ttrp/run');
      if (!result) return;
      if (result.exitCode === 0) {
        void vscode.window.showInformationMessage(`TTR-P: run succeeded (${result.out.length} output(s)).`);
      } else if (result.exitCode === 2) {
        void vscode.window.showErrorMessage('TTR-P: pre-flight failed — check TTR_CONN_* environment variables.');
      } else {
        void vscode.window.showErrorMessage(`TTR-P: run failed (exit ${result.exitCode}) — see the per-island log.`);
      }
    },
  );
}

async function forwardExplain(): Promise<void> {
  const result = await sendWithReplay<{ text: string }>('ttrp/explain');
  if (result?.text) {
    const doc = await vscode.workspace.openTextDocument({ content: result.text, language: 'text' });
    void vscode.window.showTextDocument(doc, { preview: true });
  }
}
