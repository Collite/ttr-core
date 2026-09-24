// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import org.tatrman.ttr.lexicon.EntryProvenance
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconAreaLoader
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconViolation
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.md.MdModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * The real [ModelRefIndex], over a loaded model snapshot.
 *
 * A ref is a MODEL_OBJECT when the snapshot has an object with that dotted qname, and a MEMBER
 * when it is `<attribute-ref>.<code>` for a code the attribute actually declares a label for
 * (design.md: lexicon targets may be attribute-depth refs). Everything else is dangling, which
 * is RV-20's business, not this class's.
 */
fun ModelRefIndex.Companion.of(model: Model): ModelRefIndex {
    val objects = model.objectByQname().entries.associate { (qname, obj) -> qname.dotted() to obj }
    val members =
        buildSet {
            for ((ref, obj) in objects) {
                if (obj is Attribute) for (code in obj.valueLabels.keys) add("$ref.$code")
            }
        }
    return ModelRefIndex { ref ->
        when {
            ref in members -> TargetClass.MEMBER
            ref in objects -> TargetClass.MODEL_OBJECT
            else -> null
        }
    }
}

/**
 * A loader-raised authoring warning, re-homed into the build's warning stream. The code travels
 * unchanged — `RG-LEX-101` says which guard fired, and inventing an `RG-LEXC-*` alias for it would
 * mean two codes for one condition.
 */
private fun LexiconViolation.asCompileWarning() =
    CompileWarning(code, message, EntryProvenance(provenance.file, provenance.line))

/**
 * One repo's lexicon build (RV-P1.2 T6).
 *
 * [violations] are P1.1 schema rejections — files that did not parse. They are kept separate
 * from [CompileResult.warnings] because they mean something different: a violation is a broken
 * file the author must fix, a warning is a compiled artifact with a row missing.
 */
data class LexiconBuildOutcome(
    val result: CompileResult,
    val packed: PackedLexicon,
    val violations: List<LexiconViolation>,
) {
    val ok: Boolean get() = violations.isEmpty()
}

/**
 * RV-P1.2 T6 — the packing entry point.
 *
 * Deliberately **not** a snapshot-pipeline registration: there is no model-snapshot build in
 * `tatrman-server` to register into (T1 finding), and under the (a3) ruling the lexicon is its
 * own archive anyway. Callers are the toolchain CLI and, for estates, the Modeler CLI path that
 * already emits `generated/`.
 *
 * **Flag = the files.** A repo with no `lexicon/` directory and no `model lexicon` units builds
 * exactly as it did before: the declared layer is empty and no warning is produced. The metadata
 * layer still compiles — it is a layer of the model, not of the lexicon area (RV-39), and
 * suppressing it would make the artifact's presence depend on whether anyone had authored an
 * alias yet.
 */
object LexiconBuild {
    /** Where a defining repo keeps the two declared surfaces (contracts §2 / RV-36). */
    const val AREA_DIR: String = "lexicon"
    private const val MODEL_DIR = "model"
    private const val TTRM_SUFFIX = ".ttrm"
    private val MD_MODEL = MdRefs.MD_MODEL_CODE

