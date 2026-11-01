// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.spi

/** Authored automation durations: round up to the Server/engine's second granularity. */
fun durationSeconds(raw: String): Long {
    val match =
        Regex("([0-9.]+)\\s+(milliseconds|seconds|minutes|hours|days)").matchEntire(raw)
            ?: error("unsupported duration $raw")
    val amount = match.groupValues[1].toBigDecimalOrNull() ?: error("unsupported duration $raw")
    val scale =
        mapOf(
            "milliseconds" to "0.001",
            "seconds" to "1",
            "minutes" to "60",
            "hours" to "3600",
            "days" to "86400",
        ).getValue(match.groupValues[2]).toBigDecimal()
    return (amount * scale).setScale(0, java.math.RoundingMode.CEILING).longValueExact().coerceAtLeast(1)
}
