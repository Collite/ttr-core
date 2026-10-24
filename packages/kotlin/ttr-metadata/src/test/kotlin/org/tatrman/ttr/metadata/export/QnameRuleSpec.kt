// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

internal fun xmSet(): DefinitionSet =
    loadXmWorld().let {
        DefinitionSet.from(it.files, it.model, "0.18", "fixture-1")
    }

class QnameRuleSpec :
    StringSpec({
        "db and er entries agree with the typed erp-project object keys" {
            val loaded =
                MetadataLoader(
                    FileBasedSource(
                        "erp",
                        100,
                        LocalFsStorage("erp", Path.of("src/testFixtures/resources/fixtures/erp-project/models")),
                    ),
                ).load()
            val set = DefinitionSet.from(loaded.files, loaded.model, "0.18", "erp")
            val keys =
                loaded.model!!
                    .objectByQname()
                    .values
                    .filter {
                        it.kind in
                            setOf("table", "column", "view", "fk", "entity", "attribute")
                    }.map { it.qname.dotted() }
                    .toSet()
            val ids =
                set.entries
                    .filter { it.model in setOf("db", "er") && it.kind != "relation" }
                    .map { it.qname }
                    .toSet()
            (ids - keys) shouldBe emptySet()
            (keys - ids) shouldBe emptySet()
            // Typed relation keys inherit the file namespace (including explicit custom schemas).
            // External identities retain C-3's kinded relation spelling; no typed-model migration here.
            set.entries.single { it.kind == "relation" }.qname shouldBe "er.relation.customer_sales"
            loaded.model!!
                .objectByQname()
                .values
                .single { it.kind == "relation" }
                .qname
                .dotted() shouldBe
                "er.entity.customer_sales"
        }
        "MD and CN identities use explicit kind segments" {
            xmSet()
                .entries
                .filter { it.model in setOf("md", "cn") }
                .map { it.qname }
                .toSet() shouldBe
                setOf(
                    "md.domain.Money",
                    "md.dimension.Buyer",
                    "md.dimension.Buyer.id",
                    "md.dimension.Calendar",
                    "md.dimension.Calendar.day",
                    "md.dimension.Calendar.month",
                    "md.hierarchy.CalendarRollup",
                    "md.measure.revenue",
                    "md.measure.units",
                    "md.measure.balance",
                    "md.cubelet.purchases",
                    "cn.kind.Party",
                    "cn.kind.Prospect",
                    "cn.role.Customer",
                    "cn.event.Purchase",
                    "cn.phase.LargePurchase",
                    "cn.quality.sold_at",
                    "cn.relation_type.recommends",
                )
        }
    })
