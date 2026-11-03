// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationApproval
import org.tatrman.ttr.metadata.model.AutomationColumn
import org.tatrman.ttr.metadata.model.AutomationDuration
import org.tatrman.ttr.metadata.model.AutomationObject
import org.tatrman.ttr.metadata.model.AutomationParameter
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.AutomationRecipe
import org.tatrman.ttr.metadata.model.AutomationRole
import org.tatrman.ttr.metadata.model.AutomationService
import org.tatrman.ttr.metadata.model.AutomationSet
import org.tatrman.ttr.metadata.model.AutomationTransition
import org.tatrman.ttr.metadata.model.GrantWho
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.RemovedWorldMember
import org.tatrman.ttr.metadata.model.RoleMembership
import org.tatrman.ttr.metadata.model.RunAsRef
import org.tatrman.ttr.metadata.model.WorldDeployment
import org.tatrman.ttr.metadata.model.WorldGrant
import org.tatrman.ttr.metadata.model.WorldIdentity
import org.tatrman.ttr.parser.loader.AutomationStdlib
import org.tatrman.ttr.parser.model.AutoBody
import org.tatrman.ttr.parser.model.AutoEntry
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.AutomationDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.RoleDef
import org.tatrman.ttr.parser.model.TargetReferenceValue
import org.tatrman.ttr.parser.model.WorldDef
import org.tatrman.ttr.semantics.RemovedConstructs
import java.nio.file.Path

/**
 * Grammar 0.16 (AU-core-P0 S0.2 T5) — the parser's automation AST → the typed automation tier. Lenient:
 * a malformed value reads as absent (the automation validator in ttr-semantics reports it); the raw
 * entries always ride along as `properties`.
 */
internal object AutomationBuilder {
    private fun qn(
        kind: String,
        name: String,
        pkg: String,
    ): QualifiedName = FileBasedSource.qname("automation", kind, name, pkg)

    fun automation(
        def: AutomationDef,
        pkg: String,
        sourceFile: String,
    ): AutomationObject {
        val b = def.body
        val q = qn(def.kind, def.name, pkg)
        val id = FileBasedSource.idFor("automation.${def.kind}", q)
        val description = b.description ?: ""
        val localized = b.descriptionLocalized.toLocalizedText()
        val label = b.label.toLocalizedText()
        val props = b.entries.map { it.key to it.value }
        return when (def.kind) {
            "parameter" ->
                AutomationParameter(
                    id,
                    q,
                    description,
                    localized,
                    b.tags,
                    sourceFile,
                    label = label,
                    properties = props,
                    sourceLocation = def.source,
                    type = b.type?.name,
                    value = b["value"],
                )
            "action" ->
                AutomationAction(
                    id,
                    q,
                    description,
                    localized,
                    b.tags,
                    sourceFile,
                    label = label,
                    properties = props,
                    sourceLocation = def.source,
                    columns = b.columns.map { AutomationColumn(it.name, it.type?.name ?: "text", it.optional) },
                    sinks =
                        (b["sinks"] as? AutoValue.Obj)
                            ?.entries
                            ?.mapNotNull { e ->
                                text(e.value)?.let { e.key to it }
                            }?.toMap()
                            ?: emptyMap(),
                    transition = b.transition?.let { AutomationTransition(it.attribute, it.to, it.toColumn, it.from) },
                    set = b.set?.let { AutomationSet(it.attribute, it.column) },
                    approval = text(b["approval"]),
                    deliverable = bool(b["deliverable"]) ?: false,
                    due = duration(b["due"]),
                    dryRun = localizedOf(b["dry_run"]),
                    byRole = roleName(b["by"]),
                )
            "service" ->
                AutomationService(
                    id,
                    q,
                    description,
                    localized,
                    b.tags,
                    sourceFile,
                    label = label,
                    properties = props,
                    sourceLocation = def.source,
                    request = text(b["request"]),
                    response = text(b["response"]),
                    timeout = duration(b["timeout"]),
                    budgetPerRun = int((b["budget"] as? AutoValue.Obj)?.get("per_run")),
                    write = bool(b["write"]) ?: false,
                    idempotency = text(b["idempotency"]),
                )
            "approval" ->
                AutomationApproval(
                    id,
                    q,
                    description,
                    localized,
                    b.tags,
                    sourceFile,
                    label = label,
                    properties = props,
                    sourceLocation = def.source,
                    byRole = (b["by"] as? AutoValue.Id)?.parts?.takeIf { it.size == 2 && it[0] == "role" }?.get(1),
                    quorum = int(b["quorum"]) ?: 1,
                )
            "program" -> program(def, b, q, id, label, props, sourceFile)
            else ->
                AutomationRecipe(
                    id,
                    q,
                    description,
                    localized,
                    b.tags,
                    sourceFile,
                    label = label,
                    properties = props,
                    sourceLocation = def.source,
                    key =
                        when (val k = b["key"]) {
                            is AutoValue.ListV -> k.items.mapNotNull { text(it) }
                            null -> emptyList()
                            else -> listOfNotNull(text(k))
                        },
                    mode = text(b["mode"]),
                )
        }
    }

