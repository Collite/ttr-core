// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.model.ProcedureDef
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.PropertyValue

class CanonicalCarriersSpec :
    StringSpec({
        "preserves true, false, empty defaults and ordered bindings" {
            val parsed =
                TtrLoader.parseString(
                    """
                    model db
                    def query raw { skipSecurity: true, parameters: [
                      { name: p, type: text, defaultValue: "", binds: [er.Customer.name, er.Customer.code] }
                    ] }
                    def query checked { skipSecurity: false }
                    def query ordinary { }
                    """.trimIndent(),
                    "carriers.ttrm",
                )
            parsed.errors shouldBe emptyList()
            val raw = parsed.definitions[0] as QueryDef
            raw.skipSecurity shouldBe true
            val parameter = (raw.parameters.single() as PropertyValue.ObjectValue).entries
            (parameter.getValue("defaultValue") as PropertyValue.StringValue).raw shouldBe ""
            (
                parameter.getValue(
                    "binds",
                ) as PropertyValue.ListValue
            ).items.map { (it as PropertyValue.IdValue).ref.path } shouldBe
                listOf("er.Customer.name", "er.Customer.code")
            (parsed.definitions[1] as QueryDef).skipSecurity shouldBe false
            (parsed.definitions[2] as QueryDef).skipSecurity shouldBe null
        }
        "keeps lexical policy and match-text pairs" {
            val parsed =
                TtrLoader.parseString(
                    """
                    model lexicon locale en
                    def pattern first { for: db.query.raw, match: "name (.*)", text: "Name Alice?", indexSecurity: applied }
                    def pattern second { for: db.query.raw, match: "lookup (.*)", text: "Lookup Bob?", indexSecurity: none }
                    """.trimIndent(),
                    "lex.ttrm",
                )
            parsed.errors shouldBe emptyList()
            val first = parsed.definitions[0] as LexiconEntryDef
            first.indexSecurity shouldBe "applied"
            first.text shouldBe "Name Alice?"
            (parsed.definitions[1] as LexiconEntryDef).indexSecurity shouldBe "none"
        }
        "property keywords remain identifiers" {
            TtrLoader
                .parseString(
                    "model er def entity skipSecurity { attributes: [def attribute defaultValue { type: text }, def attribute binds { type: text }] } def entity indexSecurity {}",
                    "ids.ttrm",
                ).errors shouldBe
                emptyList()
        }
        "procedure parameters retain defaults and bindings" {
            val parsed =
                TtrLoader.parseString(
                    "model db def procedure call { parameters: [{name: p, type: text, direction: IN, defaultValue: \"seed\", binds: [er.Customer.name]}] }",
                    "procedure.ttrm",
                )
            parsed.errors shouldBe emptyList()
            val p =
                ((parsed.definitions.single() as ProcedureDef).parameters.single() as PropertyValue.ObjectValue)
                    .entries
            (p.getValue("defaultValue") as PropertyValue.StringValue).raw shouldBe "seed"
            val binds = p.getValue("binds") as PropertyValue.ListValue
            (binds.items.single() as PropertyValue.IdValue).ref.path shouldBe "er.Customer.name"
        }
        listOf(
            "def query q { skipSecurity: \"true\" }",
            "def query q { parameters: [{ name: p, defaultValue: true }] }",
            "def query q { parameters: [{ name: p, defaultValue: null }] }",
            "def query q { parameters: [{ name: p, binds: [\"er.Customer.name\"] }] }",
            "def term t { indexSecurity: true }",
        ).forEach { source ->
            "rejects wrong typed input $source" {
                TtrLoader.parseString(source, "invalid.ttrm").errors.shouldNotBeEmpty()
            }
        }

        listOf("binds", "defaultValue", "label").forEach { key ->
            "incomplete parameter $key keeps the sibling query" {
                val result =
                    TtrLoader.parseString(
                        "model db def query q { parameters: [{name: p, $key }] } def query r {}",
                        "typing.ttrm",
                    )
                result.errors.shouldNotBeEmpty()
                val lexer =
                    org.tatrman.ttr.parser.generated.TTRLexer(
                        org.antlr.v4.runtime.CharStreams.fromString(
                            "model db def query q { parameters: [{name: p, $key }] } def query r {}",
                        ),
                    )
                val parser =
                    org.tatrman.ttr.parser.generated
                        .TTRParser(
                            org.antlr.v4.runtime
                                .CommonTokenStream(lexer),
                        )
                parser.removeErrorListeners()
                val walked =
                    org.tatrman.ttr.parser.walker
                        .TtrWalker("typing.ttrm")
                        .visitDocument(parser.document())
                walked.definitions.map { it.name } shouldBe listOf("q", "r")
                val q = walked.definitions[0] as QueryDef
                (q.parameters[0] as PropertyValue.ObjectValue).entries.containsKey(key) shouldBe false
            }
        }
        "new soft keywords remain ordered cn keys" {
            val result =
                TtrLoader.parseString(
                    "model cn def kind Item { binds: x, defaultValue: y, skipSecurity: z, indexSecurity: w }",
                    "keys.ttrm",
                )
            result.errors shouldBe emptyList()
            val concept = result.definitions.single() as org.tatrman.ttr.parser.model.ConceptDef
            concept.body.properties.map { it.key } shouldBe
                listOf("binds", "defaultValue", "skipSecurity", "indexSecurity")
        }

        "decoded expression newlines map to physical source columns" {
            val source = """model cn def quality P { of: X, definition: "a\n😀\tb\r\"c\"" }"""
            val parsed = TtrLoader.parseString(source, "escapes.ttrm")
            parsed.errors shouldBe emptyList()
            val concept = parsed.definitions.single() as org.tatrman.ttr.parser.model.ConceptDef
            val block =
                (
                    concept.body.properties.first {
                        it.key == "definition"
                    } as org.tatrman.ttr.parser.model.ConceptProperty.DefinitionBlock
                ).block
            block.text shouldBe "a\n😀\tb\r\"c\""
            val location = block.location(1, 2)
            val offset = source.codePointCount(0, source.indexOf("b", source.indexOf("😀")))
            Triple(location.offsetStart, location.line, location.column) shouldBe Triple(offset, 1, offset)
            block.textSource.endLine shouldBe 1
            block.textSource.endColumn shouldBe source.codePointCount(0, source.lastIndexOf('"'))
        }
        "layout scanner uses the same string decoding as the generated parser" {
            val scanned = TtrgGraphReader.scan("""graph a { model: er, objects: ["C:\\new\t\d"] }""")
            scanned.errors shouldBe emptyList()
            scanned.graph!!.objects shouldBe listOf("C:\\new\t\\d")
        }
    })
