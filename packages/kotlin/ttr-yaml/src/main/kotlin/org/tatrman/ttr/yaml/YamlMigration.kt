// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.*
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.schema.CoreSchema
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.system.exitProcess

/** Syntax migrations only. A version pair must be registered here; no best-effort dialect detection. */
object YamlMigration {
    private val version = CanonicalGrammar.version
    private val transforms: Map<Pair<String, String>, (String) -> CanonicalDocument> =
        mapOf(
            (version to version) to { source -> CanonicalYaml.read(source) },
            // Grammar 0.22 removed constructs canonical YAML never carried (D-142): the shape is unchanged, so a 0.21
            // document migrates by its `ttr:` marker alone, then normalizes as the identity transform does.
            ("0.21" to version) to { source -> CanonicalYaml.read(remark(source, "0.21", version)) },
        )

    /**
     * [source] with its top-level `ttr:` marker — and nothing else — rewritten from [from] to [to]. A marker other
     * than [from] is refused at the marker; a document without one is returned as it is, for the reader to refuse
     * with its own location.
     */
    private fun remark(
        source: String,
        from: String,
        to: String,
    ): String {
        val root =
            runCatching {
                Compose(LoadSettings.builder().setSchema(CoreSchema()).build()).composeString(source).orElse(null)
            }.getOrNull() as? MappingNode ?: return source
        val marker =
            root.value
                .firstOrNull { (it.keyNode as? ScalarNode)?.value == "ttr" }
                ?.valueNode as? ScalarNode ?: return source
        val start = marker.startMark.orElse(null) ?: return source
        val end = marker.endMark.orElse(null) ?: return source
        if (marker.value != from) {
            throw CanonicalYamlException(
                "<yaml>",
                start.line + 1,
                start.column + 1,
                "Expected ttr: \"$from\" for a migration from $from",
            )
        }
        if (start.line != end.line) return source
        val lines = source.split("\n")
        val line = lines[start.line]
        val from16 = line.offsetByCodePoints(0, start.column)
        val to16 = line.offsetByCodePoints(0, end.column)
        val rewritten = line.substring(0, from16) + "\"" + to + "\"" + line.substring(to16)
        return (lines.take(start.line) + rewritten + lines.drop(start.line + 1)).joinToString("\n")
    }

    fun migrate(request: JsonObject): JsonObject {
        fun invalid(message: String): Nothing = throw CanonicalYamlException("<yaml>", 1, 1, message)
        if (request.keys != setOf("operation", "from", "to", "source")) {
            invalid("Expected exactly operation, from, to and source")
        }

        fun field(key: String): String =
            (request[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: invalid("Expected string $key")
        if (field("operation") != "migrate") invalid("Unsupported operation")
        val from = field("from")
        val to = field("to")
        val transform = transforms[from to to] ?: invalid("No registered canonical YAML migration from $from to $to")
        val source = field("source")
        val document = transform(source)
        // A migration must never write a partial typed parse. Semantic resolution is a separate caller concern.
        document.ast.errors.firstOrNull()?.let {
            throw CanonicalYamlException("<yaml>", it.line, it.column, it.message)
        }
        val output = CanonicalYaml.write(document)
        return obj("source" to text(output), "changed" to JsonPrimitive(output != source))
    }
}

/** One UTF-8 JSON request/response. Stdout contains protocol bytes only; failures are located on stderr. */
object YamlMigrationCli {
    private const val MAX_REQUEST_BYTES = 64 * 1024 * 1024

    fun run(
        input: InputStream,
        output: OutputStream,
        diagnostics: OutputStream,
    ): Int =
        try {
            val bytes = input.readNBytes(MAX_REQUEST_BYTES + 1)
            require(bytes.size <= MAX_REQUEST_BYTES) { "Migration request exceeds 64 MiB" }
            val source =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            mapper.factory.createParser(source).use { parser ->
                require(mapper.readTree<com.fasterxml.jackson.databind.JsonNode>(parser) != null) { "Empty request" }
                require(parser.nextToken() == null) { "Expected one JSON request" }
            }
            val request = Json.parseToJsonElement(source) as? JsonObject ?: error("Expected JSON request object")
            val result = YamlMigration.migrate(request)
            output.write((result.toString() + "\n").toByteArray(Charsets.UTF_8))
            0
        } catch (e: Exception) {
            val message = if (e is CanonicalYamlException) e.message else "<request>:1:1: ${e.message}"
            diagnostics.write((message + "\n").toByteArray(Charsets.UTF_8))
            2
        }

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(System.`in`, System.out, System.err))
    }
}
