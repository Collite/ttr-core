// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.tf

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.translator.params.SqlParam
import java.io.File

/**
 * TF-P0 — the translator-fidelity regression fixture: every probe of the legacy-pattern gaps report
 * (`project/legacy/docs/translator-gaps-legacy-patterns.md`, groups A1…A11, B, C5…C13, D) and every
 * template of the legacy corpus, run through the full round trip the parse gate will use —
 * `parseToRelNode(SQL → DB)` → wire → `unparseFromRelNode(MSSQL, optimize = true)`.
 *
 * `tf/cases.json` — one entry per probe, with the expected MSSQL text or the expected failure:
 * * `"status": "green"` — asserted.
 * * `"status": "red:TF-P<n>.S<m>"` — a known gap. The case itself is registered disabled, plus a
 *   guard `"<id> is still red (expected to be fixed by <stage>)"` that FAILS as soon as the case
 *   passes — so a fix that lands early is noticed and the status is flipped in the same commit.
 * * `"status": "out-of-scope"` — a failure by design (C13), asserted like `green`.
 *
 * `tf/legacy-cases.json` — the 101 legacy templates (against `tf/legacy-model.json`) with
 * `parse`/`unparse` = `ok|fail` only, same statuses, plus a summary test pinning the exact
 * `parse ok / unparse ok` counts.
 *
 * Golden text is never written from memory: run with `TF_DUMP=<file>` to record what the engine
 * actually produces for every case (plan rule 8), read it, then edit the JSON.
 */
class MssqlRoundTripSpec :
    StringSpec({
        val mapper = ObjectMapper()

        val probeCases = TfCases.probes(mapper.readTree(resource("tf/cases.json")))
        val legacy = mapper.readTree(resource("tf/legacy-cases.json"))
        val legacyCases = TfCases.legacy(legacy.get("cases"))

        val probeTranslator = Translator(FixtureModel.tfHandle())
        val legacyTranslator = Translator(InMemoryModelHandle.fromJson(resource("tf/legacy-model.json")))

        for (case in probeCases) {
            registerStatus(case.id, case.status, { roundTrip(probeTranslator, case.sql, case.params) }) {
                case.expect.check(it)
            }
        }

        for (case in legacyCases) {
            registerStatus(case.id, case.status, { roundTrip(legacyTranslator, case.sql, case.params) }) {
                case.check(it)
            }
        }

        "legacy corpus: parse ok / unparse ok counts are pinned" {
            val outcomes = legacyCases.map { roundTrip(legacyTranslator, it.sql, it.params) }
            val parseOk = outcomes.count { it !is Outcome.ParseFailed }
            val unparseOk = outcomes.count { it is Outcome.Unparsed }
            val summary = legacy.get("summary")
            "$parseOk parse ok / $unparseOk unparse ok" shouldBe
                "${summary.get("parseOk").asInt()} parse ok / ${summary.get("unparseOk").asInt()} unparse ok"
        }

        System.getenv("TF_DUMP")?.takeIf { it.isNotBlank() }?.let { path ->
            "dump actual outcomes to TF_DUMP" {
                val out = mapper.createObjectNode()

                fun record(
                    id: String,
                    o: Outcome,
                ) = out.set<JsonNode>(id, o.toJson(mapper))
                probeCases.forEach { record(it.id, roundTrip(probeTranslator, it.sql, it.params)) }
                legacyCases.forEach { record(it.id, roundTrip(legacyTranslator, it.sql, it.params)) }
                mapper.enable(SerializationFeature.INDENT_OUTPUT).writeValue(File(path), out)
            }
        }
    })

/** The three ways a round trip ends. SQL is whitespace-collapsed (the harness's `one()`). */
sealed interface Outcome {
    data class Unparsed(
        val sql: String,
    ) : Outcome

    data class ParseFailed(
        val code: String,
        val message: String,
    ) : Outcome

    data class UnparseFailed(
        val code: String,
        val message: String,
    ) : Outcome

