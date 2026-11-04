// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import org.tatrman.ttr.yaml.CanonicalDocument
import org.tatrman.ttr.yaml.CanonicalYaml

data class EmittedDocument(val canonical: CanonicalDocument, val locations: Map<String, ProfileSpan>) {
    val value get() = canonical.value
    fun write(): String = CanonicalYaml.write(canonical)
}
data class Ignored(val location: ProfileSpan, val reason: String?)
data class ApplyReport(val codes: Map<String, String>)
data class ApplyResult(val documents: List<EmittedDocument>, val ignored: List<Ignored>, val report: ApplyReport)

object ProfileEngine {
    fun apply(tree: List<ForeignTree>, profile: List<ProfileDefinition>): ApplyResult =
        StructuralExecutor.apply(tree, profile.single().operations)
}
