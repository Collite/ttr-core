// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.tatrman.ttr.metadata.export.GeneratedHeader
import org.tatrman.ttr.writer.TtrRenderer
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** MW S3.6 T1/T3 — `xm/generated-stale` and `xm/generated-edited` (contracts §6) on the T6 fixtures. */
class GeneratedGuardSpec :
    FunSpec({
        val fixtures = Path.of("../../../tests/conformance/guard/demo-cs-links").toAbsolutePath().normalize()

        /** A case copied whole (tree beside model), so a spec may change it. */
        fun case(name: String): Path {
            val to = Files.createTempDirectory("s36-guard-$name")
            val from = fixtures.resolve(name)
            Files.walk(from).use { paths -> paths.filter { it.isRegularFile() }.toList() }.forEach {
                to
                    .resolve(from.relativize(it).toString())
                    .also { file ->
                        file.parent.createDirectories()
                    }.writeText(it.readText())
            }
            return to
        }

        fun guard(case: Path) = GeneratedGuard.check(case.resolve("model"), "t")

        fun codes(issues: List<LoadWarning>) = issues.map { it.message.removePrefix("dropped: ").substringBefore(':') }

        test("a pristine generated package raises neither code") {
            guard(case("pristine")).shouldBeEmpty()
        }

        test("stale: an input's hash moved, at each generated file's head; inputs not found are stale too") {
            val stale = guard(case("stale"))
            codes(stale).toSet() shouldBe setOf("xm/generated-stale")
            stale.map { it.file.substringAfter("/model/") }.toSet() shouldBe
                setOf("demo/binding.yaml", "demo/er.yaml", "prodej/er.yaml")
            stale.all { it.line == 1 && it.column == 1 } shouldBe true
            val gone = case("pristine")
            Files
                .walk(
                    gone.resolve("tree"),
                ).use { it.sorted(Comparator.reverseOrder()).toList() }
                .forEach { it.deleteExisting() }
            guard(gone).map { it.message }.toSet() shouldBe
                setOf(
                    "xm/generated-stale: input not found: ../../tree",
                    "xm/generated-stale: input not found: ../../tree/profiles/overlay-inline.yaml",
                )
        }

        test("edited: the body differs from a fresh generation; the header itself mangled is edited too") {
            val edited = guard(case("edited"))
            edited.map { it.file.substringAfter("/model/") to it.message } shouldBe
                listOf("demo/er.yaml" to "xm/generated-edited: the body differs from a fresh generation of its inputs")
            val mangled = case("pristine")
            val er = mangled.resolve("model/demo/er.yaml")
            er.writeText(er.readText().replaceFirst(" — do not edit", ""))
            guard(mangled).map { it.message } shouldBe
                listOf("xm/generated-edited: the generated-from header is not one ttr-core writes")
            // A generated file whose package/model its inputs no longer generate is edited, not silently passed.
            val moved = case("pristine")
            val prodej = moved.resolve("model/prodej/er.yaml")
            prodej.writeText(prodej.readText().replace("package: \"prodej\"", "package: \"jinde\""))
            guard(moved).map { it.message } shouldBe
                listOf("xm/generated-edited: its inputs generate no document with this package and model")
        }

        test("orphan overlay: stale at the heads, and xm/overlay-orphan at the overlay entry that names no record") {
            val orphan = guard(case("orphan-overlay"))
            codes(orphan).toSet() shouldBe setOf("xm/generated-stale", "xm/overlay-orphan")
            val at = orphan.first { it.message.startsWith("xm/overlay-orphan") && "dropped" !in it.message }
            at.file shouldEndWith "tree/profiles/overlay-inline.yaml"
            at.line shouldBe 5
            at.message shouldContain "demo.er.entity.Zrusena"
        }

        test("a generated .ttrm is checked in its own form: the document rendered") {
            val ttrm = case("pristine")
            val yaml = ttrm.resolve("model/demo/er.yaml")
            val text = yaml.readText()
            val header = GeneratedHeader.read(text)!!
            val rendered = TtrRenderer.render(CanonicalYaml.read(GeneratedHeader.body(text), "$yaml").ast)
            yaml.deleteExisting()
            val file = ttrm.resolve("model/demo/er.ttrm")
            file.writeText(header.line(GeneratedHeader.Form.TTRM) + rendered)
            guard(ttrm).shouldBeEmpty()
            file.writeText(
                header.line(GeneratedHeader.Form.TTRM) + rendered.replace("Objednávka (overlay)", "Objednávka (ručně)"),
            )
            guard(ttrm).map { it.message } shouldBe
                listOf("xm/generated-edited: the body differs from a fresh generation of its inputs")
        }

        test("agents/ is never read, however a file there claims to be generated (✅MW-44)") {
            val agents = case("pristine")
            agents
                .resolve(
                    "model/agents/x.yaml",
                ).also { it.parent.createDirectories() }
                .writeText("# generated-from: bogus\n")
            guard(agents).shouldBeEmpty()
        }
    })
