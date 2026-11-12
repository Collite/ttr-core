// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.profile.DeclarationReader
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
    override fun load(): SourceSnapshot =
        FileBasedSource(
            sourceId,
            priority,
            storage,
            layouts,
            allMdHopIssues,
            YamlTreeReader(profile, catalog),
        ).load()
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

    private class Problems(
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

        /** The issue names where the problem is; the dropped entry names the dialect file it removes. */
        fun drop(
            file: StorageFile,
            issue: LoadWarning,
        ) {
            dropped += issue.copy(file = file.path)
            if (issue.file != file.path) errors += issue.copy(file = file.path, message = "dropped: ${issue.message}")
        }

        fun profile(e: ProfileException) =
            error(e.location.file, e.location.line, e.location.column, "${e.code}: ${e.detail}")
    }

    /**
     * Profile resolution reads profile files, path overlays and sidecars from the filesystem under the storage root
     * (contracts §4, ✅R260-B), so it is built only when a file needs a profile, and a root that is not a directory on
     * disk fails it once with `ys/profile-not-found`; canonical files load regardless.
     */
    private class Profiles(
        val root: Path?,
        private val catalog: Map<String, Path>,
        private val problems: Problems,
    ) {
        private var resolver: Result<ProfileResolver>? = null

        fun resolver(at: String): ProfileResolver = (resolver ?: create(at).also { resolver = it }).getOrThrow()

        private fun create(at: String): Result<ProfileResolver> {
            fun failure(): Result<ProfileResolver> {
                val issue =
                    problems.error(
                        at,
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

    override fun read(
        listed: List<StorageFile>,
        storage: ModelStorage,
        sourceId: String,
    ): ReadFiles {
        val problems = Problems(sourceId)
        val control = listed.associateBy { it.underRoot() }
        val root = listed.firstNotNullOfOrNull { it.rootPath }?.toAbsolutePath()
        val candidates =
            listed.filter { file ->
                val path = file.underRoot()
                val segments = path.split('/')
                file.extension() in setOf("yaml", "yml") &&
                    segments.none { it.startsWith(".") } &&
                    segments.first() != "agents" &&
                    path !in CONTROL_FILES
            }
        val ignore =
            control["model.ignore"]?.let { modelIgnore(it, storage, problems) }?.getOrElse { e ->
                // Fail closed (review-260 F4): without the ignore list no file is known to be content.
                candidates.forEach { problems.drop(it, (e as Recorded).issue) }
                return ReadFiles(emptyList(), problems.errors, dropped = problems.dropped)
            }
        val content = candidates.filter { ignore?.matches(it.underRoot()) != true }
        val profiles = Profiles(root, catalog, problems)
        val tree = treeDeclaration(control["ttr.yaml"], storage, profiles, problems)
        val parsed = mutableListOf<ParsedFile>()
        val dialect = linkedMapOf<List<String>, Pair<List<ProfileDefinition>, MutableList<Pair<StorageFile, String>>>>()
        for (file in content) {
            val text = storage.read(file)
            val chain =
                try {
                    val declaration = DeclarationReader.readFile(text, file.absolute())
                    when {
                        declaration != null -> profiles.resolver(file.path).resolve(declaration)
                        tree != null -> tree.getOrThrow()
                        else -> null
                    }
                } catch (e: Recorded) {
                    problems.drop(file, e.issue)
                    continue
                } catch (e: ProfileException) {
                    problems.drop(file, problems.profile(e))
                    continue
                }
            if (chain == null || chain.all { it.operations.isEmpty() && it.overlay == null }) {
                canonical(file, text, problems)?.let { parsed += it }
            } else {
                dialect.getOrPut(chain.map { it.file }) { chain to mutableListOf() }.second += file to text
            }
        }
        // A dialect chain exists only once a resolver did, and a resolver only over an on-disk root.
        dialect.values.forEach { (chain, files) ->
            parsed +=
                apply(requireNotNull(profiles.root), chain, files, problems)
        }
        return ReadFiles(parsed.sortedBy { it.file.path }, problems.errors, dropped = problems.dropped)
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
     * The override, else `<root>/ttr.yaml`, else none (canonical). A failure is recorded once and fails every file it
     * would declare with that same issue.
     */
    private fun treeDeclaration(
        declarationFile: StorageFile?,
        storage: ModelStorage,
        profiles: Profiles,
        problems: Problems,
    ): Result<List<ProfileDefinition>>? {
        val label = declarationFile?.absolute() ?: profiles.root?.resolve("ttr.yaml")?.toString() ?: "ttr.yaml"
        return try {
            when {
                profile != null -> Result.success(profiles.resolver(label).resolve(profile, Path.of(label)))
                declarationFile != null -> {
                    val text =
                        try {
                            storage.read(declarationFile)
                        } catch (e: IOException) {
                            throw Recorded(problems.error(label, 1, 1, "ys/profile-not-found: ttr.yaml is unreadable"))
                        }
                    val declaration: ProfileDeclaration = DeclarationReader.readTree(text, label)
                    Result.success(profiles.resolver(label).resolve(declaration))
                }
                else -> null
            }
        } catch (e: Recorded) {
            Result.failure(e)
        } catch (e: ProfileException) {
            Result.failure(Recorded(problems.profile(e)))
        }
    }

    private fun canonical(
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
     * the run repeats without it; one naming anything else (the profile, an overlay, a sidecar) drops them all.
     */
    private fun apply(
        root: Path,
        chain: List<ProfileDefinition>,
        files: List<Pair<StorageFile, String>>,
        problems: Problems,
    ): List<ParsedFile> {
        val trees = linkedMapOf<String, Pair<StorageFile, ForeignTree>>()
        for ((file, text) in files) {
            try {
                trees[file.path] = file to ForeignTree.read(text, file.underRoot(), file.path)
            } catch (e: ProfileException) {
                problems.drop(file, problems.profile(e))
            }
        }
        while (trees.isNotEmpty()) {
            val result =
                try {
                    ProfileEngine.apply(trees.values.map { it.second }, chain, root, perFile = true)
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
            val byFile = trees.values.associate { (file, tree) -> tree.file to file }
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
        return emptyList()
    }

    /** The resolver bases a relative declaring file on its own root, so a storage with a relative root passes this. */
    private fun StorageFile.absolute(): String =
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
