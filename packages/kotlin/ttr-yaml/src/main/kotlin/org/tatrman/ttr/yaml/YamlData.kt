// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import com.networknt.schema.JsonSchema
import java.util.concurrent.ConcurrentHashMap
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.PathType
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.model.SourceLocation

/** Safe YAML data with a span for every value and key. No canonical-model projection is performed. */
class YamlData private constructor(
    val value: JsonObject,
    private val nodes: YamlNodes,
) {
    val paths: Set<String> get() = nodes.paths()

    fun source(
        path: String,
        key: Boolean = false,
    ): SourceLocation = nodes.source(path, key)

    /**
     * MW S3.4 (review-260 F8): the [BlockSpan] of the scalar at exactly [path] when it holds [text] and its lines map
     * one to one onto the file (a literal block, or a single-line plain scalar); null otherwise.
     */
    fun block(
        path: String,
        text: String,
    ): BlockSpan? = nodes.block(path, text)?.let { BlockSpan(it.source, it.lines, it.indent) }

    /** Use a branch of a bundled schema while keeping root-local $refs in its original $defs. */
    fun validate(
        schemaResource: String,
        branch: Int,
        value: JsonObject = this.value,
        prefix: String = "",
    ) {
        val schema =
            schemas.computeIfAbsent(schemaResource to branch) { (resourceName, index) ->
                val root = Json.parseToJsonElement(CanonicalGrammar.resource(resourceName)).jsonObject
                val selected = root.getValue("oneOf").jsonArray[index].jsonObject
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                    JsonObject(root.filterKeys { it != "oneOf" } + selected).toString(),
                    SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build(),
                )
            }
        nodes.validate(value, schema) { path, message ->
            val fullPath = prefix + path
            val location = source(fullPath, key = true)
            throw CanonicalYamlException(
                location.file,
                location.line,
                location.column + 1,
                message,
                fullPath,
            )
        }
    }

    companion object {
        private val schemas = ConcurrentHashMap<Pair<String, Int>, JsonSchema>()
        val grammarVersion: String get() = CanonicalGrammar.version

        /** !pl is refused only at or below [tagScope]; other safety checks cover the whole input. */
        fun read(
            source: String,
            file: String = "<yaml>",
            tagScope: String = "",
        ): YamlData {
            val nodes = YamlNodes(file)
            val value = nodes.read(source, canonical = false)
            nodes.validateTags(emptySet(), tagScope)
            return YamlData(value, nodes)
        }
    }
}
