// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode

/** A rejected file, with the stable `RG-LEX-*` code the error catalogue documents. */
data class LexiconViolation(
    val code: String,
    val message: String,
    val provenance: Provenance,
)

/** Loaded, or rejected with at least one violation. Never both, never neither. */
sealed interface LexiconLoad<out T> {
    data class Ok<T>(
        val value: T,
        /**
         * RV-44 — things worth saying about a file that nonetheless loaded ([LexiconWarnings]).
         *
         * On the success side rather than beside it, because that is the only place a caller
         * cannot forget to look: a warning attached to a `Rejected` would be noise (the file is
         * being fixed anyway), and one returned out of band is one nobody prints.
         */
        val warnings: List<LexiconViolation> = emptyList(),
    ) : LexiconLoad<T>

    data class Rejected(
        val violations: List<LexiconViolation>,
    ) : LexiconLoad<Nothing> {
        init {
            require(violations.isNotEmpty()) { "a rejection must carry at least one violation" }
        }

        val codes: List<String> get() = violations.map { it.code }
    }
}

/**
 * Validates authored lexicon files against `ttr-lexicon/v1` / `ttr-skill/v1` and maps them to the
 * typed model (RV-P1.1; RV contracts §2).
 *
 * **Where the rules live.** The two packaged `.schema.json` resources are the published contract —
 * what an editor, a non-JVM consumer or a human reads. This object is the JVM enforcement of the
 * same rules, and `SchemaEquivalenceSpec` runs every fixture through both and fails if they ever
 * disagree. Enforcing via the schema at runtime would mean shipping a JSON stack inside a
 * toolchain artifact that every TTR-P project resolves, which the repo deliberately does not do
 * (networknt is test-scope in all four packages that use it).
 *
 * **Composed, not loaded.** snakeyaml's node tree keeps a start mark per node, so every term
 * carries the line it was authored on. That is what makes a diagnostic actionable, and it is why
 * this does not simply `Yaml().load()` into maps.
 *
 * **Every violation in a file is reported**, not the first — a lexicon is authored in bulk.
 */
object LexiconValidator {
    const val LEXICON_SCHEMA_ID = "ttr-lexicon/v1"
    const val SKILL_SCHEMA_ID = "ttr-skill/v1"

    /**
     * The fallback when neither the term nor the file `defaults` names a method.
     *
     * ⚠ **ASSUMED, not ruled (RV-P1.1).** design.md §RV-32's "(default TYPOS(1))" reads as the
     * default *distance* for TYPOS — bare `TYPOS` is invalid here, so it cannot also be the
     * default *method* — and no document states the global fallback. EXACT is the safe direction:
     * too-narrow matching is visible and reported, whereas a silent TYPOS(1) produces fuzzy
     * bindings nobody asked for, which is the failure mode RV's precision-first discipline exists
     * to prevent.
     */
    val DEFAULT_METHOD: MatchMethod = MatchMethod.Exact

    /**
     * The fallback when no language is stated. Both-languages rather than `cs`: an undeclared term
     * should not silently become Czech-only in an English question.
     */
    val DEFAULT_LANG: Lang = Lang.CS_EN

    private val TOP_LEVEL = setOf("schema", "defaults", "entries")
    private val ENTRY_KEYS = setOf("terms", "target")

    // RV-44: `match` joins `method` at both levels. The schema id is UNCHANGED (`ttr-lexicon/v1`) —
    // the keys are purely additive, so every v1 file written before profiles existed still parses.
    private val TERM_KEYS = setOf("text", "lang", "method", "match")
    private val DEFAULTS_KEYS = setOf("lang", "method", "match")
    private val MATCH_RULE_KEYS = setOf("norm", "exact", "typos", "tokens")
    private val TYPOS_KEYS = setOf("distance", "penalty")
    private val SKILL_KEYS = setOf("schema", "op", "triggers", "requires", "version")
    private val OP_REF = Regex("""^op:[a-z0-9]+(-[a-z0-9]+)*$""")

    /** RV-42 — the `ground:` ref prefix and its CLOSED kind vocabulary. */
    const val GROUND_PREFIX: String = "ground:"

