// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

/**
 * The TTR-B action vocabulary (AG B4). Each action sentence lowers to `calc { <columns> } ->
 * select(<columns>)` on the current value and is shown as `display(<kind>)` — an action display the
 * host holds to the imported `def schema <kind>` (TTRP-DSP). The kinds and column names are fixed and
 * the SAME in every skin (the schema is one; only the sentence is localized).
 */
object TtrbActions {
    /** `Send an e-mail …` / `Pošli e-mail …` — komu, předmět, šablona, klíč, příloha (optional). */
    const val SEND_EMAIL = "send_email"

    /** `Set <attribute> of <entity> …` / `Nastav …` — entita, klíč, atribut, hodnota, důvod. */
    const val UPDATE_FIELD = "update_field"

    /** `Create a manual task …` / `Vytvoř ruční úkol …` — řešitel, název, popis, příloha (optional). */
    const val MANUAL_TASK = "manual_task"

    /** A department recipient `department "x"` / `oddělení "x"` is the text `oddělení:x` (the host resolves it). */
    const val DEPARTMENT_PREFIX = "oddělení:"

    /** Attachment names join into one text value with this separator. */
    const val ATTACHMENT_SEPARATOR = ";"

    /** Every action kind with its columns, in schema order (`?` = optional). */
    val COLUMNS: Map<String, List<String>> =
        linkedMapOf(
            SEND_EMAIL to listOf("komu", "předmět", "šablona", "klíč", "příloha?"),
            UPDATE_FIELD to listOf("entita", "klíč", "atribut", "hodnota", "důvod"),
            MANUAL_TASK to listOf("řešitel", "název", "popis", "příloha?"),
        )
}
