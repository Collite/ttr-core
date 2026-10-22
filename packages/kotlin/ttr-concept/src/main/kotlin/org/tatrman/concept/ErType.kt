// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

/**
 * An er attribute's type spelling, read in one place (review-125 R125-6): the assertion planner's value check
 * ([AssertionLowerer]), the translator handle's surface type ([ErModelHandle]) and the lint's granularity
 * ([CnDefinitionLints.ofType]) all classify through it, so a spelling added here reaches all three.
 */
internal enum class ErType {
    INTEGER,
    DECIMAL,
    BOOLEAN,
    DATE,
    TIMESTAMP,
    TEXT,
    ;

    companion object {
        /** [type] as written (`int`, `decimal(12,2)`, `varchar(40)`); null — a spelling no family knows. */
        fun of(type: String?): ErType? =
            when (type?.lowercase()?.substringBefore('(')?.trim()) {
                "int", "integer", "bigint", "smallint", "tinyint", "long" -> INTEGER
                "decimal", "numeric", "number", "float", "double", "real", "money" -> DECIMAL
                "bool", "boolean", "bit" -> BOOLEAN
                "date" -> DATE
                "datetime", "datetime2", "timestamp", "timestamptz", "smalldatetime" -> TIMESTAMP
                "text", "string", "varchar", "nvarchar", "char", "nchar" -> TEXT
                else -> null
            }
    }
}
