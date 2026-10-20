# CNC roles

> **Deprecated in grammar 0.17.** `model cnc` and `roles:` still parse; a deprecation warning arrives with the
> conceptual model's semantics, and a later major release removes them. There is nothing to migrate to — see
> [Conceptual model (cn)](20-conceptual-model.md#cnc-is-deprecated).

The `cnc` model is a small vocabulary of **roles** — `fact`, `dimension`, `master`, `transaction`, `structural`, `bridge` — that classify an entity by the part it plays in a data warehouse. Tagging entities with roles is how you tell analytics tooling "this is something you measure" versus "this is something you slice by." This page also gives a short primer on the dimensional-modeling ideas behind the names, since the SQL world rarely names them out loud.

## A two-minute primer

Analytical databases are usually organized around a simple split:

- Some tables hold **the things you measure** — sales, payments, clicks, shipments. They are long, they grow constantly, and they are full of numbers (amounts, quantities) and foreign keys. These are **facts**.
- Other tables hold **the things you measure *by*** — customers, products, dates, regions. They are comparatively short, they describe rather than count, and you use them to group and filter. These are **dimensions**.

A query like "total sales quantity by product category last month" reads quantities from a fact (`order_line`) and groups them through dimensions (`product` → `category`, plus a date). Recognizing which entity is which is most of dimensional modeling, and TTR lets you record it directly with roles instead of leaving it implicit.

## The stock roles

When `modeler.toml` loads `cnc-roles`, six roles are available everywhere without importing:

| Role | Meaning | Retail example |
|---|---|---|
| **`fact`** | Quantitative, measurable events; the grain you aggregate. | `order_line` |
| **`dimension`** | Descriptive context you group and filter by. | `product`, `category` |
| **`master`** | Canonical reference data shared across the business. | `customer` |
| **`transaction`** | Operational, event-level records. | `order` |
| **`structural`** | Metadata that supports the model's structure rather than business data. | (lookup/hierarchy tables) |
| **`bridge`** | Resolves a many-to-many relationship between two entities. | (an order-to-promotion link table) |

The categories overlap by design — an entity can carry more than one role. A customer is both `master` (canonical reference data) and a `dimension` (you slice sales by it). An order is both a `transaction` and, depending on how you aggregate, part of the fact story.

## Assigning roles

Roles go in an entity's `roles` list as bare names:

```ttr
model er

def entity order_line {
    description: "A single product line within an order. The grain of the sales fact.",
    roles: [fact],
    attributes: [ … ]
}

def entity customer {
    roles: [dimension, master],
    attributes: [ … ]
}

def entity product {
    roles: [dimension],
    attributes: [ … ]
}
```

That is the whole feature from the modeler's side: pick the roles that describe each entity. The names resolve to the stock `cnc.role.*` definitions, so a typo like `roles: [dimention]` is flagged.

If you came from the legacy YAML, this is where `entity_type` went. `entity_type: master` becomes `roles: [master]`; the difference is that an entity can now hold several roles instead of exactly one.

## Reading the retail model through roles

With roles assigned, the shape of the retail model becomes legible at a glance:

```
        customer (dimension, master)
            ▲
            │ order_customer
            │
   order (transaction) ◄── line_order ── order_line (FACT) ── line_product ──► product (dimension)
                                                                                    │
                                                                          product_category
                                                                                    ▼
                                                                            category (dimension)
```

`order_line` is the fact at the center; everything else is context you measure it by. An analyst — or a tool — reading this model knows immediately what to sum (`order_line.quantity`, `order_line.unit_price`) and what to group by (product, category, customer, order date), without having to reverse-engineer it from table sizes and join patterns.

## Defining your own roles

The stock six cover most needs, but `cnc` is an ordinary model and you can define additional roles when your organization uses its own vocabulary:

```ttr
model cnc

def role slowly_changing_dimension {
    description: "A dimension that retains history of attribute changes.",
    label: { en: "Slowly changing dimension", cs: "Pomalu se měnící dimenze" }
}
```

Once defined (and loaded), it is assignable like any stock role: `roles: [dimension, slowly_changing_dimension]`. Keep custom roles few and well-described — their value is shared, consistent meaning across the team.

With concepts, physical tables, bindings, and roles in place, the remaining pages are about scale and tooling: [splitting a model across packages](10-packages-and-imports.md), [curating diagrams](11-graphs.md), and [the editor experience](13-vscode.md).
