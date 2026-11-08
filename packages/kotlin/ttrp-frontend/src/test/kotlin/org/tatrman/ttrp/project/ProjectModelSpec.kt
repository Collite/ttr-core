// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.project

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttrp.resolve.ModelRepo
import java.nio.file.Files
import java.nio.file.Path

/**
 * AU-export S1.5.4 T1/T2 (D-173): the project model loads as the services load it. hartland's shape — files grouped by
 * kind (`model/er/x.ttrm`) that declare one flat `package hartland`, `[packages] layout = "off"`, the model in
 * `extra-model-roots`, the stock roles used and not copied — loads; the same files under `strict` refuse.
 */
class ProjectModelSpec :
    StringSpec({
        val entity =
            """
            package hartland

            model er

            def entity shipment {
                roles: [fact]
                attributes: [ def attribute sk { type: int, isKey: true } ]
            }
            """.trimIndent()

        fun project(
            layout: String?,
            roots: List<String> = listOf("model"),
            files: Map<String, String> = mapOf("model/er/shipment.ttrm" to entity),
        ): TtrpManifest {
            val dir = Files.createTempDirectory("project-model")
            files.forEach { (path, text) ->
                Files.createDirectories(dir.resolve(path).parent)
                Files.writeString(dir.resolve(path), text)
            }
            val packages = layout?.let { "[packages]\nlayout = \"$it\"\n" } ?: ""
            val extra = roots.joinToString(", ") { "\"$it\"" }
            Files.writeString(dir.resolve("modeler.toml"), "$packages[ttrp]\nextra-model-roots = [$extra]\n")
            val manifest = TtrpManifestReader.resolve(dir)
            manifest.diagnostics.shouldBeEmpty()
            return manifest.manifest
        }

        fun codes(loaded: ProjectModel.Loaded) = loaded.report.issues.map { it.code to it.severity }

        "layout off: hartland's declared package loads, the stock role resolves, no package issue" {
            val loaded = ProjectModel.load(project("off"))
            withClue(loaded.errors(Path.of("/"))) { loaded.ok shouldBe true }
            codes(loaded).map { it.first }.filter { it.startsWith("package") }.shouldBeEmpty()
            loaded.report.issues
                .map { it.message }
                .none { "not reachable" in it } shouldBe true
            loaded.result.model!!
                .objectByQname()
                .keys
                .map { it.name } shouldContain "shipment"
        }

        "layout strict: the same files refuse on the declared package" {
            val loaded = ProjectModel.load(project("strict"))
            loaded.ok shouldBe false
            codes(loaded) shouldContain ("package-mismatch" to LoadIssue.Severity.ERROR)
            loaded.errors(Path.of("/")) shouldContain "declared 'hartland'"
        }

        "no layout key is flexible: the mismatch warns and the model loads" {
            val loaded = ProjectModel.load(project(null))
            loaded.ok shouldBe true
            codes(loaded) shouldContain ("package-mismatch" to LoadIssue.Severity.WARNING)
        }

        "an unknown layout value warns as ttr-metadata does and falls back to flexible" {
            val loaded = ProjectModel.load(project("loose"))
            loaded.ok shouldBe true
            codes(loaded) shouldContain ("manifest-package-layout" to LoadIssue.Severity.WARNING)
        }

        "every root is a peer: one qname in two roots is a duplicate, not an override" {
            val loaded =
                ProjectModel.load(
                    project(
                        "off",
                        roots = listOf("model", "automation"),
                        files = mapOf("model/er/shipment.ttrm" to entity, "automation/er/shipment.ttrm" to entity),
                    ),
                )
            loaded.ok shouldBe false
            codes(loaded).map { it.first } shouldContain "duplicate-qname"
        }

        "a declared root that does not exist is skipped; models/ is not required" {
            val manifest = project("off", roots = listOf("model", "authoring-stubs"))
            ProjectModel.roots(manifest).map { it.fileName.toString() } shouldBe listOf("model")
            ProjectModel.load(manifest).ok shouldBe true
        }

        "the checker's ModelRepo reads the same model: the stock role resolves there too" {
            val manifest = project("off")
            val index = ModelRepo.indexOf(manifest.modelsRoot(), manifest.extraModelRootPaths())!!
            index.snapshot.model
                .objectByQname()
                .keys
                .map { it.name } shouldContain "shipment"
            index.snapshot.model
                .objectByQname()
                .keys
                .map { it.name } shouldContain "fact"
        }
    })
