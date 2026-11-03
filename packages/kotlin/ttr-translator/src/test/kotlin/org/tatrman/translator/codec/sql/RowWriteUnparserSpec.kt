// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.*
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.*
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** Same PG opt-in as ttrp-conform; unreachable configured PG fails, never silently skips. */
class RowWriteUnparserSpec :
    FunSpec({
        val uri = System.getenv("TTR_CONN_ERP_PG")?.let(::URI)
        val enabled = System.getenv("TTRP_CONFORM_PG") == "1"
        val schema = "cw_" + UUID.randomUUID().toString().replace("-", "")
        val target =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName("order")
                .build()

        val table =
            target
                .toBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("sales_order")
                .build()
        val renames =
            mapOf("id" to "so_order_number", "state" to "so_state", "held_for" to "so_held_for")
        val model =
            InMemoryModelHandle(
                tables =
                    listOf(
                        ModelTable(
                            table,
                            renames.values.map { ModelColumn(it, SurfaceType.TEXT) },
                            listOf("so_order_number"),
                        ),
                    ),
                entities = listOf(ModelEntity(target, renames.keys.map { ModelAttribute(it, SurfaceType.TEXT) })),
                entityMappings = mapOf(target to EntityMapping.ToTable(table)),
                attributeRenames = mapOf(target to renames),
            )
        val translator = Translator(model)

        fun connection(): Connection {
            val endpoint = requireNotNull(uri) { "TTRP_CONFORM_PG=1 requires TTR_CONN_ERP_PG" }
            val auth = endpoint.userInfo.split(":", limit = 2)
            val port = if (endpoint.port > 0) endpoint.port else 5432
            return DriverManager
                .getConnection(
                    "jdbc:postgresql://${endpoint.host}:$port${endpoint.path}",
                    auth[0],
                    auth[1],
                ).also { it.createStatement().use { s -> s.execute("SET search_path TO $schema") } }
        }

        fun col(name: String) = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()

        fun param(name: String) = Expression.newBuilder().setParameter(ParameterRef.newBuilder().setName(name)).build()

        fun text(value: String) =
            Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue(value).setType("text")).build()

        fun number(value: Long) =
            Expression.newBuilder().setLiteral(Literal.newBuilder().setIntValue(value).setType("int")).build()

        fun call(
            op: String,
            vararg args: Expression,
        ) = Expression
            .newBuilder()
            .setFunction(
                FunctionCall.newBuilder().setOperation(op).addAllOperands(args.toList()),
            ).build()

        fun binding(
            name: String,
            value: String,
        ) = ParameterBinding
            .newBuilder()
            .setName(
                name,
            ).setType("text")
            .setValue(Value.newBuilder().setStringValue(value))
            .build()

        fun insert(
            id: Long,
            state: String = "placed",
            absent: Boolean = false,
        ) = RowWrite
            .newBuilder()
            .setTarget(target)
            .setOp(RowWriteOp.INSERT)
            .setIfAbsent(absent)
            .addValues(ColumnValue.newBuilder().setName("id").setValue(number(id)))
            .addValues(ColumnValue.newBuilder().setName("state").setValue(text(state)))
            .build()

        fun update(
            from: String = "placed",
            id: Long = 1,
        ) = RowWrite
            .newBuilder()
            .setTarget(target)
            .setOp(RowWriteOp.UPDATE)
            .addValues(ColumnValue.newBuilder().setName("state").setValue(param("next")))
            .addReadBefore("state")
            .addReadBefore("held_for")
            .setWhere(call("and", call("eq", col("id"), number(id)), call("in", col("state"), text(from))))
            .build()

        fun sql(
            row: RowWrite,
            params: List<ParameterBinding> = emptyList(),
        ): RowWriteUnparser.Statement {
            val node = PlanNode.newBuilder().setRowWrites(RowWrites.newBuilder().addRows(row)).build()
            val parsed =
                translator.parseToRelNode(
                    node.toByteArray().toString(Charsets.ISO_8859_1),
                    Language.REL_NODE,
                    SchemaCode.DB,
                )
            if (parsed is ParseResult.Failure) throw RowWriteUnparseException(parsed.code, parsed.message)
            val result =
                translator.unparseFromRelNode(
                    (parsed as ParseResult.Success).plan,
                    Language.SQL,
                    SqlDialect.POSTGRESQL,
                    parameters = params,
                )
            if (result is UnparseResult.Failure) throw RowWriteUnparseException(result.code, result.message)
            result as UnparseResult.Success
            return RowWriteUnparser.Statement(result.output, result.parameters)
        }

        fun execute(
            c: Connection,
            stmt: RowWriteUnparser.Statement,
        ): Pair<Long, String?> =
            c.prepareStatement(stmt.sql).use { p ->
                stmt.parameters.forEachIndexed { index, b ->
                    val v = b.value
                    val value: Any? =
                        when {
                            v.isNull -> null
                            v.vCase == Value.VCase.INT_VALUE -> v.intValue
                            v.vCase == Value.VCase.STRING_VALUE -> v.stringValue
                            v.vCase == Value.VCase.BOOL_VALUE -> v.boolValue
                            v.vCase == Value.VCase.FLOAT_VALUE -> v.floatValue
                            else -> error("unexpected binding $b")
                        }
                    p.setObject(index + 1, value)
                }
                p.executeQuery().use { r ->
                    r.next() shouldBe true
                    r.metaData.getColumnType(1) shouldBe java.sql.Types.BIGINT
                    val result = r.getLong("rows_affected") to r.getString("prior")
                    r.next() shouldBe false
                    result
                }
            }
        beforeSpec {
            if (enabled) {
                connection().use { c ->
                    c.createStatement().use { s ->
                        s.execute("CREATE SCHEMA $schema")
                        s.execute(
                            "CREATE TABLE sales_order (so_order_number bigint PRIMARY KEY, so_state text, so_held_for text)",
                        )
                    }
                }
            }
        }
        afterSpec {
            if (enabled) {
                connection().use { c ->
                    c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
                }
            }
        }
        beforeTest {
            if (enabled) {
                connection().use { c ->
                    c.createStatement().use { it.execute("TRUNCATE sales_order") }
                }
            }
        }

        test("live PostgreSQL harness answers SELECT 1").config(enabled = enabled) {
            connection().use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT 1").use { r ->
                        r.next() shouldBe
                            true
                        ; r.getInt(1) shouldBe 1
                    }
                }
            }
        }
        test("INSERT binds literal data and if_absent conflict answers zero without error").config(enabled = enabled) {
            val payload = "placed'); DROP TABLE orders; --"
            val statement = sql(insert(1, payload))
            statement.sql shouldNotContain payload
            statement.sql shouldNotContain "placed"
            statement.parameters.map { it.value } shouldBe
                listOf(Value.newBuilder().setIntValue(1).build(), Value.newBuilder().setStringValue(payload).build())
            connection().use { c ->
                execute(c, statement) shouldBe (1L to null)
                execute(c, sql(insert(1, absent = true))) shouldBe (0L to null)
                c.createStatement().use { s ->
                    s.executeQuery("SELECT so_state FROM sales_order").use { r ->
                        r.next()
                        r.getString(1) shouldBe
                            payload
                    }
                }
            }
        }
        test(
            "CAS hit and miss both answer the locked prior, with bound parameters in SQL appearance order",
        ).config(enabled = enabled) {
            connection().use { c ->
                c.autoCommit = false
                execute(c, sql(insert(1))) shouldBe (1L to null)
                val change = sql(update(), listOf(binding("next", "held")))
                change.parameters.map { it.value } shouldBe
                    listOf(
                        Value.newBuilder().setIntValue(1).build(),
                        Value.newBuilder().setStringValue("held").build(),
                        Value.newBuilder().setIntValue(1).build(),
                        Value.newBuilder().setStringValue("placed").build(),
                    )
                execute(c, change) shouldBe (1L to "{\"state\":\"placed\",\"held_for\":null}")
                execute(c, change) shouldBe (0L to "{\"state\":\"held\",\"held_for\":null}")
                execute(c, sql(update(id = 99), listOf(binding("next", "held")))) shouldBe (0L to null)
                c.rollback()
            }
        }
        test("CAS miss keeps its row locked until transaction end").config(enabled = enabled) {
            connection().use { c -> execute(c, sql(insert(1, "held"))) }
            connection().use { owner ->
                connection().use { contender ->
                    owner.autoCommit = false
                    contender.autoCommit = false
                    execute(owner, sql(update(), listOf(binding("next", "cancelled")))) shouldBe
                        (0L to "{\"state\":\"held\",\"held_for\":null}")
                    contender.createStatement().use { it.execute("SET LOCAL lock_timeout = '200ms'") }
                    shouldThrow<SQLException> {
                        contender.createStatement().use {
                            it.executeUpdate("UPDATE sales_order SET so_state = 'cancelled' WHERE so_order_number = 1")
                        }
                    }.sqlState shouldBe
                        "55P03"
                    contender.rollback()
                    owner.commit()
                    contender.createStatement().use {
                        it.executeUpdate(
                            "UPDATE sales_order SET so_state = 'cancelled' WHERE so_order_number = 1",
                        ) shouldBe
                            1
                    }
                    contender.rollback()
                }
            }
        }
        test("DELETE answers its affected row count and null prior").config(enabled = enabled) {
            connection().use { c ->
                execute(c, sql(insert(1)))
                execute(c, sql(insert(2)))
                val delete =
                    RowWrite
                        .newBuilder()
                        .setTarget(
                            target,
                        ).setOp(RowWriteOp.DELETE)
                        .setWhere(call("eq", col("id"), number(1)))
                        .build()
                execute(c, sql(delete)) shouldBe (1L to null)
                execute(c, sql(delete)) shouldBe (0L to null)
            }
        }
        test("an SQL error rolls back earlier ordered row writes in the same transaction").config(enabled = enabled) {
            connection().use { c ->
                c.autoCommit = false
                execute(c, sql(insert(1)))
                shouldThrow<SQLException> { execute(c, sql(insert(1))) }.sqlState shouldBe "23505"
                c.rollback()
                c.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM sales_order").use { r ->
                        r.next()
                        r.getLong(1) shouldBe
                            0
                    }
                }
            }
        }
        test("every non-Postgres dialect refuses through Translator with the row-write diagnostic") {
            val translator = Translator(InMemoryModelHandle(tables = emptyList()))
            val node =
                PlanNode
                    .newBuilder()
                    .setRowWrites(
                        RowWrites.newBuilder().addRows(insert(1).toBuilder().setTarget(table)),
                    ).build()
            for (dialect in SqlDialect.entries.filter { it != SqlDialect.POSTGRESQL }) {
                translator
                    .unparseFromRelNode(node, Language.SQL, dialect)
                    .shouldBeInstanceOf<UnparseResult.Failure>()
                    .code shouldBe "row_writes_unsupported_dialect"
            }
        }
        test("multi-row plans lower as a batch but SQL unparse requires one RowWrite per call") {
            for (count in listOf(1, 2)) {
                val node =
                    PlanNode
                        .newBuilder()
                        .setRowWrites(
                            RowWrites.newBuilder().addAllRows((1..count).map { insert(it.toLong()) }),
                        ).build()
                val physical =
                    translator
                        .parseToRelNode(
                            node.toByteArray().toString(Charsets.ISO_8859_1),
                            Language.REL_NODE,
                            SchemaCode.DB,
                        ).shouldBeInstanceOf<ParseResult.Success>()
                        .plan
                physical.rowWrites.rowsCount shouldBe count
                val result = translator.unparseFromRelNode(physical, Language.SQL, SqlDialect.POSTGRESQL)
                if (count == 1) {
                    result.shouldBeInstanceOf<UnparseResult.Success>()
                } else {
                    result.shouldBeInstanceOf<UnparseResult.Failure>().code shouldBe "row_writes_one_row"
                }
            }
        }
        test("UPDATE and DELETE require every primary-key equality, even without read_before") {
            val physical =
                update()
                    .toBuilder()
                    .setTarget(table)
                    .clearReadBefore()
                    .clearWhere()
            val pk = "so_order_number"
            for (op in listOf(RowWriteOp.UPDATE, RowWriteOp.DELETE)) {
                val write = physical.clone().setOp(op)
                if (op == RowWriteOp.DELETE) write.clearValues()
                for (where in listOf(
                    call("in", col("so_state"), text("cancelled")),
                    call("eq", number(1), number(1)),
                    call("or", call("eq", col(pk), number(1)), call("eq", col(pk), number(2))),
                )) {
                    shouldThrow<RowWriteUnparseException> {
                        RowWriteUnparser.unparse(
                            write.clone().setWhere(where).build(),
                            SqlDialect.POSTGRESQL,
                            keyColumns = listOf(pk),
                        )
                    }.code shouldBe "row_writes_key"
                }
            }
        }
        test("a target without a primary key refuses with or without a prior read, including direct API calls") {
            val noKey = Translator(InMemoryModelHandle(tables = listOf(ModelTable(table, model.columns(table)))))
            for (op in listOf(RowWriteOp.UPDATE, RowWriteOp.DELETE)) {
                for (read in listOf(emptyList(), listOf("so_state"))) {
                    val row =
                        update()
                            .toBuilder()
                            .setTarget(table)
                            .setOp(op)
                            .clearReadBefore()
                            .addAllReadBefore(read)
                            .setWhere(
                                call(
                                    "and",
                                    call("eq", col("so_order_number"), number(12)),
                                    call("eq", col("version"), number(3)),
                                ),
                            )
                    if (op == RowWriteOp.DELETE) row.clearValues()
                    val node = PlanNode.newBuilder().setRowWrites(RowWrites.newBuilder().addRows(row)).build()
                    val refusal =
                        noKey
                            .unparseFromRelNode(node, Language.SQL, SqlDialect.POSTGRESQL)
                            .shouldBeInstanceOf<UnparseResult.Failure>()
                    refusal.code shouldBe "row_writes_key"
                    refusal.message shouldContain "sales_order"
                    shouldThrow<RowWriteUnparseException> {
                        RowWriteUnparser.unparse(row.build(), SqlDialect.POSTGRESQL)
                    }.code shouldBe "row_writes_key"
                }
            }
        }
        test("composite primary keys require both top-level equalities") {
            val keys = listOf("sl_order_number", "sl_line_no")
            val row = RowWrite.newBuilder().setTarget(table.toBuilder().setName("sales_line")).setOp(RowWriteOp.DELETE)
            val order = call("eq", col(keys[0]), number(1))
            val line = call("eq", number(2), col(keys[1]))
            val statement =
                RowWriteUnparser.unparse(
                    row.clone().setWhere(call("and", order, line)).build(),
                    SqlDialect.POSTGRESQL,
                    keyColumns = keys,
                )
            statement.parameters.size shouldBe 2
            for (where in listOf(order, call("or", order, line))) {
                val refusal =
                    shouldThrow<RowWriteUnparseException> {
                        RowWriteUnparser.unparse(
                            row.clone().setWhere(where).build(),
                            SqlDialect.POSTGRESQL,
                            keyColumns = keys,
                        )
                    }
                refusal.code shouldBe "row_writes_key"
                refusal.message shouldContain "sl_"
            }
        }
        test("prior_keys must match read_before and contain distinct non-empty keys") {
            val row = update().toBuilder().setTarget(table)
            for (keys in listOf(listOf("state"), listOf("state", "state"), listOf("state", ""))) {
                shouldThrow<RowWriteUnparseException> {
                    RowWriteUnparser.unparse(row.clone().addAllPriorKeys(keys).build(), SqlDialect.POSTGRESQL)
                }.code shouldBe "row_writes_prior"
            }
        }
        test("unsafe write shapes and unbound values refuse before producing SQL") {
            shouldThrow<RowWriteUnparseException> { sql(update().toBuilder().clearWhere().build()) }.code shouldBe
                "row_writes_where"
            shouldThrow<RowWriteUnparseException> { sql(update()) }.code shouldBe "row_writes_parameter"
            shouldThrow<RowWriteUnparseException> {
                sql(insert(1).toBuilder().setOp(RowWriteOp.ROW_WRITE_OP_UNSPECIFIED).build())
            }.code shouldBe
                "row_writes_op"
            shouldThrow<RowWriteUnparseException> {
                sql(
                    update()
                        .toBuilder()
                        .setWhere(
                            call("or", call("eq", col("id"), number(1)), call("eq", col("id"), number(2))),
                        ).build(),
                    listOf(binding("next", "held")),
                )
            }.code shouldBe
                "row_writes_key"
        }
    })
