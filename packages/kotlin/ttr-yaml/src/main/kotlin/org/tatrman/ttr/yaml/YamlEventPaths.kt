// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import org.snakeyaml.engine.v2.events.*

/** Tracks authored pointers before composition, so aliases and parser/depth failures are located too. */
internal class YamlEventPaths {
    private data class Frame(
        val path: String,
        val mapping: Boolean,
        var key: String? = null,
        var index: Int = 0,
    )

    private val stack = ArrayDeque<Frame>()
    val failurePath: String get() =
        stack.lastOrNull()?.let { frame ->
            if (frame.mapping && frame.key != null) "${frame.path}/${pointerKey(frame.key!!)}" else frame.path
        } ?: ""

    val isMappingKey: Boolean get() = stack.lastOrNull()?.let { it.mapping && it.key == null } == true

    fun accept(event: Event): String {
        if (event is CollectionEndEvent) {
            val path = stack.removeLast().path
            complete()
            return path
        }
        if (event !is NodeEvent) return failurePath
        val parent = stack.lastOrNull()
        if (parent != null && parent.mapping && parent.key == null && event is ScalarEvent) {
            parent.key = event.value
            return "${parent.path}/${pointerKey(event.value)}"
        }
        val path =
            when {
                parent == null -> ""
                parent.mapping -> "${parent.path}/${pointerKey(parent.key ?: "")}"
                else -> "${parent.path}/${parent.index}"
            }
        if (event is CollectionStartEvent) {
            stack.addLast(Frame(path, event is MappingStartEvent))
        } else {
            complete()
        }
        return path
    }

    private fun complete() {
        stack.lastOrNull()?.let { parent ->
            if (parent.mapping) parent.key = null else parent.index++
        }
    }
}
