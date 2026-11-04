// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.model.*
import org.tatrman.ttrp.emit.spi.ColumnMeta
import java.time.Instant

class WorldChoicesProjectorSpec :
    StringSpec({
        val world = World("world", QualifiedName(SchemaCode.WORLD, "world", "demo"))

        fun query(
            name: String,
            sql: String,
            params: List<QueryParameterDef> = emptyList(),
        ) = Query(
            name,
            QualifiedName(SchemaCode.DB, "query", name),
            sourceLanguage = "SQL",
            sourceText = sql,
            parameters = params,
        )

        val tableName = QualifiedName(SchemaCode.DB, "dbo", "metrics")
        val table =
            DbTable(
                "metrics",
                tableName,
                columns =
                    listOf(
                        DbColumn(
                            "id",
                            QualifiedName(SchemaCode.DB, "dbo", "metrics.id"),
                            table = tableName,
                            dataType = "int",
                        ),
                    ),
            )

        fun project(vararg queries: Query) =
            WorldChoicesProjector.project(
                Model(
                    ModelDescriptor(
                        "test",
                        "test",
                    ),
                    ModelVersion("1", Instant.EPOCH),
                    mapOf("db" to DbSchema(tables = mapOf(tableName to table))),
                    emptyList(),
                    queries.associateBy {
                        it.qname
                    },
                ),
                world,
            ) { error("no deployed programs") }

        "query output names and types come from the compiled row, preserving metadata parameters" {
            val param = QueryParameterDef("floor", "int", defaultValue = "7")
            val result =
                project(
                    query(
                        "totals",
                        "SELECT {floor} AS total, TRUE AS approved, 'ready' AS label FROM db.dbo.metrics",
                        listOf(param),
                    ),
                )
            val q = result.queries.single()
            q.qname shouldBe "db.query.totals"
            q.params.single().name shouldBe "floor"
            q.params.single().value shouldBe "7"
            q.columns shouldBe
                listOf(ColumnMeta("total", "number"), ColumnMeta("approved", "boolean"), ColumnMeta("label", "string"))
            result shouldBe
                project(
                    query(
                        "totals",
                        "SELECT {floor} AS total, TRUE AS approved, 'ready' AS label FROM db.dbo.metrics",
                        listOf(param),
                    ),
                )
        }
        "an unresolved deployed program refuses with its name before the host callback" {
            val model =
                Model(
                    ModelDescriptor("test", "test"),
                    ModelVersion("1", Instant.EPOCH),
                    emptyMap(),
                    emptyList(),
                    emptyMap(),
                )
            val deployment =
                WorldDeployment(
                    "d",
                    QualifiedName(SchemaCode.WORLD, "demo", "d"),
                    program = "missing",
                    target = "bpmn",
                    runAs = RunAsRef.Caller,
                )
            shouldThrow<IllegalArgumentException> {
                WorldChoicesProjector.project(
                    model,
                    world.copy(deployments = listOf(deployment)),
                ) { error("must not be called") }
            }.message!! shouldContain "TTRP-EXPORT-REF: unknown or ambiguous world program missing"
        }
        "unresolved query references and unsupported parameter types have named diagnostics" {
            shouldThrow<IllegalArgumentException> {
                project(query("bad", "SELECT * FROM er.entity.missing"))
            }.message!! shouldContain
                "TTRP-EXPORT-QUERY"
            shouldThrow<IllegalStateException> {
                project(query("bad_type", "SELECT :payload AS payload", listOf(QueryParameterDef("payload", "json"))))
            }.message!! shouldContain "TTRP-EXPORT-TYPE"
        }
    })
