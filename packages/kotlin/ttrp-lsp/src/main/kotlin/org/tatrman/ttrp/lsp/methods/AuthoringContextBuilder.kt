// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.methods

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.Position
import org.tatrman.ttrp.ast.ContainerBody
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.ImportDecl
import org.tatrman.ttrp.ast.IslandForm
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.entry.EntryVerbCatalog
import org.tatrman.ttrp.graph.capability.ClasspathManifestSource
import org.tatrman.ttrp.graph.capability.WorldBinder
import org.tatrman.ttrp.graph.recipe.RecipeModel
import org.tatrman.ttrp.lsp.nav.SourceNav
import org.tatrman.ttrp.resolve.ImportScope
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.TtrpChecker
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationParameter
import org.tatrman.ttr.metadata.model.AutomationRole
import org.tatrman.ttr.metadata.model.AutomationService
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.world.ResolvedWorld
import org.tatrman.ttr.metadata.world.writable
import org.tatrman.ttr.parser.model.AutoValue

/**
 * Builds the deterministic, prompt-ready authoring-context bundle (contracts §7 /
 * `docs/features/ttr-p/architecture/authoring-context.schema.json`, v2). Serialized with Gson
 * (LSP4J's JSON), every object closed. `scope` is present only when a cursor
 * [position] was supplied. The schema is final; some content (capability node/function
 * rosters, model objects, TTR-SQL/TTR-B grammar rosters) grows in later phases — the
 * fields are present now with the shapes the schema pins.
 *
 * v2 (AU-core-P2 · S2.4 T4, AU contracts §8 — additive, a v1 instance stays valid): `recipeGrammar` (the recipe
 * block's statement summary + its REC rows), `automationObjects` (the imports' `def parameter · action · role ·
 * service` and row schemas), `world.writable`, and `recipe` — the recipe the cursor is in: key, parameters, the
 * roles written so far and, once the checker reaches it, each context's class and the mode verdict.
 */
object AuthoringContextBuilder {
    const val SCHEMA_VERSION = 2
    const val SCHEMA_ID = "https://tatrman.org/schemas/ttrp/authoring-context/v2"

    /** The canonical TTR-P op roster surfaced to assist hosts (T10). */
    private val TTRP_OPS =
        listOf(
            "load",
            "store",
            "transfer",
            "display",
            "join",
            "aggregate",
            "union",
            "branch",
            "filter",
            "sort",
            "distinct",
            "limit",
            "sample",
            "head",
            "tail",
            "project",
            "select",
            "calc",
            "switch",
            "values",
            "intersect",
            "except",
            "pivot",
        )

    /** TTR-pandas method roster (S17). */
    private val PANDAS_METHODS =
        listOf("select", "calc", "filter", "join", "aggregate", "sort", "union", "limit", "load", "store", "display")

    /** TTR-SQL clause roster (C2-b α; the clauses `TTRSql.g4` accepts) — surfaced to assist. */
    private val SQL_CLAUSES =
        listOf(
            "with",
            "select",
            "distinct",
            "from",
            "join",
            "where",
            "group by",
            "having",
            "order by",
            "limit",
            "union",
            "intersect",
            "except",
            "values",
        )

    /** TTR-B sentence-verb rosters per skin (C4-b + AG B2) — read off the keyword tables, surfaced to assist. */
    private val TTRB_VERBS: List<String>
        get() =
            org.tatrman.ttrp.dialect.b.TtrbSkin.EN
                .verbRoster()
    private val TTRB_CS_VERBS: List<String>
        get() =
            org.tatrman.ttrp.dialect.b.TtrbSkin.CS
                .verbRoster()

    /** Shared classpath manifest source (T6 β) — read-only, cheap to reuse across builds. */
    private val manifestSource = ClasspathManifestSource()

