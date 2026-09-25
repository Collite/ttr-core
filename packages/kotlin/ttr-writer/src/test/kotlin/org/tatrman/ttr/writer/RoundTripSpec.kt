// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.SemanticsValue
import org.tatrman.ttr.parser.model.TableDef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Round-trip guarantee (contracts.md §3): `parseString(render(r))` reproduces a
 * structurally-equal model. We express it via render-idempotence — `render∘parse`
 * is a fixed point — which catches any structural drift through the round trip
 * without a fragile SourceLocation-stripping deep-equals:
 *
 *   text1 = render(parse(src)); text2 = render(parse(text1)); assert text1 == text2
 *
 * plus the invariant that the re-parse always succeeds.
 */
class RoundTripSpec :
    StringSpec({

        val fixtures =
            mapOf(
                "model" to "def project erp { version: \"1.2.3\", tags: [\"a\", \"b\"] }",
                "table+columns" to
                    """
                    def table customers {
                        primaryKey: ["id"]
                        columns: [
                            def column id { type: int, isKey: true },
                            def column total { type: { type: decimal, length: 19, precision: 5 } },
                            def column name { type: text, indexed: true }
                        ]
                    }
                    """.trimIndent(),
                "table+search" to
                    """
                    def table customers {
                        primaryKey: ["id"]
                        columns: [
                            def column id { type: int, isKey: true }
                        ]
                        search { searchable: true, keywords { en: ["customer"] } }
                    }
                    """.trimIndent(),
                "relation+search" to
                    """
                    def relation customer_orders {
                        from: er.customer
                        to: er.order
                        search { searchable: true, fuzzy: true }
                    }
                    """.trimIndent(),
                "entity+attributes+search" to
                    """
                    def entity Customer {
                        labelPlural: "Customers"
                        aliases: ["client"]
                        attributes: [
                            def attribute id { type: int, isKey: true },
                            def attribute name { type: text, search { searchable: true, fuzzy: true } }
                        ]
                    }
                    """.trimIndent(),
                "relation" to
                    """
                    def relation customer_orders {
                        from: er.customer
                        to: er.order
                        cardinality: { fromMin: 0, fromMax: 1, toMin: 0, toMax: -1 }
                    }
                    """.trimIndent(),
                // Grammar 0.12 (RV-P1.5, RV-32) — the match-method attribute and the deprecated
                // boolean it replaces. `fuzzy: false` is in here deliberately: under 0.12 it means
                // EXACT, so a renderer that dropped it (as "false is the default") would change
                // what the model says.
                "table+search-method" to
                    """
                    def table customers {
                        primaryKey: ["id"]
                        columns: [
                            def column id { type: int, isKey: true },
                            def column name { type: text, search { searchable: true method: TYPOS(2) } },
                            def column code { type: text, search { searchable: true method: EXACT } },
                            def column note { type: text, search { searchable: true method: TOKENS } }
                        ]
                        search { searchable: true, fuzzy: false }
                    }
                    """.trimIndent(),
                // Grounding Phase 1 (grammar 4.2) — `semantics { … }` on entity + attributes
                // (kind at entity level, role + refs/params at attribute level).
                "entity+semantics" to
                    """
                    def entity AccountingPeriod {
                        semantics { kind: period_table }
                        attributes: [
                            def attribute start_date { type: date, semantics { role: period_start } },
                            def attribute period { type: text, semantics { role: period_code, code_format: "yyyyMM" } },
                            def attribute amount { type: decimal, semantics { role: amount, currency: currency_code } }
                        ]
                    }
                    """.trimIndent(),
                // MS (vocabulary v3) — the mention facet. This is the case the writer could
                // not render at all before the parser carried structure: a `measures:` list
                // mixing a bare id with an item object. Round-tripping it is what proves the
                // list ORDER survives, which is contract (first item = the default measure).
                "entity+semantics+measures" to
                    """
                    def entity sales {
                        semantics { kind: period_table, name: customer_name, code: doc_no, measures: [amount_czk, { attribute: quantity, aggregation: avg }] }
                        attributes: [
                            def attribute customer_name { type: text },
                            def attribute doc_no { type: text },
                            def attribute amount_czk { type: decimal },
                            def attribute quantity { type: decimal }
                        ]
                    }
                    """.trimIndent(),
                // LP review-103 (D4) — `code_pattern:`, a quoted regex beside `code:`. It is not an
                // id, so the writer must QUOTE it; a backslash must come back doubled so the
                // re-parse unescapes it to the one the model meant.
                "entity+semantics+code_pattern" to
                    """
                    def entity promotion {
                        semantics { name: promo_name, code: promo_id, code_pattern: "^[A-P]{16}$", measures: [cost] }
                        attributes: [
                            def attribute promo_name { type: text },
                            def attribute promo_id { type: text },
                            def attribute cost { type: decimal }
                        ]
                    }
                    """.trimIndent(),
                "entity+semantics+code_pattern+backslash" to
                    """
                    def entity period {
                        semantics { code: code, code_pattern: "^\\d{6}$" }
                        attributes: [
                            def attribute code { type: text }
                        ]
                    }
                    """.trimIndent(),
                "table+column+semantics" to
                    """
                    def table poi {
                        semantics { kind: poi }
                        columns: [
                            def column point { type: text, semantics { role: geo_point } }
                        ]
                    }
                    """.trimIndent(),
                "query+params" to
                    """
                    def query topCustomers {
                        language: SQL
                        sourceText: "select 1"
                        parameters: [
                            { name: limit, type: int, label: "Limit" }
                        ]
                    }
                    """.trimIndent(),
                "role" to "def role fact { label: { cs: \"Fakta\", en: \"Facts\" } }",
                "er2cnc_role" to "def er2cnc_role rf { entity: er.sales, role: cnc.role.fact }",
                "er2db_entity" to "def er2db_entity m { entity: er.customer, target: { table: db.dbo.customers } }",
                "drill_map" to
                    """
                    def drill_map d {
                        from: query.query.a,
                        to: query.query.b,
                        args: { p: "C" },
                    }
                    """.trimIndent(),
                // MD dot-path (S5C-B) — the materialize-generated logical model + physical binding.
                "md_measure" to "def measure net { domain: md.Money, class: additive, aggregation: sum }",
                "md_measure_perdim_agg" to
                    "def measure headcount { domain: md.Count, class: semiAdditive, " +
                    "aggregation: { default: sum, Time: latestValid }, validBy: validFrom }",
                "md_cubelet" to "def cubelet sales { grain: [Customer.name, Time.day], measures: [net, gross] }",
                "md2db_cubelet wide" to
                    """
                    def md2db_cubelet sales_binding {
                        cubelet: md.sales,
                        target: db.dbo.f_sales,
                        shape: wide,
                        attributes: {
                            Customer.name: { column: customer_name },
                            Customer.region: { via: md.name_to_region, from: { table: db.dbo.d_customer, column: region } }
                        },
                        measures: { net: { column: net }, gross: { column: gross } },
                        allocation: proportional
                    }
                    """.trimIndent(),
                "md2db_cubelet long" to
                    """
                    def md2db_cubelet plan_binding {
                        cubelet: md.plan,
                        target: db.dbo.f_plan,
                        shape: { long: { codeColumn: measure_code, valueColumn: amount } },
                        attributes: { Customer.name: { column: customer_name }, Time.month: { column: month_num } },
                        measures: { net: { code: NET } },
                        journaling: { invalidate: { validColumn: is_current } },
                        allocation: { Time: equal }
                    }
                    """.trimIndent(),
            )

        fixtures.forEach { (label, src) ->
            "round-trips: $label" {
                val parsed1 = TtrLoader.parseString(src)
                parsed1.ok shouldBe true

                val text1 = TtrRenderer.render(parsed1.definitions)
                val parsed2 = TtrLoader.parseString(text1)
                parsed2.ok shouldBe true

                val text2 = TtrRenderer.render(parsed2.definitions)
                // render∘parse is a fixed point — no structural drift.
                text2 shouldBe text1
            }
        }

        // Grounding Phase 1 (grammar 4.2) — the golden 59-semantics.ttrm fixture:
        // parse → write → reparse, then assert every `semantics { … }` block (entity
        // kinds + attribute roles/refs/params) is byte-for-byte reproduced. This is
        // the AST-equal-modulo-trivia guarantee specialised to the semantics surface.
        "round-trips the 59-semantics.ttrm fixture's semantics blocks" {
            val fixture = locateFixturesDir().resolve("59-semantics.ttrm")
            val parsed1 = TtrLoader.parseFile(fixture)
            parsed1.ok shouldBe true

            val reparsed = TtrLoader.parseString(TtrRenderer.render(parsed1.definitions))
            reparsed.ok shouldBe true

            collectSemantics(reparsed) shouldBe collectSemantics(parsed1)
        }
    })

