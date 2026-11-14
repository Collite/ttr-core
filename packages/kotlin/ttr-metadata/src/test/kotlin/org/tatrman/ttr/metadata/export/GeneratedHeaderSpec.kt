// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.ttr.metadata.source.HeaderOptions
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ProfileApply
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** MW S3.6 T1/T2 — the generated-file header (contracts §6): its text, its hash inputs, and who writes it. */
class GeneratedHeaderSpec :
    StringSpec({
        val links = Path.of("../../../tests/conformance/dialect/demo-cs-links/tree").toAbsolutePath().normalize()
        val sha = "a".repeat(64)
        val other = "b".repeat(64)

        fun copy(
            from: Path,
            to: Path = Files.createTempDirectory("s36-tree"),
        ): Path {
            Files.walk(from).use { paths -> paths.filter { it.isRegularFile() }.toList() }.forEach {
                to
                    .resolve(from.relativize(it).toString())
                    .also { file ->
                        file.parent.createDirectories()
                    }.writeText(it.readText())
            }
            return to
        }

        "the header text is contracts §6's, in both comment forms, and parses back" {
            val header =
                GeneratedHeader(
                    GeneratedInput("../tree", sha),
                    GeneratedInput("../tree/o.yaml", other),
                    "demo/1",
                    "1.2.3",
                )
            header.text shouldBe
                "generated-from: ../tree@$sha overlay ../tree/o.yaml@$other profile demo/1 generator 1.2.3 — do not edit"
            header.line(GeneratedHeader.Form.TTRM) shouldBe "// ${header.text}\n"
            header.line(GeneratedHeader.Form.YAML) shouldBe "# ${header.text}\n"
            GeneratedHeader.read(header.line(GeneratedHeader.Form.YAML) + "ttr: \"0.22\"\n") shouldBe header
            GeneratedHeader.read(header.line(GeneratedHeader.Form.TTRM)) shouldBe header
            // The overlay is optional; a file without a header is not generated; a mangled one is claimed but invalid.
            val bare = header.copy(overlay = null)
            bare.text shouldBe "generated-from: ../tree@$sha profile demo/1 generator 1.2.3 — do not edit"
            GeneratedHeader.parse("# ${bare.text}") shouldBe bare
            GeneratedHeader.read("ttr: \"0.22\"\n").shouldBeNull()
            GeneratedHeader.claimed("# generated-from: whatever\n") shouldBe true
            GeneratedHeader.read("# generated-from: whatever\n").shouldBeNull()
            GeneratedHeader.body("${header.line(GeneratedHeader.Form.YAML)}ttr: x\n") shouldBe "ttr: x\n"
        }

        "a path with a space, a percent sign or a Czech letter round-trips; only % and whitespace are encoded (F3)" {
            val paths = listOf("../My Tree", "../100%", "../Strom ž", "../tab\there")
            paths.forEach { path ->
                val header =
                    GeneratedHeader(GeneratedInput(path, sha), GeneratedInput("$path/o.yaml", other), "p/1", "1")
                GeneratedHeader.read(header.line(GeneratedHeader.Form.YAML)) shouldBe header
            }
            GeneratedHeader(GeneratedInput("../Strom ž", sha), null, "p/1", "1").text shouldStartWith
                "generated-from: ../Strom%20ž@"
            GeneratedHeader(GeneratedInput("../100%", sha), null, "p/1", "1").text shouldStartWith
                "generated-from: ../100%25@"
            // A % not followed by two hex digits is not a header ttr-core writes.
            GeneratedHeader.parse("# generated-from: ../a%2@$sha profile p/1 generator 1 — do not edit").shouldBeNull()
        }

        "hash inputs are deterministic: a tree's digest moves with any content byte, never with hidden or build state" {
            val a = copy(links)
            val b = copy(links)
            Hashes.tree(a) shouldBe Hashes.tree(b)
            Hashes.of(a.resolve("ttr.yaml")) shouldBe Hashes.bytes(a.resolve("ttr.yaml").readText().toByteArray())
            Hashes.of(a.resolve("missing")).shouldBeNull()
            val before = Hashes.tree(a)
            a.resolve(".git").createDirectories()
            a.resolve(".git/HEAD").writeText("ref: x\n")
            a.resolve("build/out.txt").also { it.parent.createDirectories() }.writeText("x")
            Hashes.tree(a) shouldBe before
            a.resolve("l1-prodej.yaml").writeText(a.resolve("l1-prodej.yaml").readText() + " ")
            Hashes.tree(a) shouldNotBe before
            // A renamed file moves the digest though its bytes are the same.
            Files.move(b.resolve("l1-prodej.yaml"), b.resolve("l1-renamed.yaml"))
            Hashes.tree(b) shouldNotBe before
        }

        "apply writes the header on every generated document, its inputs relative to the file; byte-equal across runs" {
            val out = Files.createTempDirectory("s36-out")

            fun run() =
                ProfileApply.run(LocalFsStorage("t", links), header = HeaderOptions(out.resolve("gen"), "9.9.9"))
            val first = run().files
            first shouldBe run().files
            val er = GeneratedHeader.read(first.getValue("demo/er.yaml")).shouldNotBeNull()
            er.profile shouldBe "demo-overlay-inline/1"
            er.generator shouldBe "9.9.9"
            out.resolve("gen/demo").resolve(er.snapshot.path).normalize() shouldBe links
            er.snapshot.sha256 shouldBe Hashes.tree(links)
            out.resolve("gen/demo").resolve(er.overlay!!.path).normalize() shouldBe
                links.resolve("profiles/overlay-inline.yaml")
            // The sidecar sits at the out root: the same header, its paths relative to the out root.
            first.getValue("demo.sources.json") shouldStartWith "{\n  \"generated-from\": \"generated-from: ../"
            // Without header options (the loader's and the guard's runs) nothing is prefixed.
            ProfileApply.run(LocalFsStorage("t", links)).files.getValue("demo/er.yaml") shouldBe
                GeneratedHeader.body(first.getValue("demo/er.yaml"))
        }

        "a canonical input file is copied, not generated: it gets no header" {
            val canonical = Path.of("../../../tests/conformance/dialect/demo-cs-links/canonical").toAbsolutePath()
            val files =
                ProfileApply
                    .run(LocalFsStorage("t", canonical), header = HeaderOptions(Files.createTempDirectory("s36"), "1"))
                    .files
            files.filterKeys { it.endsWith(".yaml") }.values.none { GeneratedHeader.claimed(it) } shouldBe true
        }

        "TtrWriter and the ttr exporter put the header first when asked, and only then" {
            val header = GeneratedHeader(GeneratedInput("../s", sha), null, "p/1", "1")
            val set = xmJsonSet()

            fun bundle(request: ExportRequest) = Exporter().export(set, null, request) as ExportResult.Bundle
            // Each file's header names the same snapshot from its own directory, at any depth (F7).
            val snapshot = copy(links)
            val out = Files.createTempDirectory("s36-bundle").resolve("out")
            val inputs = GeneratedInputs(snapshot, snapshot.resolve("ttr.yaml"), "p/1", "1", out)
            val files = bundle(ExportRequest(format = "ttr", generatedFrom = inputs)).files
            files.map { it.name.count { c -> c == '/' } }.toSet().size shouldBe 2
            files.forEach {
                val read = GeneratedHeader.read(it.bytes.decodeToString()).shouldNotBeNull()
                val directory = out.resolve(it.name).parent
                directory.resolve(read.snapshot.path).normalize() shouldBe snapshot
                read.snapshot.sha256 shouldBe Hashes.tree(snapshot)
                directory.resolve(read.overlay!!.path).normalize() shouldBe snapshot.resolve("ttr.yaml")
            }
            bundle(
                ExportRequest(format = "ttr"),
            ).files.none { GeneratedHeader.claimed(it.bytes.decodeToString()) } shouldBe
                true
            val root = Files.createTempDirectory("s36-ttrm")
            root.resolve("shop/a.ttrm").also { it.parent.createDirectories() }.writeText(
                "package shop\nmodel er\ndef entity A { }\n",
            )
            val model =
                org.tatrman.ttr.metadata.MetadataLoader
                    .of(org.tatrman.ttr.metadata.source.SourceKind.TTR, LocalFsStorage("t", root))
                    .load()
                    .model
                    .shouldNotBeNull()
            TtrWriter.writeModel(model, header) shouldStartWith "// ${header.text}\n"
            GeneratedHeader.claimed(TtrWriter.writeModel(model)) shouldBe false
        }
    })
