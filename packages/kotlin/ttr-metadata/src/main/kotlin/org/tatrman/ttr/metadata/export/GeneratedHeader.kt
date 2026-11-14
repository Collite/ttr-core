// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import java.nio.file.Files
import java.nio.file.Path
import org.tatrman.ttr.metadata.source.TreeContent
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
 * [GeneratedInput.path] is the path itself; [text] percent-encodes `%`, whitespace and control characters in it, so a
 * path with a space stays one token (review-277 F3), and [parse] decodes them. Every other character is literal.
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
                append("$PREFIX${encode(snapshot.path)}@${snapshot.sha256}")
                overlay?.let { append(" overlay ${encode(it.path)}@${it.sha256}") }
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
                GeneratedInput(decode(snapshot) ?: return null, snapshotSha),
                overlay.takeIf { it.isNotEmpty() }?.let { GeneratedInput(decode(it) ?: return null, overlaySha) },
                profile,
                generator,
            )
        }

        /** `%` and every whitespace or ISO control character as `%XX` of its UTF-8 bytes; the rest literal. */
        internal fun encode(path: String): String =
            buildString {
                var i = 0
                while (i < path.length) {
                    val point = path.codePointAt(i)
                    val char = String(Character.toChars(point))
                    if (point == '%'.code ||
                        Character.isWhitespace(point) ||
                        Character.isISOControl(point) ||
                        Character.isSpaceChar(point)
                    ) {
                        char.toByteArray(Charsets.UTF_8).forEach { append("%%%02X".format(it.toInt() and 0xFF)) }
                    } else {
                        append(char)
                    }
                    i += Character.charCount(point)
                }
            }

        /** [encode] undone; null when a `%` is not followed by two hex digits or the bytes are not UTF-8. */
        internal fun decode(token: String): String? {
            if ('%' !in token) return token
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < token.length) {
                val c = token[i]
                if (c == '%') {
                    // Exactly two hex digits: toInt(16) alone would take a sign, `%+9` or `%-1` (review-277 N4 (a)).
                    val byte =
                        token.substring(i + 1, minOf(i + 3, token.length)).takeIf { digits ->
                            digits.length == 2 && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                        }
                    out.write(byte?.toInt(16) ?: return null)
                    i += 3
                } else {
                    val end = if (Character.isHighSurrogate(c) && i + 1 < token.length) i + 2 else i + 1
                    out.write(token.substring(i, end).toByteArray(Charsets.UTF_8))
                    i = end
                }
            }
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                null
            }
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

/**
 * A bundle's header inputs (review-277 F7): the [snapshot] (a file or tree) and [overlay] it was generated from, the
 * profile identity and generator, and the bundle's output directory [out]. Each file's header names the inputs relative
 * to its own directory under [out]; the inputs are hashed once.
 */
data class GeneratedInputs(
    val snapshot: Path,
    val overlay: Path?,
    val profile: String,
    val generator: String,
    val out: Path,
) {
    /** A snapshot hash the caller already holds (one `profile apply` run hashes its tree once, review-277 F8). */
    private var known: Lazy<String>? = null

    /**
     * As above, with the snapshot's hash computed by the caller: `profile apply` shares one tree hash across its
     * chains. One builder for the `.ttrm` export and the YAML apply headers, so they cannot drift (S3.7 T0, N4 (c)).
     */
    internal constructor(
        snapshot: Path,
        overlay: Path?,
        profile: String,
        generator: String,
        out: Path,
        snapshotSha: Lazy<String>,
    ) : this(snapshot, overlay, profile, generator, out) {
        known = snapshotSha
    }

    private val snapshotSha by lazy {
        known?.value ?: requireNotNull(Hashes.of(snapshot)) { "no snapshot $snapshot" }
    }
    private val overlaySha by lazy { overlay?.let { requireNotNull(Hashes.of(it)) { "no overlay $it" } } }

    /** The header of the bundle file at [file] (relative to [out], `/`-separated). */
    fun header(file: String): GeneratedHeader {
        val directory =
            out
                .resolve(file)
                .toAbsolutePath()
                .normalize()
                .parent
        return GeneratedHeader(
            GeneratedInput(GeneratedHeader.relative(directory, snapshot), snapshotSha),
            overlay?.let { GeneratedInput(GeneratedHeader.relative(directory, it), requireNotNull(overlaySha)) },
            profile,
            generator,
        )
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
     * A tree: SHA-256 over `<relative path>\n<sha256 of its bytes>\n` for every content file below [root] (the loader's
     * walk, [TreeContent]: no dot-segment, no top-level `agents/` — ✅MW-44, so an agent edit never makes a generated
     * file stale, review-277 F4), in bytewise path order.
     */
    fun tree(root: Path): String {
        val files =
            Files.walk(root).use { paths ->
                paths
                    .filter { it.isRegularFile() }
                    .filter { file -> TreeContent.includes(root.relativize(file).map { it.name }) }
                    .toList()
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
