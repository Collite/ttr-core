// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * MW S4.2a T1 (a) (✅D-MW-93, S4.2 Found (a)): what a `yaml` load leaves on the heap for the life of the process — the
 * networknt schemas of `model-yaml.schema.json` and `profile.schema.json`, cached statically. Measured in a fresh JVM
 * (the test JVM's caches are warm from other specs): load demo-cs, drop the snapshot, force a full GC, read the used
 * heap. At `grammar/v0.22.8` this was 236 MB on the canonical tree (every `$def` preloaded); the bound is loose — about
 * twice the measured value after the fix — and well under the 256 MB heap the metadata pod ran with.
 */
class SchemaHeapSpec :
    StringSpec({
        "a yaml load of demo-cs leaves the schema caches small (fresh JVM, after a full GC)" {
            for (tree in listOf("canonical", "tree")) {
                val retained = retainedMb(DEMO_CS.resolve(tree))
                withClue("demo-cs/$tree retained $retained MB") { retained shouldBeLessThan BOUND_MB }
            }
        }
    })

/** The loose bound, in MB of used heap after the load and a full GC. */
private const val BOUND_MB = 96

private val DEMO_CS: Path = Path.of("../../../tests/conformance/dialect/demo-cs").toAbsolutePath().normalize()

private fun retainedMb(root: Path): Int {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val process =
        ProcessBuilder(
            java,
            "-XX:+UseSerialGC",
            "-Xmx512m",
            "-cp",
            System.getProperty("java.class.path"),
            YamlHeapProbe::class.java.name,
            root.toString(),
        ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor(5, TimeUnit.MINUTES) shouldBe true
    withClue(output) { process.exitValue() shouldBe 0 }
    return Regex("""retainedMB=(\d+)""").find(output)!!.groupValues[1].toInt()
}

/** The child JVM: one `yaml` load of [args] `[0]`, then the used heap after a full GC, without the snapshot. */
object YamlHeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        check(load(root) > 0) { "nothing loaded from $root" }
        repeat(3) { System.gc() }
        val runtime = Runtime.getRuntime()
        println("retainedMB=${(runtime.totalMemory() - runtime.freeMemory()) shr 20}")
    }

    /** The load in its own frame, so nothing of the snapshot is reachable once it returns. */
    private fun load(root: Path): Int =
        SourceKind.YAML
            .source("probe", 100, LocalFsStorage("probe", root), catalog = ProfileCatalog.read(root).entries)
            .load()
            .loadedFiles
            .size
}
