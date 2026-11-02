// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.system.exitProcess

/** Syntax migrations only. A version pair must be registered here; no best-effort dialect detection. */
object YamlMigration {
    private val version = CanonicalGrammar.version
    private val transforms: Map<Pair<String, String>, (String) -> CanonicalDocument> =
        mapOf((version to version) to { source -> CanonicalYaml.read(source) })

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
