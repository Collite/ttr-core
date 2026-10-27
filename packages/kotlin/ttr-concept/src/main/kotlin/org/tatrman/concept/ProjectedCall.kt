// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttrp.expr.*

/** DC-13 is syntactic sugar for the existing validity-interval lookup (CN-P8). */
internal fun projectedCall(
    e: FunctionCall,
    bearer: Concept,
    resolver: DefinitionResolver,
): ConceptAggregate? {
    val receiver = projectionReceiver(e, bearer, resolver) ?: return null
    val (quality, owner, path) = receiver
    val projection = quality.projection ?: return null
    val source = resolver.named(projection.source.name, projection.source.`package`) ?: return null
    val names = e.argNames
    if (names.size != e.args.size ||
        names.any { it == null } ||
        names.filterNotNull().distinct().size != names.size
    ) {
        return null
    }
    val per = projection.per?.let { q -> source.involves.singleOrNull { it.concept == q }?.label }
    if (projection.per != null && per == null) return null
    if (names.any { it != "at" && it != per }) return null
    if (per != null && per !in names) return null
    val at = names.indexOf("at").takeIf { it >= 0 }?.let { e.args[it] } ?: ParamRef("as_of", e.location)
    val where = TemporalPredicate(TemporalOp.CONTAINS, ColumnRef(null, "period", location = e.location), at, e.location)
    val subjects =
        listOf(
            Participant(
                ThisRef(path, e.location),
                source.involves
                    .singleOrNull { p ->
                        resolver.chain(owner).any {
                            it.qname ==
                                p.concept
                        }
                    }?.label,
                e.location,
            ),
        )
    val extra = if (per == null) emptyList() else listOf(Participant(e.args[names.indexOf(per)], per, e.location))
    return ConceptAggregate(
        "max",
        listOf(ConceptBinding(source.qname.name, null, e.location)),
        subjects + extra,
        where,
        emptyList(),
        listOf(projection.quality),
        e.location,
    )
}

internal data class ProjectionReceiver(
    val quality: Concept,
    val owner: Concept,
    val path: List<String>,
)

/** Resolve in the receiver's bearer/package scope; shared by SQL and the verbaliser. */
internal fun projectionReceiver(
    e: FunctionCall,
    bearer: Concept,
    resolver: DefinitionResolver,
): ProjectionReceiver? {
    val parts = e.function.value.split('.')
    val name = parts.last()
    val path = parts.dropLast(1).let { if (it.firstOrNull() == "this") it.drop(1) else it }
    val owner =
        if (path.isEmpty()) {
            bearer
        } else {
            val walk = resolver.walk(bearer, path, 0) as? DefinitionResolver.Walk.Ok ?: return null
            (walk.value as? DefinitionResolver.Value.Instance)?.concept ?: return null
        }
    val quality =
        resolver.chain(owner).firstNotNullOfOrNull { c ->
            resolver.qualityOn(c, name)?.takeIf { it.projection != null }
        } ?: return null
    return ProjectionReceiver(quality, owner, path)
}
