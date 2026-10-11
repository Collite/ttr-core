// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.PortDecl
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.lsp.nav.SourceNav
import org.tatrman.ttrp.lsp.nav.VarRefs
import org.tatrman.ttrp.lsp.viewstate.ViewStateRenameParticipant
import org.tatrman.ttrp.lsp.viewstate.ZetaKeyRemap

/**
 * Rename (T4.1.7): SSA-aware + ζ sidecar-atomic groundwork (C1-c). Renaming a variable
 * renames **every SSA generation and every reference** of that source name in its scope
 * — the author sees one name (Q7-γ; SSA is desugar). Renaming a container updates its
 * declaration and every `container.port` wiring ref. Edits are versioned (hosts reject
 * stale). Before the `WorkspaceEdit` is returned, registered [ViewStateRenameParticipant]s
 * are handed the ζ key remaps (one per SSA generation) so Stage 5.2 can rewrite the
 * `.ttrl` sidecar atomically.
 */
class RenameService(
    private val participants: List<ViewStateRenameParticipant> = emptyList(),
) {
    /** Reserved names (S10) — never renameable targets. */
    private val reserved = setOf("in", "out", "err", "rejects", "true", "false", "else")

    sealed interface Prepare {
        data class Valid(
            val range: Range,
            val placeholder: String,
        ) : Prepare

        data class Invalid(
            val reason: String,
        ) : Prepare
    }

    fun prepareRename(
        text: String,
        doc: TtrpDocument,
        pos: Position,
    ): Prepare {
        val target = resolveTarget(doc, pos) ?: return Prepare.Invalid("no renameable symbol at this position")
        if (target.name in reserved) {
            return Prepare.Invalid("`${target.name}` is a reserved name (S10) and cannot be renamed")
        }
        return Prepare.Valid(target.anchor, target.name)
    }

    fun rename(
        uri: String,
        text: String,
        doc: TtrpDocument,
        version: Int,
        pos: Position,
        newName: String,
    ): WorkspaceEdit? {
        val target = resolveTarget(doc, pos) ?: return null
        if (target.name in reserved) return null

        val edits = target.edits.map { TextEdit(it, newName) }.sortedWith(rangeOrder())
        val textDocEdit = TextDocumentEdit(VersionedTextDocumentIdentifier(uri, version), edits)
        val remaps =
            target.generations.indices.map { i ->
                ZetaKeyRemap("${target.prefix}/${target.name}#${i + 1}", "${target.prefix}/$newName#${i + 1}")
            }
        participants.forEach { it.onRename(uri, remaps) }
        return WorkspaceEdit(listOf(Either.forLeft(textDocEdit)))
    }

    // ---- target resolution ----

    private data class Target(
        val name: String,
        val prefix: String,
        /** The head-token range to select in prepareRename. */
        val anchor: Range,
        /** Ranges to replace with the new name. */
        val edits: List<Range>,
        /** SSA generation count (assignment targets) — the ζ key set. */
        val generations: List<SourceLocation>,
    )

    private fun resolveTarget(
        doc: TtrpDocument,
        pos: Position,
    ): Target? {
        val container = SourceNav.containerAt(doc, pos)

        // A port declaration in a container header.
        if (container != null) {
            // A synthesized TTR-B action port (AG B4) points into the fragment — not a renameable declaration.
            container.ports.firstOrNull { !it.synthesized && SourceNav.contains(it.location, pos) }?.let {
                return portTarget(doc, container, it)
            }
        }

        // Program-level container name / container.port ref → rename the container.
        if (container == null) {
            containerTarget(doc, pos)?.let { return it }
        }

        // A variable in the current scope (container body or program level).
        val scope = SourceNav.scopeStatements(doc, pos)
        val inPorts = container?.ports?.map { it.name } ?: emptyList()
        val varNames = VarRefs.variableNames(scope, inPorts)
        val occurrences = VarRefs.occurrences(scope, varNames)
        val hit = occurrences.firstOrNull { SourceNav.contains(it.location, pos) } ?: return null
        val name = hit.name
        val sameName = occurrences.filter { it.name == name }
        val generations = scope.filterIsInstance<Assignment>().filter { it.target == name }.map { it.targetLocation }
        return Target(
            name = name,
            prefix = container?.name ?: "",
            anchor = headRange(hit.location, name),
            edits = sameName.map { headRange(it.location, name) },
            generations = generations,
        )
    }

    private fun containerTarget(
        doc: TtrpDocument,
        pos: Position,
    ): Target? {
        val containers = doc.statements.filterIsInstance<ContainerDecl>()
        val names = containers.map { it.name }.toSet()
        val refs = VarRefs.containerRefs(doc.statements, names)
        val hit = refs.firstOrNull { SourceNav.contains(it.location, pos) && it.tail.isEmpty() } ?: return null
        val name = hit.name
        val decl = containers.first { it.name == name }
        val edits = mutableListOf<Range>()
        // The declaration name token has its own AST span (nameLocation) — edit that, never
        // a text scan from the `container` keyword (which mis-hits names that are substrings of it).
        edits += headRange(decl.nameLocation, name)
        for (ref in refs.filter { it.name == name && !it.isDefinition }) {
            edits += headRange(ref.location, name)
        }
        return Target(
            name,
            prefix = "",
            anchor = headRange(hit.location, name),
            edits = edits,
            generations = emptyList(),
        )
    }

    private fun portTarget(
        doc: TtrpDocument,
        container: ContainerDecl,
        port: PortDecl,
    ): Target {
        val edits = mutableListOf<Range>()
        edits += headRange(port.location, port.name)
        for (ref in VarRefs.containerRefs(doc.statements, setOf(container.name))) {
            if (ref.tail.firstOrNull() == port.name) edits += tailRange(ref.location, port.name)
        }
        val body = (container.body as? org.tatrman.ttrp.ast.FlowBody)?.statements ?: emptyList()
        for (occ in VarRefs.occurrences(body, setOf(port.name))) {
            edits += headRange(occ.location, port.name)
        }
        return Target(
            port.name,
            prefix = container.name,
            anchor = headRange(port.location, port.name),
            edits = edits,
            generations = emptyList(),
        )
    }

    // ---- range helpers (identifiers are single-line) ----

    private fun headRange(
        loc: SourceLocation,
        name: String,
    ): Range {
        val line = maxOf(0, loc.line - 1)
        return Range(Position(line, loc.column), Position(line, loc.column + name.length))
    }

    private fun tailRange(
        loc: SourceLocation,
        tailName: String,
    ): Range {
        val line = maxOf(0, loc.endLine - 1)
        return Range(Position(line, loc.endColumn - tailName.length), Position(line, loc.endColumn))
    }

    private fun rangeOrder(): Comparator<TextEdit> = compareBy({ it.range.start.line }, { it.range.start.character })
}
