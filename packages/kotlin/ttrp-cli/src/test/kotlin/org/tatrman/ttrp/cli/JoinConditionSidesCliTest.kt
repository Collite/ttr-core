// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * A join condition's UNQUALIFIED column on the `sql-text` path: it is the left input's column when the
 * left has one of that name (the TTR-P convention `on: x = right.y`), else the right input's. It used
 * to fall to a bare lookup that bound `kód_zboží` (on both sides) to the left's FIRST column and could
 * not find a left-only `stav` at all.
 */
class JoinConditionSidesCliTest :
    FunSpec({
        val program: Path =
            run {
                val rel = Path.of("packages/kotlin/ttrp-frontend/src/test/resources/ttrb-rules/programs/klice.ttrp")
                var dir: Path? = Path.of("").toAbsolutePath()
                while (dir != null) {
                    if (Files.exists(dir.resolve(rel))) return@run dir.resolve(rel)
                    dir = dir.parent
                }
                error("could not locate klice.ttrp")
            }

        fun sql(port: String): String {
            val out = Files.createTempDirectory("ttrp-join-sides")
            val r = TtrpCommand().subcommands(BuildCommand()).test("build $program --out $out")
            withClue(r.output) { r.statusCode shouldBe 0 }
            return Files.readString(out.resolve("klice.bundle/islands/klice.$port.sql"))
        }

        fun on(sql: String): String =
            sql
                .substringAfter(" ON ")
                .lineSequence()
                .first()
                .trim()

        test("a name on both sides is the LEFT input's (on: kód_zboží = right.kód_zboží)") {
            val left = on(sql("oboji")).substringBefore(" = ")
            left shouldContain "\"kód_zboží\""
            left shouldNotContain "číslo"
        }

        test("a left-only name resolves to the left input (on: stav = right.vedoucí_skladu)") {
            on(sql("jen_vlevo")) shouldContain "\"stav\" = "
        }

        test("a right-only unqualified name resolves to the right input (on: poznámka = vedoucí_skladu)") {
            on(sql("jen_vpravo")) shouldContain "\"poznámka\" = "
            on(sql("jen_vpravo")) shouldContain "\"vedoucí_skladu\""
        }
    })
