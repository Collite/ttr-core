// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptSugar
import org.tatrman.ttr.parser.model.CubeletDef
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.DimensionDef
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.ConceptProperty
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.MdDomainDef
import org.tatrman.ttr.parser.model.MeasureDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import org.tatrman.ttr.lexicon.TermNormalizer
import org.tatrman.ttr.parser.generated.TTRLexer
import org.tatrman.ttr.parser.loader.TtrId

/** What a promotion changes: the def it adds or changes ([qname]), the diff, and each changed file's new text. */
data class PromotionPatch(
    val qname: String,
    val diff: String,
    val files: Map<String, String>,
)

sealed interface PromotionResult {
    data class Patched(
        val patch: PromotionPatch,
    ) : PromotionResult

    /** Nothing a steward could merge: the [reason] says why. */
    data class Refused(
        val reason: String,
    ) : PromotionResult
}

data class PullRequest(
    val title: String,
    val body: String,
)

/**
 * CN-P5 S5.2 T5 (cn contracts §6, A-CN-84) — a promotion is a model TEXT change: canon stays text (CN-D1), the steward
 * merges a pull request, the publish pipeline snapshots it. This writes the change, against the model package's files
 * as text (path → content), as a unified diff `git apply` takes:
 * - a **concept** → `def <stereo> <Name> { … }` appended to the file that defines its anchor (its `of:`, else `event:`,
 *   `is_a:`, first participant; else the first `model cn` file), carrying `sources: ["<source_ref>"]`;
 * - a **term** → `def term <id> { for: cn.X, forms: […] }` appended to the locale's lexicon file (a new
 *   `lexicon-<locale>.ttrm` when the package has none); a lexicon entry has no `sources:`, so a comment line carries it;
 * - a **relation** → a `{ type: <t>, to: <To> }` entry in the `from` concept's `relations:` (the property added when
 *   absent), and `def relation_type <t> {}` when the package does not define the type.
 *
 * Refused, never guessed: a candidate that is not proposed, a def the package already has, a ref the package does not
 * define, a term whose queued concept is not promoted yet, a package that does not parse. Pure: the store hands the
 * files in and gets text back ([promoted] answers which concept a queued candidate became).
 */
object Promotion {
    private const val INDENT = "    "
    private val NOT_ID = Regex("[^a-z0-9]+")
    private val BACKTICKS = Regex("`+")

    /** `TTR.g4`'s `TAGGED_BLOCK_LITERAL` opening: a word, optional blanks, a line end — read as a language tag. */
    private val LONE_WORD_LINE = Regex("^[a-zA-Z][a-zA-Z0-9-]*[ \t]*\r?\n")

    fun patch(
        candidate: CnCandidate,
        model: Map<String, String>,
        promoted: (String) -> String? = { null },
    ): PromotionResult {
        if (candidate.status != Status.PROPOSED) {
            return refused(
                "candidate ${candidate.id} is ${candidate.status.word}: only a proposed candidate is promoted",
            )
        }
        val pkg = Pkg.of(model)
        if (pkg.errors.isNotEmpty()) {
            return refused(
                "the model package does not parse: ${pkg.errors.joinToString("; ")}",
            )
        }
        return when (val p = candidate.proposal) {
            is Proposal.Concept -> concept(candidate, p, pkg)
            is Proposal.Term -> term(candidate, p, pkg, promoted)
            is Proposal.Relation -> relation(p, pkg)
        }
    }

