// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.python.PythonClientEmitPlugin
import org.tatrman.ttrp.emit.spi.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import io.kotest.assertions.throwables.shouldThrow

/** The existing contract HTTP fixture is not a real Server recording; P1C owns that acceptance. */
class PythonClientDriverSpec :
    StringSpec({
        val fixture =
            FixtureLoader.parse(
                """
                recipe: release_order
                as_of: "2026-11-02"
                keys: [4711, 4712]
                expected:
                  verdicts:
                    - {key: 4711, route: release, verdict_reason: ready}
                    - {key: 4712, route: release, verdict_reason: ready}
                  actions:
                    - {key: 4711, action: release, columns: {order: 4711}}
                    - {key: 4712, action: release, columns: {order: 4712}}
                  detail_counts: {4711: {stock: 1}, 4712: {stock: 1}}
                  final_states: {entity: er.entity.order, attr: {state: {4711: released, 4712: released}}}
                pinned_from: {run: prior, at: now, by: test}
                """.trimIndent(),
            )
        val dirs = mutableListOf<java.nio.file.Path>()
        afterSpec { dirs.forEach { it.toFile().deleteRecursively() } }

        fun export(
            mode: String,
            held: Boolean = false,
        ): java.nio.file.Path {
            val graph =
                RecipeGraph(
                    RecipeMeta(
                        "release_order",
                        listOf("order.order_number"),
                        "as_of",
                        emptyList(),
                        emptyMap(),
                        "per_candidate",
                        listOf("sql"),
                    ),
                    ProgramMeta2(
                        "event",
                        if (mode ==
                            "batch"
                        ) {
                            "none"
                        } else {
                            "key"
                        },
                        mode,
                        "event",
                        emptyMap(),
                        emptyMap(),
                    ),
                    DeploymentMeta(
                        "release_py",
                        "py",
                        "python_client",
                        "1",
                        "automation_decider",
                        "recipe",
                        true,
                        false,
                        emptyMap(),
                    ),
                    Roles("candidates", emptyList(), "decision", "exclusive"),
                    listOf(RouteMeta("release", "ready", emptyList(), false, false, false)),
                    emptyList(),
                    mapOf(
                        "release" to
                            ActionMeta(
                                mapOf("order" to "order.order_number"),
                                mapOf("native" to "transition", "python_client" to "door"),
                                if (held) "required" else null,
                                false,
                                null,
                                emptyMap(),
                                null,
                            ),
                    ),
                    RunApiMeta(
                        "/automation",
                        mapOf(
                            "open" to "/recipes/{recipe}/runs",
                            "part" to "/runs/{id}/parts/{part}",
                            "island" to "/runs/{id}/islands/{island}",
                            "evaluate" to "/runs/{id}/evaluate",
                            "status" to "/runs/{id}",
                            "route" to "/runs/{id}/routes/{route}",
                            "detail" to "/runs/{id}/routes/{route}/details/{context}",
                            "perform" to "/runs/{id}/actions/{action}/perform",
                            "outcome" to "/runs/{id}/actions/{action}/outcome",
                            "batch" to "/runs/{id}/batch",
                            "close" to "/runs/{id}/close",
                        ),
                    ),
                )
            val result = PythonClientEmitPlugin().emitRecipe(RecipeEmitRequest(graph, "{}", "stale", "{}"))
            val dir = Files.createTempDirectory("python-driver-").also { dirs.add(it) }
            result.files.forEach { (path, bytes) ->
                Files.createDirectories(dir.resolve(path).parent)
                Files.write(dir.resolve(path), bytes)
            }
            val config =
                Json
                    .parseToJsonElement(
                        result.files.getValue("python-client.json").decodeToString(),
                    ).jsonObject
            Files.writeString(
                dir.resolve("manifest.json"),
                buildJsonObject {
                    put("target", buildJsonObject { put("type", "python_client") })
                    put("calls", config.getValue("callPlan"))
                }.toString(),
            )
            return dir
        }
        "generated service replays per key and batch, including async evaluate, paging, details and actual perform" {
            ContractServer().use { server ->
                val starts = Files.createTempFile("python-starts-", ".txt").also { dirs.add(it) }
                val wrapper = Files.createTempFile("python-uv-", ".sh").also { dirs.add(it) }
                Files.writeString(wrapper, "#!/bin/sh\necho start >> '$starts'\nexec uv \"\$@\"\n")
                wrapper.toFile().setExecutable(true)
                val driver =
                    PythonClientDriver(
                        server.url,
                        "test-token",
                        listOf(export("on_event"), export("batch")),
                        wrapper.toString(),
                    )
                val report =
                    ReplayRunner.run(
                        fixture,
                        listOf("python-client.on-event", "python-client.batch"),
                    ) { driver }
                io.kotest.assertions.withClue(report.summary()) { report.exitCode shouldBe 0 }
                server.opens.size shouldBe 3
                server.opens.forEach {
                    it["asOf"] shouldBe JsonPrimitive("2026-11-02T00:00:00Z")
                    it["dryRun"] shouldBe
                        JsonPrimitive(true)
                }
                server.requests.count { it.endsWith("/perform") } shouldBe 3
                server.performBodies.map { it.getValue("keys").jsonArray.size } shouldBe listOf(1, 1, 2)
                server.performBodies.flatMap { it.getValue("keys").jsonArray }.forEach {
                    it.jsonObject.keys shouldBe setOf("order_number")
                }
                Files.readAllLines(starts).size shouldBe 2
                server.requests.count { it.endsWith("/close") } shouldBe 3
            }
        }
        "dry-run perform refuses performed rows, wrong skip reasons and omitted keys" {
            ContractServer().use { server ->
                server.asyncOnce = false
                val driver = PythonClientDriver(server.url, "test-token", listOf(export("batch")))
                for (fault in listOf("performs", "reason", "omits", "key")) {
                    server.performFault = fault
                    val report = ReplayRunner.run(fixture, listOf("python-client.batch")) { driver }
                    report.exitCode shouldBe 2
                    report.summary() shouldContain "perform effects differ"
                }
            }
        }
        "dry replay requires held only for approval-required actions on native held sinks" {
            ContractServer().use { server ->
                server.asyncOnce = false
                server.heldRelease = true
                val held = PythonClientDriver(server.url, "test-token", listOf(export("batch", held = true)))
                val accepted = ReplayRunner.run(fixture, listOf("python-client.batch")) { held }
                io.kotest.assertions.withClue(accepted.summary()) { accepted.exitCode shouldBe 0 }
                server.performFault = "dry-run"
                val wrongDry = ReplayRunner.run(fixture, listOf("python-client.batch")) { held }
                wrongDry.exitCode shouldBe 2
                wrongDry.summary() shouldContain "perform effects differ"
                server.performFault = null
                val unmarked = PythonClientDriver(server.url, "test-token", listOf(export("batch")))
                val wrongHeld = ReplayRunner.run(fixture, listOf("python-client.batch")) { unmarked }
                wrongHeld.exitCode shouldBe 2
                wrongHeld.summary() shouldContain "perform effects differ"
            }
        }
        "fixture timeout sums each request budget, including one re-open and uv startup" {
            pythonServiceTimeoutSeconds(listOf(60)) shouldBe 420L
            pythonServiceTimeoutSeconds(List(12) { 60L }) shouldBe 4380L
            pythonServiceTimeoutSeconds(listOf(1800)) shouldBe 3900L
            pythonServiceTimeoutSeconds(listOf(60, 600)) shouldBe 1860L
        }
        "typed Action perform without keys uses all rows and the strict Server error shapes" {
            ContractServer().use { server ->
                server.asyncOnce = false
                server.heldRelease = true
                val folder = export("batch", held = true)
                val script =
                    """
                    from release_order_client.client import Client
                    from release_order_client.runtime import Action, ApiError
                    run = Client().open({"none": True}, dry_run=True)
                    run.evaluate()
                    effect = run.actions.release.perform()
                    assert effect["performed"] == []
                    assert effect["skipped"] == [
                        {"key": {"order_number": k}, "reason": "held"} for k in [4711, 4712]]
                    assert run.actions.release.perform([9999])["skipped"] == [
                        {"key": {"order_number": 9999}, "reason": "not-a-key"}]
                    empty = Client().open({"keys": []}, dry_run=True)
                    for invoke in [lambda: Action(run, "absent").perform(),
                                   lambda: empty.actions.release.perform(),
                                   lambda: run.request("POST", "perform", {"keys": [4711]}, action="release")]:
                        try:
                            invoke()
                        except ApiError as error:
                            assert "HTTP 400" in str(error)
                        else:
                            raise AssertionError("Server accepted an invalid perform")
                    """.trimIndent()
                val log = folder.resolve("typed-perform.txt")
                val process =
                    ProcessBuilder("uv", "run", "--no-dev", "--python", "3.13.9", "python", "-c", script)
                        .directory(folder.resolve("worker").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .apply {
                            environment()["TTR_SERVER"] = server.url
                            environment()["TTR_BEARER"] = "test-token"
                        }.start()
                try {
                    process.waitFor(120, TimeUnit.SECONDS) shouldBe true
                    io.kotest.assertions.withClue(Files.readString(log)) { process.exitValue() shouldBe 0 }
                    server.performBodies.first() shouldBe buildJsonObject { }
                } finally {
                    if (process.isAlive) process.destroyForcibly().waitFor()
                }
            }
        }
        "a failed key is labelled and does not stop later requests in the same process" {
            ContractServer().use { server ->
                server.asyncOnce = false
                server.failKey = 4711
                val driver = PythonClientDriver(server.url, "test-token", listOf(export("on_event")))
                val report = ReplayRunner.run(fixture, listOf("python-client.on-event")) { driver }
                report.exitCode shouldBe 2
                report.summary() shouldContain "release_order key 4711: POST open: HTTP 400"
                server.opens.size shouldBe 2
                server.requests.count { it.endsWith("/perform") } shouldBe 1
            }
        }
        "startup failures retain the exit code and Python traceback tail" {
            val folder = export("batch")
            // Corrupt the config after the driver's metadata check, just before Python imports it.
            val wrapper = folder.resolve("break-config.sh")
            Files.writeString(
                wrapper,
                "#!/bin/sh\nprintf 'broken config' > release_order_client/config.json\nexec uv \"\$@\"\n",
            )
            wrapper.toFile().setExecutable(true)
            val error =
                shouldThrow<IllegalStateException> {
                    PythonClientDriver(
                        "http://127.0.0.1",
                        "test-token",
                        listOf(folder),
                        wrapper.toString(),
                    ).run(fixture, "python-client.batch")
                }
            error.message!! shouldContain "Python service failed (exit 1)"
            error.message!! shouldContain "Traceback"
            error.message!! shouldContain "JSONDecodeError"
        }
        "empty and malformed stdout retain bounded stderr with bearer redacted" {
            val folder = export("batch")
            for (output in listOf("", "not-json")) {
                val wrapper = folder.resolve("bad-output.sh")
                Files.writeString(wrapper, "#!/bin/sh\necho 'startup diagnostic test-token' >&2\nprintf '$output'\n")
                wrapper.toFile().setExecutable(true)
                val error =
                    shouldThrow<IllegalStateException> {
                        PythonClientDriver(
                            "http://127.0.0.1",
                            "test-token",
                            listOf(folder),
                            wrapper.toString(),
                        ).run(fixture, "python-client.batch")
                    }
                error.message!! shouldContain "Python service failed (exit 0)"
                error.message!! shouldContain "startup diagnostic [redacted]"
                error.message!! shouldNotContain "test-token"
            }
        }
        "a malformed service envelope retains the service diagnostic on nonzero exit" {
            val folder = export("batch")
            val wrapper = folder.resolve("bad-envelope.sh")
            Files.writeString(wrapper, "#!/bin/sh\nexec uv \"\$@\" <<'ENVELOPE'\n{}\nENVELOPE\n")
            wrapper.toFile().setExecutable(true)
            val error =
                shouldThrow<IllegalStateException> {
                    PythonClientDriver("http://127.0.0.1", "test-token", listOf(folder), wrapper.toString())
                        .run(fixture, "python-client.batch")
                }
            error.message!! shouldContain "Python service failed (exit 2)"
            error.message!! shouldContain "invalid service input or configuration"
        }
        "wrong verdicts disagree; truncated details and denied HTTP fail without bearer disclosure" {
            ContractServer().use { server ->
                val driver = PythonClientDriver(server.url, "test-token", listOf(export("batch")))
                server.wrongReason = true
                ReplayRunner.run(fixture, listOf("python-client.batch")) { driver }.exitCode shouldBe 3
                server.wrongReason = false
                server.truncated = true
                val truncated = ReplayRunner.run(fixture, listOf("python-client.batch")) { driver }
                truncated.exitCode shouldBe 2
                truncated.summary() shouldContain "truncated"
                server.failHttp = true
                val refused = ReplayRunner.run(fixture, listOf("python-client.batch")) { driver }
                refused.exitCode shouldBe 2
                refused.summary() shouldContain "HTTP 401"
                refused.summary() shouldNotContain "test-token"
            }
        }
    })
