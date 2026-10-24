// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.source.LoadedFile
import org.tatrman.ttr.metadata.source.underRoot
import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.semantics.kindOf
import org.tatrman.ttr.semantics.modelForKind

/** A parsed definition's export identity; package is provenance, not part of the identity (XM C-3). */
data class DefinedObject(
    val qname: String,
    val model: String,
    val kind: String,
    val def: Definition,
    val pkg: String,
    val file: String,
    val schemaCode: String?,
    val locale: String? = null,
    val parentQname: String? = null,
)

/** Tier 0/1 source. Retains definitions even when the typed model has no representation for them. */
data class DefinitionSet(
    val entries: List<DefinedObject>,
    val areas: Map<String, AreaDef>,
    val world: String,
    val grammar: String,
    val snapshot: String,
) {
    companion object {
        fun from(
            files: List<LoadedFile>,
            model: Model?,
            grammar: String,
            snapshot: String,
        ): DefinitionSet {
            val entries = mutableListOf<DefinedObject>()
            for (file in files.sortedWith(compareBy(bytewise) { it.storageFile.underRoot() })) {
                fun add(
                    def: Definition,
                    parent: DefinedObject? = null,
                ) {
                    val astKind = kindOf(def)
                    val kind =
                        when (def) {
                            is ConceptDef -> def.stereotype
                            is ConceptBindingDef -> def.bindingKind
                            is LexiconEntryDef -> def.entryKind
                            else -> ExportKinds.astKinds.getValue(astKind).single()
                        }
                    val layer =
                        parent?.model ?: when {
                            def is AreaDef -> "area"
                            def is LexiconEntryDef -> "lexicon"
                            def is AttributeDef && file.schemaCode == "md" -> "md"
                            else ->
                                modelForKind(astKind, file.schemaCode).let {
                                    if (it ==
                                        "binding"
                                    ) {
                                        "bindings"
                                    } else {
                                        it
                                    }
                                }
                        }
                    val id =
                        parent?.let { "${it.qname}.${def.name}" } ?: when (layer) {
                            "bindings", "area" -> "$kind.${def.name}"
                            "db" -> "db.${file.namespace.ifEmpty { "dbo" }}.${def.name}"
                            else -> "$layer.$kind.${def.name}"
                        }
                    val entry =
                        DefinedObject(
                            id,
                            layer,
                            kind,
                            def,
                            file.declaredPackage ?: file.computedPackage,
                            file.storageFile.underRoot(),
                            file.namespace.takeIf { layer == "db" && it.isNotEmpty() },
                            file.locale,
                            parent?.qname,
                        )
                    entries += entry
                    when (def) {
                        is TableDef -> (def.columns + def.indices + def.constraints).forEach { add(it, entry) }
                        is ViewDef -> def.columns.forEach { add(it, entry) }
                        is EntityDef -> def.attributes.forEach { add(it, entry) }
                        is DimensionDef -> def.attributes.forEach { add(it, entry) }
                        is SchemaDef -> def.columns.forEach { add(it, entry) }
                        is ProcedureDef -> def.resultColumns.forEach { add(it, entry) }
                        is CubeletDef ->
                            def.measures.filterIsInstance<CubeletMeasure.Inline>().forEach {
                                add(
                                    it.measure,
                                )
                            }
                        else -> Unit
                    }
                }
                file.definitions.forEach { add(it) }
            }
            return DefinitionSet(
                entries.sortedWith(
                    compareBy<DefinedObject, String>(bytewise) { it.qname }.thenBy(bytewise) { it.kind },
                ),
                entries.mapNotNull { (it.def as? AreaDef)?.let { area -> area.name to area } }.toMap(),
                entries.firstOrNull { it.def is WorldDef }?.def?.name ?: model?.descriptor?.id ?: "world",
                grammar,
                snapshot,
            )
        }
    }
}

/** UTF-8 byte ordering, including names outside the BMP (C-4). */
internal val bytewise =
    Comparator<String> { a, b ->
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        var result = 0
        for (i in 0 until minOf(x.size, y.size)) {
            result = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255)
            if (result != 0) break
        }
        if (result == 0) x.size.compareTo(y.size) else result
    }
