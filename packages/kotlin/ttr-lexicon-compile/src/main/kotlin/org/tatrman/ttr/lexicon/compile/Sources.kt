// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import org.tatrman.ttr.lexicon.EntryProvenance
import org.tatrman.ttr.lexicon.Lang
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.MatchMethod
import org.tatrman.ttr.lexicon.MatchProfile
import org.tatrman.ttr.lexicon.SourceTag
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.parser.loader.ParseResult
import java.nio.file.Path

//
// RV-P1.2 — the compiler's inputs (contracts §2).
//
// Three extractors produce one row type; one merge stage classifies, validates, dedups and
// orders. The extractors are deliberately dumb: they do not know what a model contains, so
// they cannot silently drop a row for the wrong reason. Everything that can reject a row
// happens once, in `LexiconCompiler`, where the warning can be attributed.
//

/**
 * A pre-classification row. `targetRef` is still just a string here — classifying it is the
 * merge stage's job, because that is where a dangling ref becomes an attributable warning
 * (RV-20) rather than a silent `null`.
 */
data class SourceRow(
    val text: String,
    val lang: Lang,
    val method: MatchMethod,
    val targetRef: String,
    val sourceTag: SourceTag,
    val provenance: EntryProvenance,
    /**
     * RV-44 — the row's **resolved** matching profile, or null where the layer has none.
     *
     * Resolution happens in the extractor rather than here, because whether a layer gets a profile
     * at all is a property of the layer (⚑M-2: declared yes, metadata no), and the extractor is the
     * one place that knows which layer it is producing.
     */
    val profile: MatchProfile? = null,
    /**
     * CN-P4 (cn contracts §1.5) — the entry's `default: true` and `sense_priority:`, as the TTR-M surface writes them
     * (a `def term` or a concept's inline `lexicon { }`). The data-file surface has neither: false / null there.
     */
    val defaultSense: Boolean = false,
    val sensePriority: Double? = null,
)

/** One parsed `model lexicon [locale <id>]` unit — the TTR-M sugar side of the DECLARED layer. */
data class TtrmLexiconUnit(
    val file: String,
    val parsed: ParseResult,
)

/** Everything the compile reads. Any layer may be empty; an empty repo compiles to an empty table. */
data class LexiconSources(
    val area: LexiconArea = LexiconArea(emptyList(), emptyList()),
    val ttrm: List<TtrmLexiconUnit> = emptyList(),
    val model: Model? = null,
    /**
     * The estate root [model]'s `sourceFile` paths are spelled relative to (see [repoRelative]).
     *
     * A `Path` here is path ARITHMETIC, never I/O: the compiler's determinism rests on it reading
     * no file system, and this is the base of a `relativeTo`, nothing more. Null for a caller with
     * no repo on disk — the compiler specs build a `Model` in memory — in which case the paths the
     * model carries pass through unchanged, exactly as before.
     */
    val repoRoot: Path? = null,
    /**
     * ttr-core#325 — the package names the estate's `modeler.toml` declares: `[packages] root` and the name of each
     * `[packages."<name>"]` table. In an estate whose files have no `package` line, the TypeScript side puts every file
     * in the `root` package and `@tatrman/edit` writes it in front of a `for:` target, yet no unit and no model object
     * here carries it. Empty without a manifest.
     */
    val manifestPackages: List<String> = emptyList(),
)

/**
 * The model snapshot a ref is validated against (RV-20). `op:` and `ground:` refs never reach
 * this — their class is in the prefix, and neither lives in the model graph.
 *
 * Returns null when the ref is not in the snapshot: that is a dangling ref, and the row is
 * dropped with a build warning rather than shipped as a binding that can never match.
 */
fun interface ModelRefIndex {
    fun classify(ref: String): TargetClass?

    companion object {
        /** Nothing resolves — every model-graph ref dangles. For tests of the warning path. */
        val EMPTY: ModelRefIndex = ModelRefIndex { null }
    }
}

/** A build warning. Never fatal: a lexicon with one bad row still compiles (RV-20 discipline). */
data class CompileWarning(
    val code: String,
    val message: String,
    val provenance: EntryProvenance,
) {
    companion object {
        /** The target ref is not in the model snapshot — row dropped. */
        const val DANGLING_REF: String = "RG-LEXC-001"

        /** Two rows agree on term+lang+target but disagree on method — the stronger one wins. */
        const val METHOD_CONFLICT: String = "RG-LEXC-002"

        /** An estate skill file overrides a stdlib op of the same id. */
        const val OPERATOR_OVERRIDE: String = "RG-LEXC-003"

        /**
         * MH T1 — a DECLARED row folds equal to a row of ANOTHER target: both refs claim the word
         * at runtime, so the matcher sees two candidates on one anchor.
         *
         * The key is `TermNormalizer.fold`, not the merge normalization — the anchor index the
         * resolver queries strips diacritics, so `vyroba` and `výroba` are ONE anchor there even
         * though they stay two rows here. Never fatal and never archive content: a declared
         * homonym can be exactly what the estate meant (an MH resolver decides it by slot), and
         * only an author can say which. The `@tatrman/lint` twin
         * (`ttr/lexicon-form-collides-with-name`) reports the same thing at `verify-model` time.
         */
        const val FORM_COLLISION: String = "RG-LEXC-004"

        /**
         * ttr-core#325 — `modeler.toml` is there but its `[packages]` could not be read (it does not parse, or
         * `packages` is not a table). The build carries on with no manifest packages, so a `for:` qualified with one
         * of them drops as RG-LEXC-001; this says why.
         */
        const val MANIFEST_UNREADABLE: String = "RG-LEXC-005"

        /**
         * CN-P4 (A-CN-8, AM-36) — a `default: true` on a PHASE's term whose form a concept up the phase's `of:` chain
         * also names: the archive drops that default, so the resolver never sees it. The code is the lint's own slug —
         * the same condition `ttr cn hygiene` reports, and two codes for one condition is what the area loader's
         * warnings avoid by travelling unchanged.
         */
        const val PHASE_AS_DEFAULT: String = "cn/phase-as-default"

        /**
         * File, then line, then code, then message. Public because the build folds the AREA
         * LOADER's warnings (RV-44's ⚑M-4 guard) into the compiler's own stream, and two streams
         * printed in two orders would read as two kinds of thing.
         */
        val ORDER: Comparator<CompileWarning> =
            compareBy({ it.provenance.file }, { it.provenance.line }, { it.code }, { it.message })
    }
}
