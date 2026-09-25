// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import org.tatrman.ttr.lexicon.CompiledEntry
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.CompiledLexiconHeader
import org.tatrman.ttr.lexicon.EntryProvenance
import org.tatrman.ttr.lexicon.MatchMethod
import org.tatrman.ttr.lexicon.MatchProfile
import org.tatrman.ttr.lexicon.OperatorEntry
import org.tatrman.ttr.lexicon.OperatorLibrary
import org.tatrman.ttr.lexicon.SourceHashes
import org.tatrman.ttr.lexicon.Reach
import org.tatrman.ttr.lexicon.SourceTag
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.lexicon.TermNormalizer
import org.tatrman.ttr.lexicon.sha256
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.memberVocabularyCarriers
import org.tatrman.ttr.semantics.semanticsblock.MentionKinds
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics

/** What one compile produced: the two artifacts plus everything the build should say out loud. */
data class CompileResult(
    val lexicon: CompiledLexicon,
    val operators: OperatorLibrary,
    val warnings: List<CompileWarning>,
)

/**
 * RV-P1.2 — declared (lexicon area + TTR-M sugar) + metadata → the compiled lexicon and the
 * operator library (contracts §2).
 *
 * Determinism is by construction, not by convention: nothing here reads a clock, a locale, a
 * file system or a hash-map iteration order. `builtAt` is a parameter for exactly that reason —
 * see [CompiledLexiconHeader.builtAt].
 */
object LexiconCompiler {
    /** `op:` / `ground:` / `pred:` refs are classified by their prefix; nothing else is. */
    private const val OP_PREFIX = "op:"
    private const val GROUND_PREFIX = "ground:"
    private const val PRED_PREFIX = "pred:"

    fun compile(
        sources: LexiconSources,
        refs: ModelRefIndex,
        modelSnapshotHash: String,
        builtAt: String,
    ): CompileResult {
        val declared = AreaExtractor.rows(sources.area) + TtrmSugarExtractor.rows(sources.ttrm)
        // Both metadata tiers, in one layer: `ttr-metadata`'s Model covers db/er/cnc, and md
        // objects are reachable only from the parsed units (RV-P3.4). An estate whose nouns are
        // md-owned gets nothing from the first tier alone.
        val metadata =
            (sources.model?.let { MetadataExtractor.rows(it, sources.repoRoot) } ?: emptyList()) +
                MdMetadataExtractor.rows(sources.ttrm)

        val warnings = mutableListOf<CompileWarning>()
        val classified = (declared + metadata).mapNotNull { classify(it, refs, warnings) }

        val entries = merge(classified, warnings)
        warnings += collisionWarnings(entries)

        val lexicon =
            CompiledLexicon(
                header =
                    CompiledLexiconHeader(
                        modelSnapshotHash = modelSnapshotHash,
                        sourceHashes =
                            SourceHashes(
                                declared = layerHash(declared),
                                metadata = layerHash(metadata),
                            ),
                        builtAt = builtAt,
                    ),
                entries = entries,
                targets = targetFacts(entries, sources.model),
            )

        return CompileResult(lexicon, operatorLibrary(sources, warnings), warnings.sortedWith(WARNING_ORDER))
    }

