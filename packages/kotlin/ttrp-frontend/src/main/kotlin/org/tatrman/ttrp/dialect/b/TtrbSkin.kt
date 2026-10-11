// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.tatrman.ttrp.parser.generated.TTRBLexer
import org.tatrman.ttrp.parser.generated.TTRBParser
import org.yaml.snakeyaml.Yaml
import java.text.Normalizer
import java.util.Locale

/**
 * A TTR-B keyword skin (AG B2): one language's spelling of the keyword token types `TTRB.g4`
 * declares. ONE grammar, several tables — `roster.en.yaml` (`"""ttrb`, `*.ttrb`) and
 * `roster.cs.yaml` (`"""ttrb-cs`, `*.ttrb-cs`). A spelling (one or more words, a word may be
 * hyphenated) stands for one keyword token or, as a `sequence`, for several in a row (`není` →
 * IS NOT). Matching is [fold]ed — case- and diacritic-insensitive — and closed (P2): a word the
 * table does not spell is an identifier and keeps its exact text.
 */
class TtrbSkin private constructor(
    /** ISO language code of the skin (`en`, `cs`). */
    val lang: String,
    /** The fragment tag / file dialect this skin serves (`ttrb`, `ttrb-cs`). */
    val tag: String,
    /** Token name → the spellings that stand for it alone (as written in the table). */
    val keywords: Map<String, List<String>>,
    /** Spelling → the token names it stands for, in order. */
    val sequences: Map<String, List<String>>,
    /** Tokens this language has no standalone word for (optional noise in the grammar). */
    val absent: Set<String>,
    /** Folded function name in this language → the catalogue name (`soucet` → `sum`). */
    private val functions: Map<String, String>,
) {
    /** A node of the folded-phrase trie: words → children; a complete phrase carries its token types. */
    internal class Node {
        val children = HashMap<String, Node>()
        var types: IntArray? = null
    }

    internal val trie = Node()

    /** Every token this skin spells — standalone or inside a sequence. */
    val spelledTokens: Set<String> = keywords.keys + sequences.values.flatten()

    init {
        val seen = HashMap<String, String>()

        fun add(
            spelling: String,
            tokenNames: List<String>,
            what: String,
        ) {
            val words = words(spelling)
            require(words.isNotEmpty()) { "skin $lang: empty spelling for $what" }
            val key = words.joinToString(" ")
            seen[key]?.let { other ->
                // Two spellings that fold alike for the SAME token(s) are one spelling (`rovna` / `rovná`).
                if (other == what) return
                throw IllegalArgumentException("skin $lang: `$key` spells both $other and $what")
            }
            seen[key] = what
            var node = trie
            for (w in words) node = node.children.getOrPut(w) { Node() }
            node.types = tokenNames.map { typeOf(it, lang) }.toIntArray()
        }
        for ((token, spellings) in keywords) for (s in spellings) add(s, listOf(token), token)
        for ((spelling, tokens) in sequences) add(spelling, tokens, tokens.joinToString(" "))
        for (a in absent) typeOf(a, lang)
    }

    /** The catalogue function a (possibly localized) function name stands for, else the name itself. */
    fun function(name: String): String = functions[fold(name)] ?: name

    /** True if [word] (one word, any case/diacritics) begins or is a keyword spelling of this skin. */
    fun spells(word: String): Boolean = trie.children.containsKey(fold(word))

    /** The token types [word] stands for when it is a complete one-word spelling, else null. */
    fun typesOf(word: String): IntArray? = trie.children[fold(word)]?.types

    /**
     * The sentence-verb roster as this skin spells it (assist / authoring context): one entry per verb
     * group, synonyms joined with `/` — `Keep/Take/Select` (English), `Ponech/Vezmi/Vyber` (Czech).
     */
    fun verbRoster(): List<String> =
        VERB_GROUPS.mapNotNull { group ->
            group
                .mapNotNull { keywords[it]?.firstOrNull() }
                .map { w -> w.replaceFirstChar { it.uppercaseChar() } }
                .ifEmpty { null }
                ?.joinToString("/")
        }

    override fun toString(): String = "TtrbSkin($lang)"

    companion object {
        /** English — `"""ttrb`, `*.ttrb`. */
        val EN: TtrbSkin by lazy { load("en", "ttrb") }

        /** Czech — `"""ttrb-cs`, `*.ttrb-cs`. */
        val CS: TtrbSkin by lazy { load("cs", "ttrb-cs") }

        val all: List<TtrbSkin> get() = listOf(EN, CS)

        /** The skin for a fragment tag / bare-file dialect, or null for an unknown one. */
        fun forTag(tag: String): TtrbSkin? =
            when (tag) {
                "ttrb" -> EN
                "ttrb-cs" -> CS
                else -> null
            }

        /** True for any TTR-B dialect tag (`ttrb`, `ttrb-<lang>`), known or not. */
        fun isTtrbTag(tag: String): Boolean = tag == "ttrb" || tag.startsWith("ttrb-")

        /** The sentence verbs, grouped by synonymy (the order of the roster in docs and assist). */
        private val VERB_GROUPS: List<List<String>> =
            listOf(
                listOf("LOAD"),
                listOf("KEEP", "TAKE", "SELECT"),
                listOf("FILTER"),
                listOf("REMOVE", "DELETE"),
                listOf("RENAME"),
                listOf("CONVERT", "RETYPE"),
                listOf("CREATE", "COMPUTE", "CALCULATE"),
                listOf("SUMMARIZE"),
                listOf("JOIN"),
                listOf("SORT"),
                listOf("COMBINE", "APPEND", "UNION"),
                listOf("STORE"),
                listOf("SHOW", "DISPLAY"),
                listOf("IF"),
                listOf("COUNT"),
                listOf("ATTACH"),
                listOf("SEND"),
                listOf("SET"),
                listOf("NAME_VERB"),
            )

        /** Tokens injected by the token source itself — never spelled by a skin. */
        val STRUCTURAL_TOKENS: Set<String> = setOf("INDENT", "DEDENT")

        /**
         * The keyword token types of the grammar: every symbolic token name that has no lexer
         * rule (they are declared in `tokens { … }` and spelled by the skins).
         */
        val KEYWORD_TOKENS: List<String> by lazy {
            val lexerRules = TTRBLexer.ruleNames.toSet()
            (1..TTRBParser.VOCABULARY.maxTokenType)
                .mapNotNull { TTRBParser.VOCABULARY.getSymbolicName(it) }
                .filter { it !in lexerRules && it !in STRUCTURAL_TOKENS }
        }

        /**
         * Case- and diacritic-insensitive fold: NFD-decompose, drop combining marks, lowercase
         * (locale-neutral). `Pošli` / `POSLI` / `posli` all fold to `posli`.
         */
        fun fold(s: String): String =
            Normalizer
                .normalize(s, Normalizer.Form.NFD)
                .replace(COMBINING, "")
                .lowercase(Locale.ROOT)

        private val COMBINING = Regex("\\p{Mn}+")

        private fun words(spelling: String): List<String> =
            spelling
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .map { fold(it) }

        private fun typeOf(
            name: String,
            lang: String,
        ): Int {
            val t =
                (1..TTRBParser.VOCABULARY.maxTokenType).firstOrNull {
                    TTRBParser.VOCABULARY.getSymbolicName(it) ==
                        name
                }
            require(t != null && name in KEYWORD_TOKENS) { "skin $lang: `$name` is not a TTR-B keyword token" }
            return t
        }

        private fun load(
            lang: String,
            tag: String,
        ): TtrbSkin {
            val path = "/ttrb/roster.$lang.yaml"
            val text =
                TtrbSkin::class.java
                    .getResourceAsStream(path)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("TTR-B skin resource not found: $path")
            return parse(text, tag)
        }

        /** Parses a skin table (the `roster.<lang>.yaml` shape) — exposed for the table specs. */
        fun parse(
            yamlText: String,
            tag: String,
        ): TtrbSkin {
            val root: Map<*, *> = Yaml().load<Any?>(yamlText) as? Map<*, *> ?: error("TTR-B skin $tag: not a mapping")
            val lang = root["lang"] as? String ?: error("TTR-B skin $tag: missing `lang`")

            fun strMap(key: String): Map<String, Any?> =
                (root[key] as? Map<*, *>).orEmpty().entries.associate { (k, v) ->
                    require(k is String) { "skin $lang: `$key` key `$k` is not a string (quote YAML 1.1 booleans)" }
                    k to v
                }

            fun strList(
                v: Any?,
                what: String,
            ): List<String> =
                (v as? List<*> ?: error("skin $lang: $what is not a list")).map {
                    require(it is String) { "skin $lang: $what holds `$it`, not a string" }
                    it
                }
            val keywords = strMap("keywords").mapValues { (k, v) -> strList(v, "keywords.$k") }
            val sequences = strMap("sequences").mapValues { (k, v) -> strList(v, "sequences.$k") }
            val absent = strList(root["absent"] ?: emptyList<String>(), "absent").toSet()
            val functions = strMap("functions").entries.associate { (k, v) -> fold(k) to v.toString() }
            val clash = absent.intersect(keywords.keys)
            require(clash.isEmpty()) { "skin $lang: $clash are both spelled and absent" }
            return TtrbSkin(lang, tag, keywords, sequences, absent, functions)
        }
    }
}
