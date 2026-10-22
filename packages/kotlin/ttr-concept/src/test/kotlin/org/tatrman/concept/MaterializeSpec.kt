// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.concept.cli.runTtr
import org.tatrman.concept.cli.ttr
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * CN-P3 S3.4 T6 — `materialize` and `ttr cn materialize`: the text a phase materializes to parses and reconciles in its
 * model (the logical query and the entity stacked on it, bound to it); the CLI prints it or writes it under `--out`, and
 * refuses a parametric definition with exit 1.
 */
class MaterializeSpec :
    FunSpec({
        val world = GoldenWorlds["hartland"]
        val golden = GoldenCases.root.resolve("materialize/placed-order/expected/placed_order.ttrm")

        test("the materialized text parses and reconciles: the query, the entity bound to it, the key") {
            val text =
                CnConceptService(
                    world.cn,
                ).materialize(ConceptRef.newBuilder().setQname("cn.PlacedOrder").build(), "postgresql")
            val dir = Files.createTempDirectory("cn-materialize")
            Files
                .walk(world.dir)
                .use { s ->
                    s.filter { it.isRegularFile() && it.extension == "ttrm" }.toList()
                }.forEach {
                    Files.copy(it, dir.resolve(it.fileName.toString()))
                }
            for ((path, content) in text.files) Files.writeString(dir.resolve(path), content)
            val (model, errors) =
                org.tatrman.concept.cli
                    .load(dir, "round-trip")
            errors.shouldBeEmpty()
            val query = model.queries.values.single { it.qname.name == "placed_order_q" }
            query.sourceText.trim() shouldBe
                ErRenderer(world.cn).render(world.cn.cn.byName("PlacedOrder")!!, RenderOptions()).text
            val entity = (model.schemas["er"] as ErSchema).entities.values.single { it.qname.name == "placed_order" }
            entity.attributes.filter { it.isKey }.map { it.qname.name.substringAfterLast('.') } shouldBe
                listOf("order_number")
            model.mappings
                .filterIsInstance<Er2DbEntityMapping>()
                .single { it.entity.name == "placed_order" }
                .target
                .shouldBeInstanceOf<MappingTarget.SqlQuery>()
                .qname.name shouldContain "placed_order_q"
        }

        test("ttr cn materialize prints the text; --out writes it; a parametric phase is refused, exit 1") {
            val printed = ttr().test("cn materialize ${world.dir} cn.PlacedOrder")
            printed.statusCode shouldBe 0
            printed.stdout shouldBe golden.readText()
            val out = Files.createTempDirectory("cn-out")
            ttr().test("cn materialize ${world.dir} PlacedOrder --out $out").statusCode shouldBe 0
            out.resolve("placed_order.ttrm").readText() shouldBe golden.readText()
            val refused = ttr().test("cn materialize ${world.dir} cn.MispricedLine")
            refused.statusCode shouldBe 1
            refused.stderr shouldContain "cn/materialize-parametric"
            runTtr(listOf("cn", "materialize", "${world.dir}")) shouldBe 2 // usage: the phase is missing
        }
    })