    /**
     * [recipe] is what the recipe checker learned of the document's recipe (`TtrpPipeline.plan(…).recipe`) — the
     * caller runs the pipeline only when [position] is inside a recipe; null when it did not, or the front half failed.
     */
    fun build(
        report: TtrpChecker.Report,
        position: Position?,
        recipe: RecipeModel? = null,
    ): JsonObject {
        val root = JsonObject()
        root.addProperty("version", SCHEMA_VERSION)
        root.add("world", world(report.world))
        root.add("capabilities", capabilities(report.world))
        root.add("modelObjects", modelObjects(report.modelIndex))
        root.add("automationObjects", automationObjects(report))
        if (position != null) root.add("scope", scope(report, position))
        if (position != null) recipeAt(report, position)?.let { root.add("recipe", recipe(it, recipe)) }
        root.add("grammar", grammar())
        root.add("recipeGrammar", recipeGrammar())
        root.add("diagnostics", diagnostics())
        return root
    }

    /** The recipe block [position] is inside, or null. */
    fun recipeAt(
        report: TtrpChecker.Report,
        position: Position,
    ): RecipeDecl? =
        report.document.statements.filterIsInstance<RecipeDecl>().firstOrNull {
            SourceNav.contains(it.location, position)
        }

    private fun world(world: ResolvedWorld?): JsonObject {
        val obj = JsonObject()
        if (world == null) {
            obj.add("engines", JsonArray())
            obj.add("executors", JsonArray())
            obj.add("storages", JsonArray())
            return obj
        }
        obj.addProperty("qname", "${world.qname.`package`}.${world.qname.name}")
        obj.addProperty("fingerprint", world.fingerprint)
        obj.add(
            "engines",
            JsonArray().apply {
                world.engines.forEach {
                    add(
                        JsonObject().apply {
                            addProperty("name", it.qname.name)
                            addNullable("type", it.type)
                            addNullable("version", it.version)
                        },
                    )
                }
            },
        )
        obj.add(
            "executors",
            JsonArray().apply {
                world.executors.forEach {
                    add(
                        JsonObject().apply {
                            addProperty("name", it.qname.name)
                            // an executor declared by `extends:` is of that type (n8n_l1, camunda_8 — review-119 m2)
                            addNullable("type", it.type ?: it.extendsRef)
                            addNullable("version", it.version)
                        },
                    )
                }
            },
        )
        obj.add(
            "storages",
            JsonArray().apply {
                world.storages.forEach {
                    add(
                        JsonObject().apply {
                            addProperty("name", it.qname.name)
                            addProperty("staging", it.staging)
                            addProperty("rls", it.manifest["rls"]?.let { p -> p.toString().contains("true") } ?: false)
                        },
                    )
                }
            },
        )
        obj.addNullable("staging", world.staging?.qname?.name)
        // v2 (§8): what a recipe's actions may write, per storage (D-65, D-76, D-79) — only the storages that say.
        obj.add(
            "writable",
            JsonArray().apply {
                world.storages.filter { it.writable.isNotEmpty() }.forEach { st ->
                    add(
                        JsonObject().apply {
                            addProperty("storage", st.qname.name)
                            add("items", JsonArray().apply { st.writable.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                }
            },
        )
        return obj
    }

    /**
     * v2 (§8): the automation objects the document's imports bring into scope — `def parameter` (type, value),
     * `def action` (columns, sinks, transition, approval), `def role`, `def service` (request/response schemas) — and
     * the row schemas (`def schema`: an answer's, a service's), each with its columns. Sorted by kind, then name.
     */
    private fun automationObjects(report: TtrpChecker.Report): JsonArray {
        val index = report.modelIndex ?: return JsonArray()
        val imports = importScopes(report)
        val out = mutableListOf<Pair<String, JsonObject>>()
        index.automationInScope(imports).forEach { o ->
            val obj = JsonObject()
            obj.addProperty("kind", o.kind)
            obj.addProperty("name", o.qname.name)
            (
                o.label.byLanguage["en"] ?: o.label.byLanguage.values
                    .firstOrNull()
            )?.let { obj.addProperty("label", it) }
            when (o) {
                is AutomationParameter -> {
                    obj.addNullable("type", o.type)
                    obj.addNullable("value", o.value?.let { autoText(it) })
                }
                is AutomationAction -> {
                    obj.add("columns", columns(o.columns.map { Triple(it.name, it.type, it.optional) }))
                    obj.add(
                        "sinks",
                        JsonObject().apply { o.sinks.toSortedMap().forEach { (k, v) -> addProperty(k, v) } },
                    )
                    o.transition?.let { t ->
                        obj.addProperty(
                            "transition",
                            "${t.attribute} → ${t.to ?: ":${t.toColumn}"}" +
                                if (t.from.isEmpty()) "" else " from ${t.from.joinToString(", ")}",
                        )
                    }
                    obj.addNullable("approval", o.approval)
                }
                is AutomationService -> {
                    obj.addNullable("request", o.request)
                    obj.addNullable("response", o.response)
                }
                else -> Unit
            }
            if (o is AutomationParameter || o is AutomationAction || o is AutomationRole || o is AutomationService) {
                out += "${o.kind}\u0000${o.qname.name}" to obj
            }
        }
        index.rowSchemasInScope(imports).forEach { rs ->
            val obj = JsonObject()
            obj.addProperty("kind", "schema")
            obj.addProperty("name", rs.name)
            obj.add("columns", columns(rs.columns.map { Triple(it.name, it.type, it.optional) }))
            out += "schema\u0000${rs.name}" to obj
        }
        return JsonArray().apply { out.sortedBy { it.first }.distinctBy { it.first }.forEach { add(it.second) } }
    }

    private fun columns(cols: List<Triple<String, String, Boolean>>): JsonArray =
        JsonArray().apply {
            cols.forEach { (name, type, optional) ->
                add(
                    JsonObject().apply {
                        addProperty("name", name)
                        addProperty("type", type)
                        if (optional) addProperty("optional", true)
                    },
                )
            }
        }

    /** An automation value as written: a number as written, a string its text, a duration `2 working_days`. */
    private fun autoText(v: AutoValue): String? =
        when (v) {
            is AutoValue.Str -> v.raw
            is AutoValue.Num -> v.text
            is AutoValue.Bool -> v.raw.toString()
            is AutoValue.Duration -> "${v.amount.toLong()} ${v.unit}"
            is AutoValue.Id -> v.parts.joinToString(".")
            else -> null
        }

    private fun importScopes(report: TtrpChecker.Report): List<ImportScope> =
        report.document.statements.filterIsInstance<ImportDecl>().map {
            ModelIndex.importScope(it.qname.parts, it.qname.text + if (it.wildcard) ".*" else "")
        }

    /**
     * v2 (§8): the recipe at the cursor — from the block as written (key, parameters, the role containers so far,
     * the decision and its routes) and, when the checker reached it, each context's class and the mode verdict.
     */
    private fun recipe(
        decl: RecipeDecl,
        checked: RecipeModel?,
    ): JsonObject =
        JsonObject().apply {
            add("key", JsonArray().apply { decl.key.forEach { add(JsonPrimitive(it.text)) } })
            add("parameters", JsonArray().apply { decl.parameters.forEach { add(JsonPrimitive(it.text)) } })
            add(
                "roles",
                JsonArray().apply {
                    decl.roleContainers.forEach { rc ->
                        add(
                            JsonObject().apply {
                                addProperty("name", rc.name)
                                addProperty("role", rc.role.keyword)
                                addProperty("form", formOf(rc.form))
                                checked
                                    ?.contexts
                                    ?.firstOrNull { it.name == rc.name }
                                    ?.let { addProperty("class", it.contextClass.spelling) }
                            },
                        )
                    }
                    decl.decisions.forEach { d ->
                        add(
                            JsonObject().apply {
                                addProperty("name", d.name)
                                addProperty("role", "decision")
                                add(
                                    "routes",
                                    JsonArray().apply { d.switch.routeNames.forEach { add(JsonPrimitive(it)) } },
                                )
                            },
                        )
                    }
                },
            )
            checked?.let { addProperty("mode", it.mode.spelling) }
        }

    private fun formOf(form: IslandForm): String =
        when (form) {
            is IslandForm.Target -> "target"
            is IslandForm.Builtin -> "builtin"
            is IslandForm.Foreign -> "foreign"
            is IslandForm.Call -> "call"
            is IslandForm.Turn -> "turn"
        }

    /**
     * Per-engine node/function support rosters, from the T6 β compiler-shipped engine-type
     * manifests (the same ones the normalizer/capability-checker match against, `WorldBinder`).
     * Absence of a matching manifest (an unrecognized engine type) degrades to empty rosters —
     * this bundle is informational, not a validation gate.
     */
    private fun capabilities(world: ResolvedWorld?): JsonObject {
        val obj = JsonObject()
        val bound = world?.let { WorldBinder(manifestSource).bind(it) }
        obj.add(
            "engines",
            JsonArray().apply {
                world?.engines?.forEach { engine ->
                    val manifest = bound?.engines?.get(engine.qname.name)?.manifest
                    add(
                        JsonObject().apply {
                            addProperty("engine", engine.qname.name)
                            addNullable("type", engine.type)
                            // Whether this engine can produce a rejects stream (RJ-P6): assist must
                            // not offer a rejects tap on an engine whose manifest declares no rejects
                            // support (it would escalate or error at compile time).
                            addProperty("rejects", manifest?.rejectsSupport()?.produces ?: false)
                            add(
                                "nodes",
                                JsonArray().apply {
                                    manifest
                                        ?.nodes
                                        ?.keys
                                        ?.sorted()
                                        ?.forEach { add(JsonPrimitive(it)) }
                                },
                            )
                            add(
                                "functions",
                                JsonArray().apply {
                                    manifest?.functions?.sorted()?.forEach { add(JsonPrimitive(it)) }
                                },
                            )
                        },
                    )
                }
            },
        )
        return obj
    }

    /** Loadable model objects (db table / er entity — [ModelIndex]'s own `is DbTable || is Entity` filter) with inline schema. */
    private fun modelObjects(modelIndex: ModelIndex?): JsonArray =
        JsonArray().apply {
            modelIndex?.snapshot?.model?.objectByQname()?.values?.forEach { obj ->
                val schema =
                    when (obj) {
                        is DbTable -> modelIndex.tableColumns(obj)
                        is Entity -> modelIndex.entityAttributes(obj)
                        else -> null
                    } ?: return@forEach
                add(
                    JsonObject().apply {
                        addProperty("name", obj.qname.dotted())
                        addProperty("kind", obj.kind)
                        add(
                            "schema",
                            JsonArray().apply {
                                schema.forEach { (name, type) ->
                                    add(
                                        JsonObject().apply {
                                            addProperty("name", name)
                                            addProperty("type", type)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }

    private fun scope(
        report: TtrpChecker.Report,
        position: Position,
    ): JsonObject {
        val obj = JsonObject()
        obj.add(
            "imports",
            JsonArray().apply {
                report.document.statements.filterIsInstance<ImportDecl>().forEach {
                    add(JsonPrimitive(it.qname.text + if (it.wildcard) ".*" else ""))
                }
            },
        )
        // Only the cursor's own scope — leaking every container's vars would put out-of-scope
        // (and cross-container name-colliding) names into the deterministic prompt (contracts §7).
        val cursorScope = report.schemas[SourceNav.scopeLabel(report.document, position)].orEmpty()
        obj.add(
            "variables",
            JsonArray().apply {
                cursorScope.forEach { (name, cols) ->
                    add(
                        JsonObject().apply {
                            addProperty("name", name)
                            add(
                                "schema",
                                JsonArray().apply {
                                    cols?.forEach { c ->
                                        add(
                                            JsonObject().apply {
                                                addProperty("name", c.name)
                                                addProperty("type", c.type.toString())
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        // A recipe's role container is a container too (v2): its ports, its dialect, its engine.
        val container: ContainerDecl? =
            SourceNav.containerAt(report.document, position)
                ?: recipeAt(report, position)
                    ?.roleContainers
                    ?.firstOrNull { SourceNav.contains(it.location, position) }
                    ?.asContainer()
        obj.add(
            "portsAtCursor",
            JsonArray().apply { container?.ports?.forEach { add(JsonPrimitive(it.name)) } },
        )
        // Cursor-scoped dialect insertion (C4-d-i γ): the host declares the target by the position;
        // the assist emits in the container's dialect (sql/pandas/ttrb), or `ttrp` at program scope.
        obj.add(
            "insertionTarget",
            JsonObject().apply {
                addProperty("dialect", container?.let { dialectOf(it.body) } ?: "ttrp")
                addNullable("containerName", container?.name)
                addNullable("targetEngine", container?.target?.parts?.lastOrNull())
            },
        )
        return obj
    }

    /**
     * The dialect the assist inserts at a container: a fragment's tag, or `ttrp` for a canonical body. A tag
     * outside the schema's set (an unknown `ttrb-<xx>`, or a file-backed body whose file failed to load) falls
     * back to `ttrp`, so the bundle stays schema-valid.
     */
    private fun dialectOf(body: ContainerBody): String =
        when (body) {
            is FragmentBody -> body.tag.takeIf { it in INSERTION_DIALECTS } ?: "ttrp"
            else -> "ttrp"
        }

    private val INSERTION_DIALECTS = setOf("sql", "pandas", "ttrb", "ttrb-cs")

    private fun grammar(): JsonObject =
        JsonObject().apply {
            addProperty("specVersion", 1)
            addProperty(
                "statementSummary",
                "chains (`a -> filter(…) -> sort(…)`) + SSA assignment, freely mixed; `=` is the one " +
                    "equality (`==` only in TTR-pandas, S9); multi-in named-only; union list-form (S11).",
            )
            add(
                "dialectRosters",
                JsonObject().apply {
                    add("ttrp", JsonArray().apply { TTRP_OPS.forEach { add(JsonPrimitive(it)) } })
                    add("sql", JsonArray().apply { SQL_CLAUSES.forEach { add(JsonPrimitive(it)) } })
                    add("pandas", JsonArray().apply { PANDAS_METHODS.forEach { add(JsonPrimitive(it)) } })
                    add("ttrb", JsonArray().apply { TTRB_VERBS.forEach { add(JsonPrimitive(it)) } })
                    add("ttrb-cs", JsonArray().apply { TTRB_CS_VERBS.forEach { add(JsonPrimitive(it)) } })
                },
            )
            // EN-P2 T6 — the `entry` stdlib verb roster (verbs + typed signatures) so assist sees the
            // apply vocabulary (same mechanism as the RJ rejects capability). Derived from the catalogue.
            add("entryVerbs", entryVerbRoster())
            // EN-P5 — the `call-fn` construct descriptor (the plugin-function call surface). The concrete
            // function vocabulary is deploy-specific (the manifest §15 registry), so assist gets the
            // construct's shape + rules, not a fixed function list.
            add("callFn", callFnDescriptor())
        }

    /** The `call-fn` construct (contracts §6) for assist: its surface form, purity, and resolution rules. */
    private fun callFnDescriptor(): JsonObject =
        JsonObject().apply {
            addProperty("form", "call-fn(\"<plugin-id>\", args…)")
            addProperty("idKind", "string-literal")
            addProperty("purity", "a `pure`-certified canon-function only (TTRP-EN-005)")
            addProperty(
                "resolution",
                "deploy-time against the manifest §15 registry; the pinned {id, version} is recorded in " +
                    "the entry record and honoured on replay (TTRP-EN-006 on a malformed or unresolvable call)",
            )
        }

    /** The `entry` verb roster (contracts §4) for assist: each verb's catalogue id, name, and param kinds. */
    private fun entryVerbRoster(): JsonArray =
        JsonArray().apply {
            EntryVerbCatalog.SHIPPED.forEach { v ->
                add(
                    JsonObject().apply {
                        addProperty("id", v.id)
                        addProperty("name", v.name)
                        add("params", JsonArray().apply { v.params.forEach { add(JsonPrimitive(it.name)) } })
                        v.requiresSemantics?.let { req ->
                            add("requiresSemantics", JsonArray().apply { req.forEach { add(JsonPrimitive(it)) } })
                        }
                    },
                )
            }
        }

    /**
     * v2 (§8): the recipe block (AU contracts §1) for assist — the statement summary, the roles and island forms, the
     * switch's parts, the time vocabulary, and the REC rows (the recipe checker's repair vocabulary; DOC rows join
     * with the document front-end, AU-core-P3).
     */
    private fun recipeGrammar(): JsonObject =
        JsonObject().apply {
            addProperty(
                "statementSummary",
                "one `recipe { … }` per document, no `program` beside it: headers `key:` (an entity attribute, or a " +
                    "list for a composite key), `as of: now`, `parameters: [ … ]` (each a `def parameter` of the " +
                    "imports), `document:`, `protocol: always | on_route`; then one `candidates` island, any " +
                    "`context` islands (`target <engine>` + a body, or `builtin` / `foreign` / `call` / `turn`, " +
                    "an optional `window { by: …, over: … }`), one `decision <name>(in …)` ending in " +
                    "`<row> -> switch { mode: exclusive | overlapping, <route>: <condition> because: \"…{column}…\" " +
                    "detail: <context>, …, else: <route> | none }`, and `actions { <decision>.<route>, … -> " +
                    "<action> { column: value, … } when: <condition> }`. `:as_of` is the run's instant; `:name` a " +
                    "parameter. A duration is `30 days` / `2 working_days` in a header or a wiring value, and " +
                    "`days(30)` / `days(:param)` in an expression (`:as_of - days(30)`); the door does no other date " +
                    "arithmetic — count days with `age(since: …)` / `working_days(between: …, and: …)` in a calc.",
            )
            add("roles", strings(listOf("candidates", "context", "decision")))
            add("islandForms", strings(listOf("target", "builtin", "foreign", "call", "turn")))
            add("switchModes", strings(listOf("exclusive", "overlapping")))
            add("routeAttributes", strings(listOf("because", "detail")))
            add("timeOps", strings(listOf("latest", "within", "working_days", "age")))
            add("durationUnits", strings(listOf("days", "working_days", "weeks", "months", "hours", "minutes")))
            add(
                "diagnostics",
                JsonArray().apply {
                    TtrpDiagnosticId.entries
                        .filter {
                            it.id.startsWith(
                                "TTRP-REC-",
                            ) ||
                                it.id.startsWith("TTRP-DOC-")
                        }.forEach { d ->
                            add(
                                JsonObject().apply {
                                    addProperty("id", d.id)
                                    addNullable("suggestedAlternative", d.suggestedAlternative)
                                },
                            )
                        }
                },
            )
        }

    private fun strings(items: List<String>): JsonArray = JsonArray().apply { items.forEach { add(JsonPrimitive(it)) } }

    private fun diagnostics(): JsonArray =
        JsonArray().apply {
            TtrpDiagnosticId.entries.forEach { d ->
                add(
                    JsonObject().apply {
                        addProperty("id", d.id)
                        addProperty("area", d.id.substringAfter("TTRP-").substringBeforeLast('-'))
                        addNullable("suggestedAlternative", d.suggestedAlternative)
                    },
                )
            }
        }

    // Omit null-valued keys: Gson's JsonElement.toString() drops explicit JsonNull members, so an
    // absent-when-null field (marked optional in the schema) round-trips consistently over JSON-RPC.
    private fun JsonObject.addNullable(
        key: String,
        value: String?,
    ) {
        if (value != null) addProperty(key, value)
    }
}
