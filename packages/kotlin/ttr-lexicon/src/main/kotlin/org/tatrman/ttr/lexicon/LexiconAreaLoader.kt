// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * Loads a whole `lexicon/` data area (RV-P1.1 T7) — the sibling area of a defining repo's
 * `model/`, per RV-36.
 *
 * Layout, from contracts §2 (+ LP §3.3): `aliases/`, `values/`, `grounding/` and `predicates/`
 * hold `.lex.yaml` data files; `skills/` holds markdown skills. Unknown subdirectories are ignored
 * rather than rejected — an estate keeping notes beside its lexicon is not an error.
 *
 * The directory is layout, never meaning: what a row IS comes from its target class, derived from
 * the ref (RV-38). A `pred:` entry in `aliases/` compiles exactly the same — the folders are for
 * the humans who maintain the files.
 *
 * **Every file is reported, not just the first bad one.** A lexicon area is authored by
 * hand, often in bulk; failing on file 1 of 40 turns one editing session into forty.
 */
object LexiconAreaLoader {
    private val DATA_DIRS = setOf("aliases", "values", "grounding", "predicates")
    private const val SKILLS_DIR = "skills"
    private const val DATA_SUFFIX = ".lex.yaml"

    fun load(root: Path): LexiconLoad<LexiconArea> {
        require(root.isDirectory()) { "not a lexicon area: $root" }

        val dataFiles = mutableListOf<LexiconDataFile>()
        val skills = mutableListOf<SkillDef>()
        val violations = mutableListOf<LexiconViolation>()
        val warnings = mutableListOf<LexiconViolation>()

        Files.walk(root).use { stream ->
            stream
                .filter { !it.isDirectory() }
                .sorted() // deterministic order: the compiled artifact's hash depends on it
                .forEach { path ->
                    val rel = path.relativeTo(root).toString()
                    val area = path.parent?.name
                    when {
                        area in DATA_DIRS && path.name.endsWith(DATA_SUFFIX) ->
                            when (val load = LexiconValidator.loadDataFile(path.readText(), rel)) {
                                is LexiconLoad.Ok -> {
                                    dataFiles += load.value
                                    warnings += load.warnings
                                }

                                is LexiconLoad.Rejected -> violations += load.violations
                            }

                        area == SKILLS_DIR && path.extension == "md" ->
                            when (val load = LexiconValidator.loadSkillFile(path.readText(), rel)) {
                                is LexiconLoad.Ok -> {
                                    skills += load.value
                                    warnings += load.warnings
                                }

                                is LexiconLoad.Rejected -> violations += load.violations
                            }

                        else -> Unit
                    }
                }
        }

        return if (violations.isEmpty()) {
            // Every file's warnings, in the walk's own (sorted) order — so the build prints them
            // file by file, the way an author reads their own tree.
            LexiconLoad.Ok(LexiconArea(dataFiles, skills), warnings)
        } else {
            LexiconLoad.Rejected(violations)
        }
    }
}
