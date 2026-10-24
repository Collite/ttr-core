// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.ProgramResult
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.bundle.BundleAssembler
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.doc.DocDiagnostic
import org.tatrman.ttrp.doc.DocExtractor
import org.tatrman.ttrp.doc.annotations.AnnotationRecord
import org.tatrman.ttrp.project.TtrpManifestReader
import java.nio.file.Files
import java.nio.file.Path

/** Builds every document skeleton from its source; --check never writes derived artifacts or bundles. */
object LiterateBuild {
    fun run(
        file: Path,
        output: Path?,
        check: Boolean,
        lang: String,
        json: Boolean,
        memberCatalog: org.tatrman.ttr.md.resolve.MemberCatalog? = null,
        warning: (String) -> Unit = {},
        echo: (String) -> Unit,
    ) {
        val source = Files.readString(file)
        val dir = output ?: file.parent
        val warnings = mutableListOf<TtrpDiagnostic>()
        var extraction = DocExtractor.extract(source, file.fileName.toString())

        fun refuse(
            errors: List<DocDiagnostic>,
            exit: Int,
        ): Nothing {
            val diagnostics =
                errors.map { d ->
                    val offset = d.span[0].coerceIn(0, source.length)
                    val prefix = source.substring(0, offset)
                    TtrpDiagnostic(
                        TtrpDiagnosticId.entries.single { it.id == d.id },
                        Severity.ERROR,
                        d.message,
                        SourceLocation(
                            file.toString(),
                            prefix.count { it == '\n' } + 1,
                            offset - (source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1),
                            prefix.count { it == '\n' } + 1,
                            0,
                            offset,
                            d.span[1],
                        ),
                        d.suggestedAlternative,
                    )
                }
            if (json) echo(BuildJson.failure(diagnostics)) else diagnostics.forEach { echo(it.render()) }
            throw ProgramResult(exit)
        }
        if (extraction.diagnostics.isNotEmpty()) refuse(extraction.diagnostics, 1)
        if (extraction.recipes.isEmpty()) {
            refuse(
                listOf(DocDiagnostic("TTRP-DOC-001", "document has no recipe skeleton", listOf(0, 0))),
                1,
            )
        }
        try {
            extraction =
                extraction.copy(
                    recipes =
                        extraction.recipes.map { recipe ->
                            val name = recipe.name ?: file.fileName.toString().removeSuffix(".md")
                            val record =
                                listOf(
                                    file.parent.resolve("$name.annotations.json"),
                                    dir.resolve(recipe.path).resolveSibling("$name.annotations.json"),
                                ).firstOrNull { Files.isRegularFile(it) }
                            if (record ==
                                null
                            ) {
                                recipe
                            } else {
                                val read = AnnotationRecord.read(Files.readString(record), recipe.sidecar.documentSha)
                                warnings +=
                                    read.warnings.map { message ->
                                        TtrpDiagnostic(
                                            TtrpDiagnosticId.DOC_005,
                                            Severity.WARNING,
                                            "$record: $message",
                                            SourceLocation.UNKNOWN.copy(file = file.toString()),
                                        )
                                    }
                                recipe.copy(sidecar = read.record.applyVerdicts(recipe.sidecar))
                            }
                        },
                )
        } catch (e: IllegalArgumentException) {
            refuse(listOf(DocDiagnostic("TTRP-DOC-003", e.message ?: "invalid annotation record", listOf(0, 0))), 3)
        }
        if (!json) warnings.forEach { warning(it.render()) }
        if (check) {
            val drift = extraction.check(dir)
            if (drift.isNotEmpty()) refuse(drift, 3)
        }
        val project = TtrpManifestReader.resolve(file.parent).manifest
        val pipeline =
            org.tatrman.ttrp.graph
                .TtrpPipeline(project, memberCatalog = memberCatalog)
        val results =
            extraction.recipes.map { recipe ->
                BundleAssembler(ToolchainVersion.current).build(
                    recipe.text,
                    dir.resolve(recipe.path).toString(),
                    project,
                    project.modelsRoot(),
                    dir,
                    recipeDir = file.parent,
                    documentTitle = extraction.title,
                    language = lang,
                    memberCatalog = memberCatalog,
                    writeArtifacts = !check,
                    pipeline = pipeline,
                )
            }

        fun readmePath(
            recipe: org.tatrman.ttrp.doc.ExtractedRecipe,
            result: BundleAssembler.BundleResult,
        ): Path =
            (
                if (recipe.name !=
                    null
                ) {
                    dir.resolve(recipe.path).parent
                } else {
                    dir.resolve(result.manifest.recipe!!.name)
                }
            ).resolve("README.md")
        if (check) {
            val drift =
                extraction.recipes.zip(results).flatMap { (recipe, result) ->
                    listOf(readmePath(recipe, result), result.dir.resolve("README.md")).mapNotNull { path ->
                        if (Files.isRegularFile(path) && Files.readString(path) == result.readme) {
                            null
                        } else {
                            DocDiagnostic("TTRP-DOC-003", "derived README drifts: $path; run ttrp build", listOf(0, 0))
                        }
                    }
                }
            if (drift.isNotEmpty()) refuse(drift, 3)
            echo(if (json) BuildJson.failure(warnings) else "${file.fileName}: derived artifacts are current")
            return
        }
        extraction.write(dir)
        extraction.recipes.zip(results).forEach { (recipe, result) ->
            // The explanation is available beside the canonical recipe and in its distributable bundle.
            val readmeDir = readmePath(recipe, result).parent
            Files.createDirectories(readmeDir)
            Files.copy(
                result.dir.resolve("README.md"),
                readmeDir.resolve("README.md"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        if (json) {
            val diagnosticJson =
                (
                    kotlinx.serialization.json.Json.parseToJsonElement(
                        BuildJson.failure(warnings),
                    ) as kotlinx.serialization.json.JsonObject
                ).getValue("diagnostics")
            echo(
                "{\"recipes\":[${results.joinToString(
                    ",",
                ) { BuildJson.success(it) }}],\"diagnostics\":$diagnosticJson}",
            )
        } else {
            results.forEach { echo(it.dir.toString()) }
        }
    }
}
