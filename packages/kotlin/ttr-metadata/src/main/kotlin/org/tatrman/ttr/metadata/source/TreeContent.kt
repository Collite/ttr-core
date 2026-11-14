// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

/**
 * The one content-walk rule of a model tree (contracts §4 "Tree walk", S3.7 T0 review-277 N4 (d)): a file is content
 * unless a segment of its path under the root starts with `.` or its first segment is `agents` (✅MW-44: never read).
 * The loader's walk, the tree hash of a generated header and the generated-file guard all ask here, so what the loader
 * reads is what the hash covers (F4 came from three hand copies drifting apart).
 */
internal object TreeContent {
    /** True when the file at [segments] (its path under the root, split on `/`) is part of the tree's content. */
    fun includes(segments: List<String>): Boolean =
        segments.isNotEmpty() && segments.none { it.startsWith(".") } && segments.first() != "agents"
}
