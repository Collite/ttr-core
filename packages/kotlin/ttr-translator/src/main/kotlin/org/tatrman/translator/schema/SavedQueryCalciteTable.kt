// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.schema

import org.apache.calcite.DataContext
import org.apache.calcite.linq4j.Enumerable
import org.apache.calcite.linq4j.Linq4j
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.ScannableTable
import org.apache.calcite.schema.Schema
import org.apache.calcite.schema.impl.AbstractTable
import org.tatrman.translator.framework.AttributeOrColumnRef
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.wire.PlanNodeDecoder

/**
 * One saved query inside a [QueryNamespaceSchema]. Implements [ScannableTable]
 * with an empty body — the v1 pipeline never executes against Calcite directly;
 * tables are only used for type derivation and SQL parsing/unparsing.
 *
 * The row type is derived from [ModelHandle.savedQueryBody] output columns.
 * "Scan's output_columns win" — the declared output columns on the Scan are
 * authoritative; the body's actual row type may differ and is checked by
 * UNFOLD for cardinality mismatches.
 *
 * LQ (contract L-23): a body that declares **no** output columns — what a metadata worker serves, since it holds
 * the referenced query's stored plan and not a model ref for a computed column such as `SUM(…) AS revenue` — takes
 * its row type, names and types, from that plan, decoded against the same model.
 */
class SavedQueryCalciteTable(
    private val source: ModelSavedQuery,
    private val model: ModelHandle,
) : AbstractTable(),
    ScannableTable {
    /** The stored plan's own row type (L-23), decoded once; copied into each caller's type factory. */
    private val planRowType: RelDataType by lazy {
        PlanNodeDecoder.decode(model.savedQueryBody(source.qname).planNode, TranslatorFramework(model)).rowType
    }

    override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType {
        val builder = typeFactory.builder()
        val body = model.savedQueryBody(source.qname)
        if (body.outputColumns.isEmpty()) {
            for (field in planRowType.fieldList) builder.add(field.name, typeFactory.copyType(field.type))
            return builder.build()
        }
        for (col in body.outputColumns) {
            val fieldName =
                when (col) {
                    is AttributeOrColumnRef.Attr -> col.ref.name
                    is AttributeOrColumnRef.Col -> col.ref.name
                }
            val fieldType =
                when (col) {
                    is AttributeOrColumnRef.Attr -> {
                        val attrs = model.attributes(col.ref)
                        val surf = attrs.firstOrNull()?.surfaceType ?: SurfaceType.TEXT
                        TypeMapping.fromSurface(surf, typeFactory)
                    }
                    is AttributeOrColumnRef.Col -> {
                        val cols = model.columns(col.ref)
                        val surf = cols.firstOrNull()?.surfaceType ?: SurfaceType.TEXT
                        TypeMapping.fromSurface(surf, typeFactory)
                    }
                }
            builder.add(fieldName, fieldType)
        }
        return builder.build()
    }

    override fun scan(root: DataContext): Enumerable<Array<Any?>> = Linq4j.emptyEnumerable()

    override fun getJdbcTableType(): Schema.TableType = Schema.TableType.TABLE

    /** Public accessor — used by [Unfold] to recognise OBJ scans via `RelOptTable.unwrap`. */
    fun qname(): org.tatrman.plan.v1.QualifiedName = source.qname
}
