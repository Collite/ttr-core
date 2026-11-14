// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.PackageLayout
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** MW S3.5 T4 — the fixed points of `profile apply` (contracts §5). */
class ProfileApplySpec :
    FunSpec({
        val dialect = Path.of("../../../tests/conformance/dialect")

        fun apply(root: Path) = ProfileApply.run(LocalFsStorage("t", root))

        /** Every model file under [root] by its relative path, the `profiles/` catalog and control files aside. */
        fun inputs(root: Path): Map<String, String> =
            Files
                .walk(root)
                .use { paths ->
                    paths
                        .filter { it.isRegularFile() && (it.extension == "yaml" || it.extension == "yml") }
                        .toList()
                }.associate { root.relativize(it).joinToString("/") to it.readText() }

        // Issues apply's grouping legitimately removes, besides the layout's `package-declaration-mismatch`: a document
        // per package and model carries the union of its files' imports (contracts §5), so a reference one dialect file
        // could not reach is reachable in the grouped document.
        val grouped =
            mapOf(
                "demo-cs" to
                    listOf(
                        "ERROR UNIMPORTED_REFERENCE ttr/unimported-reference: 'Supplier' is not reachable from this file's imports",
                    ),
                "demo-cs-links" to emptyList(),
            )

        fun issue(line: String) = line.startsWith("ERROR ") || line.startsWith("WARNING ") || line.startsWith("INFO ")

        listOf("demo-cs", "demo-cs-links").forEach { case ->
            test("apply($case) → canonical → TTR → parse dumps as the dialect tree loads") {
                val tree = dialect.resolve("$case/tree")
                val applied = apply(tree)
                applied.conflicts.shouldBeEmpty()
                // The P2 oracle: each canonical document rendered as .ttrm and loaded by the .ttrm path.
                val ttrm = Files.createTempDirectory("s35-ttrm")
                applied.files
                    .filterKeys { it.endsWith(".yaml") }
                    .forEach { (path, text) ->
                        val document = CanonicalYaml.read(text, path)
                        ttrm
                            .resolve(path.removeSuffix(".yaml") + ".ttrm")
                            .also { it.parent.createDirectories() }
                            .writeText(CanonicalYaml.toTtr(document))
                    }
                // A dialect tree is served with layout off (§4 (f)); apply writes `<package>/`, where the layout holds.
                val expected =
                    MetadataLoader(listOf(BuiltinStockSource(), YamlFileSource("t", 100, LocalFsStorage("t", tree))))
                        .load(LoadOptions(strict = true, packageLayout = PackageLayout.Off))
                val actual = SnapshotDump.load(FileBasedSource("t", 100, LocalFsStorage("t", ttrm)))
                val got = SnapshotDump.dump(actual, ttrm).lines()
                val want = SnapshotDump.dump(expected, tree).lines()
                // Every typed object, area and schema dumps the same, in the same order.
                got.filterNot(::issue) shouldBe want.filterNot(::issue)
                // Apply adds no issue; it removes only the layout mismatches and what grouping makes reachable.
                (got - want.toSet()).shouldBeEmpty()
                (want - got.toSet()).filter { "PACKAGE_MISMATCH" !in it } shouldBe grouped.getValue(case)
            }
        }

        test("apply(canonical, canonical/1) is byte-identical to its input files") {
            listOf("demo-cs", "demo-cs-links").forEach { case ->
                val canonical = dialect.resolve("$case/canonical")
                val root = Files.createTempDirectory("s35-identity")
                inputs(canonical).forEach { (path, text) ->
                    root.resolve(path).also { it.parent.createDirectories() }.writeText(text)
                }
                root.resolve("ttr.yaml").writeText("profile: canonical/1\ngrammar: '>=0.22 <1.0'\n")
                val applied = apply(root)
                applied.conflicts.shouldBeEmpty()
                withClue(case) {
                    applied.files.filterKeys { it.endsWith(".yaml") } shouldBe inputs(canonical)
                }
            }
        }

        test("two runs are byte-equal") {
            listOf("demo-cs", "demo-cs-links").forEach { case ->
                val tree = dialect.resolve("$case/tree")
                apply(tree).files shouldBe apply(tree).files
            }
        }
    })
