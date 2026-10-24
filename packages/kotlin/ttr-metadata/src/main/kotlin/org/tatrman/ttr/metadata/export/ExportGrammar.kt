// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import java.util.Properties

/** Grammar marker baked into the library, independent of its patch release version. */
object ExportGrammar {
    val version: String by lazy {
        val properties = Properties()
        requireNotNull(javaClass.getResourceAsStream("grammar.properties")).use { properties.load(it) }
        properties.getProperty("grammar")
    }
}