    fun toJson(mapper: ObjectMapper): JsonNode =
        mapper.createObjectNode().apply {
            when (val o = this@Outcome) {
                is Unparsed -> put("sql", o.sql)
                is ParseFailed -> put("parseCode", o.code).put("message", o.message)
                is UnparseFailed -> put("unparseCode", o.code).put("message", o.message)
            }
        }
}

/** Expectation of a probe case. [check] returns `null` when met, else what differs. */
sealed interface Expect {
    fun check(o: Outcome): String?

    data class Sql(
        val sql: String,
    ) : Expect {
        override fun check(o: Outcome): String? =
            if (o is Outcome.Unparsed && o.sql == oneLine(sql)) null else "expected SQL <${oneLine(sql)}> but was $o"
    }

    data class ParseFailure(
        val code: String,
        val messageContains: String?,
    ) : Expect {
        override fun check(o: Outcome): String? =
            if (o is Outcome.ParseFailed &&
                o.code == code &&
                (messageContains == null || o.message.contains(messageContains))
            ) {
                null
            } else {
                "expected parse failure $code containing <$messageContains> but was $o"
            }
    }

    data class UnparseFailure(
        val code: String,
        val messageContains: String?,
    ) : Expect {
        override fun check(o: Outcome): String? =
            if (o is Outcome.UnparseFailed &&
                o.code == code &&
                (messageContains == null || o.message.contains(messageContains))
            ) {
                null
            } else {
                "expected unparse failure $code containing <$messageContains> but was $o"
            }
    }
}

data class ProbeCase(
    val id: String,
    val group: String,
    val sql: String,
    val params: List<TfParam>,
    val expect: Expect,
    val status: String,
)

data class LegacyCase(
    val id: String,
    val sql: String,
    val params: List<TfParam>,
    val parseOk: Boolean,
    val unparseOk: Boolean,
    val status: String,
) {
    fun check(o: Outcome): String? {
        val actualParse = o !is Outcome.ParseFailed
        val actualUnparse = o is Outcome.Unparsed
        return if (actualParse == parseOk && (!parseOk || actualUnparse == unparseOk)) {
            null
        } else {
            "expected parse=${ok(parseOk)} unparse=${ok(unparseOk)} but was $o"
        }
    }

    private fun ok(b: Boolean) = if (b) "ok" else "fail"
}

data class TfParam(
    val name: String,
    val type: String,
)

internal object TfCases {
    private val STATUS = Regex("""green|out-of-scope|red:TF-P\d\.S\d""")

    fun probes(root: JsonNode): List<ProbeCase> =
        root
            .map { n ->
                val id = n.text("id")
                ProbeCase(
                    id = id,
                    group = n.text("group"),
                    sql = n.text("sql"),
                    params = params(n),
                    expect = expect(id, n.get("expect") ?: error("case '$id' has no 'expect'")),
                    status = status(id, n),
                )
            }.also { uniqueIds(it.map(ProbeCase::id)) }

    fun legacy(root: JsonNode): List<LegacyCase> =
        root
            .map { n ->
                val id = n.text("id")
                LegacyCase(
                    id = id,
                    sql = n.text("sql"),
                    params = params(n),
                    parseOk = okFail(id, n.text("parse")),
                    unparseOk = okFail(id, n.text("unparse")),
                    status = status(id, n),
                )
            }.also { uniqueIds(it.map(LegacyCase::id)) }

    private fun expect(
        id: String,
        e: JsonNode,
    ): Expect =
        when {
            e.has("sql") -> Expect.Sql(e.text("sql"))
            e.has("parseCode") -> Expect.ParseFailure(e.text("parseCode"), e.get("messageContains")?.asText())
            e.has("unparseCode") -> Expect.UnparseFailure(e.text("unparseCode"), e.get("messageContains")?.asText())
            else -> error("case '$id': 'expect' needs one of sql / parseCode / unparseCode")
        }