    /**
     * The three grounding kernels (RV-42). Closed on purpose: a `ground:` ref names a *service*
     * that must load the slice, so an unknown kind is not an extension point — it is an entry no
     * kernel will ever read. Adding a fourth kernel means adding it here, deliberately.
     */
    val GROUNDING_KINDS: Set<String> = setOf("chrono", "money", "geo")

    /** LP contracts §3.1 — the `pred:` ref prefix and its CLOSED kind vocabulary. */
    const val PRED_PREFIX: String = "pred:"

    /**
     * The five string predicates (LP contracts §3.1). Closed for the same reason [GROUNDING_KINDS]
     * is: the ref names a behaviour a *consumer* lowers — `starts_with` becomes a parameterised
     * `LIKE 'x%'` at the translator — so an unknown kind is not an extension point but an entry no
     * stage would ever act on.
     */
    val PREDICATE_KINDS: Set<String> =
        setOf("starts_with", "ends_with", "contains", "equals", "not_contains")

    /** Parses a `.lex.yaml` data file. [file] is used for provenance only. */
    fun loadDataFile(
        yaml: String,
        file: String,
    ): LexiconLoad<LexiconDataFile> {
        val ctx = Ctx(file, lineOffset = 0)
        val root = ctx.compose(yaml) ?: return ctx.rejected()

        ctx.strictKeys(root, TOP_LEVEL, "(root)")
        ctx.schemaId(root, LEXICON_SCHEMA_ID)

        val defaults = ctx.defaults(root.field("defaults"))
        val entriesNode = root.field("entries")
        if (entriesNode == null) {
            ctx += LexiconErrors.missingRequired("entries", "(root)", ctx.at(root))
            return ctx.rejected()
        }

        val entries =
            ctx.sequence(entriesNode, "entries").mapNotNull { node ->
                val entry = node as? MappingNode ?: return@mapNotNull null.also { ctx.notAMapping(node, "entries[]") }
                ctx.strictKeys(entry, ENTRY_KEYS, "entries[]")

                val target = entry.field("target")
                if (target == null) {
                    ctx += LexiconErrors.missingRequired("target", "entries[]", ctx.at(entry))
                    return@mapNotNull null
                }
                val terms = ctx.terms(entry.field("terms"), defaults, "entries[].terms")
                if (terms.isEmpty()) return@mapNotNull null

                // A non-scalar `target` (someone wrote a list or a block) is a missing target as
                // far as an author is concerned: there is no ref to bind to.
                val targetRef = ctx.scalar(target)
                if (targetRef.isNullOrBlank()) {
                    ctx += LexiconErrors.missingRequired("target", "entries[]", ctx.at(target))
                    return@mapNotNull null
                }

                // RV-42: a `ground:` target names one of the three kernels. Everything else
                // (model refs, member refs, `op:`) is the compiler's to classify, not ours.
                if (targetRef.startsWith(GROUND_PREFIX) &&
                    targetRef.removePrefix(GROUND_PREFIX) !in GROUNDING_KINDS
                ) {
                    ctx += LexiconErrors.unknownGroundingKind(targetRef, GROUNDING_KINDS, ctx.at(target))
                    return@mapNotNull null
                }

                // LP §3.1: a `pred:` target names one of the five string predicates, and its forms
                // must be able to carry a trigger. Both checks live here rather than in the
                // compiler because both reject the FILE: an author can fix either by editing a
                // line, which is the boundary between this object and RV-20's dropped rows.
                if (targetRef.startsWith(PRED_PREFIX)) {
                    if (targetRef.removePrefix(PRED_PREFIX) !in PREDICATE_KINDS) {
                        ctx += LexiconErrors.unknownPredicateKind(targetRef, PREDICATE_KINDS, ctx.at(target))
                        return@mapNotNull null
                    }
                    // Every weak form is reported, not the first — a slice is authored in bulk.
                    var weak = false
                    for (term in terms) {
                        val why = weakPredicateForm(term)
                        if (why != null) {
                            ctx += LexiconErrors.weakPredicateForm(term.text, targetRef, why, term.provenance)
                            weak = true
                        }
                    }
                    if (weak) return@mapNotNull null
                }

                LexiconEntryDef(terms, targetRef, ctx.at(target))
            }

        ctx.duplicates(entries.flatMap { it.terms })
        return if (ctx.clean()) {
            LexiconLoad.Ok(LexiconDataFile(entries, Provenance(file, 1)), ctx.warnings())
        } else {
            ctx.rejected()
        }
    }

