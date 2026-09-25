// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics
import org.tatrman.ttr.semantics.semanticsblock.SemanticsAnalyzer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Grounding Phase 1 (grammar 4.2) — the `semantics { … }` vocabulary/shape
 * validator (TTR-SEM-200…211). Mirrors the TS suite
 * (`packages/semantics/src/__tests__/semantics-block-validation.test.ts`); case
 * names are kept aligned for conformance triage.
 */
class SemanticsValidationSpec :
    StringSpec({

        fun ent(body: String): String = "model er\ndef entity E {\n$body\n}"

        fun codesFor(src: String): List<DiagnosticCode> =
            SemanticsAnalyzer
                .analyzeSemantics(
                    TtrLoader.parseString(src, "x.ttrm").definitions,
                ).diagnostics
                .map { it.code }

        fun diagsFor(src: String) =
            SemanticsAnalyzer.analyzeSemantics(TtrLoader.parseString(src, "x.ttrm").definitions).diagnostics

        fun analysisFor(src: String) =
            SemanticsAnalyzer.analyzeSemantics(TtrLoader.parseString(src, "x.ttrm").definitions)

        /** The first resolved element — the owner block, in every fixture below. */
        fun firstResolved(src: String) = analysisFor(src).resolved.values.firstOrNull()

        val members =
            "attributes: [ def attribute customer_name { type: text }, def attribute doc_no { type: text }, " +
                "def attribute amount_czk { type: decimal }, def attribute quantity { type: decimal } ]"

        "200 — unknown key (with nearest-match suggestion)" {
            val d =
                diagsFor(
                    ent(
                        "attributes: [ def attribute a { type: { type: varchar, length: 6 }, semantics { role: period_code, code_forma: \"x\" } } ]",
                    ),
                )
            val hit = d.firstOrNull { it.code == DiagnosticCode.SemUnknownKey }
            hit.shouldNotBeNull()
            hit.suggestion shouldBe "code_format"
        }

        "201 — unknown role (suggests event_date for event_dat)" {
            val d = diagsFor(ent("attributes: [ def attribute a { type: date, semantics { role: event_dat } } ]"))
            val hit = d.firstOrNull { it.code == DiagnosticCode.SemUnknownRole }
            hit.shouldNotBeNull()
            hit.suggestion shouldBe "event_date"
            (hit.message.contains("event_date")) shouldBe true
        }

        "202 — unknown kind (suggests period_table)" {
            val d = diagsFor(ent("semantics { kind: periodtable }"))
            val hit = d.firstOrNull { it.code == DiagnosticCode.SemUnknownKind }
            hit.shouldNotBeNull()
            hit.suggestion shouldBe "period_table"
        }

        "203 — duplicate key" {
            codesFor(
                ent("attributes: [ def attribute a { type: date, semantics { role: event_date, role: due_date } } ]"),
            ) shouldContain DiagnosticCode.SemDuplicateKey
        }

        // review-081 F2 — the twin of the TS "a structured value is a wrong SHAPE" block.
        //
        // MS-P0·S1b widened THIS walker to carry lists and nested objects verbatim, so the
        // analyzer inherited the judgement the walker gave up. Without the gate every lookup
        // ran `display()` over the structure and named a PERFECTLY VALID member as unknown:
        // a ListV displays as its items joined, so `kind: [period_table]` produced
        // "unknown entity/table kind 'period_table'". Shape is decided before vocabulary.
        "216 — kind: with a list value is a shape error, not an unknown kind" {
            val d = diagsFor(ent("semantics { kind: [period_table] }"))
            d.map { it.code } shouldBe listOf(DiagnosticCode.SemMentionShape)
            d[0].message shouldBe "'kind:' takes a single value, not a list"
        }

        "216 — role: with a list value is a shape error, not an unknown role" {
            val d = diagsFor(ent("attributes: [ def attribute a { type: date, semantics { role: [event_date] } } ]"))
            d.map { it.code } shouldBe listOf(DiagnosticCode.SemMentionShape)
            d[0].message shouldBe "'role:' takes a single value, not a list"
        }

        "216 — period: with a list value is a shape error, not a dangling ref" {
            diagsFor(
                ent(
                    "attributes: [ def attribute a { type: date, semantics { role: event_date, period: [P] } } ]",
                ),
            ).map { it.code } shouldBe listOf(DiagnosticCode.SemMentionShape)
        }

        "216 — currency: with an object value is a shape error, not a dangling ref" {
            val d =
                diagsFor(
                    ent(
                        "attributes: [ def attribute a { type: decimal, semantics { role: amount, currency: { x: 1 } } } ]",
                    ),
                )
            d.map { it.code } shouldBe listOf(DiagnosticCode.SemMentionShape)
            d[0].message shouldBe "'currency:' takes a single value, not an object"
        }

        "216 — code_format: with a list value no longer becomes the yyyyMM default silently" {
            diagsFor(
                ent(
                    "attributes: [ def attribute a { type: text, semantics { role: period_code, code_format: [x] } } ]",
                ),
            ).map { it.code } shouldBe listOf(DiagnosticCode.SemMentionShape)
        }

        "an unknown entity key whose value displays as a role name is not a misplaced keyword" {
            // The roster test reads the VALUE, and a ListV's display() is its items joined.
            diagsFor(ent("semantics { whatever: [event_date] }")).map { it.code } shouldBe
                listOf(DiagnosticCode.SemUnknownKey)
        }

        // review-081 F6 — a repeat inside a nested object is recorded as a path and
        // reported through the same SemDuplicateKey as one on the block itself.
        "203 — a duplicate key inside a nested object is reported with its path" {
            val d = diagsFor(ent("semantics { measures: [{ attribute: a, attribute: b }] }"))
            val dup = d.firstOrNull { it.code == DiagnosticCode.SemDuplicateKey }
            dup.shouldNotBeNull()
            dup.message shouldBe "duplicate semantics key 'measures[0].attribute'"
        }

        // ⛑ review-082 F3 — the twin of the TS case of the same name, which had none here. The
        // path form above is the interesting one, which is exactly why the ordinary one needs
        // pinning too: a walker that started prefixing every repeat would still pass the case
        // above and quietly change every existing block-level message.
        "a block-level repeat is still the bare key" {
            val d = diagsFor(ent("semantics { name: customer_name, name: doc_no }, $members"))
            val dup = d.firstOrNull { it.code == DiagnosticCode.SemDuplicateKey }
            dup.shouldNotBeNull()
            dup.message shouldBe "duplicate semantics key 'name'"
        }

        "204 — kind on an attribute, and role on an entity" {
            codesFor(
                ent("attributes: [ def attribute a { type: date, semantics { kind: poi } } ]"),
            ) shouldContain DiagnosticCode.SemMisplacedKeyword
            codesFor(ent("semantics { role: event_date }")) shouldContain DiagnosticCode.SemMisplacedKeyword
        }

        "205 — type-constraint violation (amount on a text column)" {
            codesFor(
                ent(
                    "attributes: [ def attribute a { type: { type: varchar, length: 3 }, semantics { role: amount, currency: a } } ]",
                ),
            ) shouldContain DiagnosticCode.SemTypeConstraint
        }

        "206 — completeness (period_table missing period_end)" {
            val src =
                ent(
                    listOf(
                        "semantics { kind: period_table },",
                        "attributes: [",
                        "  def attribute s { type: date, semantics { role: period_start } },",
                        "  def attribute c { type: { type: varchar, length: 6 }, semantics { role: period_code } }",
                        "]",
                    ).joinToString("\n"),
                )
            codesFor(src) shouldContain DiagnosticCode.SemCompleteness
        }

        "207 — more than one event_date on an entity" {
            val src =
                ent(
                    listOf(
                        "attributes: [",
                        "  def attribute a { type: date, semantics { role: event_date } },",
                        "  def attribute b { type: date, semantics { role: event_date } }",
                        "]",
                    ).joinToString("\n"),
                )
            codesFor(src) shouldContain DiagnosticCode.SemMultipleEventDate
        }

        "208 — period: to a nonexistent entity, and to a non-period_table entity" {
            codesFor(
                ent("attributes: [ def attribute a { type: date, semantics { role: event_date, period: Nope } } ]"),
            ) shouldContain DiagnosticCode.SemBadPeriodRef
            val miskinded =
                listOf(
                    "model er",
                    "def entity P { semantics { kind: poi }, attributes: [ def attribute x { type: decimal, semantics { role: geo_lat } }, def attribute y { type: decimal, semantics { role: geo_lon } } ] }",
                    "def entity E { attributes: [ def attribute a { type: date, semantics { role: event_date, period: P } } ] }",
                ).joinToString("\n")
            codesFor(miskinded) shouldContain DiagnosticCode.SemBadPeriodRef
        }

        "209 — currency: to a missing sibling, and to a non-currency_code sibling" {
            codesFor(
                ent("attributes: [ def attribute a { type: decimal, semantics { role: amount, currency: nope } } ]"),
            ) shouldContain DiagnosticCode.SemBadCurrencyRef
            val roleless =
                ent(
                    listOf(
                        "attributes: [",
                        "  def attribute a { type: decimal, semantics { role: amount, currency: c } },",
                        "  def attribute c { type: date, semantics { role: event_date } }",
                        "]",
                    ).joinToString("\n"),
                )
            codesFor(roleless) shouldContain DiagnosticCode.SemBadCurrencyRef
        }

        "210 — geo_lat without geo_lon, and geo_point + pair" {
            codesFor(
                ent(
                    "semantics { kind: poi }, attributes: [ def attribute a { type: decimal, semantics { role: geo_lat } } ]",
                ),
            ) shouldContain DiagnosticCode.SemGeoPair
            val both =
                ent(
                    listOf(
                        "semantics { kind: poi },",
                        "attributes: [",
                        "  def attribute p { type: text, semantics { role: geo_point } },",
                        "  def attribute a { type: decimal, semantics { role: geo_lat } },",
                        "  def attribute o { type: decimal, semantics { role: geo_lon } }",
                        "]",
                    ).joinToString("\n"),
                )
            codesFor(both) shouldContain DiagnosticCode.SemGeoPair
        }

        "211 — valid_from without valid_to" {
            codesFor(
                ent("attributes: [ def attribute a { type: date, semantics { role: valid_from } } ]"),
            ) shouldContain DiagnosticCode.SemValidPair
        }

        "green path — the golden 59-semantics.ttrm fixture yields zero diagnostics" {
            val src = Files.readString(fixturesDir().resolve("59-semantics.ttrm"))
            diagsFor(src) shouldBe emptyList()
        }

        "green path — the golden 60-semantics-db.ttrm fixture yields zero diagnostics" {
            val src = Files.readString(fixturesDir().resolve("60-semantics-db.ttrm"))
            diagsFor(src) shouldBe emptyList()
        }

        // -------------------------------------------------------------------
        // MS (vocabulary v3) — the mention facet. contracts §1 (surface) + §4
        // (diagnostics). Twins of the TS suite's `MS — …` describes; case names
        // kept aligned for conformance triage.
        // -------------------------------------------------------------------

        "accepts kind + name + code + measures and resolves them (contracts §1.1)" {
            // `kind: period_table` brings its own completeness rule (206), so the fixture carries
            // the three period roles too — the point here is that the grounding facet and the
            // mention facet coexist on one block without interfering.
            val src =
                ent(
                    "semantics { kind: period_table, name: customer_name, code: doc_no, " +
                        "measures: [amount_czk, { attribute: quantity, aggregation: avg }] }, " +
                        "attributes: [ def attribute customer_name { type: text }, " +
                        "def attribute doc_no { type: text }, " +
                        "def attribute amount_czk { type: decimal }, def attribute quantity { type: decimal }, " +
                        "def attribute start_date { type: date, semantics { role: period_start } }, " +
                        "def attribute end_date { type: date, semantics { role: period_end } }, " +
                        "def attribute period { type: text, semantics { role: period_code } } ]",
                )
            diagsFor(src) shouldBe emptyList()
            val e = firstResolved(src) as ResolvedEntitySemantics
            e.kind shouldBe "period_table"
            e.name?.path shouldBe "customer_name"
            e.code?.path shouldBe "doc_no"
            // Declared order is the contract — the first measure is the default measure.
            e.measures.map { it.attribute.path to it.aggregation } shouldBe
                listOf("amount_czk" to "sum", "quantity" to "avg")
        }

        "accepts the same surface on a db table, against its columns" {
            val src =
                "model db\ndef table sales {\n" +
                    "semantics { name: customer_name, code: doc_no, measures: [amount_czk] },\n" +
                    "columns: [ def column customer_name { type: text }, def column doc_no { type: text }, " +
                    "def column amount_czk { type: decimal } ]\n}"
            diagsFor(src) shouldBe emptyList()
            val e = firstResolved(src) as ResolvedEntitySemantics
            e.name?.path shouldBe "customer_name"
            e.measures.map { it.attribute.path } shouldBe listOf("amount_czk")
        }

        "accepts a mention-only block — no kind at all" {
            val src = ent("semantics { name: customer_name }, " + members)
            diagsFor(src) shouldBe emptyList()
            val e = firstResolved(src) as ResolvedEntitySemantics
            e.kind shouldBe null
            e.measures shouldBe emptyList()
        }

        // ⛑ The misplaced-keyword branch tests the VALUE against the role roster, so an
        // attribute that happens to be named like a role (`amount`, `version`, `due_date`)
        // would be reported as an attribute key on an entity block. The mention keys have to
        // be recognised before that branch runs.
        "accepts a mention ref whose target is named like a role" {
            val src =
                ent(
                    "semantics { name: version, measures: [amount] }, " +
                        "attributes: [ def attribute version { type: text }, " +
                        "def attribute amount { type: decimal } ]",
                )
            diagsFor(src) shouldBe emptyList()
        }

        // -------------------------------------------------------------------
        // LP review-103 (D4) — `code_pattern:`, the code attribute's value regex. Twins of the TS
        // suite's `LP D4 — …` cases.
        // -------------------------------------------------------------------

        "LP D4 — code_pattern beside code: resolves, as written" {
            val src =
                ent("""semantics { name: customer_name, code: doc_no, code_pattern: "^[A-P]{16}$" }, """ + members)
            diagsFor(src) shouldBe emptyList()
            val e = firstResolved(src) as ResolvedEntitySemantics
            e.code?.path shouldBe "doc_no"
            e.codePattern shouldBe "^[A-P]{16}$"
        }

        "LP D4 — a doubled backslash reaches the analyzer as ONE (the parsers' escape rule)" {
            // `\d` must be written `\\d` inside a TTR string; this pins what the analyzer then sees.
            val src = ent("""semantics { code: doc_no, code_pattern: "^\\d{6}$" }, """ + members)
            diagsFor(src) shouldBe emptyList()
            (firstResolved(src) as ResolvedEntitySemantics).codePattern shouldBe """^\d{6}$"""
        }

        "LP D4 — a Java-only construct is judged by the JVM, and the JVM accepts it" {
            val src = ent("""semantics { code: doc_no, code_pattern: "(?i)^[a-z]{2}[0-9]+$" }, """ + members)
            diagsFor(src) shouldBe emptyList()
        }

        "219 — a code_pattern that does not compile, and the block degrades" {
            val a = analysisFor(ent("""semantics { code: doc_no, code_pattern: "^[A-P{16}$" }, """ + members))
            val hit = a.diagnostics.single()
            hit.code shouldBe DiagnosticCode.SemBadCodePattern
            hit.message shouldContain "is not a valid regular expression"
            a.resolved.size shouldBe 0
        }

        "219 — an empty code_pattern" {
            codesFor(ent("""semantics { code: doc_no, code_pattern: "" }, """ + members)) shouldBe
                listOf(DiagnosticCode.SemBadCodePattern)
        }

        "219 — a code_pattern with no code: beside it" {
            codesFor(ent("""semantics { name: customer_name, code_pattern: "^X[0-9]+$" }, """ + members)) shouldBe
                listOf(DiagnosticCode.SemBadCodePattern)
        }

        "219 does not pile onto a code: that failed to resolve — 212 alone says what is wrong" {
            codesFor(ent("""semantics { code: nonexistent, code_pattern: "^X[0-9]+$" }, """ + members)) shouldBe
                listOf(DiagnosticCode.SemMentionRefUnresolved)
        }

        "216 — a code_pattern that is not a single value" {
            codesFor(ent("""semantics { code: doc_no, code_pattern: [a, b] }, """ + members)) shouldBe
                listOf(DiagnosticCode.SemMentionShape)
        }

        "code_pattern is an ENTITY key — on an attribute block it is unknown" {
            codesFor(
                ent(
                    "attributes: [ def attribute p { type: text, " +
                        "semantics { role: period_code, code_pattern: \"^[0-9]{6}$\" } } ]",
                ),
            ) shouldContain DiagnosticCode.SemUnknownKey
        }

        "212 — a name: that is not an attribute of THIS entity" {
            codesFor(ent("semantics { name: nonexistent }, " + members)) shouldContain
                DiagnosticCode.SemMentionRefUnresolved
        }

        "212 — a measure belonging to ANOTHER entity does not resolve" {
            // The owner-scoped assertion: `other_col` exists in the document, just not here.
            val src =
                "model er\ndef entity Other { attributes: [ def attribute other_col { type: decimal } ] }\n" +
                    "def entity E { semantics { measures: [other_col] }, " + members + " }"
            codesFor(src) shouldContain DiagnosticCode.SemMentionRefUnresolved
        }

        "213 — a measure that is not numeric" {
            codesFor(ent("semantics { measures: [customer_name] }, " + members)) shouldContain
                DiagnosticCode.SemMeasureNotNumeric
        }

        "214 — the same attribute listed twice, in either spelling" {
            codesFor(ent("semantics { measures: [amount_czk, amount_czk] }, " + members)) shouldContain
                DiagnosticCode.SemMeasureDuplicate
            codesFor(
                ent(
                    "semantics { measures: [amount_czk, { attribute: amount_czk, aggregation: avg }] }, " + members,
                ),
            ) shouldContain DiagnosticCode.SemMeasureDuplicate
        }

        "215 — an aggregation outside the closed vocabulary, with a suggestion" {
            val d =
                diagsFor(
                    ent("semantics { measures: [{ attribute: amount_czk, aggregation: summ }] }, " + members),
                )
            val hit = d.firstOrNull { it.code == DiagnosticCode.SemBadAggregation }
            hit.shouldNotBeNull()
            hit.suggestion shouldBe "sum"
        }

        "216 — a measures item that is neither an id nor an {attribute, …} object" {
            codesFor(ent("semantics { measures: [42] }, " + members)) shouldContain
                DiagnosticCode.SemMentionShape
        }

        "216 — an unknown key inside a measures item object" {
            codesFor(
                ent("semantics { measures: [{ attribute: amount_czk, aggregate: avg }] }, " + members),
            ) shouldContain DiagnosticCode.SemMentionShape
        }

        "216 — measures that is not a list, and a name: that is not an id" {
            codesFor(ent("semantics { measures: amount_czk }, " + members)) shouldContain
                DiagnosticCode.SemMentionShape
            codesFor(ent("semantics { name: 7 }, " + members)) shouldContain DiagnosticCode.SemMentionShape
        }

        "an empty measures list is legal, and equivalent to absent" {
            // contracts §1.1: `measures: []` is legal and means the same as not writing it. So it
            // is not an error, and it does not by itself make the block carry a fact worth
            // resolving — an entity that declared nothing gets no ResolvedEntitySemantics, exactly
            // as one with no semantics block at all does.
            val a = analysisFor(ent("semantics { measures: [] }, " + members))
            a.diagnostics shouldBe emptyList()
            a.resolved.size shouldBe 0
        }

        "a block with any mention ERROR degrades — no resolved semantics for it" {
            analysisFor(ent("semantics { name: nonexistent }, " + members)).resolved.size shouldBe 0
        }

        "suggests `measures` for a typo on the entity block" {
            val hit =
                diagsFor(ent("semantics { measurse: [amount_czk] }, " + members))
                    .firstOrNull { it.code == DiagnosticCode.SemUnknownKey }
            hit?.suggestion shouldBe "measures"
        }

        // The aggregation-surface firewall (plan risk 4, contracts §1.1 ⚠). Three different
        // `aggregation:` surfaces exist — the def-level attribute property (EN-P1.2 derived
        // attributes), md's measure property, and the measures-item key. They must not read
        // each other.
        "a def-level aggregation neither satisfies nor conflicts with the measures default" {
            val src =
                ent(
                    "semantics { measures: [total] }, " +
                        "attributes: [ def attribute total { type: decimal, aggregation: sum } ]",
                )
            diagsFor(src) shouldBe emptyList()
            // 'sum' here is the measures-side DEFAULT for a bare id — not the def property,
            // which says something else entirely ("this attribute is derived by aggregating").
            (firstResolved(src) as ResolvedEntitySemantics).measures[0].aggregation shouldBe "sum"
        }

        "the measures-side value is unaffected by the def-level property changing shape" {
            val src =
                ent(
                    "semantics { measures: [total] }, " +
                        "attributes: [ def attribute total { type: decimal, aggregation: { default: avg } } ]",
                )
            diagsFor(src) shouldBe emptyList()
            (firstResolved(src) as ResolvedEntitySemantics).measures[0].aggregation shouldBe "sum"
        }

        // contracts §1.2 / MS-D2 — the legacy `nameAttribute:` / `codeAttribute:` matrix.
        "legacy only — the deprecation warning, and no mismatch" {
            // The "works as today" half of contracts §1.2 row 1 is `EntityDef.nameAttribute`
            // continuing to feed the metadata merge — that is MS-P1·S2's `Source.kt`, not
            // something this layer can assert (there is no semantics block here to resolve).
            val c = codesFor(ent("nameAttribute: customer_name, " + members))
            c shouldContain DiagnosticCode.SemLegacyMentionDeprecated
            c shouldNotContain DiagnosticCode.SemLegacyMentionMismatch
        }

        "semantics only — clean" {
            diagsFor(ent("semantics { name: customer_name }, " + members)) shouldBe emptyList()
        }

        "both, agreeing — the deprecation warning only" {
            codesFor(
                ent("nameAttribute: customer_name, semantics { name: customer_name }, " + members),
            ) shouldBe listOf(DiagnosticCode.SemLegacyMentionDeprecated)
        }

        "both, disagreeing — ERROR, and the block degrades" {
            val a = analysisFor(ent("nameAttribute: doc_no, semantics { name: customer_name }, " + members))
            a.diagnostics.map { it.code } shouldContain DiagnosticCode.SemLegacyMentionMismatch
            // "A disagreement is always a bug" (MS-D2) — degrade, do not pick a winner.
            a.resolved.size shouldBe 0
        }

        "codeAttribute follows the same matrix" {
            codesFor(ent("codeAttribute: doc_no, " + members)) shouldContain
                DiagnosticCode.SemLegacyMentionDeprecated
            codesFor(
                ent("codeAttribute: doc_no, semantics { code: customer_name }, " + members),
            ) shouldContain DiagnosticCode.SemLegacyMentionMismatch
        }

        // review-081 F1 (TS) — `ownerClean = r.clean && legacyMentionOk(...)` short-circuits, so
        // the whole §1.2 matrix switched off for any block carrying an unrelated error. The
        // Kotlin twin is written without the short-circuit from the start; these pin it.
        "row 4 (mismatch) still fires alongside an unrelated block error" {
            val c =
                codesFor(
                    ent("nameAttribute: doc_no, semantics { name: customer_name, bogus_key: 1 }, " + members),
                )
            c shouldContain DiagnosticCode.SemUnknownKey
            c shouldContain DiagnosticCode.SemLegacyMentionMismatch
        }

        "rows 1 and 3 (deprecation) still fire alongside an unrelated block error" {
            val c = codesFor(ent("nameAttribute: customer_name, semantics { kind: nope }, " + members))
            c shouldContain DiagnosticCode.SemUnknownKind
            c shouldContain DiagnosticCode.SemLegacyMentionDeprecated
        }

        // review-081 F3 (TS) — `lastSeg` alone made a path pointing at ANOTHER entity agree, and
        // then advised deleting the legacy property: destructive advice from a comparison that
        // could not see the difference.
        "a legacy ref qualified to another entity is a mismatch, not a repeat" {
            val src =
                "model er\ndef entity Other { attributes: [ def attribute customer_name { type: text } ] }\n" +
                    "def entity E { nameAttribute: Other.customer_name, " +
                    "semantics { name: customer_name }, " + members + " }"
            val c = codesFor(src)
            c shouldContain DiagnosticCode.SemLegacyMentionMismatch
            c shouldNotContain DiagnosticCode.SemLegacyMentionDeprecated
        }

        "a legacy ref qualified to the owner itself still reads as a repeat" {
            codesFor(
                ent("nameAttribute: E.customer_name, semantics { name: customer_name }, " + members),
            ) shouldBe listOf(DiagnosticCode.SemLegacyMentionDeprecated)
        }

        // review-081 F2 (TS) — MS's own new code doing what the six findings did: naming a VALID
        // member as unknown because the shape was never checked.
        "aggregation: was reported as an unknown aggregation naming a valid one" {
            val d =
                diagsFor(
                    ent("semantics { measures: [{ attribute: amount_czk, aggregation: [avg] }] }, " + members),
                )
            d.map { it.code } shouldBe listOf(DiagnosticCode.SemMentionShape)
            d[0].message shouldBe "'aggregation:' takes a single value, not a list"
        }

        // review-081 F4 (TS) — contracts §4 names the SemMisplacedKeyword rewrite as a
        // requirement, and this is the string the TS twin pins. Text, not just code.
        "204 lists kind, name, code, code_pattern and measures" {
            val hit =
                diagsFor(ent("semantics { code_format: \"x\" }"))
                    .firstOrNull { it.code == DiagnosticCode.SemMisplacedKeyword }
            hit?.message shouldBe
                "'code_format' is an attribute/column key; entity/table blocks carry " +
                "'kind', 'name', 'code', 'code_pattern', 'measures'"
        }
    })

private fun fixturesDir(): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve("tests/conformance/fixtures")
        if (Files.isDirectory(candidate)) return candidate
        dir = dir.parent
    }
    error("could not locate tests/conformance/fixtures")
}
