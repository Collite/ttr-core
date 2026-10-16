// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.walker

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.QueryDef

/**
 * LQ-T0·S1 — mirrors the TypeScript `logical-query.test.ts`. A `def query` in a `model er` file is a
 * logical query (project `ttr-translator/logical-queries`, L-1/L-2); declaring `parameters` on it is
 * flagged `ttr/logical-query-parameters` (ruling LQ-5). A warning in both walkers — the Kotlin
 * parser empties a file's definitions on any error, and TS whole-file tools (format, migrate) refuse
 * a file with one — so the load-time refusal lives in ttr-metadata (`logical_query_parameters`).
 */
class LogicalQuerySpec :
    StringSpec({
        val logical = "model er\n\ndef query q {\n  sourceText: \"\"\"sql\nSELECT sale.revenue FROM sale\n\"\"\"\n}\n"
        val logicalParams =
            "model er\n\ndef query q {\n  sourceText: \"\"\"sql\nSELECT sale.revenue FROM sale\n\"\"\",\n" +
                "  parameters: [ { name: since, type: date } ]\n}\n"

        fun lqWarnings(src: String) =
            TtrLoader.parseString(src).warnings.filter {
                it.message.startsWith(DiagnosticCode.LogicalQueryParameters.id)
            }

        "a query under model er parses as an ordinary query, er directive kept" {
            val r = TtrLoader.parseString(logical)
            r.ok shouldBe true
            r.modelDirective?.modelCode shouldBe "er"
            r.definitions.single().shouldBeInstanceOf<QueryDef>()
            lqWarnings(logical).shouldBeEmpty()
        }

        "a logical query with parameters is flagged and still walks" {
            val r = TtrLoader.parseString(logicalParams)
            r.ok shouldBe true
            r.definitions.single().shouldBeInstanceOf<QueryDef>()
            val w = lqWarnings(logicalParams)
            w shouldHaveSize 1
            w.single().message shouldContain "'q'"
        }

        "the same parameters on a db or directive-less query are not flagged" {
            lqWarnings(logicalParams.replace("model er", "model db")).shouldBeEmpty()
            lqWarnings(logicalParams.replace("model er\n\n", "")).shouldBeEmpty()
        }
    })