    private fun params(n: JsonNode): List<TfParam> =
        n.get("params")?.map { TfParam(it.text("name"), it.text("type")) } ?: emptyList()

    private fun status(
        id: String,
        n: JsonNode,
    ): String = n.text("status").also { require(STATUS.matches(it)) { "case '$id': bad status '$it'" } }

    private fun okFail(
        id: String,
        v: String,
    ): Boolean =
        when (v) {
            "ok" -> true
            "fail" -> false
            else -> error("case '$id': expected ok|fail, got '$v'")
        }

    private fun uniqueIds(ids: List<String>) {
        val dup = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "duplicate case ids: $dup" }
    }

    private fun JsonNode.text(field: String): String =
        get(field)?.takeIf { it.isTextual }?.asText() ?: error("missing string field '$field' in $this")
}

private fun resource(path: String): String =
    requireNotNull(MssqlRoundTripSpec::class.java.classLoader.getResource(path)) {
        "missing test resource $path"
    }.readText()

private fun oneLine(s: String): String = s.replace(Regex("""\s+"""), " ").trim()

/**
 * `parseToRelNode(SQL, DB)` then `unparseFromRelNode(MSSQL, optimize = true)` — the calls
 * `translator-harness/Harness2.java` makes, with the same dummy binding values.
 */
internal fun roundTrip(
    translator: Translator,
    sql: String,
    params: List<TfParam>,
): Outcome {
    val parsed =
        try {
            translator.parseToRelNode(
                sql,
                Language.SQL,
                SchemaCode.DB,
                null,
                SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                params.map { SqlParam(it.name, it.type, null) },
            )
        } catch (t: Throwable) {
            return Outcome.ParseFailed("threw", oneLine("${t::class.simpleName}: ${t.message}"))
        }
    val plan =
        when (parsed) {
            is ParseResult.Failure -> return Outcome.ParseFailed(parsed.code, oneLine(parsed.message))
            is ParseResult.Success -> parsed.plan
        }
    return try {
        when (
            val u =
                translator.unparseFromRelNode(
                    plan,
                    Language.SQL,
                    SqlDialect.MSSQL,
                    true,
                    dummyBindings(params),
                )
        ) {
            is UnparseResult.Failure -> Outcome.UnparseFailed(u.code, oneLine(u.message))
            is UnparseResult.Success -> Outcome.Unparsed(oneLine(u.output))
        }
    } catch (t: Throwable) {
        Outcome.UnparseFailed("threw", oneLine("${t::class.simpleName}: ${t.message}"))
    }
}

/** One binding per declared parameter; the values only matter for the positional expansion. */
internal fun dummyBindings(params: List<TfParam>): List<ParameterBinding> =
    params.map { p ->
        val v = Value.newBuilder()
        when (p.type) {
            "int" -> v.setIntValue(1)
            "decimal" -> v.setStringValue("1.5")
            "date", "datetime" -> v.setDatetimeValue("2026-01-01")
            else -> v.setStringValue("x")
        }
        ParameterBinding
            .newBuilder()
            .setName(p.name)
            .setType(p.type)
            .setValue(v)
            .build()
    }

/**
 * Registers one case according to its status: `green`/`out-of-scope` asserted; `red:<stage>`
 * registered disabled plus the inverted still-red guard.
 */
private fun StringSpec.registerStatus(
    id: String,
    status: String,
    run: () -> Outcome,
    check: (Outcome) -> String?,
) {
    if (status.startsWith("red:")) {
        val stage = status.removePrefix("red:")
        id.config(enabled = false) {
            withClue(id) { check(run()) shouldBe null }
        }
        "$id is still red (expected to be fixed by $stage)" {
            val problem = check(run())
            withClue("$id now passes — flip its status to green in the same commit") {
                (problem != null) shouldBe true
            }
        }
    } else {
        id {
            withClue(id) { check(run()) shouldBe null }
        }
    }
}
