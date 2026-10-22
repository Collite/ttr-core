// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.semantics.*

/** Deterministic model-only report, shared by CLI and editor. Lint scope is independent of the row inventory. */
data class MdHopRow(
    val element: String,
    val erTarget: String?,
    val via: String?,
    val diagnostic: String?,
)

data class TableRole(
    val entity: String,
    val role: String,
    val why: List<String>,
)

data class MdHopReport(
    val elements: List<MdHopRow>,
    val roles: List<TableRole>,
    val issues: List<MdHopIssue>,
)

fun mdHopReport(
    docs: List<SemanticDocument>,
    resolver: Resolver,
    all: Boolean = false,
): MdHopReport {
    val hop = CompositeMdErHop(docs, resolver)
    val rows =
        mdHopElements(docs, resolver).map { md ->
            val answer = hop.erTargetOf(md)
            MdHopRow(
                md,
                answer.erTarget,
                answer.via
                    ?.name
                    ?.lowercase()
                    ?.replace('_', '-'),
                answer.diagnostic,
            )
        }
    val issues =
        (
            if (all) {
                rows.flatMap {
                    hop.issues(it.element)
                }
            } else {
                groundedMdHopIssues(docs, resolver, hop)
            }
        ).toMutableList()
    if (all) issues += hop.derived.lineageIssues
    val roles = deriveTableRoles(hop)
    for (doc in docs) {
        for (def in doc.definitions.filterIsInstance<EntityDef>()) {
            val sym = resolver.getSymbol(hop.derived.physical.key(doc, "entity", def.name)) ?: continue
            val role = roles.find { it.entity == elementRefOf(sym) } ?: continue
            val claims =
                def.roles.map { it.path.substringAfterLast('.') }.filter {
                    it in
                        setOf("fact", "dimension", "bridge")
                }
            if (role.role != "unclassified" &&
                claims.any { it != role.role }
            ) {
                issues +=
                    MdHopIssue(
                        role.entity,
                        "md/role-claim-mismatch",
                        "warning",
                        "Claimed ${claims.joinToString(", ")}; derived ${role.role}",
                    )
            }
        }
    }
    return MdHopReport(
        rows,
        roles,
        issues.distinct().sortedWith(compareBy({ it.element }, { it.slug }, { it.message })),
    )
}

/** Roles are derived from resolved landings and relation cardinalities; an N:N bridge is flagged. */
fun deriveTableRoles(hop: CompositeMdErHop): List<TableRole> {
    val p = hop.derived.physical
    val why = sortedMapOf<String, MutableMap<String, MutableSet<String>>>()

    fun mark(
        entity: String,
        role: String,
        cause: String,
    ) {
        why.getOrPut(entity) { linkedMapOf() }.getOrPut(role) { sortedSetOf() }.add(cause)
    }

    fun local(key: String) = p.resolver.getSymbol(key)?.let(::elementRefOf) ?: key
    for (row in mdHopElements(p.docs, p.resolver)) {
        val er = hop.erTargetOf(row).erTarget ?: continue
        val logical = if (".md." in row) "md." + row.substringAfterLast(".md.") else row
        when {
            logical.startsWith("md.cubelet.") && logical.split('.').size == 3 -> mark(er, "fact", "cubelet $row (R1)")
            logical.startsWith(
                "md.dimension.",
            ) &&
                logical.split('.').size == 3 -> mark(er, "dimension", "dimension $row (R3)")
        }
    }

    fun one(value: String?) = value in setOf("1", "0..1")

    fun many(value: String?) = value in setOf("N", "M", "*", "0..*", "1..*")
    for ((key, _) in p.entities) {
        val er = local(key)
        val outgoing = p.relations.filter { it.from == key && many(it.fromCardinality) && one(it.toCardinality) }
        val measures =
            hop.derived.candidates.filterKeys { it.startsWith("md.measure.") }.values.any { attrs ->
                attrs.any {
                    entityOfElement(it) ==
                        er
                }
            }
        if (outgoing.size >= 2 && measures) mark(er, "fact", "measures and at least two many-to-one relations")
    }
    for (rel in p.relations) {
        if ("fact" in why[local(rel.from)].orEmpty() &&
            many(rel.fromCardinality) &&
            one(rel.toCardinality)
        ) {
            mark(local(rel.to), "dimension", "one side of ${local(rel.key)} from a fact")
        }
    }
    for (doc in p.docs) {
        for (binding in doc.definitions.filterIsInstance<Md2dbMapDef>()) {
            val map =
                binding.mapRef?.path?.let {
                    org.tatrman.ttr.semantics.md
                        .resolveMdNs(p.resolver, doc, it, "map")
                }
                    ?: continue
            val logical =
                p.docs.find { it.uri == map.documentUri }?.definitions?.filterIsInstance<MdMapDef>()?.find {
                    it.name ==
                        map.name
                }
                    ?: continue
            if (logical.cardinality != "N:N") continue
            val table = binding.table?.path?.let { p.resolve(doc, it) } ?: continue
            for (entity in p.tableToErEntity[table.qname].orEmpty()) {
                mark(
                    local(entity),
                    "bridge",
                    "N:N map ${local(map.qname)}; flagged, not classified",
                )
            }
        }
    }
    return p.entities.keys.map(::local).distinct().sorted().map { entity ->
        val reasons = why[entity].orEmpty()
        val role = listOf("bridge", "fact", "dimension").firstOrNull { it in reasons } ?: "unclassified"
        TableRole(entity, role, reasons.values.flatMap { it }.sorted())
    }
}

