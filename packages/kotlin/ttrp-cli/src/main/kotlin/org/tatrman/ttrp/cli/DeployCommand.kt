// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.tatrman.ttrp.bundle.BundleAssembler
import org.tatrman.ttrp.bundle.BundleTar
import org.tatrman.ttrp.project.TtrpManifestReader
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * `ttr deploy <bundle-dir | file.ttrp> --envelope <env.json> --door <url>` (contracts §13/§15): pack the
 * built F-lite bundle into its verbatim tar, stamp the envelope's `artifact.bundleHash` (and, with a
 * `--sidecar`, `provenance.compileRecord`) from the actual artifact, and multipart-`POST /v1/envelopes`.
 *
 * The CLI is a **client of the §13 envelope CONTRACT, not the door's Kotlin DTO** — it carries the
 * operator-authored envelope through as opaque JSON and injects only the two content-address fields it is
 * authoritative for (the bundle it is shipping determines the hash). The door re-derives and rejects any
 * mismatch (PLT-ENV-001/009), so the stamp is a convenience, not a trust boundary.
 *
 * Exit: 0 accepted (or `--dry-run` ok) · 1 rejected / door error · 2 usage / pre-flight failure.
 */
class DeployCommand : CliktCommand(name = "deploy") {
    override fun help(context: Context) =
        "Pack a built bundle, stamp the envelope's content-address, and deploy it to a Radegast door."

