// SPDX-License-Identifier: Apache-2.0
import * as assert from 'node:assert';
import * as vscode from 'vscode';
import * as path from 'node:path';

const samplesDir = path.resolve(__dirname, '../../../../../samples/v1-metadata');
const erUri = vscode.Uri.file(path.join(samplesDir, 'er.ttrm'));

async function waitFor(fn: () => boolean | Promise<boolean>, timeout = 10000, interval = 100): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (await fn()) return;
    await new Promise((r) => setTimeout(r, interval));
  }
  throw new Error(`timeout after ${timeout}ms`);
}

async function getExtensionApi(): Promise<vscode.Extension<unknown> | undefined> {
  return vscode.extensions.getExtension('undefined_publisher.modeler') ??
         vscode.extensions.all.find((e) => e.id.endsWith('.modeler') || e.packageJSON?.name === '@tatrman/vscode-ext');
}

describe('Extension smoke test', function () {
  this.timeout(30000);

  let doc: vscode.TextDocument;

  before(async () => {
    // Activate the extension by opening a .ttrm file. Wait for the LSP to
    // produce diagnostics (proves: extension activated, LSP started, doc parsed).
    const ext = await getExtensionApi();
    if (ext && !ext.isActive) await ext.activate();

    doc = await vscode.workspace.openTextDocument(erUri);
    await vscode.window.showTextDocument(doc);

    // Wait until the LSP has at least responded — either with zero errors
    // (clean parse) or with any diagnostic at all. We're really just waiting
    // for the LSP to have processed the open notification.
    await waitFor(async () => {
      // Trigger any LSP query; if it returns (even with no result), the LSP
      // is up. Use getDiagnostics which is local and cheap.
      vscode.languages.getDiagnostics(doc.uri);
      // But also probe workspace/symbol — that's an actual round-trip and
      // proves the LSP has populated its symbol table.
      const symbols = await vscode.commands.executeCommand<vscode.SymbolInformation[]>(
        'vscode.executeWorkspaceSymbolProvider',
        'item',
      );
      return Array.isArray(symbols) && symbols.length > 0;
    }, 15000);
  });

  it('TC1 — language detection', () => {
    assert.strictEqual(doc.languageId, 'ttr');
  });

  it('TC2 — clean diagnostics on known-good sample', () => {
    const diagnostics = vscode.languages.getDiagnostics(doc.uri);
    const errors = diagnostics.filter((d) => d.severity === vscode.DiagnosticSeverity.Error);
    assert.strictEqual(errors.length, 0, `expected no errors, got ${errors.length}: ${errors.map((d) => d.message).join('; ')}`);
  });

  it('TC3 — go-to-definition on a reference jumps to its def line', async () => {
    // Find a *reference* to `er.entity.item` (not the def itself).
    // Relations near the bottom of er.ttrm contain `to: er.entity.item, ...`.
    // The cursor needs to land INSIDE the identifier `item` of that ref.
    const content = doc.getText();
    const m = content.match(/to:\s+er\.entity\.(item),/);
    if (!m || m.index === undefined) {
      throw new Error('no `to: er.entity.item` reference in er.ttrm');
    }
    // Position past "to: er.entity." onto the "i" of "item".
    const refIdx = m.index + m[0].lastIndexOf(m[1]);
    const refPos = doc.positionAt(refIdx + 1); // inside the identifier
    const editor = await vscode.window.showTextDocument(doc);
    editor.selection = new vscode.Selection(refPos, refPos);

    const before = refPos.line;
    // Find the def line for `def entity item` so we can assert the jump landed there.
    const defMatch = content.match(/def entity item\b/);
    if (!defMatch || defMatch.index === undefined) throw new Error('no `def entity item` in er.ttrm');
    const defLine = doc.positionAt(defMatch.index).line;
    assert.notStrictEqual(defLine, before, 'sanity: def line and ref line should differ');

    await vscode.commands.executeCommand('editor.action.revealDefinition');
    // Give VS Code a moment to apply the cursor jump.
    await new Promise((r) => setTimeout(r, 500));

    const after = vscode.window.activeTextEditor!.selection.active.line;
    assert.strictEqual(after, defLine, `cursor should land on def line ${defLine}, got ${after} (was ${before})`);
  });

  it('TC4 — inserting an unresolved reference produces ttr/unresolved-reference', async () => {
    const editor = await vscode.window.showTextDocument(doc);
    const original = doc.getText();
    // Insert a relation that references a nonexistent entity at end of file.
    // `def relation ... to: er.entity.<nonexistent>` triggers the unresolved-reference
    // path (the dotted ref doesn't resolve to any symbol).
    const endPos = doc.lineAt(doc.lineCount - 1).range.end;
    const inserted = `\ndef relation smoke_unresolved_xyz { from: er.entity.item, to: er.entity.does_not_exist_smoke_xyz, cardinality: { from: "1", to: "1" } }\n`;
    const edit = new vscode.WorkspaceEdit();
    edit.insert(doc.uri, endPos, inserted);
    await vscode.workspace.applyEdit(edit);

    try {
      await waitFor(() => {
        const diagnostics = vscode.languages.getDiagnostics(doc.uri);
        return diagnostics.some((d) => d.code === 'ttr/unresolved-reference');
      }, 10000);
    } finally {
      // Restore the doc to its original text (in-memory only — never save).
      const fullRange = new vscode.Range(
        new vscode.Position(0, 0),
        doc.lineAt(doc.lineCount - 1).range.end,
      );
      const restore = new vscode.WorkspaceEdit();
      restore.replace(doc.uri, fullRange, original);
      await vscode.workspace.applyEdit(restore);
      // Sanity: the in-memory text matches the on-disk text.
      assert.strictEqual(doc.getText(), original, 'doc text restored after TC4');
      // Hide editor to avoid leakage between tests.
      void editor;
    }
  });

  it('TC5 — workspaceSymbol query "ite" returns at least one item symbol', async () => {
    // The before-hook already proved this works (it waits for `item` to be
    // findable). Re-running here as the formal TC5 assertion.
    const symbols = await vscode.commands.executeCommand<vscode.SymbolInformation[]>(
      'vscode.executeWorkspaceSymbolProvider',
      'ite',
    );
    assert.ok(symbols && symbols.length > 0, `expected ≥1 symbol for "ite", got ${symbols?.length ?? 0}`);
    const hasItem = symbols.some((s) => s.name.includes('item'));
    assert.ok(hasItem, `expected an item symbol; got: ${symbols.map((s) => s.name).slice(0, 10).join(', ')}`);
  });

  it('TC6 — .ttrg language detection and LSP diagnostics', async () => {
    // Fixture lives at the package root (committed); it is NOT copied into dist/
    // by `tsc` (rootDir is src/). From dist/test/suite that root is three up.
    const ttrgUri = vscode.Uri.file(path.resolve(__dirname, '../../../test-fixtures/smoke_clean.ttrg'));
    const ttrgDoc = await vscode.workspace.openTextDocument(ttrgUri);
    await vscode.window.showTextDocument(ttrgDoc);

    assert.strictEqual(ttrgDoc.languageId, 'ttrg', '.ttrg file should have languageId ttrg');

    await waitFor(() => {
      const diagnostics = vscode.languages.getDiagnostics(ttrgUri);
      return diagnostics.length > 0;
    }, 10000);

    const diagnostics = vscode.languages.getDiagnostics(ttrgUri);
    const errors = diagnostics.filter((d) => d.severity === vscode.DiagnosticSeverity.Error);
    assert.ok(errors.length > 0, 'smoke_clean.ttrg has objects:[] so it should produce at least one error');
  });
});
