# SQL grammar lazy-patch backlog

Per DESIGN §12.7, the vendored grammars (see [`vendor/PINNED.md`](vendor/PINNED.md))
are **not** edited up front. When a concrete corpus query fails to parse — or an
adapter can't extract a real construct — record it here, then either patch the
throwaway-copy generation (`scripts/generate.sh`) or special-case the adapter,
and link the fixture that drove it.

**Parsing/extraction status: clean.** The Phase 0 spike corpus and the sample
corpus (`samples/**/query.ttrm`) both parse 100% cleanly under the default error
strategy (see `src/__tests__/extract-corpus.test.ts`). No grammar/adapter parse
patches are needed.

| Date | Dialect | Construct / query | Symptom | Resolution |
|---|---|---|---|---|
| — | — | — | — | — |

## Resolver residual false-positives (§3.4 corpus pass, 2026-06-13)

The §3.4 SQL reference resolver was run over `samples/v1-metadata` against a
representative `[sql]` config. After fixing the one true resolver-level FP
(masked `{param}` placeholders surfacing as phantom columns — now filtered via
`SqlResolveContext.placeholders`), the remaining findings are **genuine**
model↔SQL mismatches (e.g. SQL reading TPC-DS's denormalised `item.i_brand`,
which the sample model normalises out into `item_brand`; unmodelled columns
`ss_coupon_amt`/`c_login`; the undeclared table `web_clickstreams`) — the
resolver working as intended — **except** the residual below, which needs an
adapter change and is therefore deferred (DESIGN §12.7 / task 3.4.6):

| Construct | Symptom | Why deferred / fix |
|---|---|---|
| SELECT-list **output alias** referenced in `ORDER BY`/`GROUP BY`/`HAVING` — e.g. `… SUM(x) AS revenue … ORDER BY revenue` | `revenue`/`margin_pct` flagged `sql-unknown-column` (they're projection aliases, not table columns) | `SqlRefModel` doesn't surface SELECT output aliases, so the resolver can't tell them from table columns. Proper fix: capture `as_column_alias` / `target_el` aliases per dialect adapter (3.2) into a new `SqlRefModel.outputAliases`, then skip bare columns whose folded name matches one. Low blast radius; tracked here until an adapter pass picks it up. |

### §3.5 parameters cross-check — corpus result (no FPs)

Run over both sample corpora (160 queries): **0 `sql-undeclared-param`, 0
`sql-unused-param`, 0 spurious** — every query declares exactly the parameters
its SQL uses, including placeholders inside string literals (e.g.
`customers_by_last_name`'s `'%{last_name}%'`), which count as uses. Positional
`$1`/`?` params are never flagged and suppress unused warnings (conservative).
No patch needed.
