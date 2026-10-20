// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.emit.recipe.RecipeStatement
import org.tatrman.ttrp.emit.recipe.RecipeStatements
import org.tatrman.ttrp.emit.sql.SqlTier
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.recipe.RecipeModel
import org.tatrman.ttrp.project.TtrpManifest
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * AU-core-P2 · S2.1 T6 / S2.2 T6 — the four `17` recipes compiled and **run**: every statement of every output (the
 * DB rendering of the plan the bundle ships in E-R) executes on Postgres over the hartland sample
 * (`recipes/hartland/{schema,sample}.sql`, the recipe-checker fixture model's tables), and every route takes exactly
 * the keys the sample planted for it at `:as_of` = 2026-11-02 08:00 — the switch lowering, the detail joins, `latest`,
 * the calendar counts and the derived `:as_of` values, against the data.
 *
 * Gated by `TTRP_CONFORM_PG=1`; the server from `TTR_CONN_ERP_PG` (`postgresql://user:pass@host:port/db`), else the
 * local ttrp-pg container (`spike.pg.*`, localhost:55432). It creates and drops its own database.
 */
class RecipeLivePgSpec :
    FunSpec({
        val enabled = System.getenv("TTRP_CONFORM_PG") == "1"
        val erpPg = System.getenv("TTR_CONN_ERP_PG")?.let { URI(it) }
        val userInfo = erpPg?.userInfo?.split(":", limit = 2)
        val server =
            erpPg?.let { "jdbc:postgresql://${it.host}:${if (it.port > 0) it.port else 5432}" }
                ?: System.getProperty("spike.pg.server", "jdbc:postgresql://localhost:55432")
        val user = userInfo?.getOrNull(0) ?: System.getProperty("spike.pg.user", "postgres")
        val password = userInfo?.getOrNull(1) ?: System.getProperty("spike.pg.password", "ttrp")
        val database = "ttrp_recipes"
        val asOf = LocalDateTime.of(2026, 11, 2, 8, 0)

        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val c = dir.resolve(rel)
                if (Files.exists(c)) return c
                dir = dir.parent
            }
            error("could not locate $rel")
        }

        val fixtures = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes")
        val recipes = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes")
        val sample = locate("packages/kotlin/ttrp-conform/src/test/resources/recipes/hartland")
        val pipeline =
            TtrpPipeline(
                TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures),
                fixtures.resolve("model"),
            )

        lateinit var conn: Connection

        beforeSpec {
            if (!enabled) return@beforeSpec
            DriverManager.getConnection("$server/postgres", user, password).use { admin ->
                admin.createStatement().use {
                    it.execute("DROP DATABASE IF EXISTS $database")
                    it.execute("CREATE DATABASE $database")
                }
            }
            conn = DriverManager.getConnection("$server/$database", user, password)
            conn.createStatement().use {
                it.execute(Files.readString(sample.resolve("schema.sql")))
                it.execute(Files.readString(sample.resolve("sample.sql")))
            }
        }

        afterSpec {
            if (!enabled) return@afterSpec
            conn.close()
            DriverManager.getConnection("$server/postgres", user, password).use { admin ->
                admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS $database") }
            }
        }

        /** The value each bound name takes: `:as_of`, a parameter's declared value, a derived instant. */
        fun bindings(model: RecipeModel): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            out[RecipeStatements.AS_OF] = Timestamp.valueOf(asOf)
            model.parameterDecls.forEach { p ->
                out[p.name] =
                    when (p.type) {
                        "decimal" -> p.value?.let { BigDecimal(it) }
                        "days", "working_days", "int" -> p.value?.toInt()
                        else -> p.value
                    }
            }
            model.derivedParams.forEach { d ->
                val n =
                    d.amount.toLongOrNull() ?: model.parameterDecls
                        .first { it.name == d.amount }
                        .value!!
                        .toLong()
                val step =
                    when (d.unit) {
                        "days" -> asOf.minusDays(n) to asOf.plusDays(n)
                        "weeks" -> asOf.minusWeeks(n) to asOf.plusWeeks(n)
                        "months" -> asOf.minusMonths(n) to asOf.plusMonths(n)
                        "hours" -> asOf.minusHours(n) to asOf.plusHours(n)
                        else -> asOf.minusMinutes(n) to asOf.plusMinutes(n)
                    }
                out[d.name] = Timestamp.valueOf(if (d.op == "minus") step.first else step.second)
            }
            return out
        }

        /** [s] run with its `:name` placeholders bound — its rows as column → value maps. */
        fun run(
            s: RecipeStatement,
            bound: Map<String, Any?>,
        ): List<Map<String, Any?>> {
            val (sql, names) = positional(s.sql)
            conn.prepareStatement(sql).use { ps ->
                names.forEachIndexed { i, n -> ps.setObject(i + 1, bound.getValue(n)) }
                ps.executeQuery().use { rs ->
                    val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) rows += cols.associateWith { rs.getObject(it) }
                    return rows
                }
            }
        }

        fun compiled(name: String): Pair<RecipeModel, Map<String, List<Map<String, Any?>>>> {
            val plan = pipeline.plan(Files.readString(recipes.resolve(name)), name)
            plan.ok shouldBe true
            val model = plan.recipe!!
            val bound = bindings(model)
            val results =
                RecipeStatements.plan(plan, SqlTier.DB).associate { s ->
                    "${s.island}.${s.port}" to
                        try {
                            run(s, bound)
                        } catch (e: java.sql.SQLException) {
                            throw AssertionError("${s.island}.${s.port} failed on Postgres: ${e.message}\n${s.sql}", e)
                        }
                }
            return model to results
        }

        fun keys(
            rows: List<Map<String, Any?>>?,
            key: String,
        ) = rows.orEmpty().map { (it[key] as Number).toInt() }

        test(
            "release_order (H-1, overlapping): each route takes the planted orders, each detail its rows",
        ).config(enabled = enabled) {
            val (_, r) = compiled("release_order.ttrp")
            keys(r["verdict.hold_sales"], "order_number") shouldContainExactlyInAnyOrder listOf(3, 4, 7)
            keys(r["verdict.hold_ar"], "order_number") shouldContainExactlyInAnyOrder listOf(5)
            keys(r["verdict.release_confirmed"], "order_number") shouldContainExactlyInAnyOrder listOf(2)
            keys(r["verdict.release"], "order_number") shouldContainExactlyInAnyOrder listOf(1, 8)
            // the shortfall of 3 (3 on hand at the LATEST snapshot) and of 7 (its center is out); 4's price; 5's invoice
            keys(r["verdict.hold_sales.shortfall"], "order_number") shouldContainExactlyInAnyOrder listOf(3, 7)
            r["verdict.hold_sales.shortfall"]!!.first { (it["order_number"] as Number).toInt() == 7 }["outage"] shouldBe
                true
            r["verdict.hold_sales.shortfall"]!!.first { (it["order_number"] as Number).toInt() == 3 }["outage"] shouldBe
                false
            keys(r["verdict.hold_sales.price_differences"], "order_number") shouldContainExactlyInAnyOrder listOf(4)
            keys(r["verdict.hold_ar.overdue"], "order_number") shouldContainExactlyInAnyOrder listOf(5)
            r["verdict.hold_ar"]!!.single()["verdict_reason"] shouldBe "overdue invoices"
            r["verdict.hold_ar"]!!.single()["route"] shouldBe "hold_ar"
        }

        test(
            "decide_return (R2a, exclusive): the first route that matches wins; `because:` fills a parameter",
        ).config(enabled = enabled) {
            val (_, r) = compiled("decide_return.ttrp")
            keys(r["way.reject_not_ours"], "line_id") shouldContainExactlyInAnyOrder listOf(903)
            keys(r["way.reject_late"], "line_id") shouldContainExactlyInAnyOrder listOf(902)
            keys(r["way.reject_abuse"], "line_id") shouldContainExactlyInAnyOrder listOf(906)
            keys(r["way.to_customer_care"], "line_id") shouldContainExactlyInAnyOrder listOf(905)
            keys(r["way.refund_no_return"], "line_id") shouldContainExactlyInAnyOrder listOf(901)
            keys(r["way.replacement_no_return"], "line_id").shouldBeEmpty()
            keys(r["way.exchange"], "line_id").shouldBeEmpty()
            keys(r["way.refund_after_return"], "line_id") shouldContainExactlyInAnyOrder listOf(904)
            r["way.reject_late"]!!.single()["verdict_reason"] shouldBe "delivered more than 30 days ago"
        }

        test(
            "monitor_inbound (R2b): age over the calendar, the recipe's own verdicts, `else: none` gives no row",
        ).config(enabled = enabled) {
            val (_, r) = compiled("monitor_inbound.ttrp")
            keys(r["inbound.complete"], "line_id") shouldContainExactlyInAnyOrder listOf(913)
            keys(r["inbound.damaged"], "line_id") shouldContainExactlyInAnyOrder listOf(914)
            keys(r["inbound.expired"], "line_id") shouldContainExactlyInAnyOrder listOf(912)
            keys(r["inbound.remind"], "line_id") shouldContainExactlyInAnyOrder listOf(911)
            keys(r["inbound.complete.receipts"], "line_id") shouldContainExactlyInAnyOrder listOf(913)
            keys(r["inbound.damaged.receipts"], "line_id") shouldContainExactlyInAnyOrder listOf(914)
            r.keys.none { it.endsWith(".else") } shouldBe true
        }

        test(
            "settle (R2c, overlapping): working days late over the calendar, a line on several routes",
        ).config(enabled = enabled) {
            val (_, r) = compiled("settle.ttrp")
            keys(r["settlement.credit_note_issued"], "line_id") shouldContainExactlyInAnyOrder listOf(923)
            keys(r["settlement.money_received"], "line_id") shouldContainExactlyInAnyOrder listOf(923)
            keys(r["settlement.goods_shipped"], "line_id") shouldContainExactlyInAnyOrder listOf(922)
            keys(r["settlement.money_overdue"], "line_id") shouldContainExactlyInAnyOrder listOf(921)
            keys(r["settlement.goods_overdue"], "line_id").shouldBeEmpty()
            keys(r["settlement.closed"], "line_id") shouldContainExactlyInAnyOrder listOf(922, 923)
            keys(r["settlement.money_overdue.open_obligations"], "line_id") shouldContainExactlyInAnyOrder listOf(921)
            // 25 Oct → 2 Nov: 26, 27, 29, 30 Oct and 2 Nov (the 28th is a holiday, the 31st and 1st the weekend)
            r["settlement.money_overdue"]!!.single()["verdict_reason"] shouldBe "refund 5 working days late"
            keys(r["overdue_days.rows"], "line_id") shouldContainExactlyInAnyOrder listOf(921, 922, 923)
        }
    }) {
    companion object {
        /** `:name` placeholders → `?` in order of appearance (outside quotes), with the names in that order. */
        fun positional(sql: String): Pair<String, List<String>> {
            val out = StringBuilder()
            val names = mutableListOf<String>()
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                when {
                    c == '\'' || c == '"' -> {
                        var j = i + 1
                        while (j < sql.length && !(sql[j] == c && (j + 1 >= sql.length || sql[j + 1] != c))) {
                            j += if (sql[j] == c) 2 else 1
                        }
                        out.append(sql, i, minOf(j + 1, sql.length))
                        i = j + 1
                    }
                    c == ':' && i + 1 < sql.length && (sql[i + 1].isLetter() || sql[i + 1] == '_') -> {
                        var j = i + 1
                        while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                        names += sql.substring(i + 1, j)
                        out.append('?')
                        i = j
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString() to names
        }
    }
}
