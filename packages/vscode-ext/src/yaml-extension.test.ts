// SPDX-License-Identifier: Apache-2.0
import { it, expect, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import * as path from 'node:path';
import ts from 'typescript';
import { YamlLanguageSelector } from './yaml-language';

it('extension events preserve manual languages, never select on keystrokes, and reconsider marker changes', async () => {
  type Doc = {
    uri: { scheme: string; fsPath: string; toString(): string };
    languageId: string;
    isClosed: boolean;
    getText(): string;
  };
  const doc: Doc = {
    uri: { scheme: 'file', fsPath: '/work/e.yaml', toString: () => 'file:///work/e.yaml' },
    languageId: 'yaml',
    isClosed: false,
    getText: () => 'ttr: "0.22"',
  };
  let onOpen: (doc: Doc) => void = () => {};
  const watchers: { change?: (uri: Doc['uri']) => void }[] = [];
  let admitted: 'yaml' | 'ttr-yaml' = 'ttr-yaml';
  const ask = vi.fn(async () => admitted);
  const setLanguage = vi.fn(async (d: Doc, language: string) => {
    d.languageId = language;
    onOpen(d);
    return d;
  });
  const onChange = vi.fn();
  const vscode = {
    ExtensionMode: { Production: 1 },
    workspace: {
      textDocuments: [doc],
      getWorkspaceFolder: () => ({ uri: { fsPath: '/work' } }),
      createFileSystemWatcher: () => {
        const watcher: { change?: (uri: Doc['uri']) => void } = {};
        watchers.push(watcher);
        return {
          onDidCreate: () => ({}),
          onDidDelete: () => ({}),
          onDidChange: (callback: (uri: Doc['uri']) => void) => {
            watcher.change = callback;
            return {};
          },
        };
      },
      onDidOpenTextDocument: (callback: typeof onOpen) => {
        onOpen = callback;
        return {};
      },
      onDidChangeTextDocument: onChange,
    },
    languages: { setTextDocumentLanguage: setLanguage },
    commands: { registerCommand: () => ({}) },
  };
  const client = {
    LanguageClient: class {
      start() {
        return Promise.resolve();
      }
      sendRequest = ask;
    },
    TransportKind: { stdio: 0 },
  };
  const source = readFileSync(path.join(__dirname, 'extension.ts'), 'utf8');
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports: { activate?: (context: unknown) => void } = {};
  new Function('require', 'exports', code)((name: string) => {
    if (name === 'vscode') return vscode;
    if (name === 'vscode-languageclient') return client;
    if (name === './yaml-language')
      return { YamlLanguageSelector, isTtrYaml: async () => admitted === 'ttr-yaml' };
    if (name === 'path') return path;
    throw new Error(name);
  }, exports);
  exports.activate!({ extensionMode: 1, asAbsolutePath: (p: string) => p, subscriptions: [] });
  const settle = async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  };
  await settle();
  expect(doc.languageId).toBe('ttr-yaml');
  expect(setLanguage).toHaveBeenCalledTimes(1);
  expect(onChange).not.toHaveBeenCalled();
  admitted = 'yaml';
  const marker = {
    scheme: 'file',
    fsPath: '/work/ttr.yaml',
    toString: () => 'file:///work/ttr.yaml',
  };
  watchers.forEach((w) => w.change?.(marker));
  await settle();
  expect(doc.languageId).toBe('yaml');
  doc.languageId = 'ttr-yaml';
  onOpen(doc);
  await settle();
  watchers.forEach((w) => w.change?.(marker));
  await settle();
  expect(doc.languageId).toBe('ttr-yaml');
  expect(ask).toHaveBeenCalledTimes(2);
  const packageJson = JSON.parse(readFileSync(path.join(__dirname, '../package.json'), 'utf8'));
  expect(packageJson.activationEvents).toContain('workspaceContains:**/ttr.yaml');
  expect(packageJson.activationEvents).not.toContain('onLanguage:yaml');
});
