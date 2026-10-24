// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.doc

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.tatrman.ttrp.ast.DecisionDecl
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.RoleContainer
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.parser.TtrpParser
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** ANTLR token offsets count Unicode code points; Markdown/LSP spans count UTF-16 code units. */
fun utf16Offset(
    source: String,
    parserOffset: Int,
): Int = source.offsetByCodePoints(0, parserOffset.coerceIn(0, source.codePointCount(0, source.length)))

@Serializable data class DocDiagnostic(
    val id: String,
    val message: String,
    val span: List<Int>,
)

@Serializable data class DocNode(
    val block: Int,
    val span: List<Int>,
    val paragraph: List<Int>,
    val generatedSpan: List<Int>,
)

@Serializable data class DocSentence(
    val span: List<Int>,
    val nodes: List<String>,
)

@Serializable data class DocSidecar(
    val document: String,
    val documentSha: String,
    val nodes: Map<String, DocNode>,
    val sentences: List<DocSentence>,
) {
    fun toJson(): String = canonicalJson(Json.encodeToJsonElement(serializer(), this))
}

/** Sorted object keys, stable UTF-8 text, one final newline; arrays retain semantic/document order. */
fun canonicalJson(value: JsonElement): String {
    fun sorted(e: JsonElement): JsonElement =
        when (e) {
            is JsonObject -> JsonObject(e.toSortedMap().mapValues { sorted(it.value) })
            is JsonArray -> JsonArray(e.map(::sorted))
            else -> e
        }
    return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), sorted(value)) + "\n"
}

data class ExtractedRecipe(
    val name: String?,
    val path: String,
    val text: String,
    val sidecar: DocSidecar,
    val skeletonSpan: List<Int>,
)

data class DocExtraction(
    val title: String,
    val model: DocModel,
    val recipes: List<ExtractedRecipe>,
    val diagnostics: List<DocDiagnostic>,
) {
    fun write(dir: Path) {
        require(diagnostics.isEmpty()) { diagnostics.joinToString { "${it.id}: ${it.message}" } }
        recipes.forEach { recipe ->
            val path = dir.resolve(recipe.path)
            Files.createDirectories(path.parent)
            Files.writeString(path, recipe.text)
            Files.writeString(
                path.resolveSibling(path.fileName.toString().removeSuffix(".ttrp") + ".ttrd"),
                recipe.sidecar.toJson(),
            )
        }
    }

    fun check(dir: Path): List<DocDiagnostic> =
        recipes.mapNotNull { recipe ->
            val path = dir.resolve(recipe.path)
            val side = path.resolveSibling(path.fileName.toString().removeSuffix(".ttrp") + ".ttrd")
            val equal =
                runCatching {
                    val committed = Json.parseToJsonElement(Files.readString(side)) as JsonObject
                    val derived = Json.parseToJsonElement(recipe.sidecar.toJson()) as JsonObject
                    Files.readString(path) == recipe.text &&
                        committed == derived
                }.getOrDefault(false)
            if (equal) {
                null
            } else {
                DocDiagnostic(
                    "TTRP-DOC-003",
                    "derived artifacts drift for ${recipe.path}; run ttrp build",
                    listOf(0, 0),
                )
            }
        }
}

/** The literate file owns the source; both canonical programs and provenance are derived in memory first. */
object DocExtractor {
    private val tags = setOf("ttrp", "sql", "ttrb", "ttrb-cs", "python")
    private val nameHeader = Regex("^\\s*recipe:\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\n")

    private data class Member(
        val text: String,
        val name: String?,
        val block: DocBlock,
    )

    private data class Skeleton(
        val name: String?,
        val header: String,
        val members: MutableList<Member>,
        val span: List<Int>,
    )

