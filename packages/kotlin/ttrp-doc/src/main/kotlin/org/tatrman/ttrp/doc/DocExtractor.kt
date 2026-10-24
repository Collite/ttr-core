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
    val suggestedAlternative: String? = null,
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

        fun forwardErrors(
            parsed: org.tatrman.ttrp.parser.TtrpParseResult,
            sourceOffset: (Int) -> Int,
        ): Boolean {
            val errors = parsed.diagnostics.filter { it.severity == Severity.ERROR }
            errors.forEach { d ->
                val start = sourceOffset(utf16Offset(parsed.source, d.location.offsetStart)).coerceIn(0, source.length)
                val end = sourceOffset(utf16Offset(parsed.source, d.location.offsetEnd)).coerceIn(start, source.length)
                diagnostics += DocDiagnostic(d.id.id, d.message, listOf(start, end), d.suggestedAlternative)
            }
            return errors.isNotEmpty()
        }

        fun member(
            text: String,
            block: DocBlock,
            sourceOffset: (Int) -> Int,
        ): List<Member>? {
            val prefix = "recipe {\n"
            val parsed = TtrpParser.parseString(prefix + text + "\n}", document)
            if (forwardErrors(
                    parsed,
                ) { sourceOffset((it - prefix.length).coerceIn(0, text.length)) }
            ) {
                return emptyList()
            }
            val recipe =
                parsed.document.statements
                    .filterIsInstance<RecipeDecl>()
                    .singleOrNull() ?: return null
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
                        utf16Offset(parsed.source, m.location.offsetStart) - prefix.length,
                        utf16Offset(parsed.source, m.location.offsetEnd) - prefix.length,
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
                if (forwardErrors(TtrpParser.parseString(block.body, document)) { block.bodySpan[0] + it }) continue
                if (preamble == null) preamble = block.body.trimEnd()
                continue
            }
            val named = if (block.tag == "ttrp" && block.info.isEmpty()) nameHeader.find(block.body) else null
            val body = if (named != null) block.body.removeRange(named.range) else block.body
            val parsed =
                if (block.tag == "ttrp" &&
                    block.info.isEmpty()
                ) {
                    TtrpParser.parseString(body, document)
                } else {
                    null
                }
            val recipe =
                parsed
                    ?.document
                    ?.statements
                    ?.filterIsInstance<RecipeDecl>()
                    ?.singleOrNull()
            val bodyStart = block.bodySpan[0] + (named?.value?.length ?: 0)
            if (recipe != null) {
                val name = named?.groupValues?.get(1) ?: recipe.name
                if (forwardErrors(requireNotNull(parsed)) { bodyStart + it.coerceIn(0, body.length) }) {
                    skeletons += Skeleton(name, "", mutableListOf(), block.span)
                    continue
                }
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
                val misplacedMember = parsedRecipe("recipe {\n$body\n}")?.members?.isNotEmpty() == true
                if (!misplacedMember &&
                    parsed != null &&
                    forwardErrors(parsed) { bodyStart + it.coerceIn(0, body.length) }
                ) {
                    continue
                }
                refuse(1, block, "a container must follow its recipe skeleton")
                continue
            }
            var bodyInText = 0
            var originalInteriorStart = block.bodySpan[0]
            var interiorLength = body.length
            var originalHeaderStart = block.bodySpan[0]
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
                    originalHeaderStart =
                        if (block.info.isNotBlank()) {
                            source
                                .indexOf(
                                    header,
                                    block.span[0],
                                ).coerceAtLeast(block.span[0])
                        } else {
                            block.bodySpan[0]
                        }
                    originalInteriorStart = block.bodySpan[0] + if (block.info.isNotBlank()) 0 else header.length + 1
                    interiorLength = interior.length
                    bodyInText =
                        if (block.tag == "ttrp") "$header {\n".length else "$header \"\"\"${block.tag}\n".length
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
            val extracted =
                member(text, block) { at ->
                    if (at >= bodyInText) {
                        originalInteriorStart + (at - bodyInText).coerceIn(0, interiorLength)
                    } else {
                        originalHeaderStart + at.coerceAtLeast(0)
                    }
                }
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
