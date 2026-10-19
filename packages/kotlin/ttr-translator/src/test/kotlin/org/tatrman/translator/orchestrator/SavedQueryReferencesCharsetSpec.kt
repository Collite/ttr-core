// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.FixtureModel
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * [Translator.savedQueryReferences] is a Calcite entry point like the parse: it must promote Calcite's literal charset
 * to UTF-8 before Calcite freezes it. A metadata worker calls it for every saved query before its first parse; without
 * the promotion Calcite stayed on ISO-8859-1 for the process and every later body with a literal outside Latin-1
 * failed. This test JVM pins `calcite.default.charset` (build.gradle.kts), which hides exactly that, so the sequence
 * runs in a JVM of its own ([SavedQueryReferencesCharsetMain]) that inherits no such property.
 */
class SavedQueryReferencesCharsetSpec :
    StringSpec({
        "references first, in a JVM where nothing has loaded Calcite: a later non-Latin-1 literal still parses" {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val process =
                ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    SavedQueryReferencesCharsetMain::class.java.name,
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(2, TimeUnit.MINUTES) shouldBe true
            withClue(output) {
                process.exitValue() shouldBe 0
                output shouldContain "CHARSET_BEFORE null"
                output shouldContain "PARSE OK"
            }
        }
    })

/** References, then a parse of a literal outside Latin-1, as a metadata worker orders them at start-up. */
object SavedQueryReferencesCharsetMain {
    @JvmStatic
    fun main(args: Array<String>) {
        println("CHARSET_BEFORE ${System.getProperty("calcite.default.charset")}")
        val sql = "SELECT id FROM customers WHERE name = 'Poštovné'"
        println("REFS ${Translator.savedQueryReferences(sql, Language.SQL)}")
        when (val result = Translator(FixtureModel.handle()).parseToRelNode(sql, Language.SQL)) {
            is ParseResult.Success -> println("PARSE OK")
            is ParseResult.Failure -> println("PARSE FAILED ${result.message}")
        }
    }
}
