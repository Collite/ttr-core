<!-- SPDX-License-Identifier: Apache-2.0 -->
# Language reference

*Reference. TTR-M in full — the definition kinds, properties and constructs the parser accepts.*

!!! note "Skeleton"
    This is the language-reference **skeleton** for TTR-M grammar **v0.9** (the grammar version
    tracks the product `major.minor`). The tables below enumerate the surface; the per-construct
    property detail marked _(from grammar)_ is generated from `TTR.g4` so it cannot drift from what
    the parser accepts, and the prose marked _(hand-written)_ is authored. Fuller worked examples
    are migrating in from the user manual.

## File shape

A TTR-M document is a package member with a model directive:

```
package <name>            // the package this file belongs to
import  <name>[.*]        // optional cross-package imports
model   <code> [schema <id>]   // the layer this file defines
def <kind> <name> { … }   // one or more definitions
```

`<code>` is the layer: `db`, `er`, `binding` (er↔db and other mappings), `cnc`, `md`. Identifiers
allow Latin-1/Extended letters, so real Czech names are valid verbatim.

## Definition kinds _(from grammar)_

| Layer | Kinds |
|---|---|
| `db` | `table`, `column` (inline), `view`, `index`, `constraint`, `fk`, `procedure` |
| `er` | `entity`, `attribute` (inline), `relation` |
| `binding` | `er2db_entity`, `er2db_attribute`, `er2db_relation` |
| `cnc` | `role`, `er2cnc_role` |
| `md` | `domain`, `dimension`, `hierarchy`, `measure`, `cubelet`, `map`, `md2db_*`, `md2er_*` |
| structure | `package`, `import`, `area` (`packages:` / `entities:`), `graph`, `drill_map`, `query`, `world` |

## Packages and areas _(hand-written)_

- A **package** is a directory of `.ttrm` files sharing a `package <name>` header, with a
  `modeler.toml` manifest at its root. Packages are the unit of import and versioning.
- An **`area`** is a subject area that spans packages — `def area <name> { packages: […],
  entities: […] }`. Use it to name a business domain that cuts across package boundaries.

## Bindings _(hand-written)_

Bindings keep the `er` meaning anchored to the `db` truth without repeating it. `er2db_entity`,
`er2db_attribute` and `er2db_relation` map logical constructs to their physical targets; an inline
`binding:` shorthand on an entity/attribute/relation covers the common case. This is what lets the
`db` mirror be regenerated while the `er` model stays hand-owned.

## Naming, search and the lexicon _(from grammar)_

Entities and attributes carry the vocabulary the understanding layer resolves against:

- **`aliases: [ … ]`** — alternate names for the concept.
- **`search { searchable, fuzzy, keywords { <locale>: [ … ] }, patterns [ … ] }`** — how the fuzzy
  and search doors find this field, including localized keywords.
- **`lexicon { … }`** — inline sugar for terms, patterns and examples the resolution layer uses.
- **`valueLabels { "<code>": { <locale>: "…" } }`** — human labels for coded values.
- **`semantics { … }`** — the closed semantic vocabulary, in two orthogonal facets. On an
  attribute or column, `role:` declares the **grounding** facet (which date to filter on, which
  column is the amount, where the coordinates are). On an entity or table, `kind:` is the
  grounding facet and `name:` / `code:` / `measures:` are the **mention** facet — which attribute
  carries this entity when a human refers to it by name, by code, or as a value to aggregate.
  `measures:` is ordered and its first item is the entity's default measure.

!!! note "`nameAttribute:` / `codeAttribute:` are deprecated"
    The entity properties `nameAttribute:` and `codeAttribute:` still parse and still work, but
    `semantics { name: … , code: … }` is the source of truth from vocabulary v3 on. Declaring only
    the legacy property, or declaring both in agreement, raises a deprecation **warning**;
    declaring both so they *disagree* is an **error** — a disagreement is always a bug, not a
    preference. Prefer the semantics block in new models.

## Quoting a literal _(hand-written)_

TTR-M has one escape hatch on the *asking* side, and it belongs here because what it needs from a
model is declared in the section above. Putting a span of a question in quotes means **take this
exactly as typed**: it is a value, not language. Nothing looks it up, nothing corrects it, nothing
proposes alternatives for it, and no language model is asked what it might have meant.

```
Show stores starting with "Abl"
Zobraz prodejny začínající na „Abl"
```

### The delimiter family

Any member of this closed set opens a literal, and any member closes it — so a question pasted out
of Word, typed on a phone keyboard, or written with Czech typography all work without the user
knowing which quote character they produced.

