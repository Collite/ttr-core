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
        for (value in listOf("-{ x: 1 }", "-[1, 2]")) {
            test("earlier lexer error in $value precedes a resulting unmatched closer") {
                smallStack {
                    val error =
                        shouldThrow<CanonicalYamlException> {
                            CanonicalYaml.fromTtr("model automation\ndef program D {\n a: $value\n}\n", "lexer.ttrm")
                        }
                    error.file shouldBe "lexer.ttrm"
                    error.line shouldBe 3
                    error.column shouldBe 5
                    error.detail shouldBe "token recognition error at: '${value.take(2)}'"
                }
            }
        }
        test("a later lexer error cannot mask the earlier token depth refusal") {
            smallStack {
                val source = ttr(65, false).replace("\n}\n", "\n b: -{ x: 1 }\n}\n")
                val error = shouldThrow<CanonicalYamlException> { CanonicalYaml.fromTtr(source, "later.ttrm") }
                error.file shouldBe "later.ttrm"
                error.line shouldBe 3
                error.column shouldBe 68
                error.detail shouldBe "nesting too deep (limit 64)"
            }
        }
        for (body in listOf(" a: :\n b: -x", " a: : -x")) {
            test("earliest syntax position wins over later lexer errors in $body") {
                val error =
                    shouldThrow<CanonicalYamlException> {
                        CanonicalYaml.fromTtr("model automation\ndef program D {\n$body\n}\n", "syntax.ttrm")
                    }
                error.file shouldBe "syntax.ttrm"
                error.line shouldBe 3
                error.column shouldBe 5
                error.detail.substringBefore(" input") shouldBe if ('\n' in body) "extraneous" else "mismatched"
            }
        }
        test("a later lexer error on the same line cannot mask the token depth refusal") {
            smallStack {
                val value = "[".repeat(65) + "1 -x" + "]".repeat(65)
                val error =
                    shouldThrow<CanonicalYamlException> {
                        CanonicalYaml.fromTtr("model automation\ndef program D {\n a: $value\n}\n", "same-line.ttrm")
                    }
                error.file shouldBe "same-line.ttrm"
                error.line shouldBe 3
                error.column shouldBe 68
                error.detail shouldBe "nesting too deep (limit 64)"
            }
        }
        test("an immediately preceding lexer error uses the same column base as the guard") {
            val error =
                shouldThrow<CanonicalYamlException> {
                    CanonicalYaml.fromTtr("model automation\ndef program D {\n a: 1 ~)\n}\n", "column.ttrm")
                }
            error.file shouldBe "column.ttrm"
            error.line shouldBe 3
            error.column shouldBe 7
            error.detail shouldBe "token recognition error at: '~'"
        }
        for (value in listOf("-[1, 2]", "-{ x: 1 }", "1)")) {
            test("retained calc argument $value reports its actual cause") {
                val source =
                    "ttr: '0.21'\nmodel: md\ndefs:\n" +
                        "  - kind: map\n    id: Convert\n    properties:\n" +
                        "      calc:\n        name: convert\n        args:\n" +
                        "          input: '$value'\n"
                val error = shouldThrow<CanonicalYamlException> { CanonicalYaml.read(source, "calc.yaml") }
                error.file shouldBe "calc.yaml"
                error.line shouldBe 10
                error.column shouldBe 18
                error.detail shouldBe
                    if (value == "1)") "unmatched ')'" else "Calc argument must contain exactly one retained TTR value"
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
        for (depth in listOf(64, 5000, 100_000)) {
            test("retained calc argument depth $depth is guarded on a 1 MiB stack") {
                smallStack {
                    val source =
                        "ttr: '0.21'\nmodel: md\ndefs:\n" +
                            "  - kind: map\n    id: Convert\n    properties:\n" +
                            "      calc:\n        name: convert\n        args:\n" +
                            "          input: '" + "[".repeat(depth) + "1" + "]".repeat(depth) + "'\n"
                    val error =
                        shouldThrow<CanonicalYamlException> {
                            CanonicalYaml.read(source, "calc.yaml")
                        }
                    error.file shouldBe "calc.yaml"
                    // At the token limit itself the later document guard may fire on the owning definition.
                    error.line shouldBe if (depth == 64) 4 else 10
                    error.column shouldBe if (depth == 64) 5 else 18
                    error.detail shouldBe "nesting too deep (limit $limit)"
                }
            }
        }
        val deepTail = "\n a: " + "[".repeat(5000) + "1" + "]".repeat(5000) + "\n}\n"
        val unmatched =
            listOf(
                Triple(
                    "model automation\ndef program D {\n b: 1\n}\n" + "}".repeat(5000) +
                        "\ndef program E {" + deepTail,
                    5,
                    1,
                ),
                Triple("model automation\ndef program D {\n b: [1 " + ")".repeat(5000) + "]" + deepTail, 3, 8),
                Triple("model automation\ndef program D {\n b: (" + ")".repeat(5000) + deepTail, 3, 7),
                Triple("model automation\ndef program D {\n b: f(x: " + ")".repeat(5000) + ")" + deepTail, 3, 11),
                Triple("model automation\ndef program D {\n a: " + "[)".repeat(3000) + "1\n}\n", 3, 6),
                Triple("model automation\ndef program D {\n a: " + "[}".repeat(3000) + "1\n}\n", 3, 6),
            )
        for ((index, vector) in unmatched.withIndex()) {
            test("mismatched bracket vector $index is located before recovery on a 1 MiB stack") {
                smallStack {
                    val (source, line, column) = vector
                    val error =
                        shouldThrow<CanonicalYamlException> {
                            CanonicalYaml.fromTtr(source, "unmatched.ttrm")
                        }
                    error.file shouldBe "unmatched.ttrm"
                    error.line shouldBe line
                    error.column shouldBe column
                    error.detail shouldBe "unmatched '${source.lines()[line - 1][column - 1]}'"
                }
            }
        }
        test("automation object conversion limit differs from native parsing") {
            smallStack {
                fun objects(depth: Int) =
                    "model automation\ndef program D {\n a: " + "{ x: ".repeat(depth) + "1" + " }".repeat(depth) +
                        "\n}\n"
                CanonicalYaml.fromTtr(objects(19)).ast.errors shouldBe emptyList()
                for (depth in listOf(20, 64)) {
                    val error =
                        shouldThrow<CanonicalYamlException> { CanonicalYaml.fromTtr(objects(depth), "objects.ttrm") }
                    error.detail shouldBe "nesting too deep (limit $limit)"
                    error.line shouldBe 3
                    error.column shouldBe if (depth == 20) 100 else 320
                    TtrLoader.parseString(objects(depth), "native.ttrm").errors shouldBe emptyList()
                }
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
