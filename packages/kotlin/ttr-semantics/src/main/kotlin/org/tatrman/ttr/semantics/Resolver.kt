// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

/**
 * One step in the six-step resolution chain (contract §4.3). Ordered as tried.
 */
enum class ResolutionStep { Lexical, SamePackage, NamedImport, WildcardImport, AutoImport, FullyQualified }

/** A single resolution attempt, recorded for diagnostics (TS `ResolutionAttempt`). */
data class ResolutionAttempt(
    val step: ResolutionStep,
    val candidate: String,
    val reason: String? = null,
)

/**
 * Outcome of resolving one reference. Mirrors the TS `ResolutionResult` union:
 * either [Resolved] with the winning step, or [Unresolved] carrying the reason
 * (not-found vs ambiguous), the attempts tried, and any ambiguous candidates.
 */
sealed interface ResolutionResult {
    data class Resolved(
        val symbol: SymbolEntry,
        val viaStep: ResolutionStep,
    ) : ResolutionResult

    data class Unresolved(
        val reason: Reason,
        val tried: List<ResolutionAttempt>,
        val candidates: List<SymbolEntry> = emptyList(),
    ) : ResolutionResult

    enum class Reason { NotFound, Ambiguous }
}

/**
 * Context for resolving a (possibly dotted) reference. Carries the referring
 * document's schema/namespace/package plus the optional enclosing-def qname and
 * import list. Mirrors TS `ResolutionContext`.
 */
data class ResolutionContext(
    val schemaCode: String,
    val namespace: String,
    val enclosingQname: String? = null,
    val imports: List<org.tatrman.ttr.parser.model.ImportStatement>? = null,
    val packageName: String? = null,
)

/** Lexical scope for bare-id resolution (TS `LexicalScope`). */
data class LexicalScope(
    val schemaCode: String,
    val namespace: String,
    val enclosing: Enclosing? = null,
) {
    data class Enclosing(
        val kind: String,
        val qname: String,
    )
}

/**
 * Six-step reference resolver. Mirrors `packages/semantics/src/resolver.ts`
 * exactly: lexical → same-package → named-import → wildcard-import (non-recursive)
 * → cnc auto-import → fully-qualified-but-unique. First hit wins.
 */
