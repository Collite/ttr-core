// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.LexiconViolation
import org.tatrman.ttr.lexicon.SkillDef

/**
 * RV-P1.3 — the operator standard library: the six ruled operators (⚑RV-2), shipped as ordinary
 * `ttr-skill/v1` files and compiled through exactly the same path an estate's own skills take.
 *
 * **Why it lives in `tatrman` and not in `tatrman-server`,** which is what the P1.3 list assumed:
 * the stdlib is *compiler input*. The compile happens here (P1.2 T6 — the toolchain CLI and the
 * Modeler CLI path), and the toolchain cannot depend on the server. Shipping it with the server
 * would leave every estate build without a stdlib to layer under its own files. Same reasoning
 * that moved P1.1 here under the (a3) ruling; recorded on the P1.3 list.
 *
 * The files are resources rather than Kotlin constants on purpose: an operator body is prose a
 * non-engineer should be able to read and revise, and it must be diffable as prose.
 */
object LexiconStdlib {
    /** Resource path of the stdlib area, shaped like an estate's `lexicon/`. */
    const val ROOT: String = "/lexicon-stdlib/skills"

    /** RV-P1.6 (RV-42) — the grounding trigger slices, beside the operator skills. */
    const val GROUNDING_ROOT: String = "/lexicon-stdlib/grounding"

    /** LP §3.3 — the string-predicate trigger slice, beside the grounding ones. */
    const val PREDICATES_ROOT: String = "/lexicon-stdlib/predicates"

    /**
     * The predicate slice files. ONE file for all five refs, unlike grounding's file-per-kernel:
     * a `ground:` file is a kernel's vocabulary and a kernel owns its own words, whereas the five
     * predicates are one closed family read by one consumer, and splitting them would only let
     * *starts_with* and *ends_with* drift apart in style.
     */
    val PREDICATE_SLICES: List<String> = listOf("string")

    /** The ruled set (⚑RV-2). Listed explicitly: a stdlib that quietly loses a file is worse than one that fails. */
    val OPERATORS: List<String> =
        listOf("show", "trend", "compare", "drilldown", "top-n", "share-of")

    /**
     * Loads all six. Throws on a missing or malformed file: this is shipped content, so a failure
     * here is a broken build of the toolchain itself, not an authoring mistake anyone can fix by
     * editing their repo.
     */
    fun skills(): List<SkillDef> =
        OPERATORS.map { name ->
            val path = "$ROOT/$name.md"
            val text =
                requireNotNull(LexiconStdlib::class.java.getResourceAsStream(path)) {
                    "operator stdlib is missing $path"
                }.reader().readText()

            when (val load = LexiconValidator.loadSkillFile(text, "stdlib/skills/$name.md")) {
                is LexiconLoad.Ok -> load.value
                is LexiconLoad.Rejected -> error("operator stdlib file $path is invalid: ${render(load.violations)}")
            }
        }

    /**
     * RV-P1.6 T3 (RV-42) — the grounding trigger slices, one file per kernel.
     *
     * Ordinary `ttr-lexicon/v1` data files compiled through exactly the same path an estate's own
     * `.lex.yaml` takes; the only thing that makes them a slice is the `ground:` target class the
     * compiler derives from the prefix. Layered like [skills]: stdlib first, estate second, so an
     * estate that declares "fiskál" for `ground:chrono` extends the shipped vocabulary rather than
     * replacing it.
     *
     * The kind list mirrors [LexiconValidator.GROUNDING_KINDS] — a kernel with no shipped slice
     * would be a silent hole, so the two are asserted equal in the spec rather than hoped equal.
     */
    fun groundingSlices(): List<LexiconDataFile> =
        LexiconValidator.GROUNDING_KINDS.sorted().map { kind ->
            val path = "$GROUNDING_ROOT/$kind.lex.yaml"
            val text =
                requireNotNull(LexiconStdlib::class.java.getResourceAsStream(path)) {
                    "grounding stdlib is missing $path"
                }.reader().readText()

            when (val load = LexiconValidator.loadDataFile(text, "stdlib/grounding/$kind.lex.yaml")) {
                is LexiconLoad.Ok -> load.value
                is LexiconLoad.Rejected -> error("grounding stdlib file $path is invalid: ${render(load.violations)}")
            }
        }

    /**
     * LP §3.3 T3 — the string-predicate trigger slice, loaded exactly as [groundingSlices] is.
     *
     * Ordinary `ttr-lexicon/v1` data files; the only thing that makes them a predicate slice is
     * the `pred:` target class the compiler derives from the prefix. Layered the same way too —
     * stdlib first, estate second — so an estate that adds *v popisu* for `pred:contains` extends
     * the shipped vocabulary instead of replacing it.
     */
    fun predicateSlices(): List<LexiconDataFile> =
        PREDICATE_SLICES.map { name ->
            val path = "$PREDICATES_ROOT/$name.lex.yaml"
            val text =
                requireNotNull(LexiconStdlib::class.java.getResourceAsStream(path)) {
                    "predicate stdlib is missing $path"
                }.reader().readText()

            when (val load = LexiconValidator.loadDataFile(text, "stdlib/predicates/$name.lex.yaml")) {
                is LexiconLoad.Ok -> load.value
                is LexiconLoad.Rejected -> error("predicate stdlib file $path is invalid: ${render(load.violations)}")
            }
        }

    private fun render(violations: List<LexiconViolation>): String =
        violations.joinToString("; ") { "${it.code} at ${it.provenance.file}:${it.provenance.line} — ${it.message}" }
}
