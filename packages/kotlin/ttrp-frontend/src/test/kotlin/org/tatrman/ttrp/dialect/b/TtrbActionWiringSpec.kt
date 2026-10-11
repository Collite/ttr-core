// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.PortKind
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.project.TtrpManifestReader
import org.tatrman.ttrp.resolve.TtrpChecker
import java.nio.file.Files
import java.nio.file.Path

/**
 * B4 — an action sentence is a sink: the checked program gains one container OUT port per action
 * and the program-level wiring `<container>.<port> -> display(<kind>)`, exactly what a hand-written
 * canonical program declares; the action display check (TTRP-DSP) then holds the rows to the
 * imported row schema. The PARSE (what the formatter / graph edits read) is not rewritten.
 */
class TtrbActionWiringSpec :
    StringSpec({
        val project: Path = TtrbRules.project()
        val manifest = TtrpManifestReader.resolve(project).manifest

        fun check(rel: String): TtrpChecker.Report {
            val file = project.resolve("programs/$rel")
            return TtrpChecker(manifest, manifest.modelsRoot()).check(Files.readString(file), file.toString())
        }

        fun wiring(r: TtrpChecker.Report): List<String> =
            r.document.statements
                .filterIsInstance<ChainStmt>()
                .filter { (it.chain.elements.last() as? OpCall)?.name == "display" }
                .map { s ->
                    val ref = s.chain.elements[0] as DottedRef
                    val display = s.chain.elements[1] as OpCall
                    ref.parts.joinToString(".") + " -> " + display.name + "(" +
                        ((display.args.single().value as ExprArg).expr as ColumnRef).column + ")"
                }

        for (rel in listOf("rozhodnuti-cs.ttrp", "rozhodnuti-en.ttrp", "sklad-cs.ttrp", "sklad-en.ttrp")) {
            "$rel checks clean (no diagnostics at all)" {
                check(rel).diagnostics.shouldBeEmpty()
            }
        }

        "the decision container gains one OUT port per action, wired to display(<kind>) at program level" {
            val r = check("rozhodnuti-cs.ttrp")
            val c =
                r.document.statements
                    .filterIsInstance<ContainerDecl>()
                    .single()
            c.ports.filter { it.kind == PortKind.OUT }.map { it.name } shouldContainExactly
                listOf("send_email", "update_field")
            c.ports.all { it.synthesized } shouldBe true
            wiring(r) shouldContainExactly
                listOf(
                    "rozhodnuti.send_email -> display(send_email)",
                    "rozhodnuti.update_field -> display(update_field)",
                )
            r.document.statements
                .filterIsInstance<ChainStmt>()
                .all { it.synthesized } shouldBe true
        }

        "the action displays are recognised as action displays (held to shop.akce.*)" {
            check("rozhodnuti-cs.ttrp")
                .displaySchemas.values
                .map { it.qualifiedName }
                .sorted() shouldContainExactly
                listOf("shop.akce.send_email", "shop.akce.update_field")
        }

        "the ports and wiring equal the hand-written canonical program's" {
            val canonical = check("rozhodnuti-canonical.ttrp")
            val c =
                canonical.document.statements
                    .filterIsInstance<ContainerDecl>()
                    .single()
            val b =
                check("rozhodnuti-cs.ttrp")
                    .document.statements
                    .filterIsInstance<ContainerDecl>()
                    .single()
            b.ports.map { it.kind to it.name } shouldBe c.ports.map { it.kind to it.name }
            wiring(check("rozhodnuti-en.ttrp")) shouldBe wiring(canonical)
        }

        "a declared OUT port named like an action is reused, not duplicated" {
            val src =
                "uses world \"shop.worlds.host\"\nimport shop.prodej.*\nimport shop.akce.*\n" +
                    "container r(out send_email) target erp \"\"\"ttrb\nLoad objednávky.\n" +
                    "Send an e-mail to department \"obchod\" with subject \"S\", template \"t\" and key číslo.\n\"\"\"\n"
            val r =
                TtrpChecker(
                    manifest,
                    manifest.modelsRoot(),
                ).check(src, project.resolve("programs/x.ttrp").toString())
            r.errors.shouldBeEmpty()
            val c =
                r.document.statements
                    .filterIsInstance<ContainerDecl>()
                    .single()
            c.ports.map { it.name } shouldContainExactly listOf("send_email")
            c.ports.single().synthesized shouldBe false
        }

        "the schema check holds an action to the imported schema — TTRP-DSP-001 at the e-mail sentence" {
            val r = check("negative/prisne.ttrp")
            val d = r.diagnostics.single { it.id == TtrpDiagnosticId.DSP_001 }
            d.severity shouldBe Severity.ERROR
            d.message.contains("kopie") shouldBe true
            d.location.line shouldBe 8 // the `Pošli e-mail …` line inside the fragment
        }

        "a bare *.ttrb-cs rules file: the actions are the outputs — no `out result` / default display added" {
            val bare =
                manifest.copy(bareTarget = "erp", defaultImports = listOf("shop.prodej.*", "shop.akce.*"))
            val src =
                "# ttr: dialect=b lang=cs\nNačti objednávky.\nKdyž částka je větší než 1000:\n" +
                    "    Pošli e-mail oddělení \"obchod\" s předmětem \"S\", šablonou \"t\" a klíčem číslo.\n"
            val r =
                TtrpChecker(
                    bare,
                    bare.modelsRoot(),
                ).check(src, project.resolve("programs/velke.ttrb-cs").toString())
            r.errors.shouldBeEmpty()
            val c =
                r.document.statements
                    .filterIsInstance<ContainerDecl>()
                    .single()
            c.ports.filter { it.kind == PortKind.OUT }.map { it.name } shouldContainExactly listOf("send_email")
            wiring(r) shouldContainExactly listOf("velke.send_email -> display(send_email)")
        }

        "the parse alone is not rewritten (the formatter and graph edits see the authored text)" {
            val src = Files.readString(project.resolve("programs/rozhodnuti-cs.ttrp"))
            val doc = TtrpParser.parseString(src, "rozhodnuti-cs.ttrp").document
            doc.statements
                .filterIsInstance<ContainerDecl>()
                .single()
                .ports
                .shouldBeEmpty()
            doc.statements.filterIsInstance<ChainStmt>().shouldBeEmpty()
        }
    })

/** Locates the `ttrb-rules` fixture project (a neutral Czech-named shop model + action schemas). */
object TtrbRules {
    fun project(): Path {
        val rel = Path.of("packages/kotlin/ttrp-frontend/src/test/resources/ttrb-rules")
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve(rel))) return dir.resolve(rel)
            val local = dir.resolve("src/test/resources/ttrb-rules")
            if (Files.isDirectory(local)) return local
            dir = dir.parent
        }
        error("could not locate the ttrb-rules fixture from ${Path.of("").toAbsolutePath()}")
    }
}