    /**
     * MH T1 (contracts §3) — `RG-LEXC-004`, one word claimed by two refs.
     *
     * Runs over the MERGED table, because that is where a row's target class is settled and where
     * a term repeating its own target's label has already collapsed into one row (which is
     * redundant authoring, not a collision). The grouping key is [TermNormalizer.fold], **not**
     * the merge key: two refs meet at runtime iff their FOLDED forms are equal, since the
     * resolver's anchor index strips diacritics. `vyroba` and `výroba` therefore stay two rows in
     * the archive and are one collision here, which is the whole point of having two keys.
     *
     * Only `MODEL_OBJECT` rows take part. A `MEMBER` row is an `M:` identity at runtime — a
     * different species from a `V:` ref, which the Binder already keeps apart — so a value label
     * colliding with a term is not this warning (contracts §2.2/§3, the same boundary the lint
     * twin draws by skipping `valueLabels`).
     *
     * One warning **per DECLARED row per other ref**, carrying the declared row's provenance: the
     * author of the alias is the one who can act on it. Two METADATA labels that collide raise
     * nothing here — a model-level duplicate label is real, but no term author can fix it.
     */
    private fun collisionWarnings(entries: List<CompiledEntry>): List<CompileWarning> {
        val byFold =
            entries
                .filter { it.targetClass == TargetClass.MODEL_OBJECT }
                .groupBy { TermNormalizer.fold(it.termNormalized) }

        val out = mutableListOf<CompileWarning>()
        for ((folded, group) in byFold) {
            if (group.map { it.targetRef }.distinct().size < 2) continue
            for (row in group.filter { it.sourceTag == SourceTag.DECLARED }) {
                // One warning per OTHER ref, naming that ref's first row in table order: an author
                // needs to know which object took the word, not every row that object owns.
                val others = group.filter { it.targetRef != row.targetRef }.groupBy { it.targetRef }
                for (otherRef in others.keys) {
                    val other = others.getValue(otherRef).first()
                    out +=
                        CompileWarning(
                            code = CompileWarning.FORM_COLLISION,
                            message =
                                "term \"${row.termNormalized}\" (for: ${row.targetRef}) collides with the " +
                                    "${other.sourceTag} anchor \"${other.termNormalized}\" of $otherRef — " +
                                    "both refs claim this word at runtime (fold: \"$folded\")",
                            provenance = row.provenance,
                        )
                }
            }
        }
        return out
    }

    /**
     * MS (contracts §5/§6) — the per-ref facts map.
     *
     * Keyed by the entry's OWN `targetRef` string, deliberately: the map is only useful if a
     * consumer can look a row's ref up in it directly, and re-rendering the qname a second time
     * here is exactly where the two spellings could drift apart. MODEL_OBJECT refs only — an
     * `op:`/`ground:` ref is not a model node, and a facts entry for one would be a claim about
     * something that does not exist.
     *
     * ⛔ Which kind a ref gets is decided by WHICH MODEL NODE it resolved to, never by the shape
     * of the ref string. That rule is the whole reason `MentionKinds` exists as one table
     * (architecture §4.1); this is its only producer.
     *
     * A ref the model does not describe — or describes as something that is neither an
     * entity/table nor an attribute/column, such as a cnc role or a relation — gets NO entry.
     * Saying `entity` about a role would be a wrong claim, and the map's contract is that a
     * missing key means "nothing declared", which every consumer already has to handle.
     *
     * MV (member-vocabulary contracts §5.1, archive v5) — the map ALSO carries every carrier with a
     * member vocabulary ([memberVocabularyCarriers]), terms or not, flagged `memberVocabulary`. Before
     * MV an indexed attribute nobody had written a term for was absent here, so the resolver's
     * registry never learned its vocabulary existed and a governed value could not reach it.
     * Members carry no reach of their own: they are reached through their owner (`ownerRef`), the
     * same rule the terms-derived attribute entries have always followed.
     *
     * Sorted by key: iteration order is byte order in the archive, and two builds over the same
     * inputs must produce the same bytes (contracts §2 determinism).
     */
    private fun targetFacts(
        entries: List<CompiledEntry>,
        model: Model?,
    ): Map<String, TargetFacts> {
        if (model == null) return emptyMap()
        val objects = model.objectByQname().entries.associate { (qname, obj) -> qname.dotted() to obj }
        val reach = reachedFrom(model, objects.keys)
        val members = model.memberVocabularyCarriers().map { it.qname.dotted() }.toSet()
        val termRefs = entries.filter { it.targetClass == TargetClass.MODEL_OBJECT }.map { it.targetRef }
        val out = sortedMapOf<String, TargetFacts>()
        for (ref in (termRefs + members).distinct()) {
            val facts =
                when (val obj = objects[ref]) {
                    is Attribute -> memberFacts(obj.qname.name, objects[obj.entity.dotted()], obj.entity.dotted())
                    is DbColumn -> memberFacts(obj.qname.name, objects[obj.table.dotted()], obj.table.dotted())
                    is Entity -> ownerFacts(obj.mentionSemantics)
                    is DbTable -> ownerFacts(obj.mentionSemantics)
                    else -> null
                } ?: continue
            // Reach is a fact about whole OBJECTS: an attribute is reached through its owner, and
            // saying otherwise would let the resolver join to a column.
            val reachOf = if (facts.isAttribute) emptyList() else reach[ref].orEmpty()
            val mention = if (facts.isAttribute) Mention.NONE else mentionFacet(objects.getValue(ref))
            out[ref] =
                TargetFacts(
                    objectKind = MentionKinds.of(facts),
                    ownerRef = facts.ownerRef,
                    reachedFrom = reachOf,
                    nameRef = mention.nameRef,
                    codeRef = mention.codeRef,
                    codeFormat = mention.codeFormat,
                    memberVocabulary = ref in members,
                )
        }
        return out
    }

