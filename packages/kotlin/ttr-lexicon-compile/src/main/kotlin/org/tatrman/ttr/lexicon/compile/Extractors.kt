// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import org.tatrman.ttr.lexicon.EntryProvenance
import org.tatrman.ttr.lexicon.Lang
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.MatchProfile
import org.tatrman.ttr.lexicon.SourceTag
import org.tatrman.ttr.lexicon.TermDef
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.parser.model.DimensionDef
import org.tatrman.ttr.parser.model.LocalizedStringValue
import java.nio.file.Path
import kotlin.io.path.relativeTo
import org.tatrman.ttr.parser.model.LexiconEntryDef as TtrmLexiconEntryDef

/**
 * DECLARED layer, half one — the `lexicon/` data area.
 *
 * Skill triggers are ordinary vocabulary here (RV-35): the frontmatter compiles into the entry
 * table against the `op:` ref, and the body goes elsewhere entirely.
 */
object AreaExtractor {
    fun rows(area: LexiconArea): List<SourceRow> =
        buildList {
            for (file in area.dataFiles) {
                for (entry in file.entries) {
                    for (term in entry.terms) addAll(rowsFor(term, entry.target))
                }
            }
            for (skill in area.skills) {
                for (trigger in skill.triggers) addAll(rowsFor(trigger, skill.opId))
            }
        }

    private fun rowsFor(
        term: TermDef,
        target: String,
    ): List<SourceRow> =
        listOf(
            SourceRow(
                text = term.text,
                lang = term.lang,
                method = term.method,
                targetRef = target,
                sourceTag = SourceTag.DECLARED,
                provenance = EntryProvenance(term.provenance.file, term.provenance.line),
                // RV-44 — sugar is EXPANDED here, once, so nothing downstream re-derives it. A
                // `method: TYPOS(1)` row and the profile it means are the same row from here on,
                // which is what makes the two score identically (the p3-0 T7 equivalence case).
                profile = term.matchProfile ?: MatchProfile.ofSugar(term.method),
            ),
        )
}

/**
 * DECLARED layer, half two — TTR-M `def term X { for: <ref>, forms: [...] }` in a
 * `model lexicon [locale <id>]` unit (grammar 4.4).
 *
 * `pattern` and `example` defs share the same body but are **not** vocabulary: a pattern is a
 * regex and an example is a whole utterance. Compiling either into the term table would put a
 * sentence in front of the matcher as if it were a word.
 *
 * The unit's `locale` is the lang for every form in it. Unit locales outside the closed lang set
 * fall back to the default rather than dropping the file — a `locale de` unit's terms are still
 * real terms, and losing them silently is worse than tagging them broadly.
 *
 * **Not handled here: the inline `lexicon { terms: [...] }` sugar on a carrier def.** The grammar
 * documents it as desugaring to canonical `term` entries *in semantics*, and doing it here would
 * mean re-deriving each carrier's qualified ref — a second, divergent implementation of package
 * resolution. This extractor consumes desugared output when `ttr-semantics` surfaces it. Recorded
 * as a deferral on the RV-P1.2 list, not an oversight.
 */
object TtrmSugarExtractor {
    fun rows(units: List<TtrmLexiconUnit>): List<SourceRow> =
        buildList {
            for (unit in units) {
                val directive = unit.parsed.modelDirective ?: continue
                if (directive.modelCode != "lexicon") continue
                val lang = directive.locale?.let { Lang.ofWire(it) } ?: LexiconValidator.DEFAULT_LANG

                for (def in unit.parsed.definitions.filterIsInstance<TtrmLexiconEntryDef>()) {
                    if (def.entryKind != "term") continue
                    val target = def.target?.path ?: continue
                    for (form in def.forms) {
                        add(
                            SourceRow(
                                text = form,
                                lang = lang,
                                // The sugar carries no per-form method — the authoring default applies.
                                method = LexiconValidator.DEFAULT_METHOD,
                                targetRef = target,
                                sourceTag = SourceTag.DECLARED,
                                provenance = EntryProvenance(unit.file, def.source.line),
                                // ⚑M-3 — the TTR-M sugar surface keeps `method` only; profiles are
                                // a data-file surface, so the grammar was not touched. The row is
                                // still DECLARED, so it still gets a resolved profile — the one its
                                // method means.
                                profile = MatchProfile.ofSugar(LexiconValidator.DEFAULT_METHOD),
                                // CN-P4 — grammar 0.17's default sense, per entry (cn contracts §1.5).
                                defaultSense = def.default == true,
                                sensePriority = def.sensePriority,
                            ),
                        )
                    }
                }
            }
        }
}

