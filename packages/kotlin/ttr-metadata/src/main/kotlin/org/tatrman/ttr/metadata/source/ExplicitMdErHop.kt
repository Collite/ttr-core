// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.metadata.model.HopResult
import org.tatrman.ttr.metadata.model.HopVia
import org.tatrman.ttr.metadata.model.MdErHop
import org.tatrman.ttr.parser.model.Md2erCubeletDef
import org.tatrman.ttr.parser.model.Md2erDimensionDef
import org.tatrman.ttr.semantics.md.explicitErAttributes
import org.tatrman.ttr.semantics.md.buildMdErRelationIndex
import org.tatrman.ttr.semantics.md.resolveMdNs
import org.tatrman.ttr.semantics.md.resolveMdAttribute
import org.tatrman.ttr.semantics.md.cubeletMeasureKeys
import org.tatrman.ttr.semantics.ResolutionContext
import org.tatrman.ttr.semantics.ResolutionResult
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolEntry

/**
 * Explicit structural MD → ER landings (grammar 0.18): cubelets, dimension entities, grain and
 * dimension attributes, and cubelet measures. Dimension attribute paths traverse one named relation.
 * Missing mappings remain unmapped here; the derivation composes with this explicit tier in HP-P2.
 * The bearer picks first; then a dimension's own binding wins over a cubelet grain mapping of the same attribute.
 */
class ExplicitMdErHop(
    docs: List<SemanticDocument>,
    resolver: Resolver,
) : MdErHop {
    /** md cubelet / md attribute → its explicit er candidates, in document order. */
    private data class Candidate(
        val er: String,
        val fromDimension: Boolean,
    )

    private val ambiguousPaths = mutableSetOf<String>()
    private val candidates = linkedMapOf<String, MutableList<Candidate>>()

    init {
        val relationIndex = buildMdErRelationIndex(docs, resolver)
        for (doc in docs) {
            val ctx = ResolutionContext(doc.schemaCode, doc.namespace, null, doc.imports, doc.packageName)

            fun resolve(
                path: String,
                parts: List<String>,
            ): SymbolEntry? =
                (resolver.resolveReference(Resolver.Ref(path, parts), ctx) as? ResolutionResult.Resolved)?.symbol

            for (def in doc.definitions) {
                if (def is Md2erDimensionDef) {
                    val entity =
                        def.entity?.let { resolve(it.path, it.parts) }?.takeIf { it.kind == "entity" } ?: continue
                    val dimension =
                        def.dimensionRef?.let { resolveMdNs(resolver, doc, it.path, "dimension") }
                            ?: continue
                    add(elementRefOf(dimension), elementRefOf(entity), true)
                    for ((attr, path) in def.attributes) {
                        val md = resolver.getSymbol("${dimension.qname}.$attr") ?: continue
                        val targets = explicitErAttributes(relationIndex, doc, resolver, entity, path)
                        if (targets.size > 1) ambiguousPaths += elementRefOf(md)
                        if (md.kind == "attribute" &&
                            targets.size == 1
                        ) {
                            add(elementRefOf(md), elementRefOf(targets.single()), true)
                        }
                    }
                    continue
                }
                if (def !is Md2erCubeletDef) continue
                val entity =
                    def.entity?.let { resolve(it.path, it.parts) }?.takeIf { it.kind == "entity" } ?: continue
                val cubelet = def.cubeletRef?.let { resolveMdNs(resolver, doc, it.path, "cubelet") }
                if (cubelet == null) continue
                add(elementRefOf(cubelet), elementRefOf(entity))
                val measures = cubeletMeasureKeys(docs, resolver, cubelet)
                for ((measure, erAttr) in def.measures) {
                    val md = resolveMdNs(resolver, doc, measure, "measure")?.takeIf { it.qname in measures } ?: continue
                    val er =
                        resolver.getSymbol("${entity.qname}.$erAttr")?.takeIf { it.kind == "attribute" } ?: continue
                    add(elementRefOf(md), elementRefOf(er))
                }
                for ((mdAttr, erAttr) in def.attributes) {
                    val md =
                        resolveMdAttribute(resolver, doc, mdAttr)?.takeIf {
                            it.kind == "attribute" && it.schemaCode == "md"
                        } ?: continue
                    val er =
                        resolver.getSymbol("${entity.qname}.$erAttr")?.takeIf { it.kind == "attribute" } ?: continue
                    add(elementRefOf(md), elementRefOf(er))
                }
            }
        }
    }

    private fun add(
        md: String,
        er: String,
        fromDimension: Boolean = false,
    ) {
        val list = candidates.getOrPut(md) { mutableListOf() }
        val existing = list.indexOfFirst { it.er == er }
        if (existing < 0) {
            list += Candidate(er, fromDimension)
        } else if (fromDimension) {
            list[existing] = Candidate(er, true)
        }
    }

    override fun erTargetOf(
        mdElement: String,
        bearerEntity: String?,
    ): HopResult {
        val found = candidates[mdElement].orEmpty()
        if (found.size == 1) return HopResult(found.single().er, HopVia.EXPLICIT)
        if (found.isEmpty() && mdElement in ambiguousPaths) {
            return HopResult.ambiguous("'$mdElement' has an ambiguous explicit dimension path")
        }
        if (found.isEmpty()) {
            return HopResult.unmapped(
                "'$mdElement' has no er counterpart — no explicit md2er binding maps it",
            )
        }
        val ofBearer = found.filter { entityOfElement(it.er) == bearerEntity }
        if (bearerEntity != null && ofBearer.size == 1) return HopResult(ofBearer.single().er, HopVia.EXPLICIT)
        val own = found.filter { it.fromDimension }
        if (own.size == 1) return HopResult(own.single().er, HopVia.EXPLICIT)
        return HopResult.ambiguous("'$mdElement' maps to ${found.joinToString(", ") { it.er }}")
    }
}

/**
 * The archive's spelling of a symbol (contracts §1.2, A-CN-9): its canonical key without the package — `er.entity.<e>`,
 * `er.entity.<e>.<a>`, `md.dimension.<D>.<a>`, `md.measure.<m>` — and for db without the kind segment the key carries
 * (`db.dbo.table.x` → `db.dbo.x`, the form `QualifiedName.dotted()` renders).
 */
fun elementRefOf(sym: SymbolEntry): String {
    val local =
        if (sym.packageName.isNotEmpty() && sym.qname.startsWith("${sym.packageName}.")) {
            sym.qname.substring(sym.packageName.length + 1)
        } else {
            sym.qname
        }
    val segs = local.split('.')
    return if (segs.size >= 4 && segs[0] == "db") (segs.take(2) + segs.drop(3)).joinToString(".") else local
}

/** The entity of an er element ref: `er.entity.<e>` itself, or the entity around `er.entity.<e>.<a>`. */
fun entityOfElement(ref: String): String? {
    val segs = ref.split('.')
    return if (segs.size >= 3 && segs[0] == "er" && segs[1] == "entity") segs.take(3).joinToString(".") else null
}
