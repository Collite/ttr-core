// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.ttr.metadata.model.GroundingGrade
import org.tatrman.ttr.metadata.model.Via

/**
 * The two renderings of a [HygieneReport] (CN-P2 S2.7 T2): [json] — the data — and [markdown] — the text `ttr cn
 * hygiene` prints. The editor's report (`@tatrman/semantics`) renders the same up to its definitions section, which
 * points here instead. Both are pinned by the golden in `tests/conformance/cn-hygiene/`.
 */
object HygieneRender {
    fun grade(g: GroundingGrade): String = g.name.lowercase().replace('_', '-')

    fun via(v: Via?): String? = v?.name?.lowercase()?.replace('_', '-')

    // ---- json ----------------------------------------------------------------------------------------------------

    /** The report as a JSON tree; [fileName] spells a definition finding's file (the CLI: relative to its root). */
    fun json(
        report: HygieneReport,
        fileName: (String) -> String = { it },
    ): JsonObject =
        buildJsonObject {
            put("concepts", JsonArray(report.concepts.map(::concept)))
            put("relationTypes", strings(report.relationTypes))
            put("retiredCodes", HygieneReport.RETIRED_CODES_NOTE)
            put(
                "definitions",
                buildJsonArray {
                    report.definitions.forEach { d ->
                        add(
                            buildJsonObject {
                                put("concept", d.subject)
                                put("slug", d.slug)
                                put("severity", d.severity.name.lowercase())
                                put("at", at(d, fileName))
                                put("message", d.message)
                            },
                        )
                    }
                },
            )
        }

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun jsonText(
        report: HygieneReport,
        fileName: (String) -> String = { it },
    ): String = pretty.encodeToString(JsonElement.serializer(), json(report, fileName)) + "\n"

    private fun strings(xs: List<String>) = JsonArray(xs.map(::JsonPrimitive))

    private fun nullable(s: String?): JsonElement = s?.let(::JsonPrimitive) ?: JsonNull

    private fun concept(c: ConceptHygiene): JsonObject =
        buildJsonObject {
            put("concept", c.qname)
            put("package", c.packageName)
            put("stereotype", c.stereotype)
            put("grade", grade(c.grade))
            put("via", nullable(via(c.via)))
            put(
                "grounding",
                c.grounding?.let { g ->
                    buildJsonObject {
                        put("layer", g.layer.name.lowercase())
                        put("binding", g.bindingKind)
                        put("target", nullable(g.target))
                    }
                } ?: JsonNull,
            )
            put(
                "hop",
                c.hop?.let { h ->
                    buildJsonObject {
                        put("from", h.mdElement)
                        put("to", nullable(h.erTarget))
                        put("slug", nullable(h.slug))
                    }
                } ?: JsonNull,
            )
            put("landsOn", nullable(c.erTarget))
            put("landingFrom", nullable(c.landingFrom))
            put("openEntries", strings(c.openEntries))
            put("relations", strings(c.relations))
            put(
                "dangling",
                JsonArray(
                    c.danglingGroundings.map {
                        buildJsonObject {
                            put("binding", it.binding)
                            put("ref", it.ref)
                        }
                    },
                ),
            )
            put(
                "redundantTerms",
                JsonArray(
                    c.redundantTerms.map {
                        buildJsonObject {
                            put("form", it.form)
                            put("locale", nullable(it.locale))
                            put("conceptEntry", it.conceptEntry)
                            put("element", it.element)
                            put("elementEntry", it.elementEntry)
                        }
                    },
                ),
            )
            put(
                "phaseAsDefault",
                JsonArray(
                    c.phaseAsDefault.map {
                        buildJsonObject {
                            put("entry", it.entry)
                            put("form", it.form)
                            put("alsoNames", it.alsoNames)
                        }
                    },
                ),
            )
            put("retiredCodes", strings(c.retiredCodes))
        }

    private fun at(
        d: Diagnostic,
        fileName: (String) -> String,
    ): String = d.location?.let { "${fileName(it.file)}:${it.line}" } ?: ""

    // ---- markdown ------------------------------------------------------------------------------------------------

