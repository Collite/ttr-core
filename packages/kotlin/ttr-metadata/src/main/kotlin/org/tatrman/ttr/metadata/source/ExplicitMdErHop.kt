// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.metadata.model.HopResult
import org.tatrman.ttr.metadata.model.HopVia
import org.tatrman.ttr.metadata.model.MdErHop
import org.tatrman.ttr.parser.model.Md2erCubeletDef
import org.tatrman.ttr.semantics.ResolutionContext
import org.tatrman.ttr.semantics.ResolutionResult
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolEntry

/**
 * The hop as P2 answers it (CN-P2 S2.5 T3): rule 1 of [MdErHop]'s order, read off the **explicit `md2er_cubelet`**
 * defs only — a cubelet lands on the binding's `target:` entity, and each md attribute its `attributes:` map names
 * lands on that entity's attribute (`{ Customer.code: customerCode }` → `er.entity.<target>.customerCode`). Every
 * other md element — a dimension, a domain, a measure (structural `measures:` and `md2er_domain` are the md/hop
 * sub-arc's, with the derivation) — answers `md/er-unmapped`.
 *
 * An md attribute two cubelets map to different er attributes is ambiguous unless a [bearerEntity] picks one (the
 * bearer restriction of rule 2, applied to the explicit candidates); else `md/er-ambiguous`. A ref that does not
 * resolve contributes nothing — `ttr/unresolved-reference` reports it.
 */
class ExplicitMdErHop(
    docs: List<SemanticDocument>,
    resolver: Resolver,
) : MdErHop {
    /** md cubelet / md attribute → its explicit er candidates, in document order. */
    private val candidates = linkedMapOf<String, MutableList<String>>()

    init {
        for (doc in docs) {
            val ctx = ResolutionContext(doc.schemaCode, doc.namespace, null, doc.imports, doc.packageName)

            fun resolve(
                path: String,
                parts: List<String>,
            ): SymbolEntry? =
                (resolver.resolveReference(Resolver.Ref(path, parts), ctx) as? ResolutionResult.Resolved)?.symbol

            for (def in doc.definitions) {
                if (def !is Md2erCubeletDef) continue
                val entity =
                    def.entity?.let { resolve(it.path, it.parts) }?.takeIf { it.kind == "entity" } ?: continue
                val cubelet = def.cubeletRef?.let { resolve(it.path, it.parts) }?.takeIf { it.kind == "cubelet" }
                if (cubelet != null) add(elementRefOf(cubelet), elementRefOf(entity))
                for ((mdAttr, erAttr) in def.attributes) {
                    val md =
                        resolve(mdAttr, mdAttr.split('.'))?.takeIf {
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
    ) {
        val list = candidates.getOrPut(md) { mutableListOf() }
        if (er !in list) list += er
    }

    override fun erTargetOf(
        mdElement: String,
        bearerEntity: String?,
    ): HopResult {
        val found = candidates[mdElement].orEmpty()
        if (found.size == 1) return HopResult(found.single(), HopVia.EXPLICIT)
        if (found.isEmpty()) {
            return HopResult.unmapped(
                "'$mdElement' has no er counterpart — no md2er_cubelet maps it (the derivation is the md/hop sub-arc's)",
            )
        }
        val ofBearer = found.filter { entityOfElement(it) == bearerEntity }
        if (bearerEntity != null && ofBearer.size == 1) return HopResult(ofBearer.single(), HopVia.EXPLICIT)
        return HopResult.ambiguous("'$mdElement' maps to ${found.joinToString(", ")}")
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