    private fun program(
        def: AutomationDef,
        b: AutoBody,
        q: QualifiedName,
        id: String,
        label: LocalizedText,
        props: List<Pair<String, AutoValue>>,
        sourceFile: String,
    ): AutomationProgram {
        val budget = b["budget"] as? AutoValue.Obj
        val turns = b["turn_budget"] as? AutoValue.Obj
        return AutomationProgram(
            id,
            q,
            b.description ?: "",
            b.descriptionLocalized.toLocalizedText(),
            b.tags,
            sourceFile,
            label = label,
            properties = props,
            sourceLocation = def.source,
            recipe = text(b["recipe"]),
            port = text(b["port"]),
            mode = text(b["mode"]),
            asOf = text(b["as_of"]),
            overrides = (b["overrides"] as? AutoValue.Obj)?.entries?.map { it.key to it.value } ?: emptyList(),
            budgetSync = duration(budget?.get("sync")),
            budgetBatch = duration(budget?.get("batch")),
            turnBudgetPerRun = int(turns?.get("per_run")),
            turnBudgetPerCandidate = int(turns?.get("per_candidate")),
            protocol = text(b["protocol"]),
        )
    }

    /** A `def role` declared in a `model automation` file (or carrying automation properties). */
    fun role(
        def: RoleDef,
        pkg: String,
        sourceFile: String,
    ): AutomationRole {
        val q = qn("role", def.name, pkg)

        fun last(key: String) = def.entries.lastOrNull { it.key == key }?.value
        val membership =
            (last("resolve") as? AutoValue.Tagged)?.let { RoleMembership.KeycloakGroup(it.value) }
                ?: (last("members") as? AutoValue.ListV)?.let { l ->
                    RoleMembership.Members(l.items.mapNotNull { text(it) })
                }
        return AutomationRole(
            FileBasedSource.idFor("automation.role", q),
            q,
            def.description ?: "",
            def.descriptionLocalized.toLocalizedText(),
            def.tags,
            sourceFile,
            label = def.label.toLocalizedText(),
            properties = def.entries.map { it.key to it.value },
            sourceLocation = def.source,
            membership = membership,
        )
    }

    fun identities(
        def: WorldDef,
        pkg: String,
        sourceFile: String,
    ): List<WorldIdentity> =
        def.identities.mapNotNull { m ->
            val name = m.name ?: return@mapNotNull null
            val q = FileBasedSource.qname("world", def.name, name, pkg)
            WorldIdentity(
                FileBasedSource.idFor("identity", q),
                q,
                m.body.description ?: "",
                m.body.descriptionLocalized.toLocalizedText(),
                m.body.tags,
                sourceFile,
                principal = text(m.body["principal"]),
                credential = text(m.body["credential"]),
                sourceLocation = m.source,
            )
        }

    /**
     * The world's grants, FAIL-CLOSED (review-114 M4): a grant whose `who`, `program` or `as` is missing or
     * malformed is dropped and [dropped] reports it — it never widens to "anyone" or "any program". The
     * contract spellings: `who: role.<r> | user.<u> | user "<u>" | identity.<i> | * | "*"`,
     * `program: <name> | * | "*"`, `as: author | identity.<n>`.
     */
    fun grants(
        def: WorldDef,
        pkg: String,
        sourceFile: String,
        dropped: (org.tatrman.ttr.parser.model.SourceLocation, String) -> Unit,
    ): List<WorldGrant> =
        def.grants.mapIndexedNotNull { i, g ->
            val q = FileBasedSource.qname("world", def.name, "grant#${i + 1}", pkg)
            val who = grantWho(g.body["who"])
            val anyProgram = g.body["program"].let { it is AutoValue.Star || (it as? AutoValue.Str)?.raw == "*" }
            val program = (g.body["program"] as? AutoValue.Id)?.ref?.path
            val runAs = runAs(g.body["as"])?.takeIf { it != RunAsRef.Caller }
            if (who == null || runAs == null || (!anyProgram && program == null)) {
                dropped(
                    g.source,
                    "ttr/automation-grant-dropped: world '${def.name}' grant #${i + 1} has a missing or malformed " +
                        "`who:`, `program:` or `as:` — not loaded (a grant never widens)",
                )
                return@mapIndexedNotNull null
            }
            WorldGrant(
                FileBasedSource.idFor("grant", q),
                q,
                g.body.description ?: "",
                g.body.descriptionLocalized.toLocalizedText(),
                g.body.tags,
                sourceFile,
                who = who,
                program = if (anyProgram) null else program,
                runAs = runAs,
                sourceLocation = g.source,
            )
        }