    /** The report as `ttr cn hygiene` prints it (markdown: readable in a terminal, rendered in an editor preview). */
    fun markdown(
        report: HygieneReport,
        fileName: (String) -> String = { it },
    ): String =
        buildString {
            append(body(report))
            appendLine()
            appendLine("## Definitions")
            appendLine()
            if (report.definitions.isEmpty()) appendLine("None.")
            report.definitions.forEach { d ->
                appendLine(
                    "- ${d.subject} — ${d.slug} (${d.severity.name.lowercase()}) at ${at(d, fileName)}: ${d.message}",
                )
            }
        }

    /** Everything up to the definitions section — what the editor's report renders too, line for line. */
    fun body(report: HygieneReport): String =
        buildString {
            val cs = report.concepts
            appendLine("# Concept hygiene")
            appendLine()
            val counts =
                GroundingGrade.entries
                    .sortedBy { GRADE_ORDER.indexOf(it) }
                    .mapNotNull { g -> cs.count { it.grade == g }.takeIf { it > 0 }?.let { "$it ${grade(g)}" } }
            val summary = if (counts.isEmpty()) "" else counts.joinToString(" · ", " — ")
            appendLine("${cs.size} concept${if (cs.size == 1) "" else "s"}$summary")
            appendLine()
            if (cs.isNotEmpty()) {
                appendLine("| Concept | Stereotype | Grade | Via | Grounding | Hop | Lands on | Open tier |")
                appendLine("|---|---|---|---|---|---|---|---|")
                cs.forEach { c ->
                    val cells =
                        listOf(
                            c.qname,
                            c.stereotype,
                            grade(c.grade),
                            via(c.via) ?: DASH,
                            c.grounding?.let { it.target ?: "unresolved (${it.bindingKind})" } ?: DASH,
                            c.hop?.let { "${it.mdElement} → ${it.erTarget ?: it.slug ?: "?"}" } ?: DASH,
                            (c.erTarget ?: DASH) + (c.landingFrom?.let { " (← $it)" } ?: ""),
                            (c.openEntries + c.relations).joinToString(", ").ifEmpty { DASH },
                        )
                    appendLine(cells.joinToString(" | ", "| ", " |"))
                }
                appendLine()
            }
            appendLine("Open-tier relation types: ${report.relationTypes.joinToString(", ").ifEmpty { "none" }}.")
            section(
                "Dangling groundings",
                cs.flatMap { c ->
                    c.danglingGroundings.map { "${c.qname} — `${it.binding}`: ${it.ref}" }
                },
            )
            section(
                "Redundant term duplicates",
                cs.flatMap { c ->
                    c.redundantTerms.map {
                        "${c.qname} — \"${it.form}\" (${it.locale ?: "base"}): term `${it.conceptEntry}`, and term " +
                            "`${it.elementEntry}` on ${it.element}"
                    }
                },
            )
            section(
                "Phase as default sense",
                cs.flatMap { c ->
                    c.phaseAsDefault.map {
                        "${c.qname} — term `${it.entry}` defaults \"${it.form}\" to the phase, but " +
                            "\"${it.form}\" also names ${it.alsoNames} up its `of:` chain (cn/phase-as-default)"
                    }
                },
            )
            appendLine()
            appendLine("## Retired codes")
            appendLine()
            val retired = cs.flatMap { c -> c.retiredCodes.map { "- ${c.qname} — $it" } }
            if (retired.isEmpty()) appendLine("None — ${HygieneReport.RETIRED_CODES_NOTE.substringBefore(" — none")}.")
            retired.forEach(::appendLine)
        }

    private fun StringBuilder.section(
        title: String,
        rows: List<String>,
    ) {
        appendLine()
        appendLine("## $title")
        appendLine()
        if (rows.isEmpty()) appendLine("None.")
        rows.forEach { appendLine("- $it") }
    }

    private const val DASH = "—"

    private val GRADE_ORDER =
        listOf(
            GroundingGrade.ER_GROUNDED,
            GroundingGrade.MD_GROUNDED,
            GroundingGrade.MD_GROUNDED_HOP_MISSING,
            GroundingGrade.UNGROUNDED,
        )
}
