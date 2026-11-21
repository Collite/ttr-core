// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import org.tatrman.ttr.imports.ReaderInputException
import org.tatrman.ttr.metadata.export.Hashes
import org.tatrman.ttr.metadata.source.SnapshotCapture
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** The manifest selects the current batch and records which generated files a recapture can retire. */
internal object OpenMetadataSnapshots {
    private const val MANIFEST = "openmetadata-snapshot.txt"
    private const val ATTRIBUTES = ".gitattributes"
    private val ENTRY = Regex("([a-f0-9]{64}) ([a-z0-9_]+(?:[.][a-z0-9_]+)*[.]json)")

    fun hasManifest(directory: Path): Boolean = Files.exists(directory.resolve(MANIFEST), NOFOLLOW_LINKS)

    fun inputs(directory: Path): List<Path>? =
        manifest(directory)?.map { (name, hash) ->
            val path = directory.resolve(name)
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Hashes.of(path) != hash) {
                throw ReaderInputException(
                    "OpenMetadata snapshot $name is missing or changed in $directory; recapture it",
                )
            }
            path
        }

    fun write(
        directory: Path,
        files: Map<String, String>,
    ) {
        val attributes = directory.resolve(ATTRIBUTES)
        checkRegularFile(attributes)
        Files.createDirectories(directory)
        FileChannel.open(attributes, CREATE, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                // Keep the lock on the same descriptor used to update .gitattributes.
                val text = Channels.newInputStream(channel).readAllBytes().toString(Charsets.UTF_8)
                if (text.lines().none { it.trim() == SnapshotCapture.ATTRIBUTES }) {
                    val separator = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
                    Channels.newOutputStream(channel).write("$separator${SnapshotCapture.ATTRIBUTES}\n".toByteArray())
                }
                publish(directory, files)
            }
        }
    }

    fun <T> read(
        directory: Path,
        action: () -> T,
    ): T {
        val attributes = directory.resolve(ATTRIBUTES)
        if (!Files.isRegularFile(attributes, NOFOLLOW_LINKS)) return action()
        return FileChannel.open(attributes, READ, NOFOLLOW_LINKS).use { channel ->
            channel.lock(0, Long.MAX_VALUE, true).use { action() }
        }
    }

    /** Publish a complete capture at its content address, then leave its bytes for any generated files that name it. */
    fun retain(
        staged: Path,
        dest: Path,
        hash: String,
    ) {
        fun matches(): Boolean =
            Files.isDirectory(dest, NOFOLLOW_LINKS) &&
                Files.newDirectoryStream(dest).use { files -> files.all { Files.isRegularFile(it, NOFOLLOW_LINKS) } } &&
                Hashes.tree(dest) == hash

        if (Files.exists(dest, NOFOLLOW_LINKS)) {
            if (!matches()) throw ReaderInputException("retained snapshot $dest contains changed files")
            return
        }
        Files.createDirectories(dest.parent)
        val pending = Files.createTempDirectory(dest.parent, ".capture-")
        try {
            Files.newDirectoryStream(staged).use { files ->
                files.forEach { Files.copy(it, pending.resolve(it.fileName)) }
            }
            if (Hashes.tree(pending) != hash) throw IOException("staged snapshot changed before publication")
            try {
                Files.move(pending, dest, ATOMIC_MOVE)
            } catch (e: IOException) {
                // A concurrent invocation can have retained the same complete capture.
                if (!matches()) throw e
            }
        } finally {
            if (Files.exists(pending, NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(pending).use { files -> files.forEach(Files::delete) }
                Files.delete(pending)
            }
        }
    }

    private fun publish(
        directory: Path,
        files: Map<String, String>,
    ) {
        val previous = manifest(directory).orEmpty()
        val current = files.mapValues { (_, text) -> Hashes.bytes(text.toByteArray(Charsets.UTF_8)) }
        val manifest = current.entries.sortedBy { it.key }.joinToString("") { (name, hash) -> "$hash $name\n" }
        val payload = files + (MANIFEST to manifest)
        payload.keys.forEach { checkRegularFile(directory.resolve(it)) }
        for (name in files.keys) {
            val path = directory.resolve(name)
            if (Files.isRegularFile(path, NOFOLLOW_LINKS) && Hashes.of(path) != previous[name]) {
                throw ReaderInputException("$name is not an unchanged file from the previous capture")
            }
        }

        val pending = Files.createTempDirectory(directory, ".capture-")
        try {
            payload.forEach { (name, text) -> Files.writeString(pending.resolve(name), text) }
            for (name in files.keys) {
                Files.move(pending.resolve(name), directory.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING)
            }
            for ((name, hash) in previous - current.keys) {
                val path = directory.resolve(name)
                if (Files.isRegularFile(path, NOFOLLOW_LINKS) && Hashes.of(path) == hash) Files.delete(path)
            }
            // Publish membership last. Hash checks fail closed after an interrupted publish.
            Files.move(pending.resolve(MANIFEST), directory.resolve(MANIFEST), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.newDirectoryStream(pending).use { paths -> paths.forEach(Files::delete) }
            Files.delete(pending)
        }
    }

    private fun checkRegularFile(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw ReaderInputException("${path.fileName} is not a regular file")
        }
    }

    private fun manifest(directory: Path): Map<String, String>? {
        val path = directory.resolve(MANIFEST)
        if (!hasManifest(directory)) return null
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw ReaderInputException("$MANIFEST is not a regular file in $directory")
        }
        val lines = Files.readAllLines(path)
        return buildMap {
            for (line in lines) {
                val (hash, name) =
                    ENTRY.matchEntire(line)?.destructured
                        ?: throw IOException("invalid OpenMetadata snapshot manifest")
                if (put(name, hash) != null) throw IOException("duplicate filename in OpenMetadata snapshot manifest")
            }
        }
    }
}
