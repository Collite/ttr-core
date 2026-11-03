// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import org.snakeyaml.engine.v2.events.*

/** Tracks authored pointers before composition, so aliases and parser/depth failures are located too. */
internal class YamlEventPaths {
    private data class Frame(
        val path: String,
        val mapping: Boolean,
        val keyOfParent: Boolean = false,
        var key: String? = null,
        var expectingKey: Boolean = true,
        var index: Int = 0,
    )

    private val stack = ArrayDeque<Frame>()
    val failurePath: String get() =
        stack.lastOrNull()?.let { frame ->
            if (frame.mapping && frame.key != null) "${frame.path}/${pointerKey(frame.key!!)}" else frame.path
        } ?: ""

    val isMappingKey: Boolean get() = stack.lastOrNull()?.let { it.mapping && it.expectingKey } == true

    fun accept(event: Event): String {
        if (event is CollectionEndEvent) {
            val frame = stack.removeLast()
            complete(frame.keyOfParent)
            return frame.path
        }
        if (event !is NodeEvent) return failurePath
        val parent = stack.lastOrNull()
        val key = isMappingKey
        if (key && event is ScalarEvent) {
            parent!!.key = event.value
            parent.expectingKey = false
            return "${parent.path}/${pointerKey(event.value)}"
        }
        val path =
            when {
                parent == null -> ""
                parent.mapping && (key || parent.key == null) -> parent.path
                parent.mapping -> "${parent.path}/${pointerKey(parent.key!!)}"
                else -> "${parent.path}/${parent.index}"
            }
        if (event is CollectionStartEvent) {
            stack.addLast(Frame(path, event is MappingStartEvent, keyOfParent = key))
        } else {
            complete(key)
        }
        return path
    }

    private fun complete(key: Boolean = false) {
        stack.lastOrNull()?.let { parent ->
            if (parent.mapping) {
                parent.key = null
                parent.expectingKey = !key
            } else {
                parent.index++
            }
        }
    }
}
