// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.concept.v1.ConceptRef
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
 * The goldens executed on a real PostgreSQL — part of the PR's live suite (S3.8 T4):
 *
 *  - CN-P3 S3.3 T7 — the `live`-tagged `statementLower` cases: the lowered `plan.v1` unparsed to PostgreSQL by
 *    `ttr-translator` over the world's db tier, run on ttrp-conform's md seed (`md_seed.sql`) plus the case's own
 *    invented rows (`seed.sql`), its rows compared with `expected.live.json` (`{"rows": [["150.50"]]}`, numbers by
 *    value). The case seeds its own rows rather than extending `md_seed.sql`: ttrp-conform's write round trip spreads
 *    Kaufland's seed total (1885) and would read any added Kaufland row.
 *  - CN-P3 S3.4 T7 — every er rendering (`definitionRender(…, ER)`, the S3.4 goldens): translated over the world's
 *    [ErModelHandle] to the db tier and unparsed to PostgreSQL ([ErSql]), run on the world's tables (created from its
 *    db tier, [LivePg.ddl]) filled with its invented rows (`worlds/<world>/live-seed.sql`) and the case's (`seed.sql`).
 *    It must execute; an H-1 context returns the same rows as AU's island (`au.sql` — A-H-2 / A-CN-15: the row-set bar
 *    of `price-differences`, a live check of the plan-equal two); `expected.live.json`, when present, its rows.
 *
 * Everything runs in a schema of its own ([LivePg.SCHEMA], the connection's `search_path`; the plans' tables unparse
 * bare), so it never touches ttrp-conform's tables. Gated like its ttrp-conform siblings: the database is
 * `TTR_CONN_ERP_PG` (`postgresql://user:pass@host:port/db`, CI's), else — with `TTRP_CONFORM_PG=1` — the local ttrp-pg
 * (`spike.pg.*`). Without either the cases are reported ignored, never failed.
 */
class ConceptLiveSpec :
    FunSpec({
        val db = LivePg.connection()

        for (c in GoldenCases.cases.filter { "live" in it.tags && it.call == "statementLower" }) {
            test("${c.id} — the lowered plan computes its rows on live PostgreSQL").config(enabled = db != null) {
                val world = GoldenWorlds[c.world]
                val lowered = CnConceptService(world.cn).statementLower(c.statement())
                val plan = lowered.plan ?: fail("${c.id} does not lower: ${lowered.diagnostics}")
                val unparsed =
                    Translator(dbHandle(world.cn.model)).unparseFromRelNode(plan, Language.SQL, SqlDialect.POSTGRESQL)
                val sql = unparsed.shouldBeInstanceOf<UnparseResult.Success>().output
                val rows = db!!.run(LivePg.mdSeed() + "\n" + c.text("seed.sql"), sql)
                withClue("${c.id}\n$sql\nrows: $rows") { matches(rows, expectedRows(c)) shouldBe true }
            }
        }

        for (c in renderings()) {
            test("${c.id} — the er rendering executes on live PostgreSQL").config(enabled = db != null) {
                val world = GoldenWorlds[c.world]
                val model = world.cn.model
                val text = render(c).text
                val (sql, params) = ErSql.unparse(model, text, SqlDialect.POSTGRESQL)
                val seed =
                    LivePg.ddl(model) + "\n" + world.dir.resolve("live-seed.sql").readText() +
                        (if (c.has("seed.sql")) "\n" + c.text("seed.sql") else "")
                val rows = db!!.run(seed, sql, params)
                withClue("${c.id}\n$sql\n$params\nrows: $rows") {
                    c.oracle?.get("au")?.jsonPrimitive?.content?.let { au ->
                        val (auSql, auParams) = ErSql.unparse(model, c.text(au), SqlDialect.POSTGRESQL)
                        val auRows = db.run(seed, auSql, auParams)
                        withClue("A-H-2 / A-CN-15: the same rows as AU's island\n$auSql\nAU rows: $auRows") {
                            matches(rows, auRows.map { r -> r.map { it ?: NULL } }) shouldBe true
                        }
                    }
                    if (c.has("expected.live.json")) matches(rows, expectedRows(c)) shouldBe true
                }
            }
        }
    }) {
    companion object {
        /** A NULL cell in an expectation. */
        private const val NULL = "\u0000null"

        /** The er goldens that render (a refusal renders no text). */
        internal fun renderings(): List<GoldenCases.Case> =
            GoldenCases.cases.filter {
                it.call == "definitionRender" &&
                    it.str("layer") == "ER" &&
                    it.has("expected.sql") &&
                    it.text("expected.sql").isNotBlank()
            }

        internal fun render(c: GoldenCases.Case): Rendering {
            val problems = mutableListOf<String>()
            val options = ConceptGoldenSpec.options(c.spec["options"]?.jsonObject ?: JsonObject(emptyMap()), problems)
            if (problems.isNotEmpty()) fail(problems.joinToString())
            return CnConceptService(GoldenWorlds[c.world].cn).definitionRender(
                ConceptRef.newBuilder().setQname(c.str("target")!!).build(),
                Layer.ER,
                c.str("dialect")!!,
                options,
            )
        }

        private fun expectedRows(c: GoldenCases.Case): List<List<String>> =
            c.jsonFile("expected.live.json").jsonObject["rows"]!!.jsonArray.map { row ->
                row.jsonArray.map { if (it is JsonNull) NULL else it.jsonPrimitive.content }
            }

        /** The same rows in any order, cell by cell ([same]). */
        private fun matches(
            got: List<List<String?>>,
            want: List<List<String>>,
        ): Boolean {
            if (got.size != want.size) return false
            val key = { r: List<String?> ->
                r.joinToString("|") {
                    it?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
                        ?: it
                        ?: NULL
                }
            }
            return got.sortedBy(key).zip(want.sortedBy(key)).all { (g, w) ->
                g.size == w.size &&
                    g.zip(w).all { (a, b) -> same(a, b) }
            }
        }

        /** A cell equals its expectation as text, or — both numbers — by value (`150.5` = `150.50`); NULL = [NULL]. */
        private fun same(
            got: String?,
            want: String,
        ): Boolean {
            if (want == NULL) return got == null
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
    /** Runs [seed] (one script), then [query] with its `?` [params], in [SCHEMA]; the rows as text cells. */
    fun run(
        seed: String,
        query: String,
        params: List<Any?> = emptyList(),
    ): List<List<String?>> =
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = true
            conn.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS $SCHEMA; SET search_path TO $SCHEMA") }
            conn.createStatement().use { it.execute(seed) }
            conn.prepareStatement(query).use { st ->
                params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
                st.executeQuery().use { rs ->
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

        /** The world's db tier as PostgreSQL DDL: every table dropped and created (names quoted, the key kept). */
        fun ddl(model: Model): String {
            val db = model.schemas["db"] as? DbSchema ?: return ""
            return db.tables.values.sortedBy { it.qname.name }.joinToString("\n") { t ->
                val cols =
                    t.columns.map { "\"${it.qname.name.substringAfterLast('.')}\" ${pgType(it.dataType)}" } +
                        listOfNotNull(
                            t.primaryKey.takeIf { it.isNotEmpty() }?.joinToString(
                                ", ",
                                "PRIMARY KEY (",
                                ")",
                            ) { "\"$it\"" },
                        )
                val name = "\"${t.qname.name}\""
                "DROP TABLE IF EXISTS $name CASCADE;\nCREATE TABLE $name (${cols.joinToString(", ")});"
            }
        }

        private fun pgType(type: String): String =
            when (type.lowercase()) {
                "int", "integer" -> "integer"
                "long", "bigint" -> "bigint"
                "decimal", "numeric", "number" -> "numeric"
                "float", "double" -> "double precision"
                "bool", "boolean" -> "boolean"
                "date" -> "date"
                "timestamp", "datetime" -> "timestamp"
                else -> "text"
            }

        private fun text(v: Any): String = if (v is BigDecimal) v.toPlainString() else v.toString()
    }
}
