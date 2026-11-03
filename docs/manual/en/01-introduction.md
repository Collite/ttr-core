# Introduction

## What TTR is

TTR is a **modeling language**. You use it to write down — in plain text — what your data looks like: the tables in your database, the business entities they represent, and how those two views line up. The files you write are checked and navigated by editor tooling (a VS Code extension and its language server) and drawn as diagrams by the Studio, a Collite product, but TTR itself is just text. It is version-controlled like code, reviewed like code, and diffed like code.

TTR describes data; it does not run it. There is no TTR query engine and no TTR migration tool baked into this language. A TTR model is a *description* that other systems read. Think of it the way you think of a database schema diagram — except precise enough for a machine to validate.

## What it replaces

Many teams already describe their models in **YAML** — one file per business area, each listing entities, their columns, the database table behind them, and the joins between them. That works, but YAML knows nothing about your model. It cannot tell you that a join points at a column that no longer exists, that two entities claim the same table, or that you misspelled an attribute name. You find out at runtime, or never.

TTR keeps the same information but gives it structure the tooling understands. A misspelled reference is underlined as you type. "Go to definition" jumps from an attribute to the column it maps to. Renaming is safe because the tooling knows what points where. If you are coming from the YAML models, read [YAML vs TTR](03-yaml-vs-ttr.md) — it shows the same model both ways.

## Who this manual is for

You know SQL. You can read a `CREATE TABLE`, you understand primary and foreign keys, joins, and nullability. You have **not** spent years doing formal data modeling, and terms like "conceptual vs. logical model," "dimension," or "bridge entity" are fuzzy or new. That is exactly the right starting point. This manual leans on what you already know about SQL and introduces the modeling ideas one at a time, each with a concrete example.

## How TTR sees a model

The single most important idea in TTR is that one model is described from **several coordinated angles**, called *models* (the keyword is `model`). Each `model` directive selects a perspective; the word *schema* is reserved for something else — the physical database namespace (`dbo`), which only the `db` perspective has. There are four perspectives:

- **`db`** — the physical database: tables, columns, keys, indexes, foreign keys. This is the part that looks most like SQL, and the only one with a *schema* (a SQL namespace such as `dbo`).
- **`er`** — the conceptual entity-relationship view: the business entities and how they relate, independent of how they happen to be stored.
- **`binding`** — the bridge: which entity is stored in which table, which attribute is which column.
- **`cnc`** — semantic roles (fact, dimension, and so on) that classify entities for data-warehouse purposes.

You do not have to use all four. A model can be just `db`, or just `er`, or any combination. But the power of TTR comes from describing more than one and linking them. The next page, [TTR for SQL users](02-ttr-for-sql-users.md), unpacks this with a side-by-side comparison.

## How to read this manual

If you want to get hands-on fast, skim this page and [TTR for SQL users](02-ttr-for-sql-users.md), then go straight to [Your first model](04-your-first-model.md). If you prefer to understand the shape of the language before typing, read the *Getting started* and *The language* sections in order. The [Reference](14-reference.md) at the end is for looking things up, not for reading front to back.

Every example in this manual comes from one small but complete model — a **retail shop** with customers, products, categories, orders, and order lines. You can find the whole thing under [`examples/retail/`](examples/retail/) and open it in the editor as you read.