    private val target by argument(help = "a built <program>.bundle dir, or a <program>.ttrp to build first")
    private val envelopePath by option("--envelope", "-e", help = "the deployment envelope (JSON)").required()
    private val door by option("--door", help = "Radegast door base URL, e.g. https://radegast.example").required()
    private val token by option("--token", help = "bearer token (else \$TTR_DEPLOY_TOKEN)")
    private val sidecar by option("--sidecar", help = "the <program>.compile-record.json provenance sidecar (§5)")
    private val dryRun by option("--dry-run", help = "assemble + stamp, print what would be sent; do not POST").flag()

    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }

    override fun run() {
        val bundleDir = resolveBundleDir()
        val tar = BundleTar.pack(bundleDir)
        val bundleHash = BundleTar.sha256(tar)

        val envFile = Path.of(envelopePath).toAbsolutePath()
        if (!Files.isRegularFile(envFile)) {
            echo("ttr deploy: no such envelope: $envelopePath", err = true)
            throw ProgramResult(2)
        }
        if (envFile.toString().endsWith(".yaml") || envFile.toString().endsWith(".yml")) {
            // §13 authors envelopes as YAML; the CLI transports JSON (§15) and does not yet embed a YAML
            // reader. Author/convert to JSON for now — YAML ingest is a tracked follow-up, not a silent parse.
            echo("ttr deploy: envelope must be JSON for now (YAML authoring support is a follow-up)", err = true)
            throw ProgramResult(2)
        }
        val declared =
            try {
                json.parseToJsonElement(Files.readString(envFile)).jsonObject
            } catch (e: Exception) {
                echo("ttr deploy: envelope is not valid JSON: ${e.message}", err = true)
                throw ProgramResult(2)
            }

        val sidecarBytes = sidecar?.let { readSidecar(it) }
        val sidecarHash = sidecarBytes?.let { BundleTar.sha256(it) }
        val stamped = stamp(declared, bundleHash, sidecarHash)
        val envelopeJson = pretty.encodeToString(JsonObject.serializer(), stamped)

        val identity = "${strField(stamped, "name")}@${strField(stamped, "version")}"
        if (dryRun) {
            echo("ttr deploy (dry-run): would deploy $identity to $door/v1/envelopes")
            echo("  bundle: ${tar.size} bytes, $bundleHash")
            sidecarHash?.let { echo("  sidecar: ${sidecarBytes!!.size} bytes, $it") }
            echo(envelopeJson)
            return
        }

        val bearer =
            token ?: System.getenv("TTR_DEPLOY_TOKEN")
                ?: run {
                    echo("ttr deploy: no bearer token (--token or \$TTR_DEPLOY_TOKEN)", err = true)
                    throw ProgramResult(2)
                }
        post(identity, envelopeJson, tar, sidecarBytes, bearer)
    }

    /** A built bundle dir (has `run.sh`), or a `.ttrp` we build first — mirrors `ttrp run`'s resolution. */
    private fun resolveBundleDir(): Path {
        val p = Path.of(target).toAbsolutePath()
        return when {
            Files.isDirectory(p) && Files.exists(p.resolve("run.sh")) -> p
            Files.isRegularFile(p) && p.toString().endsWith(".ttrp") -> {
                val manifestResult = TtrpManifestReader.resolve(p.parent ?: p)
                try {
                    BundleAssembler(ToolchainVersion.current)
                        .build(
                            source = Files.readString(p),
                            fileName = p.toString(),
                            pipelineManifest = manifestResult.manifest,
                            modelsRoot = manifestResult.manifest.modelsRoot(),
                            outDir = p.parent ?: Path.of("."),
                        ).dir
                } catch (e: IllegalArgumentException) {
                    echo("ttr deploy: build failed — ${e.message}", err = true)
                    throw ProgramResult(2)
                }
            }
            else -> {
                echo("ttr deploy: expected a built <program>.bundle dir or a .ttrp file", err = true)
                throw ProgramResult(2)
            }
        }
    }

    private fun readSidecar(path: String): ByteArray {
        val p = Path.of(path).toAbsolutePath()
        if (!Files.isRegularFile(p)) {
            echo("ttr deploy: no such sidecar: $path", err = true)
            throw ProgramResult(2)
        }
        return Files.readAllBytes(p)
    }

    /** Copy the authored envelope, overriding `artifact.bundleHash` (and `provenance.compileRecord`). */
    private fun stamp(
        declared: JsonObject,
        bundleHash: String,
        sidecarHash: String?,
    ): JsonObject =
        buildJsonObject {
            for ((k, v) in declared) {
                if (k == "artifact" || (sidecarHash != null && k == "provenance")) continue
                put(k, v)
            }
            putJsonObject("artifact") { put("bundleHash", bundleHash) }
            if (sidecarHash != null) {
                putJsonObject("provenance") {
                    // Preserve any operator-authored provenance fields (e.g. lockHash), override the sidecar pin.
                    (declared["provenance"] as? JsonObject)?.forEach { (k, v) -> if (k != "compileRecord") put(k, v) }
                    put("compileRecord", sidecarHash)
                }
            }
        }

    private fun post(
        identity: String,
        envelopeJson: String,
        tar: ByteArray,
        sidecarBytes: ByteArray?,
        bearer: String,
    ) {
        val boundary = "----ttrDeployBoundary7f3a2c"
        val body = ByteArrayOutputStream()
        body.formField(boundary, "envelope", "application/json", envelopeJson.toByteArray())
        body.fileField(boundary, "bundle", "bundle.tar", "application/x-tar", tar)
        sidecarBytes?.let { body.fileField(boundary, "sidecar", "compile-record.json", "application/json", it) }
        body.write("--$boundary--\r\n".toByteArray())

        val req =
            HttpRequest
                .newBuilder(URI.create("${door.trimEnd('/')}/v1/envelopes"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Authorization", "Bearer $bearer")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build()

        val resp =
            try {
                HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                echo("ttr deploy: door unreachable at $door — ${e.message}", err = true)
                throw ProgramResult(1)
            }
        renderResponse(identity, resp.statusCode(), resp.body())
    }

    private fun renderResponse(
        identity: String,
        status: Int,
        body: String,
    ) {
        when (status) {
            201 -> {
                echo("ttr deploy: accepted $identity")
                return
            }
            422 -> {
                echo("ttr deploy: $identity rejected", err = true)
                runCatching {
                    json.parseToJsonElement(body).jsonObject["issues"]?.jsonArrayOrNull()?.forEach {
                        val o = it.jsonObject
                        echo("  ${strField(o, "code")}: ${strField(o, "message")}", err = true)
                    }
                }.onFailure { echo("  $body", err = true) }
                throw ProgramResult(1)
            }
            else -> {
                val detail =
                    runCatching {
                        val o = json.parseToJsonElement(body).jsonObject
                        "${strField(o, "code")}: ${strField(o, "message")}"
                    }.getOrDefault(body)
                echo("ttr deploy: door returned $status — $detail", err = true)
                throw ProgramResult(1)
            }
        }
    }

    private fun strField(
        o: JsonObject,
        name: String,
    ): String = o[name]?.jsonPrimitive?.content ?: ""
}

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() = this as? kotlinx.serialization.json.JsonArray

private fun ByteArrayOutputStream.formField(
    boundary: String,
    name: String,
    contentType: String,
    value: ByteArray,
) {
    // No `filename` ⇒ the door reads this as a FormItem (part.value), not a FileItem.
    write(
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\nContent-Type: $contentType\r\n\r\n"
            .toByteArray(),
    )
    write(value)
    write("\r\n".toByteArray())
}

private fun ByteArrayOutputStream.fileField(
    boundary: String,
    name: String,
    filename: String,
    contentType: String,
    value: ByteArray,
) {
    write(
        (
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n" +
                "Content-Type: $contentType\r\n\r\n"
        ).toByteArray(),
    )
    write(value)
    write("\r\n".toByteArray())
}