object MdHopRender {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun json(report: MdHopReport): String =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put(
                    "elements",
                    buildJsonArray {
                        for (r in report.elements) {
                            add(
                                buildJsonObject {
                                    put("element", r.element)
                                    put("erTarget", r.erTarget?.let(::JsonPrimitive) ?: JsonNull)
                                    put("via", r.via?.let(::JsonPrimitive) ?: JsonNull)
                                    put(
                                        "diagnostic",
                                        r.diagnostic?.let(::JsonPrimitive) ?: JsonNull,
                                    )
                                },
                            )
                        }
                    },
                )
                put(
                    "roles",
                    buildJsonArray {
                        for (r in report.roles) {
                            add(
                                buildJsonObject {
                                    put("entity", r.entity)
                                    put("role", r.role)
                                    put("why", buildJsonArray { r.why.forEach { add(it) } })
                                },
                            )
                        }
                    },
                )
                put(
                    "issues",
                    buildJsonArray {
                        for (r in report.issues) {
                            add(
                                buildJsonObject {
                                    put("element", r.element)
                                    put("slug", r.slug)
                                    put("severity", r.severity)
                                    put("message", r.message)
                                },
                            )
                        }
                    },
                )
            },
        ) + "\n"

    fun markdown(report: MdHopReport): String =
        buildString {
            append("# MD → ER hop\n\n| MD element | ER landing | Rule / diagnostic |\n| --- | --- | --- |\n")
            for (r in report.elements) {
                val landing = escape(r.erTarget ?: "—")
                val rule = escape(r.via ?: r.diagnostic ?: "—")
                append("| ${escape(r.element)} | $landing | $rule |\n")
            }
            append("\n## Derived warehouse roles\n\n| ER entity | Role | Why |\n| --- | --- | --- |\n")
            for (r in report.roles) {
                append(
                    "| ${escape(r.entity)} | ${r.role} | ${escape(r.why.joinToString("; "))} |\n",
                )
            }
            append("\n## Findings\n\n")
            if (report.issues.isEmpty()) append("None.\n")
            for (r in report.issues) {
                append(
                    "- ${r.severity}: ${escape(r.slug)} — ${escape(r.element)}: ${escape(r.message)}\n",
                )
            }
        }

    private fun escape(s: String): String =
        s
            .replace(
                "\\",
                "\\\\",
            ).replace(
                "|",
                "\\|",
            ).replace(
                "\n",
                " ",
            ).replace("\r", " ")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("`", "\\`")
            .replace("*", "\\*")
}
