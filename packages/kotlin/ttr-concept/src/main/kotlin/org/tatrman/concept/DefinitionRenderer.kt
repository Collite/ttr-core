// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.concept.v1.ConceptRef
import org.tatrman.ttr.md.resolve.PathComponent
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Imprecision
import org.tatrman.ttr.semantics.md.MdCubelet
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `definitionRender` and `materialize` (CN-P3 S3.4 T5/T6; contracts §3) behind one API: ER → [ErRenderer]; MD → S3.3's
 * [MdRenderer] on the bearer's carrying cubelet, its predicate written as PL text ([mdText]).
 */
internal class DefinitionRenderer(
    private val model: ResolvedCnModel,
    private val clock: Clock,
) {
    private val cn = model.cn
    private val er = ErRenderer(model)
    private val md = MdRenderer(model)

    private val byName: Map<String, List<Concept>> = cn.concepts.values.groupBy { it.qname.name }

    /**
     * The one concept [qname] names, resolved as the frames resolve it (`cn.<Name>`, one package — [FrameRefs]); null
     * and why not otherwise — a bad spelling, none, or several packages: refused, never the first (review-125 R125-5).
     */
    private fun concept(qname: String): Pair<Concept?, String> {
        val name =
            FrameRefs.conceptName(qname) ?: return null to "`$qname` is not a concept ref — a target writes `cn.<Name>`"
        val found = byName[name].orEmpty()
        return when (found.size) {
            0 -> null to "no concept `$qname`"
            1 -> found.single() to ""
            else -> {
                val packages = found.map { it.qname.`package` }.sorted().joinToString(", ") { "`$it`" }
                null to "`$qname` names a concept in ${found.size} packages ($packages) — refused, never the first"
            }
        }
    }

    fun render(
        target: ConceptRef,
        layer: Layer,
        options: RenderOptions,
    ): Rendering {
        val (found, why) = concept(target.qname)
        val c =
            found
                ?: return Rendering(
                    diagnostics =
                        listOf(
                            Diagnostic(
                                "cn/definition-unreachable(${layer.name.lowercase()})",
                                Severity.ERROR,
                                why,
                                target.qname,
                            ),
                        ),
                )
        return when (layer) {
            Layer.ER -> er.render(c, options)
            Layer.MD -> md(c, options)
        }
    }

    // ---- MD ---------------------------------------------------------------------------------------------------

    /**
     * MD (S3.3's renderer, A-CN-40): a phase on its bearer's carrying cubelet — an event bearer's own cubelet, a kind's or
     * a role's the one cubelet whose grain carries it (none or several: refused). The text is the PL predicate over the
     * carrier's attributes ([mdText]); a `:name`, `:as_of` and a window's bounds are placeholders in
     * [RenderOptions.placeholderStyle], `window_lo` derived (A-CN-22).
     */
    private fun md(
        c: Concept,
        options: RenderOptions,
    ): Rendering {
        fun refused(message: String) =
            Rendering(
                imprecisionMode = mode(c),
                diagnostics = listOf(Diagnostic(MD_UNREACHABLE, Severity.ERROR, message, c.ref)),
            )
        val temporalSource =
            when {
                c.projection != null -> "valued-state projection"
                c.fromEvent != null -> "event-bounded episode"
                c.grounding?.episodes != null -> "episode-table phase"
                c.stereotype in setOf("kind", "role") && c.involves.isNotEmpty() -> "relator"
                else -> null
            }
        if (temporalSource != null) return refused("`${c.ref}` is a $temporalSource — ER only (G-MD-1)")
        if (c.stereotype !in
            setOf("phase", "subkind")
        ) {
            return refused("the md renderer renders a phase — `${c.ref}` is a ${c.stereotype}")
        }
        if (options.mode == RenderMode.CONTEXT) return refused("CONTEXT mode is an er rendering (A-CN-20)")
        val def =
            model.definitions.of(c.qname).firstOrNull {
                it.kind == BlockKind.DEFINITION ||
                    c.stereotype == "subkind" &&
                    it.kind == BlockKind.MEMBERS
            }
                ?: return refused("`${c.ref}` has no `definition:`")
        val bearer = def.bearer?.let { cn.concepts[it] } ?: return refused("`${c.ref}` has no bearer")
        val carrier = carrier(bearer) ?: return refused("`${bearer.ref}` is carried by no one cubelet")
        val asOf = options.asOf?.let { LocalDate.ofInstant(it, ZoneOffset.UTC) } ?: LocalDate.now(clock)
        return when (val r = md.render(c, carrier, asOf)) {
            is MdRenderer.Result.Unrenderable -> refused(r.message)
            is MdRenderer.Result.Rendered -> {
                val text = MdText(r.predicate, carrier, options.placeholderStyle)
                Rendering(
                    text = text.print(),
                    parametersRead = text.params.map { ParameterRead(it, r.predicate.derivations[it]) },
                    definitionsInlined = r.predicate.inlined.drop(1),
                    imprecisionMode = mode(c),
                )
            }
        }
    }

    private fun carrier(bearer: Concept): Carrier? {
        val loaded = model.md ?: return null
        val faces = MdFace.of(bearer)
        val cubelets: List<MdCubelet> =
            if (bearer.stereotype == "event") {
                faces.mapNotNull { f -> (f.element as? MdElement.Cubelet)?.let { loaded.model.cubelets[it.name] } }
            } else {
                val dims =
                    faces.mapNotNull {
                        when (val e = it.element) {
                            is MdElement.Dimension -> e.name
                            is MdElement.Attribute -> e.dimension
                            else -> null
                        }
                    }
                loaded.model.cubelets.values
                    .filter { c -> c.grain.any { it.substringBeforeLast('.') in dims } }
            }
        return cubelets.distinctBy { it.name }.singleOrNull()?.let { Carrier(it, loaded.model) }
    }

    /**
     * The md predicate as PL text: a conjunction of `<attribute> = <v>`, `<attribute> in (<v>, …)`, `<attribute> >= <lo>
     * and <attribute> <= <hi>`, and — a same-cubelet existence (⚑CN-P9) — `<key> in (<cubelet> where <predicate>)`;
     * attributes as the dot-path spells them (`customer.name`), a member quoted (`'West'`), a placeholder as the style
     * writes it. [params] — the placeholders, in reading order.
     */
    private class MdText(
        val predicate: MdRenderer.Predicate,
        val carrier: Carrier,
        val style: PlaceholderStyle,
    ) {
        val params = linkedSetOf<String>()

        fun print(): String =
            (
                conjunction(predicate.coordinates) +
                    predicate.semiJoins.map { sj ->
                        val inner = conjunction(sj.coordinates)
                        val key = Coord(sj.key, PathComponent.Star).spelled
                        "$key in (${carrier.name}" +
                            (if (inner.isEmpty()) "" else " where ${inner.joinToString(" and ")}") +
                            ")"
                    }
            ).joinToString(" and ")

        private fun conjunction(cs: List<Coord>): List<String> =
            cs.map { c ->
                val a = c.spelled
                when (val t = c.target) {
                    is PathComponent.SetLit -> "$a in (" + t.atoms.joinToString(", ") { atom(it) } + ")"
                    is PathComponent.RangeLit -> "$a >= ${atom(t.lo)} and $a <= ${atom(t.hi)}"
                    else -> "$a = ${atom(t)}"
                }
            }

        private fun atom(c: PathComponent): String {
            predicate.symbols[c]?.let { name ->
                params += name
                return if (style == PlaceholderStyle.COLON) ":$name" else "{$name}"
            }
            return when (c) {
                is PathComponent.Quoted -> "'" + c.text.replace("'", "''") + "'"
                is PathComponent.IntLit -> c.text
                is PathComponent.Ident -> c.text
                else -> c.toString()
            }
        }
    }

    // ---- materialize ------------------------------------------------------------------------------------------

    /**
     * `materialize` (T6, CN-D1, A-CN-16): the phase's predicate-mode er rendering as a logical query and the entity
     * stacked on it, as `.ttrm` text in the phase's package — `def query <phase>_q { sourceText: """sql …""" }` (the
     * query is not named after the entity — LQ) and `def entity <phase>` bound to it, the bearer's key its attributes.
     * The author then replaces the phase's `definition:` by a `cn2er_phase` grounding on the entity (AM-2). A definition
     * reading a `:name` parameter or `:as_of` is refused — a logical query takes none (`cn/materialize-parametric`).
     */
    fun materialize(ref: ConceptRef): ModelText {
        fun refused(
            slug: String,
            message: String,
        ) = ModelText(diagnostics = listOf(Diagnostic(slug, Severity.ERROR, message, ref.qname)))
        val (found, why) = concept(ref.qname)
        val c = found ?: return refused(ER_UNREACHABLE, why)
        if (c.stereotype !in setOf("phase", "subkind")) {
            return refused(ER_UNREACHABLE, "`${c.ref}` is a ${c.stereotype} — materialize writes a phase")
        }
        if (c.stereotype ==
            "subkind"
        ) {
            model
                .definitionDiagnostics()
                .firstOrNull {
                    it.subject == c.ref && it.severity == Severity.ERROR
                }?.let { return ModelText(diagnostics = listOf(it)) }
        }
        val (tree, rendering) = er.select(c)
        rendering.diagnostics.firstOrNull { it.severity == Severity.ERROR }?.let {
            return ModelText(
                diagnostics = listOf(it),
            )
        }
        val select = checkNotNull(tree) // a rendering without an error has its tree
        if (rendering.parametersRead.isNotEmpty()) {
            return refused(
                PARAMETRIC,
                "`${c.ref}` reads ${rendering.parametersRead.joinToString {
                    "`:${it.name}`"
                }} — a logical query takes no " +
                    "parameters (LQ); render it with `definitionRender` and bind them instead",
            )
        }
        val key = (select.from as Source.Entity).name
        val entity = (model.model.schemas["er"] as? ErSchema)?.entities?.values?.firstOrNull { it.qname.name == key }
        val keys = entity?.attributes?.filter { it.isKey }.orEmpty()
        val name =
            c.qname.name
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .lowercase()
        val text =
            buildString {
                appendLine(
                    "// Materialized from ${c.ref} by `ttr cn materialize` (CN-D1): the phase's er rendering as a logical query,",
                )
                appendLine(
                    "// and the entity stacked on it. Replace the phase's `definition:` by a `cn2er_phase` grounding on the entity (AM-2).",
                )
                if (c.qname.`package`.isNotEmpty()) appendLine("package ${c.qname.`package`}\n")
                appendLine("model er")
                appendLine()
                appendLine("def query ${name}_q {")
                appendLine("    sourceText: \"\"\"sql")
                appendLine(rendering.text)
                appendLine("\"\"\"")
                appendLine("}")
                appendLine("def entity $name {")
                appendLine("    binding: { target: { query: er.query.${name}_q } },")
                appendLine(
                    "    attributes: [ " +
                        keys.joinToString(
                            ", ",
                        ) {
                            "def attribute ${it.qname.name.substringAfterLast(
                                '.',
                            )} { type: ${it.type}, isKey: true }"
                        } +
                        " ]",
                )
                appendLine("}")
            }
        return ModelText(files = mapOf("$name.ttrm" to text))
    }

    private fun mode(c: Concept): ImprecisionMode =
        if (c.imprecision == Imprecision.POSSIBLY) ImprecisionMode.POSSIBLY else ImprecisionMode.CERTAINLY

    companion object {
        const val PARAMETRIC = "cn/materialize-parametric"
        const val MD_UNREACHABLE = "cn/definition-unreachable(md)"
        const val ER_UNREACHABLE = "cn/definition-unreachable(er)"
    }
}
