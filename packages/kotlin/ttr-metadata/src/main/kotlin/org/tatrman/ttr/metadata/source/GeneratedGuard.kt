// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.metadata.export.GeneratedHeader
import org.tatrman.ttr.metadata.export.Hashes
import org.tatrman.ttr.writer.TtrRenderer
import org.tatrman.ttr.yaml.CanonicalDocument
import org.tatrman.ttr.yaml.CanonicalYaml
import org.tatrman.ttr.yaml.CanonicalYamlException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * MW S3.6 T3 — the generated-file guard (contracts §6, XM D-10): every `.ttrm` / YAML file below a model root whose
 * first line is a `generated-from:` header is checked against the inputs it names.
 *
 * - `xm/generated-stale`: an input's recomputed SHA-256 differs from the header's, or the input is not found.
 * - `xm/generated-edited`: the header is malformed, or the body differs from a fresh generation of the named inputs
 *   (the snapshot tree applied with its own declarations, never an override; a `.ttrm` is that document rendered), or
 *   the tree's own declarations do not give the document the header's profile (✅R277-A: a document an `apply
 *   --profile` override generated cannot be re-checked).
 *
 * Both are errors at the file head (line 1, column 1). Regeneration's own errors (an orphaned overlay key, a dropped
 * input) are reported where they arise, so a broken input is shown at its key. Inputs resolve against the generated
 * file's directory; each snapshot tree is hashed and regenerated once per check. `agents/` is never read (✅MW-44).
 */
object GeneratedGuard {
    fun check(
        root: Path,
        sourceId: String,
    ): List<LoadWarning> {
        val issues = mutableListOf<LoadWarning>()
        val runs = mutableMapOf<Path, Regeneration>()
        // A tree is hashed once per check, however many generated files name it (review-277 F8).
        val hashes = mutableMapOf<Path, String?>()
        for (file in files(root)) {
            val text = file.readText()
            if (!GeneratedHeader.claimed(text)) continue

            fun issue(message: String) = LoadWarning(sourceId, "$file", 1, 1, message)
            val header = GeneratedHeader.read(text)
            if (header == null) {
                issues += issue("xm/generated-edited: the generated-from header is not one ttr-core writes")
                continue
            }
            val directory = file.parent
            val snapshot = directory.resolve(header.snapshot.path).normalize()
            val inputs = listOfNotNull(header.snapshot, header.overlay)
            val stale =
                inputs.mapNotNull { input ->
                    val path = directory.resolve(input.path).normalize()
                    when (val sha = hashes.getOrPut(path) { Hashes.of(path) }) {
                        null -> "input not found: ${input.path}"
                        input.sha256 -> null
                        else -> "${input.path} changed since generation (sha256 $sha, header ${input.sha256})"
                    }
                }
            stale.forEach { issues += issue("xm/generated-stale: $it") }
            if (!snapshot.isDirectory()) continue
            val run = runs.getOrPut(snapshot) { Regeneration.of(snapshot, sourceId) }
            if (stale.isNotEmpty()) continue

            fun overridden() = issue(overridden(header.profile))
            // Before the failed-run skip (review-277 N3): when the tree's own chains fail, an override's document
            // still gets its message; the run's errors are reported where they arise.
            if (header.profile !in run.declared) {
                issues += overridden()
                continue
            }
            if (run.failed) continue
            when (val expected = run.expected(file, text, header.profile)) {
                Expected.Overridden -> issues += overridden()
                Expected.Missing ->
                    issues += issue("xm/generated-edited: its inputs generate no document with this package and model")
                is Expected.Body ->
                    if (expected.text != GeneratedHeader.body(text)) {
                        issues += issue("xm/generated-edited: the body differs from a fresh generation of its inputs")
                    }
            }
        }
        // A regeneration's own errors once per run, where they arise (an orphaned overlay key, a dropped file).
        runs.values.forEach { issues += it.problems }
        return issues
    }

    /** ✅R277-A: the document [profile] generated is not one the tree's own declarations give it. */
    private fun overridden(profile: String) =
        "xm/generated-edited: generated with profile $profile, which the tree's own declarations do not give this " +
            "document; an apply --profile override cannot be re-checked — declare it in ttr.yaml or the file's ttr: header"

    /** `.ttrm`, `.yaml` and `.yml` below [root] that are tree content ([TreeContent]). */
    private fun files(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths
                .filter { it.isRegularFile() && GeneratedHeader.Form.of(it.name) != null }
                .filter { file -> TreeContent.includes(root.relativize(file).map { it.name }) }
                .sorted()
                .toList()
        }

    /** What a generated file is checked against. */
    private sealed interface Expected {
        /** The fresh generation's body, in the file's form. */
        data class Body(
            val text: String,
        ) : Expected

        /** The tree yields the header's profile, but no document with the file's package and model. */
        data object Missing : Expected

        /** The tree's own declarations do not give this document the header's profile (✅R277-A). */
        data object Overridden : Expected
    }

    /** One snapshot tree applied with its own declarations, as `profile apply` without `--profile` would. */
    private class Regeneration(
        private val generated: Map<String, CanonicalDocument>,
        private val generatedBy: Map<String, String>,
        val problems: List<LoadWarning>,
        /** Every chain identity the tree's declarations resolved, even when its run failed (review-277 N3). */
        val declared: Set<String>,
    ) {
        val failed get() = problems.isNotEmpty()

        /** What [file] should hold below its header: the document of its own package and model, in its form. */
        fun expected(
            file: Path,
            text: String,
            profile: String,
        ): Expected {
            val yielded = profile in generatedBy.values
            val (pkg, model) =
                identity(file, GeneratedHeader.body(text))
                    ?: return if (yielded) Expected.Missing else Expected.Overridden
            val path = listOf(pkg.replace('.', '/'), "$model.yaml").filter { it.isNotEmpty() }.joinToString("/")
            val document = generated[path] ?: return if (yielded) Expected.Missing else Expected.Overridden
            if (generatedBy[path] != profile) return Expected.Overridden
            return Expected.Body(
                when (GeneratedHeader.Form.of(file.name)) {
                    GeneratedHeader.Form.TTRM -> TtrRenderer.render(document.ast)
                    else -> CanonicalYaml.write(document)
                },
            )
        }

        /** The file's own package and model, read as its form reads them; null when it does not parse. */
        private fun identity(
            file: Path,
            body: String,
        ): Pair<String, String>? =
            if (GeneratedHeader.Form.of(file.name) == GeneratedHeader.Form.TTRM) {
                val pkg =
                    Regex("""(?m)^package\s+([\w.]+)""")
                        .find(body)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                Regex("""(?m)^model\s+(\w+)""")
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.let { pkg to it }
            } else {
                try {
                    val value = CanonicalYaml.read(body, "$file").value
                    val model = (value["model"] as? JsonPrimitive)?.content ?: return null
                    (value["package"] as? JsonPrimitive)?.content.orEmpty() to model
                } catch (e: CanonicalYamlException) {
                    null
                }
            }

        companion object {
            fun of(
                snapshot: Path,
                sourceId: String,
            ): Regeneration {
                // The tree's own declarations, never an override (✅R277-A): an override replaces only the tree
                // fallback, so it would regroup files that declare their own profile.
                val catalog = ProfileCatalog.read(snapshot).entries
                val result = ProfileApply.run(LocalFsStorage(sourceId, snapshot), null, catalog, sourceId)
                return Regeneration(
                    result.generated,
                    result.generatedBy,
                    result.planned + result.problems + result.conflicts,
                    result.declared,
                )
            }
        }
    }
}
