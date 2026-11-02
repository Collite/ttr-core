// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** JVM -Xss does not constrain Kotest worker threads: request an explicit 1 MiB stack. */
private fun <T> smallStack(action: () -> T): T {
    val task = FutureTask(Callable(action))
    Thread(null, task, "canonical-depth-1mb", 1024 * 1024L).start()
    return task.get(30, TimeUnit.SECONDS)
}

class CanonicalYamlDepthSpec :
    FunSpec({
        val limit = CanonicalYaml.MAX_NESTING_DEPTH

        fun yaml(
            depth: Int,
            block: Boolean,
        ): String {
            val header =
                "ttr: '0.21'\nmodel: automation\ndefs:\n" +
                    "  - kind: program\n    id: D\n    properties:\n      a:"
            return if (block) {
                header + "\n" + (0 until depth).joinToString("\n") { " ".repeat(8 + 2 * it) + "-" } + " 1\n"
            } else {
                header + " " + "[".repeat(depth) + "1" + "]".repeat(depth) + "\n"
            }
        }

        fun ttr(
            depth: Int,
            block: Boolean,
        ): String {
            val value =
                if (block) {
                    (0 until depth).joinToString("\n") { " ".repeat(it) + "[" } + "1\n" + "]\n".repeat(depth)
                } else {
                    "[".repeat(depth) + "1" + "]".repeat(depth)
                }
            return "model automation\ndef program D {\n a: $value\n}\n"
        }
        for (block in listOf(false, true)) {
            test("YAML ${if (block) "block" else "flow"} boundary is located on a 1 MiB stack") {
                smallStack {
                    // Canonical properties sequence and singleton map add a fifth envelope level.
                    val accepted = CanonicalYaml.read(yaml(limit - 5, block), "depth.yaml")
                    accepted.ast.errors shouldBe emptyList()
                    val bytes = CanonicalYaml.write(accepted)
                    CanonicalYaml.write(CanonicalYaml.read(bytes)) shouldBe bytes
                    CanonicalYaml.write(CanonicalYaml.fromTtr(CanonicalYaml.toTtr(accepted))) shouldBe bytes
                    val error =
                        shouldThrow<CanonicalYamlException> {
                            CanonicalYaml.read(yaml(limit - 4, block), "depth.yaml")
                        }
                    error.file shouldBe "depth.yaml"
                    error.detail shouldBe "nesting too deep (limit $limit)"
                    error.line shouldBe if (block) 8 + limit - 5 else 7
                    error.column shouldBe if (block) 9 + 2 * (limit - 5) else 10 + limit - 5
                }
            }
            test("TTR ${if (block) "multiline" else "flow"} boundary is located on a 1 MiB stack") {
                smallStack {
                    val accepted = CanonicalYaml.fromTtr(ttr(limit - 5, block), "depth.ttrm")
                    accepted.ast.errors shouldBe emptyList()
                    val bytes = CanonicalYaml.write(accepted)
                    CanonicalYaml.write(CanonicalYaml.read(bytes)) shouldBe bytes
                    val error =
                        shouldThrow<CanonicalYamlException> {
                            CanonicalYaml.fromTtr(ttr(limit - 4, block), "depth.ttrm")
                        }
                    error.file shouldBe "depth.ttrm"
                    error.detail shouldBe "nesting too deep (limit $limit)"
                    error.line shouldBe if (block) 3 + limit - 5 else 3
                    error.column shouldBe if (block) limit - 4 else 5 + limit - 5
                    // Native parsing has no codec resource limit; this is intentionally a codec-only refusal.
                    TtrLoader.parseString(ttr(limit - 4, block), "native.ttrm").errors shouldBe emptyList()
                }
            }
        }
        test("native token guard refuses deep input before recursive parsing on a 1 MiB stack") {
            smallStack {
                val error =
                    shouldThrow<CanonicalYamlException> {
                        CanonicalYaml.fromTtr(ttr(1000, false), "deep.ttrm")
                    }
                error.line shouldBe 3
                error.column shouldBe 68
                error.detail shouldBe "nesting too deep (limit 64)"
            }
        }
        test("streaming guard refuses 100,000 levels before composition or recursion") {
            val request =
                obj(
                    "operation" to text("migrate"),
                    "from" to text("0.21"),
                    "to" to text("0.21"),
                    "source" to text(yaml(100_000, false)),
                )
            val stdout = java.io.ByteArrayOutputStream()
            val stderr = java.io.ByteArrayOutputStream()
            YamlMigrationCli.run(request.toString().byteInputStream(), stdout, stderr) shouldBe 2
            stdout.size() shouldBe 0
            stderr.toString() shouldBe "<yaml>:7:70: nesting too deep (limit 64)\n"
        }
    })
