// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import java.util.SortedMap

/**
 * PL-P5.S2 — the H-6 emit-determinism kit (contracts §8). Determinism is a CONTRACT obligation on every
 * [TtrEmitPlugin]: same [EmitRequest] ⇒ byte-identical [org.tatrman.ttrp.emit.spi.EmitResult]. The kit is the
 * mechanised proof and the third-party certification requirement — it emits every request TWICE through the
 * plugin under test and byte-compares the results, reporting the first divergence (file + offset). A plugin that
 * reads a clock / random / mutable process state fails; a pure one passes.
 *
 * The kit here is the pure double-emit comparator (no compiler/pipeline dependency); the `ttrp conform
 * emit-determinism --plugin <coords>` CLI verb builds the [EmitRequest]s from the conformance corpus and, for the
 * strongest H-6 rigor, runs the two passes in FRESH JVMs (so a per-JVM constant — a boot timestamp, a seed fixed
 * at class-load — is caught too, not just an in-call clock read).
 */
object EmitDeterminismKit {
    /** One byte divergence between two emits of the same request. */
    data class Divergence(
        val case: String,
        val file: String,
        /** 0-based offset of the first differing byte, or -1 when the divergence is the file SET, not content. */
        val offset: Int,
        val detail: String,
    )

    /** The kit's verdict over a run: [deterministic] with a rendered [report] naming every divergence. */
    data class Report(
        val plugin: String,
        val cases: Int,
        val divergences: List<Divergence>,
    ) {
        val deterministic: Boolean get() = divergences.isEmpty()

        fun render(): String =
            if (deterministic) {
                "emit-determinism: PASS — plugin '$plugin' is byte-deterministic over $cases case(s)."
            } else {
                buildString {
                    appendLine(
                        "emit-determinism: FAIL — plugin '$plugin' is NOT deterministic (${divergences.size} divergence(s)):",
                    )
                    divergences.forEach { d ->
                        val where = if (d.offset >= 0) "at byte offset ${d.offset}" else "(file set)"
                        appendLine("  · ${d.case} · ${d.file} $where — ${d.detail}")
                    }
                }.trimEnd()
            }
    }

    /** Emit every request twice and byte-compare. [caseLabel] names each request in the report (default index). */
    fun check(
        plugin: TtrEmitPlugin,
        requests: List<EmitRequest>,
        caseLabel: (Int) -> String = { "case#$it" },
    ): Report {
        val divergences = mutableListOf<Divergence>()
        requests.forEachIndexed { i, req ->
            val a = plugin.emit(req).files
            val b = plugin.emit(req).files
            compare(caseLabel(i), a, b)?.let { divergences += it }
        }
        return Report(plugin = plugin.targetId, cases = requests.size, divergences = divergences)
    }

    /** SPI v2 recipes use the same byte/file-set proof as ordinary program emitters. */
    fun checkRecipes(
        plugin: TtrEmitPlugin,
        requests: List<org.tatrman.ttrp.emit.spi.RecipeEmitRequest>,
    ): Report {
        require(plugin.supportsRecipes) { "${plugin.targetId} does not support recipes" }
        val differences =
            requests.mapIndexedNotNull { i, request ->
                compare("recipe#$i", plugin.emitRecipe(request).files, plugin.emitRecipe(request).files)
            }
        return Report(plugin.targetId, requests.size, differences)
    }

    private fun compare(
        case: String,
        a: SortedMap<String, ByteArray>,
        b: SortedMap<String, ByteArray>,
    ): Divergence? {
        if (a.keys != b.keys) {
            val only = (a.keys - b.keys) + (b.keys - a.keys)
            return Divergence(case, only.sorted().joinToString(","), -1, "the emitted file set differs between runs")
        }
        for (path in a.keys) {
            val x = a.getValue(path)
            val y = b.getValue(path)
            if (!x.contentEquals(y)) {
                return Divergence(
                    case,
                    path,
                    firstDiffOffset(x, y),
                    "bytes differ between two emits of the same request",
                )
            }
        }
        return null
    }

    private fun firstDiffOffset(
        x: ByteArray,
        y: ByteArray,
    ): Int {
        val n = minOf(x.size, y.size)
        for (i in 0 until n) if (x[i] != y[i]) return i
        return n // one is a prefix of the other; first divergence is at the shorter length
    }
}