    private fun grantWho(v: AutoValue?): GrantWho? =
        when (v) {
            is AutoValue.Star -> GrantWho.Anyone
            is AutoValue.Str -> if (v.raw == "*") GrantWho.Anyone else null
            // `user "<name>"` — a username an id cannot spell (`jan-novak`, `jan@x`).
            is AutoValue.Tagged -> if (v.tag == "user" && v.value.isNotBlank()) GrantWho.User(v.value) else null
            is AutoValue.Id ->
                when {
                    v.parts.size == 2 && v.parts[0] == "role" -> GrantWho.Role(v.parts[1])
                    v.parts.size == 2 && v.parts[0] == "identity" -> GrantWho.Identity(v.parts[1])
                    // `user.jan.novak` — the rest of the path is the username (review-114 M8)
                    v.parts.size >= 2 && v.parts[0] == "user" -> GrantWho.User(v.parts.drop(1).joinToString("."))
                    else -> null
                }
            else -> null
        }

    // Keep the role.<name> shape aligned with EngineAttributes.role and the validator twins.
    private fun roleName(value: AutoValue?): String? =
        (value as? AutoValue.Id)?.parts?.takeIf { it.size == 2 && it[0] == "role" }?.get(1)

    private fun roleNames(value: AutoValue?): List<String>? =
        when (value) {
            is AutoValue.Id -> roleName(value)?.let(::listOf)
            is AutoValue.ListV -> value.items.map { roleName(it) ?: return null }
            else -> null
        }

    private val DEPLOYMENT_FLAGS =
        listOf("dry_run", "enabled", "debug", "ungoverned", "allow_restricted_view", "approval_override", "obo_only")

    /**
     * The world's deployments, FAIL-CLOSED (review-114 M4): a deployment with a non-boolean flag (`enabled:
     * "false"`), or a missing or malformed `program:`, `run_as:` or `target:` is dropped and [dropped] reports it. A
     * flag keeps its default only when it is absent. The keys D-142 removed (`process:`, `trigger:`, the connectors)
     * ride in [WorldDeployment.properties] only, for the checkers to refuse.
     */
    fun deployments(
        def: WorldDef,
        pkg: String,
        sourceFile: String,
        dropped: (org.tatrman.ttr.parser.model.SourceLocation, String) -> Unit,
    ): List<WorldDeployment> =
        def.deployments.mapNotNull { d ->
            val name = d.name ?: return@mapNotNull null
            val b = d.body
            val q = FileBasedSource.qname("world", def.name, name, pkg)
            val program = (b["program"] as? AutoValue.Id)?.ref?.path
            val target = (b.target as? TargetReferenceValue)?.ref?.path
            val runAs = runAs(b["run_as"])
            val badFlags = DEPLOYMENT_FLAGS.filter { b[it] != null && b[it] !is AutoValue.Bool }
            val problem =
                when {
                    badFlags.isNotEmpty() -> "has non-boolean ${badFlags.joinToString { "`$it:`" }}"
                    runAs == null -> "has a missing or malformed `run_as:`"
                    target == null -> "has a missing or malformed `target:`"
                    // D-142: it ran a process; the flow moves to the engine, it does not gain a program
                    program == null && b["process"] != null ->
                        "runs the removed `process:` (CUT-002; model the flow in the process engine)"
                    program == null -> "has a missing or malformed `program:`"
                    else -> null
                }
            if (problem != null) {
                dropped(d.source, "ttr/automation-deployment-dropped: deployment '$name' $problem — not loaded")
                return@mapNotNull null
            }
            WorldDeployment(
                FileBasedSource.idFor("deployment", q),
                q,
                b.description ?: "",
                b.descriptionLocalized.toLocalizedText(),
                b.tags,
                sourceFile,
                program = program!!,
                target = target!!,
                runAs = runAs!!,
                dryRun = bool(b["dry_run"]) ?: false,
                enabled = bool(b["enabled"]) ?: true,
                debug = bool(b["debug"]) ?: false,
                ungoverned = bool(b["ungoverned"]) ?: false,
                granularity = text(b["granularity"]),
                allowRestrictedView = bool(b["allow_restricted_view"]) ?: false,
                approvalOverride = bool(b["approval_override"]) ?: false,
                readCeiling = int(b["read_ceiling"]),
                oboOnly = bool(b["obo_only"]) ?: false,
                // A present malformed permission never becomes unspecified; retain raw entries for the checker.
                startableBy = b["startable_by"]?.let { roleNames(it) ?: emptyList() },
                properties = b.entries.map { it.key to it.value },
                sourceLocation = d.source,
            )
        }

