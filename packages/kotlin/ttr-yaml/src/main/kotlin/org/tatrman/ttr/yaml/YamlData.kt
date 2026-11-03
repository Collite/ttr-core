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

        fun read(
            source: String,
            file: String = "<yaml>",
        ): YamlData {
            if (source.codePointCount(0, source.length) > MAX_YAML_CODE_POINTS) {
                throw CanonicalYamlException(file, 1, 1, "YAML exceeds $MAX_YAML_CODE_POINTS code points")
            }
            val nodes = YamlNodes(file)
            val value = nodes.read(source, canonical = false)
            nodes.validateTags(emptySet())
            return YamlData(value, nodes)
        }
    }
}
