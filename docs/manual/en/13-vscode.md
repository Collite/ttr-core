# The VS Code experience

This is the payoff. Everything in the previous chapters — packages, references, bindings, roles — is plain text, and plain text on its own is no better than the YAML it replaces. What makes TTR worth the structure is that an editor *understands* it. This page walks through what the VS Code extension gives you, and frames each feature against the question: *could the old YAML do this?* The answer is almost always no.

## Getting started

Install the TTR extension and open a folder that contains a `modeler.toml`. The extension recognizes `.ttrm` and `.ttrg` files, starts the language server, and indexes the whole project. From then on, editing is interactive: the features below are live as you type, across every file in the project at once.

(To run the extension from source during development, open `packages/vscode-ext` in VS Code and press F5 to launch an Extension Development Host with the extension loaded.)

## Live linting

As you type, the checker validates the whole model and underlines problems — no build step, no save required. The diagnostics are the ones referenced throughout this manual:

- **Unresolved references.** Point a foreign key at `db.dbo.CATEGORY.CATEGOR_ID` (typo) and the path is underlined immediately. The YAML `join: "… = CATEGORY.CATEGOR_ID"` string sat there silently until something broke at runtime.
- **Broken bindings.** Rename a column and every `er2db_attribute` that targeted it lights up.
- **Import problems.** Unused imports, duplicates, references to packages you forgot to import, ambiguous names — each is flagged with the code from [Packages and imports](10-packages-and-imports.md).
- **Structural mistakes.** A `graph` block in a `.ttrm` file, a definition in a `.ttrg`, a declared `package` that does not match the folder.

Each diagnostic carries a code (like `unresolved-reference` or `unimported-reference`) and a message, shown inline and collected in the Problems panel. The full catalog is in the [Reference](14-reference.md).

> This single feature is the main reason to move off YAML. A model is a web of cross-references, and TTR checks every strand continuously. The legacy format checked none of them.

## Syntax highlighting

`.ttrm` files are colorized two ways. A TextMate grammar gives instant token coloring — keywords (`def`, `model`, `package`), kinds (`table`, `entity`), strings, numbers. On top of that, the language server adds **semantic** highlighting that colors tokens by what they actually resolve to, so an entity reference looks different from an attribute reference even when both are dotted paths. The result distinguishes meaning, not just lexical shape.

## The symbol outline and breadcrumbs

Every definition is a symbol. The Outline view shows a navigable tree of a file — tables with their columns, entities with their attributes — and the breadcrumb bar at the top of the editor lets you jump within it. Across the project, **Open Symbol by Name** (`Ctrl/Cmd-T`) finds any entity, table, attribute, or relation by name, wherever it lives. A YAML file gave you text search; TTR gives you a real symbol index.

## Go to definition and find references

Put the cursor on any reference and **Go to Definition** jumps to the thing it names — from `er.entity.order.customer_id` in a `join` to the attribute's declaration, from an `er2db_attribute`'s `target` to the column, across files and across packages. **Find All References** does the reverse: select a column and see every foreign key, binding, and index that uses it.

This is the difference that compounds. Understanding a YAML model meant grepping for strings and hoping the names were spelled consistently. In TTR the connections are real edges the editor traverses for you.

## Hover

Hover over any reference to see the target's kind and its `description`. This is why writing good `description` properties pays off — they become inline documentation everywhere the object is used, not just where it is defined.

## Autocomplete

When you start a reference, the editor offers completions drawn from the project's symbols: the entities you can point a relation at, the columns available for a foreign key, the kinds valid after `def`. You spend less time remembering exact names and less time mistyping them — and the suggestions respect scope and imports.

## Safe rename

Rename a symbol (`F2`) and the editor updates the definition **and every reference to it**, across all files. Rename an entity and its bindings, relations, and graph entries follow. Rename a package — with the cursor on the `package` line — and the move is propagated project-wide. Because the tooling knows what points where, the rename is precise rather than a risky find-and-replace. Conflicting renames (a name already in use) are rejected with an explanatory error instead of quietly producing a broken model.

## Formatting and quick fixes

The extension formats `.ttrm` files on request, normalizing indentation and spacing so diffs stay about meaning rather than whitespace. Where a diagnostic has an obvious remedy, a **quick fix** (the lightbulb) offers it — for example removing an unused import. There are also refactoring actions, such as extracting an inline definition into a standalone one.

## The graphical view

The extension and the language server behind it are open tooling, and a [graph](11-graphs.md) — the objects it shows and their saved positions — is plain text you can review. The graphical view of a model is the Studio, a Collite product; its read-only tier, Studio Free, is free (see `TRADEMARKS.md` in the ttr-core repository). The Studio runs the same language server, so the picture and the text never disagree — they are two views of one model.

## Why this is the argument for TTR

Take the features together — continuous checking, real navigation, safe renames, hover docs, completion. Each one exists because TTR's structure lets a tool *know what your text means*. The legacy YAML carried the same facts but none of the meaning, so the editor could only ever treat it as text. Moving to TTR is, in practice, moving from "a document about your model" to "a model your tools understand."

For exhaustive details on every kind, type, and diagnostic, see the [Reference](14-reference.md).
