// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.Chain
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ConfigBlock
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.ControlBlock
import org.tatrman.ttrp.ast.ControlDep
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.GroupByEntry
import org.tatrman.ttrp.ast.ImportDecl
import org.tatrman.ttrp.ast.CubeletLhs
import org.tatrman.ttrp.ast.CubeletStmt
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.ParamDecl
import org.tatrman.ttrp.ast.ParamDefault
import org.tatrman.ttrp.ast.PortDecl
import org.tatrman.ttrp.ast.ProgramHeader
import org.tatrman.ttrp.ast.Qname
import org.tatrman.ttrp.ast.RelationArg
import org.tatrman.ttrp.ast.SchemaLiteralArg
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.ast.UsesWorld
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.expr.MdPathAtom
import org.tatrman.ttrp.expr.MdPathComponent

/**
 * Deterministic, field-ordered JSON dump of the TTR-P AST for golden snapshotting
 * (test-only, mirrors ttr-parser's ConformanceDump). Insertion order in
 * `buildJsonObject` is preserved, so output is stable across runs. Includes node
 * kind, children, source spans, and fragment `sourceText`.
 */
object TtrpAstDump {
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun dump(doc: TtrpDocument): String = json.encodeToString(JsonElement.serializer(), document(doc))

    /** Deterministic dump of a standalone expression IR (the `golden.exprs` corpus snapshot). */
    fun dumpExpression(e: Expression): String = json.encodeToString(JsonElement.serializer(), expr(e))

    private fun span(loc: SourceLocation): JsonElement =
        JsonPrimitive("${loc.line}:${loc.column}-${loc.endLine}:${loc.endColumn}")

    private fun document(doc: TtrpDocument) =
        buildJsonObject {
            put("kind", "Document")
            put("statements", buildJsonArray { doc.statements.forEach { add(statement(it)) } })
        }

    private fun statement(s: Statement): JsonElement =
        when (s) {
            is UsesWorld ->
                obj("UsesWorld", s.location) {
                    put("world", s.world)
                    put("leading", trivia(s.leadingTrivia))
                    put("trailing", trivia(s.trailingTrivia))
                }
            is ImportDecl ->
                obj("ImportDecl", s.location) {
                    put("qname", s.qname.text)
                    put("wildcard", s.wildcard)
                }
            is ProgramHeader -> obj("ProgramHeader", s.location) { put("name", s.name) }
            is org.tatrman.ttrp.ast.SchemaDecl ->
                obj("SchemaDecl", s.location) {
                    put("name", s.name)
                    put(
                        "columns",
                        buildJsonArray { s.columns.forEach { add(JsonPrimitive("${it.name}:${it.type}")) } },
                    )
                }
            is Assignment ->
                obj("Assignment", s.location) {
                    put("target", s.target)
                    put("chain", chain(s.chain))
                    put("leading", trivia(s.leadingTrivia))
                    put("trailing", trivia(s.trailingTrivia))
                }
            is ChainStmt -> obj("ChainStmt", s.location) { put("chain", chain(s.chain)) }
            is CubeletStmt ->
                obj("CubeletStmt", s.location) {
                    put("op", s.op.name)
                    put(
                        "lhs",
                        when (val lhs = s.lhs) {
                            is CubeletLhs.Path -> expr(lhs.path)
                            is CubeletLhs.Name -> obj("NameLhs", lhs.location) { put("name", lhs.name) }
                        },
                    )
                    put("rhs", expr(s.rhs))
                    put(
                        "with",
                        s.withClause?.let { w ->
                            buildJsonArray {
                                w.entries.forEach { e ->
                                    add(
                                        buildJsonObject {
                                            put("key", e.key)
                                            put("value", e.value)
                                        },
                                    )
                                }
                            }
                        } ?: JsonNull,
                    )
                }
            is ControlDep ->
                obj("ControlDep", s.location) {
                    put("controlKind", s.kind.name)
                    put("subject", s.subject)
                    put("reference", s.reference)
                }
            is ControlBlock ->
                obj("ControlBlock", s.location) {
                    put("deps", buildJsonArray { s.deps.forEach { add(statement(it)) } })
                }
            is ContainerDecl ->
                obj("ContainerDecl", s.location) {
                    put("name", s.name)
                    put("ports", buildJsonArray { s.ports.forEach { add(portDecl(it)) } })
                    put("target", s.target.text)
                    // PL-P2.S1 attrs: emitted only when present, so pre-feature container snapshots stay identical.
                    if (s.onFailureOf != null) put("onFailureOf", s.onFailureOf)
                    if (s.onFailureAbsorbs) put("absorbs", true)
                    if (s.retries != null) put("retries", s.retries)
                    put("body", containerBody(s))
                }
            is ParamDecl ->
                obj("ParamDecl", s.location) {
                    put("name", s.name)
                    put("type", s.type)
                    put("required", s.required)
                    put(
                        "default",
                        when (val d = s.default) {
                            null -> JsonNull
                            is ParamDefault.Builtin -> JsonPrimitive("@" + d.name)
                            is ParamDefault.Literal -> JsonPrimitive(d.text)
                        },
                    )
                }
            is org.tatrman.ttrp.ast.RecipeDecl -> recipe(s)
        }

