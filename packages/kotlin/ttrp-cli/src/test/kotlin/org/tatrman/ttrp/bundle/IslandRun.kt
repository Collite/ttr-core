// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs an emitted Python island (Polars / Postgres-ADBC) the way `run.sh` does — `python3 islands/<island>.py`
 * with the bundle dir as the working directory — and reads the Arrow files it wrote back as JSON (schema +
 * rows), so a test asserts on what an engine actually produced, not on the script text.
 *
 * Needs `python3` with `polars` + `pyarrow` (+ `adbc_driver_postgresql` for a Postgres island) — the bash
 * executor manifest's package list; [polarsMissing] names what is absent so a test can skip visibly.
 */
object IslandRun {
    /** One Arrow file read back: its fields as `name → arrow type` and its rows (values as strings / null). */
    data class ArrowFile(
        val fields: List<Pair<String, String>>,
        val rows: List<Map<String, String?>>,
    )

    data class Result(
        val exitCode: Int,
        val output: String,
    )

    private val missing: String? by lazy {
        runCatching {
            val p =
                ProcessBuilder("python3", "-c", "import polars, pyarrow")
                    .redirectErrorStream(true)
                    .start()
            val out = p.inputStream.readBytes().decodeToString()
            if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) null else "python3 polars/pyarrow: $out"
        }.getOrElse { "python3 not runnable: ${it.message}" }
    }

    /** Null when `python3` with `polars` + `pyarrow` is available, else why not. */
    fun polarsMissing(): String? = missing

    /** Runs `python3 <island>` in [bundleDir] (creating `out/` and `staging/`), with [env] added. */
    fun run(
        bundleDir: Path,
        island: String,
        env: Map<String, String> = emptyMap(),
    ): Result {
        Files.createDirectories(bundleDir.resolve("out"))
        Files.createDirectories(bundleDir.resolve("staging"))
        val pb =
            ProcessBuilder("python3", island)
                .directory(bundleDir.toFile())
                .redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor(300, TimeUnit.SECONDS)) { "island $island did not finish" }
        return Result(p.exitValue(), out)
    }

    /** Reads every given Arrow IPC file (paths relative to [bundleDir]) with pyarrow. */
    fun read(
        bundleDir: Path,
        files: List<String>,
    ): Map<String, ArrowFile> {
        val script =
            """
            import json, sys, pyarrow.ipc as ipc
            out = {}
            for f in sys.argv[1:]:
                t = ipc.open_file(f).read_all()
                out[f] = {
                    "fields": [[x.name, str(x.type)] for x in t.schema],
                    "rows": [{k: (None if v is None else str(v)) for k, v in r.items()} for r in t.to_pylist()],
                }
            print(json.dumps(out))
            """.trimIndent()
        val p =
            ProcessBuilder(listOf("python3", "-c", script) + files)
                .directory(bundleDir.toFile())
                .redirectErrorStream(true)
                .start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "reading $files failed:\n$out" }
        val json = Json.parseToJsonElement(out.lines().last { it.isNotBlank() }).jsonObject
        return json.mapValues { (_, v) ->
            val o = v.jsonObject
            ArrowFile(
                fields =
                    o.getValue("fields").jsonArray.map {
                        val pair = it.jsonArray
                        pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content
                    },
                rows =
                    o.getValue("rows").jsonArray.map { r ->
                        (r as JsonObject).mapValues { (_, x) ->
                            x.jsonPrimitive.let { if (it is kotlinx.serialization.json.JsonNull) null else it.content }
                        }
                    },
            )
        }
    }

    /** Concatenates the given Arrow files with `pyarrow.concat_tables` (what a host does); null on success, else the error. */
    fun concatError(
        bundleDir: Path,
        files: List<String>,
    ): String? {
        val script =
            "import sys, pyarrow as pa, pyarrow.ipc as ipc\n" +
                "pa.concat_tables([ipc.open_file(f).read_all() for f in sys.argv[1:]])\n"
        val p =
            ProcessBuilder(listOf("python3", "-c", script) + files)
                .directory(bundleDir.toFile())
                .redirectErrorStream(true)
                .start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "concat did not finish" }
        return if (p.exitValue() == 0) null else out
    }
}
