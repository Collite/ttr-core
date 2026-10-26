// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.extract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.tatrman.concept.PuritySpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.toPath

/**
 * DX-P0 S0.3 T5 — the CN-P3 S3.8 pattern applied to `org.tatrman.concept.extract`: no JDBC or DML, no I/O, no clock
 * or randomness (the module-wide [PuritySpec] rules), and nothing referenced beyond ttr-concept itself, the
 * ttr-metadata model, kotlinx-serialization's JSON tree and the JDK's lang/util/math/digest — so the renderer stays a
 * library function Pinakes can call on a model snapshot; and twenty renderings, over two independent loads, are
 * byte-identical.
 */
class ExtractPuritySpec :
    FunSpec({
        val root: Path =
            SchemaRenderer::class.java.protectionDomain.codeSource.location
                .toURI()
                .toPath()
                .resolve("org/tatrman/concept/extract")
        val classes =
            Files
                .walk(root)
                .use { s -> s.filter { it.isRegularFile() && it.extension == "class" }.sorted().toList() }
                .map { PuritySpec.ClassRefs.read(Files.readAllBytes(it)) }

        val allowed =
            listOf(
                "org/tatrman/concept/",
                "org/tatrman/ttr/metadata/model/",
                "kotlinx/serialization/json/",
                "kotlin/",
                "java/lang/",
                "java/util/",
                "java/math/",
                "java/security/MessageDigest",
                "java/nio/charset/",
            )

        test("no JDBC, no DML, no I/O, no clock") {
            classes.size shouldBeGreaterThan 3
            classes
                .flatMap {
                    PuritySpec.WRITES.hits(
                        it,
                    ) + PuritySpec.IO.hits(it) + PuritySpec.CLOCK.hits(it)
                }.shouldBeEmpty()
        }

        test("references nothing outside ttr-concept, the metadata model, the JSON tree and the JDK basics") {
            val owners = classes.flatMap { c -> c.members.map { c.name to it.substringBefore('.') } }.distinct()
            owners.filterNot { (_, owner) -> allowed.any { owner.startsWith(it) } }.shouldBeEmpty()
        }

        test("twenty renderings over two independent loads are byte-identical") {
            val first = SchemaRenderer(ExtractWorlds.model())
            val second = SchemaRenderer(ExtractWorlds.model())
            for (kind in TypeKind.entries) {
                for (entity in listOf("review", "claim", "product_sheet")) {
                    val texts =
                        (1..20).map { i ->
                            SchemaJson.canonical(
                                (
                                    if (i % 2 ==
                                        0
                                    ) {
                                        first
                                    } else {
                                        second
                                    }
                                ).render(entity, kind),
                            )
                        }
                    texts.distinct().size shouldBe 1
                }
            }
        }
    })
