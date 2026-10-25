# The conceptual-model sample

A small `cn` package over Hartland's web orders — who orders, what an order and an invoice are, when an order is
pending, when an invoice is overdue and who is in debt — with its groundings (`bindings.ttrm`) and its English and
Czech lexicon terms. It is written in the formatter's canonical layout, and `packages/format`'s tests hold it to that.

The full example is the Hartland demo's own package, in [Collite/ttr-demo](https://github.com/Collite/ttr-demo):
`model/cn/` (83 concepts — every sales channel, the order, invoice and return recipes' phases, the parametric ones),
`model/binding/cn.ttrm` (the events' participants and periods) and `model/lexicon/{en,cs}/cn.ttrm`. Its md-grounded
concepts land on the er layer through the derived hop, with no `md2er` binding authored, and a Tatrman server serves
them over its doors. The user manual's [conceptual-model chapter](../../../docs/manual/en/20-conceptual-model.md)
walks through both.
