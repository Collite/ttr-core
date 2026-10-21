// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.net.URI
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * The hartland sample (`recipes/hartland/{schema,sample}.sql`) and what it planted (AU-core-P2 · S2.1–S2.4): the four
 * `17` recipes compiled against the recipe-checker fixture model, and for each the keys every route takes at `:as_of`
 * = 2026-11-02 08:00 — one table of expectations every engine (Postgres, DuckDB) is held to.
 */
object RecipePlanted {
    val AS_OF: LocalDateTime = LocalDateTime.of(2026, 11, 2, 8, 0)
    val RECIPES = listOf("release_order.ttrp", "decide_return.ttrp", "monitor_inbound.ttrp", "settle.ttrp")

    /**
     * The declared engine deltas: decide_return's `share` is a decimal ÷ decimal, which Postgres keeps in NUMERIC (20
     * digits) and DuckDB computes in DOUBLE — equal to 1e-12, in `return_share.rows` and every route's rows that carry
     * it; the route it feeds (`share > :abuse_share`) and its `because:` text (4 places, ⚑AU-C-9) are the same on both.
     */
    val NUMERIC_DELTAS = mapOf("share" to 1e-12)

    private fun locate(rel: String): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val c = dir.resolve(rel)
            if (Files.exists(c)) return c
            dir = dir.parent
        }
        error("could not locate $rel")
    }

    private val fixtures by lazy { locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes") }
    private val recipes by lazy { locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes") }
    private val sample by lazy { locate("packages/kotlin/ttrp-conform/src/test/resources/recipes/hartland") }
    private val pipeline by lazy {
        TtrpPipeline(TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures), fixtures.resolve("model"))
    }

    /** The fixture scripts, in load order. */
    val scripts: List<String> by lazy {
        listOf(Files.readString(sample.resolve("schema.sql")), Files.readString(sample.resolve("sample.sql")))
    }

    fun plan(name: String): TtrpPipeline.PlanResult = planSource(Files.readString(recipes.resolve(name)), name)

    /** [source] planned against the fixture model, which must check clean. */
    fun planSource(
        source: String,
        name: String = "probe.ttrp",
    ): TtrpPipeline.PlanResult {
        val plan = pipeline.plan(source, name)
        withClue(plan.diagnostics.joinToString("\n")) { plan.ok shouldBe true }
        return plan
    }

    /**
     * Postgres, when `TTRP_CONFORM_PG=1`: the server from `TTR_CONN_ERP_PG` (`postgresql://user:pass@host:port/db`),
     * else the local ttrp-pg container (`spike.pg.*`, localhost:55432); a scratch [database] with [fixtures] loaded.
     */
    val pgEnabled = System.getenv("TTRP_CONFORM_PG") == "1"

    fun pgEngine(
        database: String,
        fixtures: List<String> = scripts,
    ): PostgresRecipeEngine {
        val erpPg = System.getenv("TTR_CONN_ERP_PG")?.let { URI(it) }
        val userInfo = erpPg?.userInfo?.split(":", limit = 2)
        val server =
            erpPg?.let { "jdbc:postgresql://${it.host}:${if (it.port > 0) it.port else 5432}" }
                ?: System.getProperty("spike.pg.server", "jdbc:postgresql://localhost:55432")
        val user = userInfo?.getOrNull(0) ?: System.getProperty("spike.pg.user", "postgres")
        val password = userInfo?.getOrNull(1) ?: System.getProperty("spike.pg.password", "ttrp")
        return PostgresRecipeEngine(server, user, password, database, fixtures)
    }

    /** [run]'s outputs as rows (column → value), every statement having run. */
    fun rows(run: RecipeConform.EngineRun): Map<String, List<Map<String, Any?>>> {
        if (run.failures.isNotEmpty()) {
            throw AssertionError(
                "${run.failures.size} statement(s) failed on ${run.engine}:\n" +
                    run.failures.entries.joinToString("\n\n") { (out, why) -> "$out: $why" },
            )
        }
        return run.outputs.mapValues { (_, t) ->
            t.rows.map { row ->
                t.columns
                    .map { it.name }
                    .zip(row)
                    .toMap()
            }
        }
    }

    private fun keys(
        rows: List<Map<String, Any?>>?,
        key: String,
    ) = rows.orEmpty().map { (it[key] as Number).toInt() }

    /** Every route of [recipe] took exactly the keys the sample planted for it. */
    fun check(
        recipe: String,
        r: Map<String, List<Map<String, Any?>>>,
    ) {
        when (recipe) {
            "release_order.ttrp" -> {
                keys(r["verdict.hold_sales"], "order_number") shouldContainExactlyInAnyOrder listOf(3, 4, 7)
                keys(r["verdict.hold_ar"], "order_number") shouldContainExactlyInAnyOrder listOf(5)
                keys(r["verdict.release_confirmed"], "order_number") shouldContainExactlyInAnyOrder listOf(2)
                keys(r["verdict.release"], "order_number") shouldContainExactlyInAnyOrder listOf(1, 8)
                // the shortfall of 3 (3 on hand at the LATEST snapshot) and of 7 (its center is out); 4's price; 5's invoice
                keys(r["verdict.hold_sales.shortfall"], "order_number") shouldContainExactlyInAnyOrder listOf(3, 7)
                val shortfall = r.getValue("verdict.hold_sales.shortfall")
                shortfall.first { (it["order_number"] as Number).toInt() == 7 }["outage"] shouldBe true
                shortfall.first { (it["order_number"] as Number).toInt() == 3 }["outage"] shouldBe false
                keys(r["verdict.hold_sales.price_differences"], "order_number") shouldContainExactlyInAnyOrder listOf(4)
                keys(r["verdict.hold_ar.overdue"], "order_number") shouldContainExactlyInAnyOrder listOf(5)
                r.getValue("verdict.hold_ar").single()["verdict_reason"] shouldBe "overdue invoices"
                r.getValue("verdict.hold_ar").single()["route"] shouldBe "hold_ar"
            }
            "decide_return.ttrp" -> {
                // 907: order 11 is delivered and its customer's, but carries no item 103 (review-119 B1)
                keys(r["way.reject_not_ours"], "line_id") shouldContainExactlyInAnyOrder listOf(903, 907)
                keys(r["way.reject_late"], "line_id") shouldContainExactlyInAnyOrder listOf(902)
                keys(r["way.reject_abuse"], "line_id") shouldContainExactlyInAnyOrder listOf(906)
                keys(r["way.to_customer_care"], "line_id") shouldContainExactlyInAnyOrder listOf(905)
                keys(r["way.refund_no_return"], "line_id") shouldContainExactlyInAnyOrder listOf(901)
                keys(r["way.replacement_no_return"], "line_id").shouldBeEmpty()
                keys(r["way.exchange"], "line_id").shouldBeEmpty()
                keys(r["way.refund_after_return"], "line_id") shouldContainExactlyInAnyOrder listOf(904)
                r.getValue("way.reject_late").single()["verdict_reason"] shouldBe "delivered more than 30 days ago"
            }
            "monitor_inbound.ttrp" -> {
                keys(r["inbound.complete"], "line_id") shouldContainExactlyInAnyOrder listOf(913)
                keys(r["inbound.damaged"], "line_id") shouldContainExactlyInAnyOrder listOf(914)
                keys(r["inbound.expired"], "line_id") shouldContainExactlyInAnyOrder listOf(912)
                // 916: created at midnight 30 days back — the days are `(a, b]`, so not yet expired (review-119 m1)
                keys(r["inbound.remind"], "line_id") shouldContainExactlyInAnyOrder listOf(911, 916)
                keys(r["inbound.complete.receipts"], "line_id") shouldContainExactlyInAnyOrder listOf(913)
                keys(r["inbound.damaged.receipts"], "line_id") shouldContainExactlyInAnyOrder listOf(914)
                r.keys.none { it.endsWith(".else") } shouldBe true
            }
            "settle.ttrp" -> {
                keys(r["settlement.credit_note_issued"], "line_id") shouldContainExactlyInAnyOrder listOf(923)
                keys(r["settlement.money_received"], "line_id") shouldContainExactlyInAnyOrder listOf(923)
                keys(r["settlement.goods_shipped"], "line_id") shouldContainExactlyInAnyOrder listOf(922)
                keys(r["settlement.money_overdue"], "line_id") shouldContainExactlyInAnyOrder listOf(921, 924)
                keys(r["settlement.goods_overdue"], "line_id").shouldBeEmpty()
                keys(r["settlement.closed"], "line_id") shouldContainExactlyInAnyOrder listOf(922, 923)
                keys(r["settlement.money_overdue.open_obligations"], "line_id") shouldContainExactlyInAnyOrder
                    listOf(921, 924)
                val late = r.getValue("settlement.money_overdue").associateBy { (it["line_id"] as Number).toInt() }
                // 25 Oct → 2 Nov: 26, 27, 29, 30 Oct and 2 Nov (the 28th is a holiday, the 31st and 1st the weekend)
                late.getValue(921)["verdict_reason"] shouldBe "refund 5 working days late"
                // 27 Oct (a working day) → 2 Nov: 29, 30 Oct and 2 Nov — the 27th itself is not counted, `(a, b]`
                late.getValue(924)["verdict_reason"] shouldBe "refund 3 working days late"
                keys(r["overdue_days.rows"], "line_id") shouldContainExactlyInAnyOrder listOf(921, 922, 923, 924)
            }
            else -> error("no planted keys for $recipe")
        }
    }
}
