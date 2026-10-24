// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.tatrman.ttrp.conform.replay.FixtureLoader
import org.tatrman.ttrp.conform.replay.NativeDriver
import org.tatrman.ttrp.conform.replay.ReplayRunner
import java.nio.file.Files
import java.nio.file.Path

object ReplayConformCli {
    fun run(
        path: String,
        cells: List<String>,
        server: String?,
        tokenEnv: String?,
        world: String?,
        out: String?,
        env: Map<String, String> = System.getenv(),
        echo: (String) -> Unit,
    ): Int {
        var native: NativeDriver? = null
        try {
            val fixture = FixtureLoader.load(Path.of(path))
            val report =
                ReplayRunner.run(fixture, cells, world) { cell ->
                    require(
                        cell.substringBefore('.').replace('-', '_') == "native",
                    ) { "no driver for replay cell $cell" }
                    native
                        ?: NativeDriver(
                            requireNotNull(server) {
                                "--server required for pinned replays"
                            },
                            requireNotNull(
                                tokenEnv?.let { env[it] },
                            ) { "--token-env must name a nonempty bearer environment variable" },
                        ).also {
                            native =
                                it
                        }
                }
            echo(report.summary())
            out?.let {
                Files.writeString(
                    Path.of(it),
                    Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report.toJson()) + "\n",
                )
            }
            return report.exitCode
        } catch (e: Exception) {
            echo("ttrp conform --replay: ${e.message}")
            return 2
        } finally {
            native?.close()
        }
    }
}
