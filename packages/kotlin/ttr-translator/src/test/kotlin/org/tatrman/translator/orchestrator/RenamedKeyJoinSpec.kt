// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType

/**
 * An ER join whose keys are RENAMED between the ER and DB layers, run through the two-pass pipeline a
 * query service drives — TransDSL → ER plan, then REL_NODE → target DB, then unparse. This is exactly
 * the shape that failed on a live estate:
 *
 *     field [d_date_sk] not found; input fields are: [sk, cal_date, year, month, …]
 *
 * Three facts combined to produce it, and the fixture carries all three rather than a tidier stand-in:
 *
 *  - **the relation has no attribute join pairs** — it is bound to its FK only, and the model serves no
 *    relation at all — so the logical Joiner cannot condition the join and the physical Joiner fills it
 *    from the FK after MAP_TO_PHYSICAL;
 *  - **every key is renamed** (`date_dim.sk` ↔ `d_date_sk`, `catalog_sales.sold_date` ↔
 *    `cs_sold_date_sk`), so MAP_TO_PHYSICAL aliases each scan's columns back to attribute names and
 *    only those names exist above the scan;
 *  - **the date bound arrives as a `datetime_value` literal**, ISO-8601 as plan.proto defines it.
 *
 * The TransDSL below is the payload the live agent sent, byte for byte apart from whitespace.
 */
class RenamedKeyJoinSpec :
    StringSpec({

        fun db(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        fun er(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName(name)
                .build()

        val model =
            InMemoryModelHandle(
                tables =
                    listOf(
                        ModelTable(
                            qname = db("date_dim"),
                            columns =
                                listOf(
                                    ModelColumn("d_date_sk", SurfaceType.INT, nullable = false),
                                    ModelColumn("d_date", SurfaceType.DATETIME, nullable = true),
                                    ModelColumn("d_moy", SurfaceType.INT, nullable = true),
                                ),
                            primaryKey = listOf("d_date_sk"),
                        ),
                        ModelTable(
                            qname = db("catalog_sales"),
                            columns =
                                listOf(
                                    ModelColumn("cs_sold_date_sk", SurfaceType.INT, nullable = true),
                                    ModelColumn("cs_ext_sales_price", SurfaceType.FLOAT, nullable = true),
                                ),
                            primaryKey = listOf("cs_sold_date_sk"),
                        ),
                    ),
                foreignKeys =
                    listOf(
                        ModelForeignKey(
                            from = listOf(db("catalog_sales.cs_sold_date_sk")),
                            to = listOf(db("date_dim.d_date_sk")),
                        ),
                    ),
                entities =
                    listOf(
                        ModelEntity(
                            qname = er("date_dim"),
                            attributes =
                                listOf(
                                    ModelAttribute("sk", SurfaceType.INT, isKey = true, nullable = false),
                                    ModelAttribute("cal_date", SurfaceType.DATETIME),
                                    ModelAttribute("month", SurfaceType.INT),
                                ),
                        ),
                        ModelEntity(
                            qname = er("catalog_sales"),
                            attributes =
                                listOf(
                                    ModelAttribute("sold_date", SurfaceType.INT),
                                    ModelAttribute("ext_sales_price", SurfaceType.FLOAT),
                                ),
                        ),
                    ),
                entityMappings =
                    mapOf(
                        er("date_dim") to EntityMapping.ToTable(db("date_dim")),
                        er("catalog_sales") to EntityMapping.ToTable(db("catalog_sales")),
                    ),
                attributeRenames =
                    mapOf(
                        er("date_dim") to mapOf("sk" to "d_date_sk", "cal_date" to "d_date", "month" to "d_moy"),
                        er("catalog_sales") to
                            mapOf("sold_date" to "cs_sold_date_sk", "ext_sales_price" to "cs_ext_sales_price"),
                    ),
            )

        /** Pass 1 (TransDSL → ER), pass 2 (REL_NODE bytes → DB), then unparse — the query service's order. */
        fun twoPassToPostgres(): UnparseResult {
            val translator = Translator(model)
            val erPass =
                translator
                    .parseToRelNode(source = AGENT_TRANSDSL, sourceLanguage = Language.TRANSFORMATION_DSL)
                    .shouldBeInstanceOf<ParseResult.Success>()
            val dbPass =
                translator
                    .parseToRelNode(
                        source = String(erPass.plan.toByteArray(), Charsets.ISO_8859_1),
                        sourceLanguage = Language.REL_NODE,
                        targetSchema = SchemaCode.DB,
                    ).shouldBeInstanceOf<ParseResult.Success>()
            return translator.unparseFromRelNode(dbPass.plan, Language.SQL, SqlDialectProto.POSTGRESQL)
        }

        "an FK-bound join over renamed keys unparses — the live `field [d_date_sk] not found`" {
            val sql = twoPassToPostgres().shouldBeInstanceOf<UnparseResult.Success>().output

            // The join is on the FK's columns, reached through the aliases each scan exposes — read off
            // the unparsed SQL, not inferred:
            //   (SELECT "d_date_sk" AS "sk", …) AS "t" INNER JOIN (SELECT "cs_sold_date_sk" AS "sold_date", …) AS "t0"
            //   ON "t"."sk" = "t0"."sold_date"
            sql shouldContain "\"d_date_sk\" AS \"sk\""
            sql shouldContain "\"cs_sold_date_sk\" AS \"sold_date\""
            sql shouldContain "INNER JOIN"
            sql shouldContain "ON \"t\".\"sk\" = \"t0\".\"sold_date\""
            // A condition the unparser could not place would surface as a Cartesian product, which
            // returns a confident wrong number instead of an error — worse than the failure it replaces.
            sql shouldNotContainIgnoringCase "cross join"
        }

        "the question's ISO-8601 date bound unparses as a TIMESTAMP, not as a string compared to a date" {
            val sql = twoPassToPostgres().shouldBeInstanceOf<UnparseResult.Success>().output

            sql shouldContainIgnoringCase "TIMESTAMP '2025-01-01 00:00:00'"
            sql shouldContainIgnoringCase "TIMESTAMP '2026-01-01 00:00:00'"
            sql shouldNotContain "2025-01-01T00:00:00Z"
        }
    })

/** The TransDSL the live agent sent for "revenue by month in 2025", as captured from the query service's log. */
private const val AGENT_TRANSDSL =
    """{"core":[{"dataObject":{"schemaCode":"ER","namespace":"entity","name":"date_dim"},"alias":"date_dim"},""" +
        """{"dataObject":{"schemaCode":"ER","namespace":"entity","name":"catalog_sales"},"alias":"catalog_sales"}],""" +
        """"columns":[{"source":"date_dim","name":"month","alias":"month"}],""" +
        """"filter":{"function":{"operation":"and","operands":[""" +
        """{"function":{"operation":"ge","operands":[{"columnRef":{"sourceAlias":"date_dim","name":"cal_date"}},""" +
        """{"literal":{"datetimeValue":"2025-01-01T00:00:00Z","type":"datetime"},"resultType":"datetime"}]},""" +
        """"resultType":"bool"},""" +
        """{"function":{"operation":"lt","operands":[{"columnRef":{"sourceAlias":"date_dim","name":"cal_date"}},""" +
        """{"literal":{"datetimeValue":"2026-01-01T00:00:00Z","type":"datetime"},"resultType":"datetime"}]},""" +
        """"resultType":"bool"}]},"resultType":"bool"}}"""
