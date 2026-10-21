// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.lsp.project.ProjectContext
import org.tatrman.ttrp.lsp.project.ProjectResolver
import org.tatrman.ttrp.lsp.protocol.ValidateDiagnostic
import org.tatrman.ttrp.lsp.protocol.ValidateParams
import org.tatrman.ttrp.lsp.test.TtrpLspHarness
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

/**
 * AU-core-P2 · S2.4 T5 — `ttrp/validate` for a recipe (the LSP/Studio generate→validate→repair loop): the candidate
 * runs through the whole recipe checker, not the front half alone, and every finding names the recipe block it sits
 * in. Driven over H-1 (`release_order`): as written it validates clean; with `hold_ar`'s wirings dropped the one
 * finding is REC-003 on the route, in `verdict`; with a misspelled action column it is REC-001, in `actions`.
 */
class RecipeValidateSpec :
    StringSpec({
        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val c = dir.resolve(rel)
                if (Files.exists(c)) return c
                dir = dir.parent
            }
            error("could not locate $rel")
        }

        val fixtures = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes")
        val h1 = Files.readString(locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes/release_order.ttrp"))
        val hartland =
            ProjectResolver {
                ProjectContext(
                    manifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures),
                    modelsRoot = fixtures.resolve("model"),
                    manifestDiagnostics = emptyList(),
                )
            }

        fun validate(source: String): List<ValidateDiagnostic> =
            TtrpLspHarness(hartland).use { h ->
                h.initialize()
                h.remote
                    .validate(ValidateParams(source = source, uri = "file:///release_order.ttrp"))
                    .get()
                    .diagnostics
            }

        fun errors(source: String) = validate(source).filter { it.severity == "error" }

        "H-1 as written validates clean through the recipe checker" {
            errors(h1).shouldBeEmpty()
        }

        "a route left unwired: REC-003 on the route, in the decision's block" {
            val broken =
                h1
                    .lines()
                    .filterNot { it.trimStart().startsWith("verdict.hold_ar ") }
                    .joinToString("\n")
            val e = errors(broken).single()
            e.code shouldBe "TTRP-REC-003"
            e.block shouldBe "verdict"
            h1.lines()[e.line - 1] shouldContain "hold_ar:"
        }

        "a misspelled action column: REC-001 on the wiring, in `actions`" {
            val broken =
                h1.replace(
                    "hold    { order: order_number, department: \"AR\" }",
                    "hold    { order: order_number, departmnt: \"AR\" }",
                )
            (broken != h1) shouldBe true
            val e = errors(broken).first { it.code == "TTRP-REC-001" }
            e.block shouldBe "actions"
            broken.lines()[e.line - 1] shouldContain "departmnt"
        }

        // review-119 M5: the loop never reads "clean" for a recipe the build cannot bundle
        "a fragment column no row carries: REC-080 in the context's block — what `ttrp build` refuses, once" {
            val broken = h1.replace("i.status = 'open'", "i.statuss = 'open'")
            (broken != h1) shouldBe true
            val e = errors(broken).single()
            e.code shouldBe "TTRP-REC-080"
            e.block shouldBe "overdue"
            e.message shouldContain "statuss"
        }

        "a `turn` island: REC-080 — a recipe bundle runs SQL statements only" {
            val turn =
                h1.replace(
                    Regex(
                        """context overdue\(in orders, out rows\) target erp ""${'"'}sql.*?""${'"'}""",
                        RegexOption.DOT_MATCHES_ALL,
                    ),
                    "context overdue(in orders, out rows) turn agents.care ask \"Any overdue invoices?\" answer care_answer",
                )
            (turn != h1) shouldBe true
            // the decision reading it is not reported again
            errors(turn).single().let {
                it.code shouldBe "TTRP-REC-080"
                it.block shouldBe "overdue"
            }
        }

        "a program (no recipe) validates as before: no block on its findings" {
            val program = "uses world \"hartland.worlds.demo\"\nx = load(nope)\n"
            validate(program).forEach { it.block shouldBe null }
        }
    })
