// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import kotlinx.serialization.Serializable

/** A shared, single-storage door read (AU S2.5, manifest v3 additive). */
@Serializable
data class RecipePartEntry(
    val name: String,
    val storage: String,
    val engine: String,
    val file: String,
    val sha256: String,
    val columns: List<OutputColumn>,
    val params: List<String>,
    val keyed: Boolean,
)

/** DuckDB statement over named part tables; never sent through the door. */
@Serializable
data class RecipeCombine(
    val file: String,
    val sha256: String,
    val parts: List<String>,
    val params: List<String>,
    val engine: String = "duckdb",
    val dialect: String = "duckdb",
)