/**
 * The provenance path an archive row records, spelled relative to the estate root.
 *
 * ⚑ **Determinism, not cosmetics.** `provenance.file` is inside the entry table, inside
 * `SourceHashes`, and inside `LexiconCompiler.PRECEDENCE` — so an absolute path makes the archive
 * id depend on *where the repo happens to be checked out*, and `--check` then reports permanent
 * drift for anyone whose clone sits at a different path than the committer's. It bit hartland:
 * `just check-lexicon` failed on a clean master, and 52 rows "changed" between two worktrees of
 * the same commit with no vocabulary difference at all.
 *
 * Only the `ttr-metadata` tier needs this. Every other layer already spells a path relative to
 * something inside the repo — the area loader relativizes against `lexicon/`, `LexiconBuild`'s
 * `.ttrm` walk against the repo root, the stdlib against its own resource root — because each
 * of those layers *walks* files and knows its own base. The model tier does not walk: it reads
 * `sourceFile` off a `Model` that `LocalFsStorage` built from absolute paths so it can read them
 * back, and that string is a filesystem handle in the metadata service. Rewriting it there would
 * change what every model diagnostic on the platform prints; rewriting it here changes only what
 * the archive records, which is the thing that has to be reproducible.
 *
 * Pass-through for anything not under [repoRoot] (a stock/classpath source) and for a path that
 * is already relative — `MdMetadataExtractor` shares [MetadataExtractor.row] and arrives with a
 * repo-relative unit file, so this must be a no-op for it rather than an error.
 */
internal fun repoRelative(
    file: String,
    repoRoot: Path?,
): String {
    if (repoRoot == null || file.isBlank()) return file
    val path = Path.of(file)
    if (!path.isAbsolute) return file
    val root = repoRoot.toAbsolutePath().normalize()
    val normalized = path.normalize()
    return if (normalized.startsWith(root)) normalized.relativeTo(root).toString() else file
}

/**
 * METADATA layer — the names already in the model: `displayLabel`, `labelPlural`, `aliases` and
 * `valueLabels`.
 *
 * **Labels only, never descriptions.** A description is a sentence written for a human reading
 * the model; admitting it as a term would put prose in the matcher, where the best case is that
 * it never matches and the worst is that it matches something.
 *
 * **`valueLabels` are the only member source here.** They are declared members — an author wrote
 * `"1" → "Aktivní"`. Member vocabulary read out of the DATA is a different layer with its own
 * refresh cadence (the lex-matcher index), deliberately not compiled into this artifact.
 *
 * Labels in languages outside the closed lang set are skipped: the artifact's `lang` column is
 * closed by `ttr-lexicon/v1`, so a `de` label has nowhere to go until that set opens. Silent
 * rather than warned — a model richer than the lexicon schema is not an authoring error.
 */
object MetadataExtractor {
    /**
     * @param repoRoot the estate root every provenance path is spelled relative to
     *   ([repoRelative]). Null only for a caller with no repo — the in-memory compiler specs.
     */
    fun rows(
        model: Model,
        repoRoot: Path? = null,
    ): List<SourceRow> =
        buildList {
            for ((qname, obj) in model.objectByQname()) {
                val ref = qname.dotted()
                when (obj) {
                    is Entity -> {
                        val file = repoRelative(obj.sourceFile, repoRoot)
                        addAll(localized(obj.displayLabel, ref, file))
                        if (obj.labelPlural.isNotBlank()) add(row(obj.labelPlural, LANG_DEFAULT, ref, file))
                        for (alias in obj.aliases) add(row(alias, LANG_DEFAULT, ref, file))
                    }

                    is Attribute -> {
                        val file = repoRelative(obj.sourceFile, repoRoot)
                        addAll(localized(obj.displayLabel, ref, file))
                        for ((code, label) in obj.valueLabels) {
                            // Attribute-depth ref + the member code: `er.entity.customer.status.1`.
                            addAll(localized(label, "$ref.$code", file))
                        }
                    }

                    else -> Unit
                }
            }
        }

