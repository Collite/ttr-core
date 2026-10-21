// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.cn

import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.diagnostics.DiagnosticSeverity
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptProperty
import org.tatrman.ttr.parser.model.EmbeddedBlock
import org.tatrman.ttr.parser.model.LocalizedStringValue
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TaggedBlockValue
import org.tatrman.ttr.parser.model.TargetObjectValue
import org.tatrman.ttr.parser.model.TargetReferenceValue
import org.tatrman.ttr.semantics.ResolutionContext
import org.tatrman.ttr.semantics.ResolutionResult
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolEntry
import org.tatrman.ttr.semantics.buildCanonicalKey

// 0.17 — one grounding per concept (CN-P2 S2.2; cn contracts §1.2, §1.4; CN-D12 strict, CN-D21). Over a project's
// canonical groundings ([desugarConcepts]): resolve the concept each authored binding names and the element each
// grounding targets, group by concept, and report the second and later groundings of a concept — and a
// `concept { }` block that restates an authored concept differently — as `cn/duplicate-grounding`. The first
// grounding in document and source order is the one read. Mirrors the TS twin (`cn/groundings.ts`).

/** One model-level cn diagnostic, placed in its document. */
data class CnDiagnostic(
    val code: DiagnosticCode,
    val message: String,
    /** The slug's grade (cn contracts §7): hygiene and advisory ones are `Information`. */
    val severity: DiagnosticSeverity,
    val uri: String,
    val source: SourceLocation,
)

/** A grounding placed in its document, its concept and target resolved. */
data class PlacedGrounding(
    val grounding: CnGrounding,
    val uri: String,
    /** The document's effective package. */
    val packageName: String,
    /** The grounded concept's key; null when an authored binding's concept slot does not resolve. */
    val conceptKey: String?,
    /** The target element; null when the target does not resolve. */
    val targetSymbol: SymbolEntry?,
)

data class GroundingAnalysis(
    /** Every grounding, in document and source order. */
    val groundings: List<PlacedGrounding>,
    /** Per concept key, its groundings in document and source order — the first is the one read. */
    val byConcept: Map<String, List<PlacedGrounding>>,
    val diagnostics: List<CnDiagnostic>,
)

/** The key of the concept [name] declared in [packageName]: `<pkg>.cn.concept.<name>`. */
fun conceptKeyOf(
    packageName: String,
    name: String,
): String = buildCanonicalKey(packageName, "", "concept", listOf(name))

/** Document, then source offset — "package / source order" (S2.2). */
internal val BY_PLACE: Comparator<Pair<String, SourceLocation>> =
    compareBy<Pair<String, SourceLocation>>({ it.first }, { it.second.offsetStart }, { it.second.offsetEnd })

/** The resolution context of a reference written in [doc]. */
internal fun contextOf(
    doc: SemanticDocument,
    enclosingQname: String? = null,
): ResolutionContext = ResolutionContext(doc.schemaCode, doc.namespace, enclosingQname, doc.imports, doc.packageName)

/**
 * A property's meaning as a string: its value with every source position dropped; a concept ref's optional `cn.`
 * head is dropped (`Order` and `cn.Order` say the same). Two properties under one key differ when their
 * fingerprints do. Mirrors TS `propertyFingerprint` (an equality, not the same string).
 */
