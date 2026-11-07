// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitResult
import org.tatrman.ttrp.emit.spi.RecipeEmitRequest

class Camunda7EmitPlugin : TtrEmitPlugin {
    override val spiRevision = TtrEmitPlugin.SPI_REVISION
    override val targetId = "camunda_7"
    override val supportsRecipes = true

    override fun executorTypeManifest(): String =
        requireNotNull(
            javaClass.getResourceAsStream("/manifests/camunda_7.ttrm"),
        ).bufferedReader().use { it.readText() }

    override fun emit(request: EmitRequest): EmitResult =
        throw UnsupportedOperationException("camunda_7: recipe exporter required")

    override fun emitRecipe(request: RecipeEmitRequest): EmitResult = emitCamunda(request, Dialect.C7)
}

internal fun emitCamunda(
    request: RecipeEmitRequest,
    dialect: Dialect,
    engine: Boolean = false,
): EmitResult {
    val renderer = CamundaRenderer(request, dialect, engine)
    val xml = renderer.render()
    val artifacts = AuthoringArtifacts(request, dialect, engine).files()
    return EmitResult(
        sortedMapOf(
            "${request.graph.recipe.name}.${dialect.suffix}.bpmn" to xml.toByteArray(Charsets.UTF_8),
            "README.md" to
                (
                    renderer.readme() +
                        "\nAuthoring artifacts:\n" + artifacts.keys.sorted().joinToString("\n") { "- `$it`" } +
                        "\n\n" +
                        """
                        Install templates/${dialect.suffix} in the Modeler project's .camunda/element-templates
                        directory, then restart Modeler. Choose the world program or query in the dropdown.
                        Inputs map by parameter name; query outputs expose the typed row columns and row_count
                        as task variables. Configure the governed program/query HTTP task and its variables
                        derivation in Modeler.

                        DMN files are empty DMN 1.3 FIRST tables. Author business rules and the outcome before
                        execution; the export invents no rules. Inline SQL and Golem questions have no generated
                        DMN skeleton.

                        The host reads deployed programs from this world, recipe parameter declarations from
                        .ttrp sources, and query columns from the metadata query compiler. Keep other programs'
                        sources in the project even when --bundle supplies the selected program's verified bundle.
                        Those other sources are unverified: they are not checked against served bundles or approvals,
                        and the model fingerprint covers .ttrm files, not those .ttrp files. Review their defaults
                        (recipe values, without program overrides) before use and re-export after source changes.

                        DMN types use DMN 1.3/FEEL names. C7 may ignore number/time/dateTime typeRef values;
                        map them to engine-supported types when authoring C7 rules. The query compiler surface
                        represents model date/time as dateTime; an explicit SQL CAST preserves date/time.
                        Parameter defaults are authoring suggestions. Review date/time and expression-like text
                        defaults in Modeler before use; program overrides are not applied.

                        Templates bind IO only; configure their implementation in Modeler before deployment.
                        version is ${request.templateVersion} (default 1). Raise --template-version whenever a regenerated
                        template metadata.fingerprint differs: Modeler prefers the highest version of an id.
                        Regenerate into a new export folder and reinstall its templates. Preserve authored DMN
                        rules separately; regeneration creates fresh skeletons. The manifest inventories every
                        exported artifact.
                        """.trimIndent() + "\n" +
                        if (request.world.omitted.isEmpty()) {
                            ""
                        } else {
                            "\nOmitted world queries:\n" +
                                request.world.omitted.joinToString(
                                    "\n",
                                ) { "- ${it.reason}" } +
                                "\n"
                        }
                ).toByteArray(Charsets.UTF_8),
            "conformance.json" to request.conformanceJson.toByteArray(Charsets.UTF_8),
        ).apply { putAll(artifacts) },
    )
}
