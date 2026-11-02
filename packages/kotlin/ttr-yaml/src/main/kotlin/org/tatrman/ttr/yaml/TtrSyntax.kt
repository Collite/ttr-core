// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import kotlinx.serialization.json.*
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import org.tatrman.ttr.parser.TtrStrings
import org.tatrman.ttr.parser.generated.TTRLexer
import org.tatrman.ttr.parser.generated.TTRParser
import org.tatrman.ttr.parser.walker.Dedent
import org.tatrman.ttr.parser.walker.resolveExpressionTag

/** The syntax projection retains forms the semantic AST intentionally folds (graph/restrict/ordering). */
internal class TtrSyntax(
    private val file: String,
) {
    private var model: String? = null

    data class Span(
        val start: Int,
        val end: Int,
        val path: String,
    )

    val spans = mutableListOf<Span>()
    private val numbers = java.util.IdentityHashMap<JsonElement, String>()

    fun spelling(value: JsonElement): String? = numbers[value]

    private val origins = java.util.IdentityHashMap<JsonElement, Pair<Int, Int>>()
    private var input = ""

    fun invalid(
        path: String,
        message: String,
    ): Nothing {
        var p = path
        while (true) {
            val span = spans.filter { it.path == p }.minByOrNull { it.start }
            if (span != null) invalidAt(span.start, message)
            if (p.isEmpty()) invalidAt(0, message)
            p = p.substringBeforeLast('/', "")
        }
    }

    fun invalid(
        value: JsonElement,
        message: String,
    ): Nothing = invalidAt(origins[value]?.first ?: 0, message)

    private fun invalidAt(
        offset: Int,
        message: String,
    ): Nothing {
        val prefix = input.take(offset)
        throw CanonicalYamlException(
            file,
            prefix.count { it == '\n' } + 1,
            prefix.length - prefix.lastIndexOf('\n'),
            message,
        )
    }

    private fun recorded(
        tree: ParseTree,
        value: JsonElement,
    ): JsonElement {
        val ctx = tree as? ParserRuleContext
        val first = ctx?.start ?: (tree as TerminalNode).symbol
        val last = ctx?.stop ?: first
        if (value != JsonNull) origins.putIfAbsent(value, first.startIndex to last.stopIndex + 1)
        return value
    }

    val genericPaths = mutableSetOf<String>()
    val embeddedPaths = mutableSetOf<String>()
    private val genericNodes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<JsonElement, Boolean>())
    private val embeddedNodes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<JsonElement, Boolean>())
    private val propertyOrigins = java.util.IdentityHashMap<JsonElement, List<Pair<String, ParseTree>>>()

    private fun paths(
        value: JsonElement,
        path: String,
    ) {
        if (value in genericNodes) genericPaths += path
        if (value in embeddedNodes) embeddedPaths += path
        propertyOrigins[value]?.forEach { (suffix, tree) ->
            val ctx = tree as ParserRuleContext
            spans += Span(ctx.start.startIndex, (ctx.stop ?: ctx.start).stopIndex + 1, path + suffix)
        }
        origins[value]?.let { spans += Span(it.first, it.second, path) }
        when (value) {
            is JsonObject -> value.forEach { (key, child) -> paths(child, "$path/${pointerKey(key)}") }
            is JsonArray -> value.forEachIndexed { i, child -> paths(child, "$path/$i") }
            else -> Unit
        }
    }

    private fun ParseTree.name(): String = if (this is ParserRuleContext) TTRParser.ruleNames[ruleIndex] else ""

    private fun ParseTree.nodes(): List<ParseTree> = (0 until childCount).map(::getChild)

    private fun ParseTree.rules(): List<ParseTree> = nodes().filterIsInstance<ParserRuleContext>()

    private fun ParseTree.named(name: String) = rules().filter { it.name() == name }

    private fun ParseTree.one(name: String) = named(name).single()

    private fun fail(
        tree: ParseTree,
        message: String,
    ): Nothing {
        val token = (tree as? ParserRuleContext)?.start ?: (tree as TerminalNode).symbol
        throw CanonicalYamlException(file, token.line, token.charPositionInLine + 1, message)
    }

    fun read(source: String): JsonObject {
        input = source
        val errors = mutableListOf<CanonicalYamlException>()
        val listener =
            object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?,
                ) {
                    errors += CanonicalYamlException(file, line, charPositionInLine + 1, msg ?: "Invalid TTR")
                }
            }
        val lexer =
            TTRLexer(CharStreams.fromString(source)).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        val tokens = CommonTokenStream(lexer)
        TtrTokens.checkNesting(tokens) { token, message ->
            throw CanonicalYamlException(file, token.line, token.charPositionInLine + 1, message)
        }
        val parser =
            TTRParser(tokens).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        val doc = parser.document()
        if (errors.isNotEmpty()) throw errors.first()
        val directive = doc.named("modelDirective").firstOrNull()
        model = directive?.one("modelCode")?.text
        val result =
            buildJsonObject {
                put("ttr", CanonicalGrammar.version)
                doc.named("packageDecl").firstOrNull()?.let { put("package", it.one("qualifiedName").text) }
                doc.named("importDecl").takeIf { it.isNotEmpty() }?.let { imports ->
                    put(
                        "imports",
                        JsonArray(
                            imports.map {
                                text(
                                    it.one("qualifiedName").text + if (it.text.endsWith(".*")) ".*" else "",
                                )
                            },
                        ),
                    )
                }
                directive?.let {
                    put("model", model!!)
                    val ids = it.named("id").iterator()
                    if (it.nodes().any { n -> n.text == "schema" }) put("schema", ids.next().text)
                    if (it.nodes().any { n -> n.text == "locale" }) put("locale", ids.next().text)
                }
                doc.named("graphBlock").firstOrNull()?.let { put("graph", graph(it)) }
                doc.named("securityBlock").takeIf { it.isNotEmpty() }?.let { blocks ->
                    put("security", JsonArray(blocks.map { security(it) }))
                }
                doc.named("definition").takeIf { it.isNotEmpty() }?.let { definitions ->
                    put("defs", JsonArray(definitions.map { definition(it.one("objectDefinition"), true) }))
                }
            }
        recorded(doc, result)
        paths(result, "")
        return result
    }

    private fun definition(
        tree: ParseTree,
        top: Boolean = false,
    ): JsonObject {
        val body = tree.rules().last { it.name() in CanonicalGrammar.bodies }
        val result =
            obj(
                "kind" to if (top) text(tree.getChild(0).text) else null,
                "id" to text(tree.one("id").text),
                "properties" to body(body),
            )
        recorded(if (top) (tree as ParserRuleContext).parent else tree, result)
        return result
    }

    private fun graph(tree: ParseTree): JsonObject {
        val entries =
            tree.named("graphProperty").flatMap { wrapper ->
                val p = wrapper.rules().single()
                val key = p.getChild(0).text
                val v =
                    if (key ==
                        "objects"
                    ) {
                        JsonArray(p.named("id").map { text(it.text) })
                    } else {
                        value(p.rules().last())
                    }
                listOf(key to v)
            }
        return obj(
            "id" to text(tree.one("id").text),
            "properties" to canonicalMap(entries, listOf("model", "description", "tags", "objects", "layout")),
        )
    }

    private fun security(tree: ParseTree) = recorded(tree, decodeSecurity(tree))

    private fun decodeSecurity(tree: ParseTree) =
        JsonArray(
            tree.named("securityStatement").map { statement ->
                val ids = statement.named("id").map { text(it.text) }
                val verb = statement.getChild(0).text
                recorded(
                    statement,
                    when (verb) {
                        "own" -> obj("verb" to text(verb), "object" to ids[0], "owner" to ids[1])
                        "classify" -> obj("verb" to text(verb), "object" to ids[0], "classification" to ids[1])
                        "grant" -> obj("verb" to text(verb), "privilege" to ids[0], "object" to ids[1], "to" to ids[2])
                        "mask" -> obj("verb" to text(verb), "object" to ids[0])
                        else -> fail(statement, "Unhandled security verb $verb")
                    },
                )
            },
        )

    private fun body(tree: ParseTree): JsonElement = recorded(tree, decodeBody(tree))

    private fun decodeBody(tree: ParseTree): JsonElement {
        val name = tree.name()
        val meta = CanonicalGrammar.body(name)
        val properties = CanonicalGrammar.properties(name).groupBy { it.string("rule") }
        val free = meta.getValue("freeForm").jsonArray.associate { it.jsonObject.string("rule") to it.jsonObject }
        val entries = mutableListOf<Pair<String, JsonElement>>()
        val entryTrees = mutableListOf<Pair<String, ParseTree>>()
        val members = mutableListOf<JsonElement>()
        val fields = mutableListOf<Pair<String, JsonElement>>()

        fun visit(node: ParseTree) {
            val rule = node.name()
            when {
                node !== tree && rule in CanonicalGrammar.bodies -> fail(node, "Unhandled nested body $rule")
                rule in properties -> {
                    val key = node.getChild(0).text
                    val p = properties.getValue(rule).first { it.string("key") == key }
                    entries += key to property(node, p)
                    entryTrees += key to node
                    if (rule ==
                        "searchableProperty"
                    ) {
                        node.named("matchMethodAttr").firstOrNull()?.let {
                            entries +=
                                "method" to value(it.one("matchMethodValue"))
                        }
                    }
                }
                rule in free -> {
                    val f = free.getValue(rule)
                    val key = node.one(f.string("key")).text
                    if (name == "storageDef" &&
                        key == "schemas"
                    ) {
                        fail(node, "schemas is reserved for nested storage schemas")
                    }
                    val rawValue = node.one(f.string("value"))

                    fun terminals(t: ParseTree): List<TerminalNode> =
                        if (t is TerminalNode) listOf(t) else t.nodes().flatMap(::terminals)
                    val literal =
                        terminals(rawValue).singleOrNull()?.takeIf {
                            it.symbol.type in
                                setOf(
                                    TTRLexer.STRING_LITERAL,
                                    TTRLexer.TRIPLE_STRING_LITERAL,
                                    TTRLexer.TAGGED_BLOCK_LITERAL,
                                )
                        }
                    entries +=
                        key to
                        if (name == "cnBindingBody" &&
                            key == "filter" &&
                            literal != null
                        ) {
                            recorded(rawValue, embedded(literal))
                        } else {
                            value(rawValue).let { v ->
                                if (name == "cnBindingBody" && key == "filter" && v is JsonObject && "block" in v) {
                                    obj("object" to v)
                                } else {
                                    v
                                }
                            }
                        }
                    entryTrees += key to node
                }
                rule == "worldSchemaField" -> {
                    val key = node.one("id").text
                    if (fields.any { it.first == key }) fail(node.one("id"), "Duplicate storage field $key")
                    fields.add(key to value(node.one("dataType")))
                    entryTrees += key to node
                }
                node.nodes().firstOrNull()?.text == "def" -> {
                    val memberBody = node.rules().last { it.name() in CanonicalGrammar.bodies }
                    val kind = node.getChild(1).text
                    val id = node.named("id").firstOrNull()?.text
                    members +=
                        if (name == "storageDef") {
                            obj("id" to id?.let(::text), "fields" to body(memberBody))
                        } else {
                            obj("kind" to text(kind), "id" to id?.let(::text), "properties" to body(memberBody))
                        }
                }
                else -> node.rules().forEach(::visit)
            }
        }
        tree.rules().forEach(::visit)
        if (meta["fields"] != null) {
            return JsonObject(fields.toMap()).also { result ->
                propertyOrigins[result] = entryTrees.map { (key, node) -> "/${pointerKey(key)}" to node }
            }
        }
        if (members.isNotEmpty()) entries += (if (name == "storageDef") "schemas" else "members") to JsonArray(members)
        return try {
            CanonicalGrammar.serialize(name, entries, model).also { result ->
                propertyOrigins[result] =
                    entryTrees.mapIndexed { i, (key, tree) ->
                        (if (result is JsonArray) "/$i/${pointerKey(key)}" else "/${pointerKey(key)}") to tree
                    }
            }
        } catch (e: IllegalArgumentException) {
            fail(
                tree,
                e.message ?: "Repeated property",
            )
        }
    }

    private fun property(
        tree: ParseTree,
        property: JsonObject,
    ): JsonElement =
        when (property.string("rule")) {
            "searchableProperty" ->
                tree
                    .nodes()
                    .filterIsInstance<TerminalNode>()
                    .firstOrNull {
                        it.symbol.type ==
                            TTRLexer.BOOLEAN_LITERAL
                    }?.let { JsonPrimitive(it.text == "true") }
                    ?: JsonPrimitive(true)
            "changeSemanticsProperty" ->
                obj(
                    "mode" to text(tree.one("id").text),
                    "roles" to (tree.named("changeRoleMap").firstOrNull()?.let(::value) ?: JsonObject(emptyMap())),
                )
            "transitionProperty" -> {
                val target = tree.one("transitionTarget")
                obj(
                    "attribute" to text(tree.one("id").text),
                    (if (target.text.startsWith("{")) "toColumn" else "to") to text(target.one("id").text),
                    "from" to
                        tree.named("transitionGuard").firstOrNull()?.let { guard ->
                            guard.named("listOfIds").firstOrNull()?.let(::value)
                                ?: JsonArray(listOf(text(guard.one("id").text)))
                        },
                )
            }
            "setProperty" ->
                obj(
                    "attribute" to text(tree.named("id")[0].text),
                    "column" to text(tree.named("id")[1].text),
                )
            "transitionsProperty" ->
                JsonObject(
                    tree.named("transitionEntry").associate {
                        it.one("id").text to
                            value(it.one("listOfIds"))
                    },
                )
            "stepsProperty" -> JsonArray(tree.named("stepInline").map { definition(it) })
            else -> {
                val children = tree.nodes().filter { it.name() != "propSep" }.drop(1)
                if (children.size == 1) {
                    value(children.single())
                } else {
                    val symbols =
                        CanonicalGrammar
                            .symbols(property.getValue("value"))
                            .filter {
                                it !in
                                    setOf("LBRACK", "RBRACK", "COMMA")
                            }.distinct()
                    require(symbols.size == 1) { "Unhandled property ${property.string("rule")}" }
                    JsonArray(tree.named(symbols.single()).map(::value))
                }
            }
        }

    private val maps =
        mapOf(
            "localizedString" to "localizedEntry",
            "localizedStringList" to "localizedStringListEntry",
            "valueLabelsBody" to "valueLabelEntry",
            "valueLabelValue" to "valueLabelField",
            "drillArgsMap" to "drillArgEntry",
            "bindingColumnMap" to "bindingColumnEntry",
            "restrictBlock" to "restrictClause",
            "membersBlock" to "memberEntry",
            "changeRoleMap" to "changeRoleEntry",
        )

    private fun embedded(token: TerminalNode): JsonElement {
        val raw = token.text
        val result =
            when (token.symbol.type) {
                TTRLexer.STRING_LITERAL -> obj("block" to text(TtrStrings.decode(raw)), "form" to text("string"))
                TTRLexer.TRIPLE_STRING_LITERAL -> obj("block" to value(token))
                else -> {
                    val inner = raw.drop(3).dropLast(3)
                    val tag = inner.substringBefore('\n').trimEnd('\r', ' ', '\t')
                    val sourceBlock =
                        generateSequence(token.parent) { it.parent }.any {
                            it.name() in setOf("sourceTextProperty", "definitionSqlProperty")
                        }
                    if (!sourceBlock && resolveExpressionTag(tag) == null) {
                        obj("block" to text(Dedent.applyTextwrapDedent(inner)))
                    } else {
                        val block =
                            Dedent
                                .applyTextwrapDedent(
                                    inner.substringAfter('\n'),
                                ).replace(Regex("\\r?\\n$"), "")
                        obj("block" to text(block), "tag" to text(tag))
                    }
                }
            }
        embeddedNodes += result
        return result
    }

    private fun value(tree: ParseTree): JsonElement {
        val result = decodeValue(tree)
        if (tree.name() in setOf("value", "cnValue", "autoValue")) genericNodes += result
        return recorded(tree, result)
    }

    private fun decodeValue(tree: ParseTree): JsonElement {
        val name = tree.name()
        if (name in CanonicalGrammar.bodies) return body(tree)
        if (name in
            setOf("id", "qualifiedName", "key", "cnKey", "autoKey", "typeValue", "modelCode")
        ) {
            return text(tree.text)
        }
        if (tree is TerminalNode) {
            return when (tree.symbol.type) {
                TTRLexer.STRING_LITERAL -> text(TtrStrings.decode(tree.text))
                TTRLexer.TRIPLE_STRING_LITERAL, TTRLexer.TAGGED_BLOCK_LITERAL ->
                    text(
                        Dedent.applyTextwrapDedent(tree.text.drop(3).dropLast(3)),
                    )
                TTRLexer.NUMBER_LITERAL ->
                    number(tree.text) { fail(tree, it) }.also {
                        numbers[it] =
                            tree.text
                    }
                TTRLexer.BOOLEAN_LITERAL -> JsonPrimitive(tree.text == "true")
                TTRLexer.NULL_LITERAL -> JsonNull
                else -> text(tree.text)
            }
        }
        if (name in maps) {
            val seen = mutableSetOf<String>()
            val entries =
                tree.named(maps.getValue(name)).map { entry ->
                    val children = entry.rules().filter { it.name() != "propSep" }
                    val key = value(children.first()).jsonPrimitive.content
                    if (name == "membersBlock" &&
                        !seen.add(key)
                    ) {
                        fail(children.first(), "Duplicate restrict member $key")
                    }
                    key to value(children.last())
                }
            if (name == "membersBlock" &&
                (entries.any { it.first == "range" } || entries.map { it.first }.toSet() == setOf("object"))
            ) {
                fail(tree, "Reserved restrict members key: range or object-only block")
            }
            return (if (name == "membersBlock") JsonObject(entries.toMap()) else canonicalMap(entries)).also { result ->
                propertyOrigins[result] =
                    tree.named(maps.getValue(name)).mapIndexed { i, node ->
                        "/${pointerKey(entries[i].first)}" to node
                    }
            }
        }
        return when (name) {
            "value", "cnValue", "autoValue" -> {
                val child = tree.nodes().single()
                when {
                    child.name() == "id" -> obj("ref" to text(child.text))
                    child.name() == "literal" && child.text.startsWith("\"\"\"") -> obj("triple" to value(child))
                    child.text == "*" -> obj("star" to JsonPrimitive(true))
                    else -> value(child)
                }
            }
            "object_", "cnObject" -> {
                val entryRule = if (name == "object_") "propertyEntry" else "cnObjectEntry"

                fun collect(t: ParseTree): List<ParseTree> =
                    if (t.name() ==
                        entryRule
                    ) {
                        listOf(t)
                    } else {
                        t.rules().flatMap(::collect)
                    }
                val map =
                    canonicalMap(
                        collect(tree).map { e ->
                            val children = e.nodes().filter { it.name() != "propSep" && it.text !in listOf(":", "=") }
                            children.first().text to value(children.last())
                        },
                    )
                if (map.keys.any { it in setOf("ref", "triple", "call", "object") }) obj("object" to map) else map
            }
            "autoObject" ->
                obj(
                    "object" to
                        JsonArray(
                            tree.named("autoEntry").map { obj(it.one("autoKey").text to value(it.one("autoValue"))) },
                        ),
                )
            "functionCall" ->
                obj(
                    "call" to text(tree.one("id").text),
                    "args" to JsonArray(tree.named("value").map(::value)),
                )
            "embeddedBlock" -> embedded(tree.nodes().single() as TerminalNode)
            "dataType" ->
                if (tree.named("typeValue").isNotEmpty()) {
                    value(tree.one("typeValue"))
                } else {
                    JsonObject(
                        tree.named("dataTypeProperty").associate { p ->
                            p.getChild(0).text to
                                value(p.nodes().last())
                        },
                    )
                }
            "durationLiteral" -> obj("amount" to value(tree.nodes().first()), "unit" to text(tree.one("id").text))
            "taggedValue" -> obj("tag" to text(tree.one("id").text), "value" to value(tree.one("stringLiteralForm")))
            "callValue" ->
                obj(
                    "call" to text(tree.one("id").text),
                    "entries" to value(tree.one("autoObject")).jsonObject.getValue("object"),
                )
            "participant" ->
                obj(
                    "ref" to text(tree.named("id")[0].text),
                    "as" to
                        tree
                            .named("id")
                            .getOrNull(1)
                            ?.text
                            ?.let(::text),
                )
            "rangeLiteral" ->
                obj(
                    "range" to obj("from" to value(tree.nodes().first()), "to" to value(tree.nodes().last())),
                )
            "calcRef" ->
                obj(
                    "name" to text(tree.one("id").text),
                    "args" to
                        JsonObject(tree.named("calcArg").associate { it.one("id").text to text(it.one("value").text) }),
                )
            "levelEntry" ->
                obj(
                    "attribute" to text(tree.named("id")[0].text),
                    "via" to
                        tree
                            .named("id")
                            .getOrNull(1)
                            ?.text
                            ?.let(::text),
                )
            "matchMethodValue" ->
                obj(
                    "name" to text(tree.one("id").text),
                    "argument" to
                        tree
                            .nodes()
                            .filterIsInstance<TerminalNode>()
                            .firstOrNull {
                                it.symbol.type ==
                                    TTRLexer.NUMBER_LITERAL
                            }?.let(::value),
                )
            "restrictValue" -> {
                val child = tree.rules().single()
                val result = value(child)
                if (child.name() == "value" && child.rules().single().name() == "object_") {
                    if (result is JsonObject && result.keys == setOf("object")) result else obj("object" to result)
                } else {
                    result
                }
            }
            "columnDefList", "attributeDefList", "indexDefList", "constraintDefList" ->
                JsonArray(
                    tree.rules().map {
                        definition(it)
                    },
                )
            "measureInlineList" -> {
                val nodes = tree.rules()
                JsonArray(
                    nodes.chunked(2).map { (id, body) ->
                        obj(
                            "id" to text(id.text),
                            "properties" to this.body(body),
                        )
                    },
                )
            }
            "parameterDefList" -> JsonArray(tree.named("parameterInline").map(::body))
            "bindingColumnValue" ->
                if (tree.nodes().first().text == "{") {
                    tree.named("bindingTargetValue").firstOrNull()?.let { obj("target" to value(it)) }
                        ?: value(tree.one("object_"))
                } else {
                    value(tree.one("id"))
                }
            else -> {
                val children = tree.nodes().filter { it.text !in setOf("[", "]", ",") }
                if (tree.nodes().firstOrNull()?.text == "[") {
                    JsonArray(children.map(::value))
                } else if (children.size == 1) {
                    value(children.single())
                } else {
                    fail(tree, "Unhandled canonical value rule $name")
                }
            }
        }
    }
}
