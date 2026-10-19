// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.loader

/**
 * Grammar 0.16 — the automation stdlib (AU-core-P0 S0.2 T6): the `obligation`, `state_history` and
 * `approvals` entities the automation runtime writes and recipes read (`package automation`,
 * `model er`). Shipped as a classpath resource of this jar, never authored; a project gets it
 * implicitly when it [needs][isNeededBy] it — the stock CNC vocab precedent. The TS twin is
 * `@tatrman/semantics`' `stock/automation-stdlib.ttrm` (byte-identical, asserted by a spec).
 */
object AutomationStdlib {
    const val RESOURCE = "automation/automation-stdlib.ttrm"

    /** The URI the stdlib document is parsed under (never a file on disk). */
    const val URI = "stdlib://automation-stdlib.ttrm"

    /** The stdlib's package. */
    const val PACKAGE = "automation"

    /** The stdlib source text. */
    val source: String by lazy {
        val stream =
            Thread.currentThread().contextClassLoader?.getResourceAsStream(RESOURCE)
                ?: AutomationStdlib::class.java.classLoader.getResourceAsStream(RESOURCE)
                ?: error("automation stdlib resource not found on the classpath: $RESOURCE")
        stream.bufferedReader().use { it.readText() }
    }

    /** The parsed stdlib (always error-free — a spec pins it). */
    val result: ParseResult by lazy { TtrLoader.parseString(source, URI) }

    /**
     * A project needs the stdlib when any document declares `model automation` or imports an
     * `automation` package (`import hartland.automation.*`, `import automation`).
     */
    fun isNeededBy(results: Collection<ParseResult>): Boolean =
        results.any { r ->
            r.modelDirective?.modelCode == "automation" ||
                r.imports.any { imp -> imp.target.split('.').contains("automation") }
        }
}
