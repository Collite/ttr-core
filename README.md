# Tatrman

**Tatrman** ("table transformation manager") — the home of the **TTR** language family:

- **TTR-M** — the modeling language: a VS Code extension, a static React graphical designer, and a shared TypeScript LSP server. All consumers share one parser, one semantic engine, and one LSP.
- **TTR-P** — the processing language (in design; see [`docs/features/ttr-p/`](docs/features/ttr-p/)): dataflow programs over TTR-M models, compiled to SQL/Polars and executed across engines. Fragment dialects TTR-SQL / TTR-pandas; NL surface TTR-B.

The runtime side of TTR is consumed by `ai-platform`/`kantheon` (separate repos); this repo is editor/compiler tooling only.

> Forked 2026-07-03 from `Collite/modeler` (full history preserved); modeler is frozen/maintenance-only.

The grammar lives in `packages/grammar/src/TTR.g4`. Sample projects are under `samples/`.

## Part of the Tatrman ecosystem

Tatrman is built on one conviction: **use the LLM for what it is good at —
understanding intent — and make everything between intent and data
deterministic.** Models carry the semantics machine-readably; an agent structures
a question over the *modeled* entities (never over physical tables); everything
after intent — translation, row-level security, execution — is deterministic and
auditable by construction. Governance is applied to the *plan*, not to the answer.

