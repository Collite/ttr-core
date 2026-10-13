// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import org.slf4j.LoggerFactory
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.source.LayoutFileRef
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.parser.loader.TtrgIssue
import org.tatrman.ttr.parser.loader.TtrgLayoutRead
import org.tatrman.ttr.parser.loader.TtrgLayoutReader
import org.tatrman.ttr.parser.model.LayoutDocument
import org.tatrman.ttr.parser.model.LayoutPoint
import kotlin.coroutines.cancellation.CancellationException

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

/**
 * A layout file left out of the index (V-38) — it cannot be read, it does not parse, or another file declares
 * its name — or a reader's warning about one. [path] is the file the finding is in, under the model root: the
 * `.ttrl` for a sidecar's own finding, else the `.ttrg`. [reason] is one line.
 */
data class LayoutIssue(
    val path: String,
    val reason: String,
) {
    /** the `/status.issues[]` line */
    override fun toString(): String = "layout $path: $reason"
}

/**
 * The layouts of one model (VL contracts V-37, V-38), built from the layout files its sources read and
 * published with it in the same registry swap. A file that cannot be read or fails to parse is an [issues]
 * entry and is not indexed; a name two files declare is served by neither — each is an issue naming the
 * other, so which picture a viewer gets never hangs on file order (A-VL-7). The model is served regardless. A
 * reader's warnings (a sidecar's residue, A-VL-3) are logged as WARN lines naming the path and kept in
 * [warnings]: §6 has no warnings field and `/status.issues[]` is for failures only.
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
            } catch (e: Throwable) {
                if (!e.recoverable()) throw e
                // the layouts never cost the model its swap (V-38)
                log.warn("layout index failed — no layouts served", e)
                LayoutIndex(emptyMap(), listOf(LayoutIssue("(all layouts)", "the index failed: ${oneLine(e.message)}")))
            }

        private fun buildIndex(
            snapshots: Collection<SourceSnapshot>,
            model: Model,
        ): LayoutIndex {
            val files = snapshots.flatMap { s -> s.layoutFiles.sortedBy { it.path } }
            if (files.isEmpty()) return EMPTY
            val index = LayoutResolver.indexOf(model, LayoutResolver.packagesFrom(snapshots))
            val read = mutableListOf<Pair<LayoutFileRef, TtrgLayoutRead.Ok>>()
            val issues = mutableListOf<LayoutIssue>()
            val warnings = mutableListOf<LayoutIssue>()
            for (file in files) {
                file.unreadable?.let {
                    issues += LayoutIssue(it.source, "could not be read: ${oneLine(it.reason)}")
                    continue
                }
                val r =
                    try {
                        TtrgLayoutReader.read(file.content, file.sidecarContent, file.source)
                    } catch (e: Throwable) {
                        if (!e.recoverable()) throw e
                        issues +=
                            LayoutIssue(
                                file.source,
                                "could not be read: ${oneLine(e.message ?: e::class.java.simpleName)}",
                            )
                        continue
                    }
                r.warnings.forEach { warnings += issueOf(file, it) }
                when (r) {
                    is TtrgLayoutRead.Failed -> issues += issueOf(file, r.errors.first())
                    is TtrgLayoutRead.Ok -> read += file to r
                }
            }
            val layouts = mutableListOf<ResolvedLayout>()
            for ((name, holders) in read.groupBy { it.second.document.name }) {
                if (holders.size == 1) {
                    val (file, r) = holders.single()
                    layouts += resolve(r, file, index)
                    continue
                }
                // A-VL-7: served by neither — whichever came first would win on file order alone
                for (holder in holders) {
                    val others = holders.filter { it !== holder }.joinToString(", ") { it.first.source }
                    issues += LayoutIssue(holder.first.source, "the name $name is also declared by $others")
                }
            }
            issues.sortWith(compareBy({ it.path }, { it.reason }))
            warnings.forEach { log.warn("{}", it) }
            issues.forEach { log.warn("{} — not served", it) }
            val sorted = layouts.sortedWith(ORDER)
            return LayoutIndex(sorted.associateBy { it.name }, issues, warnings)
        }

        /** A reader's finding, under the file it is in — the `.ttrl` for the sidecar's own (its line numbers). */
        private fun issueOf(
            file: LayoutFileRef,
            issue: TtrgIssue,
        ): LayoutIssue {
            val path = if (issue.sidecar) file.sidecarSource ?: file.source else file.source
            val message = oneLine(issue.message)
            return LayoutIssue(path, issue.line?.let { "line $it: $message" } ?: message)
        }

        /** `/status.issues[]` entries and WARN lines are one line each. */
        private fun oneLine(s: String?): String = s.orEmpty().trim().replace(Regex("\\s*\\R\\s*"), " ")

        /** what the layouts may swallow: anything but a cancellation, or a JVM error other than a stack overflow */
        private fun Throwable.recoverable(): Boolean =
            this !is CancellationException && (this !is VirtualMachineError || this is StackOverflowError)

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
                objects =
                    r.resolved.values
                        .distinct()
                        .sortedWith(LayoutDocument.ALPHABETICAL_ORDER),
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