    /** The pull request a promotion opens: what, from whom, why — and, for a phase, its er rendering to read. */
    fun pullRequest(
        candidate: CnCandidate,
        patch: PromotionPatch,
        erRendering: String? = null,
    ): PullRequest {
        val p = candidate.provenance
        val what =
            when (val proposal = candidate.proposal) {
                is Proposal.Concept -> "${proposal.stereo.word} ${proposal.name}"
                is Proposal.Term -> "term ${patch.qname.removePrefix("lexicon.")} (${proposal.locale})"
                is Proposal.Relation -> "relation ${proposal.typeName}: ${proposal.from} → ${proposal.to}"
            }
        val body = StringBuilder()
        body.append("Promotes cn candidate `${candidate.id}` — ").append(what).append(" (`${patch.qname}`).\n\n")
        body.append("- **producer:** ${code(p.producer)}\n")
        p.sourceRef?.let { body.append("- **source:** ${code(it)}\n") }
        body.append(
            "- **confidence:** ${p.confidence.value} (${code(p.confidence.producer)} · ${code(p.confidence.method)})\n",
        )
        body.append("- **proposed:** ${p.createdAt}\n")
        p.evidence?.let { e ->
            val counts =
                listOfNotNull(
                    e.questionCount?.let { "$it question(s)" },
                    e.documentCount?.let { "$it document(s)" },
                )
            body.append("\n### Evidence")
            if (counts.isNotEmpty()) body.append(" — ").append(counts.joinToString(" · "))
            body.append("\n\n")
            for (x in e.examples) {
                body.append("- ${x.kind.word} ${code(x.ref)}: ").append(code(x.text.replace('\n', ' '))).append('\n')
            }
        }
        val er = erRendering ?: (candidate.proposal as? Proposal.Concept)?.erRendering
        if (er != null) {
            val fence = "`".repeat(maxOf(3, longestBacktickRun(er) + 1))
            body.append("\n### er rendering\n\nThe phase as SQL over entities (`definition.render`, er):\n\n")
            body
                .append(fence)
                .append("sql\n")
                .append(er.trimEnd())
                .append('\n')
                .append(fence)
                .append('\n')
        }
        return PullRequest("cn: promote $what", body.toString())
    }

    /**
     * Producer text as an inline code span (review-133 F10): no mention, link, image or emphasis can come alive in it —
     * the fence one backtick longer than any run inside, padded when the text starts or ends with a backtick.
     */
    private fun code(text: String): String {
        val fence = "`".repeat(longestBacktickRun(text) + 1)
        val pad = if (text.startsWith('`') || text.endsWith('`')) " " else ""
        return fence + pad + text + pad + fence
    }

    private fun longestBacktickRun(text: String): Int = BACKTICKS.findAll(text).maxOfOrNull { it.value.length } ?: 0

    /** `canon_ref`: the merged commit and the def's qname. */
    fun canonRef(
        commit: String,
        qname: String,
    ): String = "$commit:$qname"

    // ----- the three proposal kinds -----

    private fun concept(
        candidate: CnCandidate,
        p: Proposal.Concept,
        pkg: Pkg,
    ): PromotionResult {
        pkg.holder(p.name)?.let { return refused("cn.${p.name} is already defined $it") }
        val refs = listOfNotNull(p.of, p.event) + p.isA + p.involves.map { it.concept }
        val missing = refs.filterNot { pkg.defined(bare(it)) }.distinct()
        if (missing.isNotEmpty()) {
            return refused("cn.${p.name} names ${missing.joinToString()}, which this package does not define")
        }
        p.suggestedGrounding?.let { g ->
            if (g.substringAfterLast('.') !in pkg.elements) {
                return refused(
                    "grounds: $g — the package defines no er or md element named '${g.substringAfterLast('.')}'",
                )
            }
        }
        // The anchor's file — unless the anchor is a sugar concept, whose file is er/md (review-133 ⚑R133-c): then a cn
        // file of its package, else a new one beside it (F8). No anchor: the first cn file.
        val anchor = refs.firstOrNull()?.let(::bare)
        val near = anchor?.let { pkg.fileOf(it) }
        val path =
            anchor?.let { pkg.concepts[it]?.path }
                ?: (
                    if (near !=
                        null
                    ) {
                        pkg.cnFiles.firstOrNull { pkg.packages[it] == pkg.packages[near] }
                    } else {
                        pkg.cnFiles.firstOrNull()
                    }
                )
                ?: pkg.newPath("cn", near)
        val props = mutableListOf<String>()
        p.of?.let { props += "of: ${bare(it)}" }
        p.event?.let { props += "event: ${bare(it)}" }
        if (p.isA.isNotEmpty()) {
            props +=
                "is_a: " + p.isA.map(::bare).let { if (it.size == 1) it.single() else "[${it.joinToString()}]" }
        }
        if (p.involves.isNotEmpty()) {
            props +=
                "involves: [" + p.involves.joinToString { i -> bare(i.concept) + (i.label?.let { " as $it" } ?: "") } +
                "]"
        }
        p.basis?.let { props += "basis: ${it.word}" }
        if (p.label.isNotEmpty()) {
            props +=
                "label: { " + p.label.entries.joinToString { (k, v) -> "$k: ${quote(v)}" } + " }"
        }
        candidate.provenance.sourceRef?.let { props += "sources: [${quote(it)}]" }
        p.suggestedGrounding?.let { props += "grounds: $it" }
        p.definitionText?.let { props += "definition: ${definitionBlock(it)}" }
        val def =
            if (props.isEmpty()) {
                "def ${p.stereo.word} ${p.name} {}"
            } else {
                "def ${p.stereo.word} ${p.name} {\n" + props.joinToString(",\n") { INDENT + it } + "\n}"
            }
        val base = pkg.texts[path] ?: (pkg.header(near ?: pkg.texts.keys.firstOrNull()) + "model cn\n")
        return patched("cn.${p.name}", mapOf(path to append(base, def)), pkg)
    }

