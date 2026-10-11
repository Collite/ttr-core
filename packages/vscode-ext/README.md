# ttr-modeler-vsc

VS Code extension for TTR (Tatrman) language support. Thin shim around the shared `@tatrman/lsp` server — all language understanding lives in the LSP; this package just registers languages, ships the TextMate grammar, and wires up the LanguageClient.

## Features

- Language registration for `.ttrm` files (icons + `language-configuration.json`)
- Syntax highlighting via the TextMate grammar generated from `TTR.g4`
- LSP integration: diagnostics, hover, go-to-definition, find-references, workspace symbols, semantic-token highlighting
- Stub command: `modeler.openInDesigner` (Designer integration arrives in v1.x)

## Development

1. Open `packages/vscode-ext` in VS Code.
2. Press F5 to launch the Extension Development Host.
3. Open any `.ttrm` file (e.g. from `samples/v1-metadata/`) to test highlighting and the LSP.

The LSP server is launched at `require.resolve('@tatrman/lsp/server-stdio')` — i.e. the workspace location, so its esbuild bundle can still resolve `@tatrman/parser` and `@tatrman/semantics` via pnpm symlinks.

## Building

```bash
pnpm install
pnpm --filter ttr-modeler-vsc build
```

Build outputs `dist/extension.js`. `@tatrman/lsp` must be built first (`pnpm --filter @tatrman/lsp build`) so its bundled `server-stdio.js` exists at the resolved path; `pnpm -r build` does this in the right order automatically.

## Smoke tests

Boot a real VS Code window via `@vscode/test-electron`, open the `samples/v1-metadata/` workspace, and run five Mocha smoke cases:

| Case | Asserts |
|---|---|
| TC1 — language detection | `er.ttrm` opens with `languageId === 'ttr'` |
| TC2 — clean diagnostics | LSP publishes zero error-severity diagnostics on the known-good sample |
| TC3 — go-to-definition | Cursor on a `to: er.entity.item` reference jumps to the line of `def entity item` |
| TC4 — unresolved reference | Inserting a relation with `to: er.entity.does_not_exist_*` produces a `ttr/unresolved-reference` diagnostic; the in-memory edit is reverted (the source file is never saved) |
| TC5 — workspace symbols | `vscode.executeWorkspaceSymbolProvider('ite')` returns at least one symbol whose name includes `item` |

A Mocha `before` hook gates on the LSP being live (polls `workspace/symbol` until `item` is findable) so test failures point at real regressions, not initialization races.

```bash
# Local (macOS / Linux / Windows; first run downloads ~130 MB of Electron):
pnpm --filter ttr-modeler-vsc test:smoke

# On a Linux CI runner with no display:
xvfb-run -a pnpm --filter ttr-modeler-vsc test:smoke
```

Harness lives in `src/test/`; the runner is `src/test/runTests.ts`, assertions are in `src/test/suite/extension.smoke.test.ts`. CI runs the suite on every PR via the `vscode-smoke` job in [.github/workflows/ci.yml](../../.github/workflows/ci.yml).

## Module format

This package is CommonJS (`"type": "commonjs"`). VS Code's extension host loads `dist/extension.js` via `require()`, so the package and everything it ships must be CJS-compatible. The LSP server bundle that this package launches (`@tatrman/lsp/dist/server-stdio.js`) is ESM and is launched in its own Node process by `vscode-languageclient`, so the format mismatch is fine across the process boundary.
