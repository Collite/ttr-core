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
        val queriesRoot = locate("packages/kotlin/ttrp-cli/src/test/resources/fixtures/hartland-queries")
        val stockRoles = locate("packages/kotlin/ttr-semantics/src/main/resources/builtin/cnc-stock-roles.ttr")
        val worldFile = "models/hartland/worlds/demo.ttrm"
        val automationFile = "models/hartland/automation/automation.ttrm"

        /** An export project and, unless refused before building, the recipe bundle `--bundle` passes. */
        class Project(
            val dir: Path,
            val bundle: Path?,
        )

        fun copyTree(
            from: Path,
            to: Path,
        ) = Files.walk(from).use { paths ->
            paths.forEach { p ->
                val target = to.resolve(from.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            }
        }

        fun edit(
            path: Path,
            before: String,
            after: String,
        ) {
            val source = Files.readString(path)
            check(before in source) { "fixture lacks: $before" }
            Files.writeString(path, source.replace(before, after))
        }

        /**
         * The CG-2 engine fixture (post-cut world: `engine { extends: process_engine, url, egress_url }`, H-1
         * `release_event` startable by `role.sales_manager`). Its recipe bundle builds against the fixture's own models;
         * the export project carries the same automation and world over hartland's complete model (the S1.5.1
         * `hartland-queries` fixture, 24 projectable `def query` → 24 DMN skeletons) plus the stock vocabulary, as
         * RecipeExportSpec's Camunda case does. [worldEdits]/[automationEdits] apply to both copies.
         */
        fun project(
            engineUrl: String = "http://127.0.0.1:9/engine-rest",
            worldEdits: List<Pair<String, String>> = emptyList(),
            automationEdits: List<Pair<String, String>> = emptyList(),
            bundle: Boolean = true,
        ): Project {
            val models = Files.createTempDirectory("pe-bundle-models")
            copyTree(fixture, models)
            val edits = listOf("http://process-engine.<ns>.svc/engine-rest" to engineUrl) + worldEdits
            edits.forEach { (a, b) -> edit(models.resolve(worldFile), a, b) }
            automationEdits.forEach { (a, b) -> edit(models.resolve(automationFile), a, b) }
            val dir = Files.createTempDirectory("pe-deploy-project")
            for (folder in listOf("automation", "worlds")) {
                copyTree(models.resolve("models/hartland/$folder"), dir.resolve("models/hartland/$folder"))
            }
            Files.walk(queriesRoot).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".ttrm") }.forEach { path ->
                    val name = queriesRoot.relativize(path).toString().replace('/', '_')
                    Files.copy(path, dir.resolve("models/hartland/$name"))
                }
            }
            // hartland's customer is the TPC-DS dimension keyed `sk` (ttr-demo automation.ttrm); the CG-2 fixture's
            // ER spells it `customer_sk`. The respelling is the export project's only departure from the bundle's copy.
            edit(dir.resolve(automationFile), "type: customer.customer_sk", "type: customer.sk")
            Files.copy(stockRoles, dir.resolve("models/stock.ttrm"))
            Files.createDirectories(dir.resolve("recipes"))
            Files.list(fixture).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".ttrp") }.forEach {
                    Files.copy(it, dir.resolve("recipes").resolve(it.fileName))
                }
            }
            Files.writeString(dir.resolve("modeler.toml"), "[ttrp]\nworld = \"hartland.worlds.demo\"\n")
            val built =
                if (!bundle) {
                    null
                } else {
                    org.tatrman.ttrp.bundle
                        .BundleAssembler("test")
                        .build(
                            source = Files.readString(models.resolve("release_order.ttrp")),
                            fileName = "release_order.ttrp",
                            recipeDir = models,
                            pipelineManifest =
                                org.tatrman.ttrp.project.TtrpManifestReader
                                    .resolve(models)
                                    .manifest,
                            modelsRoot = models.resolve("models"),
                            outDir = Files.createTempDirectory("pe-bundle"),
                        ).dir
                }
            return Project(dir, built)
        }

        fun emit(
            project: Project,
            vararg extra: String,
            env: Map<String, String> = mapOf("TTR_BEARER" to bearer),
            deployment: String = "release_event",
        ): CliktCommandTestResult =
            TtrpCommand()
                .subcommands(EmitCommand(env))
                .test(
                    listOf("emit", "--deployment", deployment, "--project", project.dir.toString()) +
                        (project.bundle?.let { listOf("--bundle", it.toString()) } ?: emptyList()) +
                        extra.toList(),
                )

        fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** The bundle fingerprint as `fingerprints.bundle` is written: one file export, read back. */
        fun exported(dir: Project): Path {
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

        "process_engine selects the C7 export: the executor's egress, start groups and a bundle fingerprint" {
            val folder = exported(project())
            val manifest = Json.parseToJsonElement(Files.readString(folder.resolve("manifest.json"))).jsonObject
            manifest
                .getValue("target")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content shouldBe "process_engine"
            val bpmn = Files.readString(folder.resolve("release_order.c7.bpmn"))
            bpmn shouldContain "camunda:historyTimeToLive=\"30\""
            bpmn shouldContain "camunda:candidateStarterGroups=\"hartland-sales-managers\""
            bpmn shouldContain "http://process-engine-egress/automation/recipes/release_order/runs"
            Files.readString(folder.resolve("templates/c7/release_on_placement.json")) shouldContain
                "http://process-engine-egress/automation/recipes/"
            bpmn shouldNotContain "Authorization"
            bpmn shouldNotContain "TTR_CONN_AUTOMATION"
            val dmn =
                Files
                    .list(folder.resolve("dmn"))
                    .use { it.toList() }
                    .map { it.fileName.toString() }
                    .sorted()
            dmn.size shouldBe 24
            val template =
                folder.resolve("templates/c7/${dmn.first().removeSuffix("_decision.dmn")}.json")
            val templateText = Files.readString(template)
            templateText shouldContain "camunda:Connector"
            templateText shouldContain "http-connector"
            templateText shouldContain "http://process-engine-egress/automation/query"
            templateText shouldNotContain "Authorization"
            // fingerprints.bundle = sha256 over the posted resources in name byte order, each `<name>\n<bytes>`.
            val resources =
                (
                    listOf("release_order.c7.bpmn" to folder.resolve("release_order.c7.bpmn")) +
                        dmn.map { it to folder.resolve("dmn/$it") }
                ).sortedBy { it.first }
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

        "a project without models/ exports from its extra-model-roots, as the checker reads it (hartland's layout)" {
            val project = project()
            Files.move(project.dir.resolve("models"), project.dir.resolve("model"))
            Files.writeString(
                project.dir.resolve("modeler.toml"),
                "[ttrp]\nworld = \"hartland.worlds.demo\"\nextra-model-roots = [\"model\"]\n",
            )
            val out = Files.createTempDirectory("pe-extra-roots")
            val result = emit(project, "--out", out.toString())
            withClue(result.output) { result.statusCode shouldBe 0 }
            Files.exists(out.resolve("release_event.process_engine.export/release_order.c7.bpmn")) shouldBe true
        }

        /**
         * S1.5.4 (D-173): the same project at hartland's served layout — the model by kind under `model/<kind>/<f>`, the
         * automation package and world under `automation/`, one declared `package hartland` that differs from the
         * directories under `[packages] layout`, and no copy of the stock roles (the loader injects them).
         */
        fun servedLayout(
            staged: Project,
            layout: String = "off",
        ): Project {
            val dir = Files.createTempDirectory("pe-served-layout")
            copyTree(queriesRoot, dir.resolve("model"))
            Files.delete(dir.resolve("model/README.md"))
            copyTree(staged.dir.resolve("models/hartland/automation"), dir.resolve("automation"))
            copyTree(staged.dir.resolve("models/hartland/worlds"), dir.resolve("automation/worlds"))
            copyTree(staged.dir.resolve("recipes"), dir.resolve("recipes"))
            Files.writeString(
                dir.resolve("modeler.toml"),
                "[packages]\nlayout = \"$layout\"\n\n[ttrp]\nworld = \"hartland.worlds.demo\"\n" +
                    "extra-model-roots = [\"model\", \"automation\"]\n",
            )
            return Project(dir, staged.bundle)
        }

        /** Every exported file, with the two values a layout changes (the model fingerprint and its derivatives) masked. */
        fun layoutFree(folder: Path): Map<String, String> =
            Files.walk(folder).use { paths ->
                paths.filter { Files.isRegularFile(it) }.toList().associate { path ->
                    val text = Files.readString(path)
                    folder.relativize(path).toString() to
                        when {
                            path.fileName.toString() == "manifest.json" ->
                                text
                                    .replace(Regex("\"model\": \"sha256:[0-9a-f]{64}\""), "\"model\": \"<model>\"")
                                    .replace(
                                        Regex("\"(templates/[^\"]+)\": \"sha256:[0-9a-f]{64}\""),
                                        "\"$1\": \"<t>\"",
                                    ).replace(Regex("\"bundle\": \"[0-9a-f]{64}\""), "\"bundle\": \"<b>\"")
                            path.toString().contains("/templates/") ->
                                text.replace(Regex("\"fingerprint\": \"[0-9a-f]{64}\""), "\"fingerprint\": \"<f>\"")
                            else -> text
                        }
                }
            }

        "hartland's served layout exports H-1 as the staged copy did, except the model fingerprint (D-173)" {
            val staged = project()
            val served = servedLayout(staged)
            val stagedFolder = exported(staged)
            val servedFolder = exported(served)
            val a = layoutFree(stagedFolder)
            val b = layoutFree(servedFolder)
            b.keys shouldBe a.keys
            a.keys.filter { a[it] != b[it] }.shouldBeEmpty()
            // The BPMN and DMN are byte-identical; what moves is the model fingerprint, as it hashes the roots' paths.
            Files.readString(servedFolder.resolve("release_order.c7.bpmn")) shouldBe
                Files.readString(stagedFolder.resolve("release_order.c7.bpmn"))
            Files.readString(servedFolder.resolve("manifest.json")) shouldNotBe
                Files.readString(stagedFolder.resolve("manifest.json"))
        }

        "the served layout under [packages] layout = strict refuses on the declared package, before building" {
            val result =
                emit(
                    servedLayout(project(), layout = "strict"),
                    "--out",
                    Files.createTempDirectory("pe-strict").toString(),
                )
            result.statusCode shouldBe 2
            result.output shouldContain "the model does not load"
            result.output shouldContain "package-mismatch"
            result.output shouldContain "model/er/"
        }

        "the export is deterministic, so the bundle fingerprint is stable across runs" {
            val dir = project()
            bundleFingerprint(exported(dir)) shouldBe bundleFingerprint(exported(dir))
        }

        "the retired native executor type refuses by name" {
            val dir =
                project(
                    worldEdits =
                        listOf(
                            "def executor engine {" to
                                "def executor server { extends: native }\n    def executor engine {",
                            "def deployment release_morning " to
                                "def deployment release_server { program: morning_release, target: server, " +
                                "run_as: identity.automation_decider }\n    def deployment release_morning ",
                        ),
                    bundle = false,
                )
            val result =
                emit(dir, "--out", Files.createTempDirectory("pe-native").toString(), deployment = "release_server")
            result.statusCode shouldBe 2
            result.output shouldContain "native"
            result.output shouldContain "retired"
            result.output shouldContain "process_engine"
        }

        "startable_by: [] writes no start group and says no role may start it; absent keeps no-restriction" {
            val empty = project(worldEdits = listOf("startable_by: role.sales_manager" to "startable_by: []"))
            val emptyFolder = exported(empty)
            Files.readString(emptyFolder.resolve("release_order.c7.bpmn")) shouldNotContain "candidateStarterGroups"
            Files.readString(emptyFolder.resolve("README.md")) shouldContain "no role may start"
            val absent = project(worldEdits = listOf(", startable_by: role.sales_manager" to ""))
            val absentFolder = exported(absent)
            Files.readString(absentFolder.resolve("release_order.c7.bpmn")) shouldNotContain "candidateStarterGroups"
            Files.readString(absentFolder.resolve("README.md")) shouldContain "No startable_by restriction"
        }

        "a startable_by role without a Keycloak group refuses TTRP-EXPORT-ROLE" {
            val dir =
                project(
                    worldEdits =
                        listOf(
                            "startable_by: role.sales_manager" to
                                "startable_by: [role.sales_manager, role.night_shift]",
                        ),
                    automationEdits =
                        listOf(
                            "def role ops            { resolve: keycloak_group \"hartland-ops\" }" to
                                "def role ops            { resolve: keycloak_group \"hartland-ops\" }\n" +
                                "def role night_shift    { members: [\"maya\"] }",
                        ),
                )
            val result = emit(dir, "--out", Files.createTempDirectory("pe-role").toString())
            result.statusCode shouldBe 2
            result.output shouldContain "TTRP-EXPORT-ROLE"
            result.output shouldContain "night_shift"
        }

        "an engine export without egress_url refuses by name" {
            val dir = project(worldEdits = listOf("egress_url: \"http://process-engine-egress/automation\", " to ""))
            val result = emit(dir, "--out", Files.createTempDirectory("pe-egress").toString())
            result.statusCode shouldBe 2
            result.output shouldContain "egress_url"
        }

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

        // ---- the engine fixture: `GET /deployment` + `POST /deployment/create` (process-engine contracts §1a) ----

        /**
         * The engine as CIB seven / Camunda 7.24 answers the two routes (`DeployCmd`, `DefaultDeploymentHandler`,
         * `DeploymentWithDefinitionsDto`). It holds deployments newest last. A create with duplicate filtering compares
         * the posted resources with the newest deployment of the name that has the same source: if every resource matches,
         * it creates nothing and answers the newest deployment of the name, whatever its source, with no deployed
         * definitions. Otherwise it creates a deployment and lists its definitions. [readOverride] replaces every GET body
         * (an error body); [nextReads] replaces the next GETs once each (a read that predates a racing deployer);
         * [afterCreate] runs after a create is answered (a racing deployer before the re-read).
         */
        class Engine(
            var readStatus: Int = 200,
            var createStatus: Int = 200,
            createId: String = "dep-new-1",
        ) {
            inner class Held(
                val id: String,
                val source: String?,
                val resources: Map<String, ByteArray>,
            ) {
                fun json(fresh: Boolean? = null) =
                    buildString {
                        append("""{"links":[],"id":"$id","name":"release_event","source":""")
                        append(source?.let { "\"$it\"" } ?: "null")
                        append(""","deploymentTime":"2026-11-08T07:10:53.326+0000","tenantId":null""")
                        if (fresh != null) {
                            val definition = "release_on_placement:1:$id"
                            val defs = if (fresh) """{"$definition":{"id":"$definition"}}""" else "null"
                            append(""","deployedProcessDefinitions":$defs,"deployedCaseDefinitions":null""")
                            append(""","deployedDecisionDefinitions":${if (fresh) "{}" else "null"}""")
                            append(""","deployedDecisionRequirementsDefinitions":null""")
                        }
                        append("}")
                    }
            }

            val held = mutableListOf<Held>()
            var readOverride: String? = null
            val nextReads = ArrayDeque<String>()
            var afterCreate: (() -> Unit)? = null
            val requests = mutableListOf<Triple<String, String, ByteArray>>()
            val authorizations = mutableListOf<String?>()
            val contentTypes = mutableListOf<String?>()
            private val ids = ArrayDeque(listOf(createId))
            private var serial = 1
            private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

            /** A deployment someone else made before the test: no resources of ours. */
            fun seed(
                id: String,
                source: String?,
            ) {
                held += Held(id, source, emptyMap())
            }

            private fun create(
                contentType: String,
                body: ByteArray,
            ): String {
                val form = parts(contentType, body)
                val fields = form.filter { it.second == null }.associate { it.first to String(it.third) }
                val files = form.filter { it.second != null }.associate { it.second!! to it.third }
                val source = fields["deployment-source"]
                if (fields["enable-duplicate-filtering"] == "true") {
                    val same = held.lastOrNull { it.source == source }
                    val unchanged =
                        same != null &&
                            files.all { (name, bytes) -> same.resources[name]?.contentEquals(bytes) == true }
                    if (unchanged) return held.last().json(fresh = false)
                }
                val made = Held(ids.removeFirstOrNull() ?: "dep-new-${++serial}", source, files)
                held += made
                return made.json(fresh = true)
            }

            init {
                server.createContext("/engine-rest/deployment") { ex ->
                    val body = ex.requestBody.readAllBytes()
                    val contentType = ex.requestHeaders.getFirst("Content-Type")
                    requests += Triple(ex.requestMethod, ex.requestURI.toString(), body)
                    authorizations += ex.requestHeaders.getFirst("Authorization")
                    contentTypes += contentType
                    val (status, answer) =
                        if (ex.requestMethod == "GET") {
                            readStatus to
                                (
                                    readOverride ?: nextReads.removeFirstOrNull()
                                        ?: "[" + (held.lastOrNull()?.json() ?: "") + "]"
                                )
                        } else if (createStatus != 200) {
                            createStatus to """{"type":"ProcessEngineException","message":"refused"}"""
                        } else {
                            (200 to create(contentType, body)).also { afterCreate?.invoke() }
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

        "--deploy posts BPMN and DMN as one multipart deployment, filter off, with the bundle source, then re-reads" {
            val engine = Engine()
            try {
                val dir = project(engine.url)
                val folder = exported(dir)
                val fp = bundleFingerprint(folder)
                val result = emit(dir, "--deploy")
                withClue(result.output) { result.statusCode shouldBe 0 }
                result.output.trim() shouldBe "deployed dep-new-1"
                result.output shouldNotContain bearer
                engine.requests.map { it.first } shouldContainExactly listOf("GET", "POST", "GET")
                engine.requests.last().second shouldBe engine.requests.first().second
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
                val form = parts(engine.contentTypes[1]!!, create.third)
                val fields = form.filter { it.second == null }.associate { it.first to String(it.third) }
                fields shouldBe
                    mapOf(
                        "deployment-name" to "release_event",
                        "enable-duplicate-filtering" to "false",
                        "deployment-source" to "ttrp:${fp.take(16)}",
                    )
                val files = form.filter { it.second != null }
                files.map { it.second } shouldBe files.map { it.first }
                val dmnNames = Files.list(folder.resolve("dmn")).use { it.toList() }.map { it.fileName.toString() }
                dmnNames.size shouldBe 24
                files.map { it.second!! } shouldBe (dmnNames + "release_order.c7.bpmn").sortedBy { it }
                files.single { it.second == "release_order.c7.bpmn" }.third shouldBe
                    Files.readAllBytes(folder.resolve("release_order.c7.bpmn"))
                dmnNames.forEach { name ->
                    files.single { it.second == name }.third shouldBe Files.readAllBytes(folder.resolve("dmn/$name"))
                }
            } finally {
                engine.stop()
            }
        }

        "--deploy without --out writes no export folder; file-only export needs no engine or bearer" {
            val engine = Engine()
            try {
                val dir = project(engine.url)
                val cwdBefore = Files.list(dir.dir).use { it.toList() }.toSet()
                val deployed = emit(dir, "--deploy")
                withClue(deployed.output) { deployed.statusCode shouldBe 0 }
                Files.list(dir.dir).use { it.toList() }.toSet() shouldBe cwdBefore
                Files.exists(Path.of("release_event.process_engine.export")) shouldBe false
                val out = Files.createTempDirectory("pe-file-only")
                val fileOnly = emit(dir, "--out", out.toString(), env = emptyMap())
                withClue(fileOnly.output) { fileOnly.statusCode shouldBe 0 }
                engine.requests.size shouldBe 3
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
                val named =
                    emit(
                        project(engine.url),
                        "--deploy",
                        "--token-env",
                        "DEPLOYER_BEARER",
                        env = mapOf("DEPLOYER_BEARER" to "other-bearer"),
                    )
                withClue(named.output) { named.statusCode shouldBe 0 }
                engine.authorizations.forEach { it shouldBe "Bearer other-bearer" }
            } finally {
                engine.stop()
            }
        }

        "--deploy refuses a non-engine target: customer Camunda exports stay files" {
            val dir =
                project(
                    worldEdits =
                        listOf(
                            "def executor py     { extends: python_client }" to
                                "def executor py     { extends: python_client }\n    def executor c7 { extends: camunda_7 }",
                            "def deployment release_morning " to
                                "def deployment release_c7 { program: release_on_placement, target: c7, " +
                                "run_as: identity.automation_decider }\n    def deployment release_morning ",
                        ),
                )
            val result = emit(dir, "--deploy", deployment = "release_c7")
            result.statusCode shouldBe 2
            result.output shouldContain "process_engine"
        }

        // ---- T4: the nine §1a cases ----

        "check · no deployment of the name → absent, exit 0, read-only" {
            val engine = Engine()
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
                engine.seed("dep-7", "ttrp:${fp.take(16)}")
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
                engine.seed("dep-7", "ttrp:0123456789abcdef")
                val check = emit(dir, "--deploy", "--check")
                check.statusCode shouldBe 2
                check.output.trim() shouldBe "stale 0123456789abcdef → ${fp.take(16)} dep-7"
                engine.creates().shouldBeEmpty()
                val deploy = emit(dir, "--deploy")
                withClue(deploy.output) { deploy.statusCode shouldBe 0 }
                deploy.output.trim() shouldBe "deployed dep-8"
                engine.creates().size shouldBe 1
                emit(dir, "--deploy", "--check").output.trim() shouldBe "current dep-8"
            } finally {
                engine.stop()
            }
        }

        "null source and a non-ttrp source → foreign: refused TTRP-DEPLOY-FOREIGN unless --force" {
            for (source in listOf(null, "process application")) {
                val engine = Engine(createId = "dep-forced").also { it.seed("dep-x", source) }
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
            val engine = Engine(readStatus = 401).also { it.readOverride = """{"type":"AuthenticationException"}""" }
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

        "A → B → A: re-deploying the earlier bundle makes a new version, confirmed newest by the re-read" {
            val engine = Engine()
            try {
                val a = project(engine.url)
                val egressB =
                    listOf(
                        "http://process-engine-egress/automation" to "http://process-engine-egress-b/automation",
                    )
                val b = project(engine.url, worldEdits = egressB)
                val fpA = bundleFingerprint(exported(a)).take(16)
                val fpB = bundleFingerprint(exported(b)).take(16)
                fpA shouldNotBe fpB
                emit(a, "--deploy").output.trim() shouldBe "deployed dep-new-1"
                emit(b, "--deploy").output.trim() shouldBe "deployed dep-new-2"
                emit(a, "--deploy", "--check").output.trim() shouldBe "stale $fpB → $fpA dep-new-2"
                val again = emit(project(engine.url), "--deploy")
                withClue(again.output) { again.statusCode shouldBe 0 }
                again.output.trim() shouldBe "deployed dep-new-3"
                engine.held.map { it.id to it.source } shouldContainExactly
                    listOf("dep-new-1" to "ttrp:$fpA", "dep-new-2" to "ttrp:$fpB", "dep-new-3" to "ttrp:$fpA")
                val check = emit(a, "--deploy", "--check")
                withClue(check.output) { check.statusCode shouldBe 0 }
                check.output.trim() shouldBe "current dep-new-3"
            } finally {
                engine.stop()
            }
        }

        "a racing deployer of the same bundle before the read: two deployments of the name, the re-read decides" {
            val engine = Engine(createId = "dep-race-winner")
            try {
                val dir = project(engine.url)
                emit(dir, "--deploy").output.trim() shouldBe "deployed dep-race-winner"
                engine.nextReads += "[]"
                val raced = emit(dir, "--deploy")
                withClue(raced.output) { raced.statusCode shouldBe 0 }
                raced.output.trim() shouldBe "deployed dep-new-2"
                engine.held.map { it.id } shouldContainExactly listOf("dep-race-winner", "dep-new-2")
            } finally {
                engine.stop()
            }
        }

        "a create the re-read does not confirm (another deployer after it) refuses TTRP-DEPLOY-ENGINE, never current" {
            val engine = Engine(createId = "dep-ours")
            try {
                engine.afterCreate = {
                    engine.seed("dep-racer", "ttrp:0123456789abcdef")
                    engine.afterCreate = null
                }
                val result = emit(project(engine.url), "--deploy")
                withClue(result.output) { result.statusCode shouldBe 2 }
                result.output shouldContain "TTRP-DEPLOY-ENGINE the engine did not make the bundle current"
                result.output shouldContain "dep-racer (ttrp:0123456789abcdef)"
                result.output.lines().none { it.startsWith("current") || it.startsWith("deployed") } shouldBe true
                engine.requests.map { it.first } shouldContainExactly listOf("GET", "POST", "GET")
            } finally {
                engine.stop()
            }
        }

        "a malformed bearer (a pasted newline or space) refuses before any request and is never printed" {
            val engine = Engine()
            try {
                val secret = "fixture-secret-393"
                for ((variable, value) in listOf("TTR_BEARER" to "$secret\n", "DEPLOYER_BEARER" to "$secret tail")) {
                    val flags = if (variable == "TTR_BEARER") emptyArray() else arrayOf("--token-env", variable)
                    for (check in listOf(false, true)) {
                        val result =
                            emit(
                                project(engine.url),
                                "--deploy",
                                *flags,
                                *(if (check) arrayOf("--check") else emptyArray()),
                                env = mapOf(variable to value),
                            )
                        result.statusCode shouldBe 2
                        result.output shouldContain "$variable: the engine bearer is not an RFC 6750 token"
                        result.output shouldNotContain secret
                    }
                }
                engine.requests.shouldBeEmpty()
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