    fun extract(
        source: String,
        document: String,
    ): DocExtraction {
        val model = DocModel.read(source)
        val diagnostics = mutableListOf<DocDiagnostic>()
        val skeletons = mutableListOf<Skeleton>()
        var preamble: String? = null

        fun refuse(
            id: Int,
            block: DocBlock,
            message: String,
        ) {
            diagnostics +=
                DocDiagnostic("TTRP-DOC-00$id", message, block.span)
        }

        fun parsedRecipe(text: String): RecipeDecl? {
            val result = TtrpParser.parseString(text, document)
            if (result.diagnostics.any { it.severity == Severity.ERROR }) return null
            return result.document.statements
                .filterIsInstance<RecipeDecl>()
                .singleOrNull()
        }

        fun member(
            text: String,
            block: DocBlock,
        ): List<Member>? {
            val prefix = "recipe {\n"
            val recipe = parsedRecipe(prefix + text + "\n}") ?: return null
            if (recipe.members.isEmpty()) return null
            return recipe.members.map { m ->
                val n =
                    when (m) {
                        is RoleContainer -> m.name
                        is DecisionDecl -> m.name
                        else -> null
                    }
                Member(
                    text.substring(
                        utf16Offset(prefix + text + "\n}", m.location.offsetStart) - prefix.length,
                        utf16Offset(prefix + text + "\n}", m.location.offsetEnd) - prefix.length,
                    ),
                    n,
                    block,
                )
            }
        }
        for (block in model.blocks) {
            if (block.tag !in tags) {
                refuse(4, block, "unsupported fence tag '${block.tag}'")
                continue
            }
            if (block.tag == "ttrp" && block.info == "preamble") {
                if (preamble == null) preamble = block.body.trimEnd()
                continue
            }
            val named = if (block.tag == "ttrp" && block.info.isEmpty()) nameHeader.find(block.body) else null
            val body = if (named != null) block.body.removeRange(named.range) else block.body
            val recipe = if (block.tag == "ttrp" && block.info.isEmpty()) parsedRecipe(body) else null
            if (recipe != null) {
                val name = named?.groupValues?.get(1) ?: recipe.name
                if (name != null &&
                    skeletons.any { it.name == name }
                ) {
                    refuse(2, block, "duplicate skeleton '$name'")
                    continue
                }
                if (preamble == null &&
                    skeletons.isEmpty()
                ) {
                    preamble = body.substring(0, utf16Offset(body, recipe.location.offsetStart)).trimEnd()
                }
                val start = body.indexOf('{', utf16Offset(body, recipe.location.offsetStart)) + 1
                val end =
                    recipe.members
                        .firstOrNull()
                        ?.location
                        ?.offsetStart
                        ?.let { utf16Offset(body, it) }
                        ?: (utf16Offset(body, recipe.location.offsetEnd) - 1)
                val header =
                    body
                        .substring(
                            start,
                            end,
                        ).replace(Regex("(?m)^\\s*recipe:\\s*[A-Za-z_][A-Za-z0-9_]*\\s*$"), "")
                        .trim()
                val members =
                    recipe.members
                        .map { m ->
                            Member(
                                body.substring(
                                    utf16Offset(body, m.location.offsetStart),
                                    utf16Offset(body, m.location.offsetEnd),
                                ),
                                when (m) {
                                    is RoleContainer -> m.name
                                    is DecisionDecl -> m.name
                                    else -> null
                                },
                                block,
                            )
                        }.toMutableList()
                skeletons += Skeleton(name, header, members, block.span)
                continue
            }
            if (skeletons.isEmpty()) {
                refuse(1, block, "a container must follow its recipe skeleton")
                continue
            }
            val text =
                if (block.tag == "ttrp" && block.info.isEmpty()) {
                    body
                } else {
                    val header: String
                    val interior: String
                    if (block.info.isNotBlank()) {
                        header = block.info
                        interior = body
                    } else {
                        header = body.lineSequence().firstOrNull().orEmpty()
                        interior = body.substringAfter('\n', "")
                    }
                    // The parser validates the real role/decision header; no independent header grammar is maintained.
                    val probeBody =
                        if (header.trimStart().startsWith(
                                "decision ",
                            )
                        ) {
                            "{ _doc -> switch { _doc: true } }"
                        } else {
                            "{ }"
                        }
                    if (parsedRecipe("recipe {\n$header $probeBody\n}") ==
                        null
                    ) {
                        refuse(1, block, "missing or invalid container header")
                        continue
                    }
                    if (block.tag == "ttrp") {
                        "$header {\n$interior}\n"
                    } else {
                        "$header \"\"\"${block.tag}\n$interior\"\"\"\n"
                    }
                }
            val extracted = member(text, block)
            if (extracted == null) {
                refuse(1, block, "block has no valid container or skeleton header")
            } else {
                skeletons.last().members += extracted
            }
        }
        if (skeletons.size > 1 &&
            skeletons.any { it.name == null }
        ) {
            diagnostics +=
                DocDiagnostic(
                    "TTRP-DOC-002",
                    "every skeleton in a multi-recipe document needs recipe: <name>",
                    listOf(0, 0),
                )
        }
        val sha =
            MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
        val recipes =
            skeletons.map { skeleton ->
                val nodes = linkedMapOf<String, DocNode>()
                val text =
                    buildString {
                        preamble?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
                        append("recipe {\n").append(skeleton.header).append("\n\n")
                        skeleton.members.forEachIndexed { index, m ->
                            if (index > 0) append("\n\n")
                            val from = length
                            append(m.text)
                            m.name?.let { name ->
                                val paragraph =
                                    model.paragraphs.lastOrNull { it.span[1] <= m.block.span[0] }?.span ?: listOf(0, 0)
                                if (name in
                                    nodes
                                ) {
                                    diagnostics +=
                                        DocDiagnostic("TTRP-DOC-002", "duplicate container '$name'", m.block.span)
                                }
                                nodes[name] = DocNode(m.block.index, m.block.span, paragraph, listOf(from, length))
                            }
                        }
                        append("\n}\n")
                    }
                val sentences =
                    model.paragraphs.map { p ->
                        DocSentence(p.span, nodes.filterValues { it.paragraph == p.span }.keys.toList())
                    }
                val name = skeleton.name
                val path =
                    name?.let { "$it/$it.ttrp" } ?: document.substringAfterLast('/').removeSuffix(".md") + ".ttrp"
                ExtractedRecipe(
                    name,
                    path,
                    text,
                    DocSidecar(document, sha, nodes.toSortedMap(), sentences),
                    skeleton.span,
                )
            }
        return DocExtraction(model.title, model, recipes, diagnostics)
    }
}
