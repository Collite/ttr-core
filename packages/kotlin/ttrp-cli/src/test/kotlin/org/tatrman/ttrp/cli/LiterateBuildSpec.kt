// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class LiterateBuildSpec :
    FunSpec({
        val root =
            generateSequence(Path.of("").toAbsolutePath()) {
                it.parent
            }.first { Files.isDirectory(it.resolve("packages/kotlin/ttrp-doc")) }
        val dir = Files.createTempDirectory("literate-cli")
        beforeSpec {
            Files.writeString(dir.resolve("modeler.toml"), "[ttrp]\nworld = \"hartland.worlds.demo\"\n")
            Files.createSymbolicLink(
                dir.resolve("models"),
                root.resolve("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model"),
            )
            Files.copy(
                root.resolve("packages/kotlin/ttrp-doc/src/test/resources/docs/releasing-a-placed-order.md"),
                dir.resolve("release.md"),
            )
        }
        afterSpec {
            Files.deleteIfExists(dir.resolve("models"))
            dir.toFile().deleteRecursively()
        }

        fun ttrp() = TtrpCommand().subcommands(BuildCommand())
        test("build markdown, check the committed trio and refuse a changed canonical file with DOC-003") {
            val built = ttrp().test(listOf("build", dir.resolve("release.md").toString(), "--lang", "cs"))
            check(built.statusCode == 0) { built.stdout + built.stderr }
            val canonical = dir.resolve("release_order/release_order.ttrp")
            Files.exists(canonical) shouldBe true
            Files.readString(dir.resolve("release_order/README.md")) shouldContain "## Režim"
            val checked = ttrp().test("build ${dir.resolve("release.md")} --check")
            checked.statusCode shouldBe 0
            Files.writeString(canonical, "changed\n")
            val changed = ttrp().test("build ${dir.resolve("release.md")} --check --json")
            changed.statusCode shouldBe 3
            changed.stdout shouldContain "TTRP-DOC-003"
            Files.readString(canonical) shouldBe "changed\n"
        }
        test("Czech twin keeps canonical bytes and logic fingerprints") {
            val source = Files.readString(dir.resolve("release.md"))
            Files.writeString(
                dir.resolve("release.cs.md"),
                source.replace("# Releasing a placed order", "# Uvolnění objednávky"),
            )
            val en = dir.resolve("en")
            val cs = dir.resolve("cs")
            val first = ttrp().test("build ${dir.resolve("release.md")} --out $en")
            val second = ttrp().test("build ${dir.resolve("release.cs.md")} --out $cs --lang cs")
            check(first.statusCode == 0 && second.statusCode == 0) {
                first.stdout + first.stderr + second.stdout +
                    second.stderr
            }
            Files.readString(en.resolve("release_order/release_order.ttrp")) shouldBe
                Files.readString(cs.resolve("release_order/release_order.ttrp"))

            fun manifest(out: Path) =
                org.tatrman.ttrp.bundle.RunManifest.fromJson(
                    Files.readString(out.resolve("release_order.bundle/manifest.json")),
                )
            manifest(en).recipe!!.fingerprints.text shouldBe manifest(cs).recipe!!.fingerprints.text
        }
        test("unnamed hyphenated EN and CS document stems produce valid, identical recipe identities") {
            val source = Files.readString(dir.resolve("release.md")).replace("recipe: release_order\n", "")
            for (name in listOf("release-order", "release-order.cs")) {
                Files.writeString(dir.resolve("$name.md"), source)
                val result = ttrp().test("build ${dir.resolve("$name.md")} --out ${dir.resolve(name)}")
                check(result.statusCode == 0) { result.stdout + result.stderr }
                val manifest =
                    org.tatrman.ttrp.bundle.RunManifest.fromJson(
                        Files.readString(dir.resolve("$name/release_order.bundle/manifest.json")),
                    )
                manifest.recipe!!.name shouldBe "release_order"
            }
            val en = Files.readString(dir.resolve("release-order/release-order.ttrp"))
            val cs = Files.readString(dir.resolve("release-order.cs/release-order.cs.ttrp"))
            en shouldBe cs
        }
        test("a human Verdict rejects a block-sentence link without changing the canonical recipe") {
            val md = Files.readString(dir.resolve("release.md"))
            val extracted =
                org.tatrman.ttrp.doc.DocExtractor
                    .extract(md, "release.md")
                    .recipes
                    .single()
            val node = extracted.sidecar.nodes.getValue("shortfall")
            val record =
                org.tatrman.ttrp.doc.annotations.AnnotationRecord(
                    "release.md",
                    extracted.sidecar.documentSha,
                    listOf(
                        org.tatrman.ttrp.doc.annotations
                            .Sentence("sentence", node.paragraph, "Stock"),
                        org.tatrman.ttrp.doc.annotations
                            .Block("block", node.span, listOf("shortfall")),
                        org.tatrman.ttrp.doc.annotations
                            .Link("link", node.span, "block", listOf("sentence")),
                        org.tatrman.ttrp.doc.annotations
                            .Verdict("verdict", node.span, "link", false, producer = "human@reviewer"),
                    ),
                )
            Files.writeString(dir.resolve("release_order.annotations.json"), record.toJson())
            val output = dir.resolve("reviewed")
            val built = ttrp().test("build ${dir.resolve("release.md")} --out $output")
            check(built.statusCode == 0) { built.stdout + built.stderr }
            Files.readString(output.resolve("release_order/release_order.ttrp")) shouldBe extracted.text
            val side =
                kotlinx.serialization.json.Json.decodeFromString<org.tatrman.ttrp.doc.DocSidecar>(
                    Files.readString(output.resolve("release_order/release_order.ttrd")),
                )
            side.sentences.single { it.span == node.paragraph }.nodes shouldBe emptyList()
            ttrp().test("build ${dir.resolve("release.md")} --out $output --check").statusCode shouldBe 0
            Files.delete(dir.resolve("release_order.annotations.json"))
        }
        test("unsupported README languages are usage errors for both source formats") {
            for (name in listOf("release.md", "release.ttrp")) {
                if (name.endsWith(".ttrp")) Files.writeString(dir.resolve(name), "recipe {}")
                val result = ttrp().test("build ${dir.resolve(name)} --lang de")
                result.statusCode shouldBe 2
                result.stderr shouldContain "en"
            }
        }
        test("Markdown forwards connected catalogs and reports unavailability as structured failure") {
            val result = ttrp().test("build ${dir.resolve("release.md")} --connected ws://127.0.0.1:1 --json")
            result.statusCode shouldBe 1
            result.stderr shouldContain "--connected"
            kotlinx.serialization.json.Json
                .parseToJsonElement(result.stdout)
        }
        test("Markdown emit refusal uses JSON diagnostics and flags surface their notices") {
            val source =
                Files
                    .readString(dir.resolve("release.md"))
                    .replace(
                        "SELECT o.order_number, o.customer",
                        "SELECT o.order_number, CAST(NULL AS object) AS unsupported, o.customer",
                    )
            Files.writeString(dir.resolve("refusal.md"), source)
            val result = ttrp().test("build ${dir.resolve("refusal.md")} --json --offline")
            result.statusCode shouldBe 1
            result.stdout shouldContain "TTRP-EMT-006"
            result.stderr shouldContain "compile mode = offline"
            kotlinx.serialization.json.Json
                .parseToJsonElement(result.stdout)
        }
        test("invalid fence tag is a located DOC-004 diagnostic") {
            Files.writeString(dir.resolve("bad.md"), "# Bad\n\n```js\n1\n```\n")
            val r = ttrp().test("build ${dir.resolve("bad.md")} --json")
            r.statusCode shouldBe 1
            r.stdout shouldContain "TTRP-DOC-004"
        }
    })
