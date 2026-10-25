<!-- SPDX-License-Identifier: Apache-2.0 -->
# The layers

*Explanation. `db`, `er`, `md`, `cn`: what each layer is for, why the mirror of your database is
not yet a model, and who owns which layer.*

A TTR-M model is layered on purpose. Each layer answers a different question, and the separation is
what lets a faithful copy of your database coexist with the meaning you add on top of it — without
one overwriting the other.

## `db` — the physical mirror

The `db` layer is your database as it actually is: tables, columns, types, primary keys, the foreign
keys that are really declared. `ttr import-schema` generates it, and it is **machine-owned** — a
re-run diffs the live database against it and proposes the changes. You rarely hand-edit `db`; you
let the tool keep it honest.

By itself the `db` layer is a mirror. It tells you what exists physically, but not what any of it
*means* — and "modeling that is only a mirror does not pay for itself." That is what the next layer
is for.

## `er` — the meaning

The `er` layer is the entity-relationship model: entities, their attributes, and the relations
between them — the concepts your organisation actually thinks in. `ttr import-schema` gives you a
*first cut* (junctions collapsed, relations graded by evidence, folds proposed), but from your first
edit the `er` layer is **human-owned**. Re-runs never regenerate it; they only flag drift for you to
act on. This is where you spend your judgement, and it is the layer worth protecting.

The `er` layer binds down to `db` — an entity maps to a table, an attribute to a column — so the
meaning stays anchored to the physical truth without repeating it.

## `md` — the multidimensional view

The `md` layer is the analytical projection: domains, dimensions, hierarchies, measures and
cubelets, bound down through `md2db` / `md2er`. It is optional — reach for it when you want a
cube-shaped view over the same governed model. Which entities are facts and which are dimensions
follows from it: a cubelet's entity is a fact, a dimension's entity a dimension.

## `cn` — the conceptual model

The `cn` layer names what the business talks about — a product, a customer, a sale, an overdue
invoice — apart from how the other layers store it. Each concept is grounded once, to an `er` or an
`md` element, and a defined one (`OverdueInvoice`: an open invoice past its due date) carries the
expression that says which rows it means. Agents and recipes speak in concepts; the server turns
what they say into a governed query through the groundings, and new concepts arrive as candidates
a steward promotes in a pull request. The [user manual's conceptual-model chapter](https://github.com/Collite/ttr-core/blob/master/docs/manual/en/20-conceptual-model.md)
is the full reference.

The older `cnc` layer of warehouse roles (`fact`, `dimension`, …) is deprecated: those roles now
derive from `md`. Access policy — row filters, masked columns — is not a model layer: it lives in
the validator's store ([Policy in git](../operate/policy-in-git.md)).

## Who owns what

| Layer | Owner | Lifecycle |
|---|---|---|
| `db` | machine | regenerated / diffed on every import re-run |
| `er` | you | born once from the import, hand-owned forever after |
| `md` | you | optional analytical projection |
| `cn` | you | authored; new concepts promoted from candidates by pull request |

The layers live in **packages** (a directory of `.ttrm` files) and can be grouped into **areas**
(subject areas that span packages). One model, several layers, each with an owner — so the tool can
keep the mirror current while your meaning stays exactly as you wrote it. For the full syntax of
each construct, see the [language reference](language-reference.md); for why this whole thing lives
in git, see [why the model is the deployment artifact](why-model-is-the-artifact.md).
