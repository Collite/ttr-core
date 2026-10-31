// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.StorageFile
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.writer.TtrRenderer
import java.nio.file.Path

class CanonicalCarriersSpec : StringSpec({
    class MemoryStorage(private val text: String) : ModelStorage {
        override val id = "carriers"
        override fun fetchVersion() = "v1"
        override fun listFiles(extensions: List<String>, prefixes: List<String>) =
            listOf(StorageFile("db.ttrm", 0L, Path.of("/")))
        override fun read(file: StorageFile) = text
    }
    fun load(text: String) = ModelReconciler(ModelDescriptor("carriers", "carriers"))
        .reconcile(listOf(FileBasedSource("carriers", 100, MemoryStorage(text)).load()))

    val source = """
        model db schema dbo
        def query raw { skipSecurity: true, parameters: [
            {name: p, type: text, defaultValue: "", binds: [er.entity.Customer.name, er.entity.Customer.code]}
        ], sourceText: "select 1" }
        def query checked { skipSecurity: false }
        def query ordinary { }
    """.trimIndent()

    "typed load and model-to-text roundtrip retain policy and parameter metadata" {
        val loaded = load(source)
        loaded.errors shouldBe emptyList()
        val queries = loaded.model.queries.values.associateBy { it.qname.name }
        queries.getValue("raw").skipSecurity shouldBe true
        queries.getValue("checked").skipSecurity shouldBe false
        queries.getValue("ordinary").skipSecurity shouldBe false
        val raw = queries.getValue("raw")
        raw.parameters.single().defaultValue shouldBe ""
        raw.parameters.single().binds shouldBe listOf("er.entity.Customer.name", "er.entity.Customer.code")
        val rendered = TtrRenderer.renderFile("db", "dbo", listOf(ModelToDefinitions.queryToQueryDef(raw)))
        val again = load(rendered)
        again.errors shouldBe emptyList()
        val roundtrip = again.model.queries.values.single()
        roundtrip.skipSecurity shouldBe true
        roundtrip.parameters shouldBe raw.parameters
    }

    "C-7 render-reparse bytes retain explicit false and empty default" {
        fun set(text: String): DefinitionSet {
            val parsed = TtrLoader.parseString(text, "db.ttrm")
            parsed.errors shouldBe emptyList()
            return DefinitionSet(parsed.definitions.map {
                DefinedObject("db.dbo.${it.name}", "db", "query", it, "demo", "db.ttrm", "db")
            }, emptyMap(), "demo", "0.21", "v1")
        }
        val before = set(source)
        val rendered = TtrRenderer.renderFile("db", "dbo", before.entries.map { it.def })
        val bytes = DefsJsonWriter.write(before)
        DefsJsonWriter.write(set(rendered)).toList() shouldBe bytes.toList()
        val definitions = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("definitions").jsonObject
        val checked = definitions.getValue("db.dbo.checked").jsonObject.getValue("properties").jsonObject
        checked.getValue("skipSecurity").jsonPrimitive.boolean shouldBe false
        val raw = definitions.getValue("db.dbo.raw").jsonObject.getValue("properties").jsonObject
        val parameter = raw.getValue("parameters").jsonArray.single().jsonObject
        parameter.getValue("defaultValue").jsonPrimitive.content shouldBe ""
        parameter.getValue("binds").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("er.entity.Customer.name", "er.entity.Customer.code")
    }

    "C-7 retains lexical index policy and match-text pairing" {
        val parsed = TtrLoader.parseString("model lexicon locale en def pattern paired { for: db.query.raw, match: \"name (.*)\", text: \"Name Alice?\", indexSecurity: none }", "lex.ttrm")
        parsed.errors shouldBe emptyList()
        val entry = DefinedObject("lexicon.pattern.paired", "lexicon", "pattern", parsed.definitions.single(), "demo", "lex.ttrm", "lexicon", "en")
        val set = DefinitionSet(listOf(entry), emptyMap(), "demo", "0.21", "v1")
        val doc = Json.parseToJsonElement(DefsJsonWriter.write(set).decodeToString()).jsonObject
        val props = doc.getValue("definitions").jsonObject.getValue(entry.qname).jsonObject.getValue("properties").jsonObject
        props.getValue("indexSecurity").jsonPrimitive.content shouldBe "none"
        props.getValue("match").jsonPrimitive.content shouldBe "name (.*)"
        props.getValue("text").jsonPrimitive.content shouldBe "Name Alice?"
    }
})
