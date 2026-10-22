// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.concept.v1.ConceptRef
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * CN-P3 S3.7 — the golden runner: one test per case under `resources/goldens/` ([GoldenCases]), run against
 * [CnConceptService] over the case's world ([GoldenWorlds]), compared with its `expected.*`. A `.pending` case is
 * reported as ignored, named with its demand id — never as passed.
 *
 * The goldens were authored FIRST (S3.7 runs before S3.2) and turned green stage by stage; since S3.8 every case runs
 * in `check`. The pending set is pinned ([PENDING]): a new `.pending` file is a deliberate edit, never a quiet skip.
 */
class ConceptGoldenSpec :
    FunSpec({
        test("the pending cases are exactly the pinned set, each with its demand") {
            GoldenCases.pending.associate { it.id to it.demand } shouldBe PENDING
        }
        for (p in GoldenCases.pending) {
            test("${p.id} — pending ${p.demand}").config(enabled = false) {}
        }
        for (c in GoldenCases.cases) {
            test("${c.id} [${c.call}, ${c.stage}]") {
                val problems = run(c)
                if (problems.isNotEmpty()) fail("${c.id}:\n" + problems.joinToString("\n") { "- $it" })
            }
        }
    }) {
    companion object {
        /**
         * The cases waiting on a primitive that does not exist yet (case → demand id; plan.md, `demands/`): G-ER-1/2
         * (#201), G-MD-1/2/6, `md/hop` (⚑CN-P8), CN-P5 S5.3. When a demand lands, its cases leave this table.
         */
        val PENDING: Map<String, String> =
            mapOf(
                "activecustomer/cross-cubelet" to "G-MD-1",
                "activecustomer/phase-filter-debtor-customer" to "G-MD-1",
                "activecustomer/phase-filter-has-broken-leg" to "G-MD-6",
                "activecustomer/phase-filter-post-stemi" to "G-MD-6",
                "activecustomer/phase-filter-stemi-patient" to "G-MD-2",
                "channel/by-channel-sales" to "md/hop",
                "channel/where-websale-carrier" to "md/hop",
                "refusals/retired-code" to "CN-P5 S5.3",
                "temporal/dc12-post-stemi-certainly" to "G-ER-1",
                "temporal/dc4-return-share" to "md/hop",
                "temporal/late-return-line" to "G-ER-2",
                "temporal/length-of-stay" to "G-ER-2",
                "temporal/rehospitalized-30d" to "G-ER-2",
                "temporal/transfer-delay" to "G-ER-2",
                "temporal/transferred-within-24h" to "G-ER-1",
                "temporal/within-around-instant" to "G-ER-1",
            )

        private val printer = JsonFormat.printer()

        internal fun run(c: GoldenCases.Case): List<String> {
            val svc: ConceptService = CnConceptService(GoldenWorlds[c.world].cn)
            val out = mutableListOf<String>()

            fun diagnosticsOf(
                ds: List<Diagnostic>,
                file: String = "expected.diagnostics.json",
            ) {
                val expected = if (c.has(file)) c.jsonFile(file) else JsonArray(emptyList())
                val actual = GoldenCases.diagnostics(ds)
                if (expected != actual) out += "diagnostics: expected $expected\n  actual $actual"
            }

            fun text(
                file: String,
                actual: String,
                normalize: (String) -> String = { it.trim() },
            ) {
                val expected = normalize(c.text(file))
                val got = normalize(actual)
                if (expected != got) out += "$file: expected\n  $expected\n  actual\n  $got"
            }
            when (c.call) {
                "statementValidate" -> diagnosticsOf(svc.statementValidate(c.statement()))
                "assertionValidate" -> diagnosticsOf(svc.assertionValidate(c.assertion()))
                "statementLower" -> {
                    val r = svc.statementLower(c.statement())
                    text("expected.dotpath.txt", r.dotPath)
                    val plan = r.plan?.let { Json.parseToJsonElement(printer.print(it)) }
                    val expectedPlan = c.jsonFile("expected.plan.json")
                    if (plan != expectedPlan) out += "plan: expected $expectedPlan\n  actual $plan"
                    if (c.has("expected.provenance.json")) {
                        out +=
                            GoldenCases.mismatches(
                                c.jsonFile("expected.provenance.json").jsonObject,
                                GoldenCases.provenance(r.provenance),
                                emptySet(),
                            )
                    }
                    diagnosticsOf(r.diagnostics)
                }
                "statementExplain" -> {
                    val e = svc.statementExplain(c.statement(), c.str("locale")!!)
                    text("expected.txt", e.text)
                    diagnosticsOf(e.diagnostics)
                }
                "assertionExplain" -> {
                    val e = svc.assertionExplain(c.assertion(), c.str("locale")!!)
                    text("expected.txt", e.text)
                    diagnosticsOf(e.diagnostics)
                }
                "assertionLower" -> {
                    val r = svc.assertionLower(c.assertion())
                    out +=
                        GoldenCases.mismatches(
                            c.jsonFile("expected.rows.json").jsonObject,
                            GoldenCases.rows(r),
                            alwaysCompared = setOf("diagnostics", "assertedAsOf", "obligations"),
                        )
                }
                "definitionRender" -> {
                    val options = options(c.spec["options"]?.jsonObject ?: JsonObject(emptyMap()), out)
                    val r =
                        svc.definitionRender(
                            ref(c.str("target")!!),
                            Layer.valueOf(c.str("layer")!!),
                            c.str("dialect")!!,
                            options,
                        )
                    // byte-equal: the formatting contract (contracts §3, A-CN-42); the file ends with one newline
                    text("expected.sql", r.text) { it.removeSuffix("\n") }
                    val file = "expected.rendering.json"
                    val expected = if (c.has(file)) c.jsonFile(file).jsonObject else JsonObject(emptyMap())
                    out += GoldenCases.mismatches(expected, GoldenCases.rendering(r))
                }
                "materialize" -> {
                    val m = svc.materialize(ref(c.str("target")!!), c.str("dialect")!!)
                    val dir = c.file("expected")
                    val expectedFiles =
                        if (Files.isDirectory(dir)) {
                            Files.walk(dir).use { s -> s.filter { it.isRegularFile() }.sorted().toList() }.associate {
                                dir.relativize(it).toString() to it.readText()
                            }
                        } else {
                            emptyMap()
                        }
                    if (expectedFiles.mapValues { it.value.trim() } != m.files.mapValues { it.value.trim() }) {
                        val shown = m.files.values.joinToString("\n---\n")
                        out += "files: expected ${expectedFiles.keys}\n  actual ${m.files.keys}\n$shown"
                    }
                    diagnosticsOf(m.diagnostics)
                }
                else -> out += "unknown call `${c.call}`"
            }
            return out
        }

        private fun ref(qname: String): ConceptRef = ConceptRef.newBuilder().setQname(qname).build()

        /**
         * [RenderOptions] from a case's `options`. A key the type does not carry yet (S3.4 T1 adds `candidates`,
         * `columns`, `asOf`) is reported, not dropped — the case stays red until the option exists.
         */
        internal fun options(
            o: JsonObject,
            out: MutableList<String>,
        ): RenderOptions {
            var r = RenderOptions()
            for ((k, v) in o) {
                when (k) {
                    "mode" -> r = r.copy(mode = RenderMode.valueOf(v.jsonPrimitive.content))
                    "placeholderStyle" ->
                        r =
                            r.copy(placeholderStyle = PlaceholderStyle.valueOf(v.jsonPrimitive.content))
                    "asOf" -> r = r.copy(asOf = Instant.parse(v.jsonPrimitive.content))
                    "candidates" -> r = r.copy(candidates = ref(v.jsonPrimitive.content))
                    "columns" -> r = r.copy(columns = v.jsonArray.map { it.jsonPrimitive.content })
                    else -> out += "option `$k` (${describe(v)}) is not a RenderOptions field yet"
                }
            }
            return r
        }

        private fun describe(v: JsonElement): String =
            (v as? JsonPrimitive)?.contentOrNull ?: runCatching { v.jsonArray.toString() }.getOrDefault(v.toString())
    }
}
