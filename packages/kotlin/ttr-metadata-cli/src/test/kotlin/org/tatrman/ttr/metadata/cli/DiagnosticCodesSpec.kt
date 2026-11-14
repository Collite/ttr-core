// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** MW S3.5 T5 — the diagnostic-code catalogue is generated from [DiagnosticCodes] and covers every code raised. */
class DiagnosticCodesSpec :
    FunSpec({
        val resource = Path.of("src/main/resources${DiagnosticCodes.RESOURCE}")

        test("the bundled diagnostic-codes.json is the rendering of the code list (TTR_CODES_UPDATE=1 rewrites it)") {
            if (System.getenv("TTR_CODES_UPDATE") == "1") Files.writeString(resource, DiagnosticCodes.render())
            resource.readText() shouldBe DiagnosticCodes.render()
            DiagnosticCodes.resource() shouldBe DiagnosticCodes.render()
        }

        test("every ys/ and xm/ code the engine, the codec and the yaml source raise is catalogued") {
            val code = Regex(""""((?:ys|xm)/[a-z0-9-]+)""")
            val raised =
                listOf("ttr-yaml", "ttr-profile", "ttr-metadata")
                    .flatMap { module ->
                        Files.walk(Path.of("../$module/src/main/kotlin")).use { paths ->
                            paths.filter { it.isRegularFile() && it.extension == "kt" }.toList()
                        }
                    }.flatMap { file -> code.findAll(file.readText()).map { it.groupValues[1] } }
                    .toSortedSet()
            check(raised.size > 10) { "the source scan found only $raised" }
            (raised - DiagnosticCodes.all.map { it.code }.toSet()).shouldBeEmpty()
        }

        test("lint --codes prints the catalogue: code, family, meaning, raised-by; every family is ys or xm") {
            val out = runMetadataCommand(listOf("lint", "--codes"))
            out.exitCode shouldBe 0
            val entries = Json.parseToJsonElement(out.stdout).jsonArray.map { it.jsonObject }
            entries.map { it.keys }.toSet() shouldBe setOf(setOf("code", "family", "meaning", "raisedBy"))
            entries.map { it.getValue("family").jsonPrimitive.content }.toSet() shouldBe setOf("ys", "xm")
            // Contracts §5's list, plus the codec's ys/canonical-invalid.
            entries.map { it.getValue("code").jsonPrimitive.content }.toSet() shouldBe
                setOf(
                    "ys/profile-invalid",
                    "ys/profile-grammar-mismatch",
                    "ys/profile-not-found",
                    "ys/profile-cycle",
                    "ys/unknown-key",
                    "ys/unknown-kind",
                    "ys/profile-conflict",
                    "ys/value-unmapped",
                    "ys/twin-missing",
                    "ys/record-id-missing",
                    "ys/canonical-invalid",
                    "xm/link-unresolved",
                    "xm/link-ambiguous",
                    "xm/overlay-orphan",
                    "xm/generated-stale",
                    "xm/generated-edited",
                    "xm/snapshot-size",
                )
        }
    })