    /**
     * MH (contracts §4.2) — `reachedFrom(ref)`: the facts with a `def relation` **to** `ref`, each
     * with the relation's `to`-side lower bound.
     *
     * The direction is the whole point. `store_sales → store` means a store_sales row carries a
     * store, so restricting the fact by the dimension is a join the model already declares; the
     * resolver's T3 rule reads exactly that to decide whether "the Stores channel" and "the store
     * dimension" select the same rows. `mandatory` is `cardinality.toMin >= 1` — every fact row
     * has one — which is what turns "usually the same data" into a checkable claim.
     *
     * A relation whose `to` is not in the model is skipped **silently**: dangling relations belong
     * to the model validator, and one authoring mistake should not produce a diagnostic from two
     * tools. Sorted by `factRef`, and de-duplicated, because iteration order is byte order.
     */
    private fun reachedFrom(
        model: Model,
        known: Set<String>,
    ): Map<String, List<Reach>> =
        model.schemas.values
            .filterIsInstance<ErSchema>()
            .flatMap { it.relations.values }
            .filter { it.toEntity.dotted() in known && it.fromEntity.dotted() in known }
            .groupBy { it.toEntity.dotted() }
            .mapValues { (_, rels) ->
                rels
                    .map { Reach(factRef = it.fromEntity.dotted(), mandatory = it.cardinality.toMin >= 1) }
                    .distinctBy { it.factRef }
                    .sortedBy { it.factRef }
            }

    /** An attribute/column: a measure iff its OWNER lists it. The local name is the last segment. */
    private fun memberFacts(
        qnameName: String,
        owner: ModelObject?,
        ownerRef: String,
    ): MentionKinds.ObjectFacts {
        val local = qnameName.substringAfterLast('.')
        val measures = mentionOf(owner)?.measures.orEmpty()
        return MentionKinds.ObjectFacts(
            isAttribute = true,
            ownerRef = ownerRef,
            listedAsMeasure = measures.any { it.attribute.path == local },
            ownerHasMeasures = measures.isNotEmpty(),
        )
    }

    /**
     * LP (contracts §2.1) — `semantics { name: · code: }` as FULL attribute refs, plus the regex a
     * code matches (review-103 D4, see [codeFormatOf]).
     *
     * Members carry none of this: a member has no name column, it IS one ([Mention.NONE]).
     *
     * ⛔ The local name the block declares is RESOLVED against the owner's own member list, never
     * concatenated onto the owner's ref. The two differ exactly where it matters: a model that
     * names an attribute it does not have would otherwise ship a ref pointing at nothing, and the
     * resolver would attribute a literal to a column the plan cannot select. Absent here means
     * "the model does not say", which LP-P1's `Verbatim` already handles by leaving the literal
     * headless — the one degradation that cannot produce a wrong answer.
     */
    private data class Mention(
        val nameRef: String? = null,
        val codeRef: String? = null,
        val codeFormat: String? = null,
    ) {
        companion object {
            val NONE = Mention()
        }
    }

