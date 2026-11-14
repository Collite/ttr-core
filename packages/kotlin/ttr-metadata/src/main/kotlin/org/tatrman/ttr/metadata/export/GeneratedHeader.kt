// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** One named input of a generated file: its path, relative to the generated file's directory, and its SHA-256. */
data class GeneratedInput(
    val path: String,
    val sha256: String,
)

/**
 * MW S3.6 T2 — the generated-file header (contracts §6, XM D-10): `generated-from: <snapshot>@<sha256> [overlay
 * <path>@<sha256>] profile <name>/<major> generator <version> — do not edit`, written as the file's first line, a
 * `//` comment in `.ttrm` and a `#` comment in YAML. The snapshot is a file or a tree (a directory, hashed over its
 * content files, [Hashes.tree]); input paths are relative to the generated file's own directory, `/`-separated.
 */
data class GeneratedHeader(
    val snapshot: GeneratedInput,
    val overlay: GeneratedInput?,
    val profile: String,
    val generator: String,
) {
    /** The header without its comment marker: what the sidecar's `generated-from` holds. */
    val text: String
        get() =
            buildString {
                append("$PREFIX${snapshot.path}@${snapshot.sha256}")
                overlay?.let { append(" overlay ${it.path}@${it.sha256}") }
                append(" profile $profile generator $generator $SUFFIX")
            }

    /** The header line for [form], with its newline. */
    fun line(form: Form): String = "${form.marker} $text\n"

    /** The two comment forms (contracts §6). */
    enum class Form(
        val marker: String,
    ) {
        TTRM("//"),
        YAML("#"),
        ;

        companion object {
            /** The form a file's extension takes; null for a file that cannot carry a header. */
            fun of(file: String): Form? =
                when {
                    file.endsWith(".ttrm") -> TTRM
                    file.endsWith(".yaml") || file.endsWith(".yml") -> YAML
                    else -> null
                }
        }
    }

    companion object {
        const val PREFIX = "generated-from: "
        const val SUFFIX = "— do not edit"

        private val pattern =
            Regex(
                "^(?://|#) generated-from: (\\S+)@([0-9a-f]{64})(?: overlay (\\S+)@([0-9a-f]{64}))? " +
                    "profile (\\S+) generator (\\S+) — do not edit$",
            )

        /** A file's header when its first line is one; null for a file that does not claim to be generated. */
        fun read(text: String): GeneratedHeader? = parse(text.substringBefore('\n').removeSuffix("\r"))

        /** True when the first line claims a header at all, valid or not ([read] null on such a file is an error). */
        fun claimed(text: String): Boolean {
            val first = text.substringBefore('\n').trimStart()
            return first.startsWith("// $PREFIX") || first.startsWith("# $PREFIX")
        }

        fun parse(line: String): GeneratedHeader? {
            val match = pattern.matchEntire(line) ?: return null
            val (snapshot, snapshotSha, overlay, overlaySha, profile, generator) = match.destructured
            return GeneratedHeader(
                GeneratedInput(snapshot, snapshotSha),
                overlay.takeIf { it.isNotEmpty() }?.let { GeneratedInput(it, overlaySha) },
                profile,
                generator,
            )
        }

        /** [text] without its header line. */
        fun body(text: String): String = if (claimed(text)) text.substringAfter('\n', "") else text

        /** [to] relative to [from] (a directory), `/`-separated, as a header names an input. */
        fun relative(
            from: Path,
            to: Path,
        ): String =
            from
                .toAbsolutePath()
                .normalize()
                .relativize(to.toAbsolutePath().normalize())
                .joinToString("/")
                .ifEmpty { "." }
    }
}

/** SHA-256 of an input as the header records it. */
object Hashes {
    fun bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** A file's bytes, or a tree's [tree] digest; null when [path] does not exist. */
    fun of(path: Path): String? =
        when {
            path.isRegularFile() -> bytes(Files.readAllBytes(path))
            path.isDirectory() -> tree(path)
            else -> null
        }

    /**
     * A tree: SHA-256 over `<relative path>\n<sha256 of its bytes>\n` for every regular file below [root], in bytewise
     * path order, never entering a hidden directory, `build` or `node_modules` (the sidecar walk's exclusions).
     */
    fun tree(root: Path): String {
        val files =
            Files.walk(root).use { paths ->
                paths
                    .filter { it.isRegularFile() }
                    .filter { file ->
                        root.relativize(file).none {
                            it.name.startsWith(".") ||
                                it.name == "build" ||
                                it.name == "node_modules"
                        }
                    }.toList()
            }
        val lines =
            files
                .map { root.relativize(it).joinToString("/") to it }
                .sortedWith(compareBy(bytewise) { it.first })
                .joinToString("") { (name, file) -> "$name\n${bytes(Files.readAllBytes(file))}\n" }
        return bytes(lines.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
