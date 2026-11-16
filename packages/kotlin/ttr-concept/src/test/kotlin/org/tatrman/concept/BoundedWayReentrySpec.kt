// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam
import org.tatrman.translator.wire.PlanNodeDecoder

/**
 * AU-core S2.4c (ttr-core#407) — the hartland `decide_return` way island re-enters as REL_NODE.
 *
 * Query compiles an island in two passes: SQL → ER plan, then `REL_NODE → DB`. The `reject_late` port casts its
 * `int` parameter into a reason text (`'delivered more than ' || CAST(:return_window AS VARCHAR) || ' days ago'`);
 * pass 2 failed with `rel_node_schema_resolution_failed` and no cause. Over the slice of ttr-demo's model the way reads
 * and the production handle ([ErModelHandle]), with the parameter types the Door binds: the ER plan must decode, the
 * two-half physical plan must equal the single-call one byte for byte, and a port without a parameter cast (and the
 * per-line control) must keep passing.
 */
class BoundedWayReentrySpec :
    StringSpec({
        // The slice of ttr-demo's model the way reads (`wire/hartland-way/world`, extracted verbatim at the islands' commit).
        val model by lazy {
            val dir = DefinitionFixtures.repo.resolve("packages/kotlin/ttr-concept/src/test/resources/wire/hartland-way/world")
            DefinitionFixtures.load(listOf("er.ttrm", "db.ttrm", "er2db.ttrm").associateWith { dir.resolve(it) })
        }

        // What the Door binds for this recipe (its declared parameter types).
        val params =
            listOf(
                SqlParam("return_window", "int", 30),
                SqlParam("abuse_share", "float", 0.4),
                SqlParam("care_threshold", "float", 12500.0),
                SqlParam("small_value", "float", 375.0),
                SqlParam("as_of_minus_return_window_days", "datetime", "2026-10-17 12:11:41"),
                SqlParam("as_of_minus_365_days", "datetime", "2025-11-16 12:11:41"),
            )

        val colon = Regex("(?<![\\w:]):(\\w+)")

        fun sql(file: String): String =
            javaClass
                .getResource("/wire/hartland-way/$file")!!
                .readText()
                .replace(colon) { "{${it.groupValues[1]}}" }

        fun parse(
            source: String,
            language: Language,
            target: SchemaCode,
        ): ParseResult =
            Translator(ErModelHandle(model)).parseToRelNode(
                source,
                language,
                targetSchema = target,
                sourceSchema = if (language == Language.SQL) SchemaCode.ER else SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                parameters = if (language == Language.SQL) params else emptyList(),
            )

        fun success(r: ParseResult): PlanNode = r.shouldBeInstanceOf<ParseResult.Success>().plan

        fun erPlan(file: String): PlanNode = success(parse(sql(file), Language.SQL, SchemaCode.ER))

        /** Pass 2 of Query's compile: the ER plan's bytes re-entered as REL_NODE, to the db tier. */
        fun reentered(file: String): ParseResult =
            parse(String(erPlan(file).toByteArray(), Charsets.ISO_8859_1), Language.REL_NODE, SchemaCode.DB)

        for (file in listOf("way.reject_late.sql", "way.to_customer_care.sql", "request_value.rows.sql")) {
            "(a) $file — the ER plan decodes over a fresh framework" {
                val plan = PlanNode.parseFrom(erPlan(file).toByteArray())
                PlanNodeDecoder.decode(plan, TranslatorFramework(ErModelHandle(model)))
            }

            "(b) $file — two halves (ER → REL_NODE → DB) give the single-call DB plan byte for byte" {
                val single = success(parse(sql(file), Language.SQL, SchemaCode.DB))
                val two = success(reentered(file))
                two.toByteArray().contentEquals(single.toByteArray()) shouldBe true
            }
        }
    })
