// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.md

import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.diagnostics.DiagnosticSeverity
import org.tatrman.ttr.parser.model.CubeletDef
import org.tatrman.ttr.parser.model.CubeletMeasure
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
import org.tatrman.ttr.semantics.buildCanonicalKey

fun resolveMdErRef(
    resolver: Resolver,
    doc: SemanticDocument,
    path: String,
): SymbolEntry? {
    if (path.isEmpty()) return null
    if (doc.packageName.isNotEmpty() && path.substringBefore('.') in setOf("md", "er", "db")) {
        val qualified = "${doc.packageName}.$path"
        val same =
            resolver.resolveReference(
                Resolver.Ref(qualified, qualified.split('.')),
                ResolutionContext(doc.schemaCode, doc.namespace, null, doc.imports, doc.packageName),
            )
        if (same is ResolutionResult.Resolved && same.symbol.packageName == doc.packageName) {
            val local = same.symbol.qname.removePrefix("${doc.packageName}.")
            val parts = local.split('.')
            val physical =
                if (parts.firstOrNull() == "db" && parts.size >= 4) {
                    (parts.take(2) + parts.drop(3)).joinToString(".")
                } else {
                    local
                }
            if (local == path || physical == path) return same.symbol
        }
    }
    val result =
        resolver.resolveReference(
            Resolver.Ref(path, path.split('.')),
            ResolutionContext(doc.schemaCode, doc.namespace, null, doc.imports, doc.packageName),
        )
    return (result as? ResolutionResult.Resolved)?.symbol
}

private fun mdPaths(
    path: String,
    ns: String,
): List<String> {
    if (path.isEmpty()) return emptyList()
    val parts = path.split('.')
    val canonical =
        if (parts.first() == "md") {
            if (parts.getOrNull(1) == ns) path else (listOf("md", ns) + parts.drop(1)).joinToString(".")
        } else {
            "md.$ns.$path"
        }
    return listOf(canonical, path).distinct()
}

fun resolveMdNs(
    resolver: Resolver,
    doc: SemanticDocument,
    path: String,
    ns: String,
): SymbolEntry? =
    mdPaths(path, ns).firstNotNullOfOrNull { candidate ->
        resolveMdErRef(resolver, doc, candidate)?.takeIf {
            it.kind ==
                when (ns) {
                    "domain" -> "mdDomain"
                    "map" -> "mdMap"
                    else -> ns
                } &&
                it.schemaCode == "md"
        }
    }

fun resolveMdAttribute(
    resolver: Resolver,
    doc: SemanticDocument,
    path: String,
): SymbolEntry? =
    mdPaths(path, "dimension").firstNotNullOfOrNull { candidate ->
        resolveMdErRef(resolver, doc, candidate)?.takeIf { it.kind == "attribute" && it.schemaCode == "md" }
    }

data class MdErRelationLanding(
    val toEntityQname: String,
    val packageName: String,
    val qname: String,
)
typealias MdErRelationIndex = Map<String, Map<String, List<MdErRelationLanding>>>

/** Resolve each relation's endpoints once, in its own document. */
fun buildMdErRelationIndex(
    docs: List<SemanticDocument>,
    resolver: Resolver,
): MdErRelationIndex {
    val index = linkedMapOf<String, MutableMap<String, MutableList<MdErRelationLanding>>>()
    for (doc in docs) {
        for (rel in doc.definitions.filterIsInstance<RelationDef>()) {
            val from = resolveMdErRef(resolver, doc, (rel.from as? PropertyValue.IdValue)?.ref?.path.orEmpty())
            val to = resolveMdErRef(resolver, doc, (rel.to as? PropertyValue.IdValue)?.ref?.path.orEmpty())
            if (from?.kind != "entity" || to?.kind != "entity") continue
            val qname = buildCanonicalKey(doc.packageName, doc.namespace, "relation", listOf(rel.name), doc.schemaCode)
            index
                .getOrPut(from.qname) { linkedMapOf() }
                .getOrPut(rel.name) { mutableListOf() }
                .add(MdErRelationLanding(to.qname, doc.packageName, qname))
        }
    }
    return index
}

/** Count visible paths, even when two relations land on the same attribute. */
fun explicitErAttributes(
    index: MdErRelationIndex,
    bindingDoc: SemanticDocument,
    resolver: Resolver,
    entity: SymbolEntry,
    path: String,
    usedRelations: MutableSet<String>? = null,
): List<SymbolEntry> {
    val parts = path.split('.')
    if (parts.size == 1) {
        return listOfNotNull(resolver.getSymbol("${entity.qname}.$path")?.takeIf { it.kind == "attribute" })
    }
    if (parts.size != 2) return emptyList()
    return index[entity.qname]
        ?.get(parts[0])
        .orEmpty()
        .filter { rel ->
            rel.packageName == bindingDoc.packageName ||
                bindingDoc.imports.any { imp ->
                    if (imp.wildcard) imp.target.removeSuffix(".*") == rel.packageName else imp.target == rel.qname
                }
        }.mapNotNull { rel ->
            resolver
                .getSymbol("${rel.toEntityQname}.${parts[1]}")
                ?.takeIf { it.kind == "attribute" }
                ?.also { usedRelations?.add(rel.qname) }
        }
}

/** Relations actually traversed by a dimension binding, also used by unused-import checks. */
fun mdErUsedRelations(
    index: MdErRelationIndex,
    doc: SemanticDocument,
    resolver: Resolver,
): Set<String> {
    val used = mutableSetOf<String>()
    for (def in doc.definitions.filterIsInstance<Md2erDimensionDef>()) {
        val entity = resolveMdErRef(resolver, doc, def.entity?.path.orEmpty()) ?: continue
        if (entity.kind != "entity") continue
        for (path in def.attributes.values) explicitErAttributes(index, doc, resolver, entity, path, used)
    }
    return used
}

