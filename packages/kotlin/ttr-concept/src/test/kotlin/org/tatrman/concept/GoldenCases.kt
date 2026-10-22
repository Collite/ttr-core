// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.assertion.v1.Assertion
import org.tatrman.statement.v1.Statement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * CN-P3 S3.7 — the golden cases under `resources/goldens/<group>/` (the format: `goldens/README.md`). A case is a
 * directory holding `case.json` (world, call, stage, input refs, oracle), its input frame and its `expected.*`; a
 * `<case>.pending` file (one line: the demand id) stands for a case whose lowering waits on a primitive that does not
 * exist yet. Read from the SOURCE tree, so `-Pcn.goldens.regen` writes back where the files are reviewed.
 */
internal object GoldenCases {
    val root: Path = DefinitionFixtures.repo.resolve("packages/kotlin/ttr-concept/src/test/resources/goldens")
    private val frames: Path = DefinitionFixtures.repo.resolve("packages/kotlin/ttr-concept/src/test/resources/frames")

    val json = Json { prettyPrint = true }

    class Case(
        val group: String,
        val name: String,
        val dir: Path,
        val spec: JsonObject,
    ) {
        val id: String get() = "$group/$name"
        val world: String get() = str("world")!!
        val call: String get() = str("call")!!
        val stage: String get() = str("stage")!!
        val tags: List<String> get() = spec["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val oracle: JsonObject? get() = spec["oracle"] as? JsonObject

        fun str(key: String): String? = (spec[key] as? JsonPrimitive)?.contentOrNull

        fun file(name: String): Path = dir.resolve(name)

        fun has(name: String): Boolean = file(name).exists()

        fun text(name: String): String = file(name).readText()

        fun jsonFile(name: String): JsonElement = Json.parseToJsonElement(text(name))

        /** The input frame: `frame` names an S3.1 sample under `resources/frames/`, else the case's own file. */
        private fun frameText(own: String): String =
            str("frame")?.let { frames.resolve("$it.json").readText() } ?: text(own)

        fun statement(): Statement =
            Statement
                .newBuilder()
                .also {
                    JsonFormat.parser().merge(frameText("statement.json"), it)
                }.build()

        fun assertion(): Assertion =
            Assertion
                .newBuilder()
                .also {
                    JsonFormat.parser().merge(frameText("assertion.json"), it)
                }.build()
    }

    class Pending(
        val group: String,
        val name: String,
        val demand: String,
    ) {
        val id: String get() = "$group/$name"
    }

    private fun groups(): List<Path> = Files.list(root).use { s -> s.filter { it.isDirectory() }.sorted().toList() }

    val cases: List<Case> by lazy {
        groups().flatMap { g ->
            Files
                .list(g)
                .use { s ->
                    s.filter { it.isDirectory() && it.resolve("case.json").exists() }.sorted().toList()
                }.map {
                    Case(g.name, it.name, it, Json.parseToJsonElement(it.resolve("case.json").readText()).jsonObject)
                }
        }
    }

    val pending: List<Pending> by lazy {
        groups().flatMap { g ->
            Files
                .list(g)
                .use { s ->
                    s.filter { it.isRegularFile() && it.name.endsWith(".pending") }.sorted().toList()
                }.map {
                    Pending(g.name, it.name.removeSuffix(".pending"), it.readText().trim())
                }
        }
    }

    // ---- the actual result as JSON (only the fields the result types carry today) ----

    fun diagnostics(ds: List<Diagnostic>): JsonArray =
        buildJsonArray {
            for (d in ds) {
                add(
                    buildJsonObject {
                        put("slug", d.slug)
                        put("severity", d.severity.name)
                        put("subject", d.subject)
                    },
                )
            }
        }

    fun provenance(p: LowerProvenance): JsonObject =
        buildJsonObject {
            put("snapshotHash", p.snapshotHash)
            put(
                "bindingPaths",
                buildJsonArray {
                    for (b in p.bindingPaths) {
                        add(
                            buildJsonObject {
                                put("slot", b.slot)
                                put("concept", b.concept)
                                put("bindingDef", b.bindingDef)
                                put("element", b.element)
                                put("via", b.via.name)
                            },
                        )
                    }
                },
            )
            put("inlinedDefinitions", JsonArray(p.inlinedDefinitions.map(::JsonPrimitive)))
            put("parametersBound", JsonObject(p.parametersBound.mapValues { JsonPrimitive(it.value) }))
            put("asOf", p.asOf)
            put("imprecisionMode", p.imprecisionMode.name)
        }

    fun rendering(r: Rendering): JsonObject =
        buildJsonObject {
            put(
                "parametersRead",
                JsonArray(
                    r.parametersRead.map {
                        buildJsonObject {
                            put("name", it.name)
                            it.derivation?.let { d -> put("derivation", d) }
                        }
                    },
                ),
            )
            put("definitionsInlined", JsonArray(r.definitionsInlined.map(::JsonPrimitive)))
            put("imprecisionMode", r.imprecisionMode.name)
            put("diagnostics", diagnostics(r.diagnostics))
        }

    fun rows(r: RowObjects): JsonObject =
        buildJsonObject {
            put(
                "rows",
                buildJsonArray {
                    for (row in r.rows) {
                        add(
                            buildJsonObject {
                                put("entity", row.entity)
                                put("values", JsonObject(row.values.mapValues { JsonPrimitive(it.value) }))
                            },
                        )
                    }
                },
            )
            put("diagnostics", diagnostics(r.diagnostics))
        }

    /**
     * Every key [expected] names must be [actual]'s, equal; a key [actual] carries that [expected] omits is compared
     * only when it is [alwaysCompared] (an omitted list there means an empty one — `diagnostics`, say). Returns the
     * mismatches, empty when the two agree.
     */
    fun mismatches(
        expected: JsonObject,
        actual: JsonObject,
        alwaysCompared: Set<String> = setOf("diagnostics"),
    ): List<String> {
        val out = mutableListOf<String>()
        for ((k, v) in expected) {
            val a = actual[k]
            when {
                a == null ->
                    out +=
                        "`$k` is expected but the result carries no such field yet: ${json.encodeToString(
                            JsonElement.serializer(),
                            v,
                        )}"
                a != v ->
                    out +=
                        "`$k`: expected ${json.encodeToString(
                            JsonElement.serializer(),
                            v,
                        )}\n  actual ${json.encodeToString(JsonElement.serializer(), a)}"
            }
        }
        for (k in alwaysCompared - expected.keys) {
            val a = actual[k] ?: continue
            if (a != JsonArray(emptyList()) &&
                a != JsonNull
            ) {
                out += "`$k`: expected none, actual ${json.encodeToString(JsonElement.serializer(), a)}"
            }
        }
        return out
    }
}
