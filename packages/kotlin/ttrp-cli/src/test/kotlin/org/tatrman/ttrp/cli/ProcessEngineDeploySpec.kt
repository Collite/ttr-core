// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * AU-export S1.5.2 (D-145, D-170): the `process_engine` target renders the C7 bundle against the executor's egress,
 * `ttrp emit --deploy` posts it to the engine's `deployment/create`, and `--deploy --check` compares the newest
 * deployment of the name with the bundle fingerprint. The engine is a local HTTP fixture speaking the two routes of
 * process-engine `contracts.md` §1 / §1a; no live engine is involved.
 */
class ProcessEngineDeploySpec :
    StringSpec({
        val bearer = "fixture-bearer-7d1c"

        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve(rel)
                if (Files.exists(candidate)) return candidate
                dir = dir.parent
            }
            error("could not locate $rel")
        }
        val fixture = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes/hartland-engine")
        val worldFile = "models/hartland/worlds/demo.ttrm"

        /** The CG-2 engine fixture plus one `def query`, so the bundle carries a DMN skeleton beside the BPMN. */
        fun project(engineUrl: String = "http://127.0.0.1:9/engine-rest"): Path {
            val dir = Files.createTempDirectory("pe-deploy-project")
            Files.walk(fixture).use { paths ->
                paths.forEach { p ->
                    val target = dir.resolve(fixture.relativize(p).toString())
                    if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
                }
            }
            val world = dir.resolve(worldFile)
            val text = Files.readString(world)
            check("http://process-engine.<ns>.svc/engine-rest" in text)
            Files.writeString(world, text.replace("http://process-engine.<ns>.svc/engine-rest", engineUrl))
            Files.createDirectories(dir.resolve("models/hartland/queries"))
            Files.writeString(
                dir.resolve("models/hartland/queries/q_engine.ttrm"),
                """
                package hartland

                model query

                def query placed_orders {
                    language: SQL
                    sourceText: ${"\"\"\""}SELECT so_order_number, so_state FROM sales_order WHERE so_state = 'placed'${"\"\"\""}
                }
                """.trimIndent() + "\n",
            )
            return dir
        }

        fun edit(
            dir: Path,
            before: String,
            after: String,
        ) {
            val path = dir.resolve(worldFile)
            val source = Files.readString(path)
            check(before in source) { "fixture lacks: $before" }
            Files.writeString(path, source.replace(before, after))
        }

        fun emit(
            dir: Path,
            vararg extra: String,
            env: Map<String, String> = mapOf("TTR_BEARER" to bearer),
            deployment: String = "release_event",
        ): CliktCommandTestResult =
            TtrpCommand()
                .subcommands(EmitCommand(env))
                .test(listOf("emit", "--deployment", deployment, "--project", dir.toString()) + extra.toList())

        fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** The bundle fingerprint as `fingerprints.bundle` is written: one file export, read back. */
        fun exported(dir: Path): Path {
            val out = Files.createTempDirectory("pe-export")
            val result = emit(dir, "--out", out.toString())
            withClue(result.output) { result.statusCode shouldBe 0 }
            return out.resolve("release_event.process_engine.export")
        }

        fun bundleFingerprint(folder: Path): String =
            Json
                .parseToJsonElement(Files.readString(folder.resolve("manifest.json")))
                .jsonObject
                .getValue("fingerprints")
                .jsonObject
                .getValue("bundle")
                .jsonPrimitive.content

        "process_engine selects the C7 export against the executor's egress, with start groups and a bundle fingerprint" {
            val folder = exported(project())
            val manifest = Json.parseToJsonElement(Files.readString(folder.resolve("manifest.json"))).jsonObject
            manifest.getValue("target").jsonObject.getValue("type").jsonPrimitive.content shouldBe "process_engine"
            val bpmn = Files.readString(folder.resolve("release_order.c7.bpmn"))
            bpmn shouldContain "camunda:historyTimeToLive=\"30\""
            bpmn shouldContain "camunda:candidateStarterGroups=\"hartland-sales-managers\""
            bpmn shouldContain "http://process-engine-egress/automation/recipes/release_order/runs"
            bpmn shouldNotContain "Authorization"
            bpmn shouldNotContain "TTR_CONN_AUTOMATION"
            val dmn =
                Files.list(folder.resolve("dmn")).use { it.toList() }.map { it.fileName.toString() }
            dmn.size shouldBe 1
            val template =
                Files.list(folder.resolve("templates/c7")).use { it.toList() }.single {
                    it.fileName.toString().contains("placed_orders")
                }
            val templateText = Files.readString(template)
            templateText shouldContain "camunda:Connector"
            templateText shouldContain "http-connector"
            templateText shouldContain "http://process-engine-egress/automation/query"
            templateText shouldNotContain "Authorization"
            // fingerprints.bundle = sha256 over the posted resources in name byte order, each `<name>\n<bytes>`.
            val resources =
                (listOf("release_order.c7.bpmn" to folder.resolve("release_order.c7.bpmn")) +
                    dmn.map { it to folder.resolve("dmn/$it") })
                    .sortedBy { it.first }
            val expected =
                sha256(
                    resources
                        .map { (name, path) -> (name + "\n").toByteArray() + Files.readAllBytes(path) }
                        .reduce { a, b -> a + b },
                )
            bundleFingerprint(folder) shouldBe expected
            val readme = Files.readString(folder.resolve("README.md"))
            readme shouldContain "hartland-sales-managers"
            readme shouldContain "--deploy"
            readme shouldNotContain bearer
        }

        "the export is deterministic, so the bundle fingerprint is stable across runs" {
            val dir = project()
            bundleFingerprint(exported(dir)) shouldBe bundleFingerprint(exported(dir))
        }

        "the retired native executor type refuses by name" {
            val dir = project()
            edit(
                dir,
                "def executor engine {",
                "def executor server { extends: native }\n    def executor engine {",
            )
            edit(
                dir,
                "def deployment release_morning ",
                "def deployment release_server { program: morning_release, target: server, " +
                    "run_as: identity.automation_decider }\n    def deployment release_morning ",
            )
            val result = emit(dir, "--out", Files.createTempDirectory("pe-native").toString(), deployment = "release_server")
            result.statusCode shouldBe 2
            result.output shouldContain "native"
            result.output shouldContain "retired"
            result.output shouldContain "process_engine"
        }

        "startable_by: [] writes no start group and says no role may start it; absent keeps no-restriction" {
            val empty = project()
            edit(empty, "startable_by: role.sales_manager", "startable_by: []")
            val emptyFolder = exported(empty)
            Files.readString(emptyFolder.resolve("release_order.c7.bpmn")) shouldNotContain "candidateStarterGroups"
            Files.readString(emptyFolder.resolve("README.md")) shouldContain "no role may start"
            val absent = project()
            edit(absent, ", startable_by: role.sales_manager", "")
            val absentFolder = exported(absent)
            Files.readString(absentFolder.resolve("release_order.c7.bpmn")) shouldNotContain "candidateStarterGroups"
            Files.readString(absentFolder.resolve("README.md")) shouldContain "No startable_by restriction"
        }

        "a startable_by role without a Keycloak group refuses TTRP-EXPORT-ROLE" {
            val dir = project()
            val automation = dir.resolve("models/hartland/automation/automation.ttrm")
            Files.writeString(
                automation,
                Files.readString(automation).replace(
                    "def role ops            { resolve: keycloak_group \"hartland-ops\" }",
                    "def role ops            { resolve: keycloak_group \"hartland-ops\" }\n" +
                        "def role night_shift    { members: [\"maya\"] }",
                ),
            )
            edit(dir, "startable_by: role.sales_manager", "startable_by: [role.sales_manager, role.night_shift]")
            val result = emit(dir, "--out", Files.createTempDirectory("pe-role").toString())
            result.statusCode shouldBe 2
            result.output shouldContain "TTRP-EXPORT-ROLE"
            result.output shouldContain "night_shift"
        }

        "an engine export without egress_url refuses by name" {
            val dir = project()
            edit(dir, "egress_url: \"http://process-engine-egress/automation\", ", "")
            val result = emit(dir, "--out", Files.createTempDirectory("pe-egress").toString())
            result.statusCode shouldBe 2
            result.output shouldContain "egress_url"
        }

        // ---- the engine fixture: `GET /deployment` + `POST /deployment/create` (process-engine contracts §1a) ----

        class Engine(
            var newest: String = "[]",
            var readStatus: Int = 200,
            var createStatus: Int = 200,
            var createId: String = "dep-new-1",
        ) {
            val requests = mutableListOf<Triple<String, String, ByteArray>>()
            val authorizations = mutableListOf<String?>()
            val contentTypes = mutableListOf<String?>()
            private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

            init {
                server.createContext("/engine-rest/deployment") { ex ->
                    val body = ex.requestBody.readAllBytes()
                    requests += Triple(ex.requestMethod, ex.requestURI.toString(), body)
                    authorizations += ex.requestHeaders.getFirst("Authorization")
                    contentTypes += ex.requestHeaders.getFirst("Content-Type")
                    val (status, answer) =
                        if (ex.requestMethod == "GET") {
                            readStatus to newest
                        } else {
                            createStatus to
                                """{"id":"$createId","name":"release_event","source":"ignored","deploymentTime":"2026-11-07T10:00:00.000+0000"}"""
                        }
                    val bytes = answer.toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(status, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                server.start()
            }

            val url get() = "http://127.0.0.1:${server.address.port}/engine-rest"

            fun creates() = requests.filter { it.first == "POST" }

            fun stop() = server.stop(0)
        }

        fun row(
            id: String,
            source: String?,
        ) = """[{"id":"$id","name":"release_event","source":${source?.let { "\"$it\"" } ?: "null"},"deploymentTime":"2026-11-06T09:00:00.000+0000","tenantId":null}]"""

        /** Parse a `multipart/form-data` body into (name, filename?, bytes). */
        fun parts(
            contentType: String,
            body: ByteArray,
        ): List<Triple<String, String?, ByteArray>> {
            val boundary = contentType.substringAfter("boundary=").trim('"')
            val text = String(body, Charsets.ISO_8859_1)
            return text
                .split("--$boundary")
                .drop(1)
                .filter { !it.startsWith("--") }
                .map { chunk ->
                    val head = chunk.substringAfter("\r\n").substringBefore("\r\n\r\n")
                    val content = chunk.substringAfter("\r\n\r\n").removeSuffix("\r\n")
                    val name = Regex("name=\"([^\"]*)\"").find(head)!!.groupValues[1]
                    val file = Regex("filename=\"([^\"]*)\"").find(head)?.groupValues?.get(1)
                    Triple(name, file, content.toByteArray(Charsets.ISO_8859_1))
                }
        }

        "--deploy posts BPMN and DMN as one multipart deployment with duplicate filtering and the bundle source" {
            val engine = Engine()
            try {
                val dir = project(engine.url)
                val folder = exported(dir)
                val fp = bundleFingerprint(folder)
                val result = emit(dir, "--deploy")
                withClue(result.output) { result.statusCode shouldBe 0 }
                result.output.trim() shouldBe "deployed dep-new-1"
                result.output shouldNotContain bearer
                engine.requests.map { it.first } shouldContainExactly listOf("GET", "POST")
                val read = engine.requests.first().second
                read shouldMatch Regex("/engine-rest/deployment\\?.*")
                val query =
                    read.substringAfter('?').split('&').associate {
                        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
                    }
                query shouldBe
                    mapOf(
                        "name" to "release_event",
                        "sortBy" to "deploymentTime",
                        "sortOrder" to "desc",
                        "maxResults" to "1",
                    )
                val create = engine.creates().single()
                create.second shouldBe "/engine-rest/deployment/create"
                engine.authorizations.forEach { it shouldBe "Bearer $bearer" }
                val form = parts(engine.contentTypes.last()!!, create.third)
                val fields = form.filter { it.second == null }.associate { it.first to String(it.third) }
                fields shouldBe
                    mapOf(
                        "deployment-name" to "release_event",
                        "enable-duplicate-filtering" to "true",
                        "deployment-source" to "ttrp:${fp.take(16)}",
                    )
                val files = form.filter { it.second != null }
                files.map { it.second } shouldBe files.map { it.first }
                val dmnName =
                    Files.list(folder.resolve("dmn")).use { it.toList() }.single().fileName.toString()
                files.map { it.second }.sorted() shouldBe listOf(dmnName, "release_order.c7.bpmn").sorted()
                files.single { it.second == "release_order.c7.bpmn" }.third shouldBe
                    Files.readAllBytes(folder.resolve("release_order.c7.bpmn"))
                files.single { it.second == dmnName }.third shouldBe Files.readAllBytes(folder.resolve("dmn/$dmnName"))
            } finally {
                engine.stop()
            }
        }

        "--deploy without --out writes no export folder; file-only export needs no engine or bearer" {
            val engine = Engine()
            try {
                val dir = project(engine.url)
                val cwdBefore = Files.list(dir).use { it.toList() }.toSet()
                emit(dir, "--deploy").statusCode shouldBe 0
                Files.list(dir).use { it.toList() }.toSet() shouldBe cwdBefore
                val out = Files.createTempDirectory("pe-file-only")
                val fileOnly = emit(dir, "--out", out.toString(), env = emptyMap())
                withClue(fileOnly.output) { fileOnly.statusCode shouldBe 0 }
                engine.requests.size shouldBe 2
            } finally {
                engine.stop()
            }
        }

        "--deploy needs a bearer and refuses before any engine request" {
            val engine = Engine()
            try {
                val result = emit(project(engine.url), "--deploy", env = emptyMap())
                result.statusCode shouldBe 2
                result.output shouldContain "TTR_BEARER"
                engine.requests.shouldBeEmpty()
                val named = emit(project(engine.url), "--deploy", "--token-env", "DEPLOYER_BEARER",
                    env = mapOf("DEPLOYER_BEARER" to "other-bearer"))
                withClue(named.output) { named.statusCode shouldBe 0 }
                engine.authorizations.forEach { it shouldBe "Bearer other-bearer" }
            } finally {
                engine.stop()
            }
        }

        "--deploy refuses a non-engine target: customer Camunda exports stay files" {
            val dir = project()
            edit(dir, "def executor py    { extends: python_client }",
                "def executor py    { extends: python_client }\n    def executor c7 { extends: camunda_7 }")
            edit(dir, "def deployment release_morning ",
                "def deployment release_c7 { program: release_on_placement, target: c7, " +
                    "run_as: identity.automation_decider }\n    def deployment release_morning ")
            val result = emit(dir, "--deploy", deployment = "release_c7")
            result.statusCode shouldBe 2
            result.output shouldContain "process_engine"
        }

        // ---- T4: the nine §1a cases ----

        "check · no deployment of the name → absent, exit 0, read-only" {
            val engine = Engine(newest = "[]")
            try {
                val result = emit(project(engine.url), "--deploy", "--check")
                withClue(result.output) { result.statusCode shouldBe 0 }
                result.output.trim() shouldBe "absent"
                engine.creates().shouldBeEmpty()
            } finally {
                engine.stop()
            }
        }

        "same fingerprint → current: --check exit 0 and --deploy posts nothing" {
            val engine = Engine()
            try {
                val dir = project(engine.url)
                val fp = bundleFingerprint(exported(dir))
                engine.newest = row("dep-7", "ttrp:${fp.take(16)}")
                val check = emit(dir, "--deploy", "--check")
                withClue(check.output) { check.statusCode shouldBe 0 }
                check.output.trim() shouldBe "current dep-7"
                val deploy = emit(dir, "--deploy")
                withClue(deploy.output) { deploy.statusCode shouldBe 0 }
                deploy.output.trim() shouldBe "current dep-7"
                engine.creates().shouldBeEmpty()
            } finally {
                engine.stop()
            }
        }

        "another ttrp fingerprint → stale: --check refuses, --deploy creates a new version" {
            val engine = Engine(createId = "dep-8")
            try {
                val dir = project(engine.url)
                val fp = bundleFingerprint(exported(dir))
                engine.newest = row("dep-7", "ttrp:0123456789abcdef")
                val check = emit(dir, "--deploy", "--check")
                check.statusCode shouldBe 2
                check.output.trim() shouldBe "stale 0123456789abcdef → ${fp.take(16)} dep-7"
                engine.creates().shouldBeEmpty()
                val deploy = emit(dir, "--deploy")
                withClue(deploy.output) { deploy.statusCode shouldBe 0 }
                deploy.output.trim() shouldBe "deployed dep-8"
                engine.creates().size shouldBe 1
            } finally {
                engine.stop()
            }
        }

        "null source and a non-ttrp source → foreign: refused TTRP-DEPLOY-FOREIGN unless --force" {
            for (source in listOf(null, "process application")) {
                val engine = Engine(newest = row("dep-x", source), createId = "dep-forced")
                try {
                    val dir = project(engine.url)
                    val check = emit(dir, "--deploy", "--check")
                    check.statusCode shouldBe 2
                    check.output.trim() shouldBe "foreign dep-x"
                    val refused = emit(dir, "--deploy")
                    refused.statusCode shouldBe 2
                    refused.output shouldContain "TTRP-DEPLOY-FOREIGN"
                    refused.output shouldContain "dep-x"
                    refused.output shouldNotContain "deployed"
                    engine.creates().shouldBeEmpty()
                    val forced = emit(dir, "--deploy", "--force")
                    withClue(forced.output) { forced.statusCode shouldBe 0 }
                    forced.output.trim() shouldBe "deployed dep-forced"
                } finally {
                    engine.stop()
                }
            }
        }

        "401 on the read → TTRP-DEPLOY-ENGINE 401, no success claim, no create" {
            val engine = Engine(readStatus = 401, newest = """{"type":"AuthenticationException"}""")
            try {
                for (flags in listOf(listOf("--deploy", "--check"), listOf("--deploy"))) {
                    val result = emit(project(engine.url), *flags.toTypedArray())
                    result.statusCode shouldBe 2
                    result.output shouldContain "TTRP-DEPLOY-ENGINE 401"
                    result.output shouldNotContain "deployed"
                    result.output shouldNotContain "current"
                    result.output shouldNotContain bearer
                }
                engine.creates().shouldBeEmpty()
            } finally {
                engine.stop()
            }
        }

        "500 on the create → TTRP-DEPLOY-ENGINE 500, never a success claim" {
            val engine = Engine(createStatus = 500)
            try {
                val result = emit(project(engine.url), "--deploy")
                result.statusCode shouldBe 2
                result.output shouldContain "TTRP-DEPLOY-ENGINE 500"
                result.output shouldNotContain "deployed"
            } finally {
                engine.stop()
            }
        }

        "connection refused → TTRP-DEPLOY-ENGINE with the cause, never a success claim" {
            val port = ServerSocket(0).use { it.localPort }
            val result = emit(project("http://127.0.0.1:$port/engine-rest"), "--deploy")
            result.statusCode shouldBe 2
            result.output shouldContain "TTRP-DEPLOY-ENGINE"
            result.output shouldNotContain "deployed"
        }

        "the create answering the existing id (duplicate filtering) is reported as current" {
            val engine = Engine(newest = row("dep-7", "ttrp:0123456789abcdef"), createId = "dep-7")
            try {
                val result = emit(project(engine.url), "--deploy")
                withClue(result.output) { result.statusCode shouldBe 0 }
                result.output.trim() shouldBe "current dep-7"
                engine.creates().size shouldBe 1
            } finally {
                engine.stop()
            }
        }

        "--engine-url overrides the executor's url (a port-forward) and --check without --deploy is a usage error" {
            val engine = Engine()
            try {
                val dir = project("http://127.0.0.1:9/engine-rest")
                val result = emit(dir, "--deploy", "--check", "--engine-url", engine.url)
                withClue(result.output) { result.statusCode shouldBe 0 }
                result.output.trim() shouldBe "absent"
                emit(dir, "--check").statusCode shouldNotBe 0
            } finally {
                engine.stop()
            }
        }
    })
