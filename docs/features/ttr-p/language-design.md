# TTR-P — Language Design & Reference (v1)

> **Status:** forward-looking design manual, written 2026-07-05 against the converged design effort
> ([`design/00-control-room.md`](./design/00-control-room.md) is the decision log / ground truth;
> [`architecture/architecture.md`](./architecture/architecture.md) and
> [`architecture/contracts.md`](./architecture/contracts.md) are the terse synthesis).
>
> This document is different in kind from those three. They record *what was decided and why*. This one describes
> **what TTR-P v1 is when it is finished** — a complete, readable walk-through of the language, its surfaces, its
> model, and its runtime, of the sort you would hand to someone who has to *learn* or *implement* the language. It
> is a picture of the destination, not a log of the journey. Where a claim traces to a specific decision, the
> decision id is cited in parentheses (`C3-b`, `B-T9`, `S15`, …) so the reasoning is one hop away, but you should be
> able to read this front-to-back without opening the log.
>
> Nothing here is implemented yet. This is the specification the implementation will be measured against.

---

## Table of contents

0. [How to read this](#0-how-to-read-this)
1. [What TTR-P is](#1-what-ttr-p-is)
2. [A tour, end to end — the hero program](#2-a-tour-end-to-end--the-hero-program)
3. [The mental model: one program, one graph, many engines](#3-the-mental-model-one-program-one-graph-many-engines)
4. [The canonical language (`.ttrp`)](#4-the-canonical-language-ttrp)
5. [The expression sublanguage](#5-the-expression-sublanguage)
6. [Data-model binding and the world](#6-data-model-binding-and-the-world)
7. [The fragment dialects — TTR-SQL, TTR-pandas, TTR-B](#7-the-fragment-dialects)
8. [The graphical surface](#8-the-graphical-surface)
9. [The compile pipeline](#9-the-compile-pipeline)
10. [Emit and execution](#10-emit-and-execution)
11. [Diagnostics](#11-diagnostics)
12. [Assist and agents](#12-assist-and-agents)
13. [Worked gallery — one program, every surface](#13-worked-gallery--one-program-every-surface)
14. [What is *not* in v1](#14-what-is-not-in-v1)
15. [Appendices](#15-appendices)

---

## 0. How to read this

TTR-P has more than one face — a text language, a set of embedded dialects, a canvas — but they are all descriptions
of **one thing**: a graph of operations that moves and transforms tables. The fastest way to understand the language
is to hold that graph in your head and treat every surface as a different way of drawing it. So the document is
ordered that way. Section 2 shows a whole program so you have something concrete in mind. Section 3 explains the
graph the program compiles to. Sections 4–8 then describe each surface as a projection of that graph. Sections 9–12
follow the graph out the other side, into the compiler and the runtime. Section 13 is the payoff: the same program
written five different ways, all producing the same graph.

Two conventions recur. First, **the hero program** — a single realistic transformation, introduced in section 2 and
reused everywhere — so that every syntax point lands on code you have already seen the shape of. Second, **the two
principles** that the whole design bends toward, cited constantly:

- **P1 — small core, rich edges.** The internal model has as few node kinds as it can. Expressiveness and
  ergonomics live in the surfaces and the transpilers, never in a growing pile of node types. When something feels
  like it should be a new operation, the design's instinct is to make it *sugar* that rewrites into the core.
- **P2 — no miracles.** Everything is either written explicitly or derived deterministically from declared project
  defaults. If it cannot be derived, it is an **error** — never a guess. There is no content-sniffing, no heuristic
  inference, and no LLM anywhere in the compiler. The one place a language model appears (assist, section 12) sits
  *outside* the toolchain and its output is validated by the same deterministic compiler as hand-written code.

If you remember only those two, most of the specific decisions in here will feel inevitable rather than arbitrary.

---

## 1. What TTR-P is

**TTR-P is the processing language of the Tatrman (TTR) family.** Where its sibling **TTR-M** *models* data —
declarative `def <kind> { props }` describing tables, entities, cubes, and their bindings — **TTR-P** *processes* it:
an imperative-dataflow language for programs that read tables, transform them, move them between execution engines,
and write results. TTR-P is not a sixth model kind inside TTR-M; it is a **separate language that references TTR-M
models** the way application code references a schema (A2). The two share a family — the same toolchain, the same
naming and qualified-name mechanics, the same "text is canonical" discipline — but they are different languages for
different jobs.

The defining ambition is in the family backronym: *table transformation manager*. A TTR-P program is written once,
against an abstract description of its surroundings, and can execute across **several engines at once** — a Postgres
database for the relational-heavy work, a Polars process for the file-and-dataframe work — with the compiler
synthesizing the data movement between them. One program, one graph, many engines, **identical results wherever a
computation lands**. That last clause is not a nicety; it is the v1 success criterion (A4) and it forces some of the
sharpest decisions in the language (canonical NULL semantics, the conformance harness).

### 1.1 The shape of the thing

A TTR-P program is a text file (`*.ttrp`). It reads as a series of small operations wired together — `load`, then
`filter`, then `join`, then `aggregate` — grouped into **containers** that each name the engine they run on. The
compiler parses the program into a single directed acyclic **graph** of operation nodes, normalizes that graph until
every piece can run on the engine its container targets, works out what data has to move between engines, and emits a
**bundle**: a directory of runnable SQL and Python scripts plus a bash `run.sh` that orchestrates them. You run the
bundle; it produces tables.

Crucially, the text file is only the *canonical* surface. The very same graph can be drawn on a **canvas** (the
graphical surface), and pieces of it can be written in **embedded dialects** that look like SQL, like a pandas
method-chain, or like controlled sentences (English or Czech). All of them compile to the identical graph. This is what "one
graph, many surfaces" means in practice, and it is the organizing idea of the whole language.

### 1.2 Who it is for

Two personas anchor v1 (A1): the **data engineer** building and operating pipelines, and the **data analyst** doing
ad-hoc transforms. The data scientist is a later audience. The surfaces are pitched accordingly: the canonical text
and the canvas for the engineer's full-control work; the SQL and pandas dialects for analysts who already think in
those idioms; the controlled-English dialect for the analyst who would rather write a sentence than a formula, and as
the target for language-model assistance.

### 1.3 What is in v1, and what is deliberately not

**In v1.** The full canonical language and the graphical canvas as the two complete surfaces; the three fragment
dialects (TTR-SQL, TTR-pandas, TTR-B) as embedded and bare-file container content; references to **relational (`db`)
and entity-relationship (`er`)** model tiers; two data engines, **Postgres and Polars**, orchestrated by **bash**;
synthesized cross-engine movement; a conformance harness proving identical results; and the assist/agent contracts.

**Not in v1** (and each is a conscious deferral, section 14): any write beyond `store` — no arbitrary DML sinks (A3);
streaming and change-data-capture; incremental/refresh execution; user-defined functions; window functions;
`Explode`/`Unnest` and nested types; the `finishes-with` (FF) control edge (F-b); runtime parameters; retries and
resume (F-e); the multidimensional (`md`) model tier and its sugar (D-h); the optimizer (workstream Z); and the
Kantheon orchestrator proper (F). The language is *designed* so these fit later without a redesign — the node model
already carries ports for control and error flow, the world model already distinguishes engine types, the manifest
format already has room for cost — but the machinery for them is v2.

### 1.4 Where it sits relative to Kantheon

Kantheon is a runtime platform; TTR-P is editor-and-compiler tooling. The relationship is strictly one-way and
offline: the TTR-P compiler produces **artifacts**, and a runtime like Kantheon *consumes* those artifacts (G-g). The
compiler never calls Kantheon at compile time and Kantheon never calls the compiler as a service. When a program
targets a Kantheon engine, the compiler emits Kantheon's native plan protobufs into the bundle instead of a SQL
script — but that is a compile-time choice driven by the declared world, not a live conversation. (The single
softening of this rule is assist: an agent may *author* TTR-P source through published toolchain artifacts, C4-e —
but that is still artifact consumption, not a running service.)

---

## 2. A tour, end to end — the hero program

Here is a complete, realistic TTR-P program. It is the **hero scenario** carried through the rest of the manual
(A5), grounded in the retail model that ships with the TTR-M manual (`shop.sales`, `shop.catalog`). Read it once for
shape; every construct in it is explained in later sections.

**The task.** Order lines live in a Postgres database (they are modelled — `shop.sales.db.dbo.ORDER_LINE`). A
nightly `returns.csv` file, produced by another system, lists returned quantities per order line. We want **net
sales per product** — gross revenue minus returns — and we want the products whose gross sales clear a threshold
flagged separately. The returns file is messy, so rows whose quantities do not parse must be diverted to a reject
file rather than crashing the run. The database work should happen *in the database* (SQL), the file work *in
Polars*, and the compiler should move data across the seam by itself.

```ttrp
# net_sales_by_product.ttrp
# One program, two engines: Postgres for the modelled order lines, Polars for the CSV returns.

import shop.sales.*

# --- Postgres island: prep the modelled order lines, in SQL ---
container lines_pg(out lines) target pg {
    load(db.dbo.ORDER_LINE)
        -> calc(gross = QUANTITY * UNIT_PRICE)
        -> select(ORDER_LINE_ID, PRODUCT_ID, QUANTITY, gross)
        -> lines
}

# --- Polars island: read + clean the returns file; bad rows go to a rejects port ---
container returns_polars(out clean, rejects bad) target polars {
    parsed = load("returns.csv", schema: returns_raw)
                -> calc(returned_qty = cast(returned_qty_raw as int))
    parsed -> filter(returned_qty > 0) -> clean
    parsed.rejects -> bad
}

# --- Polars island: join across the engine seam, aggregate, flag the top products ---
container net_sales(in lines, in returns, out top, out rest) target polars {
    joined = join(left: lines, right: returns,
                  kind: left,
                  on: left.ORDER_LINE_ID = right.order_line_id)
    agg = joined
            -> calc(net_qty = QUANTITY - coalesce(returned_qty, 0))
            -> aggregate {
                 group_by:    PRODUCT_ID
                 gross_sales  = sum(gross)
                 units_sold   = sum(net_qty)
               }
    flagged = agg -> branch(gross_sales >= 100000)
    flagged.true  -> top
    flagged.false -> rest
}

# --- program-level wiring: connect the containers, send results to displays ---
lines_pg.lines        -> net_sales.lines      # pg -> polars: movement synthesized here
returns_polars.clean  -> net_sales.returns
net_sales.top         -> display(top_products)
returns_polars.bad    -> display(rejected_returns)
```

A few things to notice before we take them apart:

- **Nothing says how data crosses from Postgres to Polars.** The line `lines_pg.lines -> net_sales.lines` connects an
  out-port of a Postgres container to an in-port of a Polars container. Because the two containers target different
  engines, the compiler *synthesizes* a Store → Transfer → Load across that one edge (C3-d-iv). The author draws a
  wire; the compiler moves the bytes.
- **The engine assignment is explicit and the author's.** Each `container` names its `target`. There is no
  auto-placement in v1 (B-T9 · v1 placement); if you want the join to happen in the database instead, you change one
  `target` keyword and re-compile.
- **The error path is ordinary data flow.** `returns_polars` exposes a `rejects` port carrying the rows whose
  `cast` failed; it is wired to a display exactly like any result. There is no exception machinery — erroneous rows
  are just another output (C3-f).
- **It reads top-to-bottom as a pipeline, but it is a graph.** The `->` chains and the `name = …` assignments are two
  ways of writing edges; the program is the graph they describe, not the order of the lines (C3-a).

Compiled, this program becomes a bundle whose `run.sh` runs `lines_pg` (a `psql` script) in the first wave, then —
once its output has been transferred to Polars — runs `returns_polars` and `net_sales` (Python scripts), then drops
two Arrow files, `top_products.arrow` and `rejected_returns.arrow`, into `out/`. Run it against Postgres and against
a Polars-only placement and, by construction (section 10.5), the numbers match.

That is the whole language in miniature. The rest of the manual is detail.

---

## 3. The mental model: one program, one graph, many engines

Everything in TTR-P is a projection of a single internal object: an **acyclic graph of operation nodes** (B-T4). One
document is one program is one graph. The surfaces differ in how they let you draw that graph; the compiler and
runtime differ in what they do with it; but the graph itself is the invariant. This section describes it directly,
because once you can see it, the surfaces stop being separate languages to learn and become notations for the same
picture.

### 3.1 Nodes, ports, edges

A **node** is one operation — a `Filter`, a `Join`, a `Load`. Nodes do not connect to each other directly; they
connect through **ports** (B-T2). A port is a named, typed attachment point on a node, and it is one of two kinds:

- a **data port**, across which a table (a definite set of columns with definite types) flows, or
- a **control port**, across which a *signal* flows — "I have started", "I have finished", "I have failed".

An **edge** merely joins an out-port to an in-port. It carries no meaning of its own; the meaning lives in the ports
it connects. This is why the language has no separate "control-flow node" or "error node" — control and error are
*port kinds*, not node kinds. A `Branch` is a real node with two data out-ports; a finish-to-start dependency is a
wire between two control ports.

Ports follow a few firm rules that shape the surfaces:

- **Every node has a default port**, named `out` for its primary output and `in` for its primary input. Textual
  surfaces omit the name in the common case and only name a port when routing somewhere non-default (`flagged.true`,
  `parsed.rejects`). In the graphical surface, port names barely matter — you see the wire.
- **A data in-port takes exactly one edge.** There is no implicit union: if you want to combine two inputs, you use
  an explicit `Union` node (B-T2). This keeps "what feeds this?" a question with one answer.
- **A data out-port may fan out** (multicast): one output can feed many downstream nodes.
- **Two error ports are reserved on every node** (C3-f): `err`, a control-shaped *signal* that the node failed, and
  `rejects`, a data-shaped stream of the *rows* that failed. A node can expose either or both; leaving them
  unconnected means "fail the whole run if this happens" (fail-fast is the default, and eliding the wire is legal
  under P2 because the default is explicit and known).

The reserved port names — `in`, `out`, `err`, `rejects`, `true`, `false`, `else` — are lowercase and share the
lexical rules of column names (S10).

### 3.2 The node set is small, and uniform

There is **one** set of node kinds (P1), and it is deliberately short. The value-transform nodes are the relational
core plus a few dataframe-flavored extras:

`Project` (choose / rename / compute / drop columns, carrying expressions) — with two pieces of authoring sugar,
`Select` (select + rename) and `Calc` (add computed columns); `Filter` (row predicate, one output); `Branch` and
`Switch` (row-routing splits); `Join` (including semi- and anti-join kinds); `Aggregate` (group-by with aggregate
calls; `HAVING` is sugar for Aggregate + Filter); `Sort`; `Union`, `Intersect`, `Except`; `Values` (an inline
literal table); `Limit`; `Pivot` (with statically declared pivot values); and `Distinct` (sugar for a grouping
Aggregate). Window functions are **not** in v1.

Alongside them are the movement / IO nodes (B-T9): `Load` (physical storage → engine memory), `Store` (engine memory
→ physical storage — this is the engine's "write"), `Transfer` (physical → physical, possibly converting format),
and `Index`. Two more are structural: the `Container` (a group of nodes that acts as a function and bears an
execution target — section 3.4), and `Display` (a sink-only leaf that surfaces a result). `Materialize` exists as a
*macro*, not surface syntax — it rewrites into Store + (Index) + Load (S13, B-T9).

That is the entire vocabulary. Anything a surface offers beyond it — SQL's `HAVING`, a pandas `.assign`, an English
"keep only" — is sugar that decomposes into these nodes (section 7). There is no dialect-specific node kind anywhere
in the model; the graph looks the same no matter which surface authored it (C2-a).

### 3.3 Primitive is relative to the engine

Here is the idea that makes a small node set workable across very different engines. **No node is absolutely
primitive.** A node is *primitive for an engine that runs it natively* and *a macro for an engine that must rewrite
it into supported nodes* (B-T10). `Branch` is primitive for a streaming/Alteryx-style engine that routes rows
directly, but a macro for SQL, where it lowers to a pair of complementary `Filter`s. `Pivot` is native in some SQL
dialects and a `CASE` construction in others.

So "what can this engine do?" is answered by a **capability manifest** per engine (B-T6) — which node kinds it runs
natively, with what parameters (which join kinds, which aggregate functions, whether `Pivot` is native), and which
expression functions it has. Compilation's first job (section 9) is **normalization**: rewrite the authored graph
into an equivalent graph that uses only what the target engine supports. Two kinds of rewrite do this work:

- **authoring sugar** — engine-independent, always expanded (`Select`/`Calc` → `Project`, `HAVING` →
  Aggregate + Filter, `Distinct` → Aggregate); and
- **capability lowering** — engine-relative, applied only when the target lacks native support (`Branch` → `Filter`
  for SQL, `Pivot` → `CASE` for a dialect without native pivot).

The "supported everywhere" set is just the *intersection* of every engine's native capabilities (roughly the
relational core), not a privileged tier of the language.

### 3.4 Containers, targets, and the physicality spectrum

A **container** groups nodes into a unit with its own ports, which map onto the ports of the nodes inside it
(B-T9). A container has no processing meaning of its own — it is pure encapsulation, a function. What it *does* carry,
in v1, is the **execution target**: the engine its contents run on. `container lines_pg(...) target pg` says "run
everything in here on the `pg` engine."

There is **no separate logical and physical program**. TTR-P rejected that split (Bora tried it; too many graph
layers). Instead, operations sit on a **physicality spectrum** — "load this CSV" is physical with respect to the
file but logical with respect to *which* engine loads it — and normalization is an iterative march: *rewrite
less-physical operations into more-physical ones until the question "can this engine process this graph now?" is
true* (B-T9, T8). Targets live on containers, so the transform operations themselves stay engine-agnostic; only the
container commits to an engine.

This is also the whole story of multi-engine execution. When a data edge crosses from a container targeting one
engine to a container targeting another, the graph *cannot* run as drawn — one engine cannot read the other's
memory. Normalization resolves this by rewriting that one edge into **Store + Transfer + Load** (C3-d-iv): store the
producer's output to physical storage, transfer it (converting format if needed) to storage the consumer can reach,
load it into the consumer's engine. In the hero program, the single wire `lines_pg.lines -> net_sales.lines` becomes
exactly this three-node bridge, with the staging location chosen from the world's declared staging area (section 6).

### 3.5 The execution graph is derived, not authored

Collapse every container down to a single node and you get a second graph: the **execution (orchestration) graph**,
whose nodes are the containers (now opaque tasks) and whose edges are the transfers and control dependencies between
them (B-T6). This is what actually gets scheduled at run time — which island runs when, what moves between them.

The essential point is that you **do not author this graph** in v1; it is *derived* by container-collapse from the
one graph you did author. There is one program, one authored graph; the orchestration layer falls out of it. (Being
able to author orchestration directly — pure task graphs with no data flow — is a v2 concern, and it is why the
model already distinguishes *data engines* from *execution engines*, section 6.4.)

### 3.6 Variables are names for edges

TTR-P has variables — `parsed = load(...)`, `agg = joined -> aggregate {...}` — but they are not a separate binding
construct with scopes and a symbol table. **A variable is sugar for a name on an edge** (Q7-γ). Assigning
`agg = <expression>` labels the out-edge that expression produces; writing `agg` later refers to that edge. The model
stays pure dataflow underneath.

Two consequences matter. First, **reassignment is allowed** — `x = filter(x, ...)` is legal — and is desugared
**SSA-style**: each assignment mints a fresh instance, so the name `x` at the second use refers to a different edge
than at the first. This is what lets the pandas-style surface feel natural without introducing mutable state into the
model. Second, because a variable names the edge an operation produced, it also serves as a **handle to that
operation's other ports**: `flagged.true` and `flagged.false` reach the two outputs of the `branch` named `flagged`;
`parsed.rejects` reaches the reject stream of the node named `parsed`. A variable names data, never a container and
never a callable — there are no higher-order constructs in v1 (B-T4).

These SSA names are not throwaway. They survive all the way through the compiler: an SSA name becomes a **CTE name**
in emitted SQL (section 10.1), a **node key** in the graphical view-state sidecar (section 8), and a stable label in
diffs. One naming story runs across text, canvas, and generated code (C1-c).

### 3.7 Everything is statically typed and statically shaped

Every data port has a **fully static schema** — a known set of columns with known types, checkable at author time
(B-T7). There is no schema-on-read: if a `Load` reads an ad-hoc file, that file's schema must be *declared* (in the
world, in the program, or inline; section 6.3). The single exception is a program's **outputs** — a `Display` may
have a dynamically-shaped result, because nothing downstream consumes it (B-T7 sweep). `Pivot` does not break this
rule: its pivot values are declared, so its output schema is known statically (B-T10).

Static shape is what makes author-time validation possible everywhere, makes each node's emit straightforward (every
node knows its own schema), and connects cleanly to the runtime contract — the Arrow schema fingerprint that the
conformance harness compares against (section 10.5).

---

## 4. The canonical language (`.ttrp`)

The canonical surface is a text language with the extension `.ttrp`. It is **full-coverage** — anything the internal
graph can express, this language can write — and it is the **file format**: the graphical canvas and the fragment
dialects are ultimately projections of, or content within, canonical `.ttrp` programs. If you learn one surface,
learn this one.

### 4.1 A program's shape

A `.ttrp` file has, in order: an optional world pin, zero or more imports, and then a body of statements —
program-level operation chains, assignments, container definitions, and control declarations.

```ttrp
uses world "acme.worlds.dev"   # optional: pin/override the world (else from the project manifest)

import shop.sales.*            # zero or more imports of model objects and world names

# … statements: chains, assignments, containers, control edges …
```

There is **no `program` header and no program name inside the file** — a program's identity is its filename (S12,
P2). `net_sales_by_product.ttrp` *is* the program "net_sales_by_product". Whitespace and line breaks are
insignificant, as in TTR-M; the multi-line and compact forms parse identically. Comments start with `#` and run to
end of line.

### 4.2 Statements: chains and assignments

TTR-P's statement form is a **hybrid** of two shapes, freely mixed (C3-a). Both describe edges in the graph; the
difference is only in reading flow.

A **chain** writes a run of operations connected head-to-tail with the connector token `->`:

```ttrp
load(db.dbo.ORDER_LINE) -> filter(QUANTITY > 0) -> select(ORDER_LINE_ID, PRODUCT_ID)
```

An **assignment** names an edge so it can be referred to later, at a fan-out or a reassignment or a multi-output
node:

```ttrp
base    = load(db.dbo.ORDER_LINE) -> filter(QUANTITY > 0)
lines   = base -> select(ORDER_LINE_ID, PRODUCT_ID, QUANTITY)
flagged = lines -> branch(QUANTITY >= 100)
```

The two mix freely: a chain may appear in source position of an assignment, and an assignment's name may be the head
of a chain. The **formatter** carries the house style — it keeps linear runs as chains and introduces a name at each
fan-out, reassignment, or multi-output point — so that "which form do I use?" is a formatting question, not a
semantic one. The `->` token is the single connector: it serves statement chains, program-level container wiring
(`lines_pg.lines -> net_sales.lines`), and the explicit edge form alike (C3-b).

Composition rules worth stating once (C3-a-iv):

- **Precedence** is `=` (assignment) looser than `->` (chain) looser than a call. So
  `x = load(f) -> filter(p)` binds `x` to the whole chain.
- **Chains are legal in source position** — `join(left: load(f) -> filter(p), right: g)` parses — though the
  formatter discourages deep nesting in favor of a named intermediate.
- **Expression scope is input columns only.** The expressions inside an op (`filter(amount > 0)`) see the columns of
  that op's input and nothing else. Variables are never resolvable inside op expressions — there is no correlation, no
  reaching into another edge from within a predicate.
- **Multi-input ops qualify columns by port name** — `left.ORDER_LINE_ID`, `right.order_line_id` — so a join
  condition is unambiguous about which side a column comes from.

### 4.3 Operator arguments: inline and blocks

Most operators take **named arguments** in parentheses:

```ttrp
filter(amount > 0)
join(left: a, right: b, kind: inner, on: left.id = right.id)
limit(10)
```

Wide operators — `aggregate`, `pivot`, `switch`, multi-key `join` — may instead take a **config block** in braces,
which reads far better than a long parenthesized argument list (C3-a-iii):

```ttrp
aggregate {
    group_by:    PRODUCT_ID
    gross_sales  = sum(gross)
    units_sold   = sum(net_qty)
}
```

Blocks compose inside chains exactly like calls: `joined -> aggregate { … } -> sort(gross_sales, dir: desc)`. The
formatter chooses inline-args vs block per operator width, so trivial ops stay one-liners and heavy ops get room.

### 4.4 The operators

This is the surface spelling of the node set from section 3.2. Every operator here is a node kind or a piece of
authoring sugar over one.

**`load`** — bring a table into the container's engine. Its argument is either a **model object** (a qualified name
resolving to a modelled table or entity) or an **external source** (a quoted path/name, which then requires a
declared `schema:`):

```ttrp
load(db.dbo.ORDER_LINE)                       # a modelled Postgres table
load("returns.csv", schema: returns_raw)      # an ad-hoc file, schema declared (section 6.3)
```

**`select`** — choose and rename columns (sugar over `Project`). **`calc`** — add or replace computed columns (sugar
over `Project`), each written `name = <expression>`:

```ttrp
select(ORDER_LINE_ID, PRODUCT_ID, gross)
select(ORDER_LINE_ID, price = UNIT_PRICE)     # rename in flight
calc(gross = QUANTITY * UNIT_PRICE, net = gross - discount)
```

**`filter`** — keep only rows satisfying a predicate; one output (true SQL-style filter, not a splitter):

```ttrp
filter(status = "active" and QUANTITY > 0)
```

**`branch`** — split the input into two partitions on a predicate; outputs `true` and `false`. This is the
Alteryx-style "filter" as a first-class node. Reach the outputs by port:

```ttrp
flagged = rows -> branch(gross_sales >= 100000)
flagged.true  -> top
flagged.false -> rest
```

**`switch`** — multi-way row routing, each output with its own condition, plus an optional `else`. Two modes:
non-overlapping (each row to exactly one output) or overlapping (a row flows to every output whose condition it
matches):

```ttrp
routed = orders -> switch {
    domestic:      country = "CZ"
    eu:            country in ("DE", "FR", "AT")
    else:          rest
}
routed.domestic -> …
```

**`join`** — combine two (or more) inputs on a condition; inputs are **named** (`left:`, `right:`) — never
positional (C3-c) — and `kind` selects the join type (`inner`, `left`, `right`, `full`, `semi`, `anti`, `cross`):

```ttrp
join(left: lines, right: returns, kind: left,
     on: left.ORDER_LINE_ID = right.order_line_id)
```

**`aggregate`** — group and summarize; usually written as a block with a `group_by` and one `name = agg(...)` per
output measure. `HAVING` is written as a `filter` *after* the aggregate (the sugar expands to Aggregate + Filter):

```ttrp
by_product = lines -> aggregate {
    group_by:    PRODUCT_ID
    gross_sales  = sum(gross)
    n_lines      = count()
}
big = by_product -> filter(gross_sales >= 100000)   # this is HAVING
```

**`sort`** — order rows; each key optionally `dir: asc|desc`. Every emitted sort places nulls explicitly (section
10.1). **`limit`** — cap rows, optional `offset`; note that `limit` (and `offset`) **require an ordered input** — a
`limit` on unordered data is a compile error, because "first N of an unordered set" is not reproducible across
engines (S15, forced by A4):

```ttrp
top10 = by_product -> sort(gross_sales, dir: desc) -> limit(10)
```

**`union`, `intersect`, `except`** — set operations. `union` takes a **list** of inputs, `union(a, b, c)` — there is
no named-port form for it (S11). `intersect` and `except` are real, engine-relative nodes. **`distinct`** is sugar
that expands to a grouping `aggregate`. **`values`** is an inline literal table. **`pivot`** turns declared row values
into columns; its pivot values are listed statically so the output schema stays known:

```ttrp
by_month = sales -> pivot {
    on:      month
    values:  ["jan", "feb", "mar"]
    measure: sum(amount)
}
```

**Movement operators** — `load` and `store` are authored directly for sources and sinks; `store` writes an in-memory
table out to a physical location. The *engine-crossing* movement (Store + Transfer + Load) is **not** normally
authored — it is synthesized on cross-engine edges (section 4.7). You reach for explicit `store`/`transfer` only to
control staging or indexing (S14).

### 4.5 Containers

A **container** groups operations, declares ports, and names an execution target. Its body references only its own
declared ports — containers are **closed functions**; all wiring *between* containers happens at program level
(C3-d-iii). This makes each container map one-to-one onto a node in the graphical orchestration view.

```ttrp
container lines_pg(out lines) target pg {
    load(db.dbo.ORDER_LINE)
        -> calc(gross = QUANTITY * UNIT_PRICE)
        -> select(ORDER_LINE_ID, PRODUCT_ID, QUANTITY, gross)
        -> lines
}
```

The header lists ports as `<kind> <name>`, where kind is `in`, `out`, `err`, or `rejects`, and name is an
identifier:

```ttrp
container returns_polars(out clean, rejects bad) target polars { … }
container net_sales(in lines, in returns, out top, out rest) target polars { … }
```

Inside the body, a port name used as the tail of a chain wires that chain into the port (`… -> lines`); a port name
used as a head reads from it (`lines -> …`). When a container has a single default output you may simply call the
port `out` and the single default input `in`.

A container's body may be written in canonical TTR-P (as above) **or** as a single embedded fragment in one of the
dialects (section 7):

```ttrp
container lines_pg(out lines) target pg """sql
    WITH prepped AS (
        SELECT ORDER_LINE_ID, PRODUCT_ID, QUANTITY,
               QUANTITY * UNIT_PRICE AS gross
        FROM   db.dbo.ORDER_LINE
    )
    SELECT * FROM prepped
"""
```

Containers do **not** nest arbitrarily in v1 — a fragment is leaf content (no containers inside a fragment, C2-a),
and canonical containers hold operations, not other containers. (Nested/authorable orchestration is v2.)

### 4.6 Control edges

Data edges already imply ordering: if B consumes A's output, A runs first. Control edges are for the **non-data
dependencies** — "run B after A even though B does not read A", the truncate-before-load kind of ordering. They are
written as **keywords**, either top-level or grouped in an optional `control { }` block (C3-e):

```ttrp
b after a               # finish-to-start (FS): a must finish before b starts
a with b                # start-to-start (SS): start them together
```

`after` (FS) is the workhorse; `with` (SS) expresses positive co-start. The third historical dependency,
`finishes with` (FF, finish-to-finish), is **reserved in the grammar but not available in v1** — a program that uses
it gets a capability error (`TTRP-CTL-001`). It returns with the real orchestrator in v2 (F-b). A control edge
constrains its *effect* hard: the compiler may rewrite the surrounding graph freely but may never drop the ordering
the edge specifies (B-T2).

### 4.7 Movement is synthesized

The hero's defining move — data crossing from Postgres to Polars — is written as a plain wire:

```ttrp
lines_pg.lines -> net_sales.lines
```

Because `lines_pg` targets `pg` and `net_sales` targets `polars`, this single edge cannot execute as drawn. The
compiler rewrites it, during normalization, into **Store + Transfer + Load** (C3-d-iv): store `lines_pg`'s output to
a staging location, transfer it there (as Arrow — the staging format everywhere, section 10.4), and load it into the
Polars process. The **staging location** is chosen deterministically from the world's declared staging area, and the
compiler *verifies* that both engines can reach it — if they cannot, that is a compile error, not a silent failure
(D-f). You can override the staging choice per edge with `via <storage>`, and you can drop to explicit
`store`/`transfer`/`load` when you need to control indexing or the exact staging path (S14). But for the common case,
you draw a wire and the movement appears.

### 4.8 Error ports and the reject path

Every node has an `err` port (a control-shaped failure signal) and a `rejects` port (the data-shaped rows that
failed). In canonical text you tap them by port name off the node's variable:

```ttrp
parsed = load("returns.csv", schema: returns_raw)
            -> calc(returned_qty = cast(returned_qty_raw as int))
parsed -> filter(returned_qty > 0) -> clean
parsed.rejects -> bad          # rows whose cast failed
```

The mode is visible in the text: `.rejects` is data (you wire it onward to a display or store), `.err` is a signal.
Leaving a node's error ports unconnected means **fail-fast** — the run aborts if that failure happens (the default,
elidable under P2 because it is explicit and known). One v1 restriction: a cross-*container* `err` signal is a
compile error (F-d-i) — orchestration-level error handling (on-failure islands, retries) is v2. The data-shaped
`rejects` path, by contrast, crosses engines like any other data (it becomes synthesized movement if it has to), and
that is where the hero's error path lives.

> **Implemented (2026-07-19).** The producer semantics above are now real, not aspirational. The reject-capable
> sites are catalogue-defined (`cast`, `op.div`, datetime parses; canonical validity per `ttrp/validity/*.yaml`); a
> wired `rejects` port elaborates into a guard-and-branch that splits the clean rows from the typed erroneous rows,
> emitted natively per engine (SQL: a guard CTE + first-error CASE ladder; Polars: mask-and-split). Fail-fast (an
> unwired `rejects`) stays byte-identical to pre-feature. Live-sealed across PostgreSQL and Polars; see
> `design/rejects/` (control room + detailed design) for the full arc.

### 4.9 Display

`display` is a sink-only leaf that surfaces a result (Q11):

```ttrp
net_sales.top -> display(top_products)
```

A `display` may have a dynamically-shaped result (it is the one place dynamic schema is allowed, section 3.7); a
program may have several, each named (`display(name)`; a single unnamed `display` is allowed); and a **bare
program's final result flows to a display by default**. At run time a display lands as an Arrow file in the bundle's
`out/` directory, which the graphical Designer watches and renders (section 10).

**Action displays (grammar 0.14).** Some display names are *actions* a host hands on to another system, each with a
fixed row shape. The shape is a TTR-M **`def schema`** in a model package (e.g. generated from the host's action
catalogue into an `extra-model-roots` root):

```ttrm
package shop.actions

def schema notify {
    description: "Send a notification to a recipient",
    columns: [
        def column recipient { type: text },
        def column subject   { type: text },
        def column order_id  { type: int },
        def column amount    { type: decimal, optional: true },
        def column note      { type: text,    optional: true },
    ]
}
```

A program that imports the package (`import shop.actions.*`, scoped like entities) and wires rows into
`display(notify)` has those rows held to the schema: a missing non-optional column is `TTRP-DSP-001`, a column whose
type is not assignable is `TTRP-DSP-002` (assignable = the same type; an integer → any integer, decimal or float;
`datetime` ⇄ `timestamp`; any scalar → text, since the host renders action fields as text), and a column the schema
does not name is dropped with the warning `TTRP-DSP-003`. A schema column must be a scalar an engine can produce — the
TTR-M type keywords (not `object` / `list`) and the SQL spellings a generated model carries (`bigint`, `smallint`,
`numeric`, `money`, `real`, `bit`, `time`, `datetime2`, …); anything else is `TTRP-DSP-005`. The display then carries
exactly the schema's columns, in schema order, each cast to the type the schema declares — so every source of the
display has one row type and the host can concatenate them; an absent optional column is a NULL of that type. Unlike
an ordinary display, an action display may take **several sources** (e.g. two branches that
each produce `notify` rows); the bundle lists one `displays[]` entry per source, in wiring order, each with its own
source and its own file — the host concatenates their rows in that order. An ordinary display name with several
sources is ambiguous: `TTRP-DSP-004`.

```ttrp
import shop.actions.*

review.late  -> display(notify)     // both feed the one `notify` action
review.large -> display(notify)
review.late  -> display(late_orders) // an ordinary (evidence) display — any shape
```

---

## 5. The expression sublanguage

Inside the operators live **expressions** — the predicate in a `filter`, the formulas in a `calc`, the condition in
a `join`, the calls in an `aggregate`. There is exactly **one** expression language across the entire family (T5-e).
The SQL dialect's `WHERE amount > 0`, the pandas dialect's `.filter(amount > 0)`, and the English dialect's
`where the amount is more than 0` all parse to the same expression tree. This is what lets a program authored in one
surface compile to a graph identical to the same program authored in another.

### 5.1 It is a tree, not a string

An expression is always parsed into a **structured tree** — never held as an opaque string and never passed through
as raw target-dialect SQL (T5-e). This is non-negotiable, because everything downstream needs the structure:
author-time type-checking, capability rewriting, round-tripping to the canvas, and lowering the *same* expression to
a different engine all require the tree. A surface may accept a pasted string as a convenience, but the parser
immediately lifts it into the one canonical expression tree; the string is not the artifact. In particular, you
cannot inline raw Postgres or MSSQL SQL into an expression and expect it to pass through — it would be
untypecheckable, unrewritable, and unportable, so it is rejected.

The tree is TTR-P's own IR, a structural twin of Kantheon's `plan.v1.Expression` that lowers to it at the SQL-emit
boundary (T5-a, T3). Its arms are literals, column references, function calls, an explicit cast, and a distinct
aggregate-call arm (below).

### 5.2 What you can write

The concrete syntax is conventional:

- **Literals** — numbers (`42`, `3.14`, `1e-5`), strings (`"active"`), booleans (`true`/`false`), and `null`.
- **Column references** — bare column names (`amount`, `PRODUCT_ID`), port-qualified where a node has multiple
  inputs (`left.id`, `right.id`).
- **Operators** — arithmetic (`+ - * / %`), comparison (`= <> < <= > >=`), logical (`and or not`), and the SQL-family
  set/null tests (`in (...)`, `between a and b`, `is null`, `is not null`, `like`). Standard precedence; parentheses
  to group.
- **Function calls** — `coalesce(x, 0)`, `upper(name)`, `substring(code, 1, 3)`. Functions are resolved against a
  **catalogue by id** (section 5.4), not by free-text name.
- **Casts** — explicit only: `cast(returned_qty_raw as int)`, `cast(amount as decimal(12,2))`. There is no implicit
  coercion in the IR (T5-d) — the core stays engine-agnostic, and each engine's codegen knows only its own cast
  syntax. Authoring sugar may *insert* a cast for you (e.g. int→float in a mixed expression), but the IR always
  carries it explicitly.
- **Aggregate calls** — `sum(x)`, `count()`, `avg(x)`, `min`/`max`, with an optional `distinct`. Aggregate calls are
  a **separate arm** of the expression IR (matching Calcite's scalar/aggregate split), legal only in an `aggregate`
  operator's measure position (T5 sweep) — you cannot put a `sum()` in a `filter`.

Not in the v1 expression language: **subquery-valued expressions** (`x in (SELECT …)`, scalar subqueries, `EXISTS`)
— these are covered at node level by semi/anti `join`, and the SQL dialect desugars its subquery forms into those
joins (T5 sweep, C2-b). **Window/analytic expressions** are v2. **Runtime parameters** are v2 — parameters in v1 are
compile-time only: declared, defaulted, and substituted at compile time, with no runtime bind (T5 sweep).

### 5.3 One equality, and NULL means SQL

`=` is the **one** equality operator, disambiguated by context (it is assignment in `name = expr` positions and
comparison inside expressions). `==` is **rejected** with the diagnostic `TTRP-EQ-001` ("use `=`") — with a single
exception: inside the TTR-pandas dialect, `==` is accepted as a closed dialect synonym for `=`, because pandas
authors' fingers type it (S9).

NULL semantics are **canonical SQL three-valued logic**, everywhere, on every engine (T5-d, forced by A4). `NULL = NULL`
is `NULL`, not true; unknowns propagate. This is a hard correctness constraint, not a preference: "identical results
across engines" is impossible if NULL means different things on Postgres and Polars. Engines that do not natively
match (pandas' `NaN`, Polars' null-vs-NaN) get **enforcing codegen** — a generated prelude that makes their behavior
match SQL 3VL (section 10.2). This is one reason Polars is the v1 dataframe engine and pandas is deferred: Polars has
real null semantics and gets close for free, while pandas would need far more enforcement scaffolding.

### 5.4 The function catalogue

Functions are resolved against a **typed catalogue**, referenced by stable ids rather than by whatever a dialect
happens to call them (T5-c). The catalogue is the single place that knows a function's parameter types, result type,
and semantics; the type-checker and the rewriter both consume it through one interface. TTR-P's general functions
(arithmetic, comparison, logical, string, conditional/`CASE`, the aggregates) live in this catalogue; the TTR-M
**MD calc catalogue** (`truncToMonth`, `monthOfDate`, …) is referenced through the same interface rather than
absorbed, keeping its cross-repo ownership intact. (Its full use is gated on the deferred MD-sugar work, section 14,
but the interface seat is reserved.)

Function *support* is engine-relative, exactly like node support (section 3.3): a function is primitive on an engine
that has it and a macro on one that must rewrite it (`truncToMonth` → `DATE_TRUNC` on Postgres, a `.dt` call on
Polars). A function with no rewrite into the container's engine triggers the same escalation as an unsupported node —
the whole node re-places to a capable engine (section 9.3) — never a mid-expression engine split, which would be a
nasty seam (T5-b). Typing is static and starts coarse (`text | int | float | bool | datetime`), with room to layer
refinement types (`int{1..12}`) later.

---

## 6. Data-model binding and the world

A TTR-P program transforms tables, and those tables come from somewhere: some are **modelled** (they have a TTR-M
definition — a `db` table, an `er` entity), some are **ad-hoc** (a CSV with a declared schema). And the program runs
somewhere: on named engines, moving data through named storage. Both of those — *what exists* and *where it runs* —
are described to the compiler at compile time, offline, by two things: **model references** and the **world**.

### 6.1 Referencing model objects

TTR-P names model objects with the **same qualified-name and import mechanism as TTR-M** (D-b): a model object is a
dotted path, and `import` brings a package's names into scope so you can use short forms.

```ttrp
import shop.sales.*

load(db.dbo.ORDER_LINE)        # short, thanks to the import
load(shop.sales.db.dbo.ORDER_LINE)   # the full qname, always unambiguous
```

References are **position-typed** (D-b): each syntactic position knows what *kind* of name belongs there, and checks
it. The name after `load` must resolve to a storage source or a model object; the name after `target` must be an
engine; a `schema:` must be a schema. This gives precise errors — "expected an engine here, got a storage" — instead
of a vague late collision.

The **ref kind is derived from the package path**, not from new syntax (D-a sub-1). A full qname is unambiguous by
construction: `shop.sales.db.dbo.ORDER_LINE` is a physical `db` table; `shop.sales.er.entity.order` is a logical `er`
entity. Short forms are just import sugar over those paths. There is no `table`/`entity` keyword marking a reference —
the path already says which tier it is.

### 6.2 Two model tiers: `db` and `er`

v1 references **two** model tiers (D-a). The **`db`** tier is physical: tables and columns, named as
`db.<schema>.<table>.<column>`, mapping straight to what the engine sees. The **`er`** tier is logical: entities,
attributes, and relations, named as `er.entity.<name>`. Referencing an `er` object is the analyst-friendly move —
you write in the vocabulary of the domain model, and the compiler rewrites it to physical `db` references early,
*keeping provenance*, so diagnostics and the canvas can still show you the logical name you wrote (E-d):

```ttrp
# er-flavored: reference the entity and let the modelled relation carry the join condition
join(left: lines, right: orders, kind: inner, on: relation order_of_line)
```

The v1 `er` semantics are **names plus relation-joins** (D-a sub-2): attribute→column mapping via the model's er→db
binding, and `on: relation <name>` to reference a modelled join condition rather than spelling out the key equality.
The full logical layer (cardinality checks and the like) is v2. The **`md`** (multidimensional) tier is reserved but
not shipped in v1 — its reference slot is named and its ref-kind machinery is extensible, so it arrives later as a
package path, not a language change (D-h, section 14).

### 6.3 Declared schemas for ad-hoc data

Anything not modelled needs a **declared schema** — schema-on-read is banned (B-T7). A schema can live in three
places, resolved in a fixed precedence (D-c): **inline** in the program, a **named definition** in the program, or
**declared in the world** beside the storage. Inline beats program-named beats world-declared; a same-level conflict
is an error (P2). Schema types are the TTR-M `db`-schema attribute types, verbatim (S23):

```ttrp
# a named schema def in the program
schema returns_raw {
    order_line_id:    int
    returned_qty_raw: text
    reason:           text
}

load("returns.csv", schema: returns_raw)
```

(Separately, a TTR-M **`def schema`** in an imported package declares the row shape of an *action display*, section
4.9 — a sink-side shape, not a load schema.)

The `returned_qty_raw` column is declared `text` on purpose — the file is messy, so the program reads the raw string
and does the `cast` itself, routing failures to `rejects` (section 4.8). That is the P2-clean way to handle dirty
input: declare what is really there, convert explicitly, divert what does not convert.

### 6.4 The world

The **world** is a compile-time description of the program's surroundings: which engines exist, which executors run
tasks, which storage persists data, what each can do, and how they connect. It is the single most important
compile-time input after the program itself, and it is modelled as a **new TTR-M schema kind** — `schema world` —
so it is an ordinary model file, parsed by the same toolchain, viewable in the Designer for free (D-d):

```ttrm
# acme/worlds/dev.ttrm  (a model file, in the model repo)
schema world

def world dev {
    engines:   [pg, polars],
    executors: [bash],
    storages:  [shop_pg, files, stage],
}

def engine pg      { extends: "postgres-16" }
def engine polars  { extends: "polars" }
def executor bash  { extends: "bash" }

def storage shop_pg { kind: postgres, hosts: [shop] }   # this storage hosts the shop model package
def storage files   { kind: local_file }
def storage stage   { kind: local_file, staging: true } # the default staging area for movement
```

Several ideas are load-bearing here:

- **The world is a *compile target*** (B-T6). TTR-P compiles *against* a declared world the way a C compiler targets
  an architecture ("compile to x86"). At run time, the concrete environment must *verify its compatibility* with the
  declared world — runtime capability advertisement is verification, never the compile-time source of truth. This is
  what lets the compiler work entirely offline.
- **A `def engine`/`def executor`/`def storage` instance `extends` a type manifest** (B-T6-b). `postgres-16`,
  `polars`, `bash` are **engine-type manifests** that ship with the compiler and describe the stable facts — which
  nodes and functions the engine runs natively, which control vocabulary an executor supports. The instance in the
  world adds environment-specific deltas (a Postgres box with an extension that adds functions; which storages it can
  reach). Type facts × instance overlay are orthogonal axes.
- **Storage hosts models** (D-d-i). `def storage shop_pg { hosts: [shop] }` says the `shop` model package lives in
  this Postgres. The world is the one place environment truth lives, so the same model can attach to a Postgres in
  the dev world and a different one in prod — that variance is exactly what worlds are for.
- **One storage is the staging area** (`staging: true`, D-f). Synthesized movement stages through it; the compiler
  verifies both sides of a cross-engine edge can reach it (via the world's read/write/move relations) or raises a
  compile error. A per-edge `via <storage>` overrides the default.

Engines come in kinds (B-T6). **Data engines** (Postgres, Polars) process data — their manifests declare nodes,
functions, and read/write relations. **Execution engines** (bash in v1; Airflow-class orchestrators and Kantheon
later) run *tasks* — their manifests declare the control vocabulary they support (FS, SS), parallelism, and
**invocation capabilities**: "can run a psql script", "can run python3". The compiler picks an **invocation binding**
per container from the (data engine, executor) manifest pair — the same Postgres island can be delivered as an inline
`psql` script or as a REST call to a Kantheon worker, decided by the manifests (section 10).

### 6.5 The project manifest

One more input ties it together: the **project manifest**, the `modeler.toml`-equivalent found by walking up from
the program (the same resolution as TTR-M). TTR-P's settings live in a `[ttrp]` table (S5). There are no user
dotfiles in the chain — the manifest and the document are the only sources, keeping resolution deterministic (P2):

```toml
[ttrp]
world             = "acme.worlds.dev"    # required unless every program pins its own
bare-target       = "pg"                 # engine for a bare-fragment program's synthesized container
bare-shell        = "bash"               # executor for bare-fragment programs
split-policy      = "warn"               # capability-escalation policy: warn | error
display-default   = "arrow"              # bare program's final-result sink format
staging           = ""                   # only if the world does not declare staging: true
rls-egress        = "warn"               # cross-RLS-boundary egress: warn | error
assist-provenance = "none"               # none | comment
default-imports   = ["shop.*"]           # implicit prelude for BARE fragment programs only
```

The world is selected by the `[ttrp] world` key, overridable per program by a `uses world "…"` pin (precedence:
document pin > project default > error, C3-h).

---

## 7. The fragment dialects

Not everyone wants to write the canonical flow-DSL, and some transformations read more naturally in a familiar
idiom. TTR-P answers this with **fragment dialects**: alternative notations for the *content of a container*. A
fragment is not a separate language with its own program structure — it expresses one data-flow island, never
containers or program wiring (C0-γ). There are three: **TTR-SQL**, **TTR-pandas**, and **TTR-B** (controlled
English). All three share one regime (C2, C4-a):

- they **decompose fully into the standard node set** — a fragment produces the same graph a canonical container
  would, so there is no dialect-specific node kind anywhere (C2-a);
- **document scope flows in** — imports and in-ports are visible inside a fragment, resolved in the order in-ports >
  document imports > full qnames, with same-level ambiguity an error (C2-d);
- they expose **`err` only** and a **single default output** in v1 (the `rejects` path and multi-output are what
  canonical containers are for — the graduation boundary, C2-e);
- the **formatter never touches a fragment's interior** — the authored bytes are preserved verbatim, and the parse
  is derived from them (C2-f); and
- each has **its own ANTLR grammar** (`TTRSql.g4`, `TTRPandas.g4`, `TTRB.g4`), sized to its subset, so rejects are
  precise grammar rejects with named diagnostics (C2-g).

A fragment appears either **embedded** in a `.ttrp` container via a tagged block, or as a **bare file** that is a
whole program on its own (section 7.4).

### 7.1 TTR-SQL

TTR-SQL is the **workhorse cut** of SQL: one query expression per fragment — an optional `WITH` and a final
`SELECT` (C2-b). It has the full useful clause set (FROM/JOIN, WHERE, GROUP BY/HAVING, SELECT list, DISTINCT, ORDER
BY, LIMIT/OFFSET, set operations, `VALUES`) and it maps clause-by-clause onto the node set: FROM/JOIN → `Join`,
WHERE → `Filter`, GROUP BY/HAVING → `Aggregate` + `Filter`, the SELECT list → `Project`/`Calc`, DISTINCT →
`Aggregate` sugar, ORDER BY → `Sort`, LIMIT → `Limit`. A CTE becomes a named sub-chain — and its **name becomes the
SSA label**, which is exactly the inverse of how emitted SQL turns SSA names into CTE names (section 10.1).

```ttrp
container lines_pg(out lines) target pg """sql
    WITH prepped AS (
        SELECT ORDER_LINE_ID,
               PRODUCT_ID,
               QUANTITY,
               QUANTITY * UNIT_PRICE AS gross
        FROM   db.dbo.ORDER_LINE
    )
    SELECT * FROM prepped
"""
```

It is a *generic* SQL, not any vendor's dialect — which is precisely what keeps it retargetable. Pasting T-SQL's
`NOLOCK`, `TOP`, or `::` casts is an **error**, not a pass-through, because the expression must lift into the one
TTR-P expression tree with catalogue functions and canonical NULL (T5-e). The rejected forms — DML/DDL, vendor
syntax, scalar/correlated subqueries, window functions, procedural SQL, multi-statement — each produce a named
diagnostic with a suggested alternative (`TOP 10` → "use `LIMIT 10`"). A few deliberate conveniences: `EXISTS` and
`IN (subquery)` are accepted and **desugar to semi/anti `Join`** (the only subquery forms permitted); `SELECT *` is
allowed and **expanded statically at compile time** (the authored text keeps the `*`, but the graph sees the columns
— deterministic, so P2-clean); and SQL's own positional JOIN syntax carries the port assignment, so you do not write
`left:`/`right:` inside SQL (C2-b-ii). `LIMIT`/`OFFSET` require an ordered input, else a compile error, same as
canonical (S15).

### 7.2 TTR-pandas

TTR-pandas is a **method-chain over the TTR-P operator vocabulary** — "dataframe-shaped TTR-P", descended from
Kantheon's DFDSL (C2-c). It *looks* like pandas/Polars method chaining, so a `.ttr.py` file gets useful editor
highlighting, but it is **not** Python and not any dataframe library's API — its expressions are the TTR-P expression
grammar written bare (no boolean masks, no `pl.col` ceremony):

```ttrp
container returns_polars(out clean) target polars """pandas
    load("returns.csv", schema=returns_raw)
        .calc(returned_qty = cast(returned_qty_raw as int))
        .filter(returned_qty > 0)
"""
```

The method names are the operator names, spelled as **full words** — `select`, `calc`, `filter`, `join`,
`aggregate`, `sort`, `union`, `limit`, `load`, `store`, `display` — abbreviations like `agg` are rejected with a
named diagnostic (S17). Reassignment follows the SSA rule (Q7-γ). The reason this shape was chosen over a literal
pandas subset is structural (T5-e): pandas boolean masks and `notna` cannot lift into TTR-P expression trees, and
`NaN`-vs-`NULL` would silently diverge — the dialect would *lie*. Python control flow, lambdas, `.apply`, and any IO
beyond `load()` are parse-time rejects.

### 7.3 TTR-B — controlled sentences (English and Czech)

TTR-B is the strict controlled-grammar dialect, the evolution of the historical Byx (C4-a). It is **deterministic
and LLM-free** — bypassing language models for a simple instruction set has real value — and it is the third fragment
dialect, inheriting the whole regime: sentence-wise decomposition to the node set, document scope, `err`-only, single
default-out, untouchable interior, its own grammar.

```ttrp
container returns_polars(out clean) target polars """ttrb
Load the file "returns.csv" as returns_raw.
Keep only the rows where returned_qty is more than 0.
"""
```

The statement set is the Byx roster evolved to full breadth (C4-b): verb synonymy is kept (`Keep`/`Take`/`Select`);
anaphora is grammar-resolved (`that`, `this`, `it`, and an implicit subject = the previous result); `as <name>` is SSA
variable binding; Sort/Limit/Combine are added. Out-of-roster sentences are named-diagnostic rejects, and that reject
table doubles as the assist layer's repair vocabulary (section 12).

Its expressions are a **verbose skin over the one expression grammar** (C4-c), a *closed, documented synonym table* —
`is more than` → `>`, `is empty` → `IS NULL`, `is one of` → `IN` — producing the same trees, precedence, catalogue
ids, and 3VL as any other surface. Canonical spellings are also accepted, and you may mix them. It is **never NLP and
never fuzzy** — it is grammar with a lookup table. TTR-B is engine-agnostic: a sentence-island is legal in any
container, and the container carries the target.

**Skins (AG B2).** There is ONE TTR-B grammar and one keyword table per language: English (`"""ttrb`, `*.ttrb`) and
Czech (`"""ttrb-cs`, `*.ttrb-cs` with the first line `# ttr: dialect=b lang=cs`). A keyword matches case- and
diacritic-insensitively against the active skin (`Pošli` = `posli` = `POSLI`); identifiers are matched exactly — Latin
letters incl. Czech, never folded, never declined — so a sentence names a model object as the model declares it.
Czech fragments lower to exactly the tree their English twins do.

**Blocks (B3).** `If <condition>:` / `Když <podmínka>:` followed by indented sentences is ONE output — a `filter` of the
current value, the block's sentences running on it. Blocks over the same value overlap (they are not else-if), which
is the graph a canonical program with one `filter` per output has. Blocks do not nest (`TTRP-B-110`).

**Rules and actions (B4).** Count (`Spočítej x jako počet řádků T.` / `Count the rows of T as x.`) and attach
(`Připoj T k výsledku.` / `Attach T to the result.`) cross-join a one-row table into the current row. Three action
sentences are sinks routed to **action displays** (§4.9): `Pošli e-mail … / Send an e-mail …` → `send_email`,
`Nastav … / Set …` → `update_field`, `Vytvoř ruční úkol … / Create a manual task …` → `manual_task`. Each lowers to
`calc { <the schema's columns> } -> select(<them>)`; the checker gives the container an OUT port per action and wires
it to `display(<kind>)` at program level, so the imported `def schema <kind>` holds the rows (`TTRP-DSP-*`). Recipients
are a column, a department (`oddělení "x"` → the text `"oddělení:x"`) or a column with a department fallback
(`coalesce`). A whole business rule — checks plus a decision requesting actions — can thus be written entirely in
Czech or English sentences and compiles to the same graph as its canonical program:

```ttrp
container rozhodnuti target erp """ttrb-cs
Načti objednávky.
Spočítej počet_reklamací jako počet řádků reklamace.
Když částka je větší než 1000:
    Pošli e-mail email_zástupce, jinak oddělení "obchod" s předmětem "Velká objednávka", šablonou "velka_objednavka", klíčem číslo a přílohami faktura, dodací_list.
Když stav je "zaplacená" a počet_reklamací je 0:
    Nastav stav objednávky s klíčem číslo na "uvolněná" s důvodem "zaplaceno, bez reklamací".
"""
```

**Ports, joins and conditional values (B7).** A container's IN ports are names in every sentence that names a
table (never `load(<port>)`); `Pošli to na výstup <port>.` / `Send that to output <port>.` binds a declared OUT
port. Joins may be optional (`volitelně` / `optionally` → `type: left`), on a modelled relation (`přes vazbu r` /
`on relation r`), or keep the rows that have / have no match in another table (`mají protějšek` / `have a match` →
semi join, `nemají protějšek` / `have no match` → anti join). `Spočti x jako 1, když <p>, jinak 2.` / `Compute x as 1
when <p>, otherwise 2.` is a `case when … end` value.

**Grouping, names, no silent sentences (B8).** Parentheses group any sub-condition (`and` binds tighter than `or`
without them). `Pojmenuj to jako <jméno>.` / `Call that <name>.` names the current value; `Načti <jméno>.` /
`Load <name>.` makes a named value current again. A value a sentence produces that nothing reads — no later sentence,
port or display — is the error `TTRP-B-113`: in a rule a sentence that does not take effect is always a mistake.

**File-backed fragments (B5).** `container x(…) target <engine> from "rules/x.ttrb-cs"` takes its interior from a file
(byte-preserved, relative to the program, dialect from the file's extension / first line) — the same graph as the
embedded fragment; the editor opens the file from the clause. The full sentence reference, both skins side by side,
is [`ttrb/ROSTER.md`](ttrb/ROSTER.md).

### 7.4 Bare-fragment programs

A pure SQL, pandas, or TTR-B file is a **valid TTR-P program on its own** (C0). The compiler synthesizes the wrapper
— the container, its target, its shell, and the final-result display — from the `[ttrp]` project defaults
(`bare-target`, `bare-shell`, `display-default`, `default-imports`). The dialect is declared by a cheap, explicit
marker, never sniffed (P2): the **file extension** is the normal marker (`report.ttr.sql`, `prep.ttr.py`, a `.ttrb`
file), and a **first-line comment** overrides it for generic extensions (`-- ttr: dialect=sql`,
`# ttr: dialect=pandas`, `# ttr: dialect=b`, `# ttr: dialect=b lang=cs` for a Czech TTR-B file — `*.ttrb-cs`). The
double extensions (`.ttr.sql`, `.ttr.py`) exist so foreign editors still highlight the file (H-2). A bare TTR-B file
whose action sentences route to their own displays needs no synthesized final-result display.

The source text of a bare fragment is **never rewritten** — the wrapper is desugaring, not a rewrite (C0). This is
also why layout for such programs lives in a sidecar (section 8): there is nowhere in an untouched SQL file to put
canvas coordinates. A bare fragment authored against the same models and world produces a graph **byte-identical** to
the same logic written as an embedded fragment or as canonical text — that identity is a v1 test (section 13).

---

## 8. The graphical surface

The canvas is the second full surface (co-equal with canonical text, not a read-only view). It renders, and lets you
edit, the very same graph — through the LSP, back into canonical text. It is served by a repo-attached **Designer
server** (section 10.6); v1 is loopback-only, single-user, no auth (S24).

### 8.1 Two levels

The canvas is **two-level** (C1-a). The top level is the **orchestration view**: the container-collapsed derived
execution graph (section 3.5) plus the program-level leaves — movement, `store`, `display`. This is what the author
sees as the wave structure the runtime will execute: containers as boxes, transfers as wires between them.
**Drilling into** a container opens its interior as a graph of operations; the drill-in recurses. Edit locality
matches the text (C3): program-level wiring at the top, island edits inside. (Semantic zoom — smoothly interpolating
the two levels — is a v2 rendering enhancement over the same model, not foreclosed.)

### 8.2 Skins

Rendering is done by **skins**: a pluggable presentation layer over the one graph (C1-b). A skin decides how nodes
look and how edges orient. The v1 built-in roster includes an **"Alteryx/KNIME"** skin (an icon per node type, data
edges prominent) and an **"Enso"** skin (text-forward nodes showing a description or the code). A skin is **per-canvas**
and recorded in the sidecar; positions survive a skin switch; and skins are a family-generic feature, so TTR-M gets
them too. Fragment drill-ins render as **read-only derived sub-graphs** in every skin — you can see the shape of an
embedded SQL island, but you edit it as text, because its authored bytes are canonical (C2-f).

### 8.3 Layout is binary: auto until you touch it

Layout mode is **binary per canvas** (C1-b): a canvas is laid out by a **deterministic automatic algorithm** until
the user first rearranges it, at which point it becomes **manual** and positions are snapshotted into the sidecar.
There is no hybrid in v1. "Reset to auto" discards the manual snapshot; auto mode persists nothing (deterministic
re-derivation beats stored state, P2). Derived canvases — the fragment sub-graphs — are **auto-only**, because their
node identity is not stable enough to pin (C1-b).

### 8.4 The view-state sidecar (`.ttrl`)

Layout and view state live in a **sidecar file**, not in the program (C3-h, H-2c). It pairs with the program by
filename: `net_sales_by_product.ttrp` is accompanied by `net_sales_by_product.ttrl`. This is a family-wide scheme —
`.ttrp`, `.ttrg` (TTR-M graphs), and bare fragments all pair with a `.ttrl` — and it is **shared, committed truth**
in v1, not a per-user overlay (C1-c). The grammar is hosted in TTR-M's parser (the v1.1 layout-block grammar promoted
to a document body); its inventory is a version header plus, per canvas, its key, skin, mode (`auto`|`manual`),
manual node positions, and which containers are collapsed. Viewport (zoom/pan) is deliberately *not* stored — it is
ephemeral.

Keeping layout out of the program is what stops every code edit from churning coordinate diffs, and it is the only
place a bare fragment's derived-container layout *can* live (its source is never rewritten). The cost — keeping the
pair in sync — is paid by the LSP with pair-integrity diagnostics and atomic renames.

### 8.5 Node identity, and why orphaning is safe

The hard problem under a committed layout is: when the program text changes, which stored position still belongs to
which node? TTR-P keys view-state entries by **SSA-qualified name** (`crunch/sales#2`, anonymous `crunch/sums~1`) —
the same names that flow through the whole compiler (C1-c). Structured edits through the LSP rewrite the sidecar
atomically alongside the text. If a node's SSA/chain identity changes such that its key no longer matches, its
sidecar entry **orphans deterministically** — that node reverts to auto-layout and a visible diagnostic appears. The
system never mis-attaches a stored position to the wrong node and never guesses (P2). (An explicit-ids-in-text scheme,
the Alteryx/Enso way, is the named fallback if SSA keys prove too brittle in practice.)

### 8.6 Editing on the canvas

The v1 edit vocabulary is **minimal but sufficient** — the bar is "the hero scenario is buildable on the canvas"
(C1-d): add/remove an operation; wire and unwire (a cross-container wire is just a wire — movement synthesis happens
underneath, C3-d-iv); create a container and assign its target/dialect; bind ports; rename a variable; edit
arguments and expressions through a **textual property panel**; and draw control edges. Anything beyond that, you do
in text.

Every canvas edit goes through the LSP as a structured request (`ttrp/applyGraphEdit`), which the server turns into a
**formatter-owned `WorkspaceEdit`** on the canonical text — the formatter decides where the new text lands, so the
same edit always produces the same source (deterministic, P2). Concurrency is handled by **document versioning**: a
stale edit is rejected and the Designer replays it against the current version. Because the formatter emits the
canonical hybrid form, there is no separate writeable node/edge representation to keep — the internal explicit-edge
form has no external consumer (C1-d).

### 8.7 Running from the canvas

A run from the canvas is the **same execution path** as a run from the terminal (C1-e): `ttrp/run` shells out to the
compiled bundle exactly as a person would. Display sinks land as Arrow files in `out/`; the Designer watches that
directory and renders the results when the run completes. There is one execution path, so a Designer run and a
terminal run are indistinguishable; interactive streaming of results is a v2 additive upgrade, not a different
transport. The Designer server's "run" button is IDE-run-button territory — it does not make the server a platform
runtime (the no-runtime-coupling invariant holds).

---

## 9. The compile pipeline

Compilation is a deterministic, offline pipeline from program text to a runnable bundle. It embeds the TTR-M metadata
component and reads the model repo and the world document from paths in the project defaults — there is no service
call at compile time (D-g, P2). The stages:

```
  parse  ──►  resolve  ──►  build graph  ──►  normalize  ──►  collapse  ──►  emit
 (.ttrp +    (imports,      (SSA, ports,     (rewrite to    (containers   (islands +
  fragment    qnames,        containers)      executability)  → exec graph  bundle)
  grammars)   world, er→db                                    + waves)
              provenance)
```

### 9.1 Parse

The canonical grammar (`TTRP.g4`) and the three fragment grammars parse the program and any embedded blocks.
Comments and whitespace route to a hidden channel and reattach to nodes as **trivia**, so the formatter can preserve
them — and, critically, fragment interiors are preserved **verbatim** (their `sourceText` is canonical, their parse
derived; C2-f). Parse errors are recovered from where possible, and every diagnostic is a named `TTRP-<AREA>-<NNN>`
with a suggested alternative (section 11).

### 9.2 Resolve

Names are resolved against the imported model packages, the world, and in-scope program names, each checked by its
**position type** (D-b). Model references resolve through the metadata component; the world resolves engines,
executors, storages, and staging. **`er` references rewrite to `db` references early**, right after resolution, each
carrying **provenance** back to the logical name the author wrote (E-d) — so a later diagnostic about a physical
column can still say "from `erp.er.customer.customerType`", and the canvas can render logical names. Declared schemas
are resolved in their precedence order (section 6.3).

### 9.3 Build graph, then normalize

The resolved program becomes the one SSA graph — nodes, typed ports, containers with port mappings, control edges
(FS/SS; FF is a capability error), with acyclicity and single-data-in checks enforced. Then **normalization** rewrites
that graph until every node can run on its container's engine (B-T8). The rewrites fire in a fixed order (which is
what makes the fixpoint terminate and stay deterministic):

1. **Authoring-sugar expansion** — engine-independent: `Select`/`Calc` → `Project`, `HAVING` → Aggregate + Filter,
   `Distinct` → Aggregate.
2. **Capability lowering** — engine-relative, only where the target lacks native support: `Branch` → `Filter`,
   `Pivot` → `CASE`, a function → its per-dialect expansion.
3. **Escalation** — when a node (or a function inside it) has *no* rewrite into its container's engine, the **whole
   node re-places** to a capable engine (T5-b). To keep that cheap, **node fission** may split a node first — a
   `Project` computing twenty columns where one function is unsupported becomes `Project(19 supported)` then
   `Project(1 unsupported)`, and only the small slice re-places. Escalation is **split-with-warning** by default,
   configurable to `error` via `[ttrp] split-policy`.
4. **Movement synthesis** — every remaining cross-engine data edge becomes Store + Transfer + Load, staged through
   the world's staging area (section 3.4, 4.7).

Normalization has a **strictly-decreasing measure** (the count of node/container capability misses) and rule
stratification, so it provably terminates and converges — these (the termination measure and the fission rule) are
named implementation work items carried from the B design (review 260702).

### 9.4 Collapse and emit

Collapsing containers yields the **execution graph**, and a topological levelling of it yields **waves** — sets of
islands that can run in parallel (section 10.3). Finally, each island is emitted for its engine and the whole thing
is assembled into a bundle. Everything above the emit boundary is engine-agnostic; everything engine-specific lives
in emit.

---

## 10. Emit and execution

### 10.1 SQL islands

A container targeting a SQL-family engine emits SQL in **CTE-per-node** form: each node in the island becomes a named
CTE, and the **SSA/variable name becomes the CTE name** (E-b). Trivial islands flatten to a single `SELECT`. The
emitted SQL mirrors the authored graph — it is debuggable, diffable, and lineage-readable, rather than an opaque
optimized blob (optimization is workstream Z's job, deliberately not done at emit). The `lines_pg` island of the hero
emits, for Postgres:

```sql
WITH lines_pg__load AS (
    SELECT * FROM dbo."ORDER_LINE"
),
lines_pg__calc AS (
    SELECT *, "QUANTITY" * "UNIT_PRICE" AS gross
    FROM   lines_pg__load
),
lines_pg__select AS (
    SELECT "ORDER_LINE_ID", "PRODUCT_ID", "QUANTITY", gross
    FROM   lines_pg__calc
)
SELECT * FROM lines_pg__select;
```

Every emitted `Sort` places nulls explicitly (`NULLS LAST` unless the author placed them otherwise) so ordering is
identical across engines (Q9). The v1 SQL dialect is **Postgres**. The translation itself — island → RelNode →
dialect SQL — is done by `org.tatrman:ttr-translator`, the Proteus translation core extracted from Kantheon into a
published library that the compiler embeds (E-a; section 10.6).

### 10.2 Dataframe (Polars) islands

A container targeting Polars emits a **straight-line Python script plus a generated inline prelude** (E-c). The
prelude contains **only the enforcement helpers the program actually needs** — the 3VL-NULL, decimal, and
UTC-microsecond datetime helpers that make Polars match canonical SQL semantics (section 5.3, Q9 items 4–6). There is
**no runtime library dependency**: the artifact carries its own helpers, so it is self-contained. SSA names are
carried through as variable names, mirroring the canonical text. The `returns_polars` island emits roughly:

```python
# --- generated prelude: only the helpers this island needs ---
import polars as pl
def _to_int(col): ...       # 3VL-safe cast: parse failure -> null, not NaN

# --- island body (straight-line, SSA names preserved) ---
parsed = pl.read_csv("returns.csv", schema_overrides=RETURNS_RAW)
parsed = parsed.with_columns(_to_int(pl.col("returned_qty_raw")).alias("returned_qty"))
clean  = parsed.filter(pl.col("returned_qty") > 0)
bad    = parsed.filter(pl.col("returned_qty").is_null()
                       & pl.col("returned_qty_raw").is_not_null())
clean.write_ipc("staging/returns_polars__clean.arrow")
bad.write_ipc("out/rejected_returns.arrow")
```

### 10.3 The bundle and the bash runner

A compiled program is a **bundle** — a directory of reviewable text (F-f):

```
net_sales_by_product.bundle/
├── run.sh                    # wave-parallel bash; set -euo pipefail; wait -n early abort
├── manifest.json             # machine record: islands, transfers, waves, connections, fingerprint
├── islands/<name>.{sql,py}   # one file per island; SSA/CTE names preserved
├── transfers/<name>.py       # generated ADBC/connectorx movement scripts
├── schemas/*.json            # Arrow schema fingerprints per staging boundary
└── plans/*.pb                # plan.v1 protobufs — Kantheon-target worlds only
# created at run time, wiped on restart: logs/  staging/  out/
```

Every file carries a sha256 in the manifest, and the manifest records a **semantic world fingerprint** (a hash of the
resolved world model plus the world qname in clear). The artifact *records* that fingerprint; a capable invoker
*verifies* it — the compile-target/runtime split again (B-T6). The manifest also records `ttrpVersion` and the
toolchain version.

The **executor is bash** in v1 (F-a). `run.sh` runs islands in **waves**: a topological levelling where each wave's
islands launch together with `&` and the script `wait`s for them, using `wait -n` to abort the remaining siblings on
the first failure. A finish-to-start control edge (or a data dependency) orders waves; a start-to-start edge co-launches
within a wave. Failure semantics are **fail-fast**: `set -euo pipefail`, `ON_ERROR_STOP` in psql, per-island log
files, and a run.sh failure summary. The exit contract is `0` success · `1` island failure · `2` pre-flight failure.
There are no retries and no resume in v1 — a rerun is the whole artifact, and `run.sh` wipes its own `staging/` and
`out/` at start so restarts are clean (F-e).

### 10.4 Movement, staging, and credentials

Cross-engine movement stages through **Arrow IPC at every boundary** (F-c) — and that staging format is *the same*
format the conformance harness fingerprints, so runtime and conformance agree by construction. A transfer emits as a
generated Python script (ADBC/connectorx). **Credentials never live in the artifact**: connections are referenced by
name and resolved from `TTR_CONN_<NAME>` environment variables at run time (F-c-ii); a missing connection fails
pre-flight with exit 2. The v1 invocation bindings are: Postgres × bash → `psql -v ON_ERROR_STOP=1 --no-psqlrc -f`;
Polars × bash → `python3`; display × bash → a file drop to `out/<name>.<fmt>` plus a printed notice.

### 10.5 Identical results: the conformance harness

A4 — "identical results wherever a computation lands" — is made checkable by **`ttrp-conform`** (S3, Q9), which runs
the bundle under different engine-placement variants and compares outputs under a seven-point equivalence procedure:
(1) compare Arrow **schema fingerprints**; (2) treat rows as a **multiset**, order-sensitive only under a terminal
`Sort` (both sides canonically sorted to compare); (3) emit `NULLS LAST` in every `Sort`; (4) **decimals exact**,
`float64` within a *declared* tolerance (default exact — no silent epsilon, P2); (5) **UTC-microsecond** datetimes,
truncation emitted at boundaries; (6) binary/UTF-8 collation (locale-aware sort is a declared v1 non-goal); (7) the
comparison runs over the Arrow IPC exports. The harness doubles as the **emit regression suite** and as the
standalone-vs-Kantheon **drift guard**. It is invoked as `ttrp conform`.

### 10.6 The Kantheon target, and the component split

When a container's target resolves to a **Kantheon** world engine, the invocation binding delivers `plan.v1`
**PlanNode protobufs** under `plans/` instead of a SQL script — world-driven emit (E-a). Those plans are produced by
the same `org.tatrman:ttr-translator` library; Kantheon's own Proteus becomes a thin wrapper over it (the
metadata/Ariadne pattern). This is why an external, kantheon-side **Proteus-extraction arc** is a hard gate before
the emit phase can complete: the translation core has to be extracted and published as a `org.tatrman:*` artifact
first (the `plan.v1` proto is vendored into the library at that point, S25).

The toolchain is **Kotlin-only** — no KMP, no TypeScript parser, one parser (G-b). The components:

| Component | Form | Role |
|---|---|---|
| Grammars | `TTRP.g4` + `TTRSql.g4` + `TTRPandas.g4` + `TTRB.g4` (+ `.ttrl` in TTR-M's parser) | canonical sources; antlr-ng/Kotlin generation |
| Compiler front-half | Kotlin lib | parse → resolve → graph → normalize; embeds `ttr-metadata` |
| `ttr-metadata` | Kotlin lib (shared with Ariadne) | model graph + queries; world resolution |
| `ttr-translator` | Kotlin lib (Proteus core, extracted) | relational island → RelNode → SQL / `plan.v1` (Calcite lives here) |
| Emit + bundle | Kotlin lib | island codegen, movement synthesis, bundle assembly |
| `ttrp` CLI | Kotlin binary | `build` · `run` · `explain` · `conform` |
| TTR-P LSP | Kotlin; stdio + WebSocket | one LSP across hosts; standard methods + `ttrp/*` |
| Designer server | JVM, repo-attached | hosts the WS-LSP, serves the Designer (loopback-only v1) |
| Designer frontend | TS/React | thin WS client — canvas, skins, property panel |
| vscode-ext | TS shim | language registration + LSP client; no logic |
| `ttrp-conform` | Kotlin module | the Q9 harness |

The repo dependency is one-way: Kantheon consumes `org.tatrman:*` artifacts; Tatrman never depends on Kantheon.

### 10.7 The RLS stance

Cross-engine programs blur row-level security (data stored from an RLS-governed source and loaded into Polars has
lost its policy). v1 takes a **trusted-principal-with-tripwire** stance (Q8): the artifact runs under the world's
named-connection credentials and propagates no policy, but a world storage marked `rls: true` raises a **compile
warning** when data egresses from it, and `[ttrp] rls-egress = warn|error` turns that into a hard stop. Row-filter
propagation across engines is v2 (Argos territory).

---

## 11. Diagnostics

Diagnostics are a designed, user-facing surface, not an afterthought. Every one has a **stable named id** of the form
`TTRP-<AREA>-<NNN>` (areas include `EQ`, `SQL`, `PD`, `B`, `CTL`, `CAP`, `MOV`, `SCH`, `WLD`, `RLS`), and every
*rejected* form carries a **suggested alternative** (contracts §8). A few representative ones:

- `TTRP-EQ-001` — `==` used outside TTR-pandas → "use `=`".
- `TTRP-SQL-014` — `LIMIT`/`OFFSET` on an unordered input → "add an `ORDER BY` / `sort` first".
- `TTRP-CTL-001` — `finishes with` (FF) used → "FF is not available in v1".
- SQL vendor forms — `TOP 10` → "use `LIMIT 10`"; `NOLOCK` → "not supported in TTR-SQL".
- TTR-pandas abbreviations — `agg` → "use `aggregate`".
- capability misses — "node lowered to X for engine E" (info); "no rewrite; whole node re-placed to engine F"
  (warning, or error under `split-policy = error`); "cannot stage between E1 and E2" (error).
- action displays (section 4.9, area `DSP`) — `TTRP-DSP-001` a schema column is missing, `TTRP-DSP-002` a column's
  type is not assignable, `TTRP-DSP-003` (warning) a column the schema does not name is dropped, `TTRP-DSP-004` an
  ordinary display name with several sources, `TTRP-DSP-005` a schema column no engine can produce.
- TTR-B (area `B`) — one reject table per skin (`rejects.en.yaml`, `rejects.cs.yaml`): the same ids, a message in the
  skin's language and a suggested correct sentence — e.g. `TTRP-B-104` a malformed e-mail sentence suggests the full
  `Pošli e-mail …` / `Send an e-mail …` shape, `TTRP-B-109` a keyword used as a name (`jako a`), `TTRP-B-006` a
  sentence in the other skin's language. Table: [`ttrb/ROSTER.md` §5](ttrb/ROSTER.md#5-diagnostics-ttrp-b-reject-tables-per-skin).

The per-dialect reject tables are **versioned test fixtures** — they are simultaneously the compiler's conformance
tests *and* the assist layer's repair vocabulary (section 12). One table, two consumers.

---

## 12. Assist and agents

The compiler is deterministic and LLM-free (P2). Language-model help exists, but it lives **outside** the toolchain,
which ships two deterministic contracts instead of a model dependency (C4-d):

- **`ttrp/authoringContext`** — assembles a prompt-ready bundle of everything a model needs to write correct TTR-P:
  the resolved world summary (engines, executors, storages, staging, RLS flags), the capability manifests, the model
  objects in scope (db + er, with schemas), the in-scope names at the cursor, the grammar and roster tables per
  dialect, and the named-diagnostic catalogue.
- **`ttrp/validate`** — runs the full front-half (parse → typecheck → capability-check) over candidate text and
  returns named diagnostics.

Any host — the VS Code extension, the Designer, Claude, a Kantheon agent — runs the same loop:
**generate → validate → repair → review**. Generated text is **never applied silently**; it arrives as a
pre-validated proposed edit (it parses, typechecks, and passes capabilities, or it is not presented), and the git
diff is the review artifact (an optional `[ttrp] assist-provenance = comment` knob can add a header, but the default
is none — a generated-by comment decays into a lie after the first manual edit). Assist emits **canonical TTR-P** by
default; a cursor-scoped insertion emits the pointed-at container's dialect (TTR-SQL/TTR-pandas/TTR-B), with the host
declaring the target — never a heuristic (C4-d-i).

This is also the whole story of **agents** (Q1). An AI agent is a **first-class author** of canonical TTR-P through
the exact same two contracts — there is no agent-special surface and no TTR-B detour (a model gains nothing from the
controlled grammar; TTR-B is for humans avoiding models). Kantheon agents consume the **published toolchain
artifacts** to do this, never a running Tatrman service — the one seam where "runtime consumes compiled plans only"
softens to "an agent may author source" (C4-e).

---

## 13. Worked gallery — one program, every surface

The claim that all surfaces compile to one graph is only convincing on an example. This section renders the same work
several ways. The full hero program in canonical text is section 2; here we take **one island of it — `lines_pg`,
which preps the modelled order lines** — and write it in every fragment dialect, then show the equivalent bare
program, then the `er`-flavored variant. Each produces the **identical normalized graph**: `Load → Calc → Select`,
placed on `pg`. That identity is a v1 test (section 9), not a claim.

### 13.1 The island, four ways

**Canonical TTR-P** (the shape from section 2):

```ttrp
container lines_pg(out lines) target pg {
    load(db.dbo.ORDER_LINE)
        -> calc(gross = QUANTITY * UNIT_PRICE)
        -> select(ORDER_LINE_ID, PRODUCT_ID, QUANTITY, gross)
        -> lines
}
```

**Embedded TTR-SQL** — the `FROM`/`SELECT` clauses decompose to `Load`/`Project`; the CTE name `prepped` becomes an
SSA label:

```ttrp
container lines_pg(out lines) target pg """sql
    WITH prepped AS (
        SELECT ORDER_LINE_ID, PRODUCT_ID, QUANTITY,
               QUANTITY * UNIT_PRICE AS gross
        FROM   db.dbo.ORDER_LINE
    )
    SELECT * FROM prepped
"""
```

**Embedded TTR-pandas** — a method-chain over the operator vocabulary; the expressions are TTR-P expressions written
bare:

```ttrp
container lines_pg(out lines) target pg """pandas
    load(db.dbo.ORDER_LINE)
        .calc(gross = QUANTITY * UNIT_PRICE)
        .select(ORDER_LINE_ID, PRODUCT_ID, QUANTITY, gross)
"""
```

**Embedded TTR-B** — controlled sentences; `computed as` and the verbose skin fold into `Calc`/`Select`:

```ttrp
container lines_pg(out lines) target pg """ttrb
    Load the table db.dbo.ORDER_LINE.
    Compute gross as QUANTITY times UNIT_PRICE.
    Keep the columns ORDER_LINE_ID, PRODUCT_ID, QUANTITY and gross.
"""
```

All four parse to `Load(db.dbo.ORDER_LINE) → Calc(gross = QUANTITY*UNIT_PRICE) → Project(ORDER_LINE_ID, PRODUCT_ID,
QUANTITY, gross)`, on `pg`, and all four emit the same Postgres SQL (section 10.1).

### 13.2 As a bare program

The same island as a standalone `.ttr.sql` file. There is no container and no `target` in the text — the compiler
synthesizes the wrapper from `[ttrp]` defaults (`bare-target = "pg"`, `bare-shell = "bash"`, `default-imports =
["shop.*"]`), and the final `SELECT` flows to the default display:

```sql
-- lines_prep.ttr.sql   (a whole TTR-P program on its own)
WITH prepped AS (
    SELECT ORDER_LINE_ID, PRODUCT_ID, QUANTITY,
           QUANTITY * UNIT_PRICE AS gross
    FROM   db.dbo.ORDER_LINE
)
SELECT * FROM prepped
```

The synthesized graph is byte-identical (after normalization) to the embedded and canonical forms — the wrapper is
desugaring, not a rewrite, and the source `.ttr.sql` text is never modified.

### 13.3 The `er`-flavored variant

The same join, written against the **logical** model instead of physical tables. `order_line` and `order` are
entities; the join condition is a *modelled relation* rather than a spelled-out key equality. The compiler rewrites
these `er` references to `db` early, keeping provenance, so the normalized graph — and the emitted SQL — is the same
as the physical version, but diagnostics and the canvas still show the entity names:

```ttrp
import shop.sales.*

container lines_er(out lines) target pg {
    join(left:  load(er.entity.order_line),
         right: load(er.entity.order),
         kind:  inner,
         on:    relation order_of_line)          # the modelled join, by name
        -> calc(gross = quantity * unit_price)
        -> select(id, product_id, quantity, gross)
        -> lines
}
```

This is the two-tier test in miniature (D-a): an analyst writes `er.entity.order_line.quantity`, the compiler
lowers it to `db.dbo.ORDER_LINE.QUANTITY`, and any downstream message can still name the attribute the analyst
actually typed.

### 13.4 Mixing dialects in one program

Because the container is the mixing unit (C0-γ), a single program can write each island in whichever surface suits
it — the clean modelled SQL work as a `"""sql` fragment, and the islands that need more than a fragment offers (a
second output, a `rejects` path) in canonical text — and the whole thing is still one graph, one bundle:

```ttrp
import shop.sales.*

# a TTR-SQL fragment: single default-out, err-only — fine for a fragment
container lines_pg(out lines) target pg """sql
    SELECT ORDER_LINE_ID, PRODUCT_ID, QUANTITY,
           QUANTITY * UNIT_PRICE AS gross
    FROM   db.dbo.ORDER_LINE
"""

# net_sales stays canonical text: it has two outputs (the branch) — beyond a fragment's single default-out
container net_sales(in lines, in returns, out top, out rest) target polars {
    agg = join(left: lines, right: returns, kind: left,
               on: left.ORDER_LINE_ID = right.order_line_id)
            -> aggregate {
                 group_by:   PRODUCT_ID
                 gross_sales = sum(gross)
               }
    flagged = agg -> branch(gross_sales >= 100000)
    flagged.true  -> top
    flagged.false -> rest
}

# returns_polars keeps the rejects path, so it too is canonical (a fragment surfaces err only, C2-e)
container returns_polars(out clean, rejects bad) target polars {
    parsed = load("returns.csv", schema: returns_raw)
                -> calc(returned_qty = cast(returned_qty_raw as int))
    parsed -> filter(returned_qty > 0) -> clean
    parsed.rejects -> bad
}

lines_pg.lines       -> net_sales.lines
returns_polars.clean -> net_sales.returns
net_sales.top        -> display(top_products)
returns_polars.bad   -> display(rejected_returns)
```

The point the mix makes: `lines_pg` — a clean, single-output SQL island — is written in TTR-SQL, while
`net_sales` (two outputs from the branch) and `returns_polars` (a `rejects` path) stay in canonical text because they
cross a fragment's boundaries (single default-out, `err`-only). The container is the mixing unit, and each island
picks the surface it earns.

---

## 14. What is *not* in v1

The language is designed so these land later without a redesign; each is a conscious deferral with its seat already
reserved in the model.

| Deferred | Why it is safe to defer | Returns as |
|---|---|---|
| **Optimizer (Z)** | v1 placement is author-assigned; the manifest format already has room for cost attributes. | A separate design effort; cost layers on top of capability. |
| **`finishes with` (FF) control edge** | Cross-engine atomic co-finish is a distributed-transaction problem; the grammar keyword is reserved and a v1 use is a clean capability error. | v2, with the real orchestrator (staging+swap / compensation). |
| **Orchestrator proper (F)** | The execution graph is *derived* by container-collapse in v1; bash is a sufficient executor. | A Kantheon orchestrator; execution engines are already "engines with manifests". |
| **Events, loops, re-invocation** | The control model already treats FS/SS as event wirings conceptually; v1 is static and acyclic. | v2 event layer. |
| **Runtime parameters** | Parameters are compile-time-only in v1 (declared, defaulted, substituted). | With the event/orchestration work. |
| **Window functions, `Explode`/`Unnest`** | The v1 type system is flat scalars. | v2, with nested types. |
| **`md` (multidimensional) tier + MD sugar (D-h)** | Ref kinds are package-derived and extensible; `md` refs are a later package path, not a syntax change; the catalogue interface seat is reserved. | v1.x/v2, with the md engine/emit story. |
| **pandas *engine*** (the dialect ships in v1) | Polars has real null semantics; enforcing SQL 3VL on pandas is far heavier. Dialect ≠ engine. | v1.x as an additional emit target. |
| **Erroneous-rows in fragments (`rejects` taps in dialects)** | Producer semantics for erroneous-rows-in-SQL are unsolved; fragments surface `err` only. | v1.x, once the producer semantics land. |
| **Retries / resume** | v1 is fail-fast; rerun is the whole artifact. | Executor-manifest-declared, v2. |
| **Multi-user Designer server + auth** | v1 is loopback-only, single user. | Later. |
| **TTR-M convergence onto the Designer server + `.ttrl` migration** | TTR-P's sidecar/skin/identity code is written once, Kotlin-side; TTR-M adopts it later. | One post-v1 arc (C1-f). |
| **Row-level-security propagation** | v1 is trusted-principal + egress tripwire. | v2 (Argos row-filter propagation). |

---

## 15. Appendices

### 15.1 Node reference (v1)

| Node | Surface spelling | Notes |
|---|---|---|
| Project | `select`, `calc` | the one value-shaping primitive; `select` = select+rename, `calc` = compute |
| Filter | `filter` | one output; the lowering target for `branch`/`switch` on SQL |
| Branch | `branch` | outputs `true`/`false`; engine-relative (macro→Filter on SQL) |
| Switch | `switch` | multi-output, per-output conditions, optional `else`; overlapping or not |
| Join | `join` | named inputs; `kind`: inner/left/right/full/semi/anti/cross |
| Aggregate | `aggregate` | group-by + aggregate calls; `HAVING` = following `filter` |
| Sort | `sort` | keys with `dir`; nulls placed explicitly on emit |
| Union | `union(a,b,…)` | list form only; no named ports |
| Intersect / Except | `intersect`, `except` | real, engine-relative nodes |
| Values | `values` | inline literal table |
| Limit | `limit` | requires ordered input; optional `offset` |
| Pivot | `pivot` | statically declared values; native or CASE per dialect |
| Distinct | `distinct` | sugar → Aggregate |
| Load / Store | `load`, `store` | memory boundary; source Load / sink Store authored |
| Transfer | (synthesized) | physical→physical movement; delivery via invocation binding |
| Index | `index` | optimization on stored data |
| Materialize | (macro) | rewrites → Store + (Index) + Load; not surface syntax |
| Container | `container … target …` | function-shaped group; bears the execution target |
| Display | `display` | sink-only leaf; dynamic schema allowed |

### 15.2 Expression catalogue (sketch)

Arithmetic `+ - * / %`; comparison `= <> < <= > >=`; logical `and or not`; null/set tests `is null`, `is not null`,
`in (...)`, `between … and …`, `like`; conditional `case`/`coalesce`; string (`upper`, `lower`, `substring`, `trim`,
`concat`, …); aggregates (`sum`, `count`, `avg`, `min`, `max`, with optional `distinct`, in aggregate position only);
explicit `cast(x as <type>)`. Functions resolve to catalogue ids; the TTR-M MD calc catalogue is referenced through
the same typed interface. Types: `text | int | float | bool | datetime` (coarse in v1; refinement types later). NULL
is canonical SQL 3VL everywhere.

### 15.3 File kinds

| Kind | Extension | Marker |
|---|---|---|
| Canonical program | `.ttrp` | — (identity = filename) |
| Bare TTR-SQL program | `.ttr.sql` | double extension; `-- ttr: dialect=sql` override |
| Bare TTR-pandas program | `.ttr.py` | double extension; `# ttr: dialect=pandas` override |
| Bare TTR-B program (English) | `.ttrb` | extension; `# ttr: dialect=b [lang=en]` override |
| Bare TTR-B program (Czech) | `.ttrb-cs` | extension; `# ttr: dialect=b lang=cs` override |
| View-state sidecar | `.ttrl` | pairs by filename |
| World / models | `.ttrm` | `schema world` doc, in the model repo |
| Bundle | `<program>.bundle/` | — |

Embedded fragment fences: `"""sql`, `"""pandas`, `"""ttrb`, `"""ttrb-cs` (the tag is the dialect marker). Interiors are
byte-preserved. A container body may also live in a file: `container x(…) target e from "rules/x.ttrb-cs"`.

### 15.4 CLI and LSP

CLI `ttrp`: `build` (compile to bundle), `run` (build + execute), `explain` (normalized graph, placements, rewrites,
island→payload map), `conform` (the Q9 harness). LSP methods beyond the standard set: `ttrp/getGraph`,
`ttrp/applyGraphEdit`, `ttrp/getLayout` + `ttrp/setLayout`, `ttrp/getWorld`, `ttrp/transpile`, `ttrp/run`,
`ttrp/explain`, `ttrp/authoringContext`, `ttrp/validate`.

### 15.5 Glossary

- **Island** — the emitted content of one container, targeting one engine (a SQL script or a Polars script).
- **Container** — a group of nodes that acts as a function and bears an execution target.
- **World** — a `schema world` TTR-M document describing engines, executors, storage, and their relations; the
  compile target.
- **Engine type / instance manifest** — the stable capability facts of an engine kind, plus per-environment deltas.
- **Invocation binding** — the per-container choice of *how* an island is delivered (inline psql, python3, REST to a
  worker), from the (data engine, executor) manifest pair.
- **SSA name** — the stable label a variable/edge carries through text, emitted CTE names, and the canvas sidecar.
- **Bundle** — the compiled artifact directory: `run.sh` + islands + transfers + schemas + manifest.
- **Fragment / dialect** — container content written in TTR-SQL, TTR-pandas, or TTR-B; decomposes to the one node set.
- **Physicality spectrum** — the idea that operations are neither logical nor physical but are rewritten toward
  executability until an engine can run them.

### 15.6 Decision-id map

Sections above cite decision ids; the full reasoning for each lives in [`design/00-control-room.md`](./design/00-control-room.md).
Quick index: **A** vision/scope · **B / T1–T10** internal model · **C0** surface architecture · **C1** graphical ·
**C2** fragments · **C3** canonical DSL · **C4** NL/TTR-B · **D** model binding & world · **E** emit · **F** / F-lite
orchestration · **G** tooling · **H** naming · **S1–S25** consolidation sweep · **P1/P2** principles · **Q1–Q11**
rolling questions.

---

*End of the v1 language design. This document describes the destination; the phased route to it is
[`implementation/v1/plan.md`](./implementation/v1/plan.md).*







