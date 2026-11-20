// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.tatrman.ttr.imports.ReaderInputException
import org.tatrman.ttr.metadata.export.Hashes
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Direct imports replace only files that this destination still holds byte-for-byte from a prior publication. */
internal object DirectImportOutputs {
    private const val LEDGER_HEADER = "ttr-direct-import-outputs/1\n"

    fun publish(
        out: Path,
        staged: Path,
        retained: Path,
        snapshotHash: String,
        files: Map<String, String>,
    ) {
        val state = retained.parent
        Files.createDirectories(state)
        val lock = state.resolve(".output-lock")
        if (Files.exists(lock, NOFOLLOW_LINKS) && !Files.isRegularFile(lock, NOFOLLOW_LINKS)) {
            throw ReaderInputException("invalid direct import output lock $lock")
        }
        FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                val ledger = state.resolve("generated-outputs")
                val previous = readLedger(ledger)
                val next = files.mapValues { (_, text) -> setOf(Hashes.bytes(text.toByteArray(Charsets.UTF_8))) }
                val pending =
                    next.mapValues { (name, proposed) ->
                        val actual = requireOwned(out, name, previous[name].orEmpty())
                        if (actual == null) proposed else proposed + actual
                    }
                OpenMetadataSnapshots.retain(staged, retained, snapshotHash)
                // During recovery either exact version can be present. After success, only the new version is owned.
                writeLedger(ledger, previous + pending)
                files.forEach { (name, text) ->
                    requireOwned(out, name, previous[name].orEmpty())
                    writeAtomically(out.resolve(name), text)
                }
                writeLedger(ledger, previous + next)
            }
        }
    }

    private fun requireOwned(
        out: Path,
        name: String,
        owned: Set<String>,
    ): String? {
        val actual = existingHash(out.resolve(name))
        if (actual != null && actual !in owned) {
            throw ReaderInputException("refusing to overwrite authored or edited output $name")
        }
        return actual
    }

    private fun existingHash(path: Path): String? =
        when {
            Files.isRegularFile(path, NOFOLLOW_LINKS) -> Hashes.of(path)
            !Files.exists(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS) -> null
            else -> throw ReaderInputException("refusing to overwrite special output $path")
        }

    private fun readLedger(path: Path): Map<String, Set<String>> {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return emptyMap()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw ReaderInputException("invalid direct import ledger $path")
        val text = Files.readString(path)
        if (!text.startsWith(LEDGER_HEADER)) throw ReaderInputException("invalid direct import ledger $path")
        return try {
            Json.decodeFromString(text.removePrefix(LEDGER_HEADER))
        } catch (_: SerializationException) {
            throw ReaderInputException("invalid direct import ledger $path")
        }
    }

    private fun writeLedger(
        path: Path,
        owned: Map<String, Set<String>>,
    ) = writeAtomically(path, LEDGER_HEADER + Json.encodeToString<Map<String, Set<String>>>(owned.toSortedMap()))

    private fun writeAtomically(
        path: Path,
        text: String,
    ) {
        val pending = Files.createTempFile(path.parent, ".import-", ".tmp")
        try {
            Files.writeString(pending, text)
            Files.move(pending, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (e: IOException) {
            throw IOException("cannot write $path: ${e.message}", e)
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}
