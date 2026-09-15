// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.MergeMode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.StoreNode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.ttr.md.resolve.CanonicalPath
import org.tatrman.ttr.md.resolve.Coordinate
import org.tatrman.ttr.md.resolve.MemberRef
import org.tatrman.ttr.md.resolve.PathShape
import org.tatrman.ttr.md.resolve.Selector
import org.tatrman.ttr.semantics.md.AggKind
import org.tatrman.ttr.semantics.md.AttrBinding
import org.tatrman.ttr.semantics.md.BindingShape
import org.tatrman.ttr.semantics.md.CubeletBinding
import org.tatrman.ttr.semantics.md.Journaling
import org.tatrman.ttr.semantics.md.MeasureBinding
import org.tatrman.ttr.semantics.md.fixtures.MdFixtures
import org.tatrman.ttrp.emit.sql.MaterializeLowering
import org.tatrman.ttrp.emit.sql.MdMergeDeleteLowering
import org.tatrman.ttrp.emit.sql.MdPathLowering
import org.tatrman.ttrp.emit.sql.MdWriteLowering
import org.tatrman.ttrp.emit.sql.WriteTechnical
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager

/**
 * MD dot-path S5-B — writeback **round-trip** on a live Postgres. Lowers a cubelet assignment to a
 * `plan.v1` [StoreNode] ([MdWriteLowering]), unparses it to DML ([Translator] → `StoreDmlUnparser`),
 * executes it on the seeded ttrp-pg, and reads the stored value back — asserting the write honoured its
 * cubelet's journaling mode:
 *  - **OVERWRITE** (`sales`/`f_sales`, wide) — `sales.name.Kaufland.day."2025-06-20".net = 500`: the
 *    day's two seed rows (SUM 85) are replaced, so the current value is 500.
 *  - **INVALIDATE** (`plan`/`f_plan`, long, `is_current`) — `plan.name.Kaufland.month.6.net = 999`: the
 *    prior live NET rows (SUM 150) are flipped to superseded and the new 999 row inserted live.
 *  - **DIFF** (`budget`/`f_budget`, wide, delta store) — `budget.name.Kaufland.month.6.net += 30`: a 30
 *    delta is appended, so the summed current value goes 150 → 180.
 *
 * This is the write counterpart of [org.tatrman.ttrp.bundle.MdConformLiveTest] (S4-B reads). It proves
 * the whole write path — pinned strict LHS → StoreNode → journaling-shaped DML → executed on a real
 * engine — computes the right stored state. Read-back is a plain verification SELECT replicating each
 * mode's read view (dot-path read lowering is covered by MdConformLiveTest on the same tables).
 *
 * Gated by `TTRP_CONFORM_PG=1`. The database comes from `TTR_CONN_ERP_PG` (`postgresql://user:pass@host:port/db`)
 * when set — the same variable CI hands MdConformLiveTest and HeroConformLiveTest; without it, the local
 * ttrp-pg container via the `spike.pg.*` properties of [org.tatrman.ttrp.conform.spike.PgDivergenceSpike].
 * Skips visibly otherwise.
 */
