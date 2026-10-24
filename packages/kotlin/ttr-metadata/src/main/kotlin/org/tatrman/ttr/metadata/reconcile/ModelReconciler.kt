// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.reconcile

import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.schemaCodeToToken
import org.tatrman.ttr.metadata.model.CncSchema
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Mapping
import org.tatrman.ttr.metadata.model.MappingSource
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.VocabularyVersion
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.resolve.ReferenceResolutionPass
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.time.Instant
import java.security.MessageDigest

/**
 * Merges one or more [SourceSnapshot]s into a single [Model].
 *
 * v1.2 MVP: single TTR-source path. Multi-source priority merging
 * (DB-introspection > DSL > YAML import) plus the synthetic-table creation
 * for entities that reference unknown DB targets land as Section E follow-ups
 * when [LiveDatabaseSource] and `YamlImportParser` ship.
 *
 * Per Round 4 §4.B the reconciler defaults are:
 *   - onMissingTarget    SYNTHETIC (deferred)
 *   - onTypeMismatch     WARN
 *   - onDuplicateQname   ERROR
 *   - onCycle            ERROR (detection runs against the produced graph)
 */
class ModelReconciler(
    private val descriptor: ModelDescriptor,
    private val policy: ReconciliationPolicy = ReconciliationPolicy(),
) {
    fun reconcile(snapshots: List<SourceSnapshot>): ReconciliationResult {
        // Grammar 0.16 — the automation stdlib joins the PROJECT once (review-114 M6: per source it doubled
        // `obligation` across two sources), when some file declares `model automation` and no source carries a
        // package `automation` of its own.
        val withStdlib =
            if (snapshots.any { s -> s.loadedFiles.any { it.schemaCode == "automation" } } &&
                snapshots.none { s ->
                    s.loadedFiles.any {
                        (it.declaredPackage ?: it.computedPackage) ==
                            org.tatrman.ttr.metadata.source.FileBasedSource.AUTOMATION_STDLIB_PACKAGE
                    }
                }
            ) {
                snapshots +
                    org.tatrman.ttr.metadata.source.AutomationBuilder
                        .stdlibSnapshot(STDLIB_SOURCE_ID)
            } else {
                snapshots
            }
        val sorted = withStdlib.sortedByDescending { it.priority }
        val warnings = mutableListOf<LoadWarning>()
        val errors = mutableListOf<LoadWarning>()

        sorted.forEach {
            warnings += it.warnings
            errors += it.errors
        }

        val referenceResult = runReferenceResolution(sorted)
        // Grammar 0.16 — the automation contract (ttr-semantics AutomationValidator) on the load path: the
        // automation service sees only the snapshot, so a contract violation must be a load issue.
        validateAutomation(sorted, errors, warnings)
        // Resolution is diagnostic-only: ReferenceResolutionPass populates
        // warnings/errors for unimported/ambiguous refs but does NOT rewrite
        // stored qnames. Stored Er2CncRoleMapping.role qnames are already
        // canonical (built with package in Source.kt roles:/role: defaults),
        // so no write-back is needed.
        warnings += referenceResult.warnings
        errors += referenceResult.errors

        val tables =
            mergeMaps(sorted) { it.tables }
                .also { detectDuplicates(sorted, { it.tables.keys }, "db.table", errors) }
        val views = mergeMaps(sorted) { it.views }
        val procedures = mergeMaps(sorted) { it.procedures }
        val foreignKeys = mergeMaps(sorted) { it.foreignKeys }
        val entities =
            mergeMaps(sorted) { it.entities }
                .also { detectDuplicates(sorted, { it.entities.keys }, "er.entity", errors) }
        val relations = mergeMaps(sorted) { it.relations }
        val mappings: List<Mapping> = sorted.flatMap { it.mappings }
        detectDuplicateMappings(mappings, errors)
        val queries: Map<QualifiedName, Query> = mergeMaps(sorted) { it.queries }
        val drillMaps: Map<QualifiedName, org.tatrman.ttr.metadata.model.DrillMap> =
            mergeMaps(sorted) { it.drillMaps }
                .also { detectDuplicates(sorted, { it.drillMaps.keys }, "query.drill_map", errors) }

        // Phase 2.2 — protected qnames (e.g. stock cnc.role.*) reject any
        // lower-priority source that tries to redefine them. Built-in stock
        // sources publish their own qnames here.
        val protectedRoleSources = sorted.filter { it.protectedQnames.isNotEmpty() }
        val protectedQnames = protectedRoleSources.flatMap { it.protectedQnames }.toSet()
        val roles =
            mergeMapsWithProtection(sorted, { it.roles }, protectedQnames, errors)
                .also { detectDuplicates(sorted, { it.roles.keys }, "cnc.role", errors) }

        // Golem P4 S4.2 — merge `def area` records by bare name. Same precedence as the
        // qname-keyed maps: highest-priority source lays down the canonical record, lower
        // sources fill gaps for names not yet seen.
        val areas = mutableMapOf<String, org.tatrman.ttr.metadata.model.AreaRecord>()
        for (s in sorted) {
            for ((name, record) in s.areas) {
                if (name !in areas) areas[name] = record
            }
        }

        // Grammar 0.14 — merge `def schema` row schemas by package-qualified name (same precedence).
        val rowSchemas = mutableMapOf<String, org.tatrman.ttr.metadata.model.RowSchemaRecord>()
        for (s in sorted) {
            for ((name, record) in s.rowSchemas) {
                if (name !in rowSchemas) rowSchemas[name] = record
            }
        }

        val dbSchema =
            DbSchema(
                namespace = "dbo",
                tables = tables,
                views = views,
                procedures = procedures,
                foreignKeys = foreignKeys,
            )
        val erSchema = ErSchema(entities = entities, relations = relations)
        val cncSchema = CncSchema(roles = roles)
        // v4.1 world model (M2) — merge `def world` objects by qname (same precedence).
        val worlds = mergeMaps(sorted) { it.worlds }
        val worldSchema =
            org.tatrman.ttr.metadata.model
                .WorldSchema(worlds = worlds)
        // Grammar 0.16 — the automation tier, merged by qname (same precedence).
        val automationSchema =
            org.tatrman.ttr.metadata.model
                .AutomationSchema(objects = mergeMaps(sorted) { it.automation })
        // Grammar 0.17 — the conceptual tier, each source's built over its own files (a source is a project), merged
        // by qname (same precedence); the reverse index is derived from the merged concepts.
        val cnSchema =
            org.tatrman.ttr.metadata.model.CnSchema(
                concepts = mergeMaps(sorted) { it.cn.concepts },
                bindings = mergeMaps(sorted) { it.cn.bindings },
                relationTypes = mergeMaps(sorted) { it.cn.relationTypes },
                vocabularyVersions =
                    sorted
                        .flatMap { it.cn.vocabularyVersions }
                        .distinctBy(VocabularyVersion::key),
                elementTerms =
                    sorted.fold(emptyMap()) { acc, s ->
                        acc + s.cn.elementTerms.filterKeys { it !in acc }
                    },
            )

        val model =
            Model(
                descriptor = descriptor,
                version =
                    ModelVersion(
                        // A snapshot of the same source versions must have the same ETag across CLI/HTTP loads.
                        value =
                            sorted.joinToString("|") { it.version } + ":" +
                                MessageDigest
                                    .getInstance("SHA-256")
                                    .digest(
                                        sorted
                                            .joinToString(
                                                "",
                                            ) {
                                                "${it.version.toByteArray(
                                                    Charsets.UTF_8,
                                                ).size}:${it.version}"
                                            }.toByteArray(Charsets.UTF_8),
                                    ).joinToString("") { "%02x".format(it) }
                                    .take(16),
                        swappedAt = Instant.now(),
                    ),
                schemas =
                    mapOf(
                        "db" to dbSchema,
                        "er" to erSchema,
                        "cnc" to cncSchema,
                        "world" to worldSchema,
                        "automation" to automationSchema,
                        "cn" to cnSchema,
                    ),
                mappings = mappings,
                queries = queries,
                drillMaps = drillMaps,
                areas = areas,
                rowSchemas = rowSchemas,
            )

        // v2.2 — post-reconcile validation. Needs the assembled Model so it can
        // look up `to.parameters` and `from.sourceText` on the resolved queries.
        errors +=
            org.tatrman.ttr.metadata.resolve
                .DrillMapValidator()
                .validate(model)
                .errors

        // LQ (L-2, ruling LQ-5) — a logical query (`er.query.<name>`) takes no parameters:
        // a parametrized query stays a db query. The editor flags the same rule
        // (`ttr/logical-query-parameters`); this is the load-time refusal.
        for (query in queries.values) {
            if (query.qname.schemaCode != SchemaCode.ER || query.parameters.isEmpty()) continue
            errors +=
                LoadWarning(
                    sourceId = "<reconcile>",
                    file = query.sourceFile.ifEmpty { "<reconcile>" },
                    line = -1,
                    column = -1,
                    message =
                        "logical_query_parameters: logical query 'er.query.${query.qname.name}' declares " +
                            "parameters (${query.parameters.joinToString { it.name }}); a parametrized " +
                            "query stays a db query",
                )
        }

        return ReconciliationResult(
            model = model,
            warnings = warnings,
            errors = errors,
            files =
                sorted.flatMap {
                    it.loadedFiles
                },
        )
    }

    /**
     * Like [mergeMaps] but rejects entries whose qname is in [protectedQnames]
     * unless contributed by the source that owns the protection. Used so that
     * a user `.ttr` cannot redefine stock `cnc.role.fact` etc.
     */
    private fun <T> mergeMapsWithProtection(
        sorted: List<SourceSnapshot>,
        select: (SourceSnapshot) -> Map<QualifiedName, T>,
        protectedQnames: Set<QualifiedName>,
        errors: MutableList<LoadWarning>,
    ): Map<QualifiedName, T> {
        val out = mutableMapOf<QualifiedName, T>()
        for (s in sorted) {
            val ownsProtection = s.protectedQnames.isNotEmpty()
            for ((k, v) in select(s)) {
                if (k in protectedQnames && !ownsProtection) {
                    errors +=
                        LoadWarning(
                            sourceId = s.sourceId,
                            file = "<reconcile>",
                            line = -1,
                            column = -1,
                            message =
                                "Cannot redefine protected qname '${qnameToTokenDotNamespaceDotName(k)}' " +
                                    "(stock vocabulary owns it)",
                        )
                    continue
                }
                if (k !in out) out[k] = v
            }
        }
        return out
    }

    private fun <T> mergeMaps(
        sorted: List<SourceSnapshot>,
        select: (SourceSnapshot) -> Map<QualifiedName, T>,
    ): Map<QualifiedName, T> {
        val out = mutableMapOf<QualifiedName, T>()
        // Highest priority lays down the canonical entries; lower-priority sources
        // fill gaps for keys we haven't seen yet.
        for (s in sorted) {
            for ((k, v) in select(s)) {
                if (k !in out) out[k] = v
            }
        }
        return out
    }

    private fun detectDuplicates(
        sorted: List<SourceSnapshot>,
        select: (SourceSnapshot) -> Set<QualifiedName>,
        kind: String,
        errors: MutableList<LoadWarning>,
    ) {
        if (policy.onDuplicateQname != ReconciliationPolicy.OnDuplicate.ERROR) return
        // A duplicate is when two sources of the SAME priority both define
        // the same qname. Different priorities are "merge by precedence",
        // not duplicates.
        sorted
            .groupBy { it.priority }
            .values
            .forEach { peers ->
                if (peers.size < 2) return@forEach
                val seen = mutableMapOf<QualifiedName, String>()
                peers.forEach { s ->
                    select(s).forEach { qn ->
                        val prev = seen[qn]
                        if (prev != null && prev != s.sourceId) {
                            errors +=
                                LoadWarning(
                                    sourceId = s.sourceId,
                                    file = "<reconcile>",
                                    line = -1,
                                    column = -1,
                                    message =
                                        "Duplicate $kind qname '${qn.name}' contributed by " +
                                            "sources [$prev, ${s.sourceId}] at priority ${s.priority}",
                                )
                        } else {
                            seen[qn] = s.sourceId
                        }
                    }
                }
            }
    }

    /**
     * v2.1 — emits `ttr/duplicate-mapping` when two or more `Er2Db*Mapping`
     * records share a qname and at least one was synthesised from an inline
     * `mapping:` block. Pure-explicit collisions (two `def er2db_*` with the
     * same name) are caught separately by the per-schema `er2db_*` duplicate
     * detector and do not produce this diagnostic.
     *
     * Mirrors the modeler-side `validateDuplicateMappings` in
     * `packages/semantics/src/validator.ts`. The diagnostic message names every
     * other location so the developer can navigate to the offending sites.
     */
    private fun detectDuplicateMappings(
        mappings: List<Mapping>,
        errors: MutableList<LoadWarning>,
    ) {
        val byQname = mappings.groupBy { it.qname }
        for ((qn, entries) in byQname) {
            if (entries.size < 2) continue
            if (entries.none { it.mappingSource is MappingSource.Inline }) continue
            val others =
                entries.joinToString(", ") { e ->
                    val origin =
                        when (val s = e.mappingSource) {
                            is MappingSource.Inline -> "inline on ${s.hostKind}"
                            is MappingSource.Explicit -> "explicit def er2db_*"
                        }
                    "${e.sourceFile} ($origin)"
                }
            for (e in entries) {
                errors +=
                    LoadWarning(
                        sourceId = "<reconcile>",
                        file = e.sourceFile,
                        line = -1,
                        column = -1,
                        message =
                            "ttr/duplicate-mapping: '${qn.name}' is mapped more than once; " +
                                "sources: $others",
                    )
            }
        }
    }

    /** Runs the automation validator over every loaded file; its errors and warnings become load issues. */
    private fun validateAutomation(
        sorted: List<SourceSnapshot>,
        errors: MutableList<LoadWarning>,
        warnings: MutableList<LoadWarning>,
    ) {
        val files = sorted.flatMap { it.loadedFiles }
        val documents =
            files.map { f ->
                org.tatrman.ttr.parser.loader.ParseResult(
                    definitions = f.definitions,
                    modelDirective =
                        f.schemaCode.takeIf { it.isNotEmpty() }?.let {
                            org.tatrman.ttr.parser.model.ModelDirective(
                                it,
                                source = org.tatrman.ttr.parser.model.SourceLocation.UNKNOWN,
                            )
                        },
                    errors = emptyList(),
                    sourceFile = f.storageFile.path,
                    packageName = f.declaredPackage,
                    imports = f.imports,
                )
            }
        val diagnostics =
            org.tatrman.ttr.semantics.AutomationValidator
                .validate(documents, includeStdlib = false)
        for (d in diagnostics) {
            val issue =
                LoadWarning(
                    AUTOMATION_VALIDATOR_ID,
                    d.source.file,
                    d.source.line,
                    d.source.column,
                    "${d.code}: ${d.message}",
                )
            when (d.severity) {
                "error" -> errors += issue
                "warning" -> warnings += issue
                else -> {}
            }
        }
    }

    private fun runReferenceResolution(
        sorted: List<SourceSnapshot>,
    ): org.tatrman.ttr.metadata.resolve.ResolutionResult {
        val allFiles = sorted.flatMap { it.loadedFiles }
        if (allFiles.isEmpty()) {
            return org.tatrman.ttr.metadata.resolve.ResolutionResult(
                warnings = emptyList(),
                errors = emptyList(),
                resolvedReferences = emptyList(),
            )
        }
        val sourceId = sorted.joinToString("|") { it.sourceId }
        val pass = ReferenceResolutionPass(sourceId = sourceId, files = allFiles)
        return pass.run()
    }

    private fun qnameToTokenDotNamespaceDotName(qn: QualifiedName): String {
        val token = schemaCodeToToken(qn.schemaCode)
        val schemaToken = if (token.isNotEmpty()) token else qn.namespace
        val packageSegment = if (qn.`package`.isNotEmpty()) "${qn.`package`}." else ""
        return "$packageSegment$schemaToken.${qn.namespace}.${qn.name}"
    }

    private companion object {
        const val STDLIB_SOURCE_ID = "automation-stdlib"
        const val AUTOMATION_VALIDATOR_ID = "automation-validator"
    }
}

data class ReconciliationPolicy(
    val onMissingTarget: OnMissing = OnMissing.SYNTHETIC,
    val onTypeMismatch: OnMismatch = OnMismatch.WARN,
    val onDuplicateQname: OnDuplicate = OnDuplicate.ERROR,
    val onCycle: OnCycle = OnCycle.ERROR,
) {
    enum class OnMissing { SYNTHETIC, WARN, ERROR }

    enum class OnMismatch { WARN, ERROR }

    enum class OnDuplicate { WARN, ERROR }

    enum class OnCycle { WARN, ERROR }
}

data class ReconciliationResult(
    val model: Model,
    val warnings: List<LoadWarning>,
    val errors: List<LoadWarning>,
    val files: List<org.tatrman.ttr.metadata.source.LoadedFile> = emptyList(),
) {
    val ok: Boolean get() = errors.isEmpty()
}