fun propertyFingerprint(p: ConceptProperty): String =
    buildString {
        append(p::class.simpleName).append('|').append(p.key).append('|')
        when (p) {
            is ConceptProperty.Description -> append(p.value?.let(::valueFingerprint)).append(p.localized?.let(::locFp))
            is ConceptProperty.Label -> append(locFp(p.value))
            is ConceptProperty.Tags -> append(p.value.joinToString(",") { quoted(it) })
            is ConceptProperty.Ref -> append(refPath(p.ref.path))
            is ConceptProperty.Grounds -> append(refPath(p.ref.path))
            is ConceptProperty.ValueSpace -> append(refPath(p.ref.path))
            is ConceptProperty.Involves ->
                append(p.participants.joinToString(",") { "${refPath(it.ref.path)} as ${it.label}" })
            is ConceptProperty.Period -> append(valueFingerprint(p.value))
            is ConceptProperty.Extent -> append(quoted(p.value))
            is ConceptProperty.Basis -> append(quoted(p.value))
            is ConceptProperty.DefinitionBlock -> append(blockFp(p.block))
            is ConceptProperty.MembersBlock -> append(blockFp(p.block))
            is ConceptProperty.FilterBlock -> append(blockFp(p.block))
            // A `lexicon { }` block by its five known keys, each form quoted (`["red,blue"]` ≠ `["red", "blue"]`) —
            // the canonical form TS and Python share (review-119 M-10).
            is ConceptProperty.Lexicon ->
                append(
                    listOf(
                        p.block.terms,
                        p.block.patterns,
                        p.block.examples,
                    ).joinToString("|") { forms -> forms.joinToString(",", "[", "]") { quoted(it) } },
                ).append('|').append(p.block.default).append('|').append(p.block.sensePriority)
            is ConceptProperty.Target ->
                when (val v = p.value) {
                    is TargetReferenceValue -> append("ref:").append(refPath(v.ref.path))
                    is TargetObjectValue -> append(valueFingerprint(v.obj))
                }
            is ConceptProperty.Entry -> append(valueFingerprint(p.value))
        }
    }

private fun refPath(path: String): String = if (path.startsWith("cn.")) path.substring(3) else path