    // ---- the recipe block (AU-core-P0 · S0.1) ------------------------------------------------

    private fun recipe(r: org.tatrman.ttrp.ast.RecipeDecl) =
        obj("Recipe", r.location) {
            put("key", jsonStrings(r.key.map { it.text }))
            put(
                "asOf",
                when (val a = r.asOf) {
                    is org.tatrman.ttrp.ast.AsOf.Now -> "now"
                    is org.tatrman.ttrp.ast.AsOf.Instant -> a.text
                },
            )
            put("parameters", jsonStrings(r.parameters.map { it.text }))
            put("document", r.document?.let { JsonPrimitive(it) } ?: JsonNull)
            if (r.protocol != null) put("protocol", r.protocol!!.spelling)
            if (r.name != null) put("name", r.name)
            put(
                "members",
                buildJsonArray {
                    r.members.forEach {
                        add(
                            when (it) {
                                is org.tatrman.ttrp.ast.RoleContainer -> roleContainer(it)
                                is org.tatrman.ttrp.ast.DecisionDecl -> decision(it)
                                is org.tatrman.ttrp.ast.ActionsBlock ->
                                    obj("Actions", it.location) {
                                        put("wirings", buildJsonArray { it.wirings.forEach { w -> add(wiring(w)) } })
                                    }
                            },
                        )
                    }
                },
            )
        }

    private fun roleContainer(c: org.tatrman.ttrp.ast.RoleContainer) =
        obj("RoleContainer", c.location) {
            put("role", c.role.keyword)
            put("name", c.name)
            put("ports", buildJsonArray { c.ports.forEach { add(portDecl(it)) } })
            put("form", islandForm(c.form))
            put(
                "window",
                c.window?.let { w ->
                    obj("Window", w.location) {
                        put("by", jsonStrings(w.by.map { it.text }))
                        put(
                            "over",
                            when (val o = w.over) {
                                null -> "?"
                                is org.tatrman.ttrp.ast.WindowOver.Calendar -> o.unit
                                is org.tatrman.ttrp.ast.WindowOver.Rows -> "rows:${o.count}"
                                is org.tatrman.ttrp.ast.WindowOver.Gap ->
                                    "gap:${o.duration.amount} ${o.duration.unit.spelling}"
                            },
                        )
                    }
                } ?: JsonNull,
            )
            put(
                "body",
                when (val b = c.body) {
                    null -> JsonNull
                    is FragmentBody ->
                        obj("FragmentBody", b.location) {
                            put("tag", b.tag)
                            put("decomposed", b.decomposition != null)
                        }
                    is FlowBody ->
                        obj("FlowBody", b.location) {
                            put("statements", buildJsonArray { b.statements.forEach { add(statement(it)) } })
                        }
                },
            )
        }

