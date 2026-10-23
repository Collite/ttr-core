// SPDX-License-Identifier: Apache-2.0
import * as vscode from 'vscode';
import * as path from 'path';
import { LanguageClient, TransportKind, NodeModule } from 'vscode-languageclient';

// Resolve the LSP server entry point.
//
// Production (installed .vsix): the extension is self-contained — `just vscode`
// bundles a fully-inlined ESM server (all @tatrman/* + antlr4ng +
// vscode-languageserver folded in) to `dist/server/server-stdio.mjs`. There is
// no node_modules tree to resolve against, so we point Node straight at it.
//
// Development (F5) / Test: resolve the LSP from its workspace location so the
// live build is used. We must NOT prefer the bundled .mjs here even if it
// happens to exist on disk (a leftover from a previous `just vscode`) — it would
// be stale relative to the workspace and the preLaunchTask's rebuild, silently
// masking server changes.
function resolveServerPath(context: vscode.ExtensionContext): string {
  if (context.extensionMode === vscode.ExtensionMode.Production) {
    return context.asAbsolutePath(path.join('dist', 'server', 'server-stdio.mjs'));
  }
  return require.resolve('@tatrman/lsp/server-stdio');
}

export function activate(context: vscode.ExtensionContext) {
  const serverPath = resolveServerPath(context);

  const serverModule: NodeModule = {
    module: serverPath,
    transport: TransportKind.stdio,
  };

  const serverOptions = {
    run: serverModule,
    debug: serverModule,
  };

  const client = new LanguageClient('ttr', 'TTR Language Server', serverOptions, {
    documentSelector: [
      { scheme: 'file', language: 'ttr' },
      { scheme: 'file', language: 'ttrg' },
    ],
    outputChannelName: 'TTR Language Server',
    synchronize: {
      // Notify the server when `.ttrm` files change on disk outside the editor
      // (external edits, git operations, create/delete) so the whole-project
      // symbol table stays current for cross-file reference resolution.
      fileEvents: vscode.workspace.createFileSystemWatcher('**/*.ttrm'),
    },
  });

  context.subscriptions.push(
    vscode.commands.registerCommand('modeler.openInDesigner', () => {
      vscode.window.showInformationMessage('Designer integration will be wired in Phase 3.');
    }),
    // Invoked by the "N references" code lens.
    vscode.commands.registerCommand('modeler.showReferences', (qname?: string) => {
      vscode.window.showInformationMessage(
        `Use "Find All References" (Shift+F12) on ${qname ?? 'the symbol'} to list its references.`,
      );
    }),
    // CN-P2 S2.7 T3 — "TTR: Concept hygiene report": the LSP's editor report (`modeler/getCnHygiene`, cn
    // contracts §7) as a markdown preview. Definition-level findings stay `ttr cn hygiene`'s (CN-D29).
    vscode.commands.registerCommand('modeler.mdHopReport', async () => {
      const { markdown } = await client.sendRequest<{ markdown: string }>('modeler/getMdHop');
      const doc = await vscode.workspace.openTextDocument({ language: 'markdown', content: markdown });
      await vscode.commands.executeCommand('markdown.showPreview', doc.uri);
    }),
    vscode.commands.registerCommand('modeler.cnHygieneReport', async () => {
      const { markdown } = await client.sendRequest<{ markdown: string }>('modeler/getCnHygiene');
      const doc = await vscode.workspace.openTextDocument({ language: 'markdown', content: markdown });
      await vscode.commands.executeCommand('markdown.showPreview', doc.uri);
    }),
    // Invoked by the "N files in package" code lens.
    vscode.commands.registerCommand('modeler.listPackageFiles', async (pkg?: string) => {
      if (!pkg) return;
      const uris = await vscode.workspace.findFiles('**/*.ttrm');
      const inPkg: string[] = [];
      for (const uri of uris) {
        const text = (await vscode.workspace.openTextDocument(uri)).getText();
        if (new RegExp(`(^|\\n)package\\s+${pkg.replace(/[.]/g, '\\.')}(\\s|$)`).test(text)) {
          inPkg.push(vscode.workspace.asRelativePath(uri));
        }
      }
      const pick = await vscode.window.showQuickPick(inPkg.length ? inPkg : ['(no files found)'], {
        title: `Files in package ${pkg}`,
      });
      if (pick && inPkg.includes(pick)) {
        const match = uris.find((u) => vscode.workspace.asRelativePath(u) === pick);
        if (match) void vscode.window.showTextDocument(match);
      }
    }),
  );

  client.start();
}

export function deactivate(): Thenable<void> | undefined {
  return undefined;
}
