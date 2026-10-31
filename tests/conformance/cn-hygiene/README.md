# cn hygiene report — the golden (CN-P2 S2.7 T1)

`hartland/hygiene/` is one model project (Hartland names) with every row the hygiene report v2 shows (cn contracts
§7): an er-grounded kind, an md-grounded event that hops (`md2er_cubelet sales_er`), an md-grounded kind with no hop
(`md/er-unmapped`), an ungrounded kind with an open-tier edge, a dangling grounding, two redundant term duplicates, a
phase as the default sense of its bearer's term, a definition cycle, and (grammar 0.20) a rigid subkind and an
event-bounded phase landing through their bearer and a valued-state projection landing through its relator.

| File | What | Read by |
|---|---|---|
| `expected.json` | the report as data | `ttr-concept` `CnHygieneSpec` (equal) · `@tatrman/semantics` `cn-hygiene.test.ts` (equal less `definitions`) |
| `expected.md` | the report as `ttr cn hygiene` prints it | `CnHygieneSpec` |
| `expected-editor.md` | the editor's report — the same up to `## Definitions`, which the editor does not check (CN-D29) | `cn-hygiene.test.ts` · `CnHygieneSpec` (the shared part) |

`definitions` are Kotlin-only: `ResolvedCnModel.definitionDiagnostics()`. Regenerate with
`just cn-hygiene tests/conformance/cn-hygiene --format json > tests/conformance/cn-hygiene/expected.json` (and without
`--format` for `expected.md`) — then read the diff.
