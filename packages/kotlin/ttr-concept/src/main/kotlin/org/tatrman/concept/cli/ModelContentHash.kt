// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.cli

import org.tatrman.concept.extract.CODE_POINT_ORDER
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The content hash of a TTR-M model (⚑R158-A, review-158 F2) — what an extraction pack's `types/generated/index.json`
 * pins as `snapshotHash`: `ttr cn render-types` writes it, and Pinakes recomputes it at pack load over the model the
 * pack names (contracts §6: a registry whose generated files do not match the snapshot fails to load). It depends on
 * the model's bytes only — the same model on any clone, at any path, after any `touch` gives the same hash, and one
 * changed byte gives another. Not `ResolvedCnModel.snapshotHash`: that is the load's version (`LocalFsStorage` hashes
 * absolute paths and mtimes), which cn frames and `LowerProvenance` echo — a separate contract.
 *
 * The files are the ones a model load reads as model: every regular file under the root, recursively, whose extension
 * is one of [EXTENSIONS]. Layout files (`.ttrg` / `.ttrl`, read only by a load that serves layouts), the export
 * sidecars and everything else are left out. Here, not in `extract`, because it reads files (`PuritySpec`: I/O lives in
 * `cli/` only).
 */
object ModelContentHash {
    /**
     * The extensions a model load reads as model — ttr-metadata's `MODEL_EXTENSIONS` (`source/Source.kt`, internal
     * there), mirrored. Keep the two in step.
     */
    val EXTENSIONS: List<String> = listOf("ttr", "ttrm")

    /**
     * `sha256:<hex>` over the model files under [root], in code-point order of their path relative to [root]
     * (`/`-separated): for each, its relative path (UTF-8), a NUL, its bytes, a NUL.
     */
    fun of(root: Path): String {
        val base = root.toAbsolutePath().normalize()
        val files =
            Files.walk(base).use { s ->
                s
                    .filter {
                        Files.isRegularFile(it) &&
                            it.fileName.toString().substringAfterLast('.', "") in EXTENSIONS
                    }.map { base.relativize(it).joinToString("/") to it }
                    .toList()
            }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((relative, file) in files.sortedWith(compareBy(CODE_POINT_ORDER) { it.first })) {
            digest.update(relative.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(Files.readAllBytes(file))
            digest.update(0)
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
