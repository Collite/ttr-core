// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.tatrman.plan.v1.QualifiedName

import org.tatrman.plan.v1.SchemaCode

/**
 * In-memory [ModelHandle] for tests. Constructs the model from a list of
 * [ModelTable]s and a list of [ModelForeignKey]s. Resolution is by
 * `(schemaCode, namespace)`.
 */
class InMemoryModelHandle(
    private val tables: List<ModelTable>,
    private val foreignKeys: List<ModelForeignKey> = emptyList(),
    private val version: String = "test-v0",
    private val entities: List<ModelEntity> = emptyList(),
    private val relations: List<ModelRelation> = emptyList(),
    private val entityMappings: Map<QualifiedName, EntityMapping> = emptyMap(),
    private val savedQueries: List<ModelSavedQuery> = emptyList(),
    private val savedQueryBodies: Map<QualifiedName, SavedQueryBody> = emptyMap(),
    private val attributeRenames: Map<QualifiedName, Map<String, String>> = emptyMap(),
) : ModelHandle {
    override fun tables(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelTable> =
        tables
            .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
            .associateBy { it.qname }

    override fun columns(tableQname: QualifiedName): List<ModelColumn> =
        tables.firstOrNull { it.qname == tableQname }?.columns
            ?: emptyList()

    override fun foreignKeys(): List<ModelForeignKey> = foreignKeys

    override fun currentVersion(): String = version

    override fun entities(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelEntity> =
        entities
            .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
            .associateBy { it.qname }

    override fun attributes(entityQname: QualifiedName): List<ModelAttribute> =
        entities.firstOrNull { it.qname == entityQname }?.attributes
            ?: emptyList()

    override fun relations(): List<ModelRelation> = relations

    override fun entityMapping(entityQname: QualifiedName): EntityMapping? = entityMappings[entityQname]

    override fun attributeColumnRenames(entityQname: QualifiedName): Map<String, String> =
        attributeRenames[entityQname] ?: emptyMap()

    override fun savedQueries(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelSavedQuery> =
        savedQueries
            .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
            .associateBy { it.qname }

    override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody =
        savedQueryBodies[queryQname] ?: error("Unknown query $queryQname")

    override fun namespaces(schemaCode: SchemaCode): Set<String> =
        when (schemaCode) {
            SchemaCode.DB -> tables.mapTo(mutableSetOf()) { it.qname.namespace }
            SchemaCode.ER -> entities.mapTo(mutableSetOf()) { it.qname.namespace }
            SchemaCode.OBJ -> savedQueries.mapTo(mutableSetOf()) { it.qname.namespace }
            else -> emptySet()
        }

    companion object {
        /**
         * TF-P0 — builds a handle from the translator-harness model shape
         * `{"tables": {"<TABLE>": {"<COLUMN>": "INT|TEXT|FLOAT|DATETIME|BOOL"}}}`: every table under
         * `db.dbo`, every column nullable, no keys (exactly what `Harness2.java` builds).
         */
        fun fromJson(
            json: String,
            version: String = "legacy-v0",
        ): InMemoryModelHandle {
            val root =
                com.fasterxml.jackson.databind
                    .ObjectMapper()
                    .readTree(json)
            val tablesNode =
                requireNotNull(root.get("tables")) { "model JSON has no 'tables' object" }
            val tables =
                tablesNode.properties().map { (tableName, columnsNode) ->
                    ModelTable(
                        qname =
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(SchemaCode.DB)
                                .setNamespace("dbo")
                                .setName(tableName)
                                .build(),
                        columns =
                            columnsNode.properties().map { (columnName, typeNode) ->
                                val type =
                                    requireNotNull(SurfaceType.fromTag(typeNode.asText())) {
                                        "unknown surface type '${typeNode.asText()}' for $tableName.$columnName"
                                    }
                                ModelColumn(columnName, type, nullable = true)
                            },
                    )
                }
            return InMemoryModelHandle(tables, version = version)
        }
    }
}

/**
 * Tiny canonical fixture. Two tables under `db.dbo`:
 *   customers(id BIGINT PK, name VARCHAR, signup TIMESTAMP)
 *   orders(id BIGINT PK, customer_id BIGINT FK, total DECIMAL(19,5))
 */
object FixtureModel {
    val customersQname: QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
            .setNamespace("dbo")
            .setName("customers")
            .build()
    val ordersQname: QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
            .setNamespace("dbo")
            .setName("orders")
            .build()

    val customers =
        ModelTable(
            qname = customersQname,
            columns =
                listOf(
                    ModelColumn("id", SurfaceType.INT, nullable = false),
                    ModelColumn("name", SurfaceType.TEXT, nullable = true),
                    ModelColumn("signup", SurfaceType.DATETIME, nullable = true),
                ),
            primaryKey = listOf("id"),
        )

    val orders =
        ModelTable(
            qname = ordersQname,
            columns =
                listOf(
                    ModelColumn("id", SurfaceType.INT, nullable = false),
                    ModelColumn("customer_id", SurfaceType.INT, nullable = false),
                    ModelColumn(
                        "total",
                        SurfaceType.FLOAT,
                        nullable = true,
                        physicalType = PhysicalType(PhysicalType.Kind.DECIMAL, precision = 19, scale = 5),
                    ),
                ),
            primaryKey = listOf("id"),
        )

    val customerEntityQname: QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("entity")
            .setName("customer")
            .build()

    val customerEntity =
        ModelEntity(
            qname = customerEntityQname,
            attributes =
                listOf(
                    ModelAttribute("id", SurfaceType.INT, nullable = false),
                    ModelAttribute("name", SurfaceType.TEXT, nullable = true),
                ),
        )

    fun handle(): InMemoryModelHandle = InMemoryModelHandle(listOf(customers, orders))

    fun handleWithEntities(): InMemoryModelHandle =
        InMemoryModelHandle(
            tables = listOf(customers, orders),
            entities = listOf(customerEntity),
        )

    private fun dboTable(
        name: String,
        vararg columns: Pair<String, SurfaceType>,
    ): ModelTable =
        ModelTable(
            qname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName(name)
                    .build(),
            columns = columns.map { (n, t) -> ModelColumn(n, t, nullable = true) },
        )

    /**
     * TF-P0 — the translator-fidelity probe model (`translator-harness/mini-model.json`):
     * `A(ID INT, NAME TEXT, B_ID INT)` and `B(ID INT, NAME TEXT)` under `db.dbo`, all nullable, no
     * keys. The two tables deliberately share `ID` and `NAME` (G A3).
     */
    fun tfHandle(): InMemoryModelHandle =
        InMemoryModelHandle(
            tables =
                listOf(
                    dboTable("A", "ID" to SurfaceType.INT, "NAME" to SurfaceType.TEXT, "B_ID" to SurfaceType.INT),
                    dboTable("B", "ID" to SurfaceType.INT, "NAME" to SurfaceType.TEXT),
                ),
            version = "legacy-v0",
        )
}
