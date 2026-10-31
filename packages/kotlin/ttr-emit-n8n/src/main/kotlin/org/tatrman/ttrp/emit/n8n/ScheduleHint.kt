// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.n8n

/** Export hint shapes shared with Camunda by contracts §5; arbitrary text is never treated as cron. */
internal data class ScheduleHint(
    val cron: String,
    val timezone: String,
    val minutes: Int? = null,
) {
    companion object {
        fun parse(hint: String): ScheduleHint {
            Regex("\\*/([1-9][0-9]*)").matchEntire(hint)?.let {
                val minutes = it.groupValues[1].toInt()
                require(minutes in 1..60) { "unsupported schedule hint $hint" }
                return ScheduleHint(
                    if (minutes ==
                        60
                    ) {
                        "0 0 * * * *"
                    } else {
                        "0 */$minutes * * * *"
                    },
                    "UTC",
                    minutes.takeIf { it < 60 },
                )
            }
            val m =
                Regex("(?:(Mon|Tue|Wed|Thu|Fri|Sat|Sun|1st) )?([0-9]{2}):([0-9]{2}) ([^ ]+)").matchEntire(hint)
                    ?: error("unsupported schedule hint $hint")
            val hour = m.groupValues[2].toInt()
            val minute = m.groupValues[3].toInt()
            require(hour in 0..23 && minute in 0..59) { "invalid schedule hint $hint" }
            val zone =
                runCatching {
                    java.time.ZoneId
                        .of(
                            m.groupValues[4],
                        ).id
                }.getOrElse { error("invalid schedule hint $hint") }
            val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val suffix =
                when (val day = m.groupValues[1]) {
                    "" -> "* * *"
                    "1st" -> "1 * *"
                    else -> "* * ${days.indexOf(day)}"
                }
            return ScheduleHint("0 $minute $hour $suffix", zone)
        }
    }
}
