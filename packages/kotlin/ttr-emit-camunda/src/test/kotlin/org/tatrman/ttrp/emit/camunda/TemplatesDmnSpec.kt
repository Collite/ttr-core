// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*

/** S1.5.1: export-facing specifications over a fresh H-1 projection. */
class TemplatesDmnSpec : StringSpec({
    val world = WorldChoices(
        programs = listOf(ProgramChoice("release_on_placement", "release_order", "key", listOf(ParameterMeta("tolerance", "decimal", "0.01")))),
        queries = listOf(QueryMeta("er.query.order_totals", listOf(ParameterMeta("limit", "decimal", null)), listOf(ColumnMeta("total", "decimal"), ColumnMeta("approved", "boolean")))),
    )
    fun result(target: String) = (if (target == "camunda_7") Camunda7EmitPlugin() else Camunda8EmitPlugin()).emitRecipe(
        RecipeEmitRequest(releaseGraph(target), """{"world":"hartland.worlds.demo"}""", "", "{}", world),
    )
    for ((target, dialect, input, output) in listOf(
        listOf("camunda_7", "c7", "camunda:inputParameter", "camunda:outputParameter"),
        listOf("camunda_8", "c8", "zeebe:input", "zeebe:output"),
    )) {
        "dialect $dialect uses the world's choices and named parameter/output bindings" {
            val files = result(target).files
            val template = Json.parseToJsonElement(files.getValue("templates/$dialect/release_on_placement.json").decodeToString()).jsonObject
            template.getValue("id") shouldBe JsonPrimitive("tatrman.hartland.worlds.demo.release_on_placement")
            val properties = template.getValue("properties").jsonArray.map { it.jsonObject }
            properties.flatMap { it["choices"]?.jsonArray.orEmpty() }.map { it.jsonObject.getValue("value").jsonPrimitive.content }.toSet() shouldBe
                setOf("release_on_placement", "er.query.order_totals")
            properties.any { it.getValue("binding").jsonObject.let { b -> b["type"] == JsonPrimitive(input) && (b["name"] ?: b["target"]) == JsonPrimitive("tolerance") } } shouldBe true
            val query = files.getValue("templates/$dialect/er.query.order_totals.json").decodeToString()
            query shouldContain output
            query shouldContain "total"
            query shouldContain "row_count"
        }
        "dialect $dialect emits a typed empty FIRST DMN for each declared query" {
            val dmn = result(target).files.getValue("dmn/er.query.order_totals_decision.dmn").decodeToString()
            dmn shouldContain "https://www.omg.org/spec/DMN/20191111/MODEL/"
            dmn shouldContain "id=\"er.query.order_totals_decision\""
            dmn shouldContain "hitPolicy=\"FIRST\""
            dmn shouldContain "typeRef=\"number\""
            dmn shouldContain "typeRef=\"boolean\""
            dmn shouldContain "row_count"
            dmn.contains("<rule") shouldBe false
        }
        "dialect $dialect exports deterministically and retains the H-1 BPMN" {
            val first = result(target).files
            val second = result(target).files
            first.keys shouldBe second.keys
            first.forEach { (path, bytes) -> bytes.contentEquals(second.getValue(path)) shouldBe true }
            validateBpmn(first.getValue("release_order.$dialect.bpmn").decodeToString())
        }
    }
})
