// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect

import java.net.URI
import java.nio.file.Path

/**
 * Resolves a file-backed fragment's path (`container … from "<path>"`, AG B5). A relative path is
 * relative to the PROGRAM file — a plain path (the CLI) or a `file:` URI (the LSP); an in-memory
 * program (`<memory>`) has no directory, so only an absolute path resolves.
 */
object FragmentFiles {
    fun resolve(
        programFile: String,
        path: String,
    ): Path? {
        val p = runCatching { Path.of(path) }.getOrNull() ?: return null
        if (p.isAbsolute) return p.normalize()
        val program = programPath(programFile) ?: return null
        val dir = program.toAbsolutePath().parent ?: return null
        return dir.resolve(p).normalize()
    }

    /** The fragment file's name in the program's own naming style (a `file:` URI program names it by URI). */
    fun idOf(
        programFile: String,
        file: Path,
    ): String = if (programFile.startsWith("file:")) file.toUri().toString() else file.toString()

    private fun programPath(programFile: String): Path? =
        when {
            programFile.startsWith("file:") -> runCatching { Path.of(URI(programFile)) }.getOrNull()
            programFile.isEmpty() || programFile.startsWith("<") -> null
            else -> runCatching { Path.of(programFile) }.getOrNull()
        }
}