| | Character | Name |
|---|---|---|
| ✅ | `"` | U+0022 quotation mark |
| ✅ | `„` `“` `”` `‟` | U+201E · U+201C · U+201D · U+201F — the curly family, German/Czech low-high included |
| ✅ | `«` `»` | U+00AB · U+00BB guillemets |
| ❌ | `'` `‚` `‘` `’` | apostrophes and single quotes — they appear *inside* real names (`O'Brien`) |
| ❌ | `‹` `›` | single guillemets |
| ❌ | `` ` `` | backtick |

A delimiter only counts as one at a word boundary: an opener must follow the start of the text,
whitespace, or one of `([{-–—:;,`, and a closer must precede the end, whitespace, or one of
`)]}.,;:!?-–—`. That is what keeps the inch mark in `a 5" screen` from opening a literal. Pairing
is left to right and greedy to the nearest closer; an unpaired delimiter is just text, and so is an
empty one. Whatever sits between the delimiters is the value, with the outer whitespace trimmed and
everything else — case, diacritics, inner spaces, punctuation — kept exactly as typed. There is no
escaping syntax, because a literal is never read as anything but itself.

### What the literal filters, and how

The literal has to land on a column, and the model is what says which one. The head it attaches to
is the nearest model object that governs it, and that head's **mention facet** decides:

| The head declares | A literal there filters on |
|---|---|
| `semantics { name: … }` | the name attribute |
| `semantics { code: … }`, and the literal is code-shaped | the code attribute |
| neither | nothing — the literal stays unattached and the question is refused, visibly |

That last row is the one to design for. A head with no mention facet cannot carry a quoted literal
at all, and refusing is deliberate: guessing a column for a value the user was explicit about is
the one failure mode that produces a confident wrong answer. If quoting a name does not work on
some entity, the fix is `semantics { name: … }` on that entity, not a rephrasing.

**How** it filters comes from the predicate words beside the literal — ordinary lexicon vocabulary
shipped in the standard library, matched in Czech and English:

| Predicate | Some of the words | Filter |
|---|---|---|
| `starts_with` | začínající na · začíná na · s prefixem · starts with · beginning with | `col LIKE ? \|\| '%'` |
| `ends_with` | končící na · končí na · s příponou · ends with · suffix | `col LIKE '%' \|\| ?` |
| `contains` | obsahující · obsahuje · s textem · v názvu · contains · including | `col LIKE '%' \|\| ? \|\| '%'` |
| `equals` | přesně · rovná se · exactly · named exactly · equal to | `col = ?` |
| `not_contains` | neobsahující · neobsahuje · not containing · without · excluding | `NOT (…)` |

With no predicate word at all, the default follows the facet the literal landed on: a **name** is
`contains`, a **code** is `equals`. Someone asking about a name usually means "has this in it";
someone quoting a code means that code.

An estate can add its own wording — a `pred:` entry in the estate's `lexicon/` area *extends* the
shipped list rather than replacing it — but the five predicates themselves are a closed set.

!!! note "The literal is a bound parameter, always"
    The quoted text never becomes SQL text. It is bound as a parameter, and `%` and `_` inside it
    are escaped with an explicit `ESCAPE` clause, so a value containing a wildcard filters for that
    character rather than acting as one. A user who asks for names containing `"50%"` gets rows
    with `50%` in them.

## Queries _(from grammar)_

- **Named queries** — `def query <name> { … }` with a SQL/DSL template and a parameter list;
  surfaced through `list_queries` and runnable through the query door.
- **Pattern queries** — parameterized query shapes an agent can bind and run.

## Governance — roles, not a `security` block

!!! warning "There is no `security {}` construct"
    Governance in TTR-M is expressed through the **`cnc` layer** — `role` and `er2cnc_role`
    definitions (fact/dimension roles and role bindings) — **not** a single `security` keyword. The
    row-level filters and column masks are enforced downstream by the validator and reported in
    `pipelineWarnings` on every governed answer. Model the roles; the platform enforces them.

## Worlds and composition _(from grammar)_

`def world <name> { … }` (with `engine`/`executor`/`storage` nouns) describes a composition of
packages into a deployable whole — the model as the deployment artifact, named.

## Types _(from grammar)_

The canonical type tokens (`int`, `bigint`, `text`, `decimal`, `float`, `bool`, `date`, `time`,
`datetime`, …) are what `db` columns and `er` attributes carry; `ttr import-schema` normalizes SQL
types onto them. The full type table is generated from the grammar.

---

_For a guided path into the language rather than a lookup, start with
[model your first three tables](first-three-tables.md); for how the layers relate, see
[the layers](layers.md)._
