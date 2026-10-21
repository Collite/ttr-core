# Changelog

All notable changes to the published `org.tatrman:*` Kotlin artifacts are
recorded here. While versions are `< 1.0.0`, minor bumps may contain breaking
changes (see [`PUBLISHING.md`](PUBLISHING.md) → Semver discipline).

## Unreleased

- **`ttr-translator`** — **`refuseUnconditionedBareJoins`** (new option on `Translator.parseToRelNode`, `translate`
  and `explain`, default `false`; ttr-server#232). Set, a SQL statement whose **bare** join (`JOIN` with no `ON`) stays a
  Cartesian product is refused as `join_unconditioned` (`JoinerMessages.UNCONDITIONED`). The message names the join's
  sides and why, from what each joiner found on that join, and the way out: `… no relation or foreign key joins
  entity.calendar and entity.product; write the ON`. It does not offer `CROSS JOIN`: the text reaches an LLM's repair
  round, which would turn the forgotten `ON` into the warned Cartesian product.
  - A bare join stays a Cartesian product when the model's relations cannot condition it (none, more than one, an entity
    repeated across it, or one relation with no join pairs, Hartland's FK-only shape) **and** the physical stage cannot
    condition it from a table foreign key either. So a join a table FK completes is not refused, and neither is a
    derived table whose table an FK reaches. A bare join between two derived tables (CTEs, aggregates, a `UNION`),
    which the joiners cannot see into, is refused.
  - **Decided per join.** The SQL carrier marks each bare join it leaves unconditioned (`JoinNode.condition_required`,
    below); the stage that conditions a marked join clears the mark; a join still marked and unconditioned after the
    physical stage refuses the parse. No other join's verdict can refuse it or excuse it: an author's `CROSS JOIN` with
    the same sides elsewhere in the statement no longer matters.
  - **Where the physical stage runs.** On a `DB` target that is the same call. On an `ER` target (the first call of the
    two-half pipeline) the plan comes back marked, with its warnings, and the REL_NODE re-entry refuses it, with no
    option needed on that call: the mark is the request. A plan parsed without the option carries no mark, so its
    re-entry refuses nothing.
  - The comma list and `CROSS JOIN` are the author's own Cartesian products: never marked, and they keep their
    `JOIN_NO_RELATION` warning.
  - **A bare `JOIN` its WHERE conditions is accepted** (`FROM a JOIN b WHERE a.x = b.y`): the author wrote the
    condition, in the wrong clause. Each top-level `AND` condition that reads both sides of a marked INNER join, and
    nothing outside it, moves into that join's `ON` (the lowest such join in a chain), so the plan is the one the `ON`
    form gives: no refusal, no warning, and no relation or foreign key stacked on the author's condition. A bare
    `LEFT`/`RIGHT`/`FULL JOIN` is still refused that way, because a WHERE condition on both of its sides drops the
    rows the outer join keeps, so the author meant `ON`. A condition with a sub-query stays in WHERE. Without the
    option nothing moves.
  - Unset, nothing changes: no mark, and the join is a Cartesian product with its warning, byte for byte as before.
  - The two plan stamps that ride Calcite as hints (`condition_required` on a Join, `expanded_from` on a Project) are now
    registered with every cluster the translator builds (`TtrHintStrategies`), each un-inheritable. Calcite propagates
    hints when it decorrelates a sub-query and asserts every name is known, so a stamped plan with a correlated
    sub-query failed to parse (`hint … must be present`; an NPE without `-ea`).
- **`ttr-plan-proto`** — **`JoinNode.condition_required = 5`** (bool; reserved range now 6 to 9; ttr-server#232). Set only
  by the translator, and only on a SQL parse asked to refuse unconditioned bare joins: a `JOIN` without `ON` that the
  model's relations could not condition. It stays on the join while the join has no condition. Proto3: a reader that
  does not know it ignores it, and an unmarked join serializes exactly as before.

- **Grammar 0.16 → 0.17 — the conceptual model (CN-P1; `ttr-parser`, `ttr-semantics`, the TS and Python parsers).**
  `model cn` with `def kind` · `event` · `quality` · `phase` · `relation_type` and the cn `def role` on one shared
  body (typed `description`, `label`, `tags`, the stereotype refs, `grounds`, `involves` with `as`-labelled
  participants, `period`, `extent`, `basis`, `value_space`, `definition` / `members` as expression blocks, inline
  `lexicon`, then free-form properties in authored order); ten binding kinds under `model binding`
  (`cn2md_kind | role | quality | event`, `cn2er_kind | role | event | quality | phase`, `cn2db_concept`); the
  `concept { … }` sugar inside `cubelet`, `dimension`, `domain`, `measure`, `entity` and `attribute`; lexicon
  entries gain `default:` and `sense_priority:` and take `for: cn.<X>`. Additive — every 0.16 file parses
  unchanged and every conformance golden 01–74 is byte-unchanged; fixtures 75–79 added, zero drift on the three
  targets. `ttr-parser` (Kotlin): `ConceptDef`, `ConceptBindingDef`, `ConceptSugar` (on entity,
  attribute and the four md hosts), a sealed `ConceptProperty`, `EmbeddedBlock`; `LexiconEntryDef.default` /
  `sensePriority`; codes `ttr/duplicate-property` (also a second `concept { }` on a host),
  `ttr/expression-block-required`; `EmbeddedBlock.location()` counts code points and steps over a string
  form's `escapes` (a backslash escapes any character there, a line break too). `ttr-semantics` carries the cn
  defs through unchecked (the cn semantics, the typed model and `ttr-writer` support are CN-P2). `model cnc` and
  `roles:` are deprecated (they parse unchanged). Tooling: TextMate highlighting and the formatter's cn layout
  (see `packages/grammar/CHANGELOG.md` 0.17). Manual: [Conceptual model (cn)](docs/manual/en/20-conceptual-model.md).
- **Grammar 0.17.1 — the cn semantics (CN-P2; code only, `TTR.g4` stays 0.17).** What CN-P1 carried through
  unchecked is now resolved, desugared and checked, the same on TS, Kotlin and Python (conformance `out-ts-sem` 01–106
  zero drift on three targets):
  - **`ttr-semantics`** — `cn` is a model code: a concept keys `<pkg>.cn.concept.<Name>`, an event's participant
    labels are its children (a bare participant's label is its concept's name lower-cased). A reference from a cn slot,
    or written with the `cn.` head, resolves **among concepts only**; an element reference never reaches a concept, so
    no existing reference moves. `grounds:` and `concept { }` desugar to canonical bindings (`<Concept>.grounds`,
    `<Concept>.concept`); **one grounding per concept** — a second is `cn/duplicate-grounding`, the first is read.
    The model-level `cn/*` slugs (contracts §7): `duplicate-grounding`, `grounding-target-kind`, `role-no-event`,
    `phase-double-definition`, `hierarchy-shape`, `participant-path-broken`, `involves-unlabelled-duplicate`,
    `unknown-relation-type`, `antirigid-supertype`, `db-grounding`, `ungrounded`, `phase-no-definition`,
    `event-no-period`, `cnc-deprecated`, and `ttr/required-property-missing` for a bearer-less quality or phase (the
    lexicon pair `duplicate-default-sense` / `phase-as-default` is TS-only).
  - **Resolution: the slot-filling fallback on Kotlin and Python (ttr-core#228).** After the project-wide unique match,
    `Resolver.resolveReference` reads a reference into its slots and looks for its name path in the reference's package
    and model (its kind too, when written), then in its model, then anywhere — TS's `slotResolveReference`. So
    `grounds: er.entity.store` in `hartland.west` names west's `store` beside `hartland.east`'s (D10 "current package
    first"), and `md.revenue` names the md measure beside a lexicon term `revenue`; a scope with several candidates is
    `ttr/ambiguous-reference`. Before, both stayed unresolved on Kotlin and Python while TS resolved them, and the cn
    tier read the gap as a dangling grounding. Fixtures 102 and 106.
  - **The sugar merge rule reads a property's meaning alike on the three runtimes.** An object's entries in any order
    say the same (`period: { end:, start: }` = `{ start:, end: }`); a `lexicon { }` block is compared by its five known
    keys, each form quoted (`["red,blue"]` ≠ `["red", "blue"]`). Fixture 103.
  - **A `concept { }` on a measure written inline in a cubelet's `measures:` is `cn/grounding-target-kind`** — it
    grounds nothing (the inline measure is no symbol a grounding could name); it was dropped silently on all three.
    Fixture 104.
  - **`ttr-writer`** — `TtrRenderer` writes concept defs, the cn bindings and the `concept { }` sugar in the
    formatter's layout; expression blocks re-parse byte-exact.
  - **`ttr-metadata`** — the typed tier `CnSchema` (`SchemaCode.CN`, library-only): every concept with what the
    project states of it, its one grounding, where it lands on the er layer (`erTarget`, `via` DIRECT | MD_HOP,
    `grade`; a defined phase inherits its bearer's), the md → er hop seam (`MdErHop`; P2 fills it from explicit
    `md2er_cubelet`: `ExplicitMdErHop`, else `md/er-unmapped` / `md/er-ambiguous`), the reverse index
    (`liftsTo`), lexicon links, open-tier relation types. S2.7 adds `Concept.hop` (the hop's answer for the concept's
    own md grounding) and `CnSchema.elementTerms` (canonical lexicon terms on md / er elements, by ref).
- **`TTRP.g4` 0.4 → 0.5 — the conceptual-model definition forms (CN-P2 S2.6, CN-D29; `ttrp-frontend`, Kotlin
  only).** A cn `definition:` / `members:` / `filter:` block is a TTR-P expression; 0.5 adds what it needs and 0.4
  lacked: `[not] exists C [v] (, C [v])* involving p [as label] … [where e]`, the concept aggregate `count(C involving
  … [where …] (, name: e)*)` + `.quality`, `x [not] in <call>` (`code in descendants(Kind, 'X')`), the temporal words
  `at_or_before before contains ended_before overlaps` (infix, comparison level), `this` (+ a path), and `end` after a
  dot. New tokens, all soft (still names): `exists involving where this at_or_before before contains ended_before
  overlaps`. Every 0.4 document parses to the same AST. The AST gains a sealed `ConceptForm` family (`ExistsExpr`,
  `ConceptAggregate`, `ThisRef`, `InCall`, `TemporalPredicate`); in a TTR-P program the typechecker refuses each with
  **`TTRP-CN-001`**, and the emitters treat them as `UNSUPPORTED_NODE`. Where 0.4 already spelled a construct the cn
  spelling is TTR-P's: `:as_of`, `:name`, `hours(24)`, `within(x, last: d)`, `<>` (cn contracts §1.1 "The 0.5
  spellings"). **`TtrpParser.parseExpression` reads the whole input** (`standaloneExpr : expr EOF`, ttr-core#202): a
  tail after the expression is `TTRP-PRS-001` — it was dropped silently. The TTR-P formatter renders `<>` as `<>` (it
  wrote `!=`, which does not lex).
- **`ttr-concept`** (not published) — **`ResolvedCnModel(model, definitions, snapshotHash)`** replaces the placeholder:
  the reconciled `Model`, every definition block parsed with `TTRP.g4` 0.5 and name-resolved (`CnDefinitions`: the
  definition's reads — concepts, inlined definitions, `:name` parameters, `:as_of`, instant comparisons), and the
  definition-level `cn/…` slugs (`definitionDiagnostics()`): `cn/definition-syntax`, `cn/definition-unknown-name`,
  `cn/descendants-no-hierarchy` (new), `cn/definition-cycle`, `cn/definition-unresolved`,
  `cn/definition-unreachable(md|er)`, `cn/basis-mismatch`, `cn/imprecise-comparison`. `Diagnostic` gains `location`.
  **The hygiene report v2** (S2.7, contracts §7): `CnConceptService(model).hygieneReport()` — per concept its grade,
  `via`, grounding and the hop column, its landing, core vs open (the free-form keys and `relations:` edges listed),
  dangling grounding refs, redundant term duplicates (a form a concept's term shares with a term on an element that
  lifts to it), phase-as-default rows and retired codes (none until vocabulary versions register) — then the
  definition-level findings; `HygieneRender` renders it as JSON and as markdown. **`ttr cn hygiene <dir>
  [--format text|json] [--fail-on error|warning]`** (an `application` entry point; `just cn-hygiene <dir>` runs it
  from the repo until the module publishes). The other service families are still CN-P3. A usage error (an unknown
  flag or value, a missing argument) exits **2**, as the contract pins — clikt's own 1 read as "findings at
  `--fail-on`"; `just cn-hygiene` runs the installed distribution, so the exit code and a path with spaces survive.
  The markdown escapes authored text (term forms, open-tier entries); a nested list in a participant or `via:` path
  is read through, as TS reads it.
- **TTR-P — the recipe checker (AU-core-P1; `ttrp-frontend` · `ttrp-graph` · `ttrp-cli`).** A recipe is checked
  against the world and the model: the role shape and wiring (`TTRP-REC-000`), the action wiring typed against
  `def action` (`-001`…`-003`), the candidates' key (`-004`, `-005`), key lineage with each context's class (lookup /
  per-candidate / set-level), the recipe's mode verdict and windows (`-010`…`-012`), parameters (`-020`, `-021`),
  transitions against `states:` (`-030`), the turn rule (`-040`), snapshot measures (`-050`, a warning), writes in an
  island (`-060`) and a decision with an island of its own (`-070`); time is typed — `:as_of`, durations and date
  arithmetic, `working_days(between:, and:)`, `age(since:)`, and the `latest(by:, per:)` / `within(attr, last: … |
  from: …, to: …)` ops. `ttrp explain` adds the checker's section to a recipe's skeleton; `ttrp check` runs the
  checker on a recipe. `ttrp build` still refuses a recipe (`TTRP-REC-099`) until the recipe compile.
  Also, for every TTR-P program: a load may name `er.entity.<name>` (the imported entity); the language design's
  argument forms are read — `join(…, kind: left)`, an op as an argument (`right: load(…)`), `calc(a = e)`,
  `select(c, x = e)` (they were parsed and silently dropped); in a TTR-SQL island a select item's `AS alias` names
  its column (it was dropped), a qualifier of any table joined so far reads the next join's left side, and
  `days(n)` / `days(:p)` are durations. A world's automation executors (`extends: n8n_l1`, …) no longer fail the
  TTR-P world binding (`TTRP-WLD-005`).

- **`ttr-translator`** ⚑ **stackable queries: a logical query read by another is a temporary entity** (LQ-T6, L-30).
  In a logical body, `FROM obj.query.x r` (a logical query served under OBJ, as a metadata worker and now the
  translate service serve them) is a side of a bare `JOIN` like an entity. Its attributes are the query's output
  columns, and **its relations follow its columns**:
  - a column that passes an entity attribute through unchanged (as is, renamed, or a `GROUP BY` key) keeps that
    attribute's relations; a relation is inherited when every one of its attributes on that side is passed through;
  - a relation bound only to its foreign key (no `join:` pairs) is inherited too: its pairs are read from the FK
    through both entities' column bindings (a query-bound entity's through that query's column lineage);
  - a column passing all of an entity's key attributes through relates the temporary entity to that entity on them
    (`FROM obj.query.top_customers t JOIN customer`);
  - an aggregate or computed column carries none.

  So `SELECT shopper.last_name, r.revenue FROM obj.query.shopper_spend r JOIN shopper` is conditioned
  `r.shopper_id = shopper.shopper_id`; it was `ON TRUE`, a Cartesian product with `JOIN_NO_RELATION`. Two candidate
  relations stay unconditioned and warned, never a guess. Only the bare `JOIN` is decided this way: the comma list and
  `CROSS JOIN` reach the wire carrier after UNFOLD has inlined the body, and stay `JOIN_NO_RELATION` as a derived
  table does. A db query read by name stays a derived table. New internals: `ColumnLineage` (Calcite column origins
  over a stored plan), `RelationPairs`, `TemporaryEntity`; no public signature changed.
- **`ttr-translator`** — an FK-only relation to an entity stacked on a query whose tables sit behind an `Aggregate`
  (ttr-core#204) is conditioned. `JoinPolicy` reads its pairs from the FK through the column bindings, where
  `JoinerPhysical` could find no table to match the FK against and left the join a Cartesian product with only the
  INFO `JOIN_RELATION_WITHOUT_PAIRS`. When no pairs can be read (the FK column is not a group key), the join is now
  `JOIN_NO_RELATION` at WARNING, with that side rendered as a derived table, instead of silent. Everywhere else an
  FK-only join is decided exactly as before: hartland, the investment model and the veles fixtures give byte-identical
  stored plans, warnings and SQL.
- **Grammar 0.15 → 0.16 — the automation model (AU-core-P0 · S0.2; `ttr-parser`, `ttr-writer`, `ttr-semantics`,
  `ttr-metadata`, the TS and Python parsers).** `model automation` with `def parameter` · `action` · `service` ·
  `approval` · `program` · `recipe` (one permissive body: typed properties first, then free-form entries whose
  values add durations `2 working_days`, tagged strings `schedule "…"`, call-shaped values and `*`); `def role` gains
  `resolve` / `delivery` / `members`; `transition: order.state -> held { from: placed }` and `set: a.b = column`; the
  world gains `def identity`, unnamed `def grant`, `def deployment` and `def process … steps: [ def step … ]`;
  attributes gain `states:` / `transitions:`. Additive — every 0.15 file parses unchanged and no conformance golden
  moved; fixtures 71–74 added. `ttr-semantics`: `AutomationValidator` / `StatesValidator` (`ttr/automation-*`,
  `ttr/states-*`), with a TS twin in `@tatrman/semantics` and LSP hover. **`ttr-metadata`**: a new tier
  `SchemaCode.AUTOMATION` (`AutomationSchema` — parameters, actions, services, roles, approvals, programs, recipes as
  typed objects), `World.identities / grants / deployments / processes`, `WorldStorage.writable`,
  `Attribute.states / transitions`, and the shipped stdlib entities `automation.obligation`, `state_history`,
  `approvals`, added to any source that declares `model automation` or imports an `automation` package. Manual:
  [Automation](docs/manual/en/19-automation.md).
- **TTR-P toolchain (`ttrp-frontend` · `-graph` · `-emit` · `-cli` · `ttrp-lsp`) — the recipe block (AU-core-P0 · S0.1).**
  TTR-P grammar 0.3 → 0.4 (additive): `recipe { key / as of / parameters / document / protocol }` with `candidates` /
  `context` role containers (island forms `target` · `builtin` · `foreign … per candidate | set schema` · `call` ·
  `turn … ask … answer`, an optional `window { by: over: }`), a `decision` ending in a `switch` statement (routes,
  `mode:`, `else:`, `because:`, `detail:`) and an `actions { }` wiring block. Expressions gain `:name` bindings, the
  duration literal (`30 days`, `days(:p)`) and named function arguments; `aggregate { group_by: k }` is accepted beside
  `group by k`. Every new word is a soft keyword — existing names keep working. New AST (`ast/Recipe.kt`), new
  expression nodes (`ParamRef`, `DurationLiteral`, `DurationParam`) and `FunctionCall.argNames` (empty for positional
  calls). `ttrp explain` prints a recipe's skeleton; `ttrp build` refuses a recipe (`TTRP-REC-099`) until the recipe
  compile lands. New diagnostics `TTRP-PRS-006` / `-007`. Manual: [Recipes](docs/manual/en/18-recipes.md).
- **TTR-SQL fragments** — `:name` is a parameter binding (it was silently read as a column: the lexer dropped the
  `:`); a clause keyword is a name after a dot (`er.entity.order`); any character the lexer cannot read is now the
  generic reject instead of being dropped.

- **`ttr-translator`** — `Translator.savedQueryReferences` (and `SavedQueryReferenceExtractor.fromSource` under it)
  now promotes Calcite's literal charset to UTF-8 first, like `parseToRelNode` and `unparseFromRelNode`. Calcite
  freezes the charset when it first loads; a metadata worker calls this for every saved query before its first parse,
  so on 0.12.1 Calcite stayed on ISO-8859-1 for the process and every later body with a literal outside Latin-1
  (`'Poštovné'`) failed — `Failed to encode … in character set 'ISO-8859-1'`, or a validation error that names none of
  it when the literal sits under the typed-placeholder retry. The test JVM pins `calcite.default.charset`, which hid
  it: `SavedQueryReferencesCharsetSpec` runs the sequence in a JVM of its own.
- **`ttr-concept`** (new module, **not published**) — the conceptual-model (cn) layer below the determinism
  frontier: `ConceptService` (statement validate/lower/explain · assertion validate/lower/explain ·
  `definitionRender(md|er)` · `materialize` · `hygieneReport`) as an **interface** with a `NotImplementedConceptService`
  stand-in, plus its result types. Scaffold only (CN-P0 S0.4): every body is CN-P3. A `verifyNoLlmDependency` task in
  `check` fails the build if the module's runtime classpath ever resolves an LLM, gateway, kantheon or HTTP-client
  artifact. Not in any publish bundle yet (see `publish.yml`'s header).
- **`ttr-plan-proto`** — three **pre-release** packages for the cn frames: `org.tatrman.concept.v1` (`refs.proto`:
  `ConceptRef`, `Candidate`, `Confidence`, `MemberRef`, `Instant` + `Granularity`, `Period` + `EndState`, `Value`),
  `org.tatrman.statement.v1` (`statement.proto`: the read frame, `as_of = 10`; `ASK_SET` reserved and refused) and
  `org.tatrman.assertion.v1` (`assertion.proto`: the write frame, `asserted_as_of` apart from `period`). They ship in
  the jar from the next translator tag but are **not a stable wire** until cn's P3 freezes them — no consumer
  should depend on them before then. The jar now bundles 9 `.proto` files (`verifyProtosInJar`). The Python wheel is
  unchanged: it lists its protos explicitly.

- **`ttr-metadata`** — a `def query` in a `model db` file loads as the db query it is (`db.<schema>.query.<name>`,
  the same object a directive-less file declares); it was refused as `ttr/wrong-file-kind` (ttr-core#193).
- **`@tatrman/semantics`** — binding targets are references (ttr-core#194): an entity's
  `binding: { target: { table | view | query: … } }` and a `def er2db_entity`'s `target:` are in the reference index,
  so find-references, rename and go-to-definition on a table, a view or a query (db or logical) reach the entities
  bound to it. New `collectBindingTargetReferences`.
- **`@tatrman/semantics`** — a short binding target (`x`, `<schema>.x`) resolves as the metadata loader reads it: the
  db lane first (`db.dbo.x`, `db.<schema>.x`) for a `table:`, a `view:`, a `query:` and a bare `target:` alike, never
  the entity of that name; a short `query:` whose default names no query falls to the one query of that name in
  either lane (a logical query, or a db query in another schema), and none or several leave it unresolved. The column
  bridge (`binding: <column>`) reads the entity's target the same way, so attribute bindings under a short
  `table: x` are navigable too. Short targets are resolved by canonical key, not by a full resolver walk.
- **`@tatrman/lsp`** — prepareRename / rename on a binding target, a mapping column or an fk renames the object it
  names; it renamed the enclosing entity (definition, references and hover already followed the ref).
- **`ttr-metadata`** — `ttr/wrong-file-kind` derives the kinds a `model db` / `er` / `cnc` file may hold from
  `modelForKind`, so it cannot drift from the symbol table: a `def drill_map` in a `model db` file and a
  `def er2cnc_role` in a `model cnc` file load (both were refused). `def area` and the `def project` header are
  tier-neutral, like `def schema`.

- **`ttr-translator` + `ttr-plan-proto` + `ttr-metadata` + `ttr-semantics` / `ttr-parser`** ⚑ **logical queries
  (LQ) — a saved query written over the E-R model, and entities stacked on it.** A `def query` declared in a
  `model er` file is a *logical query*, `er.query.<name>`: its body is SQL over entities (entity and attribute
  names, a bare `JOIN` conditioned from the relations' `join:` pairs), and an entity bound to it
  (`binding: { target: { query: er.query.<name> } }`) is an ordinary entity whose attributes bind to the
  query's output columns. A query anywhere else stays the db-layer object it was (`db.<schema>.query.<name>`).
  Everything new is opt-in; every existing caller is byte-identical.
  - **`ttr-semantics`, `@tatrman/semantics`, Python `ttr_parser.semantics`** — `modelForKind(kind, declaringModel)`
    / `buildCanonicalKey(…, declaringModel)`: the file's `model` directive moves only a `query` (to `er`). Kotlin
    keeps the old signatures (`@JvmOverloads`). Conformance fixture `70-logical-query`, identical in all three
    runtimes.
  - **`ttr-parser`** — new `DiagnosticCode.LogicalQueryParameters` (`ttr/logical-query-parameters`): a logical query
    with `parameters` (a parametrized query stays a db query). A warning in both walkers (an error would empty the
    file in Kotlin and make TS whole-file tools refuse it); the load-time refusal is ttr-metadata's.
  - **`ttr-metadata`** — a `model er` query loads as `Query((ER, "query", name))`; the er file-kind allowlist admits
    `query`; export writes logical queries to their own `er-query.ttr` under `model er`; the reconciler refuses a
    logical query with parameters (`logical_query_parameters`). A short `query:` binding target (`query: x`,
    `query: query.x`) whose db-lane default names no loaded query resolves to the one loaded query of that name — a
    logical query — as the editor resolves it.
  - **`ttr-translator`** — `Translator.parseToRelNode(…, strictLane = false, expandLogicalBodies = false)` and
    `translate(…, expandLogicalBodies = false)`:
    - `strictLane` parses a saved query in its declared lane: the schema detector runs although `sourceSchema` is
      stated, and anything it does not confirm fails — `mixed_lanes` (entities and tables together), `lane_mismatch`
      (the other lane, unknown names, no stated lane); a stated-ER query with parameters is
      `logical_query_parameters`. Codes are constants on `Translator` (+ `entity_query_output_mismatch`, reserved for
      the metadata workers).
    - `expandLogicalBodies` ends the ER half (ER target only) with the new stage `EXPAND_LOGICAL_BODIES`: an entity
      stacked on a logical query whose stored body is an ER plan is expanded in place under a boundary Project stamped
      `expanded_from = <entity>`, recursively (`entity_query_mapping_cycle` on a loop). Bodies that read tables
      (the `__filter` row-filter queries) are left to MAP_TO_PHYSICAL. A validator's ER pass can then secure the
      entities a stacked entity reads. MAP_TO_PHYSICAL closes a stamped boundary into the form it gives every
      `ToQuery` boundary and ends the stamp, so the physical plan is byte-identical with the option on or off.
    - `Translator.outputColumnNames(plan)` — a stored body's output names (for the workers' binding check).
    - REL_NODE re-entry: the stamp rides the decoded Project as a Calcite hint (its schema code by wire number, so
      an unknown code round-trips); the decoder never merges a stamped Project with an adjacent one, never pushes a
      limit straight above a boundary into the body's Sort, and an Aggregate over a boundary is neither pruned nor
      dropped as redundant (RelBuilder would otherwise break the on/off identity).
    - The strict lane is decided on the plan: the detector reads only the FROM clauses and leaves saved-query
      references (`obj.query.<name>`) out of its verdict, so after UNFOLD the plan must read the declared lane and
      only it — a sub-query (WHERE / select list / HAVING) or an unfolded saved query reading the other lane is
      `mixed_lanes` (beside the declared lane) or `lane_mismatch` (instead of it); a body whose lane is read only in
      a sub-query, or through a saved query of the same lane, parses. New: `SchemaDetector.detect(…,
      savedQueryRefsNeutral = false)` and `TableIdentifierExtractor.fromQuery(…, skipSavedQueryRefs = false)`
      (`@JvmOverloads`; defaults unchanged).
    - `EXPAND_LOGICAL_BODIES` refuses a stacked scan that declares no columns (`entity_query_no_columns`) rather
      than leave it unexpanded, where the entities its body reads would escape the ER-level checks.
    - ⚠ Binary compatibility: both entry points carry `@JvmOverloads`, so explicit-argument (Java-style) callers
      compiled against 0.11.x keep working; Kotlin callers that relied on default arguments call the synthetic
      `$default` method, whose signature changed — recompile against 0.12.0.
  - **`ttr-plan-proto`** — plan.v1 `ProjectNode.expanded_from = 3` (`QualifiedName`, optional; reserved `4 to 7`).
    The only wire change; readers that do not know it ignore it.
  - Test fixtures: `HartlandStackModel` (the MJ Hartland slice + `shopper_revenue` stacked on
    `er.query.shopper_revenue`, a two-level `big_spender`), published with the translator's test fixtures.
  - Known, pre-existing (not LQ): a literal compared with a FLOAT attribute encodes as `CAST(int)` on the first
    encode and as a FLOAT literal after a REL_NODE round trip, so a body with such a comparison yields physical plans
    that differ in that literal's encoding between the option on and off — the same difference today's single-call
    vs two-half paths already show.

- **`ttr-translator`** ⚑ **saved-query references a metadata worker can serve (LQ, D4).** A SQL body reads
  another saved query as `obj.<namespace>.<name>`; UNFOLD inlines its stored plan. The surface existed, but nothing a
  worker can build fed it. Additive for every body without an `obj.*` reference.
  - `Translator.savedQueryReferences(source, language)` — the `obj.*` references a body reads (FROM positions at any
    depth: joins, CTE bodies, derived tables, set-operation branches, sub-queries), as OBJ qnames, deduplicated,
    first occurrence first; the source as a worker holds it (`;`, `TOP`, hints, `{name}`). For parsing a model's
    saved queries in dependency order. Pure (no model).
  - An OBJ saved query with no declared `outputColumns` takes its row type — names **and** types — from its stored
    plan (`SavedQueryCalciteTable`), so `SUM(…) AS revenue` is numeric to the reader. Declared columns still win.
  - UNFOLD refuses a referenced body that declares parameters: `saved_query_parameters` (substitution is not
    implemented; before, the body was inlined with its parameters unbound).

- **`ttr-lexicon-compile`** ⚑ **the operator stdlib asks for a count, an average and a superlative
  (ttr-core#178).** A question asking "how many" or "average" resolved to the same lattice as a plain total,
  so a consumer writing a query from it answered a SUM. The stdlib grows from six operators to eight
  (`LexiconStdlib.OPERATORS`), and every estate archive built with this toolchain carries them.
  - **`op:count`** (`skills/count.md`, requires `count-subject`): distinct identities, not rows — a dimension
    that keeps history holds several rows per member. A counted word that names a measure ("how many units",
    „kolik činily tržby“) is the measure's amount, not a count. Triggers: cs *kolik · kolika · počtu · počty ·
    počtů*, and the diacritic-free *poctu · pocty* (EXACT; one edit from *počty · počtu* are *pošty · poštu*, the
    post office and the mail), *počet · počtem* (TYPOS(1)); en *how many · number of · count of · count* (EXACT).
  - **`op:avg`** (`skills/avg.md`, requires `avg-measure`): a sum over a count of the unit, from one result
    set, with the unit named in the answer. Triggers: cs *průměrný* in all eleven case forms, *průměrně ·
    průměr · průměru* (TYPOS(1)), *v průměru* (EXACT); en *average · averages · mean* (EXACT; one edit from
    *average* is *overage*, a surplus).
  - **`op:top-n` superlatives**: cs *nejlepší · největší · nejvyšší · nejprodávanější*, each in all six case
    forms (*nejprodávanějších* used to look up nothing), TYPOS(1); en *highest · biggest* (TYPOS(1)), *best*
    (EXACT).
  - **Left out on purpose**: en `per` and cs `na` (they name a grouping, a place or a share's whole far more
    often than an average's unit — "revenue per month" is twelve totals), and en `most` ("the most recent
    month" is a period). Bare en `number` is left out too (*order number*). A multi-word trigger is EXACT, as
    a TOKENS row fires on its fragment.
  - **`op:top-n` body**: a top-N with no number answers the top 10 and says so; it is no longer a gap. A
    question that asks for one thing in the singular ("which store has the highest …") asks for the top one.
  - New `StdlibOperatorWordsSpec`: every trigger looked up as the matcher looks it up, a guard of plain
    questions in which no operator may fire, and a check that each `Applicability:` paragraph names exactly
    the frontmatter's `requires:`.
  - ⚠ **Consumers**: `count-subject` and `avg-measure` are new requirement names. A consumer that reads
    requirements from the `Applicability:` prose and does not know them should refuse the operator (as it
    would any requirement it cannot evaluate) until it learns them. The archive schema is unchanged.

- **`ttr-translator`** ⚑ **behaviour fix — a filter that can never hold returns no rows instead of failing
  (ttr-core#148, ttr-server#203).** `RelBuilder.filter` folds a contradiction (`warehouse_name = 'Memphis DC'
  AND warehouse_name = 'Allentown DC'`, two disjoint date ranges ANDed) into an empty `Values`: the row type,
  no rows. The REL_NODE re-entry of the two-half pipeline writes it to the wire as a `ValuesNode` with
  `output_columns` and no `rows`, and `UnparseFromRelNode` then failed in every dialect with `Value count must
  be a positive multiple of field count`.
  - `PlanNodeDecoder`: an empty `ValuesNode` decodes to an empty `Values` whose row type comes from
    `output_columns` (each tag → the type the encoder took it from, nullable), so the operators above it are
    rebuilt against the plan's columns. It round-trips the wire byte for byte.
  - New `codec/sql/TypedEmptyValuesRelToSqlConverter`, used by `RelToSqlUnparser`: Calcite renders an empty
    `Values` as `(VALUES (NULL, …)) … WHERE 1 = 0`, and a bare NULL takes whatever type the engine picks.
    PostgreSQL picks `text`, so `SUM` over the empty relation failed with `function sum(text) does not exist`
    and a join on one of its columns with `operator does not exist: bigint = text`. DuckDB and SQL Server
    pick an integer. Each NULL is now cast to its column's type in the dialect's spelling:
    `CAST(NULL AS BIGINT)`, `VARCHAR(MAX)` / `VARCHAR` / `CHAR`, `FLOAT` / `DOUBLE PRECISION` / `DOUBLE`,
    `DATETIME2(0)` / `TIMESTAMP(0)` / `DATETIME`, `BIT` / `BOOLEAN` (SQL Server / PostgreSQL and DuckDB /
    MySQL). MySQL has no CAST to BOOLEAN, so that one NULL stays bare. A non-empty `Values` renders as before.
  - The answer: an ungrouped aggregate over the empty relation is its one row (`SUM` → NULL, `COUNT` → 0),
    anything else is no rows, and the result columns keep their names and types. This also applies when the
    filter folds during the unparse itself (a single-pass `Translate`), which already rendered untyped NULLs.
  - Types come back at the wire's resolution: a relation that crossed the wire carries surface tags, so a
    `decimal(18,2)` column's NULL comes back `DOUBLE`, as every decoded column does.
  - `EmptyValuesWireSpec`, `EmptyValuesUnparseSpec` (the ttr-server#203 and ttr-core#148 queries, a projection,
    an inner and a left join with one side empty × every dialect × `optimize` on and off, plus the per-dialect
    SQL for the ttr-server#203 query), `TypedEmptyValuesRelToSqlConverterSpec`. The plan wire format and the
    parse output are unchanged.

- **Grammar marker `0.15` — no syntax change; the toolchain bundle cuts at `0.15.0`.** `TTR.g4` is unchanged
  below its header. The minor moves because this bundle changes public JVM signatures in `ttr-metadata`
  (`MetadataRegistry.swap(…, layouts)`, new fields on `RegistrySnapshot`, `SourceSnapshot`, `LayoutFileRef`,
  `TtrgGraph`, `TtrgIssue`): a `0.14.x` patch would let a jar compiled against `0.14.0` meet them at run time as
  a `NoSuchMethodError`. Move the grammar and metadata refs to `0.15.0` together and recompile.
- **`ttr-parser` · `ttr-metadata` · image `ttr-viewer`** — **the catalog serves layouts (VL-P2).** A layout file
  committed under the model root reaches every viewer of that model (docs: *Operate → Deploy the Studio Viewer →
  Layouts the catalog serves*).
  - `ttr-metadata`: a source asked for layouts — **`FileBasedSource(…, layouts = true)`**, off by default;
    only a process that serves layouts asks — collects the layout files (every `.ttrg` with its sibling
    `.ttrl`, in the same listing as the model files, never parsed as model) as `SourceSnapshot.layoutFiles`
    (`source.LayoutFileRef`), and its version covers them (`ModelStorage.fetchVersion(extensions)`, new,
    defaulted; `LocalFsStorage` hashes only what the load reads), so a layout-only change is swapped in — and
    a designer's `.ttrg` / `.ttrl` write is no new model version where nobody serves layouts. A layout file
    that cannot be read is marked `LayoutFileRef.unreadable`, never a failed load.
    **`layout.LayoutIndex`**: `build(snapshots, model)` reads and resolves every layout file into
    `ResolvedLayout` (full-qname objects and positions, missing / ambiguous / ignored, `positionsFrom`, the
    path under the model root); a file that cannot be read or does not parse is a `LayoutIssue` (a sidecar's
    own under the `.ttrl`, one line each) and is not indexed; a name two files declare is served by neither,
    each an issue naming the other; a failure of the whole index costs the layouts, never the model.
    `RegistrySnapshot.layouts` (default empty) is published by `MetadataRegistry.swap(…, layouts)`, which
    `MetadataRefresher` now calls with the index built from the loads it reconciles.
  - `ttr-parser`: **`loader.TtrgLayoutReader`** — a `.ttrg` and its optional `.ttrl` → one layout document,
    the JVM twin of the viewer's reader: the sidecar's positions win, a sidecar without `canvas <graph name>` is
    read from `canvas main`, and a sidecar's residue (a key the graph does not list, nodes under a stated
    `mode: auto`) is dropped with a warning. The sidecar is read as the viewer's `readTtrl` reads it (a
    `viewport` or another unknown property skipped with a warning, bare node keys and `collapsed` items), not
    by the strict `TtrlLoader`, which is unchanged; the shared vectors gain the case `sidecar-lenient`.
    `TtrgIssue.sidecar` marks a finding about the `.ttrl`; `TtrgGraph.nodeLines` is new, defaulted.
  - Image `ttr-viewer`: the catalog proxy forwards `GET /model/layouts` and `GET /model/layout` (seven read
    routes); anything else under `<base>veles/` is still a 404, and a read route asked with another method a 403.
- **Studio Viewer (`@tatrman/designer`, image `ttr-viewer`)** — **layouts (VL-P1).** Pick the cards, place
  them, save the picture (docs: *Operate → Deploy the Studio Viewer → Layouts*).
  - The **Objects** panel: every card of the schema by package, with checkboxes, search, All · None ·
    Neighbours · Invert and a *Missing* group; the whole schema's first change makes the tab an unsaved
    layout. **Hop edges**: a dashed `…` edge between two shown cards joined through a hidden one, with an
    inspector and **Show**. A **view menu** on a card (Expand (+n) · Expand all levels… · Hide · Hide others ·
    Inspect) and on a hop edge (Show), beside the injected edit menu, never instead of it; shift-click
    multi-select. A checked card is placed beside its neighbour; **Re-layout** runs ELK over what is shown.
  - **Open layout…** (`.yaml`, or `.ttrg` + `.ttrl`; also by dropping a file) and **Save as…** (YAML or TTR,
    the exact text shown first); commands `layout.open|saveAs|new|relayout`; a tab with unsaved layout
    edits is marked `*` and guards a page leave. New runtime setting `VIEWER_LAYOUT_FORMAT` (`yaml` | `ttr`,
    default `yaml`; anything else stops the container with exit 64).
  - On a catalog: `layoutPersist` is now `'file'` (was `'none'`) — the tab holds the layout, **Save as…**
    downloads it; the spine lists the layouts the catalog serves (`GET /model/layouts`,
    `/model/layout?name=`, once a Veles serves them) after `db` and `er`, each with a shareable link. The
    truth chip says where the layout lives (`layout: file · sales.yaml`, `layout: catalog · sales`). The
    Export Layout button is gone (Save as… replaces it).
  - Supporting changes: `@tatrman/lsp` `ModelGraphEdge.kind` gains `'hop'` (+ `via`) and `ModelGraphNode` an
    optional `pkg`; `@tatrman/canvas-core` `CanvasEdge` carries `kind` / `via`; `@tatrman/tokens` gains
    `canvas.edgeHop`.
  - From the review (ttr-core#180): **Save as…** writes only what Open layout… reads — a plain subject's stale
    positions are left out, and the dialog names what the readers would refuse instead of saving it; for
    TTR it names the references the grammar cannot spell (a label, a keyword-named table) and offers YAML.
    The notation lives in the layout: an opened layout's skin reaches the canvas, a pick marks the layout
    edited. The shift-selection is exactly what is drawn and stays on its tab; a relation edge's right-click
    keeps the browser menu; the tab menu closes on an outside click and on Escape; a drag no longer
    re-projects the picture.
- **`ttr-parser` · `ttr-writer` · `ttr-metadata`** — **layout files (VL-P0).** A *layout* — a schema, the cards
  it shows, their positions — read and written as a `.ttrg` graph block with the in-file `layout:` block, its
  references resolved against the model (docs: *Model → Layout files*).
  - `ttr-parser`: **`TtrgGraphReader`** — `ttr-designer-server`'s `graph/TtrgGraphFile` moved here as
    `org.tatrman.ttr.parser.loader.TtrgGraphReader` (`parseHeader` / `findObjectsBrackets` / `parseObjects`
    unchanged; the designer server uses the moved object). New `scan` / `parseLayoutBlock` / `parseObjectRefs`
    read the in-file `layout: { nodes: { … } }` block and the `objects` list — bare or quoted keys, integer or
    decimal coordinates, comments and strings skipped, a viewport dropped. New `model.LayoutDocument` /
    `LayoutPoint` (canonical form: alphabetical order, coordinates `floor(x + 0.5)`) and `loader.TtrId` (whether a
    reference is a grammar `id`, by running the grammar's own `id` rule).
  - `ttr-writer`: **`TtrgLayoutWriter`** — the canonical `.ttrg` of a layout; reproduces the shared test vectors
    byte for byte. Every reference is written bare — the grammar admits only an `id` in `objects` and as a
    `nodes` key — so a layout holding any other reference (a label, a table named `date`) has no TTR form:
    `write` refuses it and `unwritable` names the references.
  - `ttr-metadata`: **`layout.LayoutResolver`** — the four-step resolution rule (full qname · qname ·
    `kind.name` · bare name; ambiguous never guessed; relations and foreign keys ignored) over a
    `LayoutModelIndex`; `indexOf(model, packageOf)` builds the index in the catalog's node shape, and
    `packagesFrom(sourceSnapshots)` supplies the packages the reconciled model does not keep.
  - Alongside (not Maven artifacts): `@tatrman/parser` gains **`@tatrman/parser/layout`** — the document, its
    canonical form, the `.ttrg` writer and the `.ttrg` + `.ttrl` reader, without the ANTLR runtime (the
    keywords that cannot be an `id` part are generated from `TTR.g4` at build time and held to the generated
    parser by its tests); `@tatrman/designer` keeps the YAML form and the resolution rule (`src/layout/`; the
    `yaml` package 2.9.1 is its one new dependency); and `@tatrman/package-sdk` publishes
    `schemas/layout.schema.json` (the YAML form, format 1) and `schemas/layout-vectors/` (twelve shared test
    cases every implementation of the format is held to; every `.ttrg` among them parses with `TTR.g4`).
- **`ttr-translator`** ⚑ **behaviour fix — no VARCHAR cast without a length reaches SQL Server.** T-SQL reads
  `CAST(x AS VARCHAR)` as `VARCHAR(30)`, and the MSSQL unparse wrote exactly that wherever the plan carried an
  unbounded VARCHAR: around the column branch of the CASE `COALESCE` expands to (`CASE WHEN [email] IS NOT NULL
  THEN CAST([email] AS VARCHAR) ELSE 'dept:sales' END`), for implicit casts (`CONCAT('x', <number>)`), and for
  an authored `CAST(x AS varchar)`. Every value longer than 30 characters came back cut, without an error.
  - `MssqlSqlDialectWithFloatCast.getCastSpec` spells a VARCHAR with no length `VARCHAR(MAX)`. A length the plan
    carries is kept as it is, `VARCHAR(MAX)` included; `nvarchar` without a length stays T-SQL's own `CAST`
    default, `VARCHAR(30)`, set at parse time (`TsqlDataTypes`).
  - New `codec/sql/VarcharCastElision`, on the MSSQL unparse only: the VARCHAR → VARCHAR cast Calcite wraps
    around a VARCHAR operand of another call (no length, or the operand's own length) is dropped, so
    `COALESCE(col, 'x')` renders as a plain CASE, and an `nvarchar(n)` column (VARCHAR(n) to Calcite) is no
    longer down-converted by `CAST(… AS VARCHAR(n))`. A top-level projection keeps its cast, as
    `VARCHAR(MAX)`; CHAR and number operands, any other length, and `TRY_CAST` are untouched.
  - Postgres: `varchar(max)` was written `VARCHAR(65536)`, and Postgres silently truncates an explicit cast to
    that length; it is now an unbounded `VARCHAR`. A bare `VARCHAR` is unbounded on Postgres and stays.
  - ⚠ The rendered SQL Server text changes for every statement that carried such a cast (golden strings
    downstream move: `CAST(x AS VARCHAR)` → `CAST(x AS VARCHAR(MAX))`, or the cast disappears inside a CASE).
    The plan wire format and the parse output are unchanged.
  - Not changed: `CAST(NULL AS <type>)` still renders as a bare `NULL`. plan.v1 carries a literal's type only
    as its surface tag (`text`, `bool`, `int`, `float`, `datetime`), so a decoded NULL cannot be given back its
    authored type (`decimal(18,2)` would come back `FLOAT`, `date` a timestamp); that needs a wire change.
  - `MssqlUnboundedVarcharSpec` (21 cases, the reported statement among them); the TF goldens `r12`, `r16`,
    `r24`, `CastPhysicalTypeSpec` and `TsqlPlusSpec` move with the fix.
- **`ttrp-frontend` · `ttrp-graph` · `ttrp-emit` · `ttrp-cli` · `ttrp-lsp`** ⚑ **TTR-B rules in Czech and English
  sentences (AG B1–B6).** A business rule — checks plus a decision that requests actions — can be written entirely
  in TTR-B sentences, in either language, and compiles to the same graph as its canonical TTR-P program.
  - **Keyword skins:** ONE `TTRB.g4` whose keyword token types are spelled by data — `ttrb/roster.en.yaml`
    (`"""ttrb`, `*.ttrb`) and `ttrb/roster.cs.yaml` (`"""ttrb-cs`, `*.ttrb-cs`, first line
    `# ttr: dialect=b lang=cs`). Keywords match case- and diacritic-insensitively against the active skin;
    identifiers are exact and take the Latin-extended range (Czech names). Czech fragments lower to exactly the
    tree their English twins do.
  - **Blocks:** `If <condition>:` / `Když <podmínka>:` + indented sentences = one `filter` output each; blocks
    overlap (not else-if); a nested block is `TTRP-B-110`.
  - **Count / attach / actions:** `Count the rows of T as x.` / `Spočítej x jako počet řádků T.` and
    `Attach T to the result.` / `Připoj T k výsledku.` cross-join a one-row table into the current row; the action
    sentences `Send an e-mail …` / `Pošli e-mail …`, `Set … of …` / `Nastav …`, `Create a manual task …` /
    `Vytvoř ruční úkol …` lower to `calc { <schema columns> } -> select(…)` and are routed — through a container
    OUT port the checker adds plus program-level `display(<kind>)` wiring — to the action displays `send_email`,
    `update_field`, `manual_task` (held to the imported `def schema`, `TTRP-DSP-*`). Recipients: a column, a
    department (`"oddělení:x"`), or a column with a department fallback (`coalesce`).
  - **File-backed fragments:** `container x(…) target <engine> from "rules/x.ttrb-cs"` (`TTRP.g4` 0.3, soft
    keyword `from`; `TTRP-FRG-004` file not found, `TTRP-FRG-005` no dialect marker). The LSP opens the file from
    the clause (definition + document link) and shows the fragment's findings on it.
  - **Diagnostics as help:** reject tables per skin (`ttrb/rejects.en.yaml`, `ttrb/rejects.cs.yaml`, replacing
    `rejects/ttr-b.rejects.toml`) — a message in the skin's language and a suggested correct sentence per row; new
    `TTRP-B-101…109` (stray character, block header, indentation, malformed e-mail / set / task / count / attach
    sentence, a keyword used as a name). The LSP checks bare `ttrb` / `ttrb-cs` documents; the VS Code extension
    registers `.ttrb-cs`.
  - **Fixed:** a TTR-B container's interior (sentences) was handed to the SQL engines as if it were SQL; it now
    emits from its decomposed members on every path (only `sql` / `pandas` fragments emit verbatim). A TTR-B
    `Join that …` after a transform no longer drops the transform.
  - **Ports, joins, conditional values (B7):** a container's IN ports are names in every sentence that names a
    table (`Načti orders.` reads the port — it was `load(orders)`, `TTRP-RES-001`); `Pošli to na výstup <port>.` /
    `Send that to output <port>.` binds a declared OUT port (`TTRP-B-111`, `TTRP-B-112`); `Spoj a volitelně s b …` /
    `Join a optionally with b …` (left join); `… přes vazbu r` / `… on relation r`; `Ponech jen řádky, které
    [ne]mají protějšek v b přes …` / `Keep only the rows that have [no] match in b on …` (semi / anti join);
    `Spočti x jako 1, když <p>, jinak 2.` / `Compute x as 1 when <p>, otherwise 2.` (`case when`).
  - **Grouping, naming, no silent sentences (B8):** `( … )` groups any sub-condition (`and` binds tighter than
    `or`); `Pojmenuj to jako <jméno>.` / `Call that <name>.` names the current value, `Načti <jméno>.` /
    `Load <name>.` makes a named value current again; **`TTRP-B-113` (error)** — a value a sentence produces that
    nothing reads (no later sentence, port or display): such a sentence used to vanish silently from the SQL.
  - **Fixed (`ttrp-emit`, plan.v1 / `sql-text`):** an unqualified column in a join condition bound to the wrong
    input — `on: kód = right.kód` compared the left's FIRST column, a left-only `on: stav = right.x` failed with
    `field [stav] not found`. It now binds to the left input when the left has the column, else the right. An
    island IN port fed by a program-level model `load(…)` (a bare program's derived in-port) reads the model
    object in a host statement instead of failing.

- **`ttr-parser` · `ttr-writer` · `ttr-semantics` · `ttr-metadata`** ⚑ **Grammar 0.14 — named row schemas,
  `def schema <name> { columns: [...] }`.** A new top-level, tier-neutral def kind (additive; every 0.13 file
  parses unchanged): an ordered column list, each column written exactly like a table column (`type`,
  `optional: true`, `description`). Kotlin `SchemaDef` (+ TS / Python twins, conformance fixture 69); the
  writer renders it. `ttr-metadata` surfaces it as **`Model.rowSchemas`** (`RowSchemaRecord`, keyed
  `<package>.<name>` — not a `ModelObject`, the `areas` precedent; defaulted, so constructors stay
  source-compatible); a re-declaration is `ttr/duplicate-schema`; never a `ttr/wrong-file-kind`. ⚠ The
  bundle re-cuts at **0.14.0** (the grammar minor moved).

- **`ttrp-frontend` · `ttrp-graph` · `ttrp-emit` · `ttrp-cli`** ⚑ **Action displays: `display(<name>)` held
  to an imported `def schema`.** When a row schema named exactly `<name>` is in scope (`import <pkg>.*`), the
  rows flowing into `display(<name>)` are checked: **`TTRP-DSP-001`** missing non-optional column (error),
  **`TTRP-DSP-002`** non-assignable type (error; assignable = same type, int → decimal/float, any scalar →
  text), **`TTRP-DSP-003`** a column the schema does not name — dropped from the display (warning). A display
  name with several sources that is NOT a declared schema is **`TTRP-DSP-004`** (error); two imports declaring
  the name is `TTRP-RES-002`. Row types cross program-level wiring (`a.p -> b.q`) for the check, and `select`
  narrows. The emitted display is the schema's columns in schema order (an absent optional column a typed
  NULL) on every path (`sql-text`, Polars, Postgres).
  - **Fixed:** several sources into one display name — `manifest.json` listed one `displays[]` entry per
    source but every entry carried the FIRST one's `source {island, port}`. Each entry now keeps its own
    source; same-named entries sit in wiring order; files are unique (`out/<name>.arrow`, then
    `out/<name>~2.arrow`, …), and Polars / Postgres islands write every sink of a port, not only the first.
  - Manifest v2 (additive): `displays[].schema` (the qualified schema) + `displays[].columns` (name / type /
    optional) on an action display; a port feeding both an ordinary and an action display keeps its own
    `sql-text` statement and gains a projected `<port>~<schema>` output the action display names.
  - **Fixed:** `cast(x as <type>)` on the SQL emit paths lowered to the `Expression.cast` oneof the translator
    does not decode (`TTRP-EMT-004 … CastExpression decoding is TODO`); it now lowers to the translator's
    function form with a physical cast code (`varchar:max`, `int`, `decimal:p,s`, …) wherever the translator
    renders it (`sql-text`, translator-rendered CTE nodes). The comma form `cast(x, string)` is a
    `TTRP-PRS-001` diagnostic instead of a `NullPointerException`, and `ttrp build` prints an emit failure as a
    diagnostic line (exit 1) instead of a stack trace.
  - **Fixed (`ttrp-emit`, Postgres):** a cast calc on a Postgres island is rendered raw by the CTE planner and
    named the TTR-P spelling — `calc { s = cast(order_id as string) }` emitted `CAST("order_id" AS string)`,
    invalid PostgreSQL (also `double`, `number`, `datetime`; a decimal lost its precision). It now names the
    Postgres type (`text`, `double precision`, `numeric`, `decimal(p,s)`, `boolean`, `timestamp`; `integer` and
    an unsized `decimal` are unchanged, byte-identical).
  - **Fixed (`ttrp-emit`, Polars):** a CSV load of a `decimal` field emitted a bare `pl.Decimal`, which
    Polars ≥ 1.42 rejects (`Decimal without precision/scale set is not a valid Polars datatype`) — every Polars
    island reading a decimal CSV column failed at run time. It now reads at the field's declared precision/scale
    (`{ type: decimal, length: 19, precision: 2 }` → `pl.Decimal(19, 2)`), else at **`DECIMAL(19, 2)`**, the
    toolchain default for an unsized decimal (the type the Postgres CSV ingest already used, which now also
    honours a declared precision/scale).
  - **Fixed (`ttrp-graph`, every engine — pre-existing):** a chain ending in a container OUT port
    (`o -> filter(status = 1) -> select(a, b) -> late`) built its nodes but left them dangling, and the port fell
    back to the body's last *assigned* value — the island wrote the chain's unfiltered head (Polars wrote
    `o_1`, the SQL paths emitted the unfiltered statement), even for an ordinary display. Chain-to-port now
    means exactly `late = o -> filter(…) -> select(a, b)` (`ChainToPort`, shared by the checker and the graph).
  - **Fixed:** a REQUIRED action-display column the rows lack was silently NULL-filled on Polars and Postgres
    (only `sql-text` refused it), and `ttrp check` missed it whenever the rows reached the OUT port through a
    chain (`… -> late`) — the display pass bound a port only from `late = …`. The checker now gives a
    chain-tail OUT port its row type (`TTRP-DSP-001/002/003` are reported), the Postgres emitter refuses an
    absent required column at build time (`TTRP-EMT-006 … (TTRP-DSP-001)`, as `sql-text` does), and a Polars
    island — whose frames have no static columns — stops with `TTRP-DSP-001` before it writes the display file.
    Only an absent `optional` column is NULL-filled.
  - **Fixed:** an omitted optional action-display column typed with a SQL spelling a generated model carries as a
    TTR-M type id (`bigint`, `smallint`, `numeric`, `money`, `time`, …) broke every `sql-text` build with
    `TTRP-EMT-006 … no SQL cast to 'bigint'` though `ttrp check` was clean (DSP-002 waved every unknown type
    through). Every scalar spelling now has a cast code (`SqlCastTypes`, from `ColumnType`); DSP-002 judges the
    real column types (`date` → `bigint` is an error now); **`TTRP-DSP-005` (new, error)** — a schema column no
    engine can produce (`object`, `list`, an unknown type id). An unsized `decimal` NULL fill is now
    `DECIMAL(19, 2)` (it was `DECIMAL(19, 0)`).
  - **Fixed:** the per-source files of one action display had different Arrow schemas, so a host could not
    concatenate them (`pa.concat_tables` → `ArrowInvalid`): a carried column kept its source type while a NULL
    fill took a per-engine default (Polars filled a decimal as `Float64`; a carried int stayed int under a text
    column). **Every projected column — carried or NULL-filled — is now cast to the type the schema column
    declares**, on every engine (`DisplayTypes`): text → `large_string`, integers → `int64`, `decimal(p,s)` →
    `decimal128(p, s)` (unsized: 19, 2), float → `double`, bool, `date32`, `time64[ns]`, datetime/timestamp →
    `timestamp[us, tz=UTC]` — the Postgres island casts the fetched Arrow table to that schema (ADBC returns
    `NUMERIC` as an opaque extension), so Polars and Postgres sources write identical files; a `sql-text`
    statement casts every column (`CAST("c" AS …)`). `ttr-metadata`'s `RowSchemaColumn` carries the structured
    type's `length` / `precision`; manifest `displays[].columns[].type` and a projected
    `islands[].outputs[].columns[].type` report the schema's own spelling (`decimal(12,4)`), no longer the source
    column's (`late.note: text` vs `large.note: string`).

- **`ttrp-cli` · `ttrp-lsp`** — **Fixed:** every bundle recorded `toolchain: org.tatrman:ttrp:0.0.0-dev` in
  `manifest.json` and `<prog>.compile-record.json` — the CLI (`build`, `run`, `conform`, `emit-determinism`,
  `deploy`) and the LSP built bundles with `BundleAssembler`'s default version. The Gradle project version is now
  stamped at build time (a generated `toolchain-version.properties` resource + the jar's `Implementation-Version`)
  and passed to every bundle build; **`ttrp --version`** prints it.

- **`ttr-lexicon` · `ttr-lexicon-compile`** ⚑ **`pred:` forms are EXACT, whole and negatable (LP
  review-103 F1/F12/F17/N5, ruling 1).** A `pred:` form authored `TOKENS` was scored over the QUERY's tokens,
  so the one-word window `názvem` matched *s názvem přesně* on its own and fired `pred:equals` — *customers
  named "Valmy"* became `name = ?`.
  - The stdlib slice (`lexicon-stdlib/predicates/string.lex.yaml`) authors **every** form `EXACT`, and grows:
    en *starting with · start with · begin(s) with · end with · ends/ending/end in · contain · equals*; cs the
    oblique participle cases (`-ícího · -ícímu · -ícím · -ících · -ícími`) of *obsahující / začínající na /
    končící na*; *se rovná · rovnající se*; and a negated counterpart for each where natural.
  - ⚠ **The closed `pred:` set grows by three** (D1): `pred:not_starts_with`, `pred:not_ends_with`,
    `pred:not_equals` (RG-LEX-030, the JSON schema). A consumer that does not render them refuses them, as
    for any unknown ref — kantheon and ttr-server must ship their half before an estate rebuilds its archive
    with this toolchain.
  - **`RG-LEX-032`** (new, error): a `pred:` form wider than `LexiconValidator.MAX_PREDICATE_FORM_TOKENS`
    (= 3, public — the resolver sizes its trigger windows from it). **`RG-LEX-031`** also refuses a phrase
    made of function words only (`with the`, `s na`); `not` (en) and `ne` (cs) join both stop-word twins
    (the lexicon guard and `ttr-metadata`'s keyword search).
  - The stories about who lowers a predicate now agree: the consumer (kantheon's fast-path renderer,
    `LIKE ? || '%'`), not the translator or the resolver.

- **`ttr-semantics` · `ttr-parser` · `ttr-writer` · `ttr-lexicon-compile`** ⚑ **`semantics { code_pattern: }`,
  and `TargetFacts.codeFormat` is always a regex (LP review-103 F6/F7, ruling 3, D4).** The compiler copied
  the code attribute's period `code_format:` — a date MASK such as `yyyyMM` — into a field the resolver
  compiles as a regex, so no period code was ever recognised, and letter-only codes (TPC-DS business keys)
  could not be declared at all.
  - The entity/table mention facet gains `code_pattern: "<Java regex>"`, legal only beside `code:`, compiled
    at analysis time. New **`TTR-SEM-219` `SemBadCodePattern`** (error): does not compile, empty, or no
    `code:`. `ResolvedEntitySemantics.codePattern` (defaulted, last); `Vocabulary.ALL_ENTITY_KEYS` gains
    `code_pattern` (no vocabulary-version bump — no proto enum moves); the TS twin, the lint rule
    `semantics-bad-code-pattern` and the writer follow. Grammar, parsers and conformance goldens unchanged.
  - `TargetFacts.codeFormat` = the declared `code_pattern`, else the period mask translated (`yyyyMM` →
    `^\d{6}$`), else null — never a raw mask. No archive-schema change: every reader already treated the
    field as a regex.

- **`ttr-lexicon-compile` · `ttr-semantics`** **legacy `nameAttribute:` / `codeAttribute:` reach the
  quoted-literal facet (LP review-103 F16).** An entity still on the legacy properties compiled to
  `nameRef = null`, so every quoted literal on it was refused as headless. The compiler now falls back to
  them where the semantics block names no `name:` / `code:` (a path qualified to another entity is not
  taken), and `TTR-SEM-218` says that quoting reads the column from the semantics block and the legacy
  property only as a fallback.

- **`ttr-metadata` · `ttr-lexicon` · `ttr-lexicon-compile`** ⚑ **member vocabularies (MV-T0) —
  `SearchHints.indexed` + `matchMethod` are two facts, and the compiled archive (`ttr-lexicon-compiled/v5`)
  knows every indexed attribute.** Before this, one bit — `SearchHints.fuzzy` — answered "is this carrier
  indexed?" under the name of "is it matched partially?", so `search { searchable method: EXACT }` had no
  member vocabulary at all.
  - **`SearchHints.indexed`** (new, in the constructor position `fuzzy` held): an authored `method:` of any
    kind — `EXACT` included — or the deprecated `fuzzy: true`. A bare `searchable` stays a hint. Derived at
    the one boundary (`toSearchHints`); `matchMethod` carries the method verbatim, and the deprecated
    `fuzzy: true` as the `TYPOS(1)` grammar 0.12 maps it to.
  - ⚠ **Source break, deliberate:** `SearchHints.fuzzy` is now a `@Deprecated` computed property
    `= MatchMethods.isPartial(matchMethod)`, not a constructor parameter, so `SearchHints(fuzzy = …)` /
    `.copy(fuzzy = …)` no longer compile — a producer must say which fact it means (`indexed`, `matchMethod`).
    Readers of `.fuzzy` compile with a warning and read the pre-MV value for every carrier that does not
    author both a method and `fuzzy`. `SearchHints.methodIsFuzzy` → `MatchMethods.isPartial` (deprecated
    forwarder kept).
  - `ModelToDefinitions` writes the method whenever there is one (`method: EXACT` for an indexed carrier
    with none) and never writes `fuzzy: true` back; an authored `fuzzy: false` is kept (it has no lossless
    replacement).
  - `MetadataQuery.ObjectFilter.indexedOnly` (new); `fuzzyOnly` is its alias. ⚠ `method: EXACT` carriers
    are now listed.
  - `Model.memberVocabularyCarriers()` (new) — the one definition of "has a member vocabulary": indexed
    attributes, plus indexed columns that do not back an indexed attribute.
  - **Archive v5:** `TargetFacts.memberVocabulary` (defaulted; additive in both directions — a v4 reader
    ignores it, a v4 archive decodes with `false`), and `targets` gains an entry for every member-vocabulary
    carrier whether or not it has lexicon terms (`objectKind = attribute`, `ownerRef` = its entity/table, no
    reach of its own). `entries` — and so `contentHash` — are unchanged.

- **`ttr-translator`** ⚑ **T-SQL string→datetime operand coercion (hosted test estate, 2026-09-21) — `DATEDIFF(day,
  '19000101', …)` / `DATEADD(day, 7, '2026-09-14')` validate.** The LLM lane's canonical "previous calendar
  week" idiom (`DATEADD(day, -7, DATEADD(day, (DATEDIFF(day, '19000101', CAST(GETDATE() AS date)) / 7) * 7,
  '19000101'))`) died at `ParseToRelNode` with `Cannot apply 'DATEDIFF' to arguments of type 'DATEDIFF(<INTERVAL
  DAY>, <CHAR(8)>, <DATE>)'`: our `DATEDIFF` (TF-P1.S1) and Calcite's `DATEADD` declare their operands as the
  `DATETIME` family, and Calcite's `TypeCoercionImpl` coerces a character operand only into `DATE`/`TIME`/
  `TIMESTAMP` — for `DATETIME` it inserted no cast at all, so an ISO literal failed just the same. New
  `framework/TsqlTypeCoercion` (bound through `SqlValidator.Config.withTypeCoercionFactory`): a character
  operand expected as `DATETIME` is cast to `TIMESTAMP` (what SQL Server's implicit conversion yields too),
  and T-SQL's unseparated `'YYYYMMDD'` literal is rewritten to `'YYYY-MM-DD'` first — Calcite's date parser
  reads only the dashed form, and the decode-side constant fold turns the cast into a typed TIMESTAMP
  literal (`'1900-01-01 00:00:00'` on the MSSQL unparse). A `datetime` column operand still gets no implicit
  cast; an integer anchor (`DATEDIFF(wk, 0, …)`) is still rejected; binary comparisons are untouched.
  `TsqlTypeCoercionSpec` runs the service path (parse → wire → decode → unparse).

- **`ttr-translator`** ⚑ **regression fix (MJ follow-up, hosted test estate, 2026-09-21) — `JoinerPhysical` sees the
  `TableScan` under MAP_TO_PHYSICAL's alias-at-boundary `Project` again.** 0.11.1 narrowed a join side's
  scan search to `Join`/`Filter` (so a derived table's hidden column could not become a bare-name ref that
  dies at decode) — but MAP_TO_PHYSICAL itself wraps a **query-backed** entity's body in a `Project`
  (`call_center := cs_call_center_sk`), and every `*__filter` entity on that estate is query-backed.
  The FK fallback that had conditioned a bare entity `JOIN` since v1.0 silently stopped and the
  join ran as a Cartesian product. A `Project` is now descended as a **name map** on the scan (`ScanRef`):
  only its bare column-ref expressions expose a name, the FK column is mapped through every projection up
  to the join, and an FK whose column no projection exposes is dropped from the candidates (a
  `JOIN_NO_RELATION` warning, never a decode-time `field not found`). `JoinerPhysicalSpec` (nested alias
  projects, unexposed column, computed expression) and `ModelJoinSpec` on `HartlandJoinModel.handleQueryBacked()`
  — the served-estate shape: query-backed entity, FKs derived from the relations, and no relations served — pin it.

- **`ttr-translator`** ⚑ **model joins (MJ, → `translator/v0.12.0`) — bare and chained `JOIN`s in the ER
  lane are conditioned from the model's declared relations.** `FROM sale JOIN shopper JOIN household
  JOIN product` — the way the LLM lane writes it — used to die at Calcite validation with
  `INNER, LEFT, RIGHT, FULL, or ASOF join requires a condition`; it now translates with the three `ON`s the
  E-R model implies (`s ↔ sh`, `sh ↔ h`, `s ↔ p`) and zero warnings. Two carriers on one policy:
  - **`codec/sql/ModelJoinRewriter`** (new, pre-validation `SqlShuttle`, ER catalog only): every
    `INNER`/`LEFT`/`RIGHT`/`FULL` join without `ON`/`USING` (not `NATURAL`) gets
    `<alias>.<attr> = <alias>.<attr>` from the model — join type preserved, SQL aliases used, `AND` over
    every pair of a composite relation. Each join in a chain is decided against the **whole** entity set
    of its other side, so `shopper → household` is found for the third entity, not `sale
    → household`. Anything unresolvable gets `ON TRUE` and falls through to the wire carrier, which reports
    it. Explicit `ON`/`USING`, `NATURAL`, the comma list and `CROSS JOIN` are untouched. `explain` gains
    the stage `model_joins` (the rewritten statement, or `(no bare joins)`).
  - **`joiner/JoinPolicy`** (new) — the single decision: candidates `(l, r, rel)` over both sides; exactly
    one → resolved; none → `NoRelation`; two or more → `AmbiguousRelations` (no proximity tie-break); an
    entity occurring twice across the join (a self-join, or two aliases a candidate names) → ambiguous with
    `repeated` — a repeated alias that already has its `ON` does not block the *next* join (contracts §2
    C-1, review-097 R1); all pairs oriented to the sides. Path inference through bridge entities is deliberately *not* built (gated v2).
  - **`JoinerLogical` / `JoinerPhysical`** on the same rule: whole-side matching (the comma-form chain
    `FROM a, b, c` now resolves too), `and(eq, eq)` for composite relations / multi-column FKs, and a
    **key-name collision guard** — a bare `$L`/`$R` ref resolves to the *first* holder of a name, so a join
    whose key exists on two entities of one side stays unconditioned with the new
    `JoinerWarning.KeyNameCollision` instead of silently mis-joining. ⚠ **Behaviour changes:** (1) a side's
    entity/table set descends `Join` and `Filter` only — a `Project`/`Aggregate`/`Subquery` under a join side
    is no longer looked into (it could produce a bare-name ref to a column a derived table does not expose
    and fail at decode); such a join stays unconditioned and is reported as `JOIN_NO_RELATION` naming
    "a derived table" for that side (review-097 R2) — never a silent Cartesian product. (2) `JoinerLogical` used only the FIRST
    pair of a composite relation. (3) `JoinerPhysical` skipped multi-column FKs (`NoRelation`); they are now
    ANDed.
  - **Warnings ride on the result:** `ParseResult.Success.warnings` / `TranslateResult.Success.warnings`
    (`List<JoinerWarning>`, defaulted — source-compatible), reconciled to **one verdict per join** across
    the two stages (`JoinerWarnings.merge`: a join the physical carrier conditioned drops the logical
    carrier's stale warning; a join both warned on keeps the entity-level one). New
    **`joiner/JoinerMessages`** renders `code` / `severity` / `text`: `JOIN_NO_RELATION`,
    `JOIN_AMBIGUOUS_RELATIONS`, `JOIN_RELATION_WITHOUT_PAIRS` (INFO), `JOIN_KEY_NAME_COLLISION` — the names
    Golem already demotes on; the KDoc names `join_unresolved_cartesian` / `join_ambiguous_multiple_relations`
    / `join_relation_without_pairs` are gone. ⚠ `JoinerWarning` gained `KeyNameCollision` (exhaustive `when`s
    need a branch); `NoRelation` / `AmbiguousRelations` carry `leftEntities` / `rightEntities` (`sideA` /
    `sideB` are their first members); `AmbiguousRelations.repeated`. `JoinerLogical.RelationMatcher` and
    `findFirstScanPublic` are removed. `ParseResult.{Success,Failure}.modelJoinsSql` (debug, defaulted).
  Fixture `HartlandJoinModel` (test-fixtures) + `ModelJoinSpec` (H1–H12 of the MJ plan), `ModelJoinRewriterSpec`,
  `JoinPolicySpec`, `JoinerMessagesSpec`, and the extended `JoinerLogicalSpec` / `JoinerPhysicalSpec` pin it;
  every pre-MJ golden (incl. the comma-form plan bytes) is unchanged. Design and decisions:
  `project/tatrman/features/ttr-translator/model-joins/`.

- **`ttr-translator`** ⚑ **behaviour fix — joins over renamed keys, and `datetime_value` read
  and written as ISO-8601.** Three defects that meet on the first entity join to reach execution
  on a model whose join keys are renamed between the ER and DB layers:
  - **`JoinerPhysical` is alias-aware.** MAP_TO_PHYSICAL aliases each renamed column back to its
    attribute name (alias-at-boundary), so above the scan only the alias exists — but the
    FK-based join condition named the physical columns and failed at unparse with
    `field [d_date_sk] not found; input fields are: [sk, …]`. Each operand now resolves through its
    own side's scan: the alias when that scan aliases the column, the column name otherwise. Models
    whose attribute names equal their column names produce the same plans as before.
  - **`JoinerLogical` no longer throws on a relation with no join pairs** (one bound only to its FK,
    with no `join:` list): it leaves the join for `JoinerPhysical` to condition from that FK and
    records **`JoinerWarning.RelationWithoutJoinPairs`** (wire code `join_relation_without_pairs`).
    ⚠ New member of a sealed interface — an exhaustive `when` over `JoinerWarning` needs a branch.
  - **`Literal.datetime_value` honours plan.proto's ISO-8601 in both directions.** Encode wrote
    Calcite's `value2` — epoch milliseconds for a TIMESTAMP (`"1735689600000"`); decode built a
    CHARACTER literal, so a date bound was compared to a date column as a string. A TIMESTAMP now
    encodes as an ISO instant (`2025-01-01T00:00:00Z`), a DATE as `2025-01-01`, a TIME as `12:30:00`;
    decode builds a typed TIMESTAMP / DATE / TIME literal (an offset is normalised to UTC) and rejects
    a value that is not ISO-8601 instead of degrading it to text. ⚠ Known gap, unchanged: a
    `datetime_value` in a **VALUES row** still decodes as text — `RelBuilder.values` routes cells
    through `RelBuilder.literal(Object)`, which does not accept Calcite's temporal types.
  `JoinerPhysicalSpec`, `JoinerLogicalSpec`, `DatetimeLiteralSpec` and `RenamedKeyJoinSpec` (the
  two-pass TransDSL → REL_NODE → SQL pipeline, asserting the unparsed join and bounds) pin them.

- **`ttr-translator`** ⚑ **behaviour fix — `EXTRACT` lowers to `DATEPART` on SQL Server.**
  `EXTRACT(<unit> FROM <datetime>)` reached the engine verbatim (Calcite's stock MSSQL
  dialect has no unparse rule for it) and died with error 195 `'EXTRACT' is not a
  recognized built-in function name`. Every date-part route lands on that node — the
  validator rewrites `YEAR(x)`/`MONTH(x)` to `EXTRACT`, the MD dot-path viaCalc lowering
  emits it, a free-SQL planner writes it as the portable form — so year/month grouping on
  any MSSQL estate failed. `MssqlSqlDialectWithFloatCast` now renders
  `DATEPART(<part>, <datetime>)` for both unit shapes (the validator's `TimeUnitRange`
  flag and the wire's `TimeUnit` flag); `DOY`/`DOW` map to `DAYOFYEAR`/`WEEKDAY`; a unit
  SQL Server cannot express (EPOCH, DECADE, …) fails at translate time with a clear
  message. Postgres/DuckDB are untouched. `ExtractLoweringSpec` pins it.

- **MH T1 + T3-data — the collision report, and the E-R reach in the archive
  (mention homonymy).** One word claimed by two refs (hartland's `prodejna`: the store
  *dimension*'s label and the Stores-*channel* alias pinned to the sales fact) is now
  reported at authoring time and described in the artifact.
  - **`ttr-lexicon`**: `TermNormalizer.fold` — a **second** normalization beside
    `normalize`, stripping combining marks. It is the resolver's *anchor index* key, so
    it is the only key that answers "would these two declarations meet at runtime?";
    `normalize` keeps diacritics and stays the merge key. `Reach(factRef, mandatory)` and
    `TargetFacts.reachedFrom` are new, and the archive label moves to
    **`ttr-lexicon-compiled/v3`**. Both fields are defaulted, so a v2 archive decodes
    unchanged and a v3 archive read by an MS-era reader simply ignores the field;
    `contentHash` is untouched (entry table only).
  - **`ttr-lexicon-compile`**: build warning **`RG-LEXC-004`** — a DECLARED row folds
    onto another target's row. Never fatal (a declared homonym can be deliberate) and
    never archive content. `targets[ref].reachedFrom` is derived from `def relation`:
    every fact whose `to:` is this ref, with `mandatory = cardinality.to`'s lower bound
    ≥ 1. Members and attributes carry none; a relation to a ref outside the model is
    skipped silently.
  - **`ttr-metadata`** ⚑ **behaviour fix**: a relation's authored `cardinality:` now
    reaches `Relation.cardinality`. It was hardcoded to `Cardinality(0, -1, 0, -1)` in
    the file loader, so every relation on every loaded model claimed both sides were
    optional. Nothing in this repo read the field, which is why it went unnoticed;
    `reachedFrom.mandatory` is its first consumer. Bounds parse as `1` · `0..1` ·
    `1..*` · `0..*` · `*` · `N`, with `-1` for unbounded (the previous default), and
    anything unrecognised degrades to `0..*`.
  - **`@tatrman/lint`** (TS twin): project-scoped rule
    `lexicon-form-collides-with-name` / `ttr/lexicon-form-collides-with-name`, warning
    by default, suppressed per term with the existing
    `// ttr-disable-next-line`. One fold, three implementations, one parity table
    (`packages/semantics/src/lexicon/fold-parity.json`). `lintDocument` no longer calls
    a directive naming a *project*-scoped rule unused — only `lintProject` can know.
  Detail: `project/server/features/mention-homonymy/` (design → contracts §1–§4).

- **Grammar `0.13` (additive) — localised `description:` (NLS-P10, ⚑GXP-D7).**
  `description:` accepts the localised map form (`{ en: "…", cs: "…" }`) everywhere it
  accepted a string, reusing the `localizedString` rule `displayLabel` already uses. No
  new token, no wire change: `meta.v1.ObjectDescriptor.description` stays a single
  `string` and Veles selects the locale server-side (D7 chain: requested → plain → `en`
  → first by language code → `""`). Every AST/model layer gains a sibling carrier
  beside the existing one — parser `Definition.descriptionLocalized`, metadata
  `ModelObject.descriptionLocalized` — and **every reader of `description` is
  unaffected**: the two forms are mutually exclusive and nothing folds a map.
  ⚑ **Construct these types by keyword, not by position.** The new parameter is
  declared immediately after `description` (Kotlin `Definition`/`ModelObject`
  implementors, `AreaRecord`, `ModelDescriptor`, `EngineDef`/`ExecutorDef`/
  `StorageDef`; Python's `description_localized` on the `Definition` base), so a
  positional call that passed `tags` fourth no longer compiles. The break is loud —
  `LocalizedStringValue` is not a `List<String>` — never silent, and every in-repo
  positional call site was migrated with the change. `ttr-writer` round-trips
  whichever form the author wrote. Two new lint
  codes (`ttr/localized-description-empty`,
  `ttr/localized-description-missing-locale`) cover the maps that serve nobody. The
  conformance dump gains a present-only `descriptionLocalized`. Consumers re-cut at
  `0.13.0` per the unified version policy; detail in
  [`packages/grammar/CHANGELOG.md`](packages/grammar/CHANGELOG.md).

- **Grammar `0.12` (additive) — the `searchable method:` match-method attribute
  (RV-P1.5, RV-31/RV-32).** `searchable` is the lexicon inclusion marker, so its
  boolean is now optional, and an optional `method: EXACT | TYPOS(n) | TOKENS`
  rides it. `fuzzy` still parses but is **deprecated**: the semantics layer maps
  it (`true` → `TYPOS(1)`, `false` → `EXACT`) and emits
  `ttr/search-fuzzy-deprecated`; `ttr/unknown-match-method` and
  `ttr/invalid-match-method-argument` validate the new attribute. All three codes
  are emitted alike by the TS, Kotlin and Python targets. `SearchHintsValue`
  gains `method` + `fuzzyAuthored` (**appended**, so positional construction stays
  source-compatible), and the conformance dump schema gains a `search.method`
  string. Consumers re-cut at `0.12.0` per the unified version policy; migration
  table in [`packages/grammar/CHANGELOG.md`](packages/grammar/CHANGELOG.md).
  One behaviour delta: a bare `searchable: true` with no `fuzzy` used to match
  exactly and now takes the RV-32 default `TYPOS(1)`.
- **Patch `0.8.1` (qname-redesign fix).** `classifyReference` / the resolver's
  cross-schema fall-back now strips the db schema handle when it follows the
  package + model (`pkg.db.dbo.Table.Col`), not only in the model-less
  `dbo.Name` form — so a fully-qualified cross-package db column/table reference
  resolves again under the v4.0 uniform keys. Mirrored in Kotlin + Python.
- **BREAKING — grammar 4.0 (qname redesign), published as `0.8.0`.** Address
  keywords are renamed so each names one concept: `def model <id>` →
  `def project <id>`; the file directive `schema <code> [namespace <id>]` →
  `model <code> [schema <id>]`; and `graph … schema <code>` → `graph … model
  <code>`. Canonical keys become uniform and package-first —
  `<package>.<model>.<schema?>.<kind>.<name>` — with the schema slot **db-only**
  (D6), `query`/`drillMap` folded into the `db` model (D14), and stock cnc
  de-doubled to `cnc.role.<name>` (D15). New `DiagnosticCode`s
  `ttr/schema-name-collision`, `ttr/unknown-package-schema`,
  `ttr/schema-on-logical-model`, `ttr/require-qualified-refs`. New public
  semantics API `buildCanonicalKey` / `modelForKind` / `namespaceForKind` /
  `kindOf` / `MODEL_CODES`. The conformance dump schema (§5) changes (every
  symbol key gains its kind segment), so this is a major grammar bump shipped as
  a breaking minor pre-1.0. Migrate content with `modeler migrate-qnames`. See
  `docs/features/qname-redesign/`.
- **Grammar 3.1 (additive — MD multidimensional model).** New `md` schema code
  and six logical `def` kinds — `domain` (re-added), `dimension`, `map` (now a
  real kind), `hierarchy`, `measure`, `cubelet` — plus four binding kinds under
  `schema binding`: `md2db_cubelet`, `md2db_domain`, `md2db_map`,
  `md2er_cubelet`. New `DOTDOT` (`..`) range token and MD body keywords; the
  shared `attribute` body gains optional `domain:`/`aggregation:` (per-schema
  validity is semantic). **Every new keyword is in `idPart`**, so no existing 3.0
  file changes meaning — this is additive. New data-only package
  `@tatrman/md-catalog` ships the v1 Time calc-map catalog (`MD_CALC_CATALOG`,
  `MD_CATALOG_VERSION` — the cross-repo sync key). Editor semantics: the full
  `md/*` diagnostic set (resolution, per-kind validators, calc-catalog
  type-checks, leaf/grain + hierarchy inference, cubelet + binding completeness)
  and LSP hover/definition/completion for MD symbols. See `docs/features/md/` and
  `docs/manual/en/15-md-model.md`. Publishing 3.1 (Maven/PyPI) and Kotlin/Python
  conformance are gated on the 3.0 release shipping first.
- **BREAKING — grammar 3.0 (MD Phase 0 legacy renames).** `schema map` →
  `schema binding`; inline `mapping:` → `binding:` (diagnostic
  `ttr/duplicate-mapping` → `ttr/duplicate-binding`); the `.ttrd` `domain` block
  is removed in favour of a plain `def area` definition; model files use the
  `.ttrm` extension. Consumers must bump the published `ttr-parser`
  (Maven/PyPI) and migrate their models (`modeler phase0`). See
  [`packages/grammar/CHANGELOG.md`](packages/grammar/CHANGELOG.md) → 3.0 for the
  full list and migration steps.
- **Fix — plain triple-strings that look like a tagged block no longer break
  parsing.** A `"""…"""` whose first line is a bare ASCII word + newline (e.g.
  `cs: """Ne␊1 = Ano"""` in a `description`/`valueLabels`) lexes as
  `TAGGED_BLOCK_LITERAL` (that token wins over `TRIPLE_STRING_LITERAL`). The
  0.5.0 grammar only accepted that token under `sourceText`/`definitionSql`, so
  it became a **parse error everywhere else** — and because the Kotlin parser
  empties a file's definitions on any error, downstream cross-package references
  then failed too. `stringLiteralForm` now also accepts `TAGGED_BLOCK_LITERAL`
  and reads it as a plain triple-string (tag word kept as text); only
  `embeddedBlock` peels the tag. Fixes ai-platform's `ModelTtrLoadSpec`
  (49 reconcile errors → 0). Regression fixture `53-tagged-like-plain-string`.

- **Grammar — `primaryKey` accepts bare column ids.** In addition to the legacy
  quoted-string list (`primaryKey: ["IDSTRED"]`), a table's `primaryKey` now
  accepts bare identifiers — a single id (`primaryKey: IDSTRED`) or a bare-id
  list (`primaryKey: [ID, KOD]`). All forms collapse to the same column-name
  `List<String>` in the AST, so this is **additive and backward-compatible** for
  `org.tatrman:ttr-parser` (no consumer changes required). The TS formatter
  re-emits the bare form; the Kotlin `ttr-writer` still emits the quoted form
  (both are valid and round-trip). `TTR.g4` change → not yet synced to the
  ai-platform vendored copy.

## 0.5.0 — unreleased

Phase 1 of the embedded-SQL feature (`docs/features/embedded-sql/`): the parser
now recognises a **tagged triple-string carrier** (`"""<tag>␊…"""`) for the
`sourceText` / `definitionSql` properties and exposes the embedded foreign
language structurally. Minor (additive) per `PUBLISHING.md`, but **source-breaking
for exhaustive `when` consumers** (see below).

- **`org.tatrman:ttr-parser:0.5.0`** — adds the top-level
  `PropertyValue.TaggedBlockValue` variant (`tag`, `language`, `dialect`,
  `value`, `tagSource`, `valueSource`, `indentWidth`, `source`) and the
  `LanguageKind` typealias. The walker tag-peels `"""<tag>␊…"""`, dedents
  (shared `Dedent` contract), strips one trailing newline, and resolves the tag
  via a `TAG_REGISTRY` (mirrors the TS table) into `language`/`dialect`.
  `QueryDef` / `ViewDef` gain `sourceTextBlock` / `definitionSqlBlock` carrying
  the structured value; the existing `sourceText` / `definitionSql: String?`
  are retained (= the extracted text), so **text-only consumers are unaffected**.
  Three new `DiagnosticCode`s (`UnknownLanguageTag`, `LanguageTagMismatch`,
  `DeprecatedLanguageProperty`); `language:` on `query` is now inferred from the
  tag and **soft-deprecated**. **Breaking:** any exhaustive `when (v:
  PropertyValue)` must add a `TaggedBlockValue` branch (the compiler flags it).
- **`org.tatrman:ttr-writer:0.5.0`** — `TtrRenderer` renders `TaggedBlockValue`
  back to `"""<tag>␊<value>␊"""` (round-trip guarantee against the parser,
  modulo `SourceLocation`); renders queries/views from the structured block when
  present. Untagged triple-strings render unchanged.
- `ttr-semantics` re-cut at `0.5.0` for the `kotlin/v0.5.0` bundle tag (no
  behavioural change; the embedded SQL semantics land in Phase 3).

Conformance: 11 new fixtures (`tests/conformance/fixtures/41–51`) lock the
value-extraction contract (DESIGN §4 golden cases C1–C11) byte-for-byte across
the TS and Kotlin parsers; a tagged `sourceText`/`definitionSql` now serialises
to `{ kind, tag, language, dialect, value }` in both dumpers.

## 0.4.0 — unreleased

The next published bundle. Minor (additive) per `PUBLISHING.md`.

- **`org.tatrman:ttr-writer:0.4.0`** — `TtrRenderer` now renders the v2.1 inline
  `mapping:` property on entity / attribute / relation defs (additive — the
  parser model + walker already populated these fields; only rendering was
  missing). Both surface forms emit: bare-id (`mapping: i_category_id`,
  `mapping: db.dbo.fk_item_category`) and block (`mapping: { target: …,
  columns: { … } }` on entities, `{ target: … }` on attributes, `{ fk: … }` on
  relations). Each `columns:` entry is a short bare-id (`attr: COL`) when the
  target is a plain column, else the object form. Round-trip is a fixed point on
  `samples/2.1/er.ttr`. The standalone `def er2db_*` renderers are unchanged.
  Unblocks the ai-platform legacy-YAML→TTR converter's inline-mapping output.
- **`org.tatrman:ttr-semantics:0.4.0`** — first published bundle to carry the
  **kind-derived schema/namespace defaults** (the `0.3.0 — unreleased` entry
  below). That work postdates the tagged `kotlin/v0.3.0` (which stops at
  `SymbolEntry.namespace`), so it first reaches consumers in `0.4.0`. ai-platform
  bumps `tatrman-modeler` to `0.4.0` to consume both of the above.
- `ttr-parser` re-cut at `0.4.0` for the `kotlin/v0.4.0` bundle tag (no
  behavioural change).

## 0.3.0 — unreleased

Delivers the modeler-side half of the resolver-consolidation follow-up (the
deferred part of grammar-master Phase 2.8): exposes the file namespace on every
symbol so ai-platform's downstream proto adapter can build its
`QualifiedName` triple without re-parsing the qname string.

- **`org.tatrman:ttr-semantics:0.3.0`** — `SymbolEntry` gains a `namespace:
  String` field, populated in `DocumentSymbols` from the file's declared
  namespace (`""` when none is declared — **not** the `nsOrKind` qname
  fallback). Purely additive: the resolver algorithm, qname construction, and
  stock handling are unchanged, so both conformance harnesses are unaffected.
- `ttr-parser` / `ttr-writer` re-cut at `0.3.0` for the `kotlin/v0.3.0` bundle
  tag (no behavioural change).

Also in `ttr-semantics:0.3.0`: **schema/namespace are now optional with defaults
derived from the object kind** (namespace already fell back to the kind; the
schema now does too). When a file has **no `schema` directive**, each
definition's qname uses the default schema for its kind — `entity`/`attribute`/
`relation` → `er`, the `db`-family → `db`, `er2db_*` → `map`, `role`/`er2cnc_role`
→ `cnc`, `query`/`drill_map` → `query` (`defaultSchemaForKind`). An explicit
`schema` directive still wins for the whole file. **No grammar change — no
grammar-version bump** (both `packageDecl` and `schemaDirective` were already
optional in `TTR.g4`; only schema-less resolution changed). TS and Kotlin emit
identical qname + diagnostic sets, locked in by new schema-less conformance
fixtures (`tests/conformance/fixtures/35–40`).

Consuming `0.3.0`, ai-platform completed the **resolver consolidation**: its
hand-maintained `ReferenceResolver`/`SymbolTable` are deleted and
`ReferenceResolutionPass` now resolves through a thin adapter over
`org.tatrman.ttr.semantics.{SymbolTable, Resolver}` (proven equivalent by a
differential parity harness). This closes the deferred half of grammar-master
Phase 2.8 — a future grammar/version bump now reaches ai-platform as an
`org.tatrman:*` version-ref change with no hand-written semantics edits
(rehearsed). See [`docs/grammar-master/resolver-consolidation/`](docs/grammar-master/resolver-consolidation/).

## 0.2.1 — 2026-06-09

Reconciles the bundled stock CNC vocabulary (`StockLoader` /
`builtin/cnc-stock-roles.ttr`) with ai-platform's canonical content ahead of the
ai-platform stock-source switch: each `def role` now carries a localized
`label { cs, en }` and ai-platform's descriptions (tags dropped). Names are
unchanged, so resolution and the conformance harness are unaffected. This makes
the published artifact a true single source of truth for stock roles — including
their display labels — so ai-platform's `BuiltinStockSource` can delegate to
`StockLoader.load()` without losing data.

## 0.2.0 — 2026-06-09

Phase 2 of grammar-master. Adds the semantics artifact; replaces ai-platform's
hand-rolled resolver / symbol-table / stock-loader equivalent.

- **`org.tatrman:ttr-semantics:0.2.0`** — symbol table, six-step reference
  resolver, package inference + dependency graph (Tarjan cycle detection),
  per-kind + cross-reference `Validator`, and the bundled stock CNC vocabulary
  (`StockLoader`, `builtin/cnc-stock-roles.ttr`). Faithful Kotlin port of
  `packages/semantics/src/`. Depends on `org.tatrman:ttr-parser` (api).
- `ttr-parser` / `ttr-writer` re-cut at `0.2.0` for the `kotlin/v*` bundle tag
  (no behavioural change since `0.1.0`).

Conformance: a second harness (`SemanticsConformanceSpec` / `dump-sem` /
`diff-sem`) verifies the Kotlin resolver + validator against the TypeScript
semantics layer byte-for-byte (resolved-qname + diagnostic-code sets) across
the shared fixtures.

## 0.1.0 — 2026-06-03

Phase 1 of grammar-master. First published release of the modeler-owned Kotlin
parser stack, generated from the canonical `packages/grammar/src/TTR.g4` (v2.2).

- **`org.tatrman:ttr-parser:0.1.0`** — ANTLR-generated parser + typed AST
  (`TtrLoader`, `ParseResult`, the `Definition` hierarchy, `PropertyValue`,
  `SourceLocation`, `DiagnosticCode`, `Dedent`). Depends only on
  `org.antlr:antlr4-runtime` + `org.slf4j:slf4j-api`.
- **`org.tatrman:ttr-writer:0.1.0`** — deterministic AST → TTR-source renderer
  (`TtrRenderer`) with a round-trip guarantee against the parser. Depends on
  `org.tatrman:ttr-parser`.

Conformance: the Kotlin parser is verified byte-for-byte against the TypeScript
parser across the shared fixture set (`conformance.yml`).
