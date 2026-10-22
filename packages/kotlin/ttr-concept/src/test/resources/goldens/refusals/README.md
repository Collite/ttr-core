# Refusals — refusal over guess

Source: plan.md CN-P3 S3.7 and contracts §7. Each case pins the exact slug list (`expected.diagnostics.json`, or a
rendering's `diagnostics`).

| case | slug | world |
|---|---|---|
| `ambiguous-statement` | `cn/ambiguous-statement` (a measure with candidates) | hartland |
| `ambiguous-assertion` | `cn/ambiguous-assertion` (an event with candidates) | clinic |
| `missing-time` | `cn/missing-time` | sales |
| `ask-set` | `cn/unsupported-ask` — **new slug** (`ASK_SET` is reserved, rejected in v1; added to §7 at S3.2, A-CN-32) | sales |
| `not-analytically-reachable` | `cn/not-analytically-reachable` (an er-only quality as a measure) | hartland |
| `definition-unreachable-er` | `cn/definition-unreachable(er)` (an md-grounded phase, no hop — the recipe consumer's `cn/not-recipe-reachable` cause) | sales |
| `not-assertable` | `cn/not-assertable` (an md-grounded event with no ER landing) | sales |
| `unknown-participant-label` | `cn/unknown-participant-label` — **new slug** (contracts §3 checks the labels; added to §7 at S3.2, A-CN-33) | clinic |
| `unit-mismatch` | `cn/unit-mismatch` — an `Observation` value asserted in `mg/dL`; its grounding declares `unit: "mmol/L"` (⚑CN-P10, ruled 2026-10-22) | clinic |
| `definition-cycle` | `cn/definition-cycle` (`LoopA` ⇄ `LoopB`) | hartland |
| `retired-code.pending` | `cn/retired-code` — no vocabulary versions until CN-P5 S5.3 (the registration endpoint lives beside the candidate store; S4.2 typed the field) | — |

Pending convention, case format and oracles: [`../README.md`](../README.md).
