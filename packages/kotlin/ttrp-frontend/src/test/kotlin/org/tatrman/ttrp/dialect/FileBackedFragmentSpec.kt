// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.dialect.b.TtrbCorpus
import org.tatrman.ttrp.dialect.b.TtrbRules
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.project.TtrpManifestReader
import org.tatrman.ttrp.resolve.TtrpChecker
import java.nio.file.Files

/**
 * B5 — file-backed fragments: `container x(…) target <engine> from "rules/x.ttrb-cs"`. The file's
 * whole content IS the fragment interior (byte-preserved), resolved relative to the program; its
 * dialect comes from the file's extension / first-line marker. Embedded and file-backed lower to
 * the same statements.
 */
class FileBackedFragmentSpec :
    StringSpec({
        val programs = TtrbRules.project().resolve("programs")

        fun parse(rel: String) =
            programs.resolve(rel).let { TtrpParser.parseString(Files.readString(it), it.toString()) }

        fun body(rel: String): FragmentBody =
            parse(rel)
                .document.statements
                .filterIsInstance<ContainerDecl>()
                .single()
                .body as FragmentBody

        "the fragment file is read relative to the program, byte-preserved, its dialect from the extension" {
            val parsed = parse("rozhodnuti-soubor.ttrp")
            parsed.diagnostics.shouldBeEmpty()
            val b = body("rozhodnuti-soubor.ttrp")
            b.tag shouldBe "ttrb-cs"
            b.sourceFile shouldBe "rules/rozhodnuti.ttrb-cs"
            b.sourceText shouldBe Files.readString(programs.resolve("rules/rozhodnuti.ttrb-cs"))
            b.interiorLocation.file shouldEndWith "rules/rozhodnuti.ttrb-cs"
            b.decomposition shouldNotBe null
        }

        "embedded ≡ file-backed: the same lowered statements and action outputs" {
            val embedded = body("rozhodnuti-cs.ttrp").decomposition!!
            val file = body("rozhodnuti-soubor.ttrp").decomposition!!
            TtrbCorpus.canonical(file.statements) shouldBe TtrbCorpus.canonical(embedded.statements)
            file.actionOutputs.map { it.port to it.display } shouldBe
                embedded.actionOutputs.map { it.port to it.display }
        }

        "the file-backed program checks clean" {
            val manifest = TtrpManifestReader.resolve(TtrbRules.project()).manifest
            val file = programs.resolve("rozhodnuti-soubor.ttrp")
            TtrpChecker(
                manifest,
                manifest.modelsRoot(),
            ).check(Files.readString(file), file.toString()).diagnostics.shouldBeEmpty()
        }

        "a missing fragment file is TTRP-FRG-004 at the `from` clause" {
            val parsed = parse("negative/soubor-chybi.ttrp")
            val d = parsed.diagnostics.single { it.id.id == "TTRP-FRG-004" }
            d.location.line shouldBe 2
            d.message.contains("rules/neexistuje.ttrb-cs") shouldBe true
        }

        "a fragment file with no dialect marker is TTRP-FRG-005" {
            parse("negative/soubor-neznamy.ttrp").diagnostics.map { it.id.id } shouldContain "TTRP-FRG-005"
        }

        "a reject inside the fragment file is reported at the FILE's own line" {
            val d = parse("negative/soubor-vadny.ttrp").diagnostics.single()
            d.id.id shouldStartWith "TTRP-B-"
            d.location.file shouldEndWith "rules/vadne.ttrb-cs"
            d.location.line shouldBe 3
        }

        "an in-memory program cannot resolve a relative fragment file (TTRP-FRG-004)" {
            val src = "container r target erp from \"rules/rozhodnuti.ttrb-cs\"\n"
            TtrpParser.parseString(src).diagnostics.map { it.id.id } shouldContain "TTRP-FRG-004"
        }

        "`from` stays usable as a name (a soft keyword)" {
            TtrpParser.parseString("from = load(erp.accounts)\nfrom -> display(x)\n").diagnostics.shouldBeEmpty()
        }
    })
