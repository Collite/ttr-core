// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileSpan
import org.tatrman.ttr.yaml.CanonicalYamlException
import org.tatrman.ttr.yaml.readBoundedYaml
import java.io.IOException
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

    /**
     * The pointer of the record collection the front-end's trees put the source's objects under (`/subjects`, `/rows`),
     * or null; the report folds the key beneath it to a star, so a PR body groups one ignored key over every record.
     */
    val records: String? get() = null

    fun parse(files: List<Path>): List<ForeignTree>
}

/**
 * Each input with the label its tree carries: its file name. Two inputs with one name are refused, since the label is
 * what spans, the report and a profile's `files` selectors read.
 */
internal fun labelled(files: List<Path>): List<Pair<Path, String>> {
    val names = files.map { it.fileName.toString() }
    names.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
        throw ReaderInputException("two inputs are named $it; a reader labels each input by its file name")
    }
    return files.zip(names)
}

/** A reader's input problem the caller should show as a usage error (exit 2): no file, unreadable, a name twice. */
class ReaderInputException(
    message: String,
) : IllegalArgumentException(message)

/**
 * The `json` front-end: any JSON (or YAML) document, read through ttr-yaml's located nodes. JSON is YAML 1.2, so one
 * reader serves both, with the P2 safety policy (one document, no duplicate keys, no anchors or tags, the §1 budget).
 */
object JsonFrontEnd : FrontEnd {
    override val name = "json"

    override fun parse(files: List<Path>): List<ForeignTree> =
        labelled(files).map { (file, name) -> ForeignTree.read(text(file), name, name) }

    /**
     * The file as strict UTF-8, a leading byte-order mark dropped: read through ttr-yaml's bounded reader, so an input
     * over the reader budget is refused before it is decoded, and malformed UTF-8 is a located error at its line and
     * column (labelled by the file name, as the tree is).
     */
    internal fun text(file: Path): String {
        if (!Files.isRegularFile(file)) throw ReaderInputException("no input file $file")
        val text =
            try {
                readBoundedYaml(file, "Input")
            } catch (e: IOException) {
                throw ReaderInputException("cannot read input $file (${e.javaClass.simpleName} ${e.message})")
            } catch (e: CanonicalYamlException) {
                val name = file.fileName.toString()
                throw ProfileException(
                    "ys/profile-invalid",
                    ProfileSpan(name, e.line, e.column, e.line, e.column, ""),
                    e.detail,
                )
            }
        return text.removePrefix("\uFEFF")
    }
}