    private fun islandForm(f: org.tatrman.ttrp.ast.IslandForm): JsonElement =
        when (f) {
            is org.tatrman.ttrp.ast.IslandForm.Target -> obj("Target", f.location) { put("target", f.target.text) }
            is org.tatrman.ttrp.ast.IslandForm.Builtin ->
                obj("Builtin", f.location) {
                    put("capability", f.capability.text)
                    put("config", f.config?.let { configBlock(it) } ?: JsonNull)
                }
            is org.tatrman.ttrp.ast.IslandForm.Foreign ->
                obj("Foreign", f.location) {
                    put("language", f.language)
                    put("grain", f.grain.name)
                    put("schema", f.schema)
                }
            is org.tatrman.ttrp.ast.IslandForm.Call ->
                obj("Call", f.location) {
                    put("service", f.service.text)
                    put("grain", f.grain?.name ?: "")
                }
            is org.tatrman.ttrp.ast.IslandForm.Turn ->
                obj("Turn", f.location) {
                    put("agent", f.agent.text)
                    put("ask", f.ask)
                    put("answer", f.answerSchema)
                }
        }

    private fun decision(d: org.tatrman.ttrp.ast.DecisionDecl) =
        obj("Decision", d.location) {
            put("name", d.name)
            put("ports", buildJsonArray { d.ports.forEach { add(portDecl(it)) } })
            put("statements", buildJsonArray { d.statements.forEach { add(statement(it)) } })
            put(
                "switch",
                obj("Switch", d.switch.location) {
                    put("input", jsonStrings(d.switch.input.parts))
                    put("mode", d.switch.mode?.spelling ?: "")
                    put(
                        "routes",
                        buildJsonArray {
                            d.switch.routes.forEach { r ->
                                add(
                                    obj("Route", r.location) {
                                        put("name", r.name)
                                        put("condition", expr(r.condition))
                                        put("because", r.because?.let { JsonPrimitive(it) } ?: JsonNull)
                                        put("detail", jsonStrings(r.detail.map { it.text }))
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "else",
                        d.switch.elseRoute?.let { e ->
                            obj("Else", e.location) {
                                put("name", e.name ?: "none")
                                put("because", e.because?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("detail", jsonStrings(e.detail.map { it.text }))
                            }
                        } ?: JsonNull,
                    )
                },
            )
        }

    private fun wiring(w: org.tatrman.ttrp.ast.Wiring) =
        obj("Wiring", w.location) {
            put("routes", jsonStrings(w.routes.map { "${it.decision}.${it.route}" }))
            put("action", w.action)
            put("args", buildJsonArray { w.args.forEach { add(wiringArg(it)) } })
            put("when", w.whenCondition?.let { expr(it) } ?: JsonNull)
        }

    private fun wiringArg(a: org.tatrman.ttrp.ast.WiringArg): JsonElement =
        obj("WiringArg", a.location) {
            put("name", a.name)
            put(
                "value",
                when (val v = a.value) {
                    is org.tatrman.ttrp.ast.WiringValue.Expr -> expr(v.expression)
                    is org.tatrman.ttrp.ast.WiringValue.Values ->
                        obj("Values", v.location) { put("items", buildJsonArray { v.items.forEach { add(expr(it)) } }) }
                    is org.tatrman.ttrp.ast.WiringValue.Call ->
                        obj("Call", v.location) {
                            put("action", v.action)
                            put("args", buildJsonArray { v.args.forEach { add(wiringArg(it)) } })
                        }
                },
            )
        }

    private fun portDecl(p: PortDecl) =
        obj("PortDecl", p.location) {
            put("portKind", p.kind.name)
            put("name", p.name)
        }

    private fun containerBody(c: ContainerDecl): JsonElement =
        when (val b = c.body) {
            is FragmentBody ->
                obj("FragmentBody", b.location) {
                    put("tag", b.tag)
                    put("sourceText", b.sourceText)
                }
            is FlowBody ->
                obj("FlowBody", b.location) {
                    put("statements", buildJsonArray { b.statements.forEach { add(statement(it)) } })
                }
        }

    private fun chain(c: Chain): JsonElement =
        buildJsonObject {
            put("kind", "Chain")
            put("loc", span(c.location))
            put(
                "elements",
                buildJsonArray {
                    c.elements.forEach {
                        when (it) {
                            is OpCall -> add(opCall(it))
                            is DottedRef -> add(dottedRef(it))
                        }
                    }
                },
            )
        }

    private fun opCall(o: OpCall) =
        obj("OpCall", o.location) {
            put("name", o.name)
            put("args", buildJsonArray { o.args.forEach { add(arg(it)) } })
            put("config", o.config?.let { configBlock(it) } ?: JsonNull)
        }

    private fun arg(a: Arg) =
        obj("Arg", a.location) {
            put("name", a.name ?: "")
            put(
                "value",
                when (val v = a.value) {
                    is ExprArg -> expr(v.expr)
                    is RelationArg -> obj("RelationArg", v.location) { put("qname", v.qname.text) }
                    is SchemaLiteralArg ->
                        obj("SchemaLiteralArg", v.location) {
                            put(
                                "columns",
                                buildJsonArray { v.columns.forEach { add(JsonPrimitive("${it.name}:${it.type}")) } },
                            )
                        }
                },
            )
        }

    private fun configBlock(c: ConfigBlock) =
        obj("ConfigBlock", c.location) {
            put(
                "entries",
                buildJsonArray {
                    c.entries.forEach {
                        when (it) {
                            is GroupByEntry -> add(obj("GroupBy", it.location) { put("keys", jsonStrings(it.keys)) })
                            is AssignEntry ->
                                add(
                                    obj("Assign", it.location) {
                                        put("name", it.name)
                                        put("value", expr(it.value))
                                    },
                                )
                        }
                    }
                },
            )
        }

    private fun dottedRef(d: DottedRef) = obj("DottedRef", d.location) { put("parts", jsonStrings(d.parts)) }

    private fun mdPathComponent(c: MdPathComponent): JsonElement =
        when (c) {
            is MdPathComponent.Name -> obj("Name", c.location) { put("text", c.text) }
            is MdPathComponent.IntLit -> obj("Int", c.location) { put("text", c.text) }
            is MdPathComponent.StrLit -> obj("Str", c.location) { put("text", c.text) }
            is MdPathComponent.Star -> obj("Star", c.location) {}
            is MdPathComponent.MemberSet ->
                obj("Set", c.location) { put("atoms", buildJsonArray { c.atoms.forEach { add(mdPathAtom(it)) } }) }
            is MdPathComponent.Range ->
                obj("Range", c.location) {
                    put("lo", mdPathAtom(c.lo))
                    put("hi", mdPathAtom(c.hi))
                }
        }

    private fun mdPathAtom(a: MdPathAtom): JsonElement =
        when (a) {
            is MdPathAtom.Name -> obj("Name", a.location) { put("text", a.text) }
            is MdPathAtom.IntLit -> obj("Int", a.location) { put("text", a.text) }
            is MdPathAtom.StrLit -> obj("Str", a.location) { put("text", a.text) }
        }

    /** Serializes the one PL expression IR (Stage 1.2) deterministically for golden snapshots. */
    private fun expr(e: Expression): JsonElement =
        when (e) {
            is ColumnRef ->
                obj("ColumnRef", e.location) {
                    put("port", e.port ?: "")
                    put("column", e.column)
                }
            is Literal ->
                obj("Literal", e.location) {
                    put("literalKind", literalKind(e.value))
                    put("value", literalText(e.value))
                }
            is MdPath ->
                obj("MdPath", e.location) {
                    put("components", buildJsonArray { e.components.forEach { add(mdPathComponent(it)) } })
                }
            is FunctionCall ->
                obj("FunctionCall", e.location) {
                    put("function", e.function.value)
                    put("args", buildJsonArray { e.args.forEach { add(expr(it)) } })
                    // AU-core-P0 named arguments: only when present, so every pre-recipe snapshot is unchanged.
                    if (e.argNames.isNotEmpty()) put("argNames", jsonStrings(e.argNames.map { it ?: "" }))
                }
            is AggregateCall ->
                obj("AggregateCall", e.location) {
                    put("function", e.function.value)
                    put("distinct", e.distinct)
                    put("args", buildJsonArray { e.args.forEach { add(expr(it)) } })
                }
            is Cast ->
                obj("Cast", e.location) {
                    put("target", e.target.canonical)
                    put("expr", expr(e.expr))
                }
            is CaseWhen ->
                obj("CaseWhen", e.location) {
                    put(
                        "branches",
                        buildJsonArray {
                            e.branches.forEach { (w, t) ->
                                add(
                                    buildJsonObject {
                                        put("when", expr(w))
                                        put("then", expr(t))
                                    },
                                )
                            }
                        },
                    )
                    put("else", e.elseExpr?.let { expr(it) } ?: JsonNull)
                }
            is InList ->
                obj("InList", e.location) {
                    put("negated", e.negated)
                    put("expr", expr(e.expr))
                    put("items", buildJsonArray { e.items.forEach { add(expr(it)) } })
                }
            is IsNull ->
                obj("IsNull", e.location) {
                    put("negated", e.negated)
                    put("expr", expr(e.expr))
                }
            is org.tatrman.ttrp.expr.ParamRef -> obj("ParamRef", e.location) { put("name", e.name) }
            is org.tatrman.ttrp.expr.DurationLiteral ->
                obj("Duration", e.location) {
                    put("amount", e.amount)
                    put("unit", e.unit.spelling)
                }
            is org.tatrman.ttrp.expr.DurationParam ->
                obj("DurationParam", e.location) {
                    put("param", e.param)
                    put("unit", e.unit.spelling)
                }
            // TTRP 0.5 (CN-D29) — the conceptual-model forms.
            is org.tatrman.ttrp.expr.ExistsExpr ->
                obj("Exists", e.location) {
                    put("negated", e.negated)
                    put("concepts", conceptBindings(e.concepts))
                    put("involving", participants(e.involving))
                    put("where", e.where?.let { expr(it) } ?: JsonNull)
                }
            is org.tatrman.ttrp.expr.ConceptAggregate ->
                obj("ConceptAggregate", e.location) {
                    put("function", e.function)
                    put("concepts", conceptBindings(e.concepts))
                    put("involving", participants(e.involving))
                    put("where", e.where?.let { expr(it) } ?: JsonNull)
                    put(
                        "namedArgs",
                        buildJsonArray {
                            e.namedArgs.forEach { (n, v) ->
                                add(
                                    buildJsonObject {
                                        put("name", n)
                                        put("value", expr(v))
                                    },
                                )
                            }
                        },
                    )
                    put("member", jsonStrings(e.member))
                }
            is org.tatrman.ttrp.expr.ThisRef -> obj("This", e.location) { put("path", jsonStrings(e.path)) }
            is org.tatrman.ttrp.expr.InCall ->
                obj("InCall", e.location) {
                    put("negated", e.negated)
                    put("expr", expr(e.expr))
                    put("call", expr(e.call))
                }
            is org.tatrman.ttrp.expr.TemporalPredicate ->
                obj("Temporal", e.location) {
                    put("op", e.op.spelling)
                    put("lhs", expr(e.lhs))
                    put("rhs", expr(e.rhs))
                }
        }

    private fun conceptBindings(bs: List<org.tatrman.ttrp.expr.ConceptBinding>): JsonArray =
        buildJsonArray {
            bs.forEach { b ->
                add(
                    buildJsonObject {
                        put("concept", b.concept)
                        put("var", b.variable ?: "")
                    },
                )
            }
        }

    private fun participants(ps: List<org.tatrman.ttrp.expr.Participant>): JsonArray =
        buildJsonArray {
            ps.forEach { pt ->
                add(
                    buildJsonObject {
                        put("path", expr(pt.path))
                        put("label", pt.label ?: "")
                    },
                )
            }
        }

    private fun literalKind(v: LiteralValue): String =
        when (v) {
            is LiteralValue.Str -> "STRING"
            is LiteralValue.Num -> "NUMBER"
            is LiteralValue.Bool -> "BOOL"
            is LiteralValue.Null -> "NULL"
        }

    private fun literalText(v: LiteralValue): String =
        when (v) {
            is LiteralValue.Str -> v.value
            is LiteralValue.Num -> v.raw
            is LiteralValue.Bool -> v.value.toString()
            is LiteralValue.Null -> "null"
        }

    private fun trivia(ts: List<org.tatrman.ttrp.ast.Trivia>): JsonElement =
        buildJsonArray { ts.forEach { add(JsonPrimitive(it.text)) } }

    private fun jsonStrings(xs: List<String>): JsonArray = buildJsonArray { xs.forEach { add(JsonPrimitive(it)) } }

    private fun obj(
        kind: String,
        loc: SourceLocation,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = buildJsonObject {
        put("kind", kind)
        put("loc", span(loc))
        block()
    }

    private fun Qname.render(): String = text
}
