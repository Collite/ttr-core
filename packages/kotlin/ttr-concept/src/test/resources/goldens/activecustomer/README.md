# Phase filters on md — ActiveCustomer and the gated ones

Source: `design/04-statement-options.md` §E-0 ("the phase IS the filter"), world `sales`. A phase in a statement's filter
slot: its definition is inlined, rendered on the md layer, recorded in the provenance (`inlinedDefinitions`).

| case | | stage / demand |
|---|---|---|
| `revenue-active` | net revenue in January 2026 from customers active in the 365 days before `as_of` 2026-01-31 | S3.3 |
| `cross-cubelet.pending` | an existence over another cubelet's event | G-MD-1 |
| `phase-filter-debtor-customer.pending` | `DebtorCustomer` (existence over invoices) as a statement filter | G-MD-1 |
| `phase-filter-stemi-patient.pending` | `StemiPatient` (closure membership) | G-MD-2 |
| `phase-filter-post-stemi.pending` · `phase-filter-has-broken-leg.pending` | the DC-12 words on md | G-MD-6 |

**⚑CN-P9 (default, plan.md):** `ActiveCustomer` is `exists Sale involving this where within(period.at, last: days(365))`
— an existence md cannot say (G-MD-1). When the existence ranges over the carrying cubelet's own event, `ttr-concept`
composes it on `plan.v1` as a grain-key semi-join: the outer read grouped by `customer.name`, kept where the key is
`IN` the keys of a second read over the window (`time.day: "2025-01-31".."2026-01-31"` — `within(x, last: d)` is
`x >= as_of - d and x <= as_of`), aggregated again. `oracle.semiJoin` builds exactly that from the two md reads. The
dot-path spells the composition `<outer> where customer.name in (<inner>)`.

Pending convention, case format and oracles: [`../README.md`](../README.md).