    private fun term(
        candidate: CnCandidate,
        p: Proposal.Term,
        pkg: Pkg,
        promoted: (String) -> String?,
    ): PromotionResult {
        val target =
            p.targetQname ?: promoted(p.proposedConceptRef!!)
                ?: return refused("the term waits for candidate ${p.proposedConceptRef}, which is not promoted yet")
        if (!pkg.defined(bare(target))) return refused("the term targets $target, which this package does not define")
        if (p.default == true) {
            val folded = p.forms.map(TermNormalizer::fold).toSet()
            pkg.defaults[p.locale].orEmpty().firstOrNull { it.second in folded }?.let { (entry, form) ->
                return refused(
                    "'$form' already has a default sense in locale ${p.locale} (def term $entry) — one per form " +
                        "(cn/duplicate-default-sense)",
                )
            }
        }
        val stem = termId(p.forms.first())
        val id = numbered(stem) { it !in pkg.lexiconIds && TtrId.isId(it) }
        // The locale's lexicon file in the target's package, else a new one beside the target (review-133 F8).
        val near = pkg.fileOf(bare(target))
        val path =
            pkg.lexiconFiles[p.locale].orEmpty().firstOrNull { pkg.packages[it] == pkg.packages[near] }
                ?: pkg.newPath("lexicon-${p.locale}", near)
        val fields =
            listOf("for: $target", "forms: [${p.forms.joinToString { quote(it) }}]") +
                listOfNotNull(p.default?.let { "default: $it" })
        val entry =
            (candidate.provenance.sourceRef?.let { "// sources: $it\n" } ?: "") +
                "def term $id { ${fields.joinToString()} }"
        val text = append(pkg.texts[path] ?: (pkg.header(near) + "model lexicon locale ${p.locale}\n"), entry)
        return patched("lexicon.$id", mapOf(path to text), pkg)
    }

