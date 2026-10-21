// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.model.Basis

/**
 * CN-P2 S2.6 T6 — the definition-level slugs (contracts §7; CN-D29: Kotlin's, `ttr cn hygiene`, never the editor),
 * table-driven over `resources/definitions` (package `hartland.defs`): for each slug a concept it fires on and one it
 * does not. A definition that does not parse or resolve is only reported as that (T5) — no lint reads partial reads.
 */
class CnDefinitionLintsSpec :
    StringSpec({
        val diagnostics = DefinitionFixtures.defs.definitionDiagnostics()

        fun on(
            concept: String,
            slug: String,
        ) = diagnostics.filter { it.subject == "cn.$concept" && it.slug == slug }

        data class Case(
            val slug: String,
            val severity: Severity,
            val fires: List<String>,
            val quiet: List<String>,
            val says: String,
        )

        listOf(
            Case(
                CnDefinitionLints.CYCLE,
                Severity.ERROR,
                listOf("Ping", "Pong"),
                listOf("DebtorCustomer"),
                "Ping → Pong → Ping",
            ),
            Case(
                CnDefinitionLints.UNRESOLVED,
                Severity.HYGIENE,
                listOf("Frequent", "LateDelivery"),
                listOf("DebtorCustomer"),
                "ungrounded `Visit`",
            ),
            Case(
                "cn/definition-unreachable(md)",
                Severity.HYGIENE,
                listOf("DebtorCustomer", "PendingOrder"),
                listOf("BigSpender"),
                "`Invoicing` has no md grounding",
            ),
            Case(
                "cn/definition-unreachable(er)",
                Severity.HYGIENE,
                listOf("BigSpender"),
                listOf("DebtorCustomer", "PendingOrder"),
                "`lifetime_value` lands nowhere on E-R",
            ),
            Case(
                CnDefinitionLints.BASIS_MISMATCH,
                Severity.ERROR,
                listOf("HadStockout", "NoReading", "AgedOrder"),
                listOf("OutOfStock", "EverStocked", "RecentBuyer", "BoundedBuyer"),
                "`basis: past_state`",
            ),
            Case(
                CnDefinitionLints.IMPRECISE,
                Severity.ADVISORY,
                listOf("LateDelivery", "RecentOrder"),
                listOf("LateDeliveryMaybe", "OverdueInvoice"),
                "compares a month with a day",
            ),
        ).forEach { c ->
            "${c.slug}: fires on ${c.fires.joinToString()}, not on ${c.quiet.joinToString()}" {
                c.fires.forEach { concept ->
                    val found = on(concept, c.slug)
                    found.size shouldBe 1
                    found.single().severity shouldBe c.severity
                    found.single().location.shouldNotBeNull()
                }
                on(c.fires.first(), c.slug).single().message shouldContain c.says
                c.quiet.forEach { concept -> on(concept, c.slug).shouldBeEmpty() }
            }
        }

        "the basis shape table covers every basis (08b D-T7, pinned at S2.6)" {
            CnDefinitionLints.BASIS_SHAPES.keys shouldBe Basis.entries.toSet()
            on("NoReading", CnDefinitionLints.BASIS_MISMATCH).single().message shouldContain "reads no `:as_of`"
            on("HadStockout", CnDefinitionLints.BASIS_MISMATCH).single().message shouldContain "reads `current_state`"
            // `age(since:)` reads `:as_of`, in no shape of the table (review-119 M-4)
            on("AgedOrder", CnDefinitionLints.BASIS_MISMATCH).single().message shouldContain
                "the definition reads `:as_of` in none of the table's shapes"
        }

        "the granularity of an instant: `period: { precision }`, else its attribute's type; `:as_of` is a day" {
            // Delivery's `period: { at, precision: month }` vs `:as_of`; Order's `placed_at` is a timestamp
            on("LateDelivery", CnDefinitionLints.IMPRECISE).single().message shouldContain "a month with a day"
            on("RecentOrder", CnDefinitionLints.IMPRECISE).single().message shouldContain "a second with a day"
        }

        "a definition that does not resolve is reported once, by T5 — no lint reads it" {
            val unresolved = listOf("UnknownQuality", "UnknownLabel", "UnknownConcept", "UnknownParam", "BrokenSyntax")
            unresolved.forEach { concept ->
                diagnostics.filter { it.subject == "cn.$concept" }.map { it.slug } shouldContainExactly
                    DefinitionFixtures.defs.definitions.all
                        .single { it.owner.name == concept }
                        .diagnostics
                        .map { it.slug }
            }
        }

        "the slugs are the contracts §7 spellings" {
            listOf(
                CnDefinitionLints.CYCLE,
                CnDefinitionLints.UNRESOLVED,
                CnDefinitionLints.UNREACHABLE,
                CnDefinitionLints.BASIS_MISMATCH,
                CnDefinitionLints.IMPRECISE,
            ) shouldBe
                listOf(
                    "cn/definition-cycle",
                    "cn/definition-unresolved",
                    "cn/definition-unreachable",
                    "cn/basis-mismatch",
                    "cn/imprecise-comparison",
                )
        }
    })
