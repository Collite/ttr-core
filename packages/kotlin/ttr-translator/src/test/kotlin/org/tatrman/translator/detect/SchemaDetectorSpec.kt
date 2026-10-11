// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.detect

import org.tatrman.translate.v1.Language
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType

class SchemaDetectorSpec :
    StringSpec({

        val dbNamespace = "dbo"
        val erNamespace = "entity"

        val itemCategoryQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace(dbNamespace)
                .setName("item_category")
                .build()
        val itemQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace(dbNamespace)
                .setName("item")
                .build()
        val productQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(erNamespace)
                .setName("product")
                .build()
        val brandQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(erNamespace)
                .setName("brand")
                .build()

        val itemCategoryTable =
            ModelTable(
                qname = itemCategoryQname,
                columns =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelColumn("id", SurfaceType.INT),
                        org.tatrman.translator.framework
                            .ModelColumn("name", SurfaceType.TEXT),
                    ),
            )
        val itemTable =
            ModelTable(
                qname = itemQname,
                columns =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelColumn("id", SurfaceType.INT),
                    ),
            )
        val productEntity =
            ModelEntity(
                qname = productQname,
                attributes =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelAttribute("id", SurfaceType.INT),
                        org.tatrman.translator.framework
                            .ModelAttribute("name", SurfaceType.TEXT),
                    ),
            )
        val brandEntity =
            ModelEntity(
                qname = brandQname,
                attributes =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelAttribute("id", SurfaceType.INT),
                    ),
            )

        fun makeHandle(
            tables: List<ModelTable> = listOf(itemCategoryTable, itemTable),
            entities: List<ModelEntity> = listOf(productEntity, brandEntity),
        ): InMemoryModelHandle =
            InMemoryModelHandle(
                tables = tables,
                entities = entities,
            )

        "AUTODETECTED DB when stated=UNSPECIFIED and SQL uses DB table" {
            val sql = "SELECT i_category_id, i_category_code FROM ITEM_CATEGORY WHERE name LIKE 'E%'"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.AUTODETECTED
            result.effectiveSchema shouldBe SchemaCode.DB
        }

        // CalciteExtParser (CEP-P0, the "Fix B" closer): a query using the T-SQL `COLLATE` extension
        // must still detect its catalog. The detector shares the framework's extended parser factory,
        // so `COLLATE` parses here just as it does in the validator. Before the ext parser the stock
        // parser threw on `COLLATE`, detection returned NOT_APPLICABLE, and the caller silently fell
        // back to the wrong catalog.
        "AUTODETECTED DB when SQL uses a DB table with a T-SQL COLLATE clause" {
            val sql = "SELECT i_category_id FROM ITEM_CATEGORY WHERE name COLLATE Latin1_General_CI_AI LIKE 'E%'"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.AUTODETECTED
            result.effectiveSchema shouldBe SchemaCode.DB
        }

        "AUTODETECTED ER when SQL uses ER entity" {
            val sql = "SELECT id, name FROM product"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.AUTODETECTED
            result.effectiveSchema shouldBe SchemaCode.ER
        }

        "CONFIRMED when stated matches" {
            val sql = "SELECT id FROM ITEM_CATEGORY"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.DB, makeHandle())
            result.decision shouldBe SchemaDecision.CONFIRMED
            result.effectiveSchema shouldBe SchemaCode.DB
        }

        "CORRECTED when stated=ER but SQL is physical DB" {
            val sql = "SELECT id FROM ITEM_CATEGORY"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.ER, makeHandle())
            result.decision shouldBe SchemaDecision.CORRECTED
            result.effectiveSchema shouldBe SchemaCode.DB
        }

        "UNKNOWN with suggestions for unknown table" {
            val sql = "SELECT id FROM ITEM_CATEGORYX"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.UNKNOWN
            result.effectiveSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
            result.unknownTables shouldContainExactlyInAnyOrder listOf("item_categoryx")
            result.suggestions.firstOrNull()?.candidates shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "MIXED when one DB and one ER table in one query" {
            val sql = "SELECT * FROM ITEM_CATEGORY, product"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.MIXED
            result.effectiveSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
        }

        "AMBIGUOUS when table exists in both schemas and no stated" {
            val bothTableQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace(dbNamespace)
                    .setName("dual_name")
                    .build()
            val bothEntityQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace(erNamespace)
                    .setName("dual_name")
                    .build()
            val bothTable = ModelTable(qname = bothTableQname, columns = listOf())
            val bothEntity = ModelEntity(qname = bothEntityQname, attributes = listOf())

            val handle =
                makeHandle(
                    tables = listOf(itemCategoryTable, bothTable),
                    entities = listOf(productEntity, bothEntity),
                )
            val sql = "SELECT id FROM dual_name"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, handle)
            result.decision shouldBe SchemaDecision.AMBIGUOUS
            result.effectiveSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
        }

        "CONFIRMED when table exists in both schemas but stated=DB" {
            val bothTableQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace(dbNamespace)
                    .setName("dual_name")
                    .build()
            val bothEntityQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace(erNamespace)
                    .setName("dual_name")
                    .build()
            val bothTable = ModelTable(qname = bothTableQname, columns = listOf())
            val bothEntity = ModelEntity(qname = bothEntityQname, attributes = listOf())

            val handle =
                makeHandle(
                    tables = listOf(itemCategoryTable, bothTable),
                    entities = listOf(productEntity, bothEntity),
                )
            val sql = "SELECT id FROM dual_name"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.DB, handle)
            result.decision shouldBe SchemaDecision.CONFIRMED
            result.effectiveSchema shouldBe SchemaCode.DB
        }

        "NOT_APPLICABLE when sourceLanguage=DATAFRAME_DSL" {
            val sql = "SELECT id FROM ITEM_CATEGORY"
            val result =
                SchemaDetector.detect(
                    sql,
                    Language.DATAFRAME_DSL,
                    SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                    makeHandle(),
                )
            result.decision shouldBe SchemaDecision.NOT_APPLICABLE
            result.effectiveSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
        }

        "NOT_APPLICABLE when SQL parse fails" {
            val sql = "SELECT FROM WHERE"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.NOT_APPLICABLE
        }

        "NOT_APPLICABLE when zero table refs" {
            val sql = "SELECT 1 + 2"
            val result = SchemaDetector.detect(sql, Language.SQL, SchemaCode.SCHEMA_CODE_UNSPECIFIED, makeHandle())
            result.decision shouldBe SchemaDecision.NOT_APPLICABLE
        }
    })
