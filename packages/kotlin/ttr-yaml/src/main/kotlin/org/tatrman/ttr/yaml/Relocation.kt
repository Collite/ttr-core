// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import org.tatrman.ttr.parser.model.SourceLocation

/**
 * MW S3.4 — where the typed AST of a generated canonical document points instead of the generated text: the authored
 * file it came from ([CanonicalYaml.read]).
 */
interface Relocation {
    /** The authored location for the value at canonical [pointer]; null keeps the generated one. */
    fun source(pointer: String): SourceLocation?

    /**
     * An embedded block's text span, line starts and indent in the authored file, for the block [text] at canonical
     * [pointer]. Null keeps all three generated: they are one coordinate system (review-260 F8).
     */
    fun block(
        pointer: String,
        text: String,
    ): BlockSpan? = null
}

/** Where an embedded block's text sits in a file: its span, the offset each line starts at, and the line indent. */
data class BlockSpan(
    val source: SourceLocation,
    val lineOffsets: List<Int>,
    val indentWidth: Int,
)
