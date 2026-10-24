// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

data class Observed(
    val recipes: Map<String, Expected>,
    val finalStates: JsonObject? = null,
)

data class ReplayDiff(
    val collection: String,
    val missing: List<String>,
    val extra: List<String>,
)

/** Canonical row multisets: duplicates matter, decimal values are exact, strings are never coerced to numbers.
 * Instants normalize to UTC microseconds; no implicit tolerance. NULL remains distinct from absent fields. */
object ReplayComparator {
    fun compare(
        fixture: ReplayFixture,
        actual: Observed,
    ): List<ReplayDiff> =
        buildList {
            for ((recipe, expected) in fixture.expected) {
                val seen = actual.recipes[recipe]
                if (seen == null) {
                    add(ReplayDiff(recipe, listOf("recipe result"), emptyList()))
                    continue
                }

                fun multiset(
                    name: String,
                    wanted: List<JsonElement>,
                    got: List<JsonElement>,
                ) {
                    val w = wanted.map(::canonical).groupingBy { it }.eachCount()
                    val g = got.map(::canonical).groupingBy { it }.eachCount()
                    val missing =
                        (w.keys + g.keys).sorted().flatMap { k ->
                            List(((w[k] ?: 0) - (g[k] ?: 0)).coerceAtLeast(0)) { k }
                        }
                    val extra =
                        (w.keys + g.keys).sorted().flatMap { k ->
                            List(((g[k] ?: 0) - (w[k] ?: 0)).coerceAtLeast(0)) { k }
                        }
                    if (missing.isNotEmpty() || extra.isNotEmpty()) add(ReplayDiff("$recipe.$name", missing, extra))
                }

                fun verdict(
                    v: JsonObject,
                    expectedReason: Boolean,
                ): JsonObject =
                    JsonObject(
                        v.filterKeys {
                            it in
                                if (expectedReason) setOf("key", "route", "verdict_reason") else setOf("key", "route")
                        },
                    )
                val reason = expected.verdicts.any { "verdict_reason" in it }
                val filled =
                    expected.verdicts.map { v ->
                        val template = v["verdict_reason"]?.jsonPrimitive?.content
                        if (template == null) {
                            v
                        } else {
                            val matching =
                                seen.verdicts.firstOrNull {
                                    canonical(it.getValue("key")) ==
                                        canonical(v.getValue("key")) &&
                                        it["route"] == v["route"]
                                }
                            val fills =
                                fixture.parameters + matching.orEmpty() +
                                    (matching?.get("fills") as? JsonObject).orEmpty()
                            val text =
                                Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(template) { m ->
                                    fills[m.groupValues[1]]?.jsonPrimitive?.content
                                        ?: m.value
                                }
                            JsonObject(v + ("verdict_reason" to JsonPrimitive(text)))
                        }
                    }
                multiset("verdicts", filled.map { verdict(it, reason) }, seen.verdicts.map { verdict(it, reason) })
                expected.actions?.let { wanted -> multiset("actions", wanted, seen.actions.orEmpty()) }
                expected.details?.let { wanted ->
                    fun flatten(details: List<JsonObject>): List<JsonElement> =
                        details.flatMap { d ->
                            d.getValue("rows").jsonArray.map { row ->
                                JsonObject(
                                    d.filterKeys { it != "rows" } + ("row" to row),
                                )
                            }
                        }
                    multiset("details", flatten(wanted), flatten(seen.details.orEmpty()))
                }
                expected.detailCounts?.let { wanted ->
                    if (canonical(wanted) !=
                        canonical(seen.detailCounts ?: JsonObject(emptyMap()))
                    ) {
                        add(
                            ReplayDiff(
                                "$recipe.detail_counts",
                                listOf(canonical(wanted)),
                                listOf(
                                    canonical(
                                        seen.detailCounts ?: JsonNull,
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
            if (fixture.finalStates != null &&
                canonical(fixture.finalStates) != canonical(actual.finalStates ?: JsonNull)
            ) {
                add(
                    ReplayDiff(
                        "final_states",
                        listOf(canonical(fixture.finalStates)),
                        listOf(
                            canonical(
                                actual.finalStates ?: JsonNull,
                            ),
                        ),
                    ),
                )
            }
            for (extra in actual.recipes.keys - fixture.expected.keys) {
                add(
                    ReplayDiff(extra, emptyList(), listOf("unexpected recipe result")),
                )
            }
        }

    fun canonical(value: JsonElement): String =
        when (value) {
            JsonNull -> "null"
            is JsonObject ->
                value.toSortedMap().entries.joinToString(",", "{", "}") {
                    "${JsonPrimitive(it.key)}:${canonical(it.value)}"
                }
            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
            is JsonPrimitive ->
                when {
                    value.isString -> {
                        val instant =
                            runCatching {
                                Instant
                                    .parse(
                                        value.content,
                                    ).truncatedTo(ChronoUnit.MICROS)
                            }.getOrNull()
                        JsonPrimitive(instant?.toString() ?: value.content).toString()
                    }
                    value.booleanOrNull != null -> value.content
                    else -> BigDecimal(value.content).stripTrailingZeros().toPlainString()
                }
        }
}
