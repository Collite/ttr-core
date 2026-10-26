// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.extract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.concept.DefinitionFixtures
import java.math.BigDecimal
import java.nio.file.Files
import java.security.MessageDigest

/**
 * review-158 (small item, F7) — [SchemaJson.canonical] prints the bytes Python's
 * `json.dumps(sort_keys=True, indent=2, ensure_ascii=False)` and kantheon's `CanonicalJson` print for one shared sample
 * (`canonical.sample.json`, a byte copy of kantheon's `shared/proto/fixtures/dx/canonical.sample.json`): keys in
 * code-point order (U+FF01 before U+1F642 — a UTF-16 compare puts the emoji's surrogate first), the escapes, non-ASCII
 * verbatim. And a number never prints in exponent form.
 */
class SchemaJsonSpec :
    FunSpec({
        val sample =
            DefinitionFixtures.repo.resolve(
                "packages/kotlin/ttr-concept/src/test/resources/canonical.sample.json",
            )

        test("the shared sample is kantheon's byte for byte") {
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sample)).joinToString("") {
                "%02x".format(it)
            } shouldBe "2fd4666fb03b7ef4ae61b6c0a5c59379172513d3329c8e461e4f42091505db61"
        }

        test("parsed and re-printed, the shared sample is byte-equal to itself") {
            val text = Files.readString(sample)
            SchemaJson.canonical(Json.parseToJsonElement(text)) shouldBe text
        }

        test("keys sort by code point, not by UTF-16 unit") {
            SchemaJson.canonical(
                JsonObject(mapOf("🙂" to JsonPrimitive(2), "！" to JsonPrimitive(1))),
            ) shouldBe "{\n  \"！\": 1,\n  \"🙂\": 2\n}\n"
        }

        test("a number with an exponent prints as its plain numeral") {
            SchemaJson.canonical(
                JsonArray(
                    listOf(
                        JsonPrimitive(BigDecimal("1E-7")),
                        Json.parseToJsonElement("1e-05"),
                        Json.parseToJsonElement("-2.5E+3"),
                        JsonPrimitive(0.01),
                        JsonPrimitive(true),
                    ),
                ),
            ) shouldBe "[\n  0.0000001,\n  0.00001,\n  -2500,\n  0.01,\n  true\n]\n"
        }
    })
