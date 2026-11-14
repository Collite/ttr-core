// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.setPosixFilePermissions
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
            // renames.yaml is read once, as the export sidecar it always was; never as content. The control files
            // ttr.yaml and model.ignore are read through the storage too (review-260 F3).
            storage.reads.sorted() shouldContainExactly
                listOf("a.yaml", "deep/er/b.yml", "model.ignore", "renames.yaml", "skipped/s.yaml", "ttr.yaml")
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

        /** An in-memory storage whose root is not a directory on disk (the shape of a snapshot archive). */
        fun memory(vararg docs: Pair<String, String>): ModelStorage {
            val root = Path.of("/synthetic-r260-root")
            val files = docs.toMap()
            return object : ModelStorage {
                override val id = "mem"

                override fun fetchVersion() = "1"

                override fun listFiles(
                    extensions: List<String>,
                    prefixes: List<String>,
                ) = files.keys.filter { k -> extensions.any { k.endsWith(".$it") } }.map {
                    StorageFile(root.resolve(it).toString(), 1, root)
                }

                override fun read(file: StorageFile) = files.getValue(root.relativize(Path.of(file.path)).toString())
            }
        }

        test("review-260 F3: a storage without an on-disk root loads canonical files; profiles fail once, cleanly") {
            val canonical = "a.yaml" to entity.format("A")
            val plain = MetadataLoader.of(SourceKind.YAML, memory(canonical)).load()
            plain.errors shouldBe emptyList()
            plain.model!!
                .objectByQname()
                .keys
                .map { it.name } shouldContain "A"
            // A tree declaration needs profile resolution: one issue at ttr.yaml, its files dropped, no failed load.
            val declared =
                memory(
                    canonical,
                    "ttr.yaml" to "profile: ./p/d.yaml\ngrammar: '>=0.22 <1.0'\n",
                    "b.yaml" to "entity:\n  B: {}\n",
                )
            val strict = MetadataLoader.of(SourceKind.YAML, declared).load(LoadOptions(strict = true))
            val notFound = strict.issues.filter { "ys/profile-not-found" in it.message && "dropped" !in it.message }
            notFound.map { it.file to it.message } shouldContainExactly
                listOf(
                    "/synthetic-r260-root/ttr.yaml" to
                        "ys/profile-not-found: profiles need a filesystem-backed storage (root /synthetic-r260-root)",
                )
            strict.issues.none { "load failed" in it.message } shouldBe true
            // a.yaml's canonical version header keeps it canonical inside the declared tree (§4 (c), S3.5 T0).
            strict.report!!.droppedFiles shouldContainExactly listOf("/synthetic-r260-root/b.yaml")
            // Without ttr.yaml only the file whose header names a profile is dropped; the canonical file loads.
            val mixed =
                memory(canonical, "h.yaml" to "ttr: {profile: ./d.yaml, grammar: '>=0.22 <1.0'}\nentity:\n  H: {}\n")
            val lenient = MetadataLoader.of(SourceKind.YAML, mixed).load(LoadOptions(strict = true))
            lenient.report!!.droppedFiles shouldContainExactly listOf("/synthetic-r260-root/h.yaml")
            lenient.model!!
                .objectByQname()
                .keys
                .map { it.name } shouldContain "A"
        }

        test("review-260 F5: the source version moves with model.ignore and sidecars, never with other .json") {
            val root = tree("a.yaml" to "entity: {}\n", "model.ignore" to "p/\n", "demo.sources.json" to "{}")
            val storage = LocalFsStorage("t", root)
            val extensions = MODEL_EXTENSIONS + listOf("toml", "yaml") + YamlTreeReader(null, emptyMap()).extensions
            var stamp = 1_000_000L

            fun touch(name: String) =
                root.resolve(name).also { it.parent.createDirectories() }.also { file ->
                    if (!Files.exists(file)) file.writeText("{}")
                    Files.setLastModifiedTime(file, FileTime.fromMillis(stamp++))
                }
            val before = storage.fetchVersion(extensions)
            touch("node_modules/x/package.json")
            touch("build/out.json")
            storage.fetchVersion(extensions) shouldBe before
            touch("model.ignore")
            val ignored = storage.fetchVersion(extensions)
            ignored shouldNotBe before
            touch("demo.sources.json")
            storage.fetchVersion(extensions) shouldNotBe ignored
        }

        // Reported as skipped where a file cannot be locked (no POSIX, or running as root), as review-251 R6.
        val lockable =
            Files.createTempFile("r260-lock", ".yaml").let { probe ->
                "posix" in probe.fileSystem.supportedFileAttributeViews() &&
                    try {
                        probe.setPosixFilePermissions(emptySet())
                        !Files.isReadable(probe)
                    } finally {
                        probe.setPosixFilePermissions(PosixFilePermissions.fromString("rw-------"))
                    }
            }

        fun <T> locked(
            file: Path,
            block: () -> T,
        ): T {
            file.setPosixFilePermissions(emptySet())
            try {
                return block()
            } finally {
                file.setPosixFilePermissions(PosixFilePermissions.fromString("rw-------"))
            }
        }

        fun messages(root: Path) =
            MetadataLoader(listOf(BuiltinStockSource(), yaml(root)))
                .load()
                .errors
                .filter { it.category != LoadIssue.Category.PACKAGE_MISMATCH }
                .map { "${root.relativize(Path.of(it.file))} ${it.message}" }

        fun declaredTree(declaration: String) =
            tree(
                "p/d.yaml" to profile,
                "model.ignore" to "p/\n",
                "ttr.yaml" to declaration,
                "a.yaml" to "entity:\n  A: {}\n",
                "b.yaml" to "entity:\n  B: {}\n",
            )

        test("review-260 F6: an unreadable ttr.yaml is one issue, and each file is dropped with it").config(
            enabledIf = { lockable },
        ) {
            val root = declaredTree("profile: ./p/d.yaml\ngrammar: '>=0.22 <1.0'\n")
            val issue = "ys/profile-not-found: ttr.yaml is unreadable"
            locked(root.resolve("ttr.yaml")) { messages(root) } shouldContainExactly
                listOf("ttr.yaml $issue", "a.yaml dropped: $issue", "b.yaml dropped: $issue")
        }

        test("review-260 F6: a ttr.yaml naming a missing profile is one issue, and each file is dropped with it") {
            val root = declaredTree("profile: ./p/missing.yaml\ngrammar: '>=0.22 <1.0'\n")
            val issue = "ys/profile-not-found: Profile not found: ./p/missing.yaml"
            val messages = messages(root)
            messages shouldContainExactly listOf("ttr.yaml $issue", "a.yaml dropped: $issue", "b.yaml dropped: $issue")
            messages.none { it.split("ys/profile-not-found").size > 2 } shouldBe true
        }

        fun ignoreTree(ignore: String) =
            tree(
                "p/d.yaml" to profile,
                "model.ignore" to ignore,
                "ttr.yaml" to "profile: ./p/d.yaml\ngrammar: '>=0.22 <1.0'\n",
                "a.yaml" to "entity:\n  A: {}\n",
            )

        fun failsClosed(
            root: Path,
            issue: String,
        ) {
            val storage = Recording(LocalFsStorage("t", root))
            MetadataLoader(listOf(BuiltinStockSource(), YamlFileSource("t", 100, storage)))
                .load()
                .errors
                .map { "${root.relativize(Path.of(it.file))} ${it.message}" } shouldContainExactly
                listOf("model.ignore $issue", "a.yaml dropped: $issue", "p/d.yaml dropped: $issue")
            // No candidate is read, so the profile is never taken for content.
            storage.reads.filter { it != "model.ignore" } shouldBe emptyList()
            names(yaml(root)) shouldBe emptyList()
            MetadataLoader(listOf(BuiltinStockSource(), yaml(root)))
                .load(LoadOptions(strict = true))
                .report!!
                .droppedFiles shouldContainExactly
                listOf(root.resolve("a.yaml").toString(), root.resolve("p/d.yaml").toString())
        }

        test("review-260 F4: an invalid model.ignore fails closed: no yaml content loads, each candidate is dropped") {
            failsClosed(
                ignoreTree("p/\n!p/keep.yaml\n"),
                "ys/profile-invalid: model.ignore: '!' (re-include) is not supported",
            )
        }

        test("review-260 F4: an unreadable model.ignore fails closed the same way").config(enabledIf = { lockable }) {
            val root = ignoreTree("p/\n")
            locked(root.resolve("model.ignore")) {
                failsClosed(root, "ys/profile-invalid: model.ignore is unreadable (AccessDeniedException)")
            }
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

        test("§4 (c): a file with a canonical version header loads as canonical inside a dialect tree") {
            val root = declaredTree("profile: ./p/d.yaml\ngrammar: '>=0.22 <1.0'\n")
            root.resolve("c.yaml").writeText(entity.format("Canon"))
            messages(root) shouldBe emptyList()
            names(yaml(root)) shouldContainExactly listOf("A", "B", "Canon")
        }

        test(
            "re-review RR1-1: the identity profile needs no on-disk root; a tree no file falls back to raises nothing",
        ) {
            // (a) A canonical estate on a storage without a root, declared canonical/1 by its ttr.yaml.
            val identity =
                memory(
                    "ttr.yaml" to "profile: canonical/1\ngrammar: '>=0.22 <1.0'\n",
                    "a.yaml" to entity.format("A"),
                )
            val loaded = MetadataLoader.of(SourceKind.YAML, identity).load(LoadOptions(strict = true))
            loaded.errors shouldBe emptyList()
            loaded.model!!
                .objectByQname()
                .keys
                .map { it.name } shouldContain "A"
            // A file without a header falls back to the tree: the identity reaches the codec, which judges the file
            // (canonical YAML requires `ttr`), never "profiles need a filesystem-backed storage".
            val headerless =
                memory(
                    "ttr.yaml" to "profile: canonical/1\ngrammar: '>=0.22 <1.0'\n",
                    "a.yaml" to entity.format("A"),
                    "h.yaml" to entity.format("H").substringAfter('\n'),
                )
            val judged = MetadataLoader.of(SourceKind.YAML, headerless).load(LoadOptions(strict = true))
            judged.report!!.droppedFiles shouldContainExactly listOf("/synthetic-r260-root/h.yaml")
            judged.issues.filter { "dropped" !in it.message }.map {
                it.message.substringBefore(
                    ':',
                )
            } shouldContainExactly
                listOf("ys/canonical-invalid")
            // (b) No content, or all content ignored: the override is never resolved, so no issue.
            val empty = tree("README.md" to "x\n")
            MetadataLoader(listOf(BuiltinStockSource(), yaml(empty, profile = "./p/missing.yaml")))
                .load()
                .errors shouldBe emptyList()
            val ignored = tree("model.ignore" to "*.yaml\n", "a.yaml" to "entity:\n  A: {}\n")
            MetadataLoader(listOf(BuiltinStockSource(), yaml(ignored, profile = "./p/missing.yaml")))
                .load()
                .errors shouldBe emptyList()
        }

        test("re-review RR1-4: under a relative storage root a file's issue and its drop name it one way") {
            val relative = Path.of("build", "rr1-4-${System.nanoTime()}")
            relative.resolve("a.yaml").also { it.parent.createDirectories() }.writeText(
                "ttr: {profile: ./p/missing.yaml, grammar: '>=0.22 <1.0'}\nentity:\n  A: {}\n",
            )
            try {
                val result =
                    MetadataLoader(
                        listOf(BuiltinStockSource(), YamlFileSource("t", 100, LocalFsStorage("t", relative))),
                    ).load()
                result.errors.map { "${it.file} ${it.message}" } shouldContainExactly
                    listOf("$relative/a.yaml ys/profile-not-found: Profile not found: ./p/missing.yaml")
            } finally {
                relative.toFile().deleteRecursively()
            }
        }
    })
