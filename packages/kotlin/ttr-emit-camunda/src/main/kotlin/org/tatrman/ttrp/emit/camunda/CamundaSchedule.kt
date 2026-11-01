// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import java.time.ZoneId

/** Same authored hints as n8n; Quartz for C7, zoned ISO repeating intervals for C8. */
internal data class CamundaSchedule(
    val cycle: String,
    val zone: String,
) {
    companion object {
        fun parse(
            hint: String,
            dialect: Dialect,
        ): CamundaSchedule {
            Regex("\\*/([1-9][0-9]*)").matchEntire(hint)?.let {
                val minutes = it.groupValues[1].toInt()
                require(minutes in 1..60) { "unsupported schedule hint $hint" }
                return CamundaSchedule("R/PT${minutes}M", "UTC")
            }
            val m =
                Regex("(?:(Mon|Tue|Wed|Thu|Fri|Sat|Sun|1st) )?([0-9]{2}):([0-9]{2}) ([^ ]+)").matchEntire(hint)
                    ?: error("unsupported schedule hint $hint")
            val day = m.groupValues[1]
            val hour = m.groupValues[2].toInt()
            val minute = m.groupValues[3].toInt()
            require(hour in 0..23 && minute in 0..59) { "invalid schedule hint $hint" }
            val zone = ZoneId.of(m.groupValues[4]).id
            if (dialect == Dialect.C7) {
                val ending =
                    when (day) {
                        "" -> "* * ?"
                        "1st" -> "1 * ?"
                        else -> "? * ${day.uppercase()}"
                    }
                return CamundaSchedule("0 $minute $hour $ending", zone)
            }
            val time = m.groupValues[2] + ":" + m.groupValues[3] + ":00"
            val weekdays =
                mapOf(
                    "Mon" to "Monday",
                    "Tue" to "Tuesday",
                    "Wed" to "Wednesday",
                    "Thu" to "Thursday",
                    "Fri" to "Friday",
                    "Sat" to "Saturday",
                    "Sun" to "Sunday",
                )
            val dates =
                if (day ==
                    "1st"
                ) {
                    "for n in [0,1,2] return date(today().year,today().month,1) + duration(\"P\" + string(n) + \"M\")"
                } else {
                    "for n in [-1,0,1,2,3,4,5,6,7,8] return today() + n * duration(\"P1D\")"
                }
            val weekday = weekdays[day]?.let { " and day of week(item) = ${quote(it)}" }.orEmpty()
            val period =
                when (day) {
                    "" -> "P1D"
                    "1st" -> "P1M"
                    else -> "P1W"
                }
            val candidates = "(for d in ($dates) return date and time(string(d) + ${quote(
                "T$time@$zone",
            )}))[item > now()$weekday]"
            // The timer parser uses ISO zone ids in brackets, whereas FEEL accepts @Zone in date literals.
            val next =
                listOf(
                    "next:min($candidates)",
                    "seconds:next.time offset/duration(\"PT1S\")",
                    "hours:floor(abs(seconds)/3600)",
                    "minutes:modulo(floor(abs(seconds)/60),60)",
                    "hh:(if hours < 10 then \"0\" else \"\")+string(hours)",
                    "mm:(if minutes < 10 then \"0\" else \"\")+string(minutes)",
                    "offset:(if seconds < 0 then \"-\" else \"+\")+hh+\":\"+mm",
                    "cycle:\"R/\" + substring(string(next),1,19) + offset + ${quote("[$zone]/$period")}",
                ).joinToString(", ", "{", "}.cycle")
            return CamundaSchedule("=$next", zone)
        }
    }
}
