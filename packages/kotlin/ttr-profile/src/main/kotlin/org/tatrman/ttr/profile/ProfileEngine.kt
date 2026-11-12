// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.CanonicalDocument
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Path

data class EmittedDocument(
    val canonical: CanonicalDocument,
    val locations: Map<String, ProfileSpan>,
) {
    // The execution envelope keeps imports explicit; the P2 text codec omits an empty list.
    val value: JsonObject =
        buildJsonObject {
            put("ttr", canonical.value.getValue("ttr"))
            put("package", canonical.value.getValue("package"))
            put("imports", canonical.value["imports"] ?: JsonArray(emptyList()))
            canonical.value.forEach { (key, value) -> put(key, value) }
        }

    fun write(): String = CanonicalYaml.write(canonical)
}

data class Ignored(
    val location: ProfileSpan,
    val reason: String?,
)

data class ApplyReport(
    val codes: Map<String, String>,
    val overridden: List<Overridden> = emptyList(),
)

data class ApplyResult(
    val documents: List<EmittedDocument>,
    val ignored: List<Ignored>,
    val report: ApplyReport,
) {
    /** The PR-body report: `documents[]` (package, model, imports, defs), `ignored[]` and `overridden[]`. */
    fun reportJson(): JsonObject =
        buildJsonObject {
            putJsonArray("documents") {
                documents.forEach { document ->
                    addJsonObject {
                        put("package", document.value.getValue("package"))
                        put("model", document.value.getValue("model"))
                        put("imports", document.value.getValue("imports"))
                        put(
                            "defs",
                            document.value
                                .getValue("defs")
                                .jsonArray.size,
                        )
                    }
                }
            }
            putJsonArray("ignored") {
                ignored.forEach { item ->
                    addJsonObject {
                        put("file", item.location.file)
                        put("line", item.location.line)
                        put("column", item.location.column)
                        put("path", item.location.path)
                        put("reason", item.reason)
                    }
                }
            }
            putJsonArray("overridden") {
                report.overridden.forEach { item ->
                    addJsonObject {
                        put("qname", item.qname)
                        put("property", item.property)
                        put("from", item.from)
                        put("to", item.to)
                    }
                }
            }
        }
}

/**
 * [profile] is the resolved ancestor-to-child chain returned by [ProfileResolver]. [root] is the model root: link
 * resolution reads its `<package>.sources.json` sidecars, an orphan's suggestion its `renames.yaml`, and a path overlay
 * must stay inside it. Overlays apply ancestor first, so a child's entry wins.
 */
object ProfileEngine {
    fun apply(
        tree: List<ForeignTree>,
        profile: List<ProfileDefinition>,
        root: Path? = null,
    ): ApplyResult =
        StructuralExecutor.apply(
            tree,
            profile.flatMap { it.operations },
            profile.flatMap { OverlayLoader.load(it, root) },
            root,
        )
}
