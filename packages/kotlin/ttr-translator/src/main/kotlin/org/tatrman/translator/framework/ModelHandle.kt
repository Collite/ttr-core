// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * Read surface the query-translator library needs from the metadata service
 * (or any other model owner). Implementations capture a snapshot at construction
 * time and return consistent answers for the duration of a single query
 * compilation — the snapshot must NOT mutate while a [TranslatorFramework] holds
 * this handle.
 *
 * Consumers — the metadata service in production, [InMemoryModelHandle] in
 * tests — bridge the gap between this minimal Calcite-facing surface and the
 * richer model graph types defined in `metadata.proto`.
 */
interface ModelHandle {
    /**
     * Tables visible in `(schemaCode, namespace)`, e.g. `("db", "dbo")` for the
     * `db.dbo.*` namespace or `("er", "entity")` for the entity layer.
     */
    fun tables(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelTable>

    /** Columns of a single table. Order matters — drives positional column refs.
     * Returns an empty list if the table is not found. */
    fun columns(tableQname: QualifiedName): List<ModelColumn>

    /** All foreign keys in the model — used by the Joiner during EXPAND_JOINS. */
    fun foreignKeys(): List<ModelForeignKey>

    /** Entities visible in the given schema and namespace. */
    fun entities(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelEntity>

    /** Attributes of an entity. Returns an empty list if the entity is not found. */
    fun attributes(entityQname: QualifiedName): List<ModelAttribute>

    /** Logical relations between entities, used by EXPAND_JOINS(logical) in Stage 2. */
    fun relations(): List<ModelRelation>

    /** Entity to physical mapping. Returns null if no mapping is registered. */
    fun entityMapping(entityQname: QualifiedName): EntityMapping?

    /**
     * Per-attribute rename map for translating ER attribute references to DB column
     * references after [org.tatrman.translator.schema.MapToPhysical] rewrites
     * `Scan(ER, entity)` → `TableScan(DB, table)`.
     *
     * Returns `Map<attribute-name, db-column-name>` for [entityQname]. Only entries where
     * the attribute name differs from its backing column appear in the map — when the names
     * match, the entry is omitted and v1's "attribute name = column name" assumption applies.
     *
     * Default returns empty (v1.0 behavior). [SnapshotModelHandle] overrides to project
     * `ER2DB_ATTRIBUTE_MAPPING` entries from the metadata-service snapshot.
     *
     * Expression-target mappings (denormalised display columns) are not represented here —
     * they remain deferred to a future stage.
     */
    fun attributeColumnRenames(entityQname: QualifiedName): Map<String, String> = emptyMap()

    /** Saved queries for the future UNFOLD stage. */
    fun savedQueries(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelSavedQuery>

    /**
     * The body of a saved query.
     * @throws IllegalStateException if no saved query body exists for [queryQname].
     */
    fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody

    /**
     * Snapshot version of the model graph, used by Validator's
     * `verifyContext` (Section I) for staleness detection. Stable across all
     * methods of this handle's lifetime.
     */
    fun currentVersion(): String

    /**
     * Returns the set of namespaces present in the given schema kind.
     *
     * Used by the SchemaPlus adapter to enumerate the second level of the
     * Calcite schema tree. Result is the bare namespace string (e.g. "dbo",
     * "entity", "query"), without the schema-code prefix or any name suffix.
     *
     * Returns an empty set if no objects of this schema kind are loaded.
     */
    fun namespaces(schemaCode: SchemaCode): Set<String>

    /**
     * TF-P5 (G C1) — scalar functions the database exposes and the model declares (db* `functions:`),
     * callable in SQL against [schemaCode]/[namespace] (`("db", "dbo")` → `dbo.fn_price`). Default
     * empty, so existing handles keep compiling and see no functions.
     */
    fun functions(
        schemaCode: SchemaCode,
        namespace: String,
    ): List<ModelFunction> = emptyList()
}

/**
 * TF-P5 (G C1) — a scalar function the database exposes and the model declares. [qname] is
 * `(DB, <SQL schema>, <function name>)`; lookup is case-insensitive. [parameters] are positional and
 * validated by their surface family ([org.tatrman.translator.params.SurfaceTypeMapping.familyOf]);
 * [physicalReturn] carries precision/scale when the model gave them (`decimal(18,4)`).
 */
data class ModelFunction(
    val qname: QualifiedName,
    val parameters: List<SurfaceType>,
    val returns: SurfaceType,
    val physicalReturn: PhysicalType? = null,
)

/** A table or view in the model. */
data class ModelTable(
    val qname: QualifiedName,
    val columns: List<ModelColumn>,
    val primaryKey: List<String> = emptyList(),
)

data class ModelEntity(
    val qname: QualifiedName,
    val attributes: List<ModelAttribute> = emptyList(),
)

/**
 * One column. `surfaceType` is the DSL-level tag (text/int/float/bool/datetime);
 * `physicalType` carries the DB type for round-trip preservation
 * (varchar(90), decimal(19,5), …) and is null for purely logical columns.
 */
data class ModelColumn(
    val name: String,
    val surfaceType: SurfaceType,
    val nullable: Boolean = true,
    val physicalType: PhysicalType? = null,
)

data class ModelAttribute(
    val name: String,
    val surfaceType: SurfaceType,
    val nullable: Boolean = true,
    val isKey: Boolean = false,
)

data class ModelRelation(
    val fromEntity: QualifiedName,
    val toEntity: QualifiedName,
    val joinPairs: List<Pair<QualifiedName, QualifiedName>>,
)

sealed interface EntityMapping {
    data class ToTable(
        val table: QualifiedName,
        val whereFilter: Expression? = null,
    ) : EntityMapping

    data class ToQuery(
        val query: QualifiedName,
    ) : EntityMapping
}

data class ModelSavedQuery(
    val qname: QualifiedName,
)

data class ParamSpec(
    val name: String,
    val type: SurfaceType,
)

sealed interface AttributeOrColumnRef {
    data class Attr(
        val ref: QualifiedName,
    ) : AttributeOrColumnRef

    data class Col(
        val ref: QualifiedName,
    ) : AttributeOrColumnRef
}

data class SavedQueryBody(
    val planNode: PlanNode,
    val parameters: List<ParamSpec>,
    val outputColumns: List<AttributeOrColumnRef>,
)

/** DSL surface types — the v1 RelOp subset's type lattice. */
enum class SurfaceType {
    TEXT,
    INT,
    FLOAT,
    BOOL,
    DATETIME,
    ;

    companion object {
        fun fromTag(tag: String): SurfaceType? = entries.find { it.name.equals(tag, ignoreCase = true) }
    }
}

/**
 * Physical-type carrier — preserves DB-specific precision/scale/size when the
 * source schema is a real database. Discarded by the DSL layer; round-tripped
 * by SQL ↔ RelNode codecs.
 */
data class PhysicalType(
    val kind: Kind,
    val precision: Int? = null,
    val scale: Int? = null,
) {
    enum class Kind {
        VARCHAR,
        NVARCHAR,
        CHAR,
        DECIMAL,
        NUMERIC,
        INTEGER,
        BIGINT,
        FLOAT,
        DOUBLE,
        BOOLEAN,
        DATE,
        TIME,
        TIMESTAMP,
        BINARY,
    }
}

data class ModelForeignKey(
    val from: List<QualifiedName>,
    val to: List<QualifiedName>,
)
