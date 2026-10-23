// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.parser.model.*

/** The column-value shapes accepted by the loader, shared with the physical inverse. */
internal fun inlineColumnEntries(value: BindingColumnObject): Map<String, PropertyValue> {
    val entries = value.obj.entries
    return (entries["target"] as? PropertyValue.ObjectValue)?.entries
        ?: (entries["target"] as? PropertyValue.IdValue)?.let { mapOf("column" to it) }
        ?: entries
}
