---
schema: ttr-skill/v1
op: op:top-n
# The superlatives (ttr-core#178). Czech lists every case form of each — soft declension,
# -í · -ího · -ímu · -ím · -ích · -ími: the matcher compares the surface form, the lemma of a
# superlative is its positive degree (nejvyšší → vysoký), and a TYPOS(1) on one form does not
# reach a form two edits away (nejvyšší → nejvyšších).
#  - `best` is EXACT: one edit away are `west`, `rest`, `test`.
#  - `most` is NOT a trigger. It forms the superlative of ANOTHER adjective, and the matcher
#    cannot see which one follows: "the most recent month" names a period, "most of our
#    revenue" a share, and neither asks for a ranking.
triggers:
  - { text: "prvních", lang: cs, method: TYPOS(1) }
  - { text: "top", lang: cs, method: EXACT }
  - { text: "nejlepší", lang: cs, method: TYPOS(1) }
  - { text: "nejlepšího", lang: cs, method: TYPOS(1) }
  - { text: "nejlepšímu", lang: cs, method: TYPOS(1) }
  - { text: "nejlepším", lang: cs, method: TYPOS(1) }
  - { text: "nejlepších", lang: cs, method: TYPOS(1) }
  - { text: "nejlepšími", lang: cs, method: TYPOS(1) }
  - { text: "největší", lang: cs, method: TYPOS(1) }
  - { text: "největšího", lang: cs, method: TYPOS(1) }
  - { text: "největšímu", lang: cs, method: TYPOS(1) }
  - { text: "největším", lang: cs, method: TYPOS(1) }
  - { text: "největších", lang: cs, method: TYPOS(1) }
  - { text: "největšími", lang: cs, method: TYPOS(1) }
  - { text: "nejvyšší", lang: cs, method: TYPOS(1) }
  - { text: "nejvyššího", lang: cs, method: TYPOS(1) }
  - { text: "nejvyššímu", lang: cs, method: TYPOS(1) }
  - { text: "nejvyšším", lang: cs, method: TYPOS(1) }
  - { text: "nejvyšších", lang: cs, method: TYPOS(1) }
  - { text: "nejvyššími", lang: cs, method: TYPOS(1) }
  - { text: "nejprodávanější", lang: cs, method: TYPOS(1) }
  - { text: "nejprodávanějšího", lang: cs, method: TYPOS(1) }
  - { text: "nejprodávanějšímu", lang: cs, method: TYPOS(1) }
  - { text: "nejprodávanějším", lang: cs, method: TYPOS(1) }
  - { text: "nejprodávanějších", lang: cs, method: TYPOS(1) }
  - { text: "nejprodávanějšími", lang: cs, method: TYPOS(1) }
  - { text: "top", lang: en, method: EXACT }
  - { text: "first", lang: en, method: TYPOS(1) }
  - { text: "largest", lang: en, method: TYPOS(1) }
  - { text: "highest", lang: en, method: TYPOS(1) }
  - { text: "biggest", lang: en, method: TYPOS(1) }
  - { text: "best", lang: en, method: EXACT }
requires: [ order-measure ]
version: 1
---
The leading n.

Retrieval: order descending by the ordering measure and limit to the numeral in the
question. The numeral is grounding's output, not this operator's. If the question names no
number, answer the top 10 and say so — it is not a gap to ask about. A question that asks
for ONE thing names its number without a numeral: "which store has the highest …" and
„která prodejna má nejvyšší …“ ask for the top one, while "which stores have the highest …"
names none and gets the top 10.

Formatting: show a rank column, and state the ordering measure in the answer. "Top 5
stores" is only meaningful once the reader knows top by what.

Applicability: `order-measure` — a ranking needs something to rank by. When the question
names exactly one measure, that is it; when it names several, ask rather than choose.
