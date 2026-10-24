// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.spi

/** Dependency-free recipe projection; source positions and compiler graph nodes never cross the SPI. */
data class RecipeGraph(
    val recipe: RecipeMeta,
    val program: ProgramMeta2,
    val deployment: DeploymentMeta,
    val roles: Roles,
    val routes: List<RouteMeta>,
    val wirings: List<WiringMeta>,
    val actions: Map<String, ActionMeta>,
    val process: ProcessMeta?,
    val runApi: RunApiMeta,
)

data class RecipeMeta(
    val name: String,
    val key: List<String>,
    val asOf: String,
    val parameters: List<ParameterMeta>,
    val fingerprints: Map<String, String>,
    val mode: String,
    val kinds: List<String>,
)

data class ParameterMeta(
    val name: String,
    val type: String,
    val value: String?,
)

data class ProgramMeta2(
    val name: String,
    val port: String?,
    val mode: String?,
    val asOf: String?,
    val deliver: String?,
    val schedule: String?,
    val overrides: Map<String, String>,
    val budget: Map<String, String>,
)

data class DeploymentMeta(
    val name: String,
    val target: String,
    val type: String,
    val version: String,
    val runAs: String,
    val granularity: String,
    val dryRun: Boolean,
    val ungoverned: Boolean,
    val connectors: Map<String, String>,
)

data class Roles(
    val candidates: String?,
    val contexts: List<ContextMeta>,
    val decision: String?,
    val switchMode: String?,
)

data class ContextMeta(
    val name: String,
    val contextClass: String,
    val kind: String,
)

data class RouteMeta(
    val name: String,
    val because: String?,
    val detail: List<String>,
    val turnFed: Boolean,
    val isElse: Boolean,
    val isNone: Boolean,
)

data class WiringMeta(
    val routes: List<String>,
    val action: String,
    val columns: Map<String, String>,
    val whenCondition: String?,
)

data class ActionMeta(
    val columns: Map<String, String>,
    val sinks: Map<String, String>,
    val approval: String?,
    val deliverable: Boolean,
    val due: String?,
    val dryRunText: Map<String, String>,
    val transition: Map<String, String>?,
)

data class ProcessMeta(
    val name: String,
    val instance: String?,
    val steps: List<StepMeta>,
)

data class StepMeta(
    val name: String,
    val program: String?,
    val port: String?,
    val obligations: List<String>,
    val time: String?,
    val until: List<String>,
)

data class RunApiMeta(
    val basePath: String,
    val calls: Map<String, String>,
)

data class RecipeEmitRequest(
    val graph: RecipeGraph,
    val manifestJson: String,
    val readme: String,
    val conformanceJson: String,
)
