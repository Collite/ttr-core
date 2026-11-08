// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** A `--deploy` refusal: printed as is (it names its code), the CLI's refusal exit code, never a success claim. */
internal class DeployRefusal(
    message: String,
) : RuntimeException(message)

/**
 * The bundle `ttrp emit --deploy` posts and its fingerprint (process-engine `contracts.md` §1a, ✅D-170): the export's
 * top-level BPMN and its `dmn/` files, each posted under its file name. The fingerprint is sha256 over those resources
 * in resource-name byte order, each as `<name>\n<bytes>`; the export records it as `fingerprints.bundle` (full hex),
 * the engine as `deployment-source = ttrp:<first 16 hex>`.
 */
internal object EngineBundle {
    fun resources(files: Map<String, ByteArray>): List<Pair<String, ByteArray>> {
        val posted =
            files
                .filterKeys { path ->
                    (path.endsWith(".bpmn") && '/' !in path) ||
                        (path.startsWith("dmn/") && path.endsWith(".dmn") && path.count { it == '/' } == 1)
                }.map { (path, bytes) -> path.substringAfterLast('/') to bytes }
        require(posted.any { it.first.endsWith(".bpmn") }) { "TTRP-DEPLOY-ENGINE: the export has no BPMN to deploy" }
        require(posted.map { it.first }.distinct().size == posted.size) {
            "TTRP-DEPLOY-ENGINE: two deployed resources share a file name"
        }
        return posted.sortedWith { a, b -> compareBytes(a.first.toByteArray(), b.first.toByteArray()) }
    }

