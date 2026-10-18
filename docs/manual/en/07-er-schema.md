# The ER model

The `er` model describes your data the way the business talks about it — **entities** with **attributes**, connected by **relations** — independent of how it is physically stored. This is the first part of TTR with no direct SQL equivalent, and the first place modeling earns its keep.

A file of conceptual definitions opens with:

```ttr
model er
```

The `er` model has **no schema** — only the `db` model binds to a physical database. An entity's path is `er.entity.<name>`, where `entity` is the definition's *kind*, not a namespace you configure. (This is why the directive is a bare `model er`.)

## Why model concepts at all

You already have tables. Why describe a second, parallel set of "entities"?

Because tables are shaped by storage decisions, not by meaning. A physical table is named `ORDERS` (because `ORDER` is reserved), splits an address across six columns, and uses `IDSKUPZBOZI` to mean "category." The *concept* is cleaner than that: an order, with an address, that belongs to a category. The ER view lets you state the clean version once, in business language, and — through the [binding](08-mapping.md) — tie it to the messy physical reality. Diagrams, documentation, search, and warehouse roles all hang off the conceptual view, not the physical one.

If you only ever have one table per concept with matching names, the ER view adds little. The moment names diverge, tables split or merge, or you want business-friendly diagrams, it pays off.

## Entities and attributes

```ttr
model er

def entity customer {
    description: "A person or organization that places orders.",
    displayLabel: { en: "Customer" },
    nameAttribute: full_name,
    codeAttribute: email,
    roles: [dimension, master],
    attributes: [
        def attribute id        { type: int, isKey: true },
        def attribute email     { type: text, optional: true },
        def attribute full_name { type: text, optional: true },
        def attribute status {
            type: int,
            optional: true,
            valueLabels: { "1": { en: "Active" }, "2": { en: "Closed" } }
        }
    ]
}
```

The entity-level properties:

- **`description`** — what this concept is.
- **`displayLabel`** — a localized, human-friendly name for diagrams and UIs (`{ en: "Customer", cs: "Zákazník" }`). The entity's *identifier* (`customer`) is for references; the `displayLabel` is for people.
- **`nameAttribute`** — which attribute is the entity's human-readable name (here `full_name`). Tools use it to label rows.
- **`codeAttribute`** — which attribute is the short business code or natural key (here `email`).
- **`roles`** — semantic classification; see [CNC roles](09-cnc-roles.md).
- **`aliases`** — alternative names the business uses, e.g. `["client", "buyer"]`, useful for search.

An `attribute` is a property of the entity:

- **`type`** — same type system as columns.
- **`isKey`** — marks the conceptual key.
- **`optional`** — `true` for nullable; attributes, like columns, are required by default.
- **`valueLabels`** — names the meaning of coded values. `status` stores `1`/`2`; `valueLabels` records that `1` means "Active" and `2` means "Closed", localized. This is the modeling answer to the SQL habit of a separate lookup table or a comment.

Note that the attribute names (`id`, `full_name`) are clean business names — not the physical column names (`CUSTOMER_ID`, `FULL_NAME`). Connecting the two is the binding's job, not the entity's.

## Relations

A `relation` is a named relationship between two entities, with an explicit cardinality. It is the conceptual counterpart of a foreign key, but richer:

```ttr
def relation order_customer {
    description: "Each order is placed by one customer.",
    from: er.entity.order,
    to: er.entity.customer,
    cardinality: { from: "0..*", to: "1" },
    join: [{ from: er.entity.order.customer_id, to: er.entity.customer.id }]
}
```

- **`from` / `to`** — the two entities, by path.
- **`cardinality`** — an object with `from` and `to` multiplicities. Read it as "from the `from` side, the `to` side has multiplicity X." Here `{ from: "0..*", to: "1" }` means: each customer has zero or more orders, and each order has exactly one customer — a classic many-to-one.
- **`join`** — which attributes line the entities up. It is a list (composite joins are possible), each entry `{ from: <attr>, to: <attr> }`. Unlike the legacy YAML `join` string, this is structured over *attributes* and is checked.

Cardinality strings use a small vocabulary: `1`, `0..1`, `1..*`, `0..*`, `*`, `1..n`, `n`. Pick the pair that states the business rule. Some common shapes:

| Relationship | `cardinality` |
|---|---|
| many-to-one (each line → one order) | `{ from: "0..*", to: "1" }` |
| one-to-one optional | `{ from: "0..1", to: "1" }` |
| mandatory one-to-many | `{ from: "1..*", to: "1" }` |
| many-to-many | `{ from: "*", to: "*" }` (usually via a bridge entity) |

## Relations across packages

In the retail model, an order line (in `shop.sales`) relates to a product (in `shop.catalog`). The `to` side simply uses the product's full path:

```ttr
def relation line_product {
    from: er.entity.order_line,
    to: shop.catalog.er.entity.product,
    cardinality: { from: "0..*", to: "1" },
    join: [{ from: er.entity.order_line.product_id, to: shop.catalog.er.entity.product.id }]
}
```

Full paths always resolve; [Packages and imports](10-packages-and-imports.md) shows how to shorten them with `import`.

## Entities on queries

An entity does not have to be stored in a table. It can be **stacked on a query** — the output of a saved query, often a [logical query](12-queries-and-procedures.md#logical-queries) written over other entities:

```ttr
def entity shopper_revenue {
    binding: { target: { query: er.query.shopper_revenue } },
    attributes: [
        def attribute shopper_id    { type: int, isKey: true },
        def attribute total_revenue { type: decimal, optional: true, binding: revenue }
    ]
}
```

Its attributes bind to the query's output columns, and it relates to other entities through `join:` pairs, since it has no foreign key. [Entities on queries](12-queries-and-procedures.md#entities-on-queries) has the details.

## The conceptual retail model

The retail example has five entities — `customer`, `order`, `order_line` in `shop.sales`, and `product`, `category` in `shop.catalog` — wired by four relations: `order_customer`, `line_order`, `line_product`, and `product_category`. `order_line` sits at the center, referring to an order and a product; that shape is exactly what makes it the *fact* of the model, which the [CNC roles](09-cnc-roles.md) page builds on.

So far the ER and DB views are two unconnected descriptions. [Binding](08-mapping.md) ties them together.
