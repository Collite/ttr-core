// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CellReport(
    val cell: String,
    val status: String,
    val diffs: List<ReplayDiff> = emptyList(),
    val error: String? = null,
)

data class ReplayReport(
    val fixture: String,
    val seed: Boolean,
    val cells: List<CellReport>,
) {
    val exitCode: Int get() =
        if (cells.any { it.status == "error" }) {
            2
        } else if (cells.any { it.status == "disagree" }) {
            3
        } else {
            0
        }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("fixture", fixture)
            put("seed", seed)
            put("exitCode", exitCode)
            put(
                "cells",
                JsonArray(
                    cells.map { c ->
                        buildJsonObject {
                            put("cell", c.cell)
                            put("status", c.status)
                            c.error?.let { put("error", it) }
                            put(
                                "diffs",
                                JsonArray(
                                    c.diffs.map { d ->
                                        buildJsonObject {
                                            put("collection", d.collection)
                                            put("missing", JsonArray(d.missing.map(::JsonPrimitive)))
                                            put("extra", JsonArray(d.extra.map(::JsonPrimitive)))
                                        }
                                    },
                                ),
                            )
                        }
                    },
                ),
            )
        }

    fun summary(): String =
        cells.joinToString("\n") { c ->
            "${c.cell}: ${c.status}" + (c.error?.let { " ($it)" } ?: "") +
                c.diffs.joinToString("") { d -> "\n  ${d.collection}: missing=${d.missing}; extra=${d.extra}" }
        }
}

object ReplayRunner {
    fun run(
        fixture: ReplayFixture,
        cells: List<String>,
        world: String? = null,
        driver: (String) -> Driver,
    ): ReplayReport {
        require(cells.isNotEmpty()) { "--cells requires at least one cell" }
        require(cells.distinct().size == cells.size) { "duplicate replay cells" }
        return ReplayReport(
            fixture.name,
            fixture.seed,
            cells.map { cell ->
                if (world != null && fixture.world != world) {
                    CellReport(cell, "skipped-world")
                } else if (fixture.seed) {
                    CellReport(cell, "seed")
                } else {
                    try {
                        val diffs = ReplayComparator.compare(fixture, driver(cell).run(fixture, cell))
                        CellReport(cell, if (diffs.isEmpty()) "agree" else "disagree", diffs)
                    } catch (e: Exception) {
                        CellReport(cell, "error", error = e.message ?: e.javaClass.simpleName)
                    }
                }
            },
        )
    }
}
