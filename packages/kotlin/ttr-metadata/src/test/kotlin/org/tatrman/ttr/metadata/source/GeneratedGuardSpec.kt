// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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

        /** A tree of [files] under [at], applied as `profile apply` with [profile] writes it to `<at>/../model`. */
        fun applied(
            at: Path,
            files: Map<String, String>,
            profile: String? = null,
        ): Path {
            files.forEach { (name, text) -> at.resolve(name).also { it.parent.createDirectories() }.writeText(text) }
            val model = at.resolveSibling("model")
            val catalog = ProfileCatalog.read(at).entries
            val result = ProfileApply.run(LocalFsStorage("t", at), profile, catalog, "t", HeaderOptions(model, "1"))
            result.problems.shouldBeEmpty()
            result.files.forEach { (name, text) ->
                model.resolve(name).also { it.parent.createDirectories() }.writeText(text)
            }
            return model
        }

        fun profile(
            id: String,
            pkg: String,
        ) = "profile: $id\ngrammar: '>=0.22 <1.0'\noperations:\n" +
            "  - {op: package, mapping: {}, default: $pkg}\n  - {op: kinds, at: /entity, mapping: {entita: entity}}\n"

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

        test("mixed declarations: each file's own profile, as apply grouped them, is green (✅R277-A)") {
            val at = Files.createTempDirectory("s36-mixed").resolve("tree")
            val model =
                applied(
                    at,
                    mapOf(
                        "profiles/x.yaml" to profile("demo-x/1", "px"),
                        "profiles/y.yaml" to profile("demo-y/1", "py"),
                        "ttr.yaml" to "profile: ./profiles/y.yaml\ngrammar: '>=0.22 <1.0'\n",
                        "model.ignore" to "profiles/\n",
                        "a.yaml" to "entity:\n  A: {}\n",
                        "b.yaml" to "ttr: {profile: ./profiles/x.yaml, grammar: '>=0.22 <1.0'}\nentity:\n  B: {}\n",
                    ),
                )
            model.resolve("px/er.yaml").readText() shouldContain "profile demo-x/1"
            model.resolve("py/er.yaml").readText() shouldContain "profile demo-y/1"
            GeneratedGuard.check(model, "t").shouldBeEmpty()
        }

        test(
            "an apply --profile override cannot be re-checked: one precise edited at the head; declared, it is green",
        ) {
            val files =
                mapOf(
                    "custom/my.yaml" to profile("demo-my/1", "pm"),
                    "model.ignore" to "custom/\n",
                    "a.yaml" to "entity:\n  A: {}\n",
                )
            val override = Files.createTempDirectory("s36-override").resolve("tree")
            val model = applied(override, files, override.resolve("custom/my.yaml").toString())
            val issues = GeneratedGuard.check(model, "t")
            issues.map { Triple(it.file.substringAfter("/model/"), it.line, it.column) } shouldBe
                listOf(Triple("pm/er.yaml", 1, 1))
            issues.single().message shouldBe
                "xm/generated-edited: generated with profile demo-my/1, which the tree's own declarations do not " +
                "give this document; an apply --profile override cannot be re-checked — declare it in ttr.yaml " +
                "or the file's ttr: header"
            val declared = Files.createTempDirectory("s36-declared").resolve("tree")
            val green =
                applied(declared, files + ("ttr.yaml" to "profile: ./custom/my.yaml\ngrammar: '>=0.22 <1.0'\n"))
            GeneratedGuard.check(green, "t").shouldBeEmpty()
            // The tree yields the same document under its own profile: an equal body is still not that profile's.
            val same = Files.createTempDirectory("s36-same").resolve("tree")
            val twin =
                applied(
                    same,
                    files + ("ttr.yaml" to "profile: ./profiles/y.yaml\ngrammar: '>=0.22 <1.0'\n") +
                        ("profiles/y.yaml" to profile("demo-y/1", "pm")) + ("model.ignore" to "custom/\nprofiles/\n"),
                    same.resolve("custom/my.yaml").toString(),
                )
            GeneratedGuard.check(twin, "t").map { it.message.substringBefore(",") } shouldBe
                listOf("xm/generated-edited: generated with profile demo-my/1")
        }

        test("override over a tree whose own declarations fail: the ✅R277-A message still at the head (N3)") {
            val at = Files.createTempDirectory("s37-n3").resolve("tree")
            val rename = "  - {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}\n"
            val model =
                applied(
                    at,
                    mapOf(
                        "profiles/x.yaml" to profile("demo-x/1", "px") + rename,
                        "profiles/y.yaml" to profile("demo-y/1", "py"),
                        "ttr.yaml" to "profile: ./profiles/y.yaml\ngrammar: '>=0.22 <1.0'\n",
                        "model.ignore" to "profiles/\n",
                        "a.yaml" to "entity:\n  A: {popis: x}\n",
                    ),
                    profile = "demo-x/1",
                )
            val issues = GeneratedGuard.check(model, "t")
            val head = issues.filter { it.file.endsWith("/model/px/er.yaml") }
            head.map { Triple(it.line, it.column, it.message.substringBefore(",")) } shouldBe
                listOf(Triple(1, 1, "xm/generated-edited: generated with profile demo-x/1"))
            // The tree's own error is still reported where it arises.
            issues.filter { it.file.endsWith("/tree/a.yaml") }.map { it.message } shouldContain
                "ys/unknown-key: Unconsumed key popis"
        }

        test("a tree path with a space is header-safe: apply from 'My Tree', then the guard is green (F3)") {
            val pristine = case("pristine").resolve("tree")
            val spaced = Files.createTempDirectory("s36-space").resolve("My Tree")
            Files.walk(pristine).use { paths -> paths.filter { it.isRegularFile() }.toList() }.forEach {
                spaced
                    .resolve(pristine.relativize(it).toString())
                    .also { file -> file.parent.createDirectories() }
                    .writeText(it.readText())
            }
            val model = applied(spaced, emptyMap())
            model.resolve("demo/er.yaml").readText() shouldContain "My%20Tree@"
            GeneratedGuard.check(model, "t").shouldBeEmpty()
        }

        test("agents/ under the tree is outside its hash: an agent edit leaves generated files fresh (F4, ✅MW-44)") {
            val pristine = case("pristine")
            pristine.resolve("tree/agents/foo.yaml").also { it.parent.createDirectories() }.writeText("name: a\n")
            guard(pristine).shouldBeEmpty()
        }

        test(
            "one content rule (N4 (d)): a file the loader reads under build/ is in the hash; agents/ and dot-dirs are not",
        ) {
            TreeContent.includes(listOf("build", "x.yaml")) shouldBe true
            TreeContent.includes(listOf("node_modules", "x.yaml")) shouldBe true
            TreeContent.includes(listOf("a", "agents", "x.yaml")) shouldBe true
            TreeContent.includes(listOf("agents", "x.yaml")) shouldBe false
            TreeContent.includes(listOf("a", ".git", "x")) shouldBe false
            val built = case("pristine")
            built.resolve("tree/.cache/x.yaml").also { it.parent.createDirectories() }.writeText("a: 1\n")
            guard(built).shouldBeEmpty()
            built.resolve("tree/build/x.yaml").also { it.parent.createDirectories() }.writeText("a: 1\n")
            codes(guard(built)).toSet() shouldContain "xm/generated-stale"
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