fun cubeletMeasureKeys(
    docs: List<SemanticDocument>,
    resolver: Resolver,
    cubelet: SymbolEntry,
): Set<String> {
    val doc = docs.find { it.uri == cubelet.documentUri } ?: return emptySet()
    val def = doc.definitions.filterIsInstance<CubeletDef>().find { it.name == cubelet.name } ?: return emptySet()
    // Inline measures have no project symbol and must not borrow a top-level measure's identity.
    return def.measures
        .filterIsInstance<CubeletMeasure.Ref>()
        .mapNotNull { resolveMdNs(resolver, doc, it.ref.path, "measure")?.qname }
        .toSet()
}

fun mdErBindingDiagnostics(
    docs: List<SemanticDocument>,
    resolver: Resolver,
): List<ValidationDiagnostic> {
    val out = mutableListOf<ValidationDiagnostic>()
    val seen = mutableSetOf<String>()
    val index = buildMdErRelationIndex(docs, resolver)
    val byUri = docs.associateBy { it.uri }
    for (doc in docs) {
        for (def in doc.definitions) {
            fun report(
                code: DiagnosticCode,
                message: String,
            ) {
                out += ValidationDiagnostic(code, DiagnosticSeverity.Error, message, def.source)
            }

            fun entity(path: String): SymbolEntry? = resolveMdErRef(resolver, doc, path)?.takeIf { it.kind == "entity" }
            when (def) {
                is Md2erCubeletDef -> {
                    val erPath = def.entity?.path.orEmpty()
                    val er = entity(erPath)
                    if (er == null) report(DiagnosticCode.MdMd2erReference, "Unresolved ER entity '$erPath'")
                    if (def.physicalProps.isNotEmpty()) {
                        report(
                            DiagnosticCode.MdMd2erPhysicalProp,
                            "md2er_cubelet is structural-only; remove: ${def.physicalProps.joinToString(", ")}",
                        )
                    }
                    val path = def.cubeletRef?.path.orEmpty()
                    val cubelet = resolveMdNs(resolver, doc, path, "cubelet")
                    if (cubelet == null) {
                        report(
                            DiagnosticCode.MdMd2erReference,
                            if (path.isEmpty()) "md2er_cubelet needs `cubelet:`" else "Unresolved MD cubelet '$path'",
                        )
                    }
                    val measures = cubelet?.let { cubeletMeasureKeys(docs, resolver, it) }
                    for ((key, attr) in def.measures) {
                        val measure = resolveMdNs(resolver, doc, key, "measure")
                        if (measure == null) {
                            report(DiagnosticCode.MdMd2erReference, "Unknown MD measure '$key'")
                        } else if (measures != null && measure.qname !in measures) {
                            report(
                                DiagnosticCode.MdMd2erReference,
                                "'$key' is not a measure of cubelet '$path'",
                            )
                        }
                        if (er != null && resolver.getSymbol("${er.qname}.$attr")?.kind != "attribute") {
                            report(
                                DiagnosticCode.MdMd2erMeasureUnknown,
                                "Measure '$key' names unknown attribute '$attr'",
                            )
                        }
                    }
                    for ((key, attr) in def.attributes) {
                        if (resolveMdAttribute(resolver, doc, key) == null) {
                            report(
                                DiagnosticCode.MdMd2erReference,
                                "Unknown MD attribute '$key'",
                            )
                        }
                        if (er != null && resolver.getSymbol("${er.qname}.$attr")?.kind != "attribute") {
                            report(
                                DiagnosticCode.MdMd2erReference,
                                "Unresolved ER attribute path '$attr' from '$erPath'",
                            )
                        }
                    }
                }
                is Md2erDimensionDef -> {
                    val path = def.dimensionRef?.path.orEmpty()
                    val dim = resolveMdNs(resolver, doc, path, "dimension")
                    if (dim == null) report(DiagnosticCode.MdMd2erReference, "Unresolved MD dimension '$path'")
                    val logical =
                        dim?.let { sym ->
                            byUri[sym.documentUri]?.definitions?.filterIsInstance<DimensionDef>()?.find {
                                it.name ==
                                    sym.name
                            }
                        }
                    if (logical?.key != null && logical.key !in def.attributes) {
                        report(
                            DiagnosticCode.MdMd2erDimensionKey,
                            "md2er_dimension must map key '${logical.key}'",
                        )
                    }
                    if (dim != null && !seen.add(dim.qname)) {
                        report(
                            DiagnosticCode.MdDuplicateBinding,
                            "Duplicate md2er_dimension for '$path'",
                        )
                    }
                    val erPath = def.entity?.path.orEmpty()
                    val er = entity(erPath)
                    if (er == null) report(DiagnosticCode.MdMd2erReference, "Unresolved ER entity '$erPath'")
                    for ((key, attr) in def.attributes) {
                        if (dim != null && resolver.getSymbol("${dim.qname}.$key")?.kind != "attribute") {
                            report(
                                DiagnosticCode.MdMd2erReference,
                                "'$key' is not an attribute of '$path'",
                            )
                        }
                        if (er == null) continue
                        val targets = explicitErAttributes(index, doc, resolver, er, attr)
                        if (targets.size > 1) {
                            report(
                                DiagnosticCode.MdMd2erPathAmbiguous,
                                "ER attribute path '$attr' is ambiguous from '$erPath'",
                            )
                        }
                        if (targets.isEmpty()) {
                            report(
                                DiagnosticCode.MdMd2erReference,
                                "Unresolved ER attribute path '$attr' from '$erPath'",
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
