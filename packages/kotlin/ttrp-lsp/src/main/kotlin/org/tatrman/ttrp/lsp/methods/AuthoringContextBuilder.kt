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
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.entry.EntryVerbCatalog
import org.tatrman.ttrp.graph.capability.ClasspathManifestSource
import org.tatrman.ttrp.graph.capability.WorldBinder
import org.tatrman.ttrp.lsp.nav.SourceNav
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.TtrpChecker
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.world.ResolvedWorld

/**
 * Builds the deterministic, prompt-ready authoring-context bundle (contracts §7 /
 * `docs/features/ttr-p/architecture/authoring-context.schema.json`, v1). Serialized with Gson
 * (LSP4J's JSON), every object closed. `scope` is present only when a cursor
 * [position] was supplied. The schema is final; some content (capability node/function
 * rosters, model objects, TTR-SQL/TTR-B grammar rosters) grows in later phases — the
 * fields are present now with the shapes the schema pins.
 */
object AuthoringContextBuilder {
    const val SCHEMA_VERSION = 1
    const val SCHEMA_ID = "https://tatrman.org/schemas/ttrp/authoring-context/v1"

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

    fun build(
        report: TtrpChecker.Report,
        position: Position?,
    ): JsonObject {
        val root = JsonObject()
        root.addProperty("version", SCHEMA_VERSION)
        root.add("world", world(report.world))
        root.add("capabilities", capabilities(report.world))
        root.add("modelObjects", modelObjects(report.modelIndex))
        if (position != null) root.add("scope", scope(report, position))
        root.add("grammar", grammar())
        root.add("diagnostics", diagnostics())
        return root
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
                            addNullable("type", it.type)
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
        return obj
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
        val container: ContainerDecl? = SourceNav.containerAt(report.document, position)
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
