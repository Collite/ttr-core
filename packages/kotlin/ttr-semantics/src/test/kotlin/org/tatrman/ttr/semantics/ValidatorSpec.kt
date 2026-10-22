// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.diagnostics.DiagnosticSeverity
import org.tatrman.ttr.parser.loader.TtrLoader

/**
 * Mirrors `packages/semantics/src/__tests__/validator.test.ts`.
 */
class ValidatorSpec :
    StringSpec({

        fun setup(
            uri: String,
            src: String,
            strict: Boolean = false,
        ): Pair<Validator, SemanticDocument> {
            val r = TtrLoader.parseString(src, uri)
            val schemaCode = r.modelDirective?.modelCode ?: "db"
            val namespace = r.modelDirective?.schema ?: ""
            val packageName = r.packageName ?: ""
            val symbols = SymbolTable()
            symbols.upsertDocument(uri, r.definitions, schemaCode, namespace, packageName)
            val validator =
                Validator(symbols, Resolver(symbols), ResolvedManifest(lint = ManifestLint(strict = strict)))
            val doc = SemanticDocument(uri, r.definitions, schemaCode, namespace, packageName, r.imports)
            return validator to doc
        }

        "RequiredPropertyMissing on entity with no attributes" {
            val (v, doc) =
                setup("er.ttr", "model er schema entity\ndef entity empty { description: \"no attrs\" }")
            v.validateDocument(doc).any { it.code == DiagnosticCode.RequiredPropertyMissing } shouldBe true
        }

        "EntityAttributeNotFound when nameAttribute points at a missing attr" {
            val (v, doc) =
                setup(
                    "er.ttr",
                    "model er schema entity\ndef entity artikl { attributes: [def attribute id { type: int }] nameAttribute: ghost }",
                )
            v.validateDocument(doc).any { it.code == DiagnosticCode.EntityAttributeNotFound } shouldBe true
        }

        "PrimaryKeyColumnNotFound when pk column does not exist" {
            val (v, doc) =
                setup(
                    "db.ttr",
                    "model db schema dbo\ndef table orders { columns: [def column id { type: int }] primaryKey: [\"bogus\"] }",
                )
            v.validateDocument(doc).any { it.code == DiagnosticCode.PrimaryKeyColumnNotFound } shouldBe true
        }

        "no diagnostics for a well-formed entity" {
            val (v, doc) =
                setup(
                    "er.ttr",
                    "model er schema entity\ndef entity artikl { attributes: [def attribute id { type: int }] }",
                )
            v.validateDocument(doc) shouldHaveSize 0
        }

        "UnresolvedReference is a warning by default" {
            val (v, doc) =
                setup(
                    "er.ttr",
                    """
                    model er schema entity
                    def entity artikl { attributes: [def attribute id { type: int }] }
                    def er2cnc_role x { entity: er.entity.nope role: fact }
                    """.trimIndent(),
                )
            val bad = v.validateReferences(doc).first { it.code == DiagnosticCode.UnresolvedReference }
            bad.severity shouldBe DiagnosticSeverity.Warning
        }

        "UnresolvedReference becomes an error under lint.strict" {
            val (v, doc) =
                setup(
                    "er.ttr",
                    """
                    model er schema entity
                    def entity artikl { attributes: [def attribute id { type: int }] }
                    def er2cnc_role x { entity: er.entity.nope role: fact }
                    """.trimIndent(),
                    strict = true,
                )
            val bad = v.validateReferences(doc).first { it.code == DiagnosticCode.UnresolvedReference }
            bad.severity shouldBe DiagnosticSeverity.Error
        }

        "DuplicateDefinition when the same qname appears in two documents" {
            val src =
                "model er schema entity\ndef entity twin { attributes: [def attribute id { type: int }] }"
            val symbols = SymbolTable()
            Fixtures.upsert(symbols, "a.ttr", src)
            Fixtures.upsert(symbols, "b.ttr", src)
            val v = Validator(symbols, Resolver(symbols), ResolvedManifest())
            // The entity AND its duplicated `id` attribute each yield 2 diagnostics (TS asserts >= 2).
            v.validateProject().count { it.code == DiagnosticCode.DuplicateDefinition } shouldBeGreaterThanOrEqual 2
        }

        "shared duplicate rule exempts inline binding pairs" {
            val symbols = SymbolTable()
            val text = "model binding\ndef er2db_entity item { entity: er.entity.item, target: { table: db.dbo.item } }"
            Fixtures.upsert(symbols, "a.ttr", text)
            Fixtures.upsert(symbols, "b.ttr", text)
            val duplicate = symbols.duplicates().single()
            duplicateDefinitions(listOf(duplicate)).size shouldBe 2
            val inlinePair =
                duplicate.copy(
                    entries =
                        duplicate.entries.mapIndexed { index, entry ->
                            if (index == 0) entry.copy(mappingSource = MappingSource.Inline) else entry
                        },
                )
            duplicateDefinitions(listOf(inlinePair)) shouldBe emptyList()
        }
        "FuzzyWithoutSearchable warning when fuzzy:true but searchable absent" {
            val (v, doc) =
                setup(
                    "test.ttr",
                    "def entity E { attributes: [def attribute A { type: text, search { fuzzy: true } }] }",
                )
            v.validateDocument(doc).any {
                it.code == DiagnosticCode.FuzzyWithoutSearchable && it.severity == DiagnosticSeverity.Warning
            } shouldBe true
        }

        "no FuzzyWithoutSearchable when searchable:true and fuzzy:true" {
            val (v, doc) =
                setup("test.ttr", "def entity E { search { searchable: true, fuzzy: true } }")
            v.validateDocument(doc).any { it.code == DiagnosticCode.FuzzyWithoutSearchable } shouldBe false
        }
    })
