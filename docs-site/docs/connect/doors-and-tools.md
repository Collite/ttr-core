<!-- SPDX-License-Identifier: Apache-2.0 -->
# The doors and tools

*Reference. The MCP surface an agent consumes — every door, its tools, the result envelope, and
the refusal modes. This is the consumer projection of the engineering contract; where a schema
detail is not repeated here, the tool's own MCP schema is authoritative.*

Tatrman speaks **MCP over Streamable HTTP**. Each service mounts one **door** at `POST /mcp`; the
HTTP probes (`/health`, `/ready`, `/status`, `/metrics`) sit beside it and are operational surface,
not part of this contract. Everything your agent can observe is specified here or in the tool
schemas — nothing depends on a door's internals, so a second implementation is possible by
construction.

## The five doors

| Door | What it answers | Tools |
|---|---|---|
| `meta-mcp` | The model — *what is known* | `get_model`, `get_object`, `list_objects`, `list_queries`, `resolve_area`, `search` |
| `query-mcp` | The governed query path | `query`, `compile` |
| `lex-matcher-mcp` | Fuzzy candidate matching over searchable fields | `match` |
| `grounding-mcp` | Deterministic grounding of universal spans | `ground_time`, `ground_geo`, `ground_money` |
| `resolver` | Deterministic entity resolution against model vocabulary, and frames over concepts | `resolve.bind:v1`; `statement.*`, `assertion.*`, `concept.search:v1`, `definition.render:v1` |

The **two-call shape** most agents follow: *resolve and ground* the user's words into model
vocabulary (`search` / `match` / `resolve.bind:v1` / `ground_*`), then express the intent as a
query over the modeled entities (`query`). The model door tells you what exists; the query door is
the only path to data, and it is always governed.

## The result envelope

Every governed tool returns its formatted text in `content[0]` and a structured **result envelope**
in `structuredContent` (always a JSON object, never a bare array):

```jsonc
{
  "ok": true, "tool": "query",
  "rowCount": 5, "columnCount": 4, "truncated": false,
  "format": "json", "mediaType": "application/json; charset=utf-8",
  "columns": [ { "name": "id", "type": "Int64", "nullable": true } ],
  "messages": [ { "severity": "warning", "code": "partial_results_truncated", "text": "…" } ],
  "pipelineWarnings": [
    { "code": "security_predicate_applied", "severity": "info",
      "text": "…", "sourceService": "validator", "metadata": { "sourceStage": "security" } }
  ]
}
```

**`pipelineWarnings` is always present** — an empty array when nothing happened, otherwise the
trail of everything the platform did to keep the answer legal: a row-level filter injected, a
column masked, a cap applied, a type coerced. This is the provenance attachment the contract
guarantees. Surface it to your user; do not swallow it.

## The tools

### `query-mcp`

- **`query`** — run a query through the governed path (translate → validate → dispatch → worker).
  Required: `source` (query text), `source_language` (`sql` | `transdsl` | `dfdsl` | `rel_node`).
  Optional: `parameters` (bound map; types inferred from JSON), `session_id` (sticky routing, only
  honoured when the worker advertises `supports_stateful_sessions`), `format` (`json` default |
  `csv` | `tsv` | `markdown`), `row_limit` (clamped to `[1, 5000]`, default 500),
  `hide_columns_matching` (regex list), `row_numbering` (`none` | `one_based`). Output: the result
  envelope above.
- **`compile`** — the front of the pipeline only (parse → validate; nothing executes). Required:
  `source`, `source_language`, `target_dialect` (`mssql` | `postgresql` | `mysql_mariadb`).
  Optional: `parameters`, `apply_security` (default `true`; `false` needs an admin role or you get
  `permission_denied`). Output: compiled SQL + `parameterPlan` (`[{name, type, bound, label?}]`).

### `meta-mcp`

- **`get_model(packages[], include_search_hints?, include_roles?, include_drill_map?, locale?)`** —
  the heavy call: a ModelBundle (entities, relations, tables, views, pattern/named queries, roles,
  drill maps, package versions).
- **`get_object(id)`** — typed detail by qualified name (e.g. `er.entity.artikl`).
- **`list_objects(kind?, package?, binding?)`** — the objects in scope (drained pagination).
- **`list_queries(package?, parse_status?)`** — query descriptors with SQL template + parameters.
- **`resolve_area(area)`** — the package set a subject area spans.
- **`search(query, limit?)`** — top-N over names, localized labels, aliases and search keywords,
  with scores and snippets. This is usually your first call: it turns the user's words into
  qualified model ids.

### `lex-matcher-mcp`

- **`match(name, category?, algorithm?, limit?)`** — fuzzy candidates over model-declared searchable
  fields. `algorithm` cascades `LEVENSHTEIN` | `TATRMAN` | `JARO_WINKLER` (precision first,
  recall fallback). Czech-aware diacritic handling is contract-observable — the conformance suite
  asserts it.

### `grounding-mcp`

- **`ground_time`, `ground_geo`, `ground_money`** — deterministic grounding of universal spans
  ("last quarter", a place name, "5 mil. Kč") into typed values. Deterministic by construction:
  the same span grounds to the same value.

### `resolver`

- **`resolve.bind:v1`** — deterministic entity resolution against the model's vocabulary. One tool,
  fresh call **or** clarification-resume (an opaque `resumeToken` carries an unfinished
  resolution). Its refusal discipline is the point: a below-threshold match becomes a clarifying
  question, never a fabricated binding. See [The identity contract](identity.md) for who the
  resolution runs as.
- **The conceptual-model tools** — on the same door and behind the same identity gate.
  `statement.validate:v1` · `statement.lower:v1` · `statement.explain:v1` take a statement frame over
  concepts (measures, filters, grouping, a time) and check it, lower it to the md query it means, or
  say it as a sentence. The three `assertion.*:v1` tools do the same for a claim to record: an event's
  occurrence, a row with its validity, or a phase's episode.
  `definition.render:v1` renders a defined concept (`cn.OverdueInvoice`) as SQL over entities or as an
  md predicate, and `concept.search:v1` returns the concepts a span may mean. A refused frame is a
  result carrying its `cn/*` findings, never a partial plan. The frame tools answer
  `CN_MODEL_UNAVAILABLE` until the deployment mounts its model (the one its lexicon was compiled
  from); `concept.search:v1` reads the vocabulary alone.

## Refusal modes

A well-behaved answer sometimes says *no*, and your agent must handle it rather than paper over it:

- **`ok: false` with an `error_code`** — the governed result was denied or malformed (a denied
  column, a permission failure, bad arguments). The envelope tells you which.
- **Clarification** — `resolve.bind:v1` returns options + a `resumeToken` instead of a binding when
  the user's words are ambiguous. Ask the user; resume with their choice.
- **Empty** — a resolution with zero bindings: the platform found nothing confident and said so.
  This is a correct outcome, not a failure to retry.

The guiding rule the whole surface is built on: **refuse over guess.** An agent that fabricates an
answer where the platform refused is a bug — and the [conformance suite](conformance-harness.md)
is how you prove yours does not.
