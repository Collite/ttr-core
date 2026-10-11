# TTR for SQL users

## The one idea

In SQL, there is only one description of your data: the physical schema. Tables and columns *are* the model. If you want to talk about "a customer" as a business concept, you point at the `CUSTOMER` table and everyone nods.

TTR separates those two things on purpose. There is the **physical** view — your real tables, with their real (often cryptic) names — and there is the **conceptual** view — clean business entities like `customer` and `order`, named the way the business talks about them. TTR lets you write both and then **declare how they line up**.

Why bother? Because the two drift apart in every real system. The table is `item` but the business calls it a "product." One entity is assembled from three tables; one table backs two entities. A column is named `ss_ext_sales_price` but it means "revenue." In SQL you carry that translation in your head. In TTR you write it down once, and the tooling checks it forever.

## The same model, both ways

Here is a customer in SQL:

```sql
CREATE TABLE CUSTOMER (
    CUSTOMER_ID INT NOT NULL PRIMARY KEY,
    EMAIL       VARCHAR(255),
    FULL_NAME   VARCHAR(255),
    STATUS      INT
);
```

The physical (`db`) view in TTR says the same thing:

```ttr
model db schema dbo

def table CUSTOMER {
    primaryKey: ["CUSTOMER_ID"],
    columns: [
        def column CUSTOMER_ID { type: int, isKey: true },
        def column EMAIL       { type: text, optional: true },
        def column FULL_NAME   { type: text, optional: true },
        def column STATUS      { type: int, optional: true }
    ]
}
```

So far this is just SQL with braces. The new part is the conceptual (`er`) view, where you describe the *business* entity — no table names, no physical column names, just the concept:

```ttr
model er

def entity customer {
    nameAttribute: full_name,
    attributes: [
        def attribute id        { type: int, isKey: true },
        def attribute email     { type: text, optional: true },
        def attribute full_name { type: text, optional: true },
        def attribute status    { type: int, optional: true }
    ]
}
```

And the `binding` view declares the correspondence — this entity lives in that table, this attribute is that column:

```ttr
model binding

def er2db_entity customer { entity: er.entity.customer, target: { table: db.dbo.CUSTOMER } }

def er2db_attribute customer.id    { attribute: er.entity.customer.id,    target: { column: db.dbo.CUSTOMER.CUSTOMER_ID } }
def er2db_attribute customer.email { attribute: er.entity.customer.email, target: { column: db.dbo.CUSTOMER.EMAIL } }
```

That third block is the thing SQL has no equivalent for. It is an explicit, checkable statement that the conceptual `customer.id` is physically stored in `CUSTOMER.CUSTOMER_ID`. If someone renames the column, the tooling flags the broken binding.

## Vocabulary

The conceptual side uses different words from SQL. They line up almost one-to-one:

| SQL term | TTR (conceptual / `er`) | Notes |
|---|---|---|
| Table | **Entity** | A business concept. Usually backed by one table, but not always. |
| Column | **Attribute** | A property of an entity. |
| Foreign key | **Relation** | A named relationship with an explicit cardinality (1-to-many, etc.). |
| Row | (instance) | Not modeled directly; TTR describes structure, not data. |
| Schema (a SQL namespace) | **Schema** | TTR uses the word the same way SQL does — see below. |
| — | **Role** | A semantic tag like `fact` or `dimension`. No SQL equivalent. |

A few terms are genuinely new and worth fixing in your mind now:

- **Model** (in TTR) means a *perspective* — `db`, `er`, `binding`, `cnc` — selected with the `model` directive. This is what an earlier version of TTR confusingly called a "schema."
- **Schema** in TTR means exactly what it means in SQL: a database namespace such as `dbo`. It appears as the middle segment of a physical path — `dbo` in `db.dbo.CUSTOMER` — and only the `db` model has one.
- **Package** is a module — a folder of related files — closer to a Java/Python package than to anything in SQL. Covered in [Packages and imports](10-packages-and-imports.md).
- **Role** classifies an entity for warehouse modeling (`fact`, `dimension`, …). Covered in [CNC roles](09-cnc-roles.md).

## How the views relate

A useful way to hold it in your head:

```
  er (concepts)        binding (the glue)          db (physical)
  ─────────────        ──────────────────          ────────────
  entity  customer  ──►  er2db_entity      ──►     table   CUSTOMER
  attribute  id     ──►  er2db_attribute   ──►     column  CUSTOMER_ID
  relation order_customer ─► er2db_relation ─►     fk      fk_orders_customer
```

The left column is how the business thinks. The right column is how the database is built. The middle column is the translation, written down and checked. You can start from either side — model the concepts first and map them down to tables, or describe existing tables and lift concepts up — and TTR is happy either way.

The next page shows how all of this compares to the legacy YAML models. If you have never used those, you can skip straight to [Your first model](04-your-first-model.md).