private fun quoted(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun locFp(v: LocalizedStringValue): String =
    v.byLanguage.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { "${it.key}:${quoted(it.value)}" }

private fun blockFp(b: EmbeddedBlock): String = "${b.language}/${b.form}/${quoted(b.text)}"

private fun valueFingerprint(v: PropertyValue): String =
    when (v) {
        is PropertyValue.StringValue -> "s" + quoted(v.raw)
        is PropertyValue.TripleStringValue -> "t" + quoted(v.raw)
        is PropertyValue.NumberValue -> "n${v.raw}"
        is PropertyValue.BoolValue -> "b${v.raw}"
        is PropertyValue.NullValue -> "null"
        is PropertyValue.IdValue -> "id:" + refPath(v.ref.path)
        is PropertyValue.ListValue -> v.items.joinToString(",", "[", "]") { valueFingerprint(it) }
        // entries sorted by key: `{ start:, end: }` says what `{ end:, start: }` says (review-119 M-10)
        is PropertyValue.ObjectValue ->
            v.entries.entries
                .sortedBy { it.key }
                .joinToString(",", "{", "}") { "${quoted(it.key)}:${valueFingerprint(it.value)}" }
        is PropertyValue.FunctionCall -> v.name + v.args.joinToString(",", "(", ")") { valueFingerprint(it) }
        is TaggedBlockValue -> v.tag + quoted(v.value)
    }

/**
 * The sugar merge rule (S2.2 T3, cn contracts §1.4): a `concept { }` block may name a concept an authored cn def
 * declares when it adds only the grounding and properties the def leaves unset. A different stereotype, or a
 * property both state with different values, is `cn/duplicate-grounding` at the block's slot.
 */
private fun mergeConflicts(
    sugar: SugarConcept,
    uri: String,
    def: ConceptDef,
    defUri: String,
): List<CnDiagnostic> {
    val at = "$defUri:${def.source.line}"
    if (sugar.stereotype != def.stereotype) {
        return listOf(
            CnDiagnostic(
                DiagnosticCode.CnDuplicateGrounding,
                "'concept { ${sugar.declaring.key}: ${sugar.name} }' declares a ${sugar.stereotype}, but " +
                    "'${sugar.name}' is a ${def.stereotype} ($at)",
                DiagnosticSeverity.Error,
                uri,
                sugar.declaring.source,
            ),
        )
    }
    val out = mutableListOf<CnDiagnostic>()
    for (p in sugar.sugar.body.properties) {
        if (p === sugar.declaring) continue
        val theirs = def.body.properties.firstOrNull { it.key == p.key } ?: continue
        if (propertyFingerprint(theirs) == propertyFingerprint(p)) continue
        out +=
            CnDiagnostic(
                DiagnosticCode.CnDuplicateGrounding,
                "'${p.key}' of '${sugar.name}' differs from its concept def ($at) — a 'concept { }' block may add " +
                    "only what the def leaves unset",
                DiagnosticSeverity.Error,
                uri,
                p.source,
            )
    }
    return out
}

/**
 * Resolve, group and check the groundings of a project. [resolver] must be built over the symbol table of exactly
 * these documents (the desugared concepts resolve through it).
 */
fun analyzeGroundings(
    docs: List<SemanticDocument>,
    resolver: Resolver,
): GroundingAnalysis {
    val groundings = mutableListOf<PlacedGrounding>()
    val diagnostics = mutableListOf<CnDiagnostic>()
    val sugars = mutableListOf<Triple<SugarConcept, String, String>>()
    val authored = LinkedHashMap<String, Pair<ConceptDef, String>>()

    for (doc in docs) {
        for (def in doc.definitions) {
            if (def !is ConceptDef) continue
            authored.putIfAbsent(conceptKeyOf(doc.packageName, def.name), def to doc.uri)
        }
        val desugar = desugarConcepts(doc.definitions, doc.packageName)
        for (c in desugar.concepts) sugars += Triple(c, doc.uri, conceptKeyOf(doc.packageName, c.name))
        for (g in desugar.groundings) {
            val conceptKey =
                when {
                    g.conceptName != null -> conceptKeyOf(doc.packageName, g.conceptName)
                    g.conceptRef != null -> {
                        val r =
                            resolver.resolveReference(
                                Resolver.Ref(g.conceptRef.path, g.conceptRef.parts, true),
                                contextOf(doc),
                            )
                        (r as? ResolutionResult.Resolved)?.symbol?.qname
                    }
                    else -> null
                }
            val targetSymbol =
                when {
                    g.targetKey != null -> resolver.getSymbol(g.targetKey)
                    g.target != null -> {
                        val enclosing =
                            if (g.origin == GroundingOrigin.Binding) {
                                buildCanonicalKey(
                                    doc.packageName,
                                    "",
                                    g.bindingKind ?: "conceptBinding",
                                    listOf(g.name),
                                )
                            } else {
                                conceptKey
                            }
                        val r =
                            resolver.resolveReference(
                                Resolver.Ref(g.target.path, g.target.parts),
                                contextOf(doc, enclosing),
                            )
                        (r as? ResolutionResult.Resolved)?.symbol
                    }
                    else -> null
                }
            groundings += PlacedGrounding(g, doc.uri, doc.packageName, conceptKey, targetSymbol)
        }
    }
    groundings.sortWith(compareBy(BY_PLACE) { it.uri to it.grounding.source })

    val byConcept = LinkedHashMap<String, MutableList<PlacedGrounding>>()
    for (g in groundings) {
        if (g.grounding.bindingKind == null || g.conceptKey == null) continue
        byConcept.getOrPut(g.conceptKey) { mutableListOf() } += g
    }
    for ((key, list) in byConcept) {
        val first = list[0]
        for (extra in list.drop(1)) {
            diagnostics +=
                CnDiagnostic(
                    DiagnosticCode.CnDuplicateGrounding,
                    "'${key.substringAfterLast(
                        '.',
                    )}' is already grounded at ${first.uri}:${first.grounding.source.line} — " +
                        "one grounding per concept (CN-D12)",
                    DiagnosticSeverity.Error,
                    extra.uri,
                    extra.grounding.source,
                )
        }
    }

    for ((concept, uri, key) in sugars) {
        val (def, defUri) = authored[key] ?: continue
        diagnostics += mergeConflicts(concept, uri, def, defUri)
    }
    diagnostics.sortWith(compareBy(BY_PLACE) { it.uri to it.source })

    return GroundingAnalysis(groundings, byConcept, diagnostics)
}
