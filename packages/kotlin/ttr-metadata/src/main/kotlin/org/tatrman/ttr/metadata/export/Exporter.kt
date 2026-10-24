// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.writer.TtrRenderer

/** File-system/HTTP concerns stay in callers. They read sidecars and archive Bundle files. */
data class ExportRequest(
    val models: Set<String> = setOf("world"),
    val format: String = "ttr",
    val profile: String? = null,
    val packages: Set<String>? = null,
    val areas: Set<String>? = null,
    val since: String? = null,
    val previousIds: Set<String>? = null,
    val renames: List<Rename> = emptyList(),
    val connections: List<ConnectionDescriptor> = emptyList(),
)

sealed interface ExportResult {
    data class Single(
        val name: String,
        val contentType: String,
        val bytes: ByteArray,
    ) : ExportResult

    data class Bundle(
        val files: List<Single>,
    ) : ExportResult

    data class CannotCarry(
        val lossinessId: String,
    ) : ExportResult

    data object SinceUnknown : ExportResult

    data object NotBuilt : ExportResult
}

class Exporter {
    fun export(
        set: DefinitionSet,
        @Suppress("UNUSED_PARAMETER") typedGraph: ModelGraph?,
        request: ExportRequest,
    ): ExportResult {
        require(
            request.models.isNotEmpty() &&
                request.models.all { it in models || it in bindingFamilies || it in ExportKinds.bindings },
        ) { "Unknown export model: ${request.models}" }
        require(request.format in formats) { "Unknown export format: ${request.format}" }
        val profiles =
            when (request.format) {
                "ttl" -> setOf("skos", "owl", "both")
                "xmi" -> setOf("ttr-mdg/1", "ea-erd/1")
                "ttr" -> setOf("canonical")
                else -> emptySet()
            }
        require(request.profile == null || request.profile in profiles) { "Unknown export profile: ${request.profile}" }
        for (model in request.models.sortedWith(bytewise)) {
            LossinessLedger
                .cannotCarry(
                    if (model in ExportKinds.bindings ||
                        model in bindingFamilies
                    ) {
                        "bindings"
                    } else {
                        model
                    },
                    request.format,
                )?.let { return ExportResult.CannotCarry(it) }
        }
        if (request.format !in setOf("graph.json", "defs.json", "dot", "ttr")) return ExportResult.NotBuilt
        if (request.since != null && request.previousIds == null) return ExportResult.SinceUnknown
        val scope = ExportScope(request.packages, request.areas)
        val header = ExportHeader.from(set, scope, request.connections)
        val allGraph = DefinitionGraph.build(set)
        val selected = scope.apply(allGraph, set).selectModels(request.models)
        val definitionIds by lazy { scope.definitionIds(set, allGraph) }
        val delta =
            request.since?.let {
                ExportDelta.compute(requireNotNull(request.previousIds), selected, request.renames, it)
            }
        return when (request.format) {
            "graph.json" ->
                ExportResult.Single(
                    "graph.json",
                    "application/json",
                    GraphJsonWriter.write(selected, header, delta),
                )
            "defs.json" -> {
                val entries = set.entries.filter { e -> matches(e, request.models) && e.qname in definitionIds }
                ExportResult.Single(
                    "defs.json",
                    "application/json",
                    DefsJsonWriter.write(set.copy(entries = entries), header, set),
                )
            }
            "ttr" -> {
                // The definition set covers MD/CN/lexicon too, unlike the legacy typed-model converter.
                val entries =
                    set.entries.filter { e ->
                        e.parentQname == null &&
                            matches(e, request.models) &&
                            e.qname in definitionIds
                    }
                val files =
                    entries.groupBy { it.file }.toSortedMap(bytewise).map { (file, defs) ->
                        val first = defs.first()
                        val layer = first.fileModel
                        ExportResult
                            .Single(
                                file,
                                "text/plain; charset=utf-8",
                                TtrRenderer
                                    .renderFile(
                                        layer,
                                        first.fileNamespace,
                                        defs.map {
                                            it.def
                                        },
                                        first.pkg.takeIf { it.isNotEmpty() },
                                        first.imports,
                                        first.locale,
                                    ).toByteArray(Charsets.UTF_8),
                            )
                    }
                ExportResult.Bundle(files)
            }
            "dot" -> {
                // XM includes MD/CN too; the legacy typed overload remains available to old callers.
                val text = GraphDotExporter.export(selected)
                ExportResult.Single("model.dot", "text/vnd.graphviz; charset=utf-8", text.toByteArray(Charsets.UTF_8))
            }
            else -> ExportResult.NotBuilt
        }
    }

    private fun DefinitionGraph.selectModels(models: Set<String>): DefinitionGraph {
        if ("world" in models) return this
        val inside = nodes.filter { it.model in models }.map { it.id }.toSet()
        val selectedEdges =
            edges.filter { edge ->
                edge.from in inside ||
                    edge.to in inside ||
                    edge.id?.substringBefore('.')?.let { kind ->
                        kind in ExportKinds.bindings &&
                            ("bindings" in models || kind in models || kind.substringBefore('_') in models)
                    } ==
                    true
            }
        val ids = inside + selectedEdges.flatMap { listOf(it.from, it.to) }
        return DefinitionGraph(
            nodes.filter { it.id in ids }.map {
                if (it.id in
                    inside
                ) {
                    it
                } else {
                    Node(it.id, kind = it.kind, external = true)
                }
            },
            selectedEdges,
        )
    }

    private fun matches(
        e: DefinedObject,
        selected: Set<String>,
    ): Boolean =
        "world" in selected ||
            e.model in selected ||
            e.kind in selected ||
            e.model == "bindings" &&
            e.kind.substringBefore('_') in selected

    companion object {
        private val bindingFamilies = setOf("cn2md", "cn2er", "cn2db", "md2er", "md2db", "er2db")
        private val models = setOf("world", "cn", "md", "er", "db", "lexicon", "bindings")
        private val formats =
            setOf(
                "ttr",
                "yaml",
                "defs.json",
                "graph.json",
                "dot",
                "ttl",
                "xmi",
                "ossie",
                "uc",
                "tmdl",
                "pdm",
                "ldm",
                "cdm",
            )
    }
}
