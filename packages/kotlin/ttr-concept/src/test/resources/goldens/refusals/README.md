# Refusals — refusal over guess

Source: plan.md CN-P3 S3.7 and contracts §7. Each case pins the exact slug list (`expected.diagnostics.json`, or a
rendering's `diagnostics`).

| case | slug | world |
|---|---|---|
| `ambiguous-statement` | `cn/ambiguous-statement` (a measure with candidates) | hartland |
| `ambiguous-assertion` | `cn/ambiguous-assertion` (an event with candidates) | clinic |
| `missing-time` | `cn/missing-time` | sales |
| `ask-set` | `cn/unsupported-ask` — **new slug** (`ASK_SET` is reserved, rejected in v1; §7 names no slug — added at S3.2) | sales |
| `not-analytically-reachable` | `cn/not-analytically-reachable` (an er-only quality as a measure) | hartland |
| `definition-unreachable-er` | `cn/definition-unreachable(er)` (an md-grounded phase, no hop — the recipe consumer's `cn/not-recipe-reachable` cause) | sales |
| `not-assertable` | `cn/not-assertable` (an md-grounded event with no ER landing) | sales |
| `unknown-participant-label` | `cn/unknown-participant-label` — **new slug** (contracts §3 checks the labels, §7 names no slug — added at S3.5) | clinic |
| `definition-cycle` | `cn/definition-cycle` (`LoopA` ⇄ `LoopB`) | hartland |
| `retired-code.pending` | `cn/retired-code` — no vocabulary versions until CN-P4 S4.2 | — |
| `unit-mismatch.pending` | `cn/unit-mismatch` — nothing in the model carries a unit to compare with (CN-P10) | — |

Pending convention, case format and oracles: [`../README.md`](../README.md).
