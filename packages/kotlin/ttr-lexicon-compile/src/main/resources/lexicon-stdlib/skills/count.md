---
schema: ttr-skill/v1
op: op:count
# ttr-core#178. How each word is matched, and what was left out:
#  - Multi-word forms are EXACT, never TOKENS. A TOKENS row is scored over the question's own
#    words, so `number of` would fire on `number` alone once `of` is a function word — and
#    "order number" is an attribute, not a count (LP review-103 found the same on `pred:` forms).
#  - Bare `number` is left out for that reason. Czech `počet` has no second sense (an
#    identifier is `číslo`), so it is a trigger on its own.
#  - `count` is EXACT: one edit away is `county`, a place.
#  - `kolik` / `kolika` are EXACT: one edit away is `kolin`, a town typed without diacritics.
#    `kolika` is the oblique case ("v kolika prodejnách").
#  - `počet` and `počtem` are TYPOS(1), which also reaches the diacritic-free `pocet` / `poctem`.
#  - `počty` / `počtu` / `počtů` are EXACT: one edit away are `pošty` / `poštu`, the post office
#    and the mail. Their diacritic-free forms are listed instead (`poctu` is also `počtů`'s).
triggers:
  - { text: "kolik", lang: cs, method: EXACT }
  - { text: "kolika", lang: cs, method: EXACT }
  - { text: "počet", lang: cs, method: TYPOS(1) }
  - { text: "počtem", lang: cs, method: TYPOS(1) }
  - { text: "počtu", lang: cs, method: EXACT }
  - { text: "počty", lang: cs, method: EXACT }
  - { text: "počtů", lang: cs, method: EXACT }
  - { text: "poctu", lang: cs, method: EXACT }
  - { text: "pocty", lang: cs, method: EXACT }
  - { text: "how many", lang: en, method: EXACT }
  - { text: "number of", lang: en, method: EXACT }
  - { text: "count of", lang: en, method: EXACT }
  - { text: "count", lang: en, method: EXACT }
requires: [ count-subject ]
version: 1
---
How many.

Retrieval: count distinct identities, not rows — `COUNT(DISTINCT <the entity's key>)` over the
filtered set. A dimension that keeps history holds several rows per member (one per version),
and a fact holds one row per line, not per order; counting rows of either answers a bigger
number than the question asked about. When the thing counted is an event the fact records at
a finer grain ("how many orders" over order lines), count the event's own identifier, never
the lines.

Formatting: one number, with the thing counted named beside it — "128 stores", not "128". A
count by something is a table whose count column is named for the thing counted.

Applicability: `count-subject` — a count needs a thing with identity to count: the entity the
question names (stores, customers, orders). When the counted word is a measure instead ("how
many units did we sell", „kolik kusů jsme prodali“, „kolik činily tržby“), the measure is
already the amount asked for: answer the measure, and count nothing. When the question names
several things it could count, ask rather than choose.
