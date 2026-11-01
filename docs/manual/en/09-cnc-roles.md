# CNC roles (deprecated)

> **Deprecated since grammar 0.17.** `model cnc`, `def role` in it and an entity's `roles:` still parse, and
> `cn/cnc-deprecated` warns on each; a later major release removes them. There is **nothing to migrate to**: what the
> roles recorded is now derived from the model, so a model drops its `cnc` files and `roles:` lists rather than
> rewriting them. See [Conceptual model (cn)](20-conceptual-model.md#cnc-is-deprecated).

The `cnc` model was a small vocabulary of **roles** — `fact`, `dimension`, `master`, `transaction`, `structural`,
`bridge` — that classified an entity by the part it plays in a data warehouse. This page says where each of them comes
from now, after a short primer on the dimensional-modeling ideas behind the names.

## A two-minute primer

Analytical databases are usually organized around a simple split:

- Some tables hold **the things you measure** — sales, payments, clicks, shipments. They are long, they grow constantly, and they are full of numbers (amounts, quantities) and foreign keys. These are **facts**.
- Other tables hold **the things you measure *by*** — customers, products, dates, regions. They are comparatively short, they describe rather than count, and you use them to group and filter. These are **dimensions**.

A query like "total sales quantity by product category last month" reads quantities from a fact (`order_line`) and
groups them through dimensions (`product` → `category`, plus a date). Recognizing which entity is which is most of
dimensional modeling. With `cnc` you wrote it down by hand; now the md model says it, because an md cubelet *is* the
thing you measure and an md dimension *is* what you measure by.

## Where each role comes from now

| role | now | status |
|---|---|---|
| `fact` | **derived**: the entity a cubelet lands on, or an entity with measures and two many-to-one outgoing relations | landed (grammar 0.18.2) |
| `dimension` | **derived**: the entity a dimension lands on, or the one side of a relation from a fact | landed (grammar 0.18.2) |
| `bridge` | **derived**: the target of a many-to-many map; it is kept out of grain coarsening | landed (grammar 0.18.2) |
| `master` · `transaction` · `structural` | an entity kind in `semantics { kind: … }` | **planned** — not in grammar 0.21, whose `semantics { kind: }` still takes only `period_table`, `calendar`, `poi` and `fx_rate` |

The derived roles come from the same md → er mappings that [the hop](15-md-model.md#the-md--er-hop-what-you-pin-what-is-derived)
reads. An entity no rule classifies stays unclassified rather than guessed. What a business means by a thing — a
customer, an order, an overdue invoice — is the [conceptual model](20-conceptual-model.md)'s to say, not a warehouse
role's.

## Reading a model through its derived roles

```
ttr md hop <model-dir>
```

prints every md element with its er landing and the warehouse role derived for each entity, with the rule that gave
it. In VS Code, **TTR: md → er hop report** shows the same, and hovering an er entity shows its derived role and why.
For the tutorial's retail model with an md face — a sales cubelet over `order_line`, and the dimensions Customer,
Product and Category:

```
        customer (dimension)
            ▲
            │ order_customer
            │
   order ◄── line_order ── order_line (FACT) ── line_product ──► product (dimension)
                                                                      │
                                                            product_category
                                                                      ▼
                                                              category (dimension)
```

`order_line` is the fact at the center, because the sales cubelet lands on it; the three dimensions land on
`customer`, `product` and `category`. `order` stays unclassified, as no rule speaks for it. An analyst or a tool knows what to sum (`order_line.quantity`) and what to group by (product, category,
customer, order date) from the md model, without a hand-kept tag that can drift from it.

## A model that still has `roles:`

The roles keep parsing, so nothing breaks while they stay. Each one is checked against the derivation: a `roles:`
claim the derived role disagrees with is `md/role-claim-mismatch` (a warning), which is usually the sign that the
claim was stale. Then delete them: `roles: [fact]` and the like go, and so does every `model cnc` file. A custom role
defined in a `model cnc` file has no replacement; if it named something the business talks about, it is a concept.

With concepts, physical tables, bindings, and roles in place, the remaining pages are about scale and tooling: [splitting a model across packages](10-packages-and-imports.md), [curating diagrams](11-graphs.md), and [the editor experience](13-vscode.md).
