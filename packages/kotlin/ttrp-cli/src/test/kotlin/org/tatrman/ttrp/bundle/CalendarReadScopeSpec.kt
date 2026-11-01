// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** #330: action-time calendar reads through the real checker and filesystem bundle pipeline. */
class CalendarReadScopeSpec :
    StringSpec({
        fun locate(relative: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve(relative)
                if (Files.exists(candidate)) return candidate
                dir = dir.parent
            }
            error("could not locate $relative")
        }
        val fixtures = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model/hartland")
        val quote = "\"\"\""
        val source =
            """
            uses world "hartland.worlds.demo"
            import hartland.*
            import hartland.automation.*
            recipe {
                key: order.order_number
                as of: now
                candidates placed_orders(out orders) target erp ${quote}sql
                    SELECT o.order_number, o.placed_date AS placed FROM er.entity.order o
                $quote
                decision verdict(in orders) {
                    orders -> switch { release: true because: "calendar action" }
                }
                actions {
                    verdict.release -> notify_customer { customer: 42, to: 'customer-42@customers.hartland.example', template: VALUE }
                }
            }
            """.trimIndent()

        fun project(
            transform: (String) -> String = { it },
            extra: String = "",
        ): Path {
            val root = tempdir("calendar-read-scope").toPath().resolve("private-flat-checkout")
            val models = Files.createDirectories(root.resolve("models"))
            Files.walk(fixtures).use { paths ->
                paths.filter { Files.isRegularFile(it) }.sorted().toList().forEachIndexed { i, file ->
                    Files.writeString(models.resolve("metadata-$i.ttrm"), transform(Files.readString(file)))
                }
            }
            if (extra.isNotBlank()) Files.writeString(models.resolve("extra.ttrm"), extra)
            return root
        }

        fun build(
            text: String,
            transform: (String) -> String = { it },
            extra: String = "",
        ): BundleAssembler.BundleResult {
            val root = project(transform, extra)
            return BundleAssembler("calendar-test").build(
                source = text,
                fileName = "calendar_action.ttrp",
                pipelineManifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = root),
                modelsRoot = root.resolve("models"),
                outDir = root.resolve("out"),
                recipeDir = root,
            )
        }

        fun assertCalendar(result: BundleAssembler.BundleResult) {
            result.manifest.recipe!!.objectsRead shouldContainExactly listOf("er.entity.date_dim", "er.entity.order")
            val readme = Files.readString(result.dir.resolve("README.md"))
            readme shouldContain "er.entity.date_dim"
            result.manifest.files.getValue("README.md") shouldBe
                "sha256:" +
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(readme.toByteArray())
                    .joinToString("") { "%02x".format(it) }
        }

        for ((name, expression) in listOf(
            "working days" to "cast(working_days(between: placed, and: :as_of) as text)",
            "age" to "cast(age(since: placed) as text)",
            "bound date difference" to "cast(:as_of - placed as text)",
            "shifted bound date difference" to "cast((:as_of - days(1)) - placed as text)",
            "nested expression" to "case when order_number > 0 then cast(age(since: placed) as text) else 'none' end",
        )) {
            "$name in a direct action argument declares the model calendar" {
                assertCalendar(build(source.replace("VALUE", expression)))
            }
        }

        "a wiring when condition declares the calendar" {
            assertCalendar(
                build(
                    source.replace("VALUE", "'notice'").replace(
                        "template: 'notice' }",
                        "template: 'notice' } when: age(since: placed) > 0",
                    ),
                ),
            )
        }

        val stamp =
            """
            package hartland.automation
            model automation
            def action stamp {
                columns: [def column order { type: order.order_number },
                          def column tag { type: order.held_for, optional: true }]
                sinks: { native: writer }
                writer_defaults: { tag: { expression: "cast(age(since: placed) as text)" } }
                approval: none
            }
            """.trimIndent()
        "a directly wired writer default declares the calendar and keeps approval evidence" {
            val text =
                source.replace(
                    "notify_customer { customer: 42, to: 'customer-42@customers.hartland.example', template: VALUE }",
                    "stamp { order: order_number }",
                )
            val result = build(text, extra = stamp)
            assertCalendar(result)
            result.manifest.recipe!!
                .approvalDependencies!!
                .actions.keys
                .toList() shouldContainExactly listOf("stamp")
        }

        "all direct writer default count forms declare the calendar even when explicitly overridden" {
            val text =
                source.replace(
                    "notify_customer { customer: 42, to: 'customer-42@customers.hartland.example', template: VALUE }",
                    "stamp { order: order_number, tag: 'explicit' }",
                )
            for (expression in listOf(
                "working_days(between: placed, and: :as_of)",
                "age(since: placed)",
                ":as_of - placed",
            )) {
                withClue(expression) {
                    assertCalendar(build(text, extra = stamp.replace("age(since: placed)", expression)))
                }
            }
        }

        "declared date bindings require a calendar while integer bindings do not" {
            for ((kind, expected) in listOf(
                "date" to listOf("er.entity.date_dim", "er.entity.order"),
                "int" to listOf("er.entity.order"),
            )) {
                val parameter = "package hartland.automation\nmodel automation\ndef parameter cutoff { type: $kind }"
                val text =
                    source
                        .replace("as of: now", "as of: now parameters: [cutoff]")
                        .replace("VALUE", "cast(:cutoff - placed as text)")
                withClue(kind) {
                    build(text, extra = parameter).manifest.recipe!!.objectsRead shouldContainExactly
                        expected
                }
            }
        }

        "a calendar with a different name is selected by event_date semantics" {
            val result =
                build(
                    source.replace("VALUE", "cast(age(since: placed) as text)"),
                    transform = { it.replace("date_dim", "business_calendar") },
                )
            result.manifest.recipe!!.objectsRead shouldContainExactly
                listOf("er.entity.business_calendar", "er.entity.order")
        }

        "a malformed direct writer default is refused at its wiring instead of using a partial parse" {
            val root = project(extra = stamp.replace("cast(age(since: placed) as text)", "age(since: placed) trailing"))
            val plan =
                TtrpPipeline(TtrpManifest(world = "hartland.worlds.demo", manifestDir = root), root.resolve("models"))
                    .plan(
                        source.replace(
                            "notify_customer { customer: 42, to: 'customer-42@customers.hartland.example', template: VALUE }",
                            "stamp { order: order_number }",
                        ),
                        "calendar_action.ttrp",
                    )
            plan.ok shouldBe false
            val diagnostic = plan.diagnostics.single { it.message.contains("invalid writer default expression") }
            diagnostic.message shouldContain "stamp.tag"
            diagnostic.location.file shouldBe "calendar_action.ttrp"
        }

        "a recipe with no calendar expressions remains valid without a unique model calendar" {
            build(
                source.replace("VALUE", "'notice'"),
                transform = { it.replace("semantics { role: event_date }", "") },
            ).manifest.recipe!!
                .objectsRead shouldContainExactly listOf("er.entity.order")
        }

        "a visible unwired default does not declare the calendar" {
            val result = build(source.replace("VALUE", "'notice'"), extra = stamp)
            result.manifest.recipe!!.objectsRead shouldContainExactly listOf("er.entity.order")
            result.manifest.recipe!!.approvalDependencies shouldBe null
        }

        "function names in a string and ordinary arithmetic do not declare a calendar" {
            for (value in listOf(
                "'age(since: placed) :as_of - placed working_days'",
                "cast(order_number - 1 as text)",
            )) {
                withClue(value) {
                    build(source.replace("VALUE", value)).manifest.recipe!!.objectsRead shouldContainExactly
                        listOf("er.entity.order")
                }
            }
        }

        "an already loaded calendar has one sorted read entry" {
            val text =
                source
                    .replace("VALUE", "cast(age(since: placed) as text)")
                    .replace(
                        "FROM er.entity.order o",
                        "FROM er.entity.order o JOIN er.entity.date_dim d ON o.placed_date = d.cal_date",
                    )
            assertCalendar(build(text))
        }

        for ((name, transform) in listOf<Pair<String, (String) -> String>>(
            "missing" to { it.replace("semantics { role: event_date }", "") },
            "ambiguous" to { text ->
                if (text.contains("def entity date_dim")) {
                    text + "\n" +
                        text
                            .substringAfter("model er")
                            .replace("def entity date_dim", "def entity second_calendar")
                } else {
                    text
                }
            },
        )) {
            "a $name model calendar is a located compile diagnostic" {
                val root = project(transform)
                val plan =
                    TtrpPipeline(
                        TtrpManifest(world = "hartland.worlds.demo", manifestDir = root),
                        root.resolve("models"),
                    ).plan(source.replace("VALUE", "cast(age(since: placed) as text)"), "calendar_action.ttrp")
                withClue(plan.diagnostics) { plan.ok shouldBe false }
                val errors =
                    plan.diagnostics.filter {
                        it.message.contains(
                            "one entity with `semantics { role: event_date }`",
                        )
                    }
                errors.size shouldBe 1
                errors.single().location.file shouldBe "calendar_action.ttrp"
                (errors.single().location.line > 0) shouldBe true
            }
        }
    })
