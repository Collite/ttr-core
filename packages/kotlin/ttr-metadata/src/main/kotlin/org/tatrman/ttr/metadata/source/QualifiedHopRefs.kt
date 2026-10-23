// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

/** Canonical entity identity, retaining the package prefix. */
internal fun qualifiedEntity(ref: String): String? {
    val parts = ref.split('.')
    val index = parts.indices.firstOrNull { parts[it] == "er" && parts.getOrNull(it + 1) == "entity" } ?: return null
    return parts.take(index + 3).joinToString(".")
}