    /**
     * LP contracts §3.1 — why [term] cannot be a `pred:` trigger, or null when it can.
     *
     * **Single-token forms only.** A multi-word form matches as a phrase, so `s textem` is safe
     * even though `s` alone is not: the two words must both be there, in one span, which is
     * already the evidence a predicate needs. Refusing it would leave the cs slice with no natural
     * way to say *contains* at all.
     *
     * The token test is [TermNormalizer.normalize]'s whitespace, not a tokenizer: the authored
     * form is the unit here, and the only question is whether an author wrote one word or several.
     */
    private fun weakPredicateForm(term: TermDef): String? {
        val normalized = TermNormalizer.normalize(term.text)
        if (normalized.contains(' ')) return null
        val folded = TermNormalizer.fold(normalized)
        return when {
            folded.length <= 1 -> "a single character matches inside half the words in a question"
            LexiconStopWords.isStop(folded, term.lang) ->
                "it is a function word in ${term.lang.wire}"

            else -> null
        }
    }

    /** Parses a skill file (`skills` dir, `.md`): frontmatter → [SkillDef], body kept verbatim. */
    fun loadSkillFile(
        markdown: String,
        file: String,
    ): LexiconLoad<SkillDef> {
        val split =
            when (val s = FrontmatterSplitter.split(markdown)) {
                // The splitter has no file name; stamp it on the way out.
                is LexiconLoad.Rejected ->
                    return LexiconLoad.Rejected(
                        s.violations.map { it.copy(provenance = it.provenance.copy(file = file)) },
                    )

                is LexiconLoad.Ok -> s.value
            }

        val ctx = Ctx(file, lineOffset = split.firstKeyLine - 1)
        val root = ctx.compose(split.yaml) ?: return ctx.rejected()

        ctx.strictKeys(root, SKILL_KEYS, "(frontmatter)")
        ctx.schemaId(root, SKILL_SCHEMA_ID)

        val opNode = root.field("op")
        val opId = opNode?.let { ctx.scalar(it) }
        when {
            opNode == null -> ctx += LexiconErrors.missingRequired("op", "(frontmatter)", ctx.at(root))
            opId == null || !OP_REF.matches(opId) ->
                ctx += LexiconErrors.opNotPrefixed(opId.orEmpty(), ctx.at(opNode))
        }

        val triggersNode = root.field("triggers")
        val triggers =
            if (triggersNode == null) {
                ctx += LexiconErrors.noTriggers(opId.orEmpty(), ctx.at(root))
                emptyList()
            } else {
                ctx.terms(triggersNode, TermDefaults.NONE, "triggers").also {
                    if (it.isEmpty() && ctx.sequence(triggersNode, "triggers").isEmpty()) {
                        ctx += LexiconErrors.noTriggers(opId.orEmpty(), ctx.at(triggersNode))
                    }
                }
            }

        val versionNode = root.field("version")
        val version = versionNode?.let { ctx.scalar(it) }?.toIntOrNull()
        if (version == null || version < 1) {
            ctx += LexiconErrors.missingRequired("version", "(frontmatter)", ctx.at(versionNode ?: root))
        }

        val requires =
            root.field("requires")?.let { ctx.sequence(it, "requires").mapNotNull(ctx::scalar) } ?: emptyList()

        ctx.duplicates(triggers)
        return if (ctx.clean()) {
            LexiconLoad.Ok(
                SkillDef(
                    opId = opId!!,
                    triggers = triggers,
                    requires = requires,
                    version = version!!,
                    body = split.body,
                    provenance = Provenance(file, ctx.lineOffset + 1),
                ),
                ctx.warnings(),
            )
        } else {
            ctx.rejected()
        }
    }

    // ---- internals ---------------------------------------------------------

    private fun Node.field(name: String): Node? =
        (this as? MappingNode)?.value?.firstOrNull { (it.keyNode as? ScalarNode)?.value == name }?.valueNode

