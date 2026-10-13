// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType

/**
 * ttr-core#148 — a filter that can never hold (`warehouse_name = 'Memphis DC' AND warehouse_name =
 * 'Allentown DC'`) is a valid question whose answer is no rows, or the one all-NULL row of an aggregate
 * over nothing. It must unparse in every dialect, not fail.
 *
 * How the empty relation reaches the unparser: the second pass of the two-half pipeline (REL_NODE
 * re-entry, `target=DB`) rebuilds the plan through `RelBuilder`, whose `filter` folds the contradiction to
 * an empty `Values` with the full row type, and that is what goes on the wire. Decoding it failed with
 * `Value count must be a positive multiple of field count` in every dialect.
 *
 * What it must unparse to: Calcite renders an empty `Values` as `… (VALUES (NULL, …)) … WHERE 1 = 0`. The
 * bare NULLs carry no type, so the engine picks one: PostgreSQL reads them as `text` (`SUM` over the
 * empty relation then fails with `function sum(text) does not exist`), DuckDB as an integer (`SUM` comes
 * back `HUGEINT`), SQL Server as `int`. Each NULL is cast to its column's type, so the result keeps the
 * plan's column names and types.
 */
class EmptyValuesUnparseSpec :
    StringSpec({
        // -- Fixture: a slice of the Hartland demo estate (the TPC-DS US world) ---------------------------

        fun er(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName(name)
                .build()

        fun attr(
            entity: String,
            attribute: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("attribute")
                .setName("$entity.$attribute")
                .build()

        fun db(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        val entities =
            listOf(
                ModelEntity(
                    er("catalog_sales"),
                    listOf(
                        ModelAttribute("warehouse_id", SurfaceType.INT),
                        ModelAttribute("sold_date_id", SurfaceType.INT),
                        ModelAttribute("channel", SurfaceType.TEXT),
                        ModelAttribute("ext_sales_price", SurfaceType.FLOAT),
                    ),
                ),
                ModelEntity(
                    er("warehouse"),
                    listOf(
                        ModelAttribute("warehouse_id", SurfaceType.INT, nullable = false, isKey = true),
                        ModelAttribute("warehouse_name", SurfaceType.TEXT),
                    ),
                ),
                ModelEntity(
                    er("date_dim"),
                    listOf(
                        ModelAttribute("date_id", SurfaceType.INT, nullable = false, isKey = true),
                        ModelAttribute("cal_date", SurfaceType.DATETIME),
                        ModelAttribute("month", SurfaceType.TEXT),
                    ),
                ),
            )

        val translator =
            Translator(
                InMemoryModelHandle(
                    tables =
                        entities.map { e ->
                            ModelTable(
                                db(e.qname.name),
                                e.attributes.map { ModelColumn(it.name, it.surfaceType, it.nullable) },
                            )
                        },
                    entities = entities,
                    relations =
                        listOf(
                            ModelRelation(
                                er("catalog_sales"),
                                er("warehouse"),
                                listOf(attr("catalog_sales", "warehouse_id") to attr("warehouse", "warehouse_id")),
                            ),
                            ModelRelation(
                                er("catalog_sales"),
                                er("date_dim"),
                                listOf(attr("catalog_sales", "sold_date_id") to attr("date_dim", "date_id")),
                            ),
                        ),
                    entityMappings = entities.associate { it.qname to EntityMapping.ToTable(db(it.qname.name)) },
                ),
            )

        // -- The shapes: where the empty VALUES ends up in the plan ---------------------------------------

        val neverHolds = "\"warehouse_name\" = 'Memphis DC' AND \"warehouse_name\" = 'Allentown DC'"

        /** The query from ttr-server#203: a SUM over model joins, under the row cap (`limit_offset`). */
        val sumOverJoins =
            "SELECT SUM(\"catalog_sales\".\"ext_sales_price\") AS \"ext_sales_price\" " +
                "FROM \"catalog_sales\" JOIN \"date_dim\" JOIN \"warehouse\" " +
                "WHERE \"date_dim\".\"cal_date\" >= TIMESTAMP '2025-01-01 00:00:00' " +
                "AND \"date_dim\".\"cal_date\" < TIMESTAMP '2026-01-01 00:00:00' " +
                "AND \"warehouse\".$neverHolds FETCH FIRST 200 ROWS ONLY"

        /** The query from ttr-core#148: two disjoint date ranges ANDed, under a GROUP BY. */
        val countByMonth =
            "SELECT \"date_dim\".\"month\" AS \"month\", COUNT(*) AS \"n\" FROM \"date_dim\" " +
                "WHERE \"date_dim\".\"cal_date\" >= TIMESTAMP '2025-01-01 00:00:00' " +
                "AND \"date_dim\".\"cal_date\" < TIMESTAMP '2026-01-01 00:00:00' " +
                "AND \"date_dim\".\"cal_date\" >= TIMESTAMP '2026-10-01 00:00:00' " +
                "AND \"date_dim\".\"cal_date\" < TIMESTAMP '2026-11-01 00:00:00' " +
                "GROUP BY \"date_dim\".\"month\""

        val projection =
            "SELECT \"warehouse\".\"warehouse_id\", \"warehouse\".\"warehouse_name\" FROM \"warehouse\" " +
                "WHERE \"warehouse\".$neverHolds"

        /** The contradiction sits in one side of the join, so only that side folds: VALUES under a JOIN. */
        fun joinedTo(joinType: String) =
            "SELECT \"cs\".\"channel\", \"w\".\"warehouse_name\", \"cs\".\"ext_sales_price\" " +
                "FROM \"catalog_sales\" AS \"cs\" $joinType (SELECT \"warehouse_id\", \"warehouse_name\" " +
                "FROM \"warehouse\" WHERE $neverHolds) AS \"w\" ON \"cs\".\"warehouse_id\" = \"w\".\"warehouse_id\""

        val shapes =
            mapOf(
                "a SUM over joins under a row cap (ttr-server#203)" to sumOverJoins,
                "a COUNT under a GROUP BY (ttr-core#148)" to countByMonth,
                "a plain projection" to projection,
                "an inner join with one side empty" to joinedTo("JOIN"),
                "a left join with one side empty" to joinedTo("LEFT JOIN"),
            )

        val dialects =
            SqlDialect.entries.filter { it != SqlDialect.SQL_DIALECT_UNSPECIFIED && it != SqlDialect.UNRECOGNIZED }

        // -- Helpers ---------------------------------------------------------------------------------------

        /** Pass 1 (SQL → ER plan), then pass 2 (REL_NODE re-entry → DB plan), as the query service runs it. */
        fun twoPass(sql: String): PlanNode {
            val er = translator.parseToRelNode(sql, Language.SQL, SchemaCode.ER, sourceSchema = SchemaCode.ER)
            er.shouldBeInstanceOf<ParseResult.Success>()
            val db =
                translator.parseToRelNode(
                    String(er.plan.toByteArray(), Charsets.ISO_8859_1),
                    Language.REL_NODE,
                    SchemaCode.DB,
                )
            db.shouldBeInstanceOf<ParseResult.Success>()
            return db.plan
        }

        fun emptyValuesIn(plan: PlanNode): Boolean =
            when (plan.nodeCase) {
                PlanNode.NodeCase.VALUES -> plan.values.rowsCount == 0
                PlanNode.NodeCase.PROJECT -> emptyValuesIn(plan.project.input)
                PlanNode.NodeCase.FILTER -> emptyValuesIn(plan.filter.input)
                PlanNode.NodeCase.JOIN -> emptyValuesIn(plan.join.left) || emptyValuesIn(plan.join.right)
                PlanNode.NodeCase.AGGREGATE -> emptyValuesIn(plan.aggregate.input)
                PlanNode.NodeCase.SORT -> emptyValuesIn(plan.sort.input)
                PlanNode.NodeCase.LIMIT_OFFSET -> emptyValuesIn(plan.limitOffset.input)
                else -> false
            }

        /** A NULL with no CAST around it: the engine, not the plan, would decide its type. */
        val bareNull = Regex("""(?<!CAST\()\bNULL\b""")

        // -- Tests -----------------------------------------------------------------------------------------

        "the second pass carries every shape's contradiction as an empty VALUES" {
            for ((shape, sql) in shapes) {
                withClue(shape) { emptyValuesIn(twoPass(sql)) shouldBe true }
            }
        }

        for (dialect in dialects) {
            for (optimize in listOf(true, false)) {
                for ((shape, sql) in shapes) {
                    "$shape unparses to an always-false query in $dialect (optimize=$optimize)" {
                        val unparsed = translator.unparseFromRelNode(twoPass(sql), Language.SQL, dialect, optimize)

                        val sqlOut = unparsed.shouldBeInstanceOf<UnparseResult.Success>().output
                        sqlOut shouldContain "1 = 0"
                        bareNull
                            .findAll(sqlOut)
                            .map { it.value }
                            .toList()
                            .shouldBeEmpty()
                    }
                }
            }

            "a single-pass translate types the NULLs of the relation it folds while unparsing ($dialect)" {
                // No wire in between: the filter folds when the unparse half decodes the plan.
                val translated =
                    translator.translate(
                        projection,
                        Language.SQL,
                        Language.SQL,
                        SchemaCode.DB,
                        dialect,
                        sourceSchema = SchemaCode.ER,
                    )

                val sqlOut = translated.shouldBeInstanceOf<TranslateResult.Success>().output
                sqlOut shouldContain "1 = 0"
                bareNull
                    .findAll(sqlOut)
                    .map { it.value }
                    .toList()
                    .shouldBeEmpty()
            }

            "the ttr-server#203 query renders as its golden in $dialect" {
                translator
                    .unparseFromRelNode(twoPass(sumOverJoins), Language.SQL, dialect)
                    .shouldBeInstanceOf<UnparseResult.Success>()
                    .output shouldBe sumOverJoinsGolden(dialect)
            }

            "the SUM over nothing keeps its column name and type and is not grouped ($dialect)" {
                val sqlOut =
                    translator
                        .unparseFromRelNode(twoPass(sumOverJoins), Language.SQL, dialect)
                        .shouldBeInstanceOf<UnparseResult.Success>()
                        .output

                // An ungrouped aggregate over no rows is the one all-NULL row; GROUP BY would make it none.
                sqlOut shouldNotContain "GROUP BY"
                sqlOut shouldContain "ext_sales_price"
                sqlOut shouldContain typedFloatNull(dialect)
            }
        }
    })

/**
 * The SQL each dialect gets for the ttr-server#203 query (optimize=true), copied from the engine's output.
 * The PostgreSQL and DuckDB forms were run on those engines: one row, `ext_sales_price` NULL, typed
 * `float8` / `DOUBLE` like the column.
 */
private fun sumOverJoinsGolden(dialect: SqlDialect): String {
    val columns =
        listOf(
            "warehouse_id",
            "sold_date_id",
            "channel",
            "ext_sales_price",
            "date_id",
            "cal_date",
            "month",
            "warehouse_id0",
            "warehouse_name",
        )
    return when (dialect) {
        SqlDialect.MSSQL ->
            "SELECT TOP (200) SUM([ext_sales_price]) AS [ext_sales_price]\n" +
                "FROM (VALUES (CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), CAST(NULL AS VARCHAR(MAX)), " +
                "CAST(NULL AS FLOAT), CAST(NULL AS BIGINT), CAST(NULL AS DATETIME2(0)), " +
                "CAST(NULL AS VARCHAR(MAX)), " +
                "CAST(NULL AS BIGINT), CAST(NULL AS VARCHAR(MAX)))) AS [t] (${columns.joinToString { "[$it]" }})\n" +
                "WHERE 1 = 0"
        SqlDialect.POSTGRESQL, SqlDialect.DUCKDB ->
            "SELECT SUM(\"ext_sales_price\") AS \"ext_sales_price\"\n" +
                "FROM (VALUES (CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), CAST(NULL AS VARCHAR), " +
                "CAST(NULL AS DOUBLE PRECISION), CAST(NULL AS BIGINT), CAST(NULL AS TIMESTAMP(0)), " +
                "CAST(NULL AS VARCHAR), " +
                "CAST(NULL AS BIGINT), CAST(NULL AS VARCHAR))) AS \"t\" (${columns.joinToString { "\"$it\"" }})\n" +
                "WHERE 1 = 0\n" +
                "FETCH NEXT 200 ROWS ONLY"
        SqlDialect.MYSQL_MARIADB ->
            "SELECT SUM(`ext_sales_price`) AS `ext_sales_price`\n" +
                "FROM (SELECT CAST(NULL AS SIGNED) AS `warehouse_id`, CAST(NULL AS SIGNED) AS `sold_date_id`, " +
                "CAST(NULL AS CHAR) AS `channel`, CAST(NULL AS DOUBLE) AS `ext_sales_price`, " +
                "CAST(NULL AS SIGNED) AS `date_id`, CAST(NULL AS DATETIME) AS `cal_date`, " +
                "CAST(NULL AS CHAR) AS `month`, " +
                "CAST(NULL AS SIGNED) AS `warehouse_id0`, CAST(NULL AS CHAR) AS `warehouse_name`) AS `t`\n" +
                "WHERE 1 = 0\n" +
                "LIMIT 200"
        else -> error("no golden for $dialect: add one")
    }
}

/** How [dialect] spells a NULL of the fixture's `float` (Calcite DOUBLE) type. */
private fun typedFloatNull(dialect: SqlDialect): String =
    when (dialect) {
        SqlDialect.MSSQL -> "CAST(NULL AS FLOAT)"
        SqlDialect.POSTGRESQL, SqlDialect.DUCKDB -> "CAST(NULL AS DOUBLE PRECISION)"
        SqlDialect.MYSQL_MARIADB -> "CAST(NULL AS DOUBLE)"
        else -> error("no typed-NULL expectation for $dialect: add one")
    }
