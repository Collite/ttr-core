// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import org.tatrman.ttr.profile.ForeignTree
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/**
 * MW-P5 S5.1 (contracts §1) — a front-end is a pure parser: the source format's files become located foreign trees,
 * one per file in input order, which the profile engine then reads. It never restructures, renames or drops anything;
 * that is the profile's job. Each tree is labelled with its file name, so spans, the report and a profile's `files`
 * selectors and `package` stems read the same on every machine.
 */
interface FrontEnd {
    /** The name `--from` and the report use (`json`, later `turtle`, `csv`, `xmi`, `pdm`). */
    val name: String

    fun parse(files: List<Path>): List<ForeignTree>
}

/** A reader's input problem the caller should show as a usage error (exit 2): no file, not UTF-8, a name twice. */
class ReaderInputException(
    message: String,
) : IllegalArgumentException(message)

/**
 * The `json` front-end: any JSON (or YAML) document, read through ttr-yaml's located nodes. JSON is YAML 1.2, so one
 * reader serves both, with the P2 safety policy (one document, no duplicate keys, no anchors or tags, the §1 budget).
 */
object JsonFrontEnd : FrontEnd {
    override val name = "json"

    override fun parse(files: List<Path>): List<ForeignTree> {
        val names = files.map { it.fileName.toString() }
        names.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw ReaderInputException("two inputs are named $it; a reader labels each input by its file name")
        }
        return files.zip(names).map { (file, name) -> ForeignTree.read(text(file), name, name) }
    }

    /** The file as strict UTF-8, a leading byte-order mark dropped. */
    internal fun text(file: Path): String {
        if (!Files.isRegularFile(file)) throw ReaderInputException("no input file $file")
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text =
            try {
                decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString()
            } catch (e: CharacterCodingException) {
                throw ReaderInputException("input $file is not UTF-8")
            }
        return text.removePrefix("\uFEFF")
    }
}
