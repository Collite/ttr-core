// SPDX-License-Identifier: Apache-2.0
import { BrowserYamlWorkspace } from '@tatrman/lint/yaml-browser';
import { createServerConnection } from './server.js';
import { createConnection, ProposedFeatures } from 'vscode-languageserver/browser.js';
import { BrowserMessageReader, BrowserMessageWriter } from 'vscode-languageserver-protocol/browser.js';
import type { LayoutFile } from './model-graph.js';

const reader = new BrowserMessageReader(self as unknown as Worker);
const writer = new BrowserMessageWriter(self as unknown as Worker);
const connection = createConnection(ProposedFeatures.all, reader, writer);
let workspace: BrowserYamlWorkspace | undefined;
let workspaceKey: string;
function loaderFor(roots: readonly string[]): BrowserYamlWorkspace {
  const key = JSON.stringify(roots);
  if (!workspace || key !== workspaceKey) {
    workspace = new BrowserYamlWorkspace(roots);
    workspaceKey = key;
  }
  return workspace;
}

createServerConnection(connection, {
  loadYamlDocuments: (buffers, roots) => loaderFor(roots).load(buffers),
  yamlLanguage: (uri, text, roots) => loaderFor(roots).language(uri, text),
  layoutStore: new Map<string, LayoutFile>(),
});
connection.listen();
