// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.diagnostics.DiagnosticSeverity

/**
 * Canonical duplicate-definition rule, shared by project validation and strict metadata reporting.
 * [location] lets checkout reports render storage-relative paths without changing symbol identities.
 */
fun duplicateDefinitions(
    duplicates: List<Duplicate>,
    location: (SymbolEntry) -> String = { "${it.documentUri}:${it.source.line}" },
): List<ValidationDiagnostic> =
    duplicates.flatMap { duplicate ->
        val hasInline = duplicate.entries.any { it.mappingSource == MappingSource.Inline }
        val kind = duplicate.entries.first().kind
        // Inline bindings may coexist with an explicit mapping for the same target.
        val inlineBinding = hasInline && (kind == "er2dbEntity" || kind == "er2dbAttribute" || kind == "er2dbRelation")
        // Concept sugar merges with explicit definitions; duplicate groundings have their own check.
        val entries = duplicate.entries.filter { it.cnOrigin == null }
        if (inlineBinding || entries.size < 2) {
            emptyList()
        } else {
            entries.map { entry ->
                val others =
                    entries
                        .filter {
                            !(it.documentUri == entry.documentUri && it.source.line == entry.source.line)
                        }.joinToString(", ", transform = location)
                ValidationDiagnostic(
                    DiagnosticCode.DuplicateDefinition,
                    DiagnosticSeverity.Error,
                    "Duplicate definition of '${duplicate.qname}' (also at $others)",
                    entry.source,
                )
            }
        }
    }
