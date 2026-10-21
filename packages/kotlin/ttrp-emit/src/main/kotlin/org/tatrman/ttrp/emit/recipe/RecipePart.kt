// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import org.tatrman.ttrp.emit.sql.EmitColumn

/** One maximal single-storage door read, shared across all outputs by content and storage. */
data class RecipePart(
    val name: String,
    val storage: String,
    val engine: String,
    val sql: String,
    val columns: List<EmitColumn>,
    val params: List<String>,
) {
    val file: String get() = "parts/$name.sql"
}