    /**
     * @param includeStdlib layer the RV-P1.3 operator stdlib under the estate's own skills. On by
     *   default — the six operators are what makes an estate's questions answerable at all, and an
     *   estate that wanted none of them would be the surprising case. Off for tests that assert
     *   exactly what one repo contributes.
     */
    fun run(
        repoRoot: Path,
        model: Model,
        modelSnapshotId: String,
        builtAt: String,
        producedBy: String,
        includeStdlib: Boolean = true,
    ): LexiconBuildOutcome {
        val violations = mutableListOf<LexiconViolation>()
        // RV-44 — authoring warnings raised by the LOADER (today: the ⚑M-4 short-term guard). They
        // are about a file that compiled, so they belong in the same stream as RV-20's dangling
        // refs, not in a second one an author has to know to look at.
        val authoringWarnings = mutableListOf<LexiconViolation>()

        val areaRoot = repoRoot.resolve(AREA_DIR)
        val authored =
            if (!areaRoot.isDirectory()) {
                LexiconArea(emptyList(), emptyList())
            } else {
                when (val load = LexiconAreaLoader.load(areaRoot)) {
                    is LexiconLoad.Ok -> {
                        authoringWarnings += load.warnings
                        load.value
                    }

                    is LexiconLoad.Rejected -> {
                        violations += load.violations
                        LexiconArea(emptyList(), emptyList())
                    }
                }
            }

        // Stdlib FIRST, estate SECOND — that order is the precedence statement the compiler reads
        // (P1.2 T5). An estate redefining `op:trend` wins, and the build says which file it beat.
        val stdlib = if (includeStdlib) LexiconStdlib.skills() else emptyList()
        // Same precedence for the RV-42 grounding slices: shipped vocabulary first, the estate's
        // own `ground:` files second, so an estate extends the kernels' trigger words instead of
        // having to restate them.
        val groundingStdlib = if (includeStdlib) LexiconStdlib.groundingSlices() else emptyList()
        // And the same again for LP's `pred:` slice — shipped words first, the estate's own
        // `pred:` files second.
        val predicateStdlib = if (includeStdlib) LexiconStdlib.predicateSlices() else emptyList()
        val area =
            authored.copy(
                skills = stdlib + authored.skills,
                dataFiles = groundingStdlib + predicateStdlib + authored.dataFiles,
            )

        // ONE walk, every `.ttrm` under `model/`. Each consumer takes what it owns by the unit's
        // `model` directive: the sugar extractor keeps `lexicon` units, the md tier keeps `md`
        // ones. Filtering here instead would mean walking the tree twice and would have hidden the
        // md half from the compiler for a second release.
        val units = ttrmUnits(repoRoot)
        val md =
            MdModel.from(
                units.filter { it.parsed.modelDirective?.modelCode == MD_MODEL }.flatMap { it.parsed.definitions },
            )

        val sources = LexiconSources(area = area, ttrm = units, model = model, repoRoot = repoRoot)
        // er/db/cnc first, md second — the two key spaces cannot collide (the schema token differs),
        // so this order is precedence in principle only. RV-P3.4.
        val refs = ModelRefIndex.of(model) orElse ModelRefIndex.ofMd(md)
        val compiled = LexiconCompiler.compile(sources, refs, modelSnapshotId, builtAt)
        val result =
            if (authoringWarnings.isEmpty()) {
                compiled
            } else {
                compiled.copy(
                    warnings =
                        (compiled.warnings + authoringWarnings.map { it.asCompileWarning() })
                            .sortedWith(CompileWarning.ORDER),
                )
            }
        // The PACK reads `compiled`, not `result`: warnings are build output, never artifact
        // content, so folding one in must not be able to move a byte of the archive.
        return LexiconBuildOutcome(result, LexiconPacker.pack(compiled, modelSnapshotId, producedBy), violations)
    }

    /**
     * Every `.ttrm` under `model/`, parsed once and offered whole to the compiler — each consumer
     * selects by the unit's `model` directive (the sugar extractor keeps `lexicon`, the md metadata
     * tier and the md ref index keep `md`). Filtering by directory name here instead would bind the
     * compiler to one estate's folder habit — hartland happens to use `model/lexicon/<locale>/`,
     * but the model directive is what actually declares a unit's kind.
     *
     * ⚑ The `sorted()` is load-bearing: the artifact's hash depends on the order rows are produced
     * in, and `--check` is a real gate as of RV-P3.1. Reordering this walk would show up as
     * permanent drift in every estate that has committed an archive.
     */
    private fun ttrmUnits(repoRoot: Path): List<TtrmLexiconUnit> {
        val modelRoot = repoRoot.resolve(MODEL_DIR)
        if (!modelRoot.isDirectory()) return emptyList()

        return Files.walk(modelRoot).use { stream ->
            stream
                .filter { !it.isDirectory() && it.toString().endsWith(TTRM_SUFFIX) }
                .sorted() // the artifact's hash depends on the order rows are produced in
                .toList()
                .map { path ->
                    val rel = path.relativeTo(repoRoot).toString()
                    TtrmLexiconUnit(rel, TtrLoader.parseString(path.readText(), rel))
                }
        }
    }
}
