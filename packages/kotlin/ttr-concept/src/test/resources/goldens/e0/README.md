# E-0 — one question, both languages, one statement

Source: `design/04-statement-options.md` §E-0 (A-H-1), world `sales` (the md hero). *"Kolik jsme letos v lednu utržili od
Kauflandu?"* and *"What was our net revenue from Kaufland this January?"* resolve — above the frontier, the resolver's
half (P4 / kantheon) — to ONE `statement.v1`: net revenue, customer Kaufland, period `2026-01`. Below the frontier it
lowers to the md dot-path and a `plan.v1`; the explain says it back in either language.

| case | call | stage |
|---|---|---|
| `kaufland-cs` · `kaufland-en` | `statementLower` — the same frame (only `locale` differs), the same dot-path, plan and provenance | S3.3 |
| `validate` | `statementValidate` — no finding | S3.2 |
| `explain-en` · `explain-cs` | `statementExplain` — *Net revenue · customer Kaufland · January 2026.* / *Čistý obrat · zákazník Kaufland · leden 2026.* | S3.6 |

**List fix (plan.md, CN-P3 pre-flight):** the design's dot-path `kaufland.sales.2026.january.net` is illustrative — the
md hero's month is an int and the resolver has no month names. The golden pins the resolver's canonical text,
`sales[customer.name: "Kaufland", time.month: 1, time.year: 2026].net @ sum` (`time.period_code "2026-01"` → the
`month` and `year` calc coordinates); "January 2026" lives in the explain. Oracle: `oracle.mdPath`.

Pending convention, case format and oracles: [`../README.md`](../README.md).
