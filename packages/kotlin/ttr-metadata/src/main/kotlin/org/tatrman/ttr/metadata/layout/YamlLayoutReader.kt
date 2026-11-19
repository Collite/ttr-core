// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.PathType
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.model.LayoutDocument
import org.tatrman.ttr.parser.model.LayoutPoint
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.yaml.CanonicalYamlException
import org.tatrman.ttr.yaml.YamlData

/** The published VL format, read as safe, located YAML data rather than as a model/profile document. */
object YamlLayoutReader {
    private val mapper = ObjectMapper()
    private val schema by lazy {
        val text =
            checkNotNull(javaClass.getResourceAsStream("layout.schema.json")) { "Missing layout.schema.json" }
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
            text,
            SchemaValidatorsConfig
                .builder()
                .pathType(PathType.JSON_POINTER)
                .preloadJsonSchema(false)
                .build(),
        )
    }

    fun read(
        text: String,
        file: String = "<layout>",
    ): LayoutDocument = located(text, file).document

    internal data class Located(
        val document: LayoutDocument,
        val objects: Map<String, SourceLocation>,
    )

    internal fun located(
        text: String,
        file: String,
    ): Located {
        val data = YamlData.read(text, file)
        val root = data.value

        fun fail(
            path: String,
            message: String,
        ): Nothing {
            val at = data.source(path, key = true)
            throw CanonicalYamlException(file, at.line, at.column + 1, message, path)
        }
        val version = (root["layout"] as? JsonPrimitive)?.doubleOrNull
        if (version != null && version > 1) fail("/layout", "layout format $version is newer than this reader")
        val errors = schema.validate(mapper.readTree(root.toString()))
        if (errors.isNotEmpty()) {
            val error = errors.sortedWith(compareBy({ it.instanceLocation.toString() }, { it.message })).first()
            val path =
                error.instanceLocation.toString() +
                    if (error.type == "additionalProperties") "/${pointer(error.property)}" else ""
            fail(path, error.message)
        }

        fun strings(key: String): List<String> = root[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val objects = strings("objects")
        val positions =
            root["positions"]
                ?.jsonObject
                ?.mapValues { (ref, value) ->
                    val path = "/positions/${pointer(ref)}"
                    if (ref !in objects) fail(path, "positions key '$ref' is not in objects")

                    fun axis(name: String): Double {
                        val number =
                            value.jsonObject
                                .getValue(name)
                                .jsonPrimitive.double
                        if (!number.isFinite()) fail("$path/$name", "Expected a finite coordinate")
                        return number
                    }
                    LayoutPoint(axis("x"), axis("y"))
                }.orEmpty()
        val document =
            LayoutDocument(
                root.getValue("name").jsonPrimitive.content,
                root.getValue("model").jsonPrimitive.content,
                objects,
                positions,
                root["skin"]?.jsonPrimitive?.content,
                strings("collapsed"),
            ).canonical()
        return Located(document, objects.withIndex().associate { (i, ref) -> ref to data.source("/objects/$i") })
    }

    private fun pointer(key: String): String = key.replace("~", "~0").replace("/", "~1")
}
