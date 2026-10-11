// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Rules written in Czech / English TTR-B sentences (AG B4) through the shipped commands: they
 * `ttrp check` clean, `ttrp explain` the same as the hand-written canonical program (modulo generated
 * names), and `ttrp build` a bundle whose host statements and action-display entries are IDENTICAL
 * to the canonical program's.
 */
class TtrbRulesCliTest :
    FunSpec({
        val programs: Path =
            run {
                val rel = Path.of("packages/kotlin/ttrp-frontend/src/test/resources/ttrb-rules/programs")
                var dir: Path? = Path.of("").toAbsolutePath()
                while (dir != null) {
                    if (Files.isDirectory(dir.resolve(rel))) return@run dir.resolve(rel)
                    dir = dir.parent
                }
                error("could not locate the ttrb-rules fixture programs")
            }

        fun ttrp() = TtrpCommand().subcommands(BuildCommand(), CheckCommand(), ExplainCommand())

        /** Labels (`name#k`, `~n`) renamed in order of first appearance; the header (file path) dropped. */
        fun explain(program: String): String {
            val r = ttrp().test("explain ${programs.resolve(program)}")
            withClue(r.output) { r.statusCode shouldBe 0 }
            val body =
                r.stdout
                    .lines()
                    .drop(1)
                    .joinToString("\n")
            val label = Regex("(?<![\\p{L}\\p{N}_#~])(~\\d+|[\\p{L}_][\\p{L}\\p{N}_]*#\\d+)(?![\\p{L}\\p{N}_#])")
            val seen = LinkedHashMap<String, String>()
            return label.replace(body) { m -> seen.getOrPut(m.value) { "L${seen.size + 1}" } }
        }

        fun build(program: String): Path {
            val out = Files.createTempDirectory("ttrp-rules")
            val r = ttrp().test("build ${programs.resolve(program)} --out $out")
            withClue(r.output) { r.statusCode shouldBe 0 }
            return out.resolve(program.substringAfterLast('/').removeSuffix(".ttrp") + ".bundle")
        }

        fun statements(bundle: Path): Map<String, String> =
            Files.list(bundle.resolve("islands")).use { s -> s.toList() }.associate { it.name to Files.readString(it) }

        fun displays(bundle: Path): List<JsonElement> =
            Json
                .parseToJsonElement(Files.readString(bundle.resolve("manifest.json")))
                .jsonObject
                .getValue("displays")
                .jsonArray

        for (p in listOf(
            "rozhodnuti-cs",
            "rozhodnuti-en",
            "rozhodnuti-soubor",
            "sklad-cs",
            "sklad-en",
            "predani-cs",
            "predani-en",
            "predani-soubor",
        )) {
            test("check: $p.ttrp exits 0 with no diagnostics") {
                val r = ttrp().test("check ${programs.resolve("$p.ttrp")}")
                withClue(r.output) { r.statusCode shouldBe 0 }
                r.stdout.trim() shouldBe ""
            }
        }

        val scenarios =
            mapOf(
                "rozhodnuti" to listOf("cs", "en"),
                "sklad" to listOf("cs", "en"),
                "predani" to listOf("cs", "en", "soubor"),
                // optional join, have / have no match (semi / anti), `is [not] empty` in a join condition
                "odklad" to listOf("cs", "en"),
                // joins on a modelled relation (inner and optional)
                "vazba" to listOf("cs", "en"),
                // a conditional value (case when … then … else … end), `je prázdný` in its condition
                "priorita" to listOf("cs", "en"),
                // grouped conditions + named intermediate values (`Pojmenuj to jako …`, `Načti <jméno>`)
                "nazvy" to listOf("cs", "en"),
            )
        for ((scenario, langs) in scenarios) {
            for (lang in langs) {
                test("explain: $scenario-$lang.ttrp ≡ $scenario-canonical.ttrp, modulo generated names") {
                    explain("$scenario-$lang.ttrp") shouldBe explain("$scenario-canonical.ttrp")
                }

                test("build: $scenario-$lang.ttrp runs the same host statements + action displays as the canonical") {
                    val rules = build("$scenario-$lang.ttrp")
                    val canonical = build("$scenario-canonical.ttrp")
                    statements(rules) shouldBe statements(canonical)
                    displays(rules) shouldBe displays(canonical)
                }
            }
        }

        test("B5 build: the file-backed decision runs the same host statements + displays as the canonical") {
            val file = build("rozhodnuti-soubor.ttrp")
            val canonical = build("rozhodnuti-canonical.ttrp")
            statements(file) shouldBe statements(canonical)
            displays(file) shouldBe displays(canonical)
        }

        test("bare: the rules file run as a bare program builds the canonical's host statements") {
            // The same file a program pulls in with `from "rules/rozhodnuti.ttrb-cs"` is a bare program on
            // its own: its derived in-ports are fed by program-level model loads, which a host statement
            // reads as the model object.
            val bare = build("rules/rozhodnuti.ttrb-cs")
            statements(bare) shouldBe statements(build("rozhodnuti-canonical.ttrp"))
        }

        test("B5 explain: the file-backed decision ≡ canonical, modulo generated names") {
            explain("rozhodnuti-soubor.ttrp") shouldBe explain("rozhodnuti-canonical.ttrp")
        }

        test("B5 check: a reject inside a fragment file is reported at the file's own line") {
            val r = ttrp().test("check ${programs.resolve("negative/soubor-vadny.ttrp")}")
            r.statusCode shouldBe 1
            r.stdout shouldContain "rules/vadne.ttrb-cs:3:"
        }

        test("check: a sentence whose result nothing reads is TTRP-B-113, an error that fails the check") {
            val r = ttrp().test("check ${programs.resolve("negative/nepouzito.ttrp")}")
            r.statusCode shouldBe 1
            r.stdout shouldContain "nepouzito.ttrp:8:0 TTRP-B-113"
            r.stdout shouldContain "`Ponech`"
        }

        test("build: the decision bundle routes each action to its display, held to the imported schema") {
            val bundle = build("rozhodnuti-cs.ttrp")
            statements(bundle).keys.sorted() shouldContainExactly
                listOf("rozhodnuti.send_email.sql", "rozhodnuti.update_field.sql")
            val manifest = Files.readString(bundle.resolve("manifest.json"))
            manifest shouldContain "\"schema\": \"shop.akce.send_email\""
            manifest shouldContain "\"schema\": \"shop.akce.update_field\""
            statements(bundle).getValue("rozhodnuti.send_email.sql") shouldContain "'oddělení:obchod'"
            statements(bundle).getValue("rozhodnuti.send_email.sql") shouldContain "'faktura;dodací_list'"
        }
    })