    /** Metadata carries no per-label lang when it is a bare string — the authoring default applies. */
    private val LANG_DEFAULT = LexiconValidator.DEFAULT_LANG

    private fun localized(
        text: LocalizedText,
        ref: String,
        file: String,
    ): List<SourceRow> =
        text.byLanguage.mapNotNull { (code, value) ->
            val lang = Lang.ofWire(code) ?: return@mapNotNull null
            if (value.isBlank()) null else row(value, lang, ref, file)
        }

    internal fun row(
        text: String,
        lang: Lang,
        ref: String,
        file: String,
        line: Int = 0,
    ) = SourceRow(
        text = text,
        lang = lang,
        method = LexiconValidator.DEFAULT_METHOD,
        targetRef = ref,
        sourceTag = SourceTag.METADATA,
        // The model object's file. `ttr-metadata`'s tier carries no def spans, so its rows pass
        // line 0 — an honest 0 beats a wrong line. The md tier below reads the parsed defs
        // directly and DOES have the span, so it passes one.
        provenance = EntryProvenance(file, line),
        // ⚑M-2 — NO profile. A display label is not an authoring decision about matching, and
        // giving it one would rescore rows nobody wrote a rule for, which is precisely what the
        // P1.4 T4 "not a rescorer" ruling refused.
        profile = null,
    )
}

/**
 * METADATA layer, md half (RV-P3.4 T3) — the labels on md **dimension attributes**.
 *
 * A separate extractor from [MetadataExtractor] because md is a separate model: `ttr-metadata`'s
 * `Model` covers db/er/cnc and its `SchemaCode` has no `MD`, so no md object ever reaches that
 * walk. Without this, an estate whose nouns are md-owned — hartland's are, nearly all of them —
 * gets a METADATA layer that silently stops at its er tables.
 *
 * **Reads the parsed defs, not `MdModel`, and that is deliberate.** `MdModel` is a symbol graph
 * with no source spans; `DimensionDef`/`AttributeDef` carry `source`, so rows harvested here get a
 * real file AND line, which is what an author needs when a label collides. The ref spellings come
 * from [MdRefs], shared with the index, so the harvest and the resolution cannot drift apart.
 *
 * ## What is NOT harvested, and why it is not a deferral
 *
 * **Measures, dimensions and cubelets have no `displayLabel` in the grammar** — `MeasureDef`,
 * `DimensionDef` and `CubeletDef` carry `description` only, and a description is a sentence for a
 * human reading the model, which [MetadataExtractor] already refuses to admit as a term. So there
 * is nothing to harvest for them: closing that gap is a GRAMMAR change (a `displayLabel` on those
 * defs), not a compiler one. Estates name their measures through the DECLARED layer meanwhile —
 * `def term { for: md.measure.revenue, forms: [...] }` — which is the surface hartland already uses.
 *
 * `valueLabelAliases` (the A4-β per-value `aliases`) is also skipped, to stay at parity with the er
 * half, which does not harvest them either. Widening that is one change across both tiers, not a
 * quiet asymmetry introduced here.
 */
object MdMetadataExtractor {
    fun rows(units: List<TtrmLexiconUnit>): List<SourceRow> =
        buildList {
            for (unit in units) {
                if (unit.parsed.modelDirective?.modelCode != MdRefs.MD_MODEL_CODE) continue
                for (dim in unit.parsed.definitions.filterIsInstance<DimensionDef>()) {
                    for (attr in dim.attributes) {
                        val attrRef = MdRefs.attribute(dim.name, attr.name)
                        attr.displayLabel?.let { addAll(localized(it, attrRef, unit.file, attr.source.line)) }
                        for ((code, label) in attr.valueLabels) {
                            val memberRef = MdRefs.member(dim.name, attr.name, code)
                            addAll(localized(label, memberRef, unit.file, attr.source.line))
                        }
                    }
                }
            }
        }

    private fun localized(
        text: LocalizedStringValue,
        ref: String,
        file: String,
        line: Int,
    ): List<SourceRow> =
        text.byLanguage.mapNotNull { (code, value) ->
            val lang = Lang.ofWire(code) ?: return@mapNotNull null
            if (value.isBlank()) null else MetadataExtractor.row(value, lang, ref, file, line)
        }
}
