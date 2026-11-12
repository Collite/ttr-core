// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText

/** MW S3.4 T1 — the `yaml` source kind (contracts §4). */
class YamlSourceSpec :
    FunSpec({
        val fixtures = Path.of("../../../tests/conformance/fixtures")

        fun tree(vararg files: Pair<String, String>): Path =
            Files.createTempDirectory("s34-tree").also { root ->
                files.forEach { (name, text) ->
                    root.resolve(name).also { it.parent.createDirectories() }.writeText(text)
                }
            }

        /** Records every file the source reads. */
        class Recording(
            private val delegate: ModelStorage,
        ) : ModelStorage by delegate {
            val reads = mutableListOf<String>()

            override fun read(file: StorageFile): String = delegate.read(file).also { reads += file.underRoot() }
        }

        fun yaml(
            root: Path,
            profile: String? = null,
        ) = YamlFileSource("t", 100, LocalFsStorage("t", root), profile)

        fun names(source: ModelSource) =
            SnapshotDump
                .load(source)
                .model!!
                .objectByQname()
                .keys
                .map { it.toString() }
                .filter { "hartland" !in it && it.startsWith("QualifiedName(schemaCode=ER") }
                .map { it.substringAfter("name=").substringBefore(',') }
                .sorted()

        val entity = "ttr: \"0.22\"\nmodel: er\ndefs:\n  - kind: entity\n    id: %s\n    properties: {}\n"
        val profile =
            "profile: d/1\ngrammar: '>=0.22 <1.0'\noperations:\n" +
                "  - {op: package, mapping: {}, default: demo}\n  - {op: kinds, at: /entity, mapping: {entita: entity}}\n"

        test("a canonical-YAML tree loads to the snapshot of its .ttrm twin, for every P2 twin directory") {
            val directories =
                Files.list(fixtures).use { paths ->
                    paths.filter { Files.isDirectory(it) && !it.name.endsWith("-negative") }.sorted().toList()
                }
            directories.forEach { dir ->
                withClue(dir.name) {
                    SnapshotDump.dump(SnapshotDump.load(yaml(dir)), dir) shouldBe
                        SnapshotDump.dump(SnapshotDump.load(FileBasedSource("t", 100, LocalFsStorage("t", dir))), dir)
                }
            }
        }

        test("a dialect document keeps its file and authored spans; a fan-out file's second model is <file>#<model>") {
            val root = Path.of("../../../tests/conformance/dialect/demo-cs/tree").toAbsolutePath().normalize()
            val result = SnapshotDump.load(yaml(root))
            val fanout = root.resolve("07-fanout.yaml").toString()
            val files = result.files.filter { it.storageFile.path.startsWith(fanout) }
            files.map { it.storageFile.path } shouldContainExactly listOf(fanout, "$fanout#binding")
            files.map { it.schemaCode } shouldContainExactly listOf("er", "binding")
            // `Odberatel:` is line 2 of the dialect file; its record is the mapping that starts on line 3.
            val first = files.first().definitions.first()
            first.name shouldBe "Odberatel"
            first.source.file shouldBe fanout
            first.source.line shouldBe 3
            files[1]
                .definitions
                .first()
                .source.file shouldBe fanout
            result.report!!.droppedFiles shouldBe emptyList()
        }

        test("the walk skips dot-segments, agents/, model.ignore and profile file ignores; the rest is content") {
            val root =
                tree(
                    "ttr.yaml" to "profile: ./p/d.yaml\ngrammar: '>=0.22 <1.0'\n",
                    "p/d.yaml" to profile + "  - {op: ignore, files: [skipped/]}\n",
                    "model.ignore" to "p/\nignored.yaml\n",
                    "a.yaml" to "entity:\n  A: {}\n",
                    "deep/er/b.yml" to "entity:\n  B: {}\n",
                    ".hidden/c.yaml" to "not: [valid",
                    "x/.cache/c.yaml" to "not: [valid",
                    "agents/golem.yaml" to "not: [valid",
                    "ignored.yaml" to "not: [valid",
                    "skipped/s.yaml" to "entity:\n  S: {}\n",
                    "renames.yaml" to "[]\n",
                )
            val storage = Recording(LocalFsStorage("t", root))
            val result = MetadataLoader(listOf(BuiltinStockSource(), YamlFileSource("t", 100, storage))).load()
            result.errors.filter { it.category != LoadIssue.Category.PACKAGE_MISMATCH } shouldBe emptyList()
            names(YamlFileSource("t", 100, LocalFsStorage("t", root))) shouldContainExactly listOf("A", "B")
            // ✅MW-44: never read, not merely dropped — so no tool built on this source can lint an agent file.
            // renames.yaml is read once, as the export sidecar it always was; never as content.
            storage.reads.sorted() shouldContainExactly
                listOf("a.yaml", "deep/er/b.yml", "renames.yaml", "skipped/s.yaml")
            storage.reads shouldNotContain "agents/golem.yaml"
        }

        test("declarations: ttr.yaml, a per-file header wins, undeclared is canonical, mixed trees, the override") {
            val dialect = "d.yaml" to profile
            // No ttr.yaml: canonical, except a file whose header names a profile.
            val mixed =
                tree(
                    dialect,
                    "model.ignore" to "d.yaml\n",
                    "c.yaml" to entity.format("Canon"),
                    "h.yaml" to "ttr: {profile: ./d.yaml, grammar: '>=0.22 <1.0'}\nentity:\n  Header: {}\n",
                )
            names(yaml(mixed)) shouldContainExactly listOf("Canon", "Header")
            // ttr.yaml declares the tree; a header naming another profile wins for its file.
            val declared =
                tree(
                    dialect,
                    // Under d.yaml (the tree) h.yaml would be an unknown key; only its header profile reads /ent.
                    "e.yaml" to profile.replace("at: /entity", "at: /ent").replace("d/1", "e/1"),
                    "model.ignore" to "d.yaml\ne.yaml\n",
                    "ttr.yaml" to "profile: ./d.yaml\ngrammar: '>=0.22 <1.0'\n",
                    "t.yaml" to "entity:\n  Tree: {}\n",
                    "h.yaml" to "ttr: {profile: ./e.yaml, grammar: '>=0.22 <1.0'}\nent:\n  Other: {}\n",
                )
            names(yaml(declared)) shouldContainExactly listOf("Other", "Tree")
            // The override (METADATA_PROFILE, P4) replaces ttr.yaml; the header still wins.
            val overridden =
                tree(*listOf(dialect, "model.ignore" to "d.yaml\n", "t.yaml" to "entity:\n  Tree: {}\n").toTypedArray())
            names(yaml(overridden, profile = "./d.yaml")) shouldContainExactly listOf("Tree")
            // A tree declared with the identity profile is canonical: no operations, so the codec reads every file.
            val identity =
                tree(
                    "ttr.yaml" to "profile: canonical/1\ngrammar: '>=0.22 <1.0'\n",
                    "c.yaml" to entity.format("Canon"),
                )
            names(yaml(identity)) shouldContainExactly listOf("Canon")
        }

        test("a profile error drops its file with an issue; strict fails the load, non-strict keeps the rest") {
            val root =
                tree(
                    "d.yaml" to profile,
                    "model.ignore" to "d.yaml\n",
                    "ttr.yaml" to "profile: ./d.yaml\ngrammar: '>=0.22 <1.0'\n",
                    "good.yaml" to "entity:\n  Good: {}\n",
                    "bad.yaml" to "entity:\n  Bad: {}\nneznamy: 1\n",
                )
            val bad = root.resolve("bad.yaml").toString()
            val strict = MetadataLoader.of(SourceKind.YAML, LocalFsStorage("t", root)).load(LoadOptions(strict = true))
            strict.report!!.ok shouldBe false
            strict.report!!.droppedFiles shouldContainExactly listOf(bad)
            val issue = strict.report!!.issues.single { it.file == bad }
            issue.code shouldBe "dropped-file"
            issue.line shouldBe 3
            issue.message shouldStartWith "ys/unknown-key"
            val lenient = MetadataLoader(listOf(BuiltinStockSource(), yaml(root))).load()
            lenient.model!!
                .objectByQname()
                .keys
                .map { it.name } shouldContainExactly
                lenient.model!!
                    .objectByQname()
                    .keys
                    .map { it.name }
                    .filter { it != "Bad" }
            names(yaml(root)) shouldContainExactly listOf("Good")
        }

        test("review-260 F2: an unmapped package key drops only its file, named by its storage path") {
            val root =
                tree(
                    "p/d.yaml" to
                        profile.replace("mapping: {}, default: demo", "mapping: {a: demo}"),
                    "model.ignore" to "p/\n",
                    "ttr.yaml" to "profile: ./p/d.yaml\ngrammar: '>=0.22 <1.0'\n",
                    "a.yaml" to "entity:\n  Good: {}\n",
                    "x.yaml" to "entity:\n  Bad: {}\n",
                )
            val x = root.resolve("x.yaml").toString()
            val result = MetadataLoader(listOf(BuiltinStockSource(), yaml(root))).load()
            names(yaml(root)) shouldContainExactly listOf("Good")
            MetadataLoader(listOf(BuiltinStockSource(), yaml(root)))
                .load(LoadOptions(strict = true))
                .report!!
                .droppedFiles shouldContainExactly listOf(x)
            result.errors.filter { it.category != LoadIssue.Category.PACKAGE_MISMATCH }.map {
                "${it.file} ${it.message}"
            } shouldContainExactly listOf("$x ys/value-unmapped: Unmapped package key x")
        }
    })
