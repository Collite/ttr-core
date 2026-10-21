// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.conformance

import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.semantics.ResolutionContext
import org.tatrman.ttr.semantics.ResolutionResult
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.ResolvedManifest
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.StockLoader
import org.tatrman.ttr.semantics.SymbolTable
import org.tatrman.ttr.semantics.Validator
import org.tatrman.ttr.semantics.collectAllReferences
import org.tatrman.ttr.semantics.enclosingQnameOf

/**
 * Kotlin side of the *semantics* conformance dump (contracts.md §5): loads the
 * stock vocab, builds the symbol table, resolves every reference and runs the
 * portable validator subset, then emits a `{ diagnostics, resolved }` object
 * byte-identical to the TS `dump-sem.ts` output.
 *
 * The validator subset and the always-load-stock behaviour are mirrored exactly
 * on the TS side; positions are excluded (the Kotlin `Reference` value class
 * carries none), so `resolved` keys on `<refPath> => <qname>` and `diagnostics`
 * on the code only.
 *
 * ⛑ [isPortable] — the `semantics { … }` block validator is NOT part of the portable subset,
 * and this dump used to include it anyway. `Validator.validateDocument` folds
 * `SemanticsAnalyzer` diagnostics in, while the TS twin's `PORTABLE_RULE_IDS` lists no
 * `semantics-*` rule and the Python runtime has no semantics-block analyzer at all — so the
 * Kotlin dump has always been one class of diagnostic wider than the other two. Nothing had
 * surfaced it, because no shared fixture produced a TTR-SEM-2xx code until MS-P1·S1 made a
 * plain legacy `nameAttribute:` emit the TTR-SEM-218 deprecation (fixtures 09 and 28, both
 * untouched by MS).
 *
 * ⚠ Be honest about what that costs: with the filter on, **nothing compares semantics-block
 * diagnostics across runtimes** — `fixtures/59-semantics.ttrm` contributes to the parser AST
 * comparison and to nothing else. The twins are held instead by `SemanticsValidationSpec` ⇄
 * `semantics-block-validation.test.ts`, which cover the same ground **by review, not by
 * construction**: the case names run parallel but are not a mechanism, and a case added to one
 * side alone fails nothing (review-082 F3 counted 49 ⇄ 50 and found one such gap). Making that
 * mechanical — one shared, ordered case-name list both suites assert against — is the real fix
 * and is worth doing the next time this area is opened. It cannot be a conformance dump, because
 * Python has no semantics-block analyzer to compare against at all.
 */
object SemanticsConformanceDump {
    /** One parsed document in a (possibly multi-file) scenario. */
    data class DocInput(
        val uri: String,
        val result: ParseResult,
    )

    /** Single-document dump — the common case. A 1-element delegation to [dumpDocs]. */
    fun dump(
        result: ParseResult,
        uri: String,
    ): String = dumpDocs(listOf(DocInput(uri, result)))

    /**
     * Multi-document dump: builds ONE symbol table from the stock vocab plus every
     * document in the scenario, then resolves references and runs the portable
     * validator subset across all of them. This is what exercises cross-file
     * resolution (same-package siblings, named/wildcard imports). For a single
     * document it is byte-identical to the previous single-doc dump. Mirrors
     * `dumpSemDocs` in dump-sem.ts.
     */
    fun dumpDocs(docs: List<DocInput>): String {
        val symbols = SymbolTable()
        symbols.upsertDocument("stock://cnc-roles.ttr", StockLoader.load(), "cnc", "role", "")

        // Upsert every document FIRST so cross-document lookups see the whole project.
        data class Meta(
            val doc: SemanticDocument,
            val schemaCode: String,
            val namespace: String,
            val packageName: String,
        )
        val metas =
            docs.map { (uri, result) ->
                // "" (no directive) ⇒ the semantics layer derives the schema per
                // definition from its kind. Must match the TS dump (`?? ''`).
                val schemaCode = result.modelDirective?.modelCode ?: ""
                val namespace = result.modelDirective?.schema ?: ""
                val packageName = result.packageName ?: ""
                symbols.upsertDocument(uri, result.definitions, schemaCode, namespace, packageName)
                Meta(
                    SemanticDocument(
                        uri,
                        result.definitions,
                        schemaCode,
                        namespace,
                        packageName,
                        result.imports,
                        result.modelDirective?.source,
                    ),
                    schemaCode,
                    namespace,
                    packageName,
                )
            }

        val resolver = Resolver(symbols)
        val validator = Validator(symbols, resolver, ResolvedManifest())

        val resolved = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        for (m in metas) {
            collectAllReferences(m.doc.definitions).forEach { c ->
                val enc = enclosingQnameOf(c.ownerDef, m.schemaCode, m.namespace, m.packageName)
                // The ref itself: a cn slot's ref carries its context (0.17).
                val res =
                    resolver.resolveReference(
                        c.toRef(),
                        ResolutionContext(m.schemaCode, m.namespace, enc, m.doc.imports, m.packageName),
                    )
                if (res is ResolutionResult.Resolved) resolved += "${c.path} => ${res.symbol.qname}"
            }
            diagnostics +=
                (
                    validator.validateDocument(m.doc) + validator.validateReferences(m.doc) +
                        validator.validateImports(m.doc)
                ).map { it.code.id }.filter(::isPortable)
        }
        // validateProject() is project-global — run once across all documents (0.17: the cn checks with it).
        diagnostics += validator.validateProject(metas.map { it.doc }).map { it.code.id }.filter(::isPortable)

        // Full qnames of the scenario's own definitions (stock vocab excluded).
        val symbolQnames =
            symbols.all().filter { !it.documentUri.startsWith("stock://") }.map { it.qname }

        return render(diagnostics.sorted(), resolved.sorted(), symbolQnames.sorted())
    }

    /** See the ⛑ note on this object: the TTR-SEM-2xx family is outside the portable subset. */
    private fun isPortable(code: String): Boolean = !code.startsWith("TTR-SEM-")

    /** Matches `JSON.stringify({ diagnostics, resolved, symbols }, null, 4)`. */
    private fun render(
        diagnostics: List<String>,
        resolved: List<String>,
        symbols: List<String>,
    ): String {
        fun arr(items: List<String>): String =
            if (items.isEmpty()) {
                "[]"
            } else {
                items.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n    ]") { "        ${quote(it)}" }
            }
        return "{\n    \"diagnostics\": ${arr(diagnostics)},\n    \"resolved\": ${arr(resolved)}," +
            "\n    \"symbols\": ${arr(symbols)}\n}"
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
