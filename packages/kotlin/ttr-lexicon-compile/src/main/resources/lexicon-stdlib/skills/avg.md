---
schema: ttr-skill/v1
op: op:avg
# ttr-core#178. How each word is matched, and what was left out:
#  - `per` (en) and `na` (cs) are NOT triggers, although they name an average's unit ("revenue
#    per line item", „tržba na položku“). Both are among the commonest words in a question and
#    usually say something else: "revenue per month" asks for twelve monthly totals, „tržby na
#    tržišti“ names a channel, „podíl na tržbách“ a share's whole. A trigger there turns plain
#    totals into an average — one wrong number that nothing downstream can tell from a right
#    one. (`na` is also a function word, and both are too short for any fuzz.) They stay the
#    words that name the UNIT once an average was asked for.
#  - `mean` is EXACT: one edit away are `meat`, `meal`, `means`, `men`.
#  - Multi-word forms are EXACT, never TOKENS: `v průměru` as TOKENS is `průměru` alone.
#  - The Czech adjective is listed in every case form: the matcher compares the surface form,
#    and a TYPOS(1) on one form does not reach a form two edits away (průměrná → průměrnou).
triggers:
  - { text: "průměrný", lang: cs, method: TYPOS(1) }
  - { text: "průměrná", lang: cs, method: TYPOS(1) }
  - { text: "průměrné", lang: cs, method: TYPOS(1) }
  - { text: "průměrní", lang: cs, method: TYPOS(1) }
  - { text: "průměrného", lang: cs, method: TYPOS(1) }
  - { text: "průměrnému", lang: cs, method: TYPOS(1) }
  - { text: "průměrném", lang: cs, method: TYPOS(1) }
  - { text: "průměrným", lang: cs, method: TYPOS(1) }
  - { text: "průměrnou", lang: cs, method: TYPOS(1) }
  - { text: "průměrných", lang: cs, method: TYPOS(1) }
  - { text: "průměrnými", lang: cs, method: TYPOS(1) }
  - { text: "průměrně", lang: cs, method: TYPOS(1) }
  - { text: "průměr", lang: cs, method: TYPOS(1) }
  - { text: "průměru", lang: cs, method: TYPOS(1) }
  - { text: "v průměru", lang: cs, method: EXACT }
  - { text: "average", lang: en, method: TYPOS(1) }
  - { text: "mean", lang: en, method: EXACT }
requires: [ avg-measure ]
version: 1
---
The amount per one.

Retrieval: an average is a sum over a count, and both come from one result set, filtered
once: `SUM(<measure>) / COUNT(DISTINCT <the unit's key>)`. The unit is what the question
averages over — "per line item", „na položku“, "per order", "monthly". An `AVG` over the
fact's rows gives the same number only when the unit IS the row: an average order value over
order lines is each order's sum averaged, not the average line. A sum and a count fetched in
two queries can divide numbers that were filtered differently. When the question names no
unit, the unit is one row of the measure's own fact — and the answer says so.

Formatting: the average with its unit named — "average revenue per order", never a bare
"average revenue". Like a share's denominator, the unit is what makes the number readable;
without it the reader cannot tell a per-line figure from a per-order one.

Applicability: `avg-measure` — an average needs exactly one measure to average. When the
question names exactly one, that is it; when it names several, ask rather than choose.