    fun fingerprint(resources: List<Pair<String, ByteArray>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resources.forEach { (name, bytes) ->
            digest.update((name + "\n").toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun source(fingerprint: String) = "ttrp:" + fingerprint.take(16)

    private fun compareBytes(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return a.size - b.size
    }
}

/** §1a's outcome of the newest deployment of the name against this bundle. [line] is the CLI's one output line. */
internal sealed interface DeployCheck {
    val line: String

    data object Absent : DeployCheck {
        override val line = "absent"
    }

    data class Current(
        val id: String,
    ) : DeployCheck {
        override val line = "current $id"
    }

    data class Stale(
        val old: String,
        val new: String,
        val id: String,
    ) : DeployCheck {
        override val line = "stale $old → $new $id"
    }

    data class Foreign(
        val id: String,
    ) : DeployCheck {
        override val line = "foreign $id"
    }

    companion object {
        fun of(
            newest: EngineClient.Deployment?,
            source: String,
        ): DeployCheck =
            when {
                newest == null -> Absent
                newest.source == source -> Current(newest.id)
                newest.source?.startsWith("ttrp:") == true ->
                    Stale(newest.source.removePrefix("ttrp:"), source.removePrefix("ttrp:"), newest.id)
                else -> Foreign(newest.id)
            }
    }
}

/**
 * The two engine routes `--deploy` consumes (process-engine `contracts.md` §1): the newest deployment of a name and the
 * multipart create. Any transport failure or non-2xx answer is `TTRP-DEPLOY-ENGINE <status|cause>`. The bearer goes
 * only into the Authorization header and never into a message; redirects are not followed, so it never reaches another
 * origin.
 */
internal class EngineClient(
    baseUrl: String,
    private val bearer: String,
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
) {
    data class Deployment(
        val id: String,
        val source: String?,
    )

    /**
     * The create's answer. [fresh] is whether the engine made a new deployment: it lists the definitions it deployed
     * only then. When duplicate filtering matches, the engine answers the newest deployment of the name instead, which
     * may carry another bundle's source.
     */
    data class Created(
        val id: String,
        val source: String?,
        val fresh: Boolean,
    )

    init {
        if (!isToken(bearer)) throw DeployRefusal(INVALID_BEARER)
    }

    private val base = baseUrl.trimEnd('/')

    fun newest(name: String): Deployment? {
        val query =
            listOf(
                "name" to name,
                "sortBy" to "deploymentTime",
                "sortOrder" to "desc",
                "maxResults" to "1",
            ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val body =
            send(
                HttpRequest
                    .newBuilder(uri("$base/deployment?$query"))
                    .header("Accept", "application/json")
                    .GET(),
            )
        val rows = parse(body) as? JsonArray ?: throw DeployRefusal("TTRP-DEPLOY-ENGINE malformed deployment list")
        return rows.firstOrNull()?.let { deployment(it) }
    }

    /** Post [resources] as one deployment; the engine's answer (an existing deployment when duplicate filtering hit). */
    fun create(
        name: String,
        source: String,
        resources: List<Pair<String, ByteArray>>,
    ): Created {
        val boundary = "ttrp-deploy-" + source.removePrefix("ttrp:")
        val form = ByteArrayOutputStream()

        fun line(text: String) = form.write((text + "\r\n").toByteArray(Charsets.UTF_8))
        for (
        (field, value) in
        listOf(
            "deployment-name" to name,
            "enable-duplicate-filtering" to "true",
            "deployment-source" to source,
        )
        ) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$field\"")
            line("")
            line(value)
        }
        for ((file, bytes) in resources) {
            require(file.none { it == '"' || it == '\r' || it == '\n' }) { "TTRP-DEPLOY-ENGINE: unsafe resource name" }
            require(!String(bytes, Charsets.ISO_8859_1).contains(boundary)) {
                "TTRP-DEPLOY-ENGINE: resource $file contains the multipart boundary"
            }
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$file\"; filename=\"$file\"")
            line("Content-Type: application/octet-stream")
            line("")
            form.write(bytes)
            line("")
        }
        line("--$boundary--")
        val body =
            send(
                HttpRequest
                    .newBuilder(uri("$base/deployment/create"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(form.toByteArray())),
            )
        val answer = parse(body) as? JsonObject ?: throw DeployRefusal("TTRP-DEPLOY-ENGINE malformed deployment answer")
        val deployed = deployment(answer)
        val fresh = (answer["deployedProcessDefinitions"] as? JsonObject)?.isNotEmpty() == true
        return Created(deployed.id, deployed.source, fresh)
    }

    private fun uri(text: String): URI =
        try {
            URI(text).also { require(it.scheme in setOf("http", "https") && !it.host.isNullOrBlank()) }
        } catch (e: Exception) {
            throw DeployRefusal("TTRP-DEPLOY-ENGINE invalid engine url ${text.substringBefore('?')}")
        }

    private fun send(request: HttpRequest.Builder): String {
        val response =
            try {
                http.send(
                    request.header("Authorization", "Bearer $bearer").timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (e: IllegalArgumentException) {
                // The HTTP client's header check quotes the header value, which may be the bearer: never echo it.
                throw DeployRefusal("TTRP-DEPLOY-ENGINE the HTTP client refused the request (a header value)")
            } catch (e: IOException) {
                throw DeployRefusal("TTRP-DEPLOY-ENGINE ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DeployRefusal("TTRP-DEPLOY-ENGINE interrupted")
            }
        if (response.statusCode() !in 200..299) throw DeployRefusal("TTRP-DEPLOY-ENGINE ${response.statusCode()}")
        return response.body()
    }

    companion object {
        /** RFC 6750 `b64token`: a pasted newline or space is the usual way a bearer fails it. */
        private val TOKEN = Regex("[A-Za-z0-9._~+/-]+=*")

        const val INVALID_BEARER =
            "the engine bearer is not an RFC 6750 token (letters, digits, -._~+/ and trailing =); " +
                "a pasted newline or space is the usual cause; its value is not shown"

        fun isToken(bearer: String) = TOKEN.matches(bearer)
    }

    private fun parse(body: String) =
        try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw DeployRefusal("TTRP-DEPLOY-ENGINE malformed answer")
        }

    private fun deployment(element: kotlinx.serialization.json.JsonElement): Deployment {
        val row = element as? JsonObject ?: throw DeployRefusal("TTRP-DEPLOY-ENGINE malformed deployment")
        val id =
            (row["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: throw DeployRefusal("TTRP-DEPLOY-ENGINE deployment without an id")
        val source =
            when (val value = row["source"]) {
                null, JsonNull -> null
                is JsonPrimitive -> value.contentOrNull
                else -> throw DeployRefusal("TTRP-DEPLOY-ENGINE malformed deployment source")
            }
        return Deployment(id, source)
    }
}
