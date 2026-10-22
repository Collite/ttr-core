// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.md

import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.diagnostics.DiagnosticSeverity
import org.tatrman.ttr.parser.model.DimensionDef
import org.tatrman.ttr.parser.model.Md2erCubeletDef
import org.tatrman.ttr.parser.model.Md2erDimensionDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.RelationDef
import org.tatrman.ttr.semantics.ResolutionContext
import org.tatrman.ttr.semantics.ResolutionResult
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolEntry
import org.tatrman.ttr.semantics.ValidationDiagnostic

fun resolveMdErRef(
    resolver: Resolver,
    doc: SemanticDocument,
    path: String,
): SymbolEntry? =
    (
        resolver.resolveReference(
            Resolver.Ref(path, path.split('.')),
            ResolutionContext(doc.schemaCode, doc.namespace, null, doc.imports, doc.packageName),
        ) as? ResolutionResult.Resolved
    )?.symbol

/** Resolve a direct attribute or exactly one named outgoing relation. Preserve path count for ambiguity. */
fun explicitErAttributes(
    docs: List<SemanticDocument>,
    resolver: Resolver,
    entity: SymbolEntry,
    path: String,
): List<SymbolEntry> {
    val parts = path.split('.')
    if (parts.size ==
        1
    ) {
        return listOfNotNull(resolver.getSymbol("${entity.qname}.$path")?.takeIf { it.kind == "attribute" })
    }
    if (parts.size != 2) return emptyList()
    return docs.flatMap { doc ->
        doc.definitions.filterIsInstance<RelationDef>().filter { it.name == parts[0] }.mapNotNull { rel ->
            val from = (rel.from as? PropertyValue.IdValue)?.ref?.path ?: return@mapNotNull null
            val to = (rel.to as? PropertyValue.IdValue)?.ref?.path ?: return@mapNotNull null
            if (resolveMdErRef(resolver, doc, from)?.qname != entity.qname) return@mapNotNull null
            val target = resolveMdErRef(resolver, doc, to) ?: return@mapNotNull null
            resolver.getSymbol("${target.qname}.${parts[1]}")?.takeIf { it.kind == "attribute" }
        }
    }
}

fun mdErBindingDiagnostics(
    docs: List<SemanticDocument>,
    resolver: Resolver,
): List<ValidationDiagnostic> {
    val out = mutableListOf<ValidationDiagnostic>()
    val seen = mutableSetOf<String>()
    for (doc in docs) {
        for (def in doc.definitions) {
            fun report(
                code: DiagnosticCode,
                message: String,
            ) {
                out += ValidationDiagnostic(code, DiagnosticSeverity.Error, message, def.source)
            }
            when (def) {
                is Md2erCubeletDef -> {
                    if (def.physicalProps.isNotEmpty()) {
                        report(
                            DiagnosticCode.MdMd2erPhysicalProp,
                            "md2er_cubelet is structural-only; remove: ${def.physicalProps.joinToString(", ")}",
                        )
                    }
                    val entity =
                        def.entity?.let { resolveMdErRef(resolver, doc, it.path) }?.takeIf {
                            it.kind ==
                                "entity"
                        }
                    if (entity == null) {
                        report(DiagnosticCode.MdMd2erReference, "Unresolved ER entity '${def.entity?.path}'")
                        continue
                    }
                    for ((measure, attr) in def.measures) {
                        if (resolver.getSymbol("${entity.qname}.$attr") ==
                            null
                        ) {
                            report(
                                DiagnosticCode.MdMd2erMeasureUnknown,
                                "Measure '$measure' names unknown attribute '$attr'",
                            )
                        }
                    }
                }
                is Md2erDimensionDef -> {
                    val dim =
                        def.dimensionRef?.let { resolveMdErRef(resolver, doc, it.path) }?.takeIf {
                            it.kind ==
                                "dimension"
                        }
                    if (dim ==
                        null
                    ) {
                        report(
                            DiagnosticCode.MdMd2erReference,
                            "Unresolved MD dimension '${def.dimensionRef?.path}'",
                        )
                    }
                    val logical =
                        dim?.let { s ->
                            docs.find { it.uri == s.documentUri }?.definitions?.filterIsInstance<DimensionDef>()?.find {
                                it.name ==
                                    s.name
                            }
                        }
                    if (logical?.key != null &&
                        logical.key !in def.attributes
                    ) {
                        report(DiagnosticCode.MdMd2erDimensionKey, "md2er_dimension must map key '${logical.key}'")
                    }
                    if (dim != null &&
                        !seen.add(dim.qname)
                    ) {
                        report(
                            DiagnosticCode.MdDuplicateBinding,
                            "Duplicate md2er_dimension for '${def.dimensionRef?.path}'",
                        )
                    }
                    val entity =
                        def.entity?.let { resolveMdErRef(resolver, doc, it.path) }?.takeIf {
                            it.kind ==
                                "entity"
                        }
                    if (entity == null) {
                        report(DiagnosticCode.MdMd2erReference, "Unresolved ER entity '${def.entity?.path}'")
                        continue
                    }
                    for (path in def.attributes.values) {
                        val attrs = explicitErAttributes(docs, resolver, entity, path)
                        if (attrs.size >
                            1
                        ) {
                            report(
                                DiagnosticCode.MdMd2erPathAmbiguous,
                                "ER attribute path '$path' is ambiguous from '${def.entity?.path}'",
                            )
                        }
                        if (attrs.isEmpty()) {
                            report(
                                DiagnosticCode.MdMd2erReference,
                                "Unresolved ER attribute path '$path' from '${def.entity?.path}'",
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
    return out
}
