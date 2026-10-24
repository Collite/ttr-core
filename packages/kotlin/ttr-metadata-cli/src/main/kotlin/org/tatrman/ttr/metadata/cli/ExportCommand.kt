// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.export.*
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tomlj.Toml
import org.tomlj.TomlTable
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class ExportCommand(
    private val output: (CliOutcome) -> Unit,
) : CliktCommand(name = "export") {
    override fun help(context: Context) =
        "Export <root>/model: ttr (canonical), graph.json, defs.json, dot, ttl (skos|owl|both), xmi (ttr-mdg/1). " +
            "Reserved: yaml, ossie, uc, tmdl, pdm, ldm, cdm; xmi ea-erd/1."

    private val root by argument()
    private val models by option("--model").split(",").default(listOf("world"))
    private val format by option("--format").required()
    private val profile by option("--profile")
    private val packages by option("--package").split(",")
    private val areas by option("--area").split(",")
    private val since by option("--since", help = "Previous graph.json path")
    private val out by option("--out", help = "Output directory, including .snapshot").required()
    private val tar by option("--tar", help = "Also write model.tar.gz for a multi-file answer").flag()

    override fun run() {
        val request = ExportRequest(models.toSet(), format, profile, packages?.toSet(), areas?.toSet())
        try {
            Exporter.validate(request)
        } catch (error: IllegalArgumentException) {
            output(CliOutcome(64, "", "${getFormattedHelp()}\nxm/bad-request: ${error.message}\n"))
            return
        }
        output(export(request))
    }

    private fun export(request: ExportRequest): CliOutcome {
        val previous =
            since?.let { path ->
                runCatching {
                    val doc = Json.parseToJsonElement(Files.readString(Path.of(path))).jsonObject
                    require(doc["graph"] == JsonPrimitive("v1")) { "not a graph.json v1 document" }
                    val version = doc.getValue("snapshot").jsonPrimitive.content
                    val ids =
                        doc
                            .getValue("nodes")
                            .jsonArray
                            .map {
                                it.jsonObject
                                    .getValue("id")
                                    .jsonPrimitive.content
                            }.toSet() +
                            doc.getValue("edges").jsonArray.mapNotNull {
                                it.jsonObject["id"]
                                    ?.takeUnless { id ->
                                        id is JsonNull
                                    }?.jsonPrimitive
                                    ?.content
                            }
                    version to ids
                }.getOrElse { return CliOutcome(3, "", "xm/since-unknown: $path\n") }
            }
        return try {
            val checkout = Path.of(root).toAbsolutePath().normalize()
            require(Files.isRegularFile(checkout.resolve("modeler.toml"))) { "no modeler.toml under $checkout" }
            require(Files.isDirectory(checkout.resolve("model"))) { "no model/ under $checkout" }
            val (layout, manifestIssues) = MetadataLoadCli.readManifest(checkout.resolve("modeler.toml"))
            require(
                manifestIssues.none {
                    it.severity == org.tatrman.ttr.metadata.LoadIssue.Severity.ERROR
                },
            ) { "invalid modeler.toml" }
            val loaded =
                MetadataLoader(
                    listOf(
                        BuiltinStockSource(),
                        FileBasedSource("checkout", 100, LocalFsStorage("checkout", checkout.resolve("model"))),
                    ),
                ).load(LoadOptions(strict = true, packageLayout = layout))
            require(loaded.report?.ok == true) { loaded.issues.joinToString("\n") { "${it.category}: ${it.message}" } }
            val model = requireNotNull(loaded.model)
            val set = DefinitionSet.from(loaded.files, model, ExportGrammar.version, model.version.value)
            val result =
                Exporter().export(
                    set,
                    null,
                    request.copy(
                        since = previous?.first,
                        previousIds = previous?.second,
                        connections = readExportConnections(loaded.exportSidecars["connections.toml"]),
                        renames = loaded.exportSidecars["renames.yaml"]?.let(Rename::read).orEmpty(),
                    ),
                )
            when (result) {
                is ExportResult.CannotCarry ->
                    CliOutcome(
                        2,
                        "",
                        "xm/format-cannot-carry: ${models.joinToString(",")} / $format (${result.lossinessId})\n",
                    )
                ExportResult.SinceUnknown -> CliOutcome(3, "", "xm/since-unknown: $since\n")
                ExportResult.NotBuilt -> CliOutcome(64, "", "xm/format-not-built: $format\n")
                else -> {
                    val directory = Path.of(out).toAbsolutePath().normalize()
                    val files =
                        when (result) {
                            is ExportResult.Single -> listOf(result)
                            is ExportResult.Bundle -> result.files
                            else -> error("unreachable")
                        }
                    Files.createDirectories(directory)
                    for (file in files) {
                        val target = directory.resolve(file.name).normalize()
                        require(
                            target.startsWith(directory) && target != directory,
                        ) { "unsafe export path: ${file.name}" }
                        Files.createDirectories(target.parent)
                        Files.write(target, file.bytes)
                    }
                    if (tar && result is ExportResult.Bundle) {
                        GZIPOutputStream(Files.newOutputStream(directory.resolve("model.tar.gz"))).use { gzip ->
                            TarArchiveOutputStream(gzip).use { archive ->
                                archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                                for (file in files) {
                                    val entry = TarArchiveEntry(file.name)
                                    entry.size = file.bytes.size.toLong()
                                    entry.modTime = java.util.Date(0)
                                    archive.putArchiveEntry(entry)
                                    archive.write(file.bytes)
                                    archive.closeArchiveEntry()
                                }
                            }
                        }
                    }
                    Files.writeString(directory.resolve(".snapshot"), "${set.snapshot}\n")
                    CliOutcome(0, "", "")
                }
            }
        } catch (error: Exception) {
            CliOutcome(1, "", "ttr-metadata export: ${error.message}\n")
        }
    }
}

/** Only the public locator contract enters defs.json; credentials never travel. */
internal fun readExportConnections(text: String?): List<ConnectionDescriptor> {
    if (text == null) return emptyList()
    val doc = Toml.parse(text)
    require(!doc.hasErrors()) { "invalid connections.toml: ${doc.errors().first()}" }

    fun table(value: TomlTable): Map<String, Any> =
        value.keySet().associateWith { key ->
            val item = requireNotNull(value.get(key))
            if (item is TomlTable) table(item) else item
        }
    val array = doc.getArray("connection") ?: return emptyList()
    return (0 until array.size()).map { index ->
        val item = array.getTable(index)
        ConnectionDescriptor(
            requireNotNull(item.getString("id")),
            item.getString("database"),
            item.getString("schema"),
            item.getTable("locators")?.let(::table).orEmpty(),
        )
    }
}