    private fun mentionFacet(obj: ModelObject): Mention {
        val (semantics, members) =
            when (obj) {
                is Entity -> obj.mentionSemantics to obj.attributes
                is DbTable -> obj.mentionSemantics to obj.columns
                else -> return Mention.NONE
            }
        val sem = semantics ?: return Mention.NONE
        val byLocal = members.associateBy { it.qname.name.substringAfterLast('.') }
        val name = sem.name?.path?.let { byLocal[it] }
        val code = sem.code?.path?.let { byLocal[it] }
        return Mention(
            nameRef = name?.qname?.dotted(),
            codeRef = code?.qname?.dotted(),
            codeFormat = code?.let { codeFormatOf(sem.codePattern, it) },
        )
    }

    /**
     * Review-103 D4 — the REGEX a quoted code matches, for `TargetFacts.codeFormat`. Always a regex
     * or null, never anything else: the resolver compiles this field with `Regex(...)`, and before
     * this fix the compiler copied a period `code_format:` MASK into it (`yyyyMM`), which as a
     * regex matches the literal letters `yyyyMM` and nothing a user would ever quote (F6).
     *
     * In order: the owner's declared `code_pattern:` (already compiled by the analyzer, which
     * refuses the block otherwise); else the code member's period `code_format:` mask, translated
     * ([maskToRegex]); else null — never a shape this compiler invents.
     */
    private fun codeFormatOf(
        declaredPattern: String?,
        member: ModelObject,
    ): String? {
        if (!declaredPattern.isNullOrBlank()) return declaredPattern
        val mask =
            when (member) {
                is Attribute -> member.semantics?.codeFormat
                is DbColumn -> member.semantics?.codeFormat
                else -> null
            }
        return mask?.takeIf { it.isNotBlank() }?.let(::maskToRegex)
    }

    /** The date-mask letters [maskToRegex] reads as digits: year, month, day, hour, minute, second, quarter, week. */
    private const val MASK_DIGIT_LETTERS = "yMdHmsQW"

    /** Mask letters that stop being digits at three in a row: `MMM` is a month name, `QQQ` a quarter label. */
    private const val MASK_TEXT_WHEN_3 = "MQ"

    /**
     * Review-103 D4 — a period `code_format:` mask as an anchored regex: `yyyyMM` → `^\d{6}$`,
     * `yyyy-MM` → `^\d{4}\-\d{2}$`.
     *
     * Each RUN of mask letters (`y M d H m s Q W`, in any mix) becomes `\d{n}`, n being the run's
     * length — every one of those fields is written as digits, so `yyyyMM` is six digits. Any other
     * letter means the mask says something this translation does not understand (an era, a day
     * name), and the answer is null rather than a regex that is confidently wrong. So does three or
     * more of `M` or `Q` in a row: in the date-pattern convention the masks follow, `MMM`/`MMMM` is
     * a month NAME and `QQQ` a quarter label (`Q1`), not digits. Every other
     * character is literal: a non-alphanumeric one is backslash-escaped (always legal in a Java
     * regex, and required for `.` `+` `(`…), and a digit is appended as it is (`\2` would be a
     * back-reference).
     */
    internal fun maskToRegex(mask: String): String? {
        if (mask.isEmpty()) return null
        val out = StringBuilder("^")
        var i = 0
        while (i < mask.length) {
            val c = mask[i]
            when {
                c in MASK_DIGIT_LETTERS -> {
                    var j = i
                    while (j < mask.length && mask[j] in MASK_DIGIT_LETTERS) {
                        // A textual field (`MMM`, `QQQ`) inside the run makes the whole mask unreadable.
                        var k = j
                        while (k < mask.length && mask[k] == mask[j]) k++
                        if (mask[j] in MASK_TEXT_WHEN_3 && k - j >= 3) return null
                        j = k
                    }
                    out.append("\\d{").append(j - i).append('}')
                    i = j
                }

                c.isLetter() -> return null

                c.isDigit() -> {
                    out.append(c)
                    i++
                }

                else -> {
                    out.append('\\').append(c)
                    i++
                }
            }
        }
        return out.append('$').toString()
    }

    private fun ownerFacts(mention: ResolvedEntitySemantics?): MentionKinds.ObjectFacts =
        MentionKinds.ObjectFacts(
            isAttribute = false,
            ownerHasMeasures = mention?.measures?.isNotEmpty() == true,
        )

