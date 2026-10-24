// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.schemaCodeToToken
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.metadata.source.LoadedFile
import org.tatrman.ttr.parser.model.LayoutDocument

/**
 * One model object as layout resolution sees it — the shape the catalog serves a node in:
 * `fullQname` = `pkg` + `.` + `qname` (the `fullQname()` format), `qname` = `<schema>.<namespace>.<name>`
 * (`er.entity.item`, `db.dbo.item`), `name` = the leaf identifier (`item`).
 */
data class LayoutIndexEntry(
    val fullQname: String,
    val qname: String,
    val kind: String,
    val name: String,
    val pkg: String,
    val schema: String,
)

/** The cards a layout can show, and the edges (relations, foreign keys) a ref may name and be ignored for. */
data class LayoutModelIndex(
    val cards: List<LayoutIndexEntry>,
    val edges: List<LayoutIndexEntry> = emptyList(),
) {
    // built once per index, so resolving a layout is a map lookup per ref and step, not a scan of every card
    internal val cardSteps: Map<String, List<Map<String, List<String>>>> by lazy { LayoutResolver.stepsBySchema(cards) }
    internal val edgeSteps: Map<String, List<Map<String, List<String>>>> by lazy { LayoutResolver.stepsBySchema(edges) }
}

data class LayoutAmbiguity(
    val ref: String,
    /** the full qnames the deciding step matched, sorted alphabetically */
    val candidates: List<String>,
)

/** Every list and the [resolved] keys in alphabetical order of the ref. */
data class LayoutResolution(
    val resolved: Map<String, String>,
    val missing: List<String>,
    val ambiguous: List<LayoutAmbiguity>,
    val ignored: List<String>,
)

/**
 * The resolution rule (VL contracts §4, V-15, V-16) — the JVM twin of the viewer's `resolveRefs`
 * (`packages/designer/src/layout/resolve.ts`), held to it by the shared vectors' `expect.json`.
 *
 * A ref resolves against the cards of the layout's schema, in order; the first step that matches
 * decides, and a step matching more than one card is AMBIGUOUS (never guessed, P2):
 *   1. the exact full qname · 2. the catalog qname, any package · 3. `<kind>.<name>`, any package ·
 *   4. the bare name, any package.
 * No card at any step ⇒ a ref that names a relation / foreign key (the same forms against the schema's
 * edges, or a qname whose kind segment says so) is IGNORED (V-1); anything else is MISSING.
 */
object LayoutResolver {
    /** model-object kinds that are cards on a layout (er: entity; db: table, view) */
    val CARD_KINDS: Set<String> = setOf("entity", "table", "view")

    /** model-object kinds that are edges between cards */
    val EDGE_KINDS: Set<String> = setOf("relation", "foreign_key")

    /** a qname whose kind segment (the one before the name) is one of these names an edge, not a card */
    val EDGE_KIND_SEGMENTS: Set<String> = setOf("relation", "fk", "foreign_key")

    private val steps: List<(LayoutIndexEntry) -> String> =
        listOf({ it.fullQname }, { it.qname }, { "${it.kind}.${it.name}" }, { it.name })

    /** Per schema, one map per step (§4, in order): the step's form of an entry → the entries' full qnames. */
    internal fun stepsBySchema(entries: List<LayoutIndexEntry>): Map<String, List<Map<String, List<String>>>> =
        entries.groupBy { it.schema }.mapValues { (_, inSchema) ->
            steps.map { form -> inSchema.groupBy(form) { it.fullQname } }
        }

    /**
     * The package of every model object, by the loader's rule — a file's declared `package`, else the
     * one its directory implies (`declaredPackage ?: computedPackage`) — from the [snapshots] the model
     * was reconciled from. The reconciled model does not keep packages (`QualifiedName.package` is
     * empty after a load), so [indexOf] needs this, or a server's own per-file package index.
     */
    fun packagesFrom(snapshots: Collection<SourceSnapshot>): (ModelObject) -> String =
        packagesFromFiles(snapshots.flatMap { it.loadedFiles })

