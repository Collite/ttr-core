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
        "each unresolved or unsupported query is omitted with its own diagnostic and supported queries survive" {
            val result =
                project(
                    query("bad", "SELECT * FROM er.entity.missing"),
                    query("bad_type", "SELECT {payload} AS payload", listOf(QueryParameterDef("payload", "json"))),
                    query("good", "SELECT id FROM db.dbo.metrics"),
                )
            result.queries.map { it.qname } shouldBe listOf("db.query.good")
            result.omitted.map { it.qname } shouldBe listOf("db.query.bad", "db.query.bad_type")
            result.omitted.forEach { it.reason shouldContain "TTRP-EXPORT-QUERY: ${it.qname}:" }
            result.omitted.last().reason shouldContain "TTRP-EXPORT-TYPE"
        }
        "approval roles resolve a unique model group, refusing members, missing roles and ambiguity" {
            val action =
                AutomationAction("action", QualifiedName(SchemaCode.AUTOMATION, "action", "approve"), byRole = "owner")
            val role =
                AutomationRole(
                    "role",
                    QualifiedName(SchemaCode.AUTOMATION, "role", "owner"),
                    membership = RoleMembership.KeycloakGroup("hartland-process-owners"),
                )

            fun index(roles: List<AutomationRole>): org.tatrman.ttrp.resolve.ModelIndex {
                val model =
                    Model(
                        ModelDescriptor("roles", "roles"),
                        ModelVersion("1", Instant.EPOCH),
                        mapOf("automation" to AutomationSchema((roles + action).associateBy { it.qname })),
                        emptyList(),
                        emptyMap(),
                    )
                val snapshot =
                    org.tatrman.ttr.metadata.registry.RegistrySnapshot(
                        model,
                        org.tatrman.ttr.metadata.graph.ModelGraph
                            .build(model),
                        Instant.EPOCH,
                        emptyList(),
                    )
                return org.tatrman.ttrp.resolve
                    .ModelIndex(snapshot) { it.sourceFile.ifBlank { "hartland.automation" } }
            }
            ApprovalGroupsProjector.group(action, index(listOf(role))) shouldBe "hartland-process-owners"
            ApprovalGroupsProjector.group(action, index(listOf(role.copy(sourceFile = "another.automation")))) shouldBe
                "hartland-process-owners"
            for (roles in listOf(
                emptyList(),
                listOf(role.copy(membership = RoleMembership.Members(listOf("bora")))),
                listOf(role, role.copy(qname = role.qname.copy(`package` = "another"))),
            )) {
                shouldThrow<IllegalArgumentException> { ApprovalGroupsProjector.group(action, index(roles)) }
                    .message!! shouldContain "TTRP-EXPORT-ROLE: action approve: role.owner"
            }
        }
        "hartland's complete query set omits bool_or and projects the other 24 queries deterministically" {
            val root =
                java.nio.file.Path
                    .of("src/test/resources/fixtures/hartland-queries")
                    .toAbsolutePath()
            val snapshot =
                org.tatrman.ttr.metadata.source
                    .FileBasedSource(
                        "hartland",
                        0,
                        org.tatrman.ttr.metadata.source
                            .LocalFsStorage("hartland", root),
                    ).load()
            val model =
                org.tatrman.ttr.metadata.reconcile
                    .ModelReconciler(ModelDescriptor("fixture", "fixture"))
                    .reconcile(listOf(snapshot))
                    .model
            model.queries.size shouldBe 25

            fun result() = WorldChoicesProjector.project(model, world) { error("no deployments") }
            val result = result()
            result.queries.size shouldBe 24
            result.omitted.single().qname shouldBe "query.query.customer_channel_overlap"
            result.omitted.single().reason shouldContain "TTRP-EXPORT-QUERY: query.query.customer_channel_overlap:"
            result.omitted.single().reason shouldContain "bool_or"
            result shouldBe result()
        }
    })
