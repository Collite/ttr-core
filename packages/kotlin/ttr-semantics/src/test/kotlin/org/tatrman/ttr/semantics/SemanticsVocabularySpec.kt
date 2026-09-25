// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.semantics.semanticsblock.MeasureRef
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics
import org.tatrman.ttr.semantics.semanticsblock.SymbolRef
import org.tatrman.ttr.semantics.semanticsblock.Vocabulary

/**
 * The closed `semantics { … }` vocabulary as a TABLE — the twin of the TS suite
 * (`packages/semantics/src/__tests__/semantics-vocabulary.test.ts`), case names kept
 * aligned for conformance triage. Validator behaviour lives in [SemanticsValidationSpec];
 * what belongs here is only "what does the vocabulary contain".
 *
 * The two v2 cases below moved here from the validation spec in MS-P1·S1, where they had
 * always been misplaced: they assert the table, not the validator.
 */
class SemanticsVocabularySpec :
    StringSpec({

        "the journal-role family is registered (S5C-B.4, contracts §12 R30)" {
            val roles = Vocabulary.ATTRIBUTE_ROLES
            // valid_from/valid_to reused; the four new roles added.
            listOf("valid_flag", "valid_from", "valid_to", "version", "authored_by", "written_at")
                .all { it in roles } shouldBe true
            roles.getValue("version").typeConstraint shouldBe Vocabulary.TypeConstraint.Numeric
            roles.getValue("authored_by").typeConstraint shouldBe Vocabulary.TypeConstraint.Text
            roles.getValue("written_at").typeConstraint shouldBe Vocabulary.TypeConstraint.Date
            roles.getValue("valid_flag").typeConstraint shouldBe null // boolean — unconstrained
        }

        // ---- v3 (MS — mention facet) ----

        "is version 3" {
            Vocabulary.SEMANTICS_VOCABULARY_VERSION shouldBe 3
        }

        "carries the five entity/table keys (LP D4 added code_pattern), in declaration order" {
            // Order is meaningful in the message of SemMisplacedKeyword and in the README table.
            Vocabulary.ALL_ENTITY_KEYS shouldBe listOf("kind", "name", "code", "code_pattern", "measures")
        }

        "closes the aggregation vocabulary, defaulting to sum" {
            Vocabulary.AGGREGATIONS shouldBe listOf("sum", "avg", "min", "max", "count", "last")
            Vocabulary.DEFAULT_AGGREGATION shouldBe "sum"
        }

        "gives every role a grounding facet and a known family" {
            // Every role that exists today answers "what computation grounds on this column".
            // The mention facet (name/code/measures) is declared entity-side and deliberately
            // adds no role — see design.md §2 on the single-valued `role:` collision.
            //
            // ⛑ review-082 F4 — asserted against `Vocabulary.ROLE_FAMILIES`, not against a fourth
            // hand-written copy of the same four strings. `family` is a bare `String` on this side
            // (the TS twin closes it as a union and `tsc` enforces it), so `ROLE_FAMILIES` is the
            // only thing that can reject a typo here — and it could not, while nothing read it.
            Vocabulary.ROLE_FAMILIES shouldBe listOf("dates", "geo", "finance", "journal")
            for ((role, spec) in Vocabulary.ATTRIBUTE_ROLES) {
                withClue(role) {
                    spec.facet shouldBe Vocabulary.FACET_GROUNDING
                    Vocabulary.ROLE_FAMILIES shouldContain spec.family
                }
            }
        }

        "pins the family of one role per family" {
            Vocabulary.ATTRIBUTE_ROLES.getValue("event_date").family shouldBe "dates"
            Vocabulary.ATTRIBUTE_ROLES.getValue("geo_point").family shouldBe "geo"
            Vocabulary.ATTRIBUTE_ROLES.getValue("amount").family shouldBe "finance"
            Vocabulary.ATTRIBUTE_ROLES.getValue("written_at").family shouldBe "journal"
        }

        "assigns every role to its contracts §2 family" {
            val byFamily = Vocabulary.ATTRIBUTE_ROLES.entries.groupBy({ it.value.family }, { it.key })
            byFamily.getValue("dates").sorted() shouldBe
                listOf(
                    "calendar_date",
                    "document_date",
                    "due_date",
                    "event_date",
                    "period_code",
                    "period_end",
                    "period_start",
                    "posting_date",
                    "valid_from",
                    "valid_to",
                ).sorted()
            byFamily.getValue("geo").sorted() shouldBe listOf("geo_lat", "geo_lon", "geo_point").sorted()
            byFamily.getValue("finance").sorted() shouldBe
                listOf(
                    "amount",
                    "amount_domestic",
                    "currency_code",
                    "fx_from_currency",
                    "fx_rate",
                    "fx_to_currency",
                ).sorted()
            byFamily.getValue("journal").sorted() shouldBe
                listOf("authored_by", "valid_flag", "version", "written_at").sorted()
        }

        // MS: adding a role now requires facet+family — this count moves consciously, with
        // the vocabulary version.
        "still has exactly twenty-three roles" {
            Vocabulary.ATTRIBUTE_ROLES.size shouldBe 23
        }

        // ---- the resolved model shape (contracts §3) ----

        "carries name, code and ordered measures with a kind-less block" {
            // A mention-only block is legal: an entity can declare how people refer to it
            // without being a period table, a calendar, a POI or an fx-rate table.
            val r =
                ResolvedEntitySemantics(
                    name = SymbolRef("customer_name"),
                    code = SymbolRef("doc_no"),
                    measures =
                        listOf(
                            MeasureRef(SymbolRef("amount_czk"), "sum"),
                            MeasureRef(SymbolRef("quantity"), "avg"),
                        ),
                )
            r.kind shouldBe null
            r.name?.path shouldBe "customer_name"
            r.code?.path shouldBe "doc_no"
            // Order is the contract: the FIRST measure is the default measure.
            r.measures.map { it.attribute.path } shouldBe listOf("amount_czk", "quantity")
            r.measures.map { it.aggregation } shouldBe listOf("sum", "avg")
        }

        "treats an empty measures list as \"none declared\"" {
            ResolvedEntitySemantics(kind = "period_table").measures shouldBe emptyList()
        }
    })