    /** Includes reconciler-injected files (notably the stdlib) when passed ReconciliationResult.files. */
    fun packagesFromFiles(files: Collection<LoadedFile>): (ModelObject) -> String {
        val byFile =
            files.associate {
                it.storageFile.path to
                    (it.declaredPackage ?: it.computedPackage)
            }
        return { obj -> byFile[obj.sourceFile].orEmpty() }
    }

    /**
     * The cards and edges of [model], in the catalog's node shape, sorted by full qname. [packageOf]
     * names an object's package ([packagesFrom], or the serving process's package index) so the full
     * qnames are the ones the catalog serves.
     */
    fun indexOf(
        model: Model,
        packageOf: (ModelObject) -> String,
    ): LayoutModelIndex {
        val cards = mutableListOf<LayoutIndexEntry>()
        val edges = mutableListOf<LayoutIndexEntry>()
        for (obj in model.objectByQname().values) {
            val target =
                when (obj.kind) {
                    in CARD_KINDS -> cards
                    in EDGE_KINDS -> edges
                    else -> continue
                }
            val q = obj.qname
            val schema = schemaCodeToToken(q.schemaCode)
            val qname = listOf(schema, q.namespace, q.name).filter { it.isNotEmpty() }.joinToString(".")
            val pkg = packageOf(obj)
            target +=
                LayoutIndexEntry(
                    fullQname = listOf(pkg, qname).filter { it.isNotEmpty() }.joinToString("."),
                    qname = qname,
                    kind = obj.kind,
                    name = q.name.substringAfterLast('.'),
                    pkg = pkg,
                    schema = schema,
                )
        }
        val order = compareBy(LayoutDocument.ALPHABETICAL_ORDER) { e: LayoutIndexEntry -> e.fullQname }
        return LayoutModelIndex(cards.sortedWith(order), edges.sortedWith(order))
    }

    fun indexOf(
        snapshot: RegistrySnapshot,
        packageOf: (ModelObject) -> String,
    ): LayoutModelIndex = indexOf(snapshot.model, packageOf)

    /** Resolve [refs] against the cards (and edges) of [model] — §4, steps 1–4. */
    fun resolve(
        refs: Collection<String>,
        index: LayoutModelIndex,
        model: String,
    ): LayoutResolution {
        val cards = index.cardSteps[model].orEmpty()
        val edges = index.edgeSteps[model].orEmpty()
        val resolved = linkedMapOf<String, String>()
        val missing = mutableListOf<String>()
        val ambiguous = mutableListOf<LayoutAmbiguity>()
        val ignored = mutableListOf<String>()
        for (ref in refs.distinct().sortedWith(LayoutDocument.ALPHABETICAL_ORDER)) {
            val hits = match(ref, cards)
            when {
                hits.size == 1 -> resolved[ref] = hits.single()
                hits.size > 1 -> ambiguous += LayoutAmbiguity(ref, hits)
                match(ref, edges).isNotEmpty() || namesAnEdgeKind(ref) -> ignored += ref
                else -> missing += ref
            }
        }
        return LayoutResolution(resolved, missing, ambiguous, ignored)
    }

    fun resolve(
        refs: Collection<String>,
        snapshot: RegistrySnapshot,
        model: String,
        packageOf: (ModelObject) -> String,
    ): LayoutResolution = resolve(refs, indexOf(snapshot, packageOf), model)

    /** The first step whose form of some entry equals [ref] decides; its distinct full qnames, sorted. */
    private fun match(
        ref: String,
        steps: List<Map<String, List<String>>>,
    ): List<String> {
        for (byForm in steps) {
            val hits = byForm[ref] ?: continue
            return hits.distinct().sortedWith(LayoutDocument.ALPHABETICAL_ORDER)
        }
        return emptyList()
    }

    private fun namesAnEdgeKind(ref: String): Boolean {
        val parts = ref.split('.')
        return parts.size >= 2 && parts[parts.size - 2] in EDGE_KIND_SEGMENTS
    }
}
