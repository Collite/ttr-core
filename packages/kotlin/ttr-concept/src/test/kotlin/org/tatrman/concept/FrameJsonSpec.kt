// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.tatrman.assertion.v1.Assertion
import org.tatrman.statement.v1.Statement
import java.nio.file.Files
import java.nio.file.Path

/**
 * CN-P3 S3.1 — the frozen frames' JSON mapping (cn contracts §2: protobuf JsonFormat, canonical). Every sample under
 * `resources/frames/` parses strictly (an unknown field or enum value is an error, never dropped) and prints back to
 * the same bytes, so the files ARE the canonical form — the frames the S3.7 goldens feed the service.
 */
class FrameJsonSpec :
    DescribeSpec({
        val dir = DefinitionFixtures.repo.resolve("packages/kotlin/ttr-concept/src/test/resources/frames")
        val parser = JsonFormat.parser()
        val printer = JsonFormat.printer()

        fun roundTrip(
            file: Path,
            builder: Message.Builder,
        ): String {
            parser.merge(Files.readString(file), builder)
            return printer.print(builder.build()) + "\n"
        }

        describe("statement.v1 samples round-trip byte-equal") {
            for (name in listOf("statement-e0", "statement-all-forms", "statement-ambiguous")) {
                it(name) {
                    val file = dir.resolve("$name.json")
                    roundTrip(file, Statement.newBuilder()) shouldBe Files.readString(file)
                }
            }
        }

        describe("assertion.v1 samples round-trip byte-equal") {
            for (name in listOf("assertion-transfer", "assertion-transfer-known")) {
                it(name) {
                    val file = dir.resolve("$name.json")
                    roundTrip(file, Assertion.newBuilder()) shouldBe Files.readString(file)
                }
            }
        }

        describe("the parse is strict") {
            it("an unknown field fails") {
                shouldThrow<InvalidProtocolBufferException> {
                    parser.merge("""{ "ask": "ASK_VALUE", "measure": [] }""", Statement.newBuilder())
                }
            }
            it("an unknown enum value fails") {
                shouldThrow<InvalidProtocolBufferException> {
                    parser.merge("""{ "ask": "ASK_TABLE" }""", Statement.newBuilder())
                }
            }
            it("a misspelled nested field fails") {
                shouldThrow<InvalidProtocolBufferException> {
                    parser.merge(
                        """{ "concept": { "qname": "cn.Transfer" }, "assertedAsof": {} }""",
                        Assertion.newBuilder(),
                    )
                }
            }
        }
    })
