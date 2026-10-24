// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationColumn
import org.tatrman.ttr.metadata.model.AutomationSet
import org.tatrman.ttr.metadata.model.AutomationTransition
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.parser.model.AutoEntry
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttrp.bundle.RecipeApprovalDependencies

class RecipeActionDependenciesSpec :
    StringSpec({
        val source = SourceLocation.UNKNOWN
        val entityName = QualifiedName(SchemaCode.ER, "entity", "proposal", "shop")
        val attr =
            Attribute(
                "attr-id",
                QualifiedName(SchemaCode.ER, "attribute", "proposal.id", "shop"),
                entity = entityName,
                type = "decimal",
                isKey = true,
                nullable = false,
                length = 10,
                precision = 0,
            )
        val entity = Entity("entity-id", entityName, attributes = listOf(attr))

        fun str(s: String) = AutoValue.Str(s, false, source)

        fun obj(vararg entries: Pair<String, AutoValue>) =
            AutoValue.Obj(entries.map { AutoEntry(it.first, it.second, source) }, source)
        val defaults = obj("id" to obj("expression" to str("1")))
        val action =
            AutomationAction(
                "action-id",
                QualifiedName(SchemaCode.AUTOMATION, "action", "propose", "shop.automation"),
                columns = listOf(AutomationColumn("id", "proposal.id", optional = true)),
                sinks = mapOf("native" to "writer"),
                properties = listOf("writer_defaults" to defaults),
            )

        fun fingerprints(
            a: AutomationAction = action,
            e: Entity = entity,
        ) = RecipeActionDependencies.fingerprints(mapOf("propose" to a)) { e.takeIf { it.qname.name == "proposal" } }
        val base = "sha256:" + "a".repeat(64)
        val evidence = RecipeApprovalDependencies(textBase = base, actions = fingerprints())
        val text = RecipeActionDependencies.text(evidence)

        "frozen native dependency and text2 byte vectors match an independent JSON SHA256 implementation" {
            // Independently derived with Python json.dumps(sort_keys=True, separators=(',', ':')) + hashlib.
            fingerprints() shouldBe
                mapOf("propose" to "sha256:139a18984e89d8fd87dbee9c0c4908fc0a950c94ae56eb1369105cbfddfa7c95")
            text shouldBe "sha256:1657f4c480805fe670bc3f69a2c326895a757c0e97e27abe68e5f8f070e5b55b"
        }

        "pinned consumer verification refuses absent unknown malformed or mismatched coverage" {
            fun matches(
                e: RecipeApprovalDependencies?,
                t: String = text,
                a: Map<String, AutomationAction> =
                    mapOf(
                        "propose" to action,
                    ),
            ) = RecipeActionDependencies.matches(e, t, a) { entity }
            matches(evidence) shouldBe true
            matches(null) shouldBe false
            matches(evidence.copy(version = 2)) shouldBe false
            matches(evidence.copy(textBase = "not a digest")) shouldBe false
            matches(evidence.copy(actions = emptyMap())) shouldBe false
            matches(evidence.copy(actions = mapOf("propose" to "invalid"))) shouldBe false
            matches(evidence.copy(actions = mapOf("other" to fingerprints().getValue("propose")))) shouldBe false
            matches(evidence.copy(textBase = "sha256:" + "b".repeat(64))) shouldBe false
            matches(evidence, base) shouldBe false
            matches(evidence, a = emptyMap()) shouldBe false
            matches(evidence, a = mapOf("propose" to action, "extra" to action)) shouldBe false
            matches(
                evidence,
                a = mapOf("propose" to action.copy(properties = listOf("writer_defaults" to obj()))),
            ) shouldBe
                false
            RecipeActionDependencies.matches(evidence, text, mapOf("propose" to action)) { null } shouldBe false
        }

        "all native target assignment key state and transition declarations change coverage" {
            val mutations: List<Attribute> =
                listOf(
                    attr.copy(type = "text"),
                    attr.copy(isKey = false),
                    attr.copy(nullable = true),
                    attr.copy(length = null),
                    attr.copy(precision = null),
                    attr.copy(precision = 1),
                    attr.copy(aggregated = true),
                    attr.copy(states = listOf("proposed")),
                    attr.copy(transitions = mapOf("proposed" to listOf("sent"))),
                    attr.copy(qname = attr.qname.copy(`package` = "other")),
                )
            mutations.forEachIndexed { i, changed ->
                withClue("attribute mutation $i") {
                    fingerprints(e = entity.copy(attributes = listOf(changed))) shouldNotBe fingerprints()
                }
            }
            fingerprints(
                e = entity.copy(attributes = listOf(attr, attr.copy(qname = attr.qname.copy(name = "proposal.extra")))),
            ) shouldNotBe
                fingerprints()
        }

        "typed action mappings and raw extension values are independently covered" {
            val mutations =
                listOf(
                    action.copy(columns = listOf(AutomationColumn("id", "proposal.id"))),
                    action.copy(sinks = mapOf("native" to "transition")),
                    action.copy(set = AutomationSet("proposal.id", "id")),
                    action.copy(
                        transition = AutomationTransition("proposal.id", to = "sent", from = listOf("proposed")),
                    ),
                    action.copy(approval = "required"),
                    action.copy(deliverable = true),
                    action.copy(properties = action.properties + ("future_extension" to str("value"))),
                )
            mutations.forEachIndexed { i, changed ->
                withClue("action mutation $i") { fingerprints(a = changed) shouldNotBe fingerprints() }
            }
        }

        "canonicalization drops presentation and identity bookkeeping but retains duplicate and scalar distinctions" {
            val moved =
                action.copy(
                    internalId = "reconciled-id",
                    sourceFile = "/different/checkout/action.ttrm",
                    description = "new prose",
                    properties = listOf("dry_run" to str("would do"), "label" to str("new label")) + action.properties,
                )
            fingerprints(a = moved) shouldBe fingerprints()
            fingerprints(
                e = entity.copy(internalId = "new", attributes = listOf(attr.copy(description = "new prose"))),
            ) shouldBe
                fingerprints()

            fun withDefault(v: AutoValue) = action.copy(properties = listOf("writer_defaults" to v))
            val one = obj("a" to str("x|,=\n\t\\\""), "b" to str("y"))
            val reordered = obj("b" to str("y"), "a" to str("x|,=\n\t\\\""))
            fingerprints(a = withDefault(one)) shouldBe fingerprints(a = withDefault(reordered))
            fingerprints(a = withDefault(obj("id" to str("1"), "id" to str("2")))) shouldNotBe
                fingerprints(a = withDefault(obj("id" to str("2"))))
            fingerprints(a = withDefault(obj("id" to str("1"), "id" to str("2")))) shouldNotBe
                fingerprints(a = withDefault(obj("id" to str("2"), "id" to str("1"))))
            fingerprints(a = withDefault(str("1"))) shouldNotBe
                fingerprints(a = withDefault(AutoValue.Num(1.0, source, "1")))
            // Double is not the source of truth for authored numeric literals.
            fingerprints(a = withDefault(AutoValue.Num(9007199254740992.0, source, "9007199254740992"))) shouldNotBe
                fingerprints(a = withDefault(AutoValue.Num(9007199254740992.0, source, "9007199254740993")))
            fingerprints(a = withDefault(AutoValue.Null(source))) shouldNotBe fingerprints(a = withDefault(str("null")))
        }
    })
