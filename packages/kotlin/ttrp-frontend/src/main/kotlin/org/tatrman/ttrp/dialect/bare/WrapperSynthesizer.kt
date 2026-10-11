// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.bare

import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.dialect.b.TtrB
import org.tatrman.ttrp.dialect.pandas.TtrPandas
import org.tatrman.ttrp.dialect.sql.TtrSql
import org.tatrman.ttrp.project.TtrpManifest

/**
 * T6.3.3 — a marked bare fragment file (`.ttr.sql` / `.ttr.py` / `.ttrb` / `.ttrb-cs`) desugars to a canonical
 * TTR-P **wrapper program** (C0 bare-fragment commitment). The wrapper is DERIVED text — the source
 * file is never rewritten (C0/C2-f); it is fed straight back through the normal front-half, so the
 * bare program reuses parse → decompose → resolve → build **identically to the embedded case** (the
 * key-gate design). Shape:
 * ```
 *   uses world "<world>"
 *   import <default-imports…>                       # S18: bare-only implicit prelude
 *   _src_<p> = load(<p>)                             # one program-level load per derived in-port
 *   container <filename>(in <p…>[, out result]) target <bare-target> """<dialect>
 *   <interior verbatim>
 *   """
 *   _src_<p> -> <filename>.<p>                       # wiring
 *   <filename>.result -> display(main_result)        # Q11 default sink (unless self-terminating)
 * ```
 * Derived in-ports (the flagged rule, `/review`) = one per distinct external table reference the
 * decomposer reports, each fed by a synthesized `load(<short name>)` resolved via the default-imports
 * prelude (C2-d: in-ports > imports > qnames). A `ttrb`/interior that already ends in a `display`/`store`
 * sink is self-terminating — no synthesized out/display then (avoids a display-of-display, CTL-005).
 */
object WrapperSynthesizer {
    data class Result(
        val wrapperSource: String,
        val diagnostics: List<TtrpDiagnostic>,
    )

    /** Synthesize the wrapper for a bare file, or null if [fileName]+[source] is not a bare fragment. */
    fun synthesize(
        fileName: String,
        source: String,
        manifest: TtrpManifest,
    ): Result? {
        val dialect = DialectMarker.resolve(fileName, source) ?: return null
        val at = fileStart(fileName, source)

        val bareTarget =
            manifest.bareTarget
                ?: return Result("", listOf(diag(TtrpDiagnosticId.FRG_003, at)))

        val name = containerName(fileName)
        val interiorLoc = interiorLocation(fileName, source)
        val decomp =
            when (dialect) {
                "sql" -> TtrSql.decompose(source, interiorLoc, outPort = null)
                "pandas" -> TtrPandas.decompose(source, interiorLoc, outPort = null)
                else -> {
                    val skin =
                        org.tatrman.ttrp.dialect.b.TtrbSkin
                            .forTag(dialect)
                            ?: return Result(
                                "",
                                listOf(
                                    TtrpDiagnostic(
                                        id = TtrpDiagnosticId.FRG_001,
                                        severity = Severity.ERROR,
                                        message = "unknown fragment dialect `$dialect`",
                                        location = at,
                                        suggestedAlternative = TtrpDiagnosticId.FRG_001.suggestedAlternative,
                                    ),
                                ),
                            )
                    TtrB.decompose(source, interiorLoc, outPort = null, skin = skin)
                }
            }
        val ports = decomp.derivedInPorts
        // An interior ending in a display/store — or one whose actions route to their own displays (B4) —
        // needs no synthesized `out result` + default display.
        val selfTerminating =
            endsInSink(decomp.statements) || decomp.actionOutputs.isNotEmpty() || decomp.outputPorts.isNotEmpty()
        // TTR-B output sentences (B7) name the program's outputs: each becomes an OUT port shown as a display.
        val outputs = decomp.outputPorts.distinct()

        val sb = StringBuilder()
        manifest.world?.let { sb.append("uses world \"").append(it).append("\"\n") }
        for (imp in manifest.defaultImports) sb.append("import ").append(importStmt(imp)).append('\n')
        for (p in ports) {
            sb
                .append("_src_")
                .append(p)
                .append(" = load(")
                .append(p)
                .append(")\n")
        }

        val portDecls =
            ports.map { "in $it" } + outputs.map { "out $it" } +
                if (selfTerminating) emptyList() else listOf("out result")
        sb
            .append("container ")
            .append(name)
            .append('(')
            .append(portDecls.joinToString(", "))
            .append(')')
        sb
            .append(" target ")
            .append(bareTarget)
            .append(" \"\"\"")
            .append(dialect)
            .append('\n')
        sb.append(source)
        if (!source.endsWith("\n")) sb.append('\n')
        sb.append("\"\"\"\n")

        for (p in ports) {
            sb
                .append("_src_")
                .append(p)
                .append(" -> ")
                .append(name)
                .append('.')
                .append(p)
                .append('\n')
        }
        for (o in outputs) {
            sb
                .append(name)
                .append('.')
                .append(o)
                .append(" -> display(")
                .append(o)
                .append(")\n")
        }
        if (!selfTerminating) {
            sb.append(name).append(".result -> display(main_result)\n")
        }
        return Result(sb.toString(), decomp.diagnostics)
    }

    /** True if the lowered interior already terminates in a `display`/`store` leaf (C4-b-iv / C2-c-i). */
    private fun endsInSink(statements: List<org.tatrman.ttrp.ast.Statement>): Boolean {
        val lastChain =
            when (val s = statements.lastOrNull()) {
                is Assignment -> s.chain
                is ChainStmt -> s.chain
                else -> null
            }
        val terminal = (lastChain?.elements?.lastOrNull() as? OpCall)?.name
        return terminal == "display" || terminal == "store"
    }

    /** `erp.*` in the manifest → the `import erp.*` statement body (`.* ` already implied by the parser). */
    private fun importStmt(entry: String): String = if (entry.endsWith(".*")) entry else "$entry.*"

    /** Container name from the filename base (S12 analogue): `crunch.ttr.sql` → `crunch`; sanitized to an identifier. */
    fun containerName(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\').substringBefore('.')
        // Identifier letters as TTRP.g4 admits them (Latin incl. Latin-1 Supplement + Extended-A/B).
        val cleaned = base.replace(Regex("[^A-Za-z0-9_\\u00C0-\\u024F]"), "_")
        return if (cleaned.isEmpty() || cleaned.first().isDigit()) "_$cleaned" else cleaned
    }

    private fun fileStart(
        fileName: String,
        source: String,
    ): SourceLocation = SourceLocation(fileName, 1, 0, 1, 0, 0, minOf(source.length, 1))

    private fun interiorLocation(
        fileName: String,
        source: String,
    ): SourceLocation = SourceLocation(fileName, 1, 0, 1, 0, 0, source.length)

    private fun diag(
        id: TtrpDiagnosticId,
        at: SourceLocation,
    ): TtrpDiagnostic =
        TtrpDiagnostic(
            id = id,
            severity = Severity.ERROR,
            message = "a bare-fragment program needs a `[ttrp] bare-target` (and bare-shell) — no guessing (P2)",
            location = at,
            suggestedAlternative = id.suggestedAlternative,
        )
}
