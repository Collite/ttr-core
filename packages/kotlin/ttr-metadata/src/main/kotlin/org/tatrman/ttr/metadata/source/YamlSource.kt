// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.profile.ApplyResult
import org.tatrman.ttr.profile.DeclarationReader
import org.tatrman.ttr.profile.FileHeader
import org.tatrman.ttr.profile.FileSelector
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileDeclaration
import org.tatrman.ttr.profile.ProfileDefinition
import org.tatrman.ttr.profile.ProfileEngine
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileResolver
import org.tatrman.ttr.yaml.CanonicalYaml
import org.tatrman.ttr.yaml.CanonicalYamlException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The two model-file kinds a source can read (contracts §4). */
enum class SourceKind {
    /** `.ttr` / `.ttrm` text, the [FileBasedSource] default. */
    TTR,

    /** Canonical YAML, or dialect YAML with a profile ([YamlFileSource]). */
    YAML,
    ;

    /**
     * A source of this kind over [storage]. [profile] (YAML only) replaces the tree declaration `<root>/ttr.yaml`, as
     * `METADATA_PROFILE` will (P4); a per-file header still wins. [catalog] names profiles for the resolver.
     */
    fun source(
        sourceId: String,
        priority: Int,
        storage: ModelStorage,
        profile: String? = null,
        catalog: Map<String, Path> = emptyMap(),
        layouts: Boolean = false,
    ): ModelSource =
        when (this) {
            TTR -> {
                require(profile == null) { "A profile applies to the yaml source kind only" }
                FileBasedSource(sourceId, priority, storage, layouts = layouts)
            }
            YAML -> YamlFileSource(sourceId, priority, storage, profile, catalog, layouts)
        }
}

/**
 * MW S3.4 — the `yaml` source kind (contracts §4, ✅D-MW-57): a [FileBasedSource] whose files are canonical YAML or
 * dialect YAML plus a resolved profile. Both become the same [LoadedFile]/definition objects the `.ttrm` path makes;
 * a document emitted from a dialect file keeps the dialect file as its path and the authored spans. A dialect file
 * that emits a second model (✅D-MW-67 fan-out) gives that document the path `<file>#<model>`.
 *
 * The tree walk (C-4): every `.yaml`/`.yml` under the root is content except dot-segments, the root's `agents/`
 * (✅MW-44: never read), `model.ignore` matches and the profile's `files` ignores; the root's `ttr.yaml` (the tree
 * declaration) and `renames.yaml` (the rename ledger) are control files, not content. A profile error on one file
 * drops that file with an issue; [org.tatrman.ttr.metadata.LoadOptions] `strict` then fails the load.
 */
class YamlFileSource(
    private val sourceId: String,
    private val priority: Int,
    private val storage: ModelStorage,
    private val profile: String? = null,
    private val catalog: Map<String, Path> = emptyMap(),
    private val layouts: Boolean = false,
    private val allMdHopIssues: Boolean = false,
) : ModelSource {
    /** A [ProfileSession]'s reader, shared with its apply run; null builds a fresh one per load. */
    private var shared: YamlTreeReader? = null

    internal constructor(
        sourceId: String,
        priority: Int,
        storage: ModelStorage,
        reader: YamlTreeReader,
    ) : this(sourceId, priority, storage) {
        shared = reader
    }

    override fun load(): SourceSnapshot =
        FileBasedSource(
            sourceId,
            priority,
            storage,
            layouts,
            allMdHopIssues,
            shared ?: YamlTreeReader(profile, catalog),
        ).load()

    companion object {
        /** `canonical` / `canonical/1`: the bundled identity profile, which resolves without a catalog or a root. */
        fun isBundledProfile(reference: String): Boolean = ProfileResolver.isBundled(reference)
    }
}

/** One file's AST for the [FileBasedSource] loop: what the `.ttrm` parser returns, from wherever it came. */
internal data class ParsedFile(
    val file: StorageFile,
    val result: ParseResult,
)

