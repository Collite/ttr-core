// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.TtrStrings
import org.tatrman.ttr.parser.walker.Dedent
import org.antlr.v4.runtime.*
import org.tatrman.ttr.parser.generated.TTRLexer
import org.tatrman.ttr.parser.generated.TTRParser

/** Explicit structural adapters plus mechanically derived scalar/list/union productions. */
internal class TtrText(
    private val projection: Boolean = false,
    private val spelling: (JsonElement) -> String? = { null },
) {
    private var model: String? = null

    fun write(document: JsonObject): String =
        buildString {
            model = document["model"]?.jsonPrimitive?.content
            document["package"]?.let { appendLine("package ${it.jsonPrimitive.content}") }
            document["imports"]?.jsonArray?.forEach { appendLine("import ${it.jsonPrimitive.content}") }
            model?.let {
                append("model $it")
                document["schema"]?.let { v -> append(" schema ${v.jsonPrimitive.content}") }
                document["locale"]?.let { v -> append(" locale ${v.jsonPrimitive.content}") }
                appendLine()
            }
            document["graph"]?.jsonObject?.let { g ->
                append("graph ${g.string("id")} { ")
                append(
                    g.getValue("properties").jsonObject.entries.joinToString(", ") { (k, v) ->
                        "$k: " +
                            when (k) {
                                "objects" -> array(v) { primitive(it) }
                                "model" -> primitive(v)
                                "description" -> if (v is JsonObject) render("localizedString", v) else quoted(v)
                                "tags" -> render("listOfStrings", v)
                                "layout" -> render("object_", v)
                                else -> error("Unhandled graph property $k")
                            }
                    },
                )
                appendLine(" }")
            }
            document["defs"]?.jsonArray?.forEach { def ->
                val d = def.jsonObject
                val kind = d.string("kind")
                appendLine(
                    "def $kind ${d.string(
                        "id",
                    )} ${body(CanonicalGrammar.kinds.getValue(kind), d.getValue("properties"))}",
                )
            }
            document["security"]?.jsonArray?.forEach { block ->
                append("security { ")
                append(
                    block.jsonArray.joinToString(", ") { s ->
                        val p = s.jsonObject
                        when (val verb = p.string("verb")) {
                            "own" -> "own ${p.string("object")}: ${p.string("owner") }"
                            "classify" -> "classify ${p.string("object")}: ${p.string("classification") }"
                            "grant" -> "grant ${p.string("privilege")} on ${p.string("object")} to ${p.string("to") }"
                            "mask" -> "mask ${p.string("object") }"
                            else -> error("Unhandled security verb $verb")
                        }
                    },
                )
                appendLine(" }")
            }
        }

    private fun primitive(value: JsonElement): String {
        require(value is JsonPrimitive && value != JsonNull) { "Expected a scalar" }
        return value.content
    }

    private fun quoted(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString) { "Expected a string" }
        return TtrStrings.encode(value.content)
    }

    private fun array(
        value: JsonElement,
        encode: (JsonElement) -> String,
    ) = value.jsonArray.joinToString(", ", "[", "]", transform = encode)

    private fun map(
        value: JsonElement,
        quotedKey: Boolean = false,
        encode: (JsonElement) -> String,
    ) = value.jsonObject.entries.joinToString(", ", "{ ", " }") { (k, v) ->
        "${if (quotedKey) TtrStrings.encode(k) else k}: ${encode(v)}"
    }

    private fun body(
        name: String,
        value: JsonElement,
    ): String {
        val meta = CanonicalGrammar.body(name)
        if (meta["fields"] != null) return map(value) { render("dataType", it) }
        val props = CanonicalGrammar.properties(name).associateBy { it.string("key") }
        val free =
            meta
                .getValue("freeForm")
                .jsonArray
                .firstOrNull()
                ?.jsonObject
                ?.string("value")
        val values = CanonicalGrammar.entries(value)
        val members = meta.getValue("members").jsonArray.map { it.jsonObject }
        return values.filter { it.first != "method" || "searchable" !in props }.joinToString(
            ", ",
            "{ ",
            " }",
        ) { (key, v) ->
            if (members.isNotEmpty() && key == if (name == "storageDef") "schemas" else "members") {
                v.jsonArray.joinToString(", ") { member ->
                    val m = member.jsonObject
                    val kind = if (name == "storageDef") "schema" else m.string("kind")
                    val shape = members.single { it.string("kind") == kind }
                    "def $kind ${m["id"]?.jsonPrimitive?.content ?: ""} ${body(
                        shape.string("body"),
                        m.getValue(if (name == "storageDef") "fields" else "properties"),
                    )}"
                }
            } else if (name == "cnBindingBody" && key == "filter" && v is JsonObject && "block" in v) {
                "$key: ${render("embeddedBlock", v)}"
            } else {
                val p = props[key]
                val typed =
                    p?.let {
                        try {
                            property(it, v, values)
                        } catch (
                            e: CanonicalValueException,
                        ) {
                            throw e
                        } catch (_: RuntimeException) {
                            null
                        }
                    }
                if (typed != null) {
                    "$key: $typed"
                } else {
                    require(free != null) { "No canonical handler for $name.$key" }
                    "$key: ${render(free, v)}"
                }
            }
        }
    }

    private fun property(
        p: JsonObject,
        value: JsonElement,
        entries: List<Pair<String, JsonElement>>,
    ): String =
        when (p.string("rule")) {
            "searchableProperty" ->
                render("BOOLEAN_LITERAL", value) +
                    (
                        entries.lastOrNull { it.first == "method" }?.let {
                            " method: ${render(
                                "matchMethodValue",
                                it.second,
                            )}"
                        }
                            ?: ""
                    )
            "changeSemanticsProperty" ->
                value.jsonObject.let {
                    "${it.string("mode")} ${map(it.getValue("roles")) { v -> primitive(v) }}"
                }
            "transitionProperty" ->
                value.jsonObject.let {
                    val target = it["to"]?.let(::primitive) ?: "{${it.string("toColumn")}}"
                    "${it.string("attribute")} -> $target" +
                        (it["from"]?.let { f -> " { from: ${render("listOfIds", f)} }" } ?: "")
                }
            "setProperty" -> value.jsonObject.let { "${it.string("attribute")} = ${it.string("column")}" }
            "transitionsProperty" -> map(value) { render("listOfIds", it) }
            "stepsProperty" -> array(value) { nested(it, "step", "autoBody") }
            else -> expr(p.getValue("value"), value)
        }

    private fun nested(
        value: JsonElement,
        kind: String,
        body: String,
    ) = value.jsonObject.let {
        "def $kind ${it.string("id")} ${this.body(body, it.getValue("properties"))}"
    }

    private val maps =
        mapOf(
            "localizedString" to "stringLiteralForm",
            "localizedStringList" to "listOfStrings",
            "valueLabelsBody" to "valueLabelValue",
            "drillArgsMap" to "stringLiteralForm",
            "bindingColumnMap" to "bindingColumnValue",
            "restrictBlock" to "restrictValue",
            "membersBlock" to "localizedString",
            "changeRoleMap" to "id",
        )

    private fun render(
        name: String,
        value: JsonElement,
    ): String {
        if (name in CanonicalGrammar.bodies) return body(name, value)
        if (name in setOf("id", "qualifiedName", "key", "cnKey", "autoKey")) {
            require(value is JsonPrimitive && value.isString)
            require(
                Regex(
                    CanonicalGrammar.metadata.getValue("identifiers").jsonObject.string(
                        if (name in
                            setOf("cnKey", "autoKey")
                        ) {
                            name
                        } else {
                            "id"
                        },
                    ),
                ).matches(value.content),
            ) { "Invalid identifier" }
            return value.content
        }
        when (name) {
            "STRING_LITERAL", "TRIPLE_STRING_LITERAL", "TAGGED_BLOCK_LITERAL", "stringLiteralForm" -> return quoted(
                value,
            )
            "NUMBER_LITERAL" -> {
                require(
                    value is JsonPrimitive && !value.isString && value.content.toBigDecimalOrNull() != null,
                )
                return spelling(value) ?: value.content.toBigDecimal().toPlainString()
            }
            "BOOLEAN_LITERAL" -> {
                require(value is JsonPrimitive && value.booleanOrNull != null)
                return value.content
            }
            "NULL_LITERAL" -> {
                require(value == JsonNull)
                return "null"
            }
        }
        CanonicalGrammar.literals[name]?.let { allowed ->
            val v = primitive(value)
            require(v in allowed) { "Not a $name literal" }
            return v
        }
        maps[name]?.let { child ->
            return map(value, name in setOf("valueLabelsBody", "membersBlock")) { render(child, it) }
        }
        return when (name) {
            "value", "cnValue", "autoValue" -> generic(value, name)
            "object_", "cnObject" -> {
                val v =
                    value.jsonObject.let {
                        if (it.keys ==
                            setOf("object")
                        ) {
                            it.getValue("object").jsonObject
                        } else {
                            it
                        }
                    }
                map(v) { generic(it, if (name == "cnObject") "cnValue" else "value") }
            }
            "autoObject" ->
                value.jsonObject
                    .getValue("object")
                    .jsonArray
                    .flatMap {
                        it.jsonObject.entries
                    }.joinToString(", ", "{ ", " }") { "${it.key}: ${generic(it.value, "autoValue")}" }
            "functionCall" ->
                value.jsonObject.let {
                    "${it.string(
                        "call",
                    )}${it.getValue("args").jsonArray.joinToString(", ", "(", ")") { arg -> generic(arg, "value") }}"
                }
            "embeddedBlock" ->
                value.jsonObject.let {
                    val block = it.string("block")
                    if (it["form"]?.jsonPrimitive?.content == "string") {
                        TtrStrings.encode(block)
                    } else {
                        triple(block, it["tag"]?.jsonPrimitive?.content)
                    }
                }
            "dataType" ->
                if (value is JsonPrimitive) {
                    render("typeValue", value)
                } else {
                    map(value) {
                        if (it is JsonPrimitive &&
                            it.isString
                        ) {
                            render("typeValue", it)
                        } else {
                            render("NUMBER_LITERAL", it)
                        }
                    }
                }
            "participant" ->
                value.jsonObject.let {
                    it.string("ref") +
                        (it["as"]?.let { a -> " as ${primitive(a)}" } ?: "")
                }
            "durationLiteral" ->
                value.jsonObject.let {
                    "${render(
                        "NUMBER_LITERAL",
                        it.getValue("amount"),
                    )} ${it.string("unit")}"
                }
            "taggedValue" -> value.jsonObject.let { "${it.string("tag")} ${quoted(it.getValue("value"))}" }
            "callValue" ->
                value.jsonObject.let {
                    "${it.string("call")} ${render(
                        "autoObject",
                        obj(
                            "object" to it.getValue("entries"),
                        ),
                    )}"
                }
            "rangeLiteral" ->
                value.jsonObject.getValue("range").jsonObject.let {
                    "${render("NUMBER_LITERAL", it.getValue("from"))}..${render("NUMBER_LITERAL", it.getValue("to"))}"
                }
            "restrictValue" ->
                if (value is JsonObject) {
                    when {
                        value.keys == setOf("range") -> render("rangeLiteral", value)
                        value.keys == setOf("object") -> render("object_", value)
                        value.keys.any { it in setOf("ref", "triple", "call") } -> generic(value, "value")
                        else -> render("membersBlock", value)
                    }
                } else {
                    generic(value, "value")
                }
            "calcRef" ->
                value.jsonObject.let {
                    "${it.string(
                        "name",
                    )}${it.getValue(
                        "args",
                    ).jsonObject.entries.joinToString(", ", "(", ")") { (k, v) -> "$k: ${retainedValue(v)}" }}"
                }
            "levelEntry" ->
                value.jsonObject.let {
                    it.string("attribute") +
                        (it["via"]?.let { v -> " via ${primitive(v)}" } ?: "")
                }
            "matchMethodValue" ->
                value.jsonObject.let {
                    it.string("name") +
                        (it["argument"]?.let { a -> "(${render("NUMBER_LITERAL", a)})" } ?: "")
                }
            "parameterDefList" -> array(value) { body("parameterInline", it) }
            "columnDefList", "attributeDefList", "indexDefList", "constraintDefList", "measureInlineList" -> {
                val kind = name.removeSuffix("DefList").removeSuffix("InlineList")
                array(value) { nested(it, kind, "${kind}Def") }
            }
            "bindingColumnValue" ->
                when (value) {
                    is JsonPrimitive -> render("id", value)
                    is JsonObject ->
                        if (value.keys ==
                            setOf("target")
                        ) {
                            "{ target: ${render("bindingTargetValue", value.getValue("target"))} }"
                        } else {
                            render("object_", value)
                        }
                    else -> error("Invalid binding column")
                }
            "valueLabelValue" ->
                map(value) {
                    when (it) {
                        is JsonObject -> render("localizedString", it)
                        is JsonArray -> render("listOfStrings", it)
                        else -> quoted(it)
                    }
                }
            else -> expr(parse(CanonicalGrammar.rules.getValue(name)), value)
        }
    }

    private fun triple(
        content: String,
        tag: String? = null,
    ): String {
        val inner = if (tag == null) "\n$content" else "$tag\n$content\n"
        val body =
            if (tag ==
                null
            ) {
                Dedent.applyTextwrapDedent(inner)
            } else {
                Dedent.applyTextwrapDedent(content + "\n").removeSuffix("\n")
            }
        if ("\"\"\"" !in content && body == content) return "\"\"\"$inner\"\"\""
        require(projection) { "String content cannot be represented by a TTR triple literal" }
        return TtrStrings.encode(content)
    }

    private fun retainedValue(value: JsonElement): String {
        val source = value.jsonPrimitive.content
        var valid = true
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
                    valid =
                        false
                }
            }
        val lexer =
            TTRLexer(CharStreams.fromString(source)).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        val parser =
            TTRParser(CommonTokenStream(lexer)).apply {
                removeErrorListeners()
                addErrorListener(listener)
            }
        parser.value()
        if (!valid ||
            parser.currentToken.type != Token.EOF
        ) {
            throw CanonicalValueException(value, "Calc argument must contain exactly one retained TTR value")
        }
        return source
    }

    private fun generic(
        value: JsonElement,
        mode: String,
    ): String =
        when (value) {
            is JsonPrimitive -> if (value.isString) quoted(value) else spelling(value) ?: value.toString()
            is JsonArray -> array(value) { generic(it, mode) }
            is JsonObject ->
                when {
                    value.keys == setOf("ref") -> render("id", value.getValue("ref"))
                    value.keys == setOf("triple") -> triple(value.string("triple"))
                    mode == "autoValue" && value.keys == setOf("star") -> "*"
                    mode == "autoValue" && "entries" in value -> render("callValue", value)
                    mode == "autoValue" && "amount" in value -> render("durationLiteral", value)
                    mode == "autoValue" && "tag" in value -> render("taggedValue", value)
                    mode == "autoValue" && "object" in value -> render("autoObject", value)
                    "call" in value && "args" in value -> render("functionCall", value)
                    else -> render(if (mode == "cnValue") "cnObject" else "object_", value)
                }
        }

    private fun expr(
        expression: JsonElement,
        value: JsonElement,
    ): String {
        val e = expression.jsonObject
        e["symbol"]?.let { return render(it.jsonPrimitive.content, value) }
        e["alt"]?.let { choices ->
            for (choice in choices.jsonArray) {
                val attempt = runCatching { expr(choice, value) }
                if (attempt.isSuccess) return attempt.getOrThrow()
            }
            throw IllegalArgumentException("No canonical grammar alternative for $value")
        }
        e["node"]?.let { return expr(it, value) }
        val seq = e.getValue("seq").jsonArray
        if (seq.size == 1) return expr(seq.single(), value)
        if (seq
                .first()
                .jsonObject["symbol"]
                ?.jsonPrimitive
                ?.content == "LBRACK" &&
            seq
                .last()
                .jsonObject["symbol"]
                ?.jsonPrimitive
                ?.content == "RBRACK"
        ) {
            val names =
                CanonicalGrammar
                    .symbols(
                        expression,
                    ).filter { it !in setOf("LBRACK", "RBRACK", "COMMA") }
                    .distinct()
            require(names.size == 1) { "Unhandled list grammar" }
            return array(value) { render(names.single(), it) }
        }
        throw IllegalArgumentException("Unhandled grammar value expression $expression")
    }

    private fun parse(source: String): JsonElement = GrammarExpression.parse(source)
}
