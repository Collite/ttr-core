// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

/** Package selectors include descendants only at whole segment boundaries. */
object PackageNames {
    fun covers(
        prefix: String,
        pkg: String,
    ): Boolean = pkg == prefix || pkg.startsWith("$prefix.")
}
