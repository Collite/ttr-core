// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.nav

import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.dialect.FragmentFiles

/**
 * File-backed fragments in the editor (AG B5): every `container … from "<path>"` clause of a program,
 * with the fragment file it names — the target of go-to-definition and of a document link — and the
 * re-anchoring of findings made INSIDE a fragment file onto the clause that pulls it in.
 */
object FragmentLinks {
    data class Link(
        /** The `from "<path>"` clause in the program. */
        val clause: SourceLocation,
        /** The fragment file as a `file:` URI. */
        val target: String,
        /** The fragment's interior file id (as the frontend names it in diagnostics). */
        val fileId: String,
    )

    fun of(
        uri: String,
        doc: TtrpDocument,
    ): List<Link> =
        doc.statements.filterIsInstance<ContainerDecl>().mapNotNull { c ->
            val body = c.body as? FragmentBody ?: return@mapNotNull null
            val rel = body.sourceFile ?: return@mapNotNull null
            val file = FragmentFiles.resolve(uri, rel) ?: return@mapNotNull null
            Link(body.location, file.toUri().toString(), body.interiorLocation.file)
        }

    /**
     * [d] as the program [uri] should show it: a finding located in a fragment FILE moves onto the
     * `from` clause that pulls the file in, its message prefixed with `<file>:<line>:<col>:`. Any
     * other finding is returned unchanged.
     */
    fun anchor(
        d: TtrpDiagnostic,
        uri: String,
        links: List<Link>,
    ): TtrpDiagnostic {
        if (d.location.file == uri) return d
        val link = links.firstOrNull { it.fileId == d.location.file } ?: return d
        val name = link.target.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(name, Charsets.UTF_8) }.getOrDefault(name)
        return d.copy(
            location = link.clause,
            message = "$decoded:${d.location.line}:${d.location.column}: ${d.message}",
        )
    }
}