/**
 * Every `semantics { … }` block keyed by its owner path (entity/table +
 * attribute/column), reduced to `(entries, duplicateProperties)` — `source` spans
 * legitimately shift under re-rendering, so they are excluded from the compare.
 */
private fun collectSemantics(r: ParseResult): Map<String, Pair<Map<String, SemanticsValue>, List<String>>> {
    val out = LinkedHashMap<String, Pair<Map<String, SemanticsValue>, List<String>>>()
    for (def in r.definitions) {
        when (def) {
            is EntityDef -> {
                def.semantics?.let { out[def.name] = it.entries to it.duplicateProperties }
                def.attributes.forEach { a ->
                    a.semantics?.let {
                        out["${def.name}.${a.name}"] =
                            it.entries to it.duplicateProperties
                    }
                }
            }
            is TableDef -> {
                def.semantics?.let { out[def.name] = it.entries to it.duplicateProperties }
                def.columns.forEach { c ->
                    c.semantics?.let {
                        out["${def.name}.${c.name}"] =
                            it.entries to it.duplicateProperties
                    }
                }
            }
            else -> {}
        }
    }
    return out
}

private fun locateFixturesDir(): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve("tests/conformance/fixtures")
        if (Files.isDirectory(candidate)) return candidate
        dir = dir.parent
    }
    error("could not locate tests/conformance/fixtures")
}