class MdWriteRoundTripTest :
    FunSpec({
        val enabled = System.getenv("TTRP_CONFORM_PG") == "1"

        val erpPg = System.getenv("TTR_CONN_ERP_PG")?.let { URI(it) }
        val erpUserInfo = erpPg?.userInfo?.split(":")
        val url =
            erpPg?.let { "jdbc:postgresql://${it.host}:${if (it.port > 0) it.port else 5432}${it.path}" }
                ?: System.getProperty("spike.pg.url", "jdbc:postgresql://localhost:55432/postgres")
        val user = erpUserInfo?.getOrNull(0) ?: System.getProperty("spike.pg.user", "postgres")
        val password = erpUserInfo?.getOrNull(1) ?: System.getProperty("spike.pg.password", "ttrp")

        // sales (wide/overwrite) + plan (long/invalidate) from the shared fixture; budget (wide/diff) added
        // here — the seed has no diff table, so the third journaling mode gets a purpose-built cubelet.
        val budget =
            CubeletBinding(
                cubelet = "budget",
                table = "db.dbo.f_budget",
                shape = BindingShape.Wide,
                attributes =
                    mapOf(
                        "Customer.name" to AttrBinding.Column("customer_name"),
                        "Time.month" to AttrBinding.Column("month_num"),
                    ),
                measures = mapOf("net" to MeasureBinding.Column("net")),
                journaling = Journaling.Diff,
            )
        val bindings = MdFixtures.salesBindings().let { it.copy(cubelets = it.cubelets + ("budget" to budget)) }
        val writer = MdWriteLowering(bindings, MdFixtures.salesModel())
        val translator = Translator(InMemoryModelHandle(emptyList()))

        fun pinned(
            attr: String,
            member: String,
        ) = Coordinate(attr.substringBefore('.'), attr, Selector.Pinned(MemberRef(member)))

        fun lhs(
            cubelet: String,
            coords: List<Coordinate>,
        ) = CanonicalPath(cubelet, coords, "net", AggKind.SUM)

        fun dml(store: StoreNode): String {
            val result =
                translator.unparseFromRelNode(
                    PlanNode.newBuilder().setStore(store).build(),
                    Language.SQL,
                    SqlDialect.POSTGRESQL,
                    optimize = true,
                )
            check(result is UnparseResult.Success) { "unparse failed: $result" }
            return result.output
        }

        test("assignment writeback honours journaling mode end-to-end on live Postgres") {
            if (!enabled) {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — live MD write round-trip not run.")
                return@test
            }
            val seed = Files.readString(Paths.get("src/test/resources/seed/md_seed.sql"))
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    st.execute(seed)
                    st.execute(
                        "DROP TABLE IF EXISTS f_budget; " +
                            "CREATE TABLE f_budget (customer_name text, month_num integer, net numeric); " +
                            "INSERT INTO f_budget VALUES ('Kaufland', 6, 100.00), ('Kaufland', 6, 50.00);",
                    )

                    fun sum(sql: String): BigDecimal =
                        st.executeQuery(sql).use { rs ->
                            rs.next()
                            rs.getBigDecimal(1) ?: BigDecimal.ZERO
                        }

                    // OVERWRITE — replace the day's value (85 → 500).
                    st.executeUpdate(
                        dml(
                            writer.lower(
                                lhs(
                                    "sales",
                                    listOf(pinned("Customer.name", "Kaufland"), pinned("Time.day", "2025-06-20")),
                                ),
                                MdWriteLowering.floatValue(500.0),
                                MergeMode.ASSIGN,
                            ),
                        ),
                    )
                    sum(
                        "SELECT SUM(net) FROM f_sales WHERE customer_name = 'Kaufland' AND sale_date = DATE '2025-06-20'",
                    ).compareTo(BigDecimal("500")) shouldBe 0

                    // INVALIDATE — supersede the prior live NET rows (150) with 999.
                    st.executeUpdate(
                        dml(
                            writer.lower(
                                lhs("plan", listOf(pinned("Customer.name", "Kaufland"), pinned("Time.month", "6"))),
                                MdWriteLowering.floatValue(999.0),
                                MergeMode.ASSIGN,
                            ),
                        ),
                    )
                    sum(
                        "SELECT SUM(amount) FROM f_plan WHERE customer_name = 'Kaufland' AND month_num = 6 " +
                            "AND measure_code = 'NET' AND is_current",
                    ).compareTo(BigDecimal("999")) shouldBe 0

                    // DIFF += — append a 30 delta (150 → 180).
                    st.executeUpdate(
                        dml(
                            writer.lower(
                                lhs("budget", listOf(pinned("Customer.name", "Kaufland"), pinned("Time.month", "6"))),
                                MdWriteLowering.floatValue(30.0),
                                MergeMode.ACCUMULATE,
                            ),
                        ),
                    )
                    sum(
                        "SELECT SUM(net) FROM f_budget WHERE customer_name = 'Kaufland' AND month_num = 6",
                    ).compareTo(BigDecimal("180")) shouldBe 0

                    // DIFF = — assign an absolute value on a delta store: append (value − current_sum) so the
                    // running sum lands AT 999, not accumulates to 1179 (T-R1-4). Current sum is 180.
                    st.executeUpdate(
                        dml(
                            writer.lower(
                                lhs("budget", listOf(pinned("Customer.name", "Kaufland"), pinned("Time.month", "6"))),
                                MdWriteLowering.floatValue(999.0),
                                MergeMode.ASSIGN,
                            ),
                        ),
                    )
                    sum(
                        "SELECT SUM(net) FROM f_budget WHERE customer_name = 'Kaufland' AND month_num = 6",
                    ).compareTo(BigDecimal("999")) shouldBe 0
                }
            }
        }

        test("R21 spread writeback (proportional + equal) executes on live Postgres") {
            if (!enabled) {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — live MD spread round-trip not run.")
                return@test
            }
            val seed = Files.readString(Paths.get("src/test/resources/seed/md_seed.sql"))
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    st.execute(seed)

                    fun sum(sql: String): BigDecimal =
                        st.executeQuery(sql).use { rs ->
                            rs.next()
                            rs.getBigDecimal(1) ?: BigDecimal.ZERO
                        }

                    // PROPORTIONAL — spread a coarse Customer.name total across the day rows ∝ their current
                    // values. Kaufland's seed net total is 1885; writing 3770 doubles every Kaufland row.
                    st.executeUpdate(
                        dml(
                            writer.lower(
                                lhs(
                                    "sales",
                                    listOf(
                                        pinned("Customer.name", "Kaufland"),
                                        Coordinate("Time", "Time.day", Selector.Star),
                                    ),
                                ),
                                MdWriteLowering.floatValue(3770.0),
                                MergeMode.ASSIGN,
                            ),
                        ),
                    )
                    // Total redistributed to exactly the coarse value; the proportion (ratio 2) is preserved.
                    sum("SELECT SUM(net) FROM f_sales WHERE customer_name = 'Kaufland'")
                        .compareTo(BigDecimal("3770")) shouldBe 0
                    sum(
                        "SELECT SUM(net) FROM f_sales WHERE customer_name = 'Kaufland' AND sale_date = DATE '2025-06-20'",
                    ).compareTo(BigDecimal("170")) shouldBe 0 // (60+25) × 2
                    // A different customer is untouched by the pinned-key spread.
                    sum("SELECT SUM(net) FROM f_sales WHERE customer_name = 'Lidl'")
                        .compareTo(BigDecimal("70")) shouldBe 0

                    // EQUAL — spread a coarse Customer.name total evenly across the Month restrict members
                    // (1..12). Writing 1200 lands 100 in each month; under invalidate the prior live NET rows
                    // (month 6 = 150, month 7 = 30) are superseded, so the current NET total is 12 × 100.
                    st.executeUpdate(
                        dml(
                            writer.lower(
                                lhs(
                                    "plan",
                                    listOf(
                                        pinned("Customer.name", "Kaufland"),
                                        Coordinate("Time", "Time.month", Selector.Star),
                                    ),
                                ),
                                MdWriteLowering.floatValue(1200.0),
                                MergeMode.ASSIGN,
                            ),
                        ),
                    )
                    sum(
                        "SELECT SUM(amount) FROM f_plan WHERE customer_name = 'Kaufland' " +
                            "AND measure_code = 'NET' AND is_current",
                    ).compareTo(BigDecimal("1200")) shouldBe 0
                    // Each month now holds exactly the even share; month 6's prior 150 was superseded.
                    sum(
                        "SELECT SUM(amount) FROM f_plan WHERE customer_name = 'Kaufland' AND month_num = 6 " +
                            "AND measure_code = 'NET' AND is_current",
                    ).compareTo(BigDecimal("100")) shouldBe 0
                    // A non-NET measure and a different customer are untouched.
                    sum(
                        "SELECT SUM(amount) FROM f_plan WHERE customer_name = 'Kaufland' AND measure_code = 'GROSS' " +
                            "AND is_current",
                    ).compareTo(BigDecimal("7")) shouldBe 0
                }
            }
        }

        // A materialized wide cubelet `mc` over `sales` — the generated binding (B.2a conventions):
        // grain columns `dimension_attribute`, table `db.dbo.md_mc`, overwrite journaling.
        val mc =
            CubeletBinding(
                cubelet = "mc",
                table = "db.dbo.md_mc",
                shape = BindingShape.Wide,
                attributes =
                    mapOf(
                        "Customer.name" to AttrBinding.Column("customer_name"),
                        "Time.day" to AttrBinding.Column("time_day"),
                    ),
                measures = mapOf("net" to MeasureBinding.Column("net")),
                journaling = Journaling.Overwrite,
            )
        val matBindings = bindings.let { it.copy(cubelets = it.cubelets + ("mc" to mc)) }
        val materialize = MaterializeLowering(matBindings, MdFixtures.salesModel())
        // Materialize's Store.input is a real READ (a TableScan of the source fact table), so — unlike the
        // Values-based slice writes — the translator must resolve `f_sales`. Register the read's referenced
        // tables (MdPathLowering.referencedTables) in the model handle.
        val matRead = MdPathLowering(matBindings, MdFixtures.salesModel())

        fun matDml(
            store: StoreNode,
            tables: List<org.tatrman.translator.framework.ModelTable>,
        ): String {
            val result =
                Translator(InMemoryModelHandle(tables)).unparseFromRelNode(
                    PlanNode.newBuilder().setStore(store).build(),
                    Language.SQL,
                    SqlDialect.POSTGRESQL,
                    optimize = true,
                )
            check(result is UnparseResult.Success) { "unparse failed: $result" }
            return result.output
        }

        test("materialize `mc := sales.name.*.day.*.net` creates + full-replaces the backing table") {
            if (!enabled) {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — live MD materialize round-trip not run.")
                return@test
            }
            val seed = Files.readString(Paths.get("src/test/resources/seed/md_seed.sql"))
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    st.execute(seed)
                    st.execute("DROP TABLE IF EXISTS md_mc")

                    fun sum(sql: String): BigDecimal =
                        st.executeQuery(sql).use { rs ->
                            rs.next()
                            rs.getBigDecimal(1) ?: BigDecimal.ZERO
                        }

                    // `mc := sales.name.*.day.*.net` — the whole sales cubelet read (group by name × day, SUM net).
                    val rhs =
                        CanonicalPath(
                            "sales",
                            listOf(
                                Coordinate("Customer", "Customer.name", Selector.Star),
                                Coordinate("Time", "Time.day", Selector.Star),
                            ),
                            "net",
                            AggKind.SUM,
                        )
                    val shape = PathShape(freeDims = listOf("Customer.name", "Time.day"))
                    val tables = matRead.referencedTables(rhs, shape)

                    st.execute(materialize.createTableDdl("mc"))
                    st.executeUpdate(matDml(materialize.lower("mc", rhs, shape), tables))

                    // The materialized table equals the full read: total net preserved, one row per (name, day).
                    sum("SELECT SUM(net) FROM md_mc")
                        .compareTo(sum("SELECT SUM(net) FROM f_sales")) shouldBe 0
                    sum("SELECT COUNT(*) FROM md_mc")
                        .compareTo(
                            sum("SELECT COUNT(*) FROM (SELECT 1 FROM f_sales GROUP BY customer_name, sale_date) g"),
                        ) shouldBe 0

                    // Re-materialize is idempotent (full-file replace, not append): DDL is a no-op, the row set
                    // is cleared and re-inserted — totals and row count unchanged, not doubled.
                    st.execute(materialize.createTableDdl("mc"))
                    st.executeUpdate(matDml(materialize.lower("mc", rhs, shape), tables))
                    sum("SELECT SUM(net) FROM md_mc")
                        .compareTo(sum("SELECT SUM(net) FROM f_sales")) shouldBe 0
                }
            }
        }

        val mergeDelete = MdMergeDeleteLowering(matBindings, MdFixtures.salesModel())

        test("merge `mc += sales.name.*.day.*.net` upserts; delete `mc -= sales.name.Lidl.day.*` removes") {
            if (!enabled) {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — live MD merge/delete round-trip not run.")
                return@test
            }
            val seed = Files.readString(Paths.get("src/test/resources/seed/md_seed.sql"))
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    st.execute(seed)
                    st.execute(
                        "DROP TABLE IF EXISTS md_mc; " +
                            "CREATE TABLE md_mc (customer_name text, time_day date, net numeric)",
                    )

                    fun sum(sql: String): BigDecimal =
                        st.executeQuery(sql).use { rs ->
                            rs.next()
                            rs.getBigDecimal(1) ?: BigDecimal.ZERO
                        }

                    // `mc += sales.name.*.day.*.net` — upsert every (name, day) cell into the empty table.
                    val mergeRhs =
                        CanonicalPath(
                            "sales",
                            listOf(
                                Coordinate("Customer", "Customer.name", Selector.Star),
                                Coordinate("Time", "Time.day", Selector.Star),
                            ),
                            "net",
                            AggKind.SUM,
                        )
                    val mergeShape = PathShape(freeDims = listOf("Customer.name", "Time.day"))
                    st.executeUpdate(
                        matDml(
                            mergeDelete.merge("mc", mergeRhs, mergeShape),
                            matRead.referencedTables(mergeRhs, mergeShape),
                        ),
                    )
                    sum("SELECT SUM(net) FROM md_mc")
                        .compareTo(sum("SELECT SUM(net) FROM f_sales")) shouldBe 0

                    // `mc -= sales.name.Lidl.day.*` — anti-join delete the Lidl grain cells (keys only).
                    val delRhs =
                        CanonicalPath(
                            "sales",
                            listOf(
                                Coordinate("Customer", "Customer.name", Selector.Pinned(MemberRef("Lidl"))),
                                Coordinate("Time", "Time.day", Selector.Star),
                            ),
                            "net",
                            AggKind.SUM,
                        )
                    val delShape = PathShape(freeDims = listOf("Time.day"))
                    st.executeUpdate(
                        matDml(mergeDelete.delete("mc", delRhs, delShape), matRead.referencedTables(delRhs, delShape)),
                    )
                    sum("SELECT COUNT(*) FROM md_mc WHERE customer_name = 'Lidl'")
                        .compareTo(BigDecimal.ZERO) shouldBe 0
                    sum("SELECT SUM(net) FROM md_mc")
                        .compareTo(sum("SELECT SUM(net) FROM f_sales WHERE customer_name <> 'Lidl'")) shouldBe 0
                }
            }
        }

        test("merge stamps the authored_by / written_at technical columns (R31)") {
            if (!enabled) {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — live MD technical-column fill not run.")
                return@test
            }
            // A materialized wide cubelet `mt` whose backing table declares authored_by / written_at columns.
            val mt =
                CubeletBinding(
                    cubelet = "mt",
                    table = "db.dbo.md_mt",
                    shape = BindingShape.Wide,
                    attributes =
                        mapOf(
                            "Customer.name" to AttrBinding.Column("customer_name"),
                            "Time.day" to AttrBinding.Column("time_day"),
                        ),
                    measures = mapOf("net" to MeasureBinding.Column("net")),
                    journaling = Journaling.Overwrite,
                )
            val techBindings = matBindings.let { it.copy(cubelets = it.cubelets + ("mt" to mt)) }
            val techMerge = MdMergeDeleteLowering(techBindings, MdFixtures.salesModel())
            val techRead = MdPathLowering(techBindings, MdFixtures.salesModel())

            val seed = Files.readString(Paths.get("src/test/resources/seed/md_seed.sql"))
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    st.execute(seed)
                    st.execute(
                        "DROP TABLE IF EXISTS md_mt; " +
                            "CREATE TABLE md_mt (customer_name text, time_day date, net numeric, " +
                            "author text, at timestamp)",
                    )

                    val rhs =
                        CanonicalPath(
                            "sales",
                            listOf(
                                Coordinate("Customer", "Customer.name", Selector.Star),
                                Coordinate("Time", "Time.day", Selector.Star),
                            ),
                            "net",
                            AggKind.SUM,
                        )
                    val shape = PathShape(freeDims = listOf("Customer.name", "Time.day"))
                    val technical =
                        WriteTechnical.fromRoleColumns(
                            mapOf("authored_by" to "author", "written_at" to "at"),
                            authoredBy = "run-42",
                            writtenAt = "2026-07-20T12:00:00Z",
                        )
                    st.executeUpdate(
                        matDml(techMerge.merge("mt", rhs, shape, technical), techRead.referencedTables(rhs, shape)),
                    )

                    // Every written row carries the run identity + write clock.
                    st
                        .executeQuery(
                            "SELECT COUNT(*), COUNT(DISTINCT author), MIN(author), " +
                                "COUNT(*) FILTER (WHERE at = TIMESTAMP '2026-07-20 12:00:00') FROM md_mt",
                        ).use { rs ->
                            rs.next()
                            val rows = rs.getInt(1)
                            (rows > 0) shouldBe true
                            rs.getInt(2) shouldBe 1 // one distinct author
                            rs.getString(3) shouldBe "run-42"
                            rs.getInt(4) shouldBe rows // written_at stamped on every row
                        }
                }
            }
        }

        test("delete `plan -= plan.name.Kaufland.month.6` flips the live rows (invalidate valid-flip)") {
            if (!enabled) {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — live MD invalidate-delete not run.")
                return@test
            }
            val seed = Files.readString(Paths.get("src/test/resources/seed/md_seed.sql"))
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    st.execute(seed)

                    fun sum(sql: String): BigDecimal =
                        st.executeQuery(sql).use { rs ->
                            rs.next()
                            rs.getBigDecimal(1) ?: BigDecimal.ZERO
                        }

                    // Prior live NET for Kaufland/month 6 is 150 (the seeded value the read path asserts).
                    sum(
                        "SELECT SUM(amount) FROM f_plan WHERE customer_name = 'Kaufland' AND month_num = 6 " +
                            "AND measure_code = 'NET' AND is_current",
                    ).compareTo(BigDecimal("150")) shouldBe 0

                    // `plan -= plan.name.Kaufland.month.6` — both coords pinned → flip the whole grain cell live.
                    val delRhs =
                        CanonicalPath(
                            "plan",
                            listOf(
                                Coordinate("Customer", "Customer.name", Selector.Pinned(MemberRef("Kaufland"))),
                                Coordinate("Time", "Time.month", Selector.Pinned(MemberRef("6"))),
                            ),
                            "net",
                            AggKind.SUM,
                        )
                    val delShape = PathShape(freeDims = emptyList())
                    st.executeUpdate(
                        matDml(
                            mergeDelete.delete("plan", delRhs, delShape),
                            matRead.referencedTables(delRhs, delShape),
                        ),
                    )
                    // The Kaufland/month-6 grain cell is no longer live (all its measures superseded).
                    sum(
                        "SELECT COALESCE(SUM(amount), 0) FROM f_plan WHERE customer_name = 'Kaufland' " +
                            "AND month_num = 6 AND is_current",
                    ).compareTo(BigDecimal.ZERO) shouldBe 0
                }
            }
        }

        test("proportional spread over an all-zero key leaves the rows untouched, never NULL (T-R1-5)") {
            if (!enabled) {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — live MD spread-zero not run.")
                return@test
            }
            val seed = Files.readString(Paths.get("src/test/resources/seed/md_seed.sql"))
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    st.execute(seed)
                    // Zero out Lidl's rows: a coarse value has no proportions to distribute by.
                    st.executeUpdate("UPDATE f_sales SET net = 0 WHERE customer_name = 'Lidl'")

                    fun scalar(sql: String): BigDecimal =
                        st.executeQuery(sql).use { rs ->
                            rs.next()
                            rs.getBigDecimal(1) ?: BigDecimal.ZERO
                        }

                    st.executeUpdate(
                        dml(
                            writer.lower(
                                lhs(
                                    "sales",
                                    listOf(
                                        pinned("Customer.name", "Lidl"),
                                        Coordinate("Time", "Time.day", Selector.Star),
                                    ),
                                ),
                                MdWriteLowering.floatValue(500.0),
                                MergeMode.ASSIGN,
                            ),
                        ),
                    )
                    // Rows stay 0 (untouched) — NOT set to NULL as the old `/ NULLIF(_tot.s, 0)` did.
                    scalar(
                        "SELECT SUM(net) FROM f_sales WHERE customer_name = 'Lidl'",
                    ).compareTo(BigDecimal.ZERO) shouldBe
                        0
                    scalar("SELECT COUNT(*) FROM f_sales WHERE customer_name = 'Lidl' AND net IS NULL")
                        .compareTo(BigDecimal.ZERO) shouldBe 0
                }
            }
        }

        test("materialize into an invalidate-journaled target reads back non-empty (T-R1-6)") {
            if (!enabled) {
                System.err.println("SKIP: TTRP_CONFORM_PG != 1 — live MD invalidate-materialize not run.")
                return@test
            }
            // A materialized wide cubelet with invalidate journaling (is_current valid flag).
            val mci =
                mc.copy(
                    cubelet = "mci",
                    table = "db.dbo.md_mci",
                    journaling = Journaling.Invalidate(validColumn = "is_current"),
                )
            val mciBindings = matBindings.let { it.copy(cubelets = it.cubelets + ("mci" to mci)) }
            val mciMaterialize = MaterializeLowering(mciBindings, MdFixtures.salesModel())
            val mciRead = MdPathLowering(mciBindings, MdFixtures.salesModel())

            val seed = Files.readString(Paths.get("src/test/resources/seed/md_seed.sql"))
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { st ->
                    st.execute(seed)
                    st.execute("DROP TABLE IF EXISTS md_mci")

                    fun sum(sql: String): BigDecimal =
                        st.executeQuery(sql).use { rs ->
                            rs.next()
                            rs.getBigDecimal(1) ?: BigDecimal.ZERO
                        }

                    val rhs =
                        CanonicalPath(
                            "sales",
                            listOf(
                                Coordinate("Customer", "Customer.name", Selector.Star),
                                Coordinate("Time", "Time.day", Selector.Star),
                            ),
                            "net",
                            AggKind.SUM,
                        )
                    val shape = PathShape(freeDims = listOf("Customer.name", "Time.day"))
                    st.execute(mciMaterialize.createTableDdl("mci"))
                    st.executeUpdate(
                        matDml(mciMaterialize.lower("mci", rhs, shape), mciRead.referencedTables(rhs, shape)),
                    )

                    // The invalidate read view filters is_current = true; without the valid-flag stamp every
                    // row would land is_current = NULL and the view would return 0 rows.
                    (sum("SELECT COUNT(*) FROM md_mci WHERE is_current") > BigDecimal.ZERO) shouldBe true
                    sum("SELECT SUM(net) FROM md_mci WHERE is_current")
                        .compareTo(sum("SELECT SUM(net) FROM f_sales")) shouldBe 0
                }
            }
        }
    })
