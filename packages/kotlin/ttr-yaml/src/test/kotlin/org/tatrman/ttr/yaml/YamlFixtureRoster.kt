// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/** One root document or a complete directory loaded into one resolution context. No frozen counts. */
internal data class YamlScenario(
    val name: String,
    val files: List<Path>,
    val negative: Boolean = false,
)

internal class YamlFixtureRoster(
    val root: Path,
) {
    private fun native(path: Path) = Files.isRegularFile(path) && path.extension in setOf("ttrm", "ttrg")

    val scenarios: List<YamlScenario> =
        Files
            .list(root)
            .use { paths ->
                paths.sorted().toList().mapNotNull { path ->
                    when {
                        native(path) -> YamlScenario(path.name, listOf(path))
                        Files.isDirectory(path) -> {
                            val files = Files.walk(path).use { all -> all.filter(::native).sorted().toList() }
                            check(files.isNotEmpty()) { "Empty conformance scenario $path" }
                            YamlScenario(path.name, files, path.name.endsWith("-negative"))
                        }
                        else -> null
                    }
                }
            }.also { check(it.isNotEmpty()) { "Empty conformance roster $root" } }

    init {
        val collisions = scenarios.flatMap { it.files }.groupBy(::twin).filterValues { it.size > 1 }
        check(collisions.isEmpty()) { "Twin stem collision: $collisions" }
    }

    val positive = scenarios.filterNot { it.negative }
    val negative = scenarios.filter { it.negative }

    fun twin(path: Path): Path = path.resolveSibling(path.fileName.toString().substringBeforeLast('.') + ".yaml")

    fun requireTwins() {
        val expected = positive.flatMap { it.files }.map(::twin).toSet()
        val missing = expected.filterNot(Files::isRegularFile)
        check(
            missing.isEmpty(),
        ) { "Missing committed YAML twins: ${missing.joinToString { root.relativize(it).toString() }}" }
        val actual =
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.extension == "yaml" }.toList().toSet()
            }
        check(actual == expected) { "Orphan YAML twins: ${actual - expected}" }
    }
}

/** Deliberately opt-in local authoring utility; CI always reads the committed twins. */
object GenerateYamlTwins {
    @JvmStatic
    fun main(args: Array<String>) {
        val roster = YamlFixtureRoster(Path.of("../../../tests/conformance/fixtures"))
        var count = 0
        for (scenario in roster.positive) {
            for (file in scenario.files) {
                val document = CanonicalYaml.fromTtr(Files.readString(file), file.toString())
                check(document.ast.ok) { "Positive fixture failed to parse: $file ${document.ast.errors}" }
                Files.writeString(roster.twin(file), CanonicalYaml.write(document))
                count++
            }
        }
        println("Generated $count twins for ${roster.positive.size} scenarios; review and commit the YAML files.")
        val cases = YamlFixtureRoster(Path.of("../../../tests/conformance/yaml-cases"))
        cases.positive.flatMap { it.files }.forEach { file ->
            Files.writeString(cases.twin(file), CanonicalYaml.write(CanonicalYaml.fromTtr(Files.readString(file))))
        }
    }
}
