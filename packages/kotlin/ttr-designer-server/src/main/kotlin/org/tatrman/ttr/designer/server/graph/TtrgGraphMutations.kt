// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.designer.server.graph

import org.tatrman.ttr.parser.loader.TtrgGraphReader

/**
 * The MUTATION half of `.ttrg` graph-block editing (FO-21, FO-P0.S2.T5).
 *
 * Split out of the former `TtrgGraphFile` per the move manifest §6: the parse helpers
 * (`parseHeader`/`findObjectsBrackets`/`parseObjects`) are read-half and live in
 * [TtrgGraphReader] (moved into `ttr-parser` by VL-P0, so the metadata service reads
 * `.ttrg` layouts through the same reader); these object/graph MUTATORS are the edit half. They ride the
 * `ttrm/addObjectToGraph`/`removeObjectFromGraph`/`createGraph` handlers
 * ([org.tatrman.ttr.designer.server.methods.registerTtrmEditMethods]) and are the
 * unit that relocates to the `ttr-designer-edit-server` module in tatrman-platform
 * at the cross-repo cutover (gated on S3 publishing the read half as a library —
 * see move manifest §1a). Kept here (still compiled + registered) until then so
 * the server keeps working and the existing specs stay green.
 *
 * Mutators operate on full file content and return full new content for the
 * caller to write (T3.1's ratified edit-application model — no `WorkspaceEdit`
 * deltas). Bracket/qname scanning is delegated to [TtrgGraphReader] (the shared
 * parse lib), never re-implemented here.
 */
object TtrgGraphMutations {
    /** Whether the file already has an `import <packageName>` line. */
    fun hasImportFor(
        content: String,
        packageName: String,
    ): Boolean = Regex("""import\s+${Regex.escape(packageName)}\b""").containsMatchIn(content)

    /**
     * Insert [qname] into the `objects: […]` list, optionally adding an `import`
     * line for [packageToImport] if not already present. Mirrors
     * `buildAddObjectEdit`'s text logic, applied directly (no diffs).
     */
    fun addObject(
        content: String,
        qname: String,
        packageToImport: String?,
    ): String? {
        val brackets = TtrgGraphReader.findObjectsBrackets(content) ?: return null
        val inner = content.substring(brackets.first + 1, brackets.last)
        val insertion =
            if (inner.trim().isEmpty()) {
                qname
            } else if (inner.trimEnd().endsWith(",")) {
                qname
            } else {
                ", $qname"
            }
        var out = content.substring(0, brackets.last) + insertion + content.substring(brackets.last)

        if (packageToImport != null && !hasImportFor(content, packageToImport)) {
            val lastImportIdx = content.lastIndexOf("import ")
            val insertOffset =
                if (lastImportIdx == -1) {
                    0
                } else {
                    val lineEnd = content.indexOf('\n', lastImportIdx)
                    if (lineEnd == -1) content.length else lineEnd + 1
                }
            val importLine = if (lastImportIdx == -1) "import $packageToImport\n\n" else "import $packageToImport\n"
            out = out.substring(0, insertOffset) + importLine + out.substring(insertOffset)
        }
        return out
    }

    /**
     * Remove [qname] from the `objects: […]` list (a whole-token match — a
     * qname that's a substring of another entry is never touched). Optionally
     * prunes the `import` line for its package if no other listed object still
     * needs it. Returns null if [qname] isn't present (nothing to do).
     */
    fun removeObject(
        content: String,
        qname: String,
        pruneUnusedImport: Boolean,
    ): String? {
        val brackets = TtrgGraphReader.findObjectsBrackets(content) ?: return null
        val inner = content.substring(brackets.first + 1, brackets.last)
        val newInner = removeFromObjectsList(inner, qname) ?: return null

        var out = content.substring(0, brackets.first + 1) + newInner + content.substring(brackets.last)

        if (pruneUnusedImport) {
            val packageName = qname.substringBefore('.', missingDelimiterValue = "").ifEmpty { null }
            if (packageName != null && !newInner.contains("$packageName.")) {
                val importPattern = Regex("""import\s+${Regex.escape(packageName)}\b[^\n]*\n?""")
                out = importPattern.replace(out, "")
            }
        }
        return out
    }

    private fun removeFromObjectsList(
        inner: String,
        qname: String,
    ): String? {
        val entries = inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (qname !in entries) return null
        val remaining = entries.filter { it != qname }
        return if (remaining.isEmpty()) "" else remaining.joinToString(", ")
    }

    data class CreateGraphParams(
        val name: String,
        val schema: String,
        val packages: List<String>,
        val objects: List<String>,
        val description: String?,
        val tags: List<String>,
    )

    /** Full content for a brand-new `.ttrg` file. Mirrors `buildCreateGraphContent`. */
    fun createContent(params: CreateGraphParams): String {
        val lines = mutableListOf<String>()
        if (params.packages.isNotEmpty()) {
            params.packages.forEach { lines += "import $it" }
            lines += ""
        }
        val graphProps = mutableListOf("model: ${params.schema}")
        params.description?.let { graphProps += "description: \"$it\"" }
        if (params.tags.isNotEmpty()) graphProps += "tags: [${params.tags.joinToString(", ") { "\"$it\"" }}]"
        val objectsStr =
            if (params.objects.isNotEmpty()) {
                "objects: [${params.objects.joinToString(
                    ", ",
                )}]"
            } else {
                "objects: []"
            }

        lines += "graph ${params.name} {"
        graphProps.forEach { lines += "    $it" }
        lines += "    $objectsStr"
        lines += "}"
        return lines.joinToString("\n") + "\n"
    }
}
