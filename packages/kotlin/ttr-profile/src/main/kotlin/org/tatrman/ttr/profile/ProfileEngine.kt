// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.CanonicalDocument
import org.tatrman.ttr.yaml.CanonicalYaml

data class EmittedDocument(val canonical: CanonicalDocument, val locations: Map<String, ProfileSpan>) {
    // The execution envelope keeps imports explicit; the P2 text codec omits an empty list.
    val value: JsonObject = buildJsonObject {
        put("ttr", canonical.value.getValue("ttr"))
        put("package", canonical.value.getValue("package"))
        put("imports", canonical.value["imports"] ?: JsonArray(emptyList()))
        canonical.value.forEach { (key, value) -> put(key, value) }
    }
    fun write(): String = CanonicalYaml.write(canonical)
}
data class Ignored(val location: ProfileSpan, val reason: String?)
data class ApplyReport(val codes: Map<String, String>)
data class ApplyResult(val documents: List<EmittedDocument>, val ignored: List<Ignored>, val report: ApplyReport)

/** [profile] is the resolved ancestor-to-child chain returned by [ProfileResolver]. */
object ProfileEngine {
    fun apply(tree: List<ForeignTree>, profile: List<ProfileDefinition>): ApplyResult =
        StructuralExecutor.apply(tree, profile.flatMap { it.operations })
}