    /**
     * Per-file `defaults`, applied to a term that does not state its own.
     *
     * [method] is already the projection of [profile] when the file defaults to a `match:` list, so
     * a term inheriting the default gets one consistent answer rather than two half-answers.
     */
    internal data class TermDefaults(
        val lang: Lang,
        val method: MatchMethod,
        val profile: MatchProfile? = null,
    ) {
        companion object {
            val NONE = TermDefaults(DEFAULT_LANG, DEFAULT_METHOD)
        }
    }

    /**
     * One file's walk: collects violations instead of throwing, so an author sees everything wrong
     * with a file in one pass.
     */
    private class Ctx(
        val file: String,
        val lineOffset: Int,
    ) {
        private val violations = mutableListOf<LexiconViolation>()
        private val warnings = mutableListOf<LexiconViolation>()

        operator fun plusAssign(v: LexiconViolation) {
            violations += v
        }

        fun warn(v: LexiconViolation) {
            warnings += v
        }

        fun warnings(): List<LexiconViolation> = warnings.toList()

        fun clean(): Boolean = violations.isEmpty()

        fun rejected(): LexiconLoad.Rejected =
            LexiconLoad.Rejected(
                violations.ifEmpty {
                    listOf(
                        LexiconErrors.malformedYaml("no content", Provenance(file, lineOffset + 1)),
                    )
                },
            )

        /** 1-based authored line, offset for frontmatter. */
        fun at(node: Node): Provenance = Provenance(file, lineOffset + node.startMark.line + 1)

        fun compose(yaml: String): MappingNode? {
            val node =
                try {
                    Yaml().compose(yaml.reader())
                } catch (e: org.yaml.snakeyaml.error.YAMLException) {
                    this += LexiconErrors.malformedYaml(e.message.orEmpty(), Provenance(file, lineOffset + 1))
                    return null
                }
            if (node == null) {
                this += LexiconErrors.malformedYaml("the document is empty", Provenance(file, lineOffset + 1))
                return null
            }
            return node as? MappingNode ?: null.also {
                this += LexiconErrors.malformedYaml("the document is not a YAML mapping", at(node))
            }
        }

        fun strictKeys(
            node: MappingNode,
            allowed: Set<String>,
            where: String,
        ) {
            node.value.forEach { tuple ->
                val key = (tuple.keyNode as? ScalarNode)?.value
                if (key == null || key !in allowed) {
                    this += LexiconErrors.unknownKey(key.orEmpty(), where, at(tuple.keyNode))
                }
            }
        }

        fun schemaId(
            root: MappingNode,
            expected: String,
        ) {
            val node = root.field("schema")
            if (node == null) {
                this += LexiconErrors.missingRequired("schema", "(root)", at(root))
                return
            }
            val declared = scalar(node)
            if (declared != expected) {
                this += LexiconErrors.schemaIdMismatch(declared.orEmpty(), expected, at(node))
            }
        }

        fun scalar(node: Node): String? = (node as? ScalarNode)?.value

        fun sequence(
            node: Node,
            where: String,
        ): List<Node> =
            (node as? SequenceNode)?.value ?: emptyList<Node>().also {
                this += LexiconErrors.missingRequired(where, where, at(node))
            }

        fun notAMapping(
            node: Node,
            where: String,
        ) {
            this += LexiconErrors.missingRequired(where, where, at(node))
        }

        fun defaults(node: Node?): TermDefaults {
            val map = node as? MappingNode ?: return TermDefaults.NONE
            strictKeys(map, DEFAULTS_KEYS, "defaults")

            val methodNode = map.field("method")
            val matchNode = map.field("match")
            if (methodNode != null && matchNode != null) {
                this += LexiconErrors.methodAndMatch("defaults", at(matchNode))
            }
            val profile = matchNode?.let { profile(it, "defaults.match") }
            return TermDefaults(
                lang = map.field("lang")?.let { lang(it) } ?: DEFAULT_LANG,
                // The profile wins where one is authored: it is the richer statement, and `method`
                // is only ever a projection of it.
                method = profile?.sugarMethod() ?: methodNode?.let { method(it) } ?: DEFAULT_METHOD,
                profile = profile,
            )
        }

        fun terms(
            node: Node?,
            defaults: TermDefaults,
            where: String,
        ): List<TermDef> {
            if (node == null) {
                return emptyList()
            }
            return sequence(node, where).mapNotNull { termNode ->
                val term = termNode as? MappingNode ?: return@mapNotNull null.also { notAMapping(termNode, where) }
                strictKeys(term, TERM_KEYS, where)

                val textNode = term.field("text")
                val text = textNode?.let { scalar(it) }
                if (text.isNullOrEmpty()) {
                    this += LexiconErrors.missingRequired("text", where, at(textNode ?: term))
                    return@mapNotNull null
                }

                val methodNode = term.field("method")
                val matchNode = term.field("match")
                if (methodNode != null && matchNode != null) {
                    this += LexiconErrors.methodAndMatch("this term", at(matchNode))
                }

                // A term states its matching ONE way. Its own `match` wins, then its own `method`
                // — and a term with its own `method` inherits no default profile, or the file's
                // default would silently outrank what the author wrote on the term itself.
                val profile =
                    when {
                        matchNode != null -> profile(matchNode, "$where.match")
                        methodNode != null -> null
                        else -> defaults.profile
                    }
                val method =
                    when {
                        matchNode != null -> profile?.sugarMethod() ?: DEFAULT_METHOD
                        methodNode != null -> method(methodNode)
                        else -> defaults.method
                    }

                TermDef(
                    text = text,
                    lang = term.field("lang")?.let { lang(it) } ?: defaults.lang,
                    method = method,
                    provenance = at(textNode),
                    matchProfile = profile,
                ).also { shortTermGuard(it) }
            }
        }

        /**
         * ⚑M-4 — the short-term guard, reported at authoring time.
         *
         * Fires on an INHERITED default too, and deliberately: a file that defaults to `TYPOS(1)`
         * and lists a two-character code has declared fuzz on that code just as surely as if it had
         * written the rule on the term. That is exactly the case p3-2's authoring advice is about,
         * and silence is what the guard exists to replace.
         */
        private fun shortTermGuard(term: TermDef) {
            val declaresTypos =
                term.matchProfile?.rules?.any { it.typos != null } ?: (term.method is MatchMethod.Typos)
            if (!declaresTypos) return
            if (TermNormalizer.normalize(term.text).length > MatchProfile.SHORT_TERM_MAX_CHARS) return
            warn(LexiconWarnings.shortTermTyposGuard(term.text, term.provenance))
        }

        /** One `match:` list → the profile it declares. Null when nothing usable survived. */
        fun profile(
            node: Node,
            where: String,
        ): MatchProfile? {
            val items = (node as? SequenceNode)?.value
            if (items.isNullOrEmpty()) {
                this += LexiconErrors.missingRequired(where, where, at(node))
                return null
            }
            val rules = items.mapNotNull { normRule(it, where) }
            return if (rules.isEmpty()) null else MatchProfile(rules)
        }

        private fun normRule(
            node: Node,
            where: String,
        ): NormRule? {
            val map = node as? MappingNode ?: return null.also { notAMapping(node, where) }
            strictKeys(map, MATCH_RULE_KEYS, where)

            val normNode = map.field("norm")
            if (normNode == null) {
                this += LexiconErrors.missingRequired("norm", where, at(map))
                return null
            }
            val raw = scalar(normNode).orEmpty()
            val norm =
                Norm.ofWire(raw) ?: return null.also {
                    this += LexiconErrors.unknownNorm(raw, at(normNode))
                }

            val exactNode = map.field("exact")
            val typosNode = map.field("typos")
            val tokensNode = map.field("tokens")
            if (exactNode == null && typosNode == null && tokensNode == null) {
                // A `{ norm: folded }` and nothing else declares a form to compare on and no way to
                // compare it — silently inert, which is the failure mode this catalogue exists to
                // turn into a message. Keyed on the KEYS, not on what they parsed to: an
                // out-of-range `exact` is one complaint, not two.
                this += LexiconErrors.missingRequired("exact", where, at(map))
                return null
            }

            val exact = exactNode?.let { score("exact", it) }
            val typos = typosNode?.let { typosRule(it, norm, hasExact = exactNode != null, where = where) }
            // Both fields legal, the pair not: `exact − d·penalty` at the widest edit is what the
            // matcher actually reports, and it has to stay inside (0,1] like any other score.
            // Checked here rather than in either field's own bound because neither one is wrong
            // alone — only their combination is.
            if (exact != null && typos != null && exact - typos.distance * typos.penalty <= 0.0) {
                this +=
                    LexiconErrors.typosBudgetExhaustsScore(norm.wire, exact, typos.distance, typos.penalty, at(node))
            }
            return NormRule(norm, exact = exact, typos = typos, tokens = tokensNode != null)
        }

        private fun typosRule(
            node: Node,
            norm: Norm,
            hasExact: Boolean,
            where: String,
        ): TyposRule? {
            val map = node as? MappingNode ?: return null.also { notAMapping(node, "$where.typos") }
            strictKeys(map, TYPOS_KEYS, "$where.typos")
            if (!hasExact) this += LexiconErrors.typosWithoutExact(norm.wire, at(node))

            val distance = map.field("distance")?.let { distanceValue(it) }
            if (map.field("distance") == null) {
                this += LexiconErrors.missingRequired("distance", "$where.typos", at(map))
            }
            val penalty = map.field("penalty")?.let { penaltyValue(it) }
            if (map.field("penalty") == null) {
                this += LexiconErrors.missingRequired("penalty", "$where.typos", at(map))
            }
            return if (distance == null || penalty == null) null else TyposRule(distance, penalty)
        }

        /** `(0,1]` — a declared score is a within-class score, and 0 would mean "never fires". */
        private fun score(
            key: String,
            node: Node,
        ): Double? {
            val raw = scalar(node)
            val value = raw?.toDoubleOrNull()
            if (value == null || value <= 0.0 || value > 1.0) {
                this += LexiconErrors.scoreOutOfRange(key, raw.orEmpty(), "scores are in (0,1]", at(node))
                return null
            }
            return value
        }

        /**
         * The edit budget: an integer ≥ 1. Note it is NOT capped at 3 the way `TYPOS(n)` sugar is —
         * the addendum bounds it below only, and the sugar's ceiling is the grammar's, not the
         * model's. [MatchProfile.sugarMethod] clamps on the way back out.
         */
        private fun distanceValue(node: Node): Int? {
            val raw = scalar(node)
            val value = raw?.toIntOrNull()
            if (value == null || value < 1) {
                this +=
                    LexiconErrors.scoreOutOfRange(
                        "distance",
                        raw.orEmpty(),
                        "the edit budget is a whole number ≥ 1",
                        at(node),
                    )
                return null
            }
            return value
        }

        private fun penaltyValue(node: Node): Double? {
            val raw = scalar(node)
            val value = raw?.toDoubleOrNull()
            if (value == null || value <= 0.0) {
                this +=
                    LexiconErrors.scoreOutOfRange(
                        "penalty",
                        raw.orEmpty(),
                        "the per-edit penalty is > 0 (an edit that costs nothing is not a penalty)",
                        at(node),
                    )
                return null
            }
            return value
        }

        private fun lang(node: Node): Lang {
            val raw = scalar(node).orEmpty()
            return Lang.ofWire(raw) ?: DEFAULT_LANG.also {
                this += LexiconErrors.unsupportedLang(raw, at(node))
            }
        }

        private fun method(node: Node): MatchMethod {
            val raw = scalar(node).orEmpty()
            return MatchMethod.ofWire(raw) ?: DEFAULT_METHOD.also {
                this +=
                    if (raw.trimStart().startsWith("TYPOS")) {
                        LexiconErrors.typosWithoutDistance(raw, at(node))
                    } else {
                        LexiconErrors.unknownMethod(raw, at(node))
                    }
            }
        }

        fun duplicates(terms: List<TermDef>) {
            val seen = mutableMapOf<Pair<String, Lang>, Int>()
            terms.forEach { term ->
                val key = term.text to term.lang
                val first = seen[key]
                if (first == null) {
                    seen[key] = term.provenance.line
                } else {
                    this += LexiconErrors.duplicateTerm(term.text, term.lang, first, term.provenance)
                }
            }
        }
    }
}
