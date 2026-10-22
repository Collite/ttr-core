// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.Model
import java.math.BigDecimal
import java.net.URI
import java.sql.DriverManager
import kotlin.io.path.readText

/**
 * CN-P3 S3.3 T7 — the `live`-tagged `statementLower` goldens executed on a real PostgreSQL (part of the PR's live
 * suite, S3.8 T4): the lowered `plan.v1` unparsed to PostgreSQL by `ttr-translator` over the world's db tier, run on
 * ttrp-conform's md seed (`md_seed.sql`) plus the case's own invented rows (`seed.sql`), and its rows compared with
 * `expected.live.json` (`{"rows": [["150.50"]]}`, numbers compared by value).
 *
 * The case seeds its own rows rather than extending `md_seed.sql`: ttrp-conform's write round trip spreads Kaufland's
 * seed total (1885) and would read any added Kaufland row. Everything runs in a schema of its own ([LivePg.SCHEMA], the
 * connection's `search_path`; the plan's tables unparse bare), so it never touches ttrp-conform's tables.
 *
 * Gated like its ttrp-conform siblings: the database is `TTR_CONN_ERP_PG` (`postgresql://user:pass@host:port/db`,
 * CI's), else — with `TTRP_CONFORM_PG=1` — the local ttrp-pg (`spike.pg.*`). Without either the cases are reported
 * ignored, never failed.
 */
class ConceptLiveSpec :
    FunSpec({
        val db = LivePg.connection()
        val cases = GoldenCases.cases.filter { "live" in it.tags && it.call == "statementLower" }

        for (c in cases) {
            test("${c.id} — the lowered plan computes its rows on live PostgreSQL").config(enabled = db != null) {
                val world = GoldenWorlds[c.world]
                val lowered = CnConceptService(world.cn).statementLower(c.statement())
                val plan = lowered.plan ?: fail("${c.id} does not lower: ${lowered.diagnostics}")
                val unparsed =
                    Translator(dbHandle(world.cn.model)).unparseFromRelNode(plan, Language.SQL, SqlDialect.POSTGRESQL)
                val sql = unparsed.shouldBeInstanceOf<UnparseResult.Success>().output
                val expected =
                    c.jsonFile("expected.live.json").jsonObject["rows"]!!.jsonArray.map { row ->
                        row.jsonArray.map { it.jsonPrimitive.content }
                    }
                val rows = db!!.run(LivePg.mdSeed() + "\n" + c.text("seed.sql"), sql)
                withClue("${c.id}\n$sql\nrows: $rows") {
                    rows.size shouldBe expected.size
                    rows.zip(expected).forEach { (got, want) ->
                        got.size shouldBe want.size
                        got.zip(want).forEach { (g, w) -> same(g, w) shouldBe true }
                    }
                }
            }
        }
    }) {
    companion object {
        /** A cell equals its expectation as text, or — both numbers — by value (`150.5` = `150.50`). */
        private fun same(
            got: String?,
            want: String,
        ): Boolean {
            if (got == want) return true
            val g = got?.toBigDecimalOrNull() ?: return false
            val w = want.toBigDecimalOrNull() ?: return false
            return g.compareTo(w) == 0
        }

        /** The world's db tier as the translator's model: each table on `db.dbo.<name>`, its columns typed. */
        internal fun dbHandle(model: Model): InMemoryModelHandle {
            val db = model.schemas["db"] as? DbSchema ?: return InMemoryModelHandle(emptyList())
            return InMemoryModelHandle(
                db.tables.values.sortedBy { it.qname.name }.map { t ->
                    ModelTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName(t.qname.name)
                            .build(),
                        t.columns.map {
                            ModelColumn(
                                it.qname.name.substringAfterLast('.'),
                                surface(it.dataType),
                                it.nullable,
                            )
                        },
                    )
                },
            )
        }

        private fun surface(type: String): SurfaceType =
            when (type.lowercase()) {
                "int", "integer", "long", "bigint" -> SurfaceType.INT
                "decimal", "float", "double", "number", "numeric" -> SurfaceType.FLOAT
                "bool", "boolean" -> SurfaceType.BOOL
                "date", "timestamp", "datetime" -> SurfaceType.DATETIME
                else -> SurfaceType.TEXT
            }
    }
}

/** The live-PG harness's connection (the ttrp-conform convention — `MdWriteRoundTripTest`'s). */
internal class LivePg private constructor(
    private val url: String,
    private val user: String,
    private val password: String,
) {
    /** Runs [seed] (one script), then [query], in [SCHEMA]; the rows as text cells. */
    fun run(
        seed: String,
        query: String,
    ): List<List<String?>> =
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS $SCHEMA; SET search_path TO $SCHEMA") }
            conn.createStatement().use { it.execute(seed) }
            conn.createStatement().use { st ->
                st.executeQuery(query).use { rs ->
                    val n = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..n).map { rs.getObject(it)?.let(::text) }) }
                }
            }
        }

    companion object {
        /** The live suite's own schema — never ttrp-conform's (`public`) tables (S3.8 T4). */
        const val SCHEMA = "cn_live"

        /** `TTR_CONN_ERP_PG`, else (`TTRP_CONFORM_PG=1`) the local ttrp-pg; null — skip — when neither. */
        fun connection(): LivePg? {
            System.getenv("TTR_CONN_ERP_PG")?.let { raw ->
                val uri = URI(raw)
                val info = uri.userInfo?.split(":").orEmpty()
                return LivePg(
                    "jdbc:postgresql://${uri.host}:${if (uri.port > 0) uri.port else 5432}${uri.path}",
                    info.getOrElse(0) { "postgres" },
                    info.getOrElse(1) { "" },
                )
            }
            if (System.getenv("TTRP_CONFORM_PG") != "1") return null
            return LivePg(
                System.getProperty("spike.pg.url", "jdbc:postgresql://localhost:55432/postgres"),
                System.getProperty("spike.pg.user", "postgres"),
                System.getProperty("spike.pg.password", "ttrp"),
            )
        }

        /** ttrp-conform's md seed — `f_sales` / `f_plan` / `d_calendar`, the tables the md hero's bindings target. */
        fun mdSeed(): String =
            DefinitionFixtures.repo
                .resolve(
                    "packages/kotlin/ttrp-conform/src/test/resources/seed/md_seed.sql",
                ).readText()

        private fun text(v: Any): String = if (v is BigDecimal) v.toPlainString() else v.toString()
    }
}
