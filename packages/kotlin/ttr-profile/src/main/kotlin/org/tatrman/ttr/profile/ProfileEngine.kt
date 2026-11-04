// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.JsonObject

data class EmittedDocument(val value: JsonObject, val locations: Map<String, ProfileSpan>)
data class Ignored(val location: ProfileSpan, val reason: String?)
data class ApplyReport(val codes: Map<String, String>)
data class ApplyResult(val documents: List<EmittedDocument>, val ignored: List<Ignored>, val report: ApplyReport)

object ProfileEngine {
    fun apply(tree: List<ForeignTree>, profile: List<ProfileDefinition>): ApplyResult =
        TODO("S3.2 record execution")
}
