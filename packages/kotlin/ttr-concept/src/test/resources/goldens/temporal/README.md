# Temporal + the definition catalog on er (PREDICATE mode)

Source: `design/08b-temporality-261016.md` §3.1 (DC-12) and the S0.3 memo §1/§4/§5 (the measured SQL); worlds
`clinic` and `hartland`. `definitionRender(…, ER)`, PREDICATE mode: `SELECT <bearer key> FROM <bearer> WHERE …`.

| case | DC | | world |
|---|---|---|---|
| `dc1-stemi-patient` | 1, 2 | closure semi-join inside a participant `EXISTS` | clinic |
| `dc2-debtor-customer` | 2 | phase in phase (`OverdueInvoice` inlined), `{as_of}` | hartland |
| `dc3-recent-buyer` | 3 | `within(…, last: days(365))` → `>= {window_lo} AND <= {as_of}`, `window_lo` derived (A-CN-22) | hartland |
| `dc4-frequent-patient` | 4 | a count as JOIN + GROUP BY + HAVING; a timestamp vs the day `as_of` → `cn/imprecise-comparison` (advisory) | clinic |
| `dc5-pending-order` · `dc5-held-order` | 5 | state predicates, `"order"` quoted (A-CN-12) | hartland |
| `dc8-node` · `dc8-lexical` | 8 | a block code as a closure node; `ranges: lexical` → BETWEEN (A-CN-13) | clinic |
| `dc9-on-hand` | 9 | `latest(… by:)` → a ROW_NUMBER window in a LEFT JOIN, unbounded at now (A-CN-17) | hartland |
| `dc10-mispriced-line` · `…-colon` | 10 | `:tolerance` → `{tolerance}` / `:tolerance` (A-CN-11) | hartland |
| `dc11-foreign-return-line` | 11 | `not exists` → `NOT EXISTS` through `this.request.order` | hartland |
| `dc12-post-stemi` | 12 | `at_or_before :as_of`, day grain both sides | clinic |
| `dc12-post-stemi-possibly` | 12 | a month-precision onset read POSSIBLY → `< {as_of_hi}`, `as_of_hi = as_of + days(1)` (S0.3 §4) | clinic |
| `dc12-post-stemi-stored-bound` | 12 | the same CERTAINLY over the grounding's stored upper bound (`at_hi: onset_hi`, ⚑CN-P10 — exclusive, A-CN-45) → `onset_hi <= {as_of_hi}` (S3.4) | clinic |
| `dc12-has-broken-leg` | 12 | `contains`, `end_open: open` → the NULL arm | clinic |
| `dc12-history-of-leg-fracture` | 12 | `ended_before` | clinic |

**A day-grain end is the period's last day** (the H-1 outage rule — AU's `to_date >= :as_of`): `contains` reads
`end >= :as_of` and `ended_before` reads `end < :as_of`. S0.3's DC-12b/c probes wrote `>` / `<=`; they measured
translatability, not this rule — the goldens follow H-1.

**Pending:** `dc12-post-stemi-certainly` (certainly from the granularity, no stored bound — G-ER-1) ·
`transferred-within-24h`, `within-around-instant` (an interval over columns — G-ER-1) · `transfer-delay`, `length-of-stay`, `rehospitalized-30d`, `late-return-line`
(datetime difference — G-ER-2).

`dc4-return-share` is active after HP-P2: revenue reaches the stacked sales entity through its bearer-restricted lineage hop.

Pending convention, case format and oracles: [`../README.md`](../README.md).