    /**
     * What D-142 removed from the world, recognised by the 0.22 grammar only to be refused: every `def process`
     * (CUT-001), and every removed entry of every `def deployment` — loaded or dropped — with `target: native` when the
     * world declares no executor of that name (CUT-002…005). Each at its entry.
     */
    fun removed(def: WorldDef): List<RemovedWorldMember> {
        val executors = def.executors.map { it.name }
        return def.processes.map { RemovedWorldMember("process", it.name, "process", it.source) } +
            def.deployments.flatMap { d ->
                val name = d.name ?: return@flatMap emptyList()
                val target = d.body.target as? TargetReferenceValue
                val native =
                    if (target != null && RemovedConstructs.isNativeTarget(target.ref.path, executors)) {
                        listOf(RemovedWorldMember("deployment", name, "target", target.source))
                    } else {
                        emptyList()
                    }
                (
                    RemovedConstructs.inEntries("deployment", d.body.entries).map {
                        RemovedWorldMember("deployment", name, it.key, it.at)
                    } + native
                ).sortedWith(compareBy({ it.sourceLocation?.line }, { it.sourceLocation?.column }))
            }
    }

    /** A storage's `writable:` manifest entry, each item as written (a glob without its quotes). */
    fun writable(manifest: Map<String, PropertyValue>): List<String> {
        val v = manifest["writable"] ?: return emptyList()
        val items = (v as? PropertyValue.ListValue)?.items ?: listOf(v)
        return items.mapNotNull {
            when (it) {
                is PropertyValue.IdValue -> it.ref.path
                is PropertyValue.StringValue -> it.raw
                else -> null
            }
        }
    }

    /**
     * The automation stdlib as a one-file model source: package `automation` under a synthetic root, so
     * the computed package equals the declared one. Joined into a [FileBasedSource] that needs it.
     */
    fun stdlibSnapshot(sourceId: String): SourceSnapshot {
        val root = Path.of("/automation-stdlib")
        val file =
            StorageFile(
                path = root.resolve("automation/automation-stdlib.ttrm").toString(),
                sizeBytes = 0L,
                rootPath = root,
            )
        val storage =
            object : ModelStorage {
                override val id: String = "automation-stdlib"

                override fun fetchVersion(): String = "stdlib"

                override fun listFiles(
                    extensions: List<String>,
                    prefixes: List<String>,
                ): List<StorageFile> = listOf(file)

                override fun read(file: StorageFile): String = AutomationStdlib.source
            }
        return FileBasedSource(sourceId, Int.MIN_VALUE, storage).load()
    }

    private fun runAs(v: AutoValue?): RunAsRef? =
        when (v) {
            is AutoValue.Id ->
                when {
                    v.ref.path == "caller" -> RunAsRef.Caller
                    v.ref.path == "author" -> RunAsRef.Author
                    v.parts.size == 2 && v.parts[0] == "identity" -> RunAsRef.Identity(v.parts[1])
                    else -> null
                }
            else -> null
        }

    private fun text(v: AutoValue?): String? =
        when (v) {
            is AutoValue.Id -> v.ref.path
            is AutoValue.Str -> v.raw
            else -> null
        }

    private fun bool(v: AutoValue?): Boolean? = (v as? AutoValue.Bool)?.raw

    private fun int(v: AutoValue?): Int? = (v as? AutoValue.Num)?.raw?.takeIf { it == kotlin.math.floor(it) }?.toInt()

    private fun duration(v: AutoValue?): AutomationDuration? =
        (v as? AutoValue.Duration)?.let {
            AutomationDuration(it.amount, it.unit)
        }

    private fun localizedOf(v: AutoValue?): LocalizedText =
        (v as? AutoValue.Obj)
            ?.entries
            ?.mapNotNull { e: AutoEntry -> (e.value as? AutoValue.Str)?.let { e.key to it.raw } }
            ?.toMap()
            ?.let { LocalizedText(it) } ?: LocalizedText.EMPTY
}
