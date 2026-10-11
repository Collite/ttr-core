// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.ast

/**
 * Chain-to-port inside a container body: `o -> filter(status = 1) -> select(a, b) -> late`, where `late` is one
 * of the container's declared OUT ports, writes the chain's value to that port — exactly `late = o -> filter(…)
 * -> select(a, b)`. The checker (row types, action displays) and the graph builder (the port's producer) both
 * read the statement through [asAssignment], so the two never disagree about what the port carries.
 *
 * (Before this desugar the graph builder resolved the tail `late` to nothing — an OUT port is not in the body's
 * scope — so the chain's nodes dangled and the port fell back to the body's last ASSIGNED value: an island wrote
 * the chain's unfiltered head.)
 */
object ChainToPort {
    /**
     * [stmt] as the assignment it stands for when its last element is a bare name in [outPorts] and something
     * precedes it; null for any other chain statement.
     */
    fun asAssignment(
        stmt: ChainStmt,
        outPorts: Set<String>,
    ): Assignment? {
        val elements = stmt.chain.elements
        if (elements.size < 2) return null
        val tail = elements.last() as? DottedRef ?: return null
        val port = tail.parts.singleOrNull() ?: return null
        if (port !in outPorts) return null
        return Assignment(
            target = port,
            targetLocation = tail.location,
            chain = Chain(elements.dropLast(1), stmt.chain.location),
            location = stmt.location,
            leadingTrivia = stmt.leadingTrivia,
            trailingTrivia = stmt.trailingTrivia,
        )
    }
}