This repository is the **language and toolchain** home of that ecosystem: the TTR
grammar, the shared LSP, the graphical Designer, and the published parser/writer
artifacts. The governed **read spine** that turns a question into an answer lives
in the companion [`tatrman-server`](https://github.com/Collite/ttr-server)
repo.

**Status labels** used across the docs: **live** (running at a production pilot) ·
**extracted** (implemented in the open lineage) · **planned** (designed, not
built) · **parked** (deliberately deferred). A one-command quickstart and the
`tatrman.org` docs site land with **1.0**.

## What ships in v1

- **VS Code extension** — `.ttrm` syntax highlighting, diagnostics, hover, go-to-definition, find-references, workspace symbol search.
- **Graphical Designer** — read-only React + React Flow renderer for `db` and `er` schemas. Display-mode toggle (just-names / with-types / with-constraints), schema toggle (db ↔ er), inspector panel with symbol details and reference navigation, per-graph layout persistence via `.ttrg` files. Deployed via GitHub Pages; `?demo=v1.1-mini` query loads the sample project without an upload.
- **Tatrman LSP** — single TypeScript server, two transports: stdio for VS Code / IntelliJ, Web Worker for the Designer. Custom `modeler/*` methods documented in [packages/lsp/README.md](packages/lsp/README.md).

## What ships in v1.1

- **Package model** — every `.ttrm` file declares `package <qualified-name>` at the top; the directory structure determines the package name.
- **Import system** — cross-package references require explicit `import` statements; same-package references resolve without imports.
- **`.ttrg` graph files** — a `.ttrg` file describes a graph (schema + object list + layout). Multiple `.ttrg` files per project, each scoped to a subset of the model.
- **Migration CLI** — `pnpm exec modeler-migrate <project-root>` converts a v1 project to v1.1: inserts `package` declarations, produces `import` statements, converts `.modeler/layout.ttrl` to per-graph `.ttrg` files.
- **Add / Remove object** — the Designer can add or remove objects from a graph via `modeler/addObjectToGraph` / `modeler/removeObjectFromGraph`.
- **Graph-centric entry** — three ways to open a graph: open an existing `.ttrg`, browse project graphs via `modeler/listGraphs`, or create a new graph via a wizard.

Edit mode (round-tripping graph edits back into `.ttrm` text) and rename propagates into `.ttrg` files in v1.1; `modeler/applyGraphEdit` is a stub returning `{ ok: false }` in v1.

## Architecture

See [docs/features/v1/design/architecture.md](docs/features/v1/design/architecture.md) for the full design and decision log, plus the Designer ↔ LSP control-flow diagram for the deployed (browser) topology.

The v1.1 design (packages, imports, and `.ttrg` graph files) lives under [docs/features/v1-1/](docs/features/v1-1/).

## Status and current work

The version V1 is shipped. The v1.1 release (packages, imports, `.ttrg` graph files, migration CLI) is complete. We are currently developing v1.2, according to the plan [docs/features/v1-1/plan/implementation-plan-v1.1.md](docs/features/v1-1/plan/implementation-plan-v1.1.md) (the v1.2 plan will be added as a new document).


## Developing locally

### Prerequisites

- Node.js 20+
- pnpm 11+ (the repo pins `pnpm@11.1.1` via `packageManager`; Corepack picks it up automatically)

### Setup

```bash
pnpm install
pnpm -r build      # builds all packages
pnpm -r test       # 226 vitest cases across packages + integration
pnpm -r lint
pnpm -r typecheck
```

### Per-package commands

```bash
pnpm --filter @tatrman/designer dev          # Vite dev server on http://localhost:5173
pnpm --filter ttr-modeler-vsc test:smoke # boots VS Code, runs 5 smoke cases
pnpm --filter @tatrman/integration-tests test
```

For the VS Code extension dev cycle, open `packages/vscode-ext` in VS Code and press F5 — the Extension Development Host opens; load any `.ttrm` from `samples/` to exercise syntax highlighting, diagnostics, and navigation.

### Package structure

| Package | Purpose |
|---|---|
| [`@tatrman/grammar`](packages/grammar) | `TTR.g4` grammar and the ANTLR / TextMate generation scripts; no runtime logic |
| [`@tatrman/parser`](packages/parser/README.md) | `parseString` / `parseFile` returning `{ ast, errors }`; recovery strategy emits `ttr/parse-recovery-info` |
| [`@tatrman/semantics`](packages/semantics/README.md) | Symbol table, resolver, validator, reference index — browser-safe core plus a Node-only subpath for disk I/O |
| [`@tatrman/lsp`](packages/lsp/README.md) | LSP server (stdio + browser worker), Phase-3 custom `modeler/*` methods |
| [`ttr-modeler-vsc`](packages/vscode-ext/README.md) | VS Code extension — thin shim, all language logic lives in the LSP |
| [`@tatrman/designer`](packages/designer/README.md) | React + React Flow Designer; deployed via GitHub Pages |

## Documentation

### v1 (shipped)

- [docs/features/v1/design/architecture.md](docs/features/v1/design/architecture.md) — Architecture and design decisions
- [docs/features/v1/design/diagnostics.md](docs/features/v1/design/diagnostics.md) — Diagnostic codes, severities, examples
- [docs/features/v1/design/phase-03-contracts.md](docs/features/v1/design/phase-03-contracts.md) — Phase-3 LSP custom-method contracts
- [docs/features/v1/plan/progress-phase-03.md](docs/features/v1/plan/progress-phase-03.md) — Phase 3 progress log

### v1.1 (planning)

- [docs/features/v1-1/design/v1.1-packages-and-graphs.md](docs/features/v1-1/design/v1.1-packages-and-graphs.md) — Design spec for packages, imports, and `.ttrg` graph files
- [docs/features/v1-1/design/grammar-v1-1-changes.md](docs/features/v1-1/design/grammar-v1-1-changes.md) — Grammar diff coordination doc for ai-platform's Kotlin parser
- [docs/features/v1-1/plan/implementation-plan-v1.1.md](docs/features/v1-1/plan/implementation-plan-v1.1.md) — v1.1 phased plan (sub-phases A–I)

## License, governance & contributing

Tatrman is open source under the **[Apache License 2.0](LICENSE)** (see also
[NOTICE](NOTICE)). "Tatrman" is a trademark of Collite — see the **[Trademark
Policy](TRADEMARKS.md)**.

- **[CONTRIBUTING.md](CONTRIBUTING.md)** — how to contribute (DCO sign-off, edges vs. core).
- **[GOVERNANCE.md](GOVERNANCE.md)** — steward model and the control-room RFC process.
- **[SECURITY.md](SECURITY.md)** — report a vulnerability privately.
- **[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)** — Contributor Covenant 2.1.
