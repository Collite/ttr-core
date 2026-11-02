// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.nav

import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.dialect.FragmentFiles

/**
 * File-backed fragments in the editor (AG B5): every `container … from "<path>"` clause of a program,
 * with the fragment file it names — the target of go-to-definition and of a document link — and the
 * re-anchoring of findings made INSIDE a fragment file onto the clause that pulls it in (and of
 * findings made in any other file onto the recipe's header).
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
     * `from` clause that pulls the file in, its message prefixed with `<file>:<line>:<col>:`. A finding
     * located in any other file — a model file the recipe is checked against (D-142's TTRP-CUT-nnn in a
     * `.ttrm`) — is not at that line and column of the program: it moves onto [header] (the recipe's
     * header, else the document start) with the same prefix. Any other finding is returned unchanged.
     */
    fun anchor(
        d: TtrpDiagnostic,
        uri: String,
        links: List<Link>,
        header: SourceLocation? = null,
    ): TtrpDiagnostic {
        if (d.location.file == uri) return d
        val link = links.firstOrNull { it.fileId == d.location.file }
        if (link != null) return elsewhere(d, link.target, link.clause)
        return if (header != null) elsewhere(d, d.location.file, header) else d
    }

    /**
     * Where [doc] shows a finding made in another file: the `recipe` keyword of its recipe, else the
     * start of the document.
     */
    fun header(doc: TtrpDocument): SourceLocation {
        val r =
            doc.statements
                .filterIsInstance<RecipeDecl>()
                .firstOrNull()
                ?.location
        if (r == null || r.line < 1) return SourceLocation(doc.location.file, 1, 0, 1, 0, 0, 0)
        val width = "recipe".length
        return SourceLocation(r.file, r.line, r.column, r.line, r.column + width, r.offsetStart, r.offsetStart + width)
    }

    /**
     * [d], made in [file], shown [at]: its message prefixed with `<file name>:<line>:<col>:`. A finding
     * with no position of its own (`SourceLocation.UNKNOWN`) is shown [at] unprefixed.
     */
    fun elsewhere(
        d: TtrpDiagnostic,
        file: String,
        at: SourceLocation,
    ): TtrpDiagnostic {
        if (d.location.line < 1) return d.copy(location = at)
        val name = file.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(name, Charsets.UTF_8) }.getOrDefault(name)
        return d.copy(
            location = at,
            message = "$decoded:${d.location.line}:${d.location.column}: ${d.message}",
        )
    }
}