    private fun mentionOf(obj: ModelObject?): ResolvedEntitySemantics? =
        when (obj) {
            is Entity -> obj.mentionSemantics
            is DbTable -> obj.mentionSemantics
            else -> null
        }

    /**
     * RV-38/RV-42, LP §3.2 — the target's class. `op:`/`ground:`/`pred:` refs resolve by prefix and
     * never touch the index: they are not model objects, so consulting a model snapshot for them
     * would make every operator, grounding trigger and string predicate dangle against a snapshot
     * that will never contain it.
     *
     * Anything else must be in the snapshot. Absent ⇒ RV-20: drop the row, warn with the line.
     */
    private fun classify(
        row: SourceRow,
        refs: ModelRefIndex,
        warnings: MutableList<CompileWarning>,
    ): Pair<SourceRow, TargetClass>? {
        val cls =
            when {
                row.targetRef.startsWith(OP_PREFIX) -> TargetClass.OPERATOR
                row.targetRef.startsWith(GROUND_PREFIX) -> TargetClass.GROUNDING_TRIGGER
                row.targetRef.startsWith(PRED_PREFIX) -> TargetClass.STRING_PREDICATE
                else -> refs.classify(row.targetRef)
            }
        if (cls == null) {
            warnings +=
                CompileWarning(
                    code = CompileWarning.DANGLING_REF,
                    message =
                        "term \"${row.text}\" targets `${row.targetRef}`, which is not in the model " +
                            "snapshot — entry dropped",
                    provenance = row.provenance,
                )
            return null
        }
        return row to cls
    }

    /**
     * Collapse to one row per (normalized term, lang, target) and order the table.
     *
     * The identity deliberately excludes `method`: one term pointing at one target with two
     * different match methods is contradictory authoring, not two entries. Precedence is
     * **DECLARED over METADATA** (an author's file states an intent; a model label is a byproduct),
     * then the **widest method** — dropping the wider one would lose matches somebody asked for.
     */
    private fun merge(
        rows: List<Pair<SourceRow, TargetClass>>,
        warnings: MutableList<CompileWarning>,
    ): List<CompiledEntry> {
        val byIdentity = LinkedHashMap<Triple<String, String, String>, MutableList<Pair<SourceRow, TargetClass>>>()
        for ((row, cls) in rows) {
            val key = Triple(TermNormalizer.normalize(row.text), row.lang.wire, row.targetRef)
            byIdentity.getOrPut(key) { mutableListOf() } += row to cls
        }

        val out = mutableListOf<CompiledEntry>()
        for ((key, group) in byIdentity) {
            val winner = group.sortedWith(PRECEDENCE).first()
            // RV-44 widens this from "two methods" to "two matching statements": with profiles, two
            // rows can agree on `method` and still disagree about which norms count. Reporting only
            // the method disagreement would have let the richer half of the conflict pass silently.
            val loser =
                group.firstOrNull {
                    it.first.method != winner.first.method || it.first.profile != winner.first.profile
                }
            if (loser != null) {
                warnings +=
                    CompileWarning(
                        code = CompileWarning.METHOD_CONFLICT,
                        message =
                            "term \"${key.first}\" → `${key.third}` is declared with both " +
                                "${describe(winner.first)} and ${describe(loser.first)}; " +
                                "kept ${describe(winner.first)}",
                        provenance = loser.first.provenance,
                    )
            }
            out +=
                CompiledEntry(
                    termNormalized = key.first,
                    lemma = null,
                    lang = key.second,
                    targetRef = key.third,
                    targetClass = winner.second,
                    method = winner.first.method.wire,
                    sourceTag = winner.first.sourceTag,
                    provenance = winner.first.provenance,
                    matchProfile = winner.first.profile,
                )
        }
        return out.sortedWith(ENTRY_ORDER)
    }

