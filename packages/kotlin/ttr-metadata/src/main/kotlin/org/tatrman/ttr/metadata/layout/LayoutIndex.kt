// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import org.slf4j.LoggerFactory
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.source.LayoutFileRef
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.parser.loader.TtrgLayoutRead
import org.tatrman.ttr.parser.loader.TtrgLayoutReader
import org.tatrman.ttr.parser.model.LayoutDocument
import org.tatrman.ttr.parser.model.LayoutPoint

/**
 * One layout as the catalog serves it (VL contracts §6, `GET /model/layout`): the document's refs resolved
 * against the model it was published with (§4) — [objects] and the [positions] keys are full qnames of
 * resolved cards; what did not resolve is reported, never dropped from the file.
 */
data class ResolvedLayout(
    val name: String,
    val schema: String,
    /** the resolved cards, alphabetically (A-VL-1) */
    val objects: List<String>,
    /** positions of resolved cards only, keys alphabetically */
    val positions: Map<String, LayoutPoint>,
    val skin: String?,
    val collapsed: List<String>,
    val missing: List<String>,
    val ambiguous: List<LayoutAmbiguity>,
    val ignored: List<String>,
    /** null when the layout has no positions */
    val positionsFrom: String?,
    /** the `.ttrg`'s path under the model root */
    val source: String,
) {
    /** derived, never stored (V-2) */
    val mode: String get() = if (positions.isEmpty()) "auto" else "manual"
}

/** A layout file left out of the index (V-38): it does not parse, or its name is taken. */
data class LayoutIssue(
    val path: String,
    val reason: String,
) {
    /** the `/status.issues[]` line */
    override fun toString(): String = "layout $path: $reason"
}

/**
 * The layouts of one model (VL contracts V-37, V-38), built from the layout files its sources read and
 * published with it in the same registry swap. A file that fails to parse, or repeats a name an earlier file
 * holds, is an [issues] entry and is not indexed; the model is served regardless. A reader's warnings (a
 * sidecar's residue, A-VL-3) are logged as WARN lines naming the path and kept in [warnings]: §6 has no
 * warnings field and `/status.issues[]` is for failures only.
 */
class LayoutIndex(
    /** by name, in the order [all] gives */
    val layouts: Map<String, ResolvedLayout>,
    val issues: List<LayoutIssue>,
    val warnings: List<LayoutIssue> = emptyList(),
) {
    /** every layout, by schema then name, alphabetically (V-39, A-VL-1) */
    val all: List<ResolvedLayout> get() = layouts.values.toList()

    operator fun get(name: String): ResolvedLayout? = layouts[name]

    companion object {
        private val log = LoggerFactory.getLogger(LayoutIndex::class.java)

        val EMPTY = LayoutIndex(emptyMap(), emptyList())

        private val ORDER: Comparator<ResolvedLayout> =
            compareBy(LayoutDocument.ALPHABETICAL_ORDER) { l: ResolvedLayout -> l.schema }
                .thenBy(LayoutDocument.ALPHABETICAL_ORDER) { it.name }

        /**
         * Read every layout file of [snapshots] (in their order, each source's files by path) and resolve it
         * against [model]. Packages come from the same snapshots ([LayoutResolver.packagesFrom]) — the reconciled
         * model keeps none — so the index can be built before the swap that publishes both.
         */
        fun build(
            snapshots: Collection<SourceSnapshot>,
            model: Model,
        ): LayoutIndex =
            try {
                buildIndex(snapshots, model)
            } catch (e: RuntimeException) {
                // the layouts never cost the model its swap (V-38)
                log.warn("layout index failed — no layouts served", e)
                LayoutIndex(emptyMap(), listOf(LayoutIssue("(all layouts)", "the index failed: ${e.message}")))
            }

        private fun buildIndex(
            snapshots: Collection<SourceSnapshot>,
            model: Model,
        ): LayoutIndex {
            val files = snapshots.flatMap { s -> s.layoutFiles.sortedBy { it.path } }
            if (files.isEmpty()) return EMPTY
            val index = LayoutResolver.indexOf(model, LayoutResolver.packagesFrom(snapshots))
            val byName = linkedMapOf<String, ResolvedLayout>()
            val issues = mutableListOf<LayoutIssue>()
            val warnings = mutableListOf<LayoutIssue>()
            for (file in files) {
                val read =
                    try {
                        TtrgLayoutReader.read(file.content, file.sidecarContent, file.source)
                    } catch (e: RuntimeException) {
                        issues += LayoutIssue(file.source, "could not be read: ${e.message}")
                        continue
                    }
                read.warnings.forEach { w ->
                    warnings += LayoutIssue(file.source, w.line?.let { "line $it: ${w.message}" } ?: w.message)
                }
                when (read) {
                    is TtrgLayoutRead.Failed -> {
                        val e = read.errors.first()
                        issues += LayoutIssue(file.source, e.line?.let { "line $it: ${e.message}" } ?: e.message)
                    }
                    is TtrgLayoutRead.Ok -> {
                        val name = read.document.name
                        val holder = byName[name]
                        if (holder != null) {
                            issues += LayoutIssue(file.source, "the name $name is already taken by ${holder.source}")
                        } else {
                            byName[name] = resolve(read, file, index)
                        }
                    }
                }
            }
            warnings.forEach { log.warn("{}", it) }
            issues.forEach { log.warn("{} — not served", it) }
            val sorted = byName.values.sortedWith(ORDER)
            return LayoutIndex(sorted.associateBy { it.name }, issues, warnings)
        }

        private fun resolve(
            read: TtrgLayoutRead.Ok,
            file: LayoutFileRef,
            index: LayoutModelIndex,
        ): ResolvedLayout {
            val doc = read.document
            val r = LayoutResolver.resolve(doc.objects, index, doc.model)
            // two refs naming one card (a bare name and its full qname) are one card; the first position wins
            val positions = linkedMapOf<String, LayoutPoint>()
            for (ref in doc.positions.keys.sortedWith(LayoutDocument.ALPHABETICAL_ORDER)) {
                val full = r.resolved[ref] ?: continue
                positions.putIfAbsent(full, doc.positions.getValue(ref))
            }
            return ResolvedLayout(
                name = doc.name,
                schema = doc.model,
                objects = r.resolved.values.distinct().sortedWith(LayoutDocument.ALPHABETICAL_ORDER),
                positions = positions.toSortedMap(LayoutDocument.ALPHABETICAL_ORDER),
                skin = doc.skin,
                collapsed = doc.collapsed,
                missing = r.missing,
                ambiguous = r.ambiguous,
                ignored = r.ignored,
                positionsFrom = if (positions.isEmpty()) null else read.positionsFrom?.wire,
                source = file.source,
            )
        }
    }
}