    private fun relation(
        p: Proposal.Relation,
        pkg: Pkg,
    ): PromotionResult {
        if (pkg.defined(p.typeName)) {
            return refused("cn.${p.typeName} is a concept, not a relation type — they share one cn name space")
        }
        val missing = listOf(p.from, p.to).filterNot { pkg.defined(bare(it)) }.distinct()
        if (missing.isNotEmpty()) {
            return refused("the relation names ${missing.joinToString()}, which this package does not define")
        }
        val from =
            pkg.concepts[bare(p.from)] ?: return refused(
                "${p.from} is declared by `concept { }` in ${pkg.sugar.getValue(
                    bare(p.from),
                )}: no cn def carries its " +
                    "relations — state the relation there by hand",
            )
        val existing = from.def.body["relations"] as? ConceptProperty.Entry
        val stated =
            (existing?.value as? PropertyValue.ListValue)?.items.orEmpty().any { item ->
                val o = item as? PropertyValue.ObjectValue
                o != null && word(o.entries["type"]) == p.typeName && word(o.entries["to"]) == bare(p.to)
            }
        if (stated) return refused("${p.from} already states the relation ${p.typeName} → ${p.to}")
        val text = pkg.texts.getValue(from.path)
        val entry = "{ type: ${p.typeName}, to: ${bare(p.to)} }"
        var edited = insertRelation(text, from.def, existing, entry)
        if (p.typeName !in pkg.relationTypes) edited = append(edited, "def relation_type ${p.typeName} {}")

        return patched(p.from, mapOf(from.path to edited), pkg)
    }

    // ----- text surgery -----

    /**
     * [entry] into [def]'s `relations:` list, or a new `relations:` property after its last one — placed by the lexer's
     * tokens, never by scanning characters, so a comment is never mistaken for code (review-133 F4): comments are on the
     * hidden channel, and a trailing comment stays on the line it was written on.
     */
    private fun insertRelation(
        text: String,
        def: ConceptDef,
        existing: ConceptProperty.Entry?,
        entry: String,
    ): String {
        val tokens = tokens(text)
        val code = tokens.filter { it.channel == Token.DEFAULT_CHANNEL }

        fun after(t: Token) = chars(text, t.stopIndex + 1)

        if (existing != null) {
            val close = code.first { it.startIndex == existing.source.offsetEnd - 1 } // the list's `]`
            val prev = code.last { it.stopIndex < close.startIndex }
            val at = after(prev)
            val pad = if (text[at] == ']') " " else ""
            return text.substring(0, at) + (if (prev.text == "[" || prev.text == ",") " " else ", ") + entry + pad +
                text.substring(at)
        }
        val property = "relations: [ $entry ]"
        val close = code.first { it.startIndex == def.source.offsetEnd - 1 } // the def's `}`
        val last = def.body.properties.lastOrNull()
        if (last == null) {
            val open = code.first { it.text == "{" && it.startIndex >= def.source.offsetStart }
            val at = after(open)
            return text.substring(0, at) + " $property" + (if (text[at] == '}') " " else "") + text.substring(at)
        }
        val lastToken = code.last { it.stopIndex < last.source.offsetEnd }
        val next = code.first { it.startIndex > lastToken.stopIndex }
        val anchor = if (next.text == ",") next else lastToken
        if (close.line == anchor.line) {
            val at = after(anchor)
            return text.substring(0, at) + (if (anchor === next) " $property," else ", $property") + text.substring(at)
        }
        // Multi-line: the new property on its own line, after any comment that ends the anchor's line.
        val lineEnd =
            tokens
                .filter {
                    it.line == anchor.line && it.startIndex > anchor.stopIndex && it.startIndex < close.startIndex
                }.maxByOrNull { it.stopIndex } ?: anchor
        val lineStart = text.lastIndexOf('\n', chars(text, last.source.offsetStart)) + 1
        val indent = text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
        val atLine = after(lineEnd)
        val edited =
            text.substring(0, atLine) + "\n$indent$property" + (if (anchor === next) "," else "") +
                text.substring(atLine)
        if (anchor === next) return edited
        val atComma = after(lastToken)
        return edited.substring(0, atComma) + "," + edited.substring(atComma)
    }

    /** Every token of [text], hidden-channel comments included (whitespace is skipped by the lexer). */
    private fun tokens(text: String): List<Token> {
        val stream = CommonTokenStream(TTRLexer(CharStreams.fromString(text)))
        stream.fill()
        return stream.tokens.filter { it.type != Token.EOF }
    }

    /** [block] appended to [text], one blank line between, one newline at the end. */
    private fun append(
        text: String,
        block: String,
    ): String {
        val base = if (text.isEmpty() || text.endsWith("\n")) text else "$text\n"
        val gap = if (base.isEmpty() || base.endsWith("\n\n")) "" else "\n"
        return base + gap + block + "\n"
    }

    /**
     * The changed files as a patch — once each has been read back. Every write above is text surgery; parsing what it
     * wrote is the net under all of it (review-133 F3): a patch the steward could not merge is never emitted.
     */
    private fun patched(
        qname: String,
        changed: Map<String, String>,
        pkg: Pkg,
    ): PromotionResult {
        for ((path, text) in changed.toSortedMap()) {
            val e = TtrLoader.parseString(text, path).errors.firstOrNull() ?: continue
            val where = "$path:${e.line}:${e.column}"
            return refused("the patch would not parse (a promotion bug): $where ${e.message.lineSequence().first()}")
        }
        val diff =
            changed.toSortedMap().entries.joinToString(
                "",
            ) { (path, text) -> UnifiedDiff.of(path, pkg.texts[path], text) }
        return PromotionResult.Patched(PromotionPatch(qname, diff, changed.toSortedMap()))
    }

    private fun refused(reason: String) = PromotionResult.Refused(reason)

    private fun bare(ref: String): String = ref.removePrefix("cn.")

    private fun word(v: PropertyValue?): String? = (v as? PropertyValue.IdValue)?.parts?.lastOrNull()

    /** A TTR string literal: `"` and `\` escaped (the values are single lines — the schema says so). */
    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * A term's def id from its first form: the lexicon's own fold (`TermNormalizer.fold` — lower case, diacritics off),
     * `_` between words (`přeložení` → `prelozeni`); a word the grammar reserves takes `_term` (`date` → `date_term`).
     */
    internal fun termId(form: String): String {
        val folded = TermNormalizer.fold(form).replace(NOT_ID, "_").trim('_')
        val stem =
            when {
                folded.isEmpty() -> "term"
                folded.first().isDigit() -> "t_$folded"
                else -> folded
            }
        return if (TtrId.isId(stem)) stem else "${stem}_term"
    }

    /** [stem], else `<stem>_2`, `<stem>_3`, … — the first that [free] takes. */
    private fun numbered(
        stem: String,
        free: (String) -> Boolean,
    ): String = generateSequence(1) { it + 1 }.map { if (it == 1) stem else "${stem}_$it" }.first(free)

    /**
     * `definition:`'s block, written so the lexer reads back exactly [text] (review-133 ⚑R133-b): `"""<text>"""`, the
     * house spelling — unless [text] ends in `"` (the closing `"""` would take it) or its first line is a lone word (the
     * lexer would read it as a block's language tag); then a `pl`-tagged block, the closing quotes on their own line.
     */
    internal fun definitionBlock(text: String): String =
        if (text.endsWith('"') ||
            LONE_WORD_LINE.containsMatchIn(text)
        ) {
            "\"\"\"pl\n$text\n\"\"\""
        } else {
            "\"\"\"$text\"\"\""
        }

    /** A parser offset (code points) as a string index. */
    private fun chars(
        text: String,
        codePoints: Int,
    ): Int = text.offsetByCodePoints(0, codePoints)

    /**
     * The package as the promotion reads it: which file defines what. Only its `.ttrm` files are model text (anything
     * else in the map is ignored — review-133 F12). One cn name space (F6): a concept def, a relation type and a concept
     * declared by `concept { }` sugar in an er / md def (F5) each take a name.
     */
    private class Pkg(
        val texts: Map<String, String>,
        val taken: Set<String>,
        val errors: List<String>,
        val packages: Map<String, String?>,
        val concepts: Map<String, Located>,
        val sugar: Map<String, String>,
        val relationTypes: Map<String, String>,
        val cnFiles: List<String>,
        val lexiconFiles: Map<String, List<String>>,
        val lexiconIds: Set<String>,
        /** Per locale: (entry, folded form) for every form of an entry that says `default: true`. */
        val defaults: Map<String, List<Pair<String, String>>>,
        /** Every er entity / attribute and md element name — what a `grounds:` ref's last segment may name. */
        val elements: Set<String>,
    ) {
        class Located(
            val path: String,
            val def: ConceptDef,
        )

        /** A concept the package defines — by a cn def or by sugar. */
        fun defined(name: String): Boolean = name in concepts || name in sugar

        /** The file that defines concept [name] (its cn def, or the er / md def carrying its sugar). */
        fun fileOf(name: String): String? = concepts[name]?.path ?: sugar[name]

        /** What already holds [name] in the cn name space, in words; null when it is free. */
        fun holder(name: String): String? =
            concepts[name]?.let { "(${it.path})" }
                ?: sugar[name]?.let { "(by `concept { }` in $it)" }
                ?: relationTypes[name]?.let {
                    "as a relation type ($it) — concepts and relation types share one cn name space"
                }

        /** A new file `<base>.ttrm` in [near]'s directory, stepping around a taken path (`<base>-2.ttrm`, …). */
        fun newPath(
            base: String,
            near: String?,
        ): String {
            val dir = near?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }?.let { "$it/" } ?: ""
            return generateSequence(1) { it + 1 }
                .map { if (it == 1) "$dir$base.ttrm" else "$dir$base-$it.ttrm" }
                .first { it !in taken }
        }

        /** A new file's `package` line — [near]'s package, so the def lands in its concept's package (F8). */
        fun header(near: String?): String = near?.let { packages[it] }?.let { "package $it\n\n" } ?: ""

        companion object {
            private val SUGAR_KEYS = listOf("kind", "role", "event", "quality")
            private const val MAX_ERRORS = 5

            fun of(model: Map<String, String>): Pkg {
                val texts = model.filterKeys { it.endsWith(".ttrm") }.toSortedMap()
                val errors = mutableListOf<String>()
                val packages = linkedMapOf<String, String?>()
                val concepts = linkedMapOf<String, Located>()
                val sugar = linkedMapOf<String, String>()
                val relationTypes = linkedMapOf<String, String>()
                val cnFiles = mutableListOf<String>()
                val lexiconFiles = linkedMapOf<String, MutableList<String>>()
                val lexiconIds = mutableSetOf<String>()
                val defaults = linkedMapOf<String, MutableList<Pair<String, String>>>()
                val elements = mutableSetOf<String>()
                for ((path, text) in texts) {
                    val parsed = TtrLoader.parseString(text, path)
                    for (e in parsed.errors) {
                        errors +=
                            "${e.file}:${e.line}:${e.column} ${e.message.lineSequence().first()}"
                    }
                    packages[path] = parsed.packageName
                    for (d in parsed.definitions) {
                        for (s in sugarOf(d)) {
                            SUGAR_KEYS
                                .firstNotNullOfOrNull {
                                    s.body.ref(
                                        it,
                                    )
                                }?.let { sugar.putIfAbsent(it.parts.last(), path) }
                        }
                        elements += elementsOf(d, parsed.modelDirective?.modelCode)
                    }
                    val directive = parsed.modelDirective
                    when (directive?.modelCode) {
                        "cn" -> {
                            cnFiles += path
                            for (d in parsed.definitions.filterIsInstance<ConceptDef>()) {
                                if (d.stereotype == "relation_type") {
                                    relationTypes.putIfAbsent(d.name, path)
                                } else {
                                    concepts.putIfAbsent(d.name, Located(path, d))
                                }
                            }
                        }
                        "lexicon" -> {
                            val locale = directive.locale
                            val entries = parsed.definitions.filterIsInstance<LexiconEntryDef>()
                            lexiconIds += entries.map { it.name }
                            if (locale != null) {
                                lexiconFiles.getOrPut(locale) { mutableListOf() } += path
                                for (e in entries.filter { it.default == true }) {
                                    defaults.getOrPut(locale) { mutableListOf() } +=
                                        e.forms.map { e.name to TermNormalizer.fold(it) }
                                }
                            }
                        }
                    }
                }
                return Pkg(
                    texts = texts,
                    taken = model.keys,
                    errors = errors.take(MAX_ERRORS),
                    packages = packages,
                    concepts = concepts,
                    sugar = sugar,
                    relationTypes = relationTypes,
                    cnFiles = cnFiles,
                    lexiconFiles = lexiconFiles,
                    lexiconIds = lexiconIds,
                    defaults = defaults,
                    elements = elements,
                )
            }

            /** Every `concept { }` an er or md def carries (cn contracts §1.4): its own, and its attributes'. */
            private fun sugarOf(d: Definition): List<ConceptSugar> =
                when (d) {
                    is EntityDef -> listOfNotNull(d.concept) + d.attributes.mapNotNull { it.concept }
                    is DimensionDef -> listOfNotNull(d.concept) + d.attributes.mapNotNull { it.concept }
                    is MdDomainDef -> listOfNotNull(d.concept)
                    is MeasureDef -> listOfNotNull(d.concept)
                    is CubeletDef -> listOfNotNull(d.concept)
                    else -> emptyList()
                }

            /**
             * The names a `grounds:` ref may end in: er entities and their attributes, md elements and dimension
             * attributes — a cheap check from the text (F9); the resolver's own reading is the S5.3 load gate's.
             */
            private fun elementsOf(
                d: Definition,
                model: String?,
            ): List<String> =
                when (d) {
                    is EntityDef -> listOf(d.name) + d.attributes.map { it.name }
                    is DimensionDef -> listOf(d.name) + d.attributes.map { it.name }
                    is MdDomainDef, is MeasureDef, is CubeletDef -> listOf(d.name)
                    else -> if (model == "md" || model == "er") listOf(d.name) else emptyList()
                }
        }
    }
}