class Resolver(
    private val symbols: SymbolTable,
) {
    /**
     * What [resolveReference] reads off a reference: its written path and segments, and — for a reference from a
     * cn slot ([CollectedRef.cnContext]) — the cn context. Pass [CollectedRef.toRef] so the context survives.
     */
    data class Ref(
        val path: String,
        val parts: List<String>,
        val cnContext: Boolean = false,
    )

    /** Direct symbol-table lookup by canonical key ([SymbolTable.get]). Mirrors TS `Resolver.getSymbol`. */
    fun getSymbol(qname: String): SymbolEntry? = symbols.get(qname)

    // ----- 0.17: concepts are reachable only from a cn slot or through the `cn.` head (CN-P2 S2.1) -----

    /** A concept or one of its participant labels (model `cn`). */
    private fun isCnSymbol(e: SymbolEntry): Boolean = e.schemaCode == "cn"

    /**
     * What an element reference never reaches: the cn model, and the bindings the cn desugar synthesized —
     * named `<Concept>.grounds` / `<Concept>.concept`, they would otherwise match a bare name by suffix.
     */
    private fun isHiddenFromElements(e: SymbolEntry): Boolean = isCnSymbol(e) || e.cnOrigin != null

    private fun elementGet(qname: String): SymbolEntry? = symbols.get(qname)?.takeUnless(::isHiddenFromElements)

    private fun elementsByPackage(pkg: String): List<SymbolEntry> =
        symbols.getByPackage(pkg).filterNot(::isHiddenFromElements)

    private fun elementsBySuffix(suffix: String): List<SymbolEntry> =
        symbols.getBySuffix(suffix).filterNot(::isHiddenFromElements)

    /** The concept (or participant label) a key reads as ([SymbolTable.get]: authored first). */
    private fun cnSymbol(key: String): SymbolEntry? = symbols.get(key)?.takeIf(::isCnSymbol)

    /** The canonical key of a concept (or `<Name>.<label>`) in [pkg]: `<pkg>.cn.concept.<parts>`. */
    private fun cnKey(
        pkg: String,
        parts: List<String>,
    ): String = (if (pkg.isNotEmpty()) "$pkg." else "") + (listOf("cn", "concept") + parts).joinToString(".")

    fun resolveReference(
        ref: Ref,
        context: ResolutionContext,
    ): ResolutionResult {
        // 0.17 — a cn slot's reference, or one written `cn.<Name>`, resolves among concepts only.
        if (ref.cnContext || (ref.parts.size > 1 && ref.parts[0] == "cn")) return resolveCn(ref, context)

        val tried = mutableListOf<ResolutionAttempt>()

        // Step 1 — lexical (child of the enclosing def).
        val enclosingCandidate = context.enclosingQname?.let { "$it.${ref.path}" }
        if (enclosingCandidate != null) {
            tried += ResolutionAttempt(ResolutionStep.Lexical, enclosingCandidate)
            elementGet(enclosingCandidate)?.let { return ResolutionResult.Resolved(it, ResolutionStep.Lexical) }
            tried.markLastUnknown()
        }

        // Step 2 — same-package (by constructed qname, then by package + name).
        val fullQname = "${context.schemaCode}.${context.namespace}.${ref.path}"
        if (fullQname != context.enclosingQname) {
            tried += ResolutionAttempt(ResolutionStep.SamePackage, fullQname)
            elementGet(fullQname)?.let { return ResolutionResult.Resolved(it, ResolutionStep.SamePackage) }
            tried.markLastUnknown()
        }
        if (context.packageName != null) {
            for (entry in elementsByPackage(context.packageName)) {
                if (entry.name == ref.path && entry.qname != enclosingCandidate && entry.qname != fullQname) {
                    return ResolutionResult.Resolved(entry, ResolutionStep.SamePackage)
                }
            }
        }

        // Steps 3 & 4 — named / wildcard imports.
        val imports = context.imports
        if (imports != null) {
            for (imp in imports) {
                if (imp.wildcard) continue
                if (imp.target.endsWith(".${ref.path}")) {
                    elementGet(imp.target)?.let {
                        return ResolutionResult.Resolved(it, ResolutionStep.NamedImport)
                    }
                }
            }

            val wildcardMatches = mutableListOf<SymbolEntry>()
            for (imp in imports) {
                if (!imp.wildcard) continue
                val matches = elementsByPackage(imp.target).filter { it.name == ref.path && it.qname != fullQname }
                for (m in matches) {
                    if (wildcardMatches.none { it.qname == m.qname }) wildcardMatches += m
                }
            }
            if (wildcardMatches.size > 1) {
                for (m in wildcardMatches) {
                    tried += ResolutionAttempt(ResolutionStep.WildcardImport, m.qname, "ambiguous")
                }
                return ResolutionResult.Unresolved(ResolutionResult.Reason.Ambiguous, tried, wildcardMatches)
            }
            if (wildcardMatches.size == 1) {
                tried += ResolutionAttempt(ResolutionStep.WildcardImport, wildcardMatches[0].qname)
                return ResolutionResult.Resolved(wildcardMatches[0], ResolutionStep.WildcardImport)
            }
        }

        // Step 5 — cnc.* auto-import (stock roles keyed cnc.role.*, no doubling — D15).
        val cncQname = "cnc.role.${ref.path}"
        if (cncQname != fullQname && cncQname != context.enclosingQname) {
            tried += ResolutionAttempt(ResolutionStep.AutoImport, cncQname)
            elementGet(cncQname)?.let { return ResolutionResult.Resolved(it, ResolutionStep.AutoImport) }
            tried.markLastUnknown()
        }

        // Step 6 — fully-qualified-but-unique across the project. Try the written
        // form, then the classified tail — the trailing name path with leading
        // model/package/schema/kind segments stripped — so a reference naming a
        // slot the v4.0 key now carries explicitly (`db.dbo.Orders` →
        // `…db.dbo.table.Orders`) still resolves by suffix.
        val tail = classifiedTail(ref.path)
        val forms = if (tail.isNotEmpty() && tail != ref.path) listOf(ref.path, tail) else listOf(ref.path)
        for (cand in forms) {
            val uniqueMatches =
                elementsBySuffix(cand)
                    .filter { it.qname != fullQname && it.qname != cncQname }
                    .distinctBy { it.qname }
            if (uniqueMatches.size == 1) {
                tried += ResolutionAttempt(ResolutionStep.FullyQualified, uniqueMatches[0].qname)
                return ResolutionResult.Resolved(uniqueMatches[0], ResolutionStep.FullyQualified)
            }
        }

        return ResolutionResult.Unresolved(ResolutionResult.Reason.NotFound, tried)
    }

    /**
     * 0.17 — a reference from a cn slot, or one written with the `cn.` head, resolves among concepts only:
     * `<pkg>.cn.concept.<Name>`, or an event's participant label `<Name>.<label>`. The steps mirror the element
     * ones — package-qualified, same package, named import, wildcard import, then a unique match across the
     * project (`unimported-reference` names the missing import). It never falls back to an element of the same
     * name. Mirrors TS `Resolver.resolveCn`.
     */
    private fun resolveCn(
        ref: Ref,
        context: ResolutionContext,
    ): ResolutionResult {
        val tried = mutableListOf<ResolutionAttempt>()
        val parts = if (ref.parts.size > 1 && ref.parts[0] == "cn") ref.parts.drop(1) else ref.parts

        fun lookup(
            step: ResolutionStep,
            key: String,
        ): SymbolEntry? {
            tried += ResolutionAttempt(step, key)
            val symbol = cnSymbol(key)
            if (symbol == null) tried.markLastUnknown()
            return symbol
        }

        // Package-qualified (`hartland.core.Party`): a known package prefix that leaves a name.
        val packages = symbols.listPackages().toSet()
        for (i in parts.size - 1 downTo 1) {
            val pkg = parts.subList(0, i).joinToString(".")
            if (pkg !in packages) continue
            lookup(ResolutionStep.FullyQualified, cnKey(pkg, parts.drop(i)))?.let {
                return ResolutionResult.Resolved(it, ResolutionStep.FullyQualified)
            }
        }

        lookup(ResolutionStep.SamePackage, cnKey(context.packageName ?: "", parts))?.let {
            return ResolutionResult.Resolved(it, ResolutionStep.SamePackage)
        }

        val imports = context.imports ?: emptyList()
        for (imp in imports) {
            if (imp.wildcard) continue
            val segs = imp.target.split('.')
            if (segs.size < 2 || segs.last() != parts[0]) continue
            lookup(ResolutionStep.NamedImport, cnKey(segs.dropLast(1).joinToString("."), parts))?.let {
                return ResolutionResult.Resolved(it, ResolutionStep.NamedImport)
            }
        }

        val wildcardMatches =
            imports
                .filter { it.wildcard }
                .mapNotNull { cnSymbol(cnKey(it.target, parts)) }
                .distinctBy { it.qname }
        if (wildcardMatches.size > 1) {
            for (m in wildcardMatches) tried += ResolutionAttempt(ResolutionStep.WildcardImport, m.qname, "ambiguous")
            return ResolutionResult.Unresolved(ResolutionResult.Reason.Ambiguous, tried, wildcardMatches)
        }
        if (wildcardMatches.size == 1) {
            tried += ResolutionAttempt(ResolutionStep.WildcardImport, wildcardMatches[0].qname)
            return ResolutionResult.Resolved(wildcardMatches[0], ResolutionStep.WildcardImport)
        }

        val anywhere =
            symbols
                .getBySuffix(cnKey("", parts))
                .filter(::isCnSymbol)
                .distinctBy { it.qname }
                .map { cnSymbol(it.qname) ?: it }
        if (anywhere.size == 1) {
            tried += ResolutionAttempt(ResolutionStep.FullyQualified, anywhere[0].qname)
            return ResolutionResult.Resolved(anywhere[0], ResolutionStep.FullyQualified)
        }
        if (anywhere.size > 1) {
            for (m in anywhere) tried += ResolutionAttempt(ResolutionStep.FullyQualified, m.qname, "ambiguous")
            return ResolutionResult.Unresolved(ResolutionResult.Reason.Ambiguous, tried, anywhere)
        }
        return ResolutionResult.Unresolved(ResolutionResult.Reason.NotFound, tried)
    }

    /** Kind segments that may lead a written reference (camelCase + MD aliases). */
    private val kindKeywords =
        setOf(
            "entity",
            "attribute",
            "relation",
            "table",
            "view",
            "column",
            "index",
            "constraint",
            "fk",
            "procedure",
            "query",
            "drillMap",
            "role",
            "project",
            "area",
            "er2dbEntity",
            "er2dbAttribute",
            "er2dbRelation",
            "er2cncRole",
            "domain",
            "map",
            "dimension",
            "hierarchy",
            "measure",
            "cubelet",
            "md2db_cubelet",
            "md2db_domain",
            "md2db_map",
            "md2er_cubelet",
        )

    /**
     * The trailing name path of a dotted reference, with any leading
     * model/package/schema/kind segments stripped (D4 slot order). Mirrors TS
     * `Resolver.classifiedTail` / `classifyReference`.
     */
    private fun classifiedTail(path: String): String {
        val segs = path.split('.').filter { it.isNotEmpty() }
        val packages = symbols.listPackages().filter { it.isNotEmpty() }.toSet()
        val schemas = mutableSetOf("dbo")
        for (q in symbols.allQnames()) {
            val parts = q.split('.')
            val i = parts.indexOf("db")
            if (i >= 0 && i + 1 < parts.size) schemas += parts[i + 1]
        }
        var i = 0
        var model = false
        var schema = false
        if (i < segs.size && segs[i] in MODEL_CODES) {
            i++
            model = true
        }
        run {
            var best = -1
            for (j in i until segs.size) {
                if (segs.subList(i, j + 1).joinToString(".") in packages) best = j
            }
            if (best >= i) i = best + 1
        }
        if (i < segs.size && segs[i] in schemas) {
            i++
            schema = true
        }
        // The model may also follow the package (`pkg.er.entity.x`) — only when none led (TS `classifyReference`;
        // since 0.17 `cn` is a model code, and `db.dbo.cn.x` names a column `cn`, not the cn model).
        if (!model && i < segs.size && segs[i] in MODEL_CODES) i++
        // schema may follow the post-package model (`pkg.db.dbo.x`).
        if (!schema && i < segs.size && segs[i] in schemas) i++
        if (i < segs.size && segs[i] in kindKeywords) i++
        return segs.subList(i, segs.size).joinToString(".")
    }

    fun resolveBareId(
        name: String,
        scope: LexicalScope,
    ): ResolutionResult {
        val tried = mutableListOf<ResolutionAttempt>()

        if (scope.enclosing != null) {
            val withEnclosing = "${scope.enclosing.qname}.$name"
            tried += ResolutionAttempt(ResolutionStep.Lexical, withEnclosing)
            symbols.get(withEnclosing)?.let { return ResolutionResult.Resolved(it, ResolutionStep.Lexical) }
            tried.markLastUnknown()
        }

        val withSchema = "${scope.schemaCode}.${scope.namespace}.$name"
        if (withSchema != scope.enclosing?.qname) {
            tried += ResolutionAttempt(ResolutionStep.SamePackage, withSchema)
            symbols.get(withSchema)?.let { return ResolutionResult.Resolved(it, ResolutionStep.SamePackage) }
            tried.markLastUnknown()
        }

        val cncQname = "cnc.role.$name"
        if (cncQname != withSchema) {
            tried += ResolutionAttempt(ResolutionStep.AutoImport, cncQname)
            symbols.get(cncQname)?.let { return ResolutionResult.Resolved(it, ResolutionStep.AutoImport) }
            tried.markLastUnknown()
        }

        return ResolutionResult.Unresolved(ResolutionResult.Reason.NotFound, tried)
    }
}

/** Stamp `reason = "unknown-symbol"` on the most recent attempt (mirrors TS). */
private fun MutableList<ResolutionAttempt>.markLastUnknown() {
    if (isNotEmpty()) this[lastIndex] = this[lastIndex].copy(reason = "unknown-symbol")
}