internal class ReadFiles(
    val files: List<ParsedFile>,
    val errors: List<LoadWarning> = emptyList(),
    val warnings: List<LoadWarning> = emptyList(),
    val dropped: List<LoadWarning> = emptyList(),
)

internal interface ModelFileReader {
    /** Extensions listed besides the `.ttrm` ones (and hashed into the source version). */
    val extensions: List<String>

    fun read(
        listed: List<StorageFile>,
        storage: ModelStorage,
        sourceId: String,
    ): ReadFiles
}

internal class YamlTreeReader(
    private val profile: String?,
    private val catalog: Map<String, Path>,
) : ModelFileReader {
    // `sources.json`: the `<package>.sources.json` link sidecars change what a dialect file loads to; `ignore`:
    // `model.ignore` changes what is content. Both version the source; storages match extensions by suffix.
    override val extensions = listOf("yml", "sources.json", "ignore")

    /** A problem already recorded once; every file it removes is dropped with this same issue (review-260 F6). */
    private class Recorded(
        val issue: LoadWarning,
    ) : RuntimeException(issue.message)

    internal class Problems(
        val sourceId: String,
    ) {
        val errors = mutableListOf<LoadWarning>()
        val dropped = mutableListOf<LoadWarning>()

        fun error(
            file: String,
            line: Int,
            column: Int,
            message: String,
        ) = LoadWarning(sourceId, file, line, column, message).also { errors += it }

        /** Another run's own record of the same plan: what the plan raised, and nothing a run added. */
        fun copy() =
            Problems(sourceId).also {
                it.errors += errors
                it.dropped += dropped
            }

        /** The issue names where the problem is; the dropped entry names the dialect file it removes. */
        fun drop(
            file: StorageFile,
            issue: LoadWarning,
        ) {
            dropped += issue.copy(file = file.path)
            if (issue.file != file.path) errors += issue.copy(file = file.path, message = "dropped: ${issue.message}")
        }

        /**
         * The resolver sees a declaring file by its absolute path; [named] maps that label back to the storage name,
         * so an issue and the file's drop name it one way (re-review RR1-4).
         */
        fun profile(
            e: ProfileException,
            named: Pair<String, String>? = null,
        ) = error(
            e.location.file.takeUnless { it == named?.first } ?: named!!.second,
            e.location.line,
            e.location.column,
            "${e.code}: ${e.detail}",
        )
    }

    /**
     * Profile resolution reads profile files, path overlays and sidecars from the filesystem under the storage root
     * (contracts §4, ✅R260-B), so it is built only when a file needs a profile, and a root that is not a directory on
     * disk fails it once with `ys/profile-not-found`; canonical files load regardless. The bundled identity
     * (`canonical/1`) reads no file, so it never needs the resolver (re-review RR1-1).
     */
    private class Profiles(
        val root: Path?,
        private val catalog: Map<String, Path>,
        private val problems: Problems,
        /**
         * The tree declaration's storage name when the tree's own `ttr.yaml` needs the resolver (review-269 F8, N3):
         * asked only when the resolver cannot be built, so a tree that never needs one never reads its declaration.
         */
        private val tree: () -> String?,
    ) {
        private var resolver: Result<ProfileResolver>? = null

        /**
         * [at] names the file that needs the resolver. The one issue its absence raises is a storage problem, shared by
         * every file that needs a profile, so it is recorded under the tree declaration when there is one, else [at].
         */
        private fun resolver(at: String): ProfileResolver = (resolver ?: create(at).also { resolver = it }).getOrThrow()

        fun resolve(
            declaration: ProfileDeclaration,
            at: String,
        ): List<ProfileDefinition> = ProfileResolver.bundled(declaration) ?: resolver(at).resolve(declaration)

        fun resolve(
            reference: String,
            declaringFile: String,
            at: String,
        ): List<ProfileDefinition> =
            ProfileResolver.bundled(reference) ?: resolver(at).resolve(reference, Path.of(declaringFile))

        private fun create(at: String): Result<ProfileResolver> {
            fun failure(): Result<ProfileResolver> {
                val issue =
                    problems.error(
                        tree() ?: at,
                        1,
                        1,
                        "ys/profile-not-found: profiles need a filesystem-backed storage (root ${root ?: "none"})",
                    )
                return Result.failure(Recorded(issue))
            }
            if (root == null || !Files.isDirectory(root)) return failure()
            return try {
                Result.success(ProfileResolver(root, catalog))
            } catch (e: IOException) {
                failure()
            }
        }
    }

    /** The tree as the walk and the declarations see it: canonical files, and dialect files by profile chain. */
    internal data class Plan(
        val root: Path?,
        val canonical: List<Pair<StorageFile, String>>,
        val dialect: List<Pair<List<ProfileDefinition>, List<Pair<StorageFile, String>>>>,
        val problems: Problems,
    )

    override fun read(
        listed: List<StorageFile>,
        storage: ModelStorage,
        sourceId: String,
    ): ReadFiles {
        val plan = plan(listed, storage, sourceId)
        val problems = plan.problems
        val parsed = plan.canonical.mapNotNull { (file, text) -> canonical(file, text, problems) }.toMutableList()
        // A dialect chain exists only once a resolver did, and a resolver only over an on-disk root.
        plan.dialect.forEach { (chain, files) ->
            parsed +=
                apply(requireNotNull(plan.root), chain, files, problems)
        }
        return ReadFiles(parsed.sortedBy { it.file.path }, problems.errors, dropped = problems.dropped)
    }

    /** The walk (C-4) and each content file's declaration; files that fail either are dropped into the problems. */
    internal fun plan(
        listed: List<StorageFile>,
        storage: ModelStorage,
        sourceId: String,
    ): Plan {
        // One reader serves a profile command's load and its apply run (S3.6, from S3.5 review-269 F8): the walk, the
        // reads and the declarations are planned once per listing; each run records its own problems on a copy.
        // The loader lists `.ttrm` too; the key holds only what the plan reads, so its listing and apply's agree.
        val planned = listed.filter { file -> (listOf("yaml") + extensions).any { file.path.endsWith(".$it") } }
        val key = Triple(storage, sourceId, planned.sortedBy { it.path }.map { it.path to it.sizeBytes })
        memo?.takeIf { it.first == key }?.second?.let { return it.copy(problems = it.problems.copy()) }
        return planned(listed, storage, sourceId).also { memo = key to it.copy(problems = it.problems.copy()) }
    }

    private var memo: Pair<Triple<ModelStorage, String, List<Pair<String, Long>>>, Plan>? = null

    /** Parsed dialect files by storage path, for every run over one plan: a [ForeignTree] is immutable. */
    private val trees = mutableMapOf<String, Result<ForeignTree>>()

    private fun planned(
        listed: List<StorageFile>,
        storage: ModelStorage,
        sourceId: String,
    ): Plan {
        val problems = Problems(sourceId)
        var declarationFile: StorageFile? = null
        var ignoreFile: StorageFile? = null
        val candidates = mutableListOf<StorageFile>()
        for (file in listed.sortedBy { it.path }) {
            val path = file.underRoot()
            val segments = path.split('/')
            when {
                path == "ttr.yaml" -> declarationFile = file
                path == "model.ignore" -> ignoreFile = file
                file.extension() in setOf("yaml", "yml") &&
                    segments.none { it.startsWith(".") } &&
                    segments.first() != "agents" &&
                    path !in CONTROL_FILES -> candidates += file
            }
        }
        val storageRoot = listed.firstNotNullOfOrNull { it.rootPath }
        val treeName = declarationFile?.path ?: storageRoot?.resolve("ttr.yaml")?.toString() ?: "ttr.yaml"
        // Only a ttr.yaml the resolver must read is the shared reason: not an override, not the bundled identity (N3).
        val declares = {
            profile == null &&
                declarationFile != null &&
                runCatching {
                    ProfileResolver.bundled(
                        DeclarationReader.readTree(storage.read(declarationFile), absolute(treeName)),
                    )
                }.getOrNull() == null
        }
        val profiles = Profiles(storageRoot?.toAbsolutePath(), catalog, problems) { treeName.takeIf { declares() } }
        val ignore =
            ignoreFile?.let { modelIgnore(it, storage, problems) }?.getOrElse { e ->
                // Fail closed (review-260 F4): without the ignore list no file is known to be content.
                candidates.forEach { problems.drop(it, (e as Recorded).issue) }
                return Plan(profiles.root, emptyList(), emptyList(), problems)
            }
        val content = candidates.filter { ignore?.matches(it.underRoot()) != true }
        // Resolved on the first file that falls back to it, so a tree without such a file raises nothing (RR1-1).
        val tree by lazy { treeDeclaration(declarationFile, treeName, storage, profiles, problems) }
        val canonical = mutableListOf<Pair<StorageFile, String>>()
        val dialect = linkedMapOf<List<String>, Pair<List<ProfileDefinition>, MutableList<Pair<StorageFile, String>>>>()
        for (file in content) {
            val text = storage.read(file)
            val absolute = file.absolute()
            val chain =
                try {
                    when (val header = DeclarationReader.header(text, absolute)) {
                        is FileHeader.Declared -> profiles.resolve(header.declaration, file.path)
                        // §4 (c): a canonical version header is canonical whatever the tree declares.
                        is FileHeader.Canonical -> null
                        FileHeader.Absent -> tree?.getOrThrow()
                    }
                } catch (e: Recorded) {
                    problems.drop(file, e.issue)
                    continue
                } catch (e: ProfileException) {
                    problems.drop(file, problems.profile(e, absolute to file.path))
                    continue
                }
            if (chain == null || chain.all { it.operations.isEmpty() && it.overlay == null }) {
                canonical += file to text
            } else {
                dialect.getOrPut(chain.map { it.file }) { chain to mutableListOf() }.second += file to text
            }
        }
        return Plan(profiles.root, canonical, dialect.values.toList(), problems)
    }

    private fun modelIgnore(
        file: StorageFile,
        storage: ModelStorage,
        problems: Problems,
    ): Result<FileSelector> =
        try {
            Result.success(FileSelector(storage.read(file).lines()))
        } catch (e: IOException) {
            val detail = "model.ignore is unreadable (${e::class.simpleName})"
            Result.failure(Recorded(problems.error(file.path, 1, 1, "ys/profile-invalid: $detail")))
        } catch (e: IllegalArgumentException) {
            Result.failure(Recorded(problems.error(file.path, 1, 1, "ys/profile-invalid: model.ignore: ${e.message}")))
        }

    /**
     * The override, else `<root>/ttr.yaml`, else none (canonical). A failure is recorded once, under [name] (the
     * storage's name for `ttr.yaml`), and fails every file it would declare with that same issue.
     */
    private fun treeDeclaration(
        declarationFile: StorageFile?,
        name: String,
        storage: ModelStorage,
        profiles: Profiles,
        problems: Problems,
    ): Result<List<ProfileDefinition>>? {
        // The resolver bases relative references on the declaring file, so it sees the absolute path; issues do not.
        val label = absolute(name)
        return try {
            when {
                profile != null -> Result.success(profiles.resolve(profile, label, name))
                declarationFile != null -> {
                    val text =
                        try {
                            storage.read(declarationFile)
                        } catch (e: IOException) {
                            throw Recorded(problems.error(name, 1, 1, "ys/profile-not-found: ttr.yaml is unreadable"))
                        }
                    val declaration: ProfileDeclaration = DeclarationReader.readTree(text, label)
                    Result.success(profiles.resolve(declaration, name))
                }
                else -> null
            }
        } catch (e: Recorded) {
            Result.failure(e)
        } catch (e: ProfileException) {
            Result.failure(Recorded(problems.profile(e, label to name)))
        }
    }

    internal fun canonical(
        file: StorageFile,
        text: String,
        problems: Problems,
    ): ParsedFile? =
        try {
            ParsedFile(file, CanonicalYaml.read(text, file.path).ast)
        } catch (e: CanonicalYamlException) {
            problems.drop(file, problems.error(e.file, e.line, e.column, "ys/canonical-invalid: ${e.detail}"))
            null
        }

    /**
     * One run per profile chain, so links resolve across its files. A profile error naming one of them drops it and
     * the run repeats without it; one naming anything else (the profile, an overlay, a sidecar) drops them all. Null
     * when no file survives; else the result and the files it kept.
     */
    internal fun run(
        root: Path,
        chain: List<ProfileDefinition>,
        files: List<Pair<StorageFile, String>>,
        problems: Problems,
        perFile: Boolean,
    ): Pair<ApplyResult, List<Pair<StorageFile, ForeignTree>>>? {
        val trees = linkedMapOf<String, Pair<StorageFile, ForeignTree>>()
        for ((file, text) in files) {
            val tree =
                this.trees.getOrPut("${file.path}\u0000${text.hashCode()}") {
                    runCatching { ForeignTree.read(text, file.underRoot(), file.path) }
                }
            tree
                .onSuccess { trees[file.path] = file to it }
                .onFailure { e ->
                    if (e !is ProfileException) throw e
                    problems.drop(file, problems.profile(e))
                }
        }
        while (trees.isNotEmpty()) {
            val result =
                try {
                    ProfileEngine.apply(trees.values.map { it.second }, chain, root, perFile)
                } catch (e: ProfileException) {
                    val issue = problems.profile(e)
                    // Spans name a tree by its label (the storage path); the root-relative name is a second guard,
                    // so a relative span can never widen one file's drop to the whole chain (review-260 F2).
                    val culprit =
                        trees.remove(e.location.file)
                            ?: trees.entries
                                .firstOrNull { it.value.second.file == e.location.file }
                                ?.let { trees.remove(it.key) }
                    if (culprit != null) {
                        problems.drop(culprit.first, issue)
                    } else {
                        trees.values.forEach { problems.drop(it.first, issue) }
                        trees.clear()
                    }
                    continue
                }
            return result to trees.values.toList()
        }
        return null
    }

    private fun apply(
        root: Path,
        chain: List<ProfileDefinition>,
        files: List<Pair<StorageFile, String>>,
        problems: Problems,
    ): List<ParsedFile> {
        val (result, kept) = run(root, chain, files, problems, perFile = true) ?: return emptyList()
        val byFile = kept.associate { (file, tree) -> tree.file to file }
        return result.documents.groupBy { requireNotNull(it.file) }.flatMap { (relative, documents) ->
            val file = byFile.getValue(relative)
            documents.mapIndexed { i, document ->
                val ast = document.canonical.ast
                // Downstream keys a document by its path (symbol table, reference pass), so a file's second model
                // takes `<path>#<model>`; each keeps its own directive, so every qname is the canonical twin's.
                val path = if (i == 0) file.path else "${file.path}#${ast.modelDirective?.modelCode ?: i}"
                ParsedFile(file.copy(path = path), ast)
            }
        }
    }

    /**
     * The resolver bases a relative declaring file on its own root, so a storage with a relative root passes this to
     * the resolver only; issues keep the storage's name ([Problems.profile]).
     */
    private fun StorageFile.absolute(): String = absolute(path)

    private fun absolute(path: String): String =
        try {
            Path.of(path).toAbsolutePath().toString()
        } catch (e: java.nio.file.InvalidPathException) {
            path
        }

    companion object {
        /** Root files that steer the load and are never model content. */
        val CONTROL_FILES = setOf("ttr.yaml", "renames.yaml")
    }
}
