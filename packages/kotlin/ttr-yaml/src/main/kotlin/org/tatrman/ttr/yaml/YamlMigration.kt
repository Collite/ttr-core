// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.*
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.CollectionEndEvent
import org.snakeyaml.engine.v2.events.MappingStartEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.events.SequenceStartEvent
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/** Keep a high surrogate with its low surrogate across SnakeYAML's fixed-size reads. */
private class SurrogateReader(
    private val source: String,
) : Reader() {
    private var offset = 0

    override fun read(
        buffer: CharArray,
        start: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (offset == source.length) return -1
        var count = minOf(length, source.length - offset)
        if (count > 1 && offset + count < source.length && source[offset + count - 1].isHighSurrogate()) count--
        source.toCharArray(buffer, start, offset, offset + count)
        offset += count
        return count
    }

    override fun close() = Unit
}

/** Syntax migrations only. A version pair must be registered here; no best-effort dialect detection. */
object YamlMigration {
    private val version = CanonicalGrammar.version
    private val transforms: Map<Pair<String, String>, (String) -> CanonicalDocument> =
        mapOf(
            (version to version) to { source -> CanonicalYaml.read(source) },
            // Grammar 0.22 removed constructs canonical YAML never carried (D-142): the shape is unchanged, so a 0.21
            // document migrates by its `ttr:` marker alone, then normalizes as the identity transform does.
            ("0.21" to "0.22") to { source -> CanonicalYaml.read(remark(source, "0.21", "0.22")) },
        )

    /**
     * [source] with its top-level `ttr:` marker — and nothing else — rewritten from [from] to [to]. A marker other
     * than [from] is refused at the marker; a document without one (or not YAML) is returned as it is, for the reader
     * to refuse with its own location.
     */
    private fun remark(
        source: String,
        from: String,
        to: String,
    ): String {
        val marker = marker(source) ?: return source
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
        // the marks count code points from the start of the text, whatever its line breaks
        return source.substring(0, source.offsetByCodePoints(0, start.index)) + "\"" + to + "\"" +
            source.substring(source.offsetByCodePoints(0, end.index))
    }

    /**
     * The scalar value of the first document's top-level `ttr` key, from the event stream: no node is composed, so
     * no nesting is recursed into before the reader's depth guard sees it. Null when there is none.
     */
    private fun marker(source: String): ScalarEvent? {
        var depth = 0
        var key = true
        var value = false
        try {
            for (event in Parse(LoadSettings.builder().build()).parseReader(SurrogateReader(source))) {
                val start = event is MappingStartEvent || event is SequenceStartEvent
                val node = start || event is ScalarEvent || event is AliasEvent
                if (depth == 0 && node && event !is MappingStartEvent) return null
                if (depth == 1 && node && value) return event as? ScalarEvent
                when {
                    start -> depth++
                    event is CollectionEndEvent -> depth--
                }
                if (depth == 0 && event is CollectionEndEvent) return null
                // a node at the top level is complete: a key's value follows it
                if (depth == 1 && (event is ScalarEvent || event is AliasEvent || event is CollectionEndEvent)) {
                    value = key && (event as? ScalarEvent)?.value == "ttr"
                    key = !key
                }
            }
        } catch (_: YamlEngineException) {
            return null
        }
        return null
    }

    fun migrate(request: JsonObject): JsonObject {
        fun invalid(message: String): Nothing = throw CanonicalYamlException("<request>", 1, 1, message)
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
                strictUtf8Decoder()
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
            diagnostics.write((message.toString().lineSequence().joinToString(" ") + "\n").toByteArray(Charsets.UTF_8))
            2
        } catch (e: StackOverflowError) {
            diagnostics.write("<yaml>:1:1: nesting too deep for the canonical YAML codec\n".toByteArray())
            2
        } catch (e: Throwable) {
            val message = "<request>:1:1: internal error: ${e.javaClass.simpleName}: ${e.message}"
            diagnostics.write((message.lineSequence().joinToString(" ") + "\n").toByteArray())
            2
        }

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(System.`in`, System.out, System.err))
    }
}
