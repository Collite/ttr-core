// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationRole
import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.resolve.ModelIndex

/** CG-2: model declarations accepted by the permissive parser must be checked before bundle emission. */
internal object EngineAttributes {
    fun check(model: ModelIndex): List<TtrpDiagnostic> =
        buildList {
            val roles =
                model
                    .automationAll()
                    .filterIsInstance<AutomationRole>()
                    .map { it.qname.name }
                    .toSet()

            fun refuse(
                id: TtrpDiagnosticId,
                message: String,
                at: org.tatrman.ttr.parser.model.SourceLocation?,
            ) {
                val location =
                    at?.let {
                        SourceLocation(
                            it.file,
                            it.line,
                            it.column,
                            it.endLine,
                            it.endColumn,
                            it.offsetStart,
                            it.offsetEnd,
                        )
                    }
                        ?: SourceLocation.UNKNOWN
                add(TtrpDiagnostic(id, Severity.ERROR, message, location))
            }

            // Keep the role.<name> shape aligned with AutomationBuilder.roleName and the validator twins.
            fun role(
                value: AutoValue,
                what: String,
                id: TtrpDiagnosticId,
            ) {
                val ref = value as? AutoValue.Id
                when {
                    ref == null || ref.parts.size != 2 || ref.parts[0] != "role" ->
                        refuse(id, "$what must name `role.<name>`", value.source)
                    ref.parts[1] !in roles -> refuse(id, "$what names unknown role `${ref.parts[1]}`", value.source)
                }
            }
            for (action in model.automationAll().filterIsInstance<AutomationAction>()) {
                val what = "action `${action.qname.name}`"
                val by = action.property("by")
                if (by != null) {
                    role(by, "$what `by:`", TtrpDiagnosticId.ACT_001)
                } else if (action.approval == "required") {
                    refuse(
                        TtrpDiagnosticId.ACT_001,
                        "$what with `approval: required` needs `by: role.<name>`",
                        action.sourceLocation,
                    )
                }
                action.property("due")?.let { due ->
                    if (due !is AutoValue.Duration ||
                        due.unit !in UNITS ||
                        !due.amount.isFinite() ||
                        due.amount < 0 ||
                        due.amount != kotlin.math.floor(due.amount)
                    ) {
                        refuse(
                            TtrpDiagnosticId.ACT_003,
                            "$what `due:` must be a whole, non-negative duration (${UNITS.joinToString()})",
                            due.source,
                        )
                    }
                }
            }
            for (world in model.objectsByQname.values.filterIsInstance<World>()) {
                for (deployment in world.deployments) {
                    deployment.properties.lastOrNull { it.first == "startable_by" }?.second?.let { value ->
                        val values = if (value is AutoValue.ListV) value.items else listOf(value)
                        values.forEach {
                            role(
                                it,
                                "deployment `${deployment.qname.name}` `startable_by:`",
                                TtrpDiagnosticId.ACT_002,
                            )
                        }
                    }
                }
                for (executor in world.executors) {
                    executor.manifest["egress_url"]?.let { value ->
                        if (value !is PropertyValue.StringValue && value !is PropertyValue.TripleStringValue) {
                            refuse(
                                TtrpDiagnosticId.ACT_004,
                                "executor `${executor.qname.name}` `egress_url:` must be a string",
                                value.source,
                            )
                        }
                    }
                }
            }
        }

    // Keep aligned with AutomationValidator.DURATION_UNITS and the TypeScript duration() validator.
    private val UNITS = setOf("seconds", "minutes", "hours", "days", "working_days", "weeks", "months")
}
