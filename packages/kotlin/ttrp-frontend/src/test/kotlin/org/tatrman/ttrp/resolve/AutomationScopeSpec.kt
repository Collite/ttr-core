// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode

class AutomationScopeSpec :
    StringSpec({
        val qname = QualifiedName(SchemaCode.ER, "entity", "order")
        val attr = Attribute("a", QualifiedName(SchemaCode.ER, "attribute", "order.id"), entity = qname, type = "int")
        val entity = Entity("e", qname, attributes = listOf(attr))
        val action =
            AutomationAction("a", QualifiedName(SchemaCode.AUTOMATION, "action", "release", "hartland.automation"))

        fun scope(
            entities: List<Entity> = listOf(entity),
            actions: List<AutomationAction> = listOf(action),
            stdlib: List<Entity> = emptyList(),
        ) = AutomationScope(emptyList(), actions, entities, stdlib) { _, prefix -> prefix == "hartland" }

        "bare package and canonical target refs resolve the same entity and attribute" {
            val expected = TargetResolution.Resolved(entity, attr)
            for (ref in listOf("order.id", "hartland.order.id", "er.entity.order.id")) {
                scope().target(ref) shouldBe
                    expected
            }
            for (ref in listOf("other.order.id", "order.missing", "hartland.order", "id", "order..id")) {
                scope().target(ref) shouldBe TargetResolution.Unresolved(ref)
            }
        }

        "ambiguous entities attributes and actions are distinct from unresolved names" {
            val other = entity.copy(qname = qname.copy(`package` = "other"))
            scope(entities = listOf(entity, other)).target("order.id") shouldBe TargetResolution.Ambiguous("order.id")
            scope(entities = listOf(entity.copy(attributes = listOf(attr, attr.copy(internalId = "second")))))
                .target("order.id") shouldBe TargetResolution.Ambiguous("order.id")
            scope(actions = listOf(action, action.copy(qname = action.qname.copy(`package` = "other"))))
                .action("release") shouldBe ActionResolution.Ambiguous("release")
            scope().action("missing") shouldBe ActionResolution.Unresolved("missing")
            scope().action("release") shouldBe ActionResolution.Resolved(action)
        }

        "imported entities take precedence over the stdlib and only absent imported names fall back" {
            val standard = entity.copy(internalId = "stdlib", attributes = listOf(attr.copy(type = "text")))
            scope(stdlib = listOf(standard)).target("order.id") shouldBe TargetResolution.Resolved(entity, attr)
            scope(entities = emptyList(), stdlib = listOf(standard)).target("order.id") shouldBe
                TargetResolution.Resolved(standard, standard.attributes.single())
        }
    })
