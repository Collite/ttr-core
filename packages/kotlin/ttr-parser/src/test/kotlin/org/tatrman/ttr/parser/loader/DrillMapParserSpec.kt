// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.model.DrillMapDef

/**
 * Phase 03 (new-Golem) — `def drill_map { from, to, args, display?, override? }`
 * lands as v2.2 of the TTR grammar (modeler@10282d4, vendored).
 *
 * Spec note: the Phase 03 task list pointed to
 * `infra/metadata/.../ttr/parser/DrillMapParserSpec.kt`, but the live parser
 * (and its sibling specs) actually live in the `ttr-parser` shared library, so
 * the spec lives here. Cross-reference + arg-name validation belongs to the
 * metadata-side validator (DrillMapValidatorSpec); this spec is parser-only.
 */
class DrillMapParserSpec :
    StringSpec({

        "happy path — full drill_map block round-trips into DrillMapDef" {
            val r =
                TtrLoader.parseString(
                    """
                    package returns
                    model query schema drill

                    def drill_map reason_to_return_detail {
                        from: query.query.returns_by_reason,
                        to:   query.query.return_detail,
                        args: { order_number: "cr_order_number" },
                        display: { en: "Return detail" },
                        override: true,
                    }
                    """.trimIndent(),
                )

            r.ok shouldBe true
            r.definitions shouldHaveSize 1
            val def = r.definitions[0]
            def.shouldBeInstanceOf<DrillMapDef>()
            def.name shouldBe "reason_to_return_detail"
            def.from?.path shouldBe "query.query.returns_by_reason"
            def.to?.path shouldBe "query.query.return_detail"
            def.args["order_number"] shouldBe "cr_order_number"
            def.display?.byLanguage?.get("en") shouldBe "Return detail"
            def.overrideAuto shouldBe true
        }

        "minimal drill_map — display + override default to absent/false" {
            val r =
                TtrLoader.parseString(
                    """
                    package returns
                    model query schema drill

                    def drill_map warehouse_to_return_detail {
                        from: query.query.returns_by_warehouse,
                        to:   query.query.return_detail,
                        args: { order_number: "cr_order_number" },
                    }
                    """.trimIndent(),
                )

            r.ok shouldBe true
            val def = r.definitions[0]
            def.shouldBeInstanceOf<DrillMapDef>()
            def.display shouldBe null
            def.overrideAuto shouldBe false
        }

        "unknown property key inside drill_map block surfaces as parse error" {
            val r =
                TtrLoader.parseString(
                    """
                    package returns
                    model query schema drill

                    def drill_map x {
                        frmo: query.query.a,
                        to:   query.query.b,
                        args: { p: "C" },
                    }
                    """.trimIndent(),
                )

            r.ok shouldBe false
            r.errors.isNotEmpty() shouldBe true
        }

        "empty args block parses cleanly (validation deferred to metadata side)" {
            val r =
                TtrLoader.parseString(
                    """
                    package returns
                    model query schema drill

                    def drill_map x {
                        from: query.query.a,
                        to:   query.query.b,
                        args: {},
                    }
                    """.trimIndent(),
                )

            r.ok shouldBe true
            val def = r.definitions[0]
            def.shouldBeInstanceOf<DrillMapDef>()
            def.args.isEmpty() shouldBe true
        }
    })
