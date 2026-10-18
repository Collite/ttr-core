# Queries and procedures

Beyond tables and entities, TTR can record the **queries** and **stored procedures** that belong to a model. These let you capture reusable SQL, parameterized lookups, and procedure signatures as first-class, documented objects rather than as loose strings scattered around an application.

A `def procedure` is a **db-layer object**, and so is a `def query` by default: both live in the `db` model, in a schema (default `dbo`), just like a table — so the tables a query references resolve in that same schema. They sit in a normal `db` file:

```ttr
model db schema dbo
```

A query can also be an **er-layer object** — a *logical query*, written over entities instead of tables — when it is declared in a `model er` file. [Logical queries](#logical-queries) below covers those; everything up to there is about db queries.

> Earlier versions of TTR had a separate `query` perspective. As of grammar 4.0, queries (and drill maps) fold into the `db` model — there is no separate `query` schema to select. A query is addressed like any db object, under its schema (`db.dbo.<name>`).

## Queries

A `query` names a piece of query logic, in a stated language, with optional parameters:

```ttr
def query top_products_by_revenue {
    description: "Best-selling products by total line revenue.",
    language: SQL,
    parameters: [
        { name: from_date, type: date, label: "From date" },
        { name: to_date,   type: date, label: "To date" }
    ],
    sourceText: """
        SELECT p.NAME, SUM(ol.QUANTITY * ol.UNIT_PRICE) AS revenue
        FROM ORDER_LINE ol
        JOIN PRODUCT p ON p.PRODUCT_ID = ol.PRODUCT_ID
        JOIN ORDERS o ON o.ORDER_ID = ol.ORDER_ID
        WHERE o.ORDER_DATE BETWEEN :from_date AND :to_date
        GROUP BY p.NAME
        ORDER BY revenue DESC
    """
}
```

- **`language`** — one of `SQL`, `TRANSFORMATION_DSL`, `DATAFRAME_DSL`, or `REL_NODE`. It tells consumers how to interpret `sourceText`.
- **`parameters`** — a list of `{ name, type, label }` objects. `name` is a bare identifier, `type` uses the standard type system, and `label` is a display string.
- **`sourceText`** — the query body, usually a triple-quoted string. TTR stores it verbatim; it does not parse or validate the SQL.

The value of declaring a query in TTR is documentation and discoverability: it appears in the symbol outline, carries a description, states its parameters explicitly, and sits in version control alongside the model it queries.

## Logical queries

A **logical query** is a saved query written over the [ER model](07-er-schema.md): its body names entities and attributes, not tables and columns, and it joins entities with a bare `JOIN` — the condition comes from the relations' `join:` pairs, exactly as when an agent asks over entities. Declare it in a `model er` file and it is addressed `er.query.<name>`:

```ttr
model er

def query shopper_revenue {
    description: "Revenue per shopper, from store sales",
    sourceText: """sql
SELECT shopper.shopper_id, shopper.last_name, shopper.current_household,
       SUM(sale.revenue) AS revenue
FROM sale JOIN shopper
GROUP BY shopper.shopper_id, shopper.last_name, shopper.current_household
"""
}
```

`sale` and `shopper` are entities; `FROM sale JOIN shopper` needs no `ON`, because the relation between them says which attributes line up. The physical tables behind the entities never appear in the body.

**The file's model is the query's lane.** A `def query` in a `model er` file is read over entities; one in a `model db` file, or in a file with no `model` directive, is read over tables. When the model is served, the metadata service parses each query's body in its lane — it never guesses one — and reports a body that does not fit:

- A logical query whose body names a table is refused (`lane_mismatch`, naming the table); one that reads entities and tables together is refused too (`mixed_lanes`). Mixing layers in one body is not supported.
- The same check runs the other way: a db query whose body names an entity, or names no table at all, is `lane_mismatch`.
- A logical query takes **no parameters**. The editor warns (`ttr/logical-query-parameters`). The metadata loader reports an error (`logical_query_parameters`), and the error is the model's, not the query's: the query is not dropped, the whole model fails to reconcile. A query that needs parameters stays a db query.

### Entities on queries

A query's output can back an entity — an entity *stacked on* the query. Its binding targets the query instead of a table, and its attributes bind to the query's **output columns**:

```ttr
def entity shopper_revenue {
    description: "A shopper with the revenue of their store sales",
    binding: { target: { query: er.query.shopper_revenue } },
    attributes: [
        def attribute shopper_id        { type: int, isKey: true },
        def attribute last_name         { type: text, optional: true },
        def attribute current_household { type: int, optional: true },
        def attribute total_revenue     { type: decimal, optional: true, binding: revenue }
    ]
}

def relation shopper_revenue_household {
    from: er.entity.shopper_revenue, to: er.entity.household,
    cardinality: { from: "0..*", to: "0..1" },
    join: [{ from: er.entity.shopper_revenue.current_household, to: er.entity.household.household_id }]
}
```

- An attribute with no `binding:` names the output column of its own name; `binding: revenue` names the column `revenue`. The attributes are declared, not inferred, and they are checked against the query's output when the query is parsed: an attribute bound to a column the query does not output is reported as `entity_query_output_mismatch` on the entity, naming the attribute, the missing column and the columns the query does output. The query itself still loads.
- Either lane can back an entity: `target: { query: … }` may name a logical query or a db query.
- A stacked entity has no foreign key, so it relates to other entities through `join:` pairs, as above.
- Entities can stack on queries over other stacked entities; a cycle is an error.
- **Column rules do not reach through the query.** A column rule (`deny` or `mask`) on a column of an entity the body reads does not apply to the stacked entity, not even to an attribute computed from that column: with `sale.revenue` masked, `total_revenue` above is still served unmasked. A column rule sees the stacked entity as an ordinary entity, so put the rule on the stacked entity's attribute too. Row-level policies do reach through (below).

Everything above the model sees a stacked entity as an ordinary entity — its roles, name attribute, aliases and relations work unchanged, and an agent asks over `shopper_revenue` without knowing a query is behind it. A logical query is read through its entity, never run on its own: a saved-query reference to one is refused. The runtime inlines the query's body where the entity is read — there is no view and no materialized copy — so row-level policies on the entities the body reads still apply to a question asked through the stacked entity, as does a policy on the stacked entity itself.

A complete, loadable example — the tables, the entities and relations, the logical query and the entity stacked on it — is [`samples/2.1/logical-query`](../../../samples/2.1/logical-query/).

## Procedures

A `procedure` captures a stored procedure's **signature** — its parameters (with direction) and its result columns:

```ttr
def procedure close_order {
    description: "Marks an order closed and returns the affected row count.",
    parameters: [
        { name: order_id, type: int, label: "Order ID", direction: IN },
        { name: closed_count, type: int, label: "Rows closed", direction: OUT }
    ],
    resultColumns: [
        def column ORDER_ID { type: int },
        def column STATUS   { type: int }
    ]
}
```

- **`parameters`** — like a query's, plus a `direction` of `IN`, `OUT`, or `INOUT`.
- **`resultColumns`** — the shape of what the procedure returns, written as inline `def column` definitions (same kind as a table's columns).

As with views and queries, TTR records the *contract*, not the implementation body. That is enough for the model to document what the procedure expects and returns, and for tooling to surface it.

## When to use these

Reach for `query` and `procedure` when a piece of SQL is part of the model's vocabulary — a canonical metric, a standard lookup, a procedure other systems call — and you want it described, named, and versioned in one place. Reach for a **logical query** when the piece of logic is about business objects — revenue per shopper, active customers with an open order — and you want agents to ask about its result as an entity: written over entities, it keeps working when the physical tables behind them change. For one-off, application-private SQL, there is no need to model it. Like the rest of TTR, these are descriptions meant to be read and checked, not executed by TTR itself.

The next page leaves the language behind and covers the editor — [the VS Code experience](13-vscode.md) — where all of this becomes interactive.