    /**
     * Skill bodies, keyed by `op:` id — a separate artifact from the lexicon (RV-35).
     *
     * **Estate overrides stdlib** (T5): when two files declare the same op id, the later one in
     * the caller's skill list wins and the build says so. Ordering the stdlib first and the estate
     * second is the loader's job, not a rule this can infer from a file path.
     */
    private fun operatorLibrary(
        sources: LexiconSources,
        warnings: MutableList<CompileWarning>,
    ): OperatorLibrary {
        val byId = LinkedHashMap<String, OperatorEntry>()
        for (skill in sources.area.skills) {
            val existing = byId[skill.opId]
            if (existing != null) {
                warnings +=
                    CompileWarning(
                        code = CompileWarning.OPERATOR_OVERRIDE,
                        message =
                            "`${skill.opId}` is defined in ${existing.source.file} and overridden by " +
                                "${skill.provenance.file}",
                        provenance = EntryProvenance(skill.provenance.file, skill.provenance.line),
                    )
            }
            byId[skill.opId] =
                OperatorEntry(
                    body = skill.body,
                    version = skill.version,
                    checksum = sha256(skill.body.toByteArray(Charsets.UTF_8)),
                    source = EntryProvenance(skill.provenance.file, skill.provenance.line),
                )
        }
        // Sorted keys: the JSON object's key order is part of the artifact's bytes.
        return OperatorLibrary(operators = byId.toSortedMap())
    }

    /**
     * How a row states its matching, for a diagnostic — the method alone where the profile is just
     * that method's expansion, and the profile spelled out where it says more.
     */
    private fun describe(row: SourceRow): String {
        val profile = row.profile
        if (profile == null || profile == MatchProfile.ofSugar(row.method)) return row.method.wire
        return profile.rules.joinToString(", ", prefix = "match[", postfix = "]") { rule ->
            buildString {
                append(rule.norm.wire)
                rule.exact?.let { append("/exact ").append(it) }
                rule.typos?.let { append("/typos ").append(it.distance).append("@").append(it.penalty) }
                if (rule.tokens) append("/tokens")
            }
        }
    }

    /**
     * A profile, rendered for hashing: stable, total, and independent of the JSON codec (which is
     * internal to `ttr-lexicon` and, being an artifact format, free to gain pretty-printing).
     */
    private fun fingerprint(profile: MatchProfile): String =
        profile.rules.joinToString(";") { rule ->
            val typos = rule.typos?.let { "${it.distance},${it.penalty}" }.orEmpty()
            "${rule.norm.wire}|${rule.exact ?: ""}|$typos|${rule.tokens}"
        }

    /**
     * Per-layer input fingerprint, over the layer's rows in a stable order.
     *
     * The profile is part of it (RV-44): an edit that changes only *how* a term matches is still an
     * edit to the declared layer, and a fingerprint that missed it would report "nothing changed"
     * for a rebuild that changes what the estate binds.
     */
    private fun layerHash(rows: List<SourceRow>): String =
        sha256(
            rows
                .map {
                    listOf(
                        TermNormalizer.normalize(it.text),
                        it.lang.wire,
                        it.method.wire,
                        it.targetRef,
                        it.provenance.file,
                        it.provenance.line.toString(),
                        it.profile?.let { p -> fingerprint(p) } ?: "-",
                    ).joinToString(" ")
                }.sorted()
                .joinToString("\n")
                .toByteArray(Charsets.UTF_8),
        )

    /** DECLARED first, then widest method, then the earliest provenance — total and stable. */
    private val PRECEDENCE =
        compareBy<Pair<SourceRow, TargetClass>>(
            { if (it.first.sourceTag == SourceTag.DECLARED) 0 else 1 },
            { -breadth(it.first.method) },
            { it.first.provenance.file },
            { it.first.provenance.line },
        )

    /** How much a method admits. Wider wins a conflict; the ranking is the artifact's, not the matcher's. */
    private fun breadth(method: MatchMethod): Int =
        when (method) {
            is MatchMethod.Typos -> 10 + method.maxDistance
            MatchMethod.Tokens -> 5
            MatchMethod.Exact -> 0
        }

    private val ENTRY_ORDER =
        compareBy<CompiledEntry>({ it.termNormalized }, { it.lang }, { it.targetRef }, { it.method })

    private val WARNING_ORDER =
        compareBy<CompileWarning>({ it.provenance.file }, { it.provenance.line }, { it.code }, { it.message })
}
