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
 *   (the snapshot tree applied with the named profile; a `.ttrm` is that document rendered).
 *
 * Both are errors at the file head (line 1, column 1). Regeneration's own errors (an orphaned overlay key, a dropped
 * input) are reported where they arise, so a broken input is shown at its key. Inputs resolve against the generated
 * file's directory; each snapshot tree is regenerated once per check. `agents/` is never read (✅MW-44).
 */
object GeneratedGuard {
    fun check(
        root: Path,
        sourceId: String,
    ): List<LoadWarning> {
        val issues = mutableListOf<LoadWarning>()
        val runs = mutableMapOf<Pair<Path, String>, Regeneration>()
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
                    when (val sha = Hashes.of(path)) {
                        null -> "input not found: ${input.path}"
                        input.sha256 -> null
                        else -> "${input.path} changed since generation (sha256 $sha, header ${input.sha256})"
                    }
                }
            stale.forEach { issues += issue("xm/generated-stale: $it") }
            if (!snapshot.isDirectory()) continue
            val run = runs.getOrPut(snapshot to header.profile) { Regeneration.of(snapshot, header.profile, sourceId) }
            if (stale.isNotEmpty() || run.failed) continue
            val expected = run.expected(file, text)
            when {
                expected == null ->
                    issues += issue("xm/generated-edited: its inputs generate no document with this package and model")
                expected != GeneratedHeader.body(text) ->
                    issues += issue("xm/generated-edited: the body differs from a fresh generation of its inputs")
            }
        }
        // A regeneration's own errors once per run, where they arise (an orphaned overlay key, a dropped file).
        runs.values.forEach { issues += it.problems }
        return issues
    }

    /** `.ttrm`, `.yaml` and `.yml` below [root]; hidden, `build`, `node_modules` and a top-level `agents/` skipped. */
    private fun files(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths
                .filter { it.isRegularFile() && GeneratedHeader.Form.of(it.name) != null }
                .filter { file ->
                    val segments = root.relativize(file).map { it.name }
                    segments.none { it.startsWith(".") || it == "build" || it == "node_modules" } &&
                        segments.first() != "agents"
                }.sorted()
                .toList()
        }

    /** One snapshot tree applied with one profile, as `profile apply` would. */
    private class Regeneration(
        private val generated: Map<String, CanonicalDocument>,
        val problems: List<LoadWarning>,
    ) {
        val failed get() = problems.isNotEmpty()

        /** What [file] should hold below its header: the document of its own package and model, in its form. */
        fun expected(
            file: Path,
            text: String,
        ): String? {
            val (pkg, model) = identity(file, GeneratedHeader.body(text)) ?: return null
            val path = listOf(pkg.replace('.', '/'), "$model.yaml").filter { it.isNotEmpty() }.joinToString("/")
            val document = generated[path] ?: return null
            return when (GeneratedHeader.Form.of(file.name)) {
                GeneratedHeader.Form.TTRM -> TtrRenderer.render(document.ast)
                else -> CanonicalYaml.write(document)
            }
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
                profile: String,
                sourceId: String,
            ): Regeneration {
                val catalog = ProfileCatalog.read(snapshot).entries
                // The header names an identity; one the tree cannot resolve leaves the tree's own declarations.
                val reference = profile.takeIf { YamlFileSource.isBundledProfile(it) || it in catalog }
                val result = ProfileApply.run(LocalFsStorage(sourceId, snapshot), reference, catalog, sourceId)
                return Regeneration(result.generated, result.planned + result.problems + result.conflicts)
            }
        }
    }
}
