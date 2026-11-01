// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import kotlinx.serialization.json.JsonObject
import org.tatrman.ttr.parser.loader.ParseResult

/** Lossless syntax tree plus the existing parser's typed projection. */
data class CanonicalDocument(val value: JsonObject, val ast: ParseResult)

object CanonicalYaml {
    fun read(source: String, file: String = "<yaml>"): CanonicalDocument = TODO("S2.2 reader")
    fun fromTtr(source: String, file: String = "<ttr>"): CanonicalDocument = TODO("S2.2 syntax adapter")
    fun write(document: CanonicalDocument): String = TODO("S2.2 writer")
    fun toTtr(document: CanonicalDocument): String = TODO("S2.2 inverse syntax adapter")
}
