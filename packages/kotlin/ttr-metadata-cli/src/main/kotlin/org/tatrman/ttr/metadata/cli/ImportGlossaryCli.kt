// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import org.tatrman.ttr.imports.CsvFrontEnd
import org.tatrman.ttr.imports.FrontEnd
import org.tatrman.ttr.imports.RdfFrontEnd
import java.nio.file.Path

/**
 * MW-P5 S5.2 T5 — `import-glossary --from <file> --profile skos/1|om-glossary/1 --out <dir>` (contracts §2): a glossary
 * export through the reader harness, as `import-schema --from file:` runs one (same outputs, report, `--dry-run`, exit
 * codes). The front-end is `--syntax`, else the file's extension: `.ttl` turtle, `.rdf`/`.owl`/`.xml` rdfxml, `.csv` csv.
 */
object ImportGlossaryCli {
    val SYNTAXES: Map<String, FrontEnd> =
        linkedMapOf("turtle" to RdfFrontEnd.TURTLE, "rdfxml" to RdfFrontEnd.RDFXML, "csv" to CsvFrontEnd)

    private val EXTENSIONS =
        mapOf("ttl" to "turtle", "rdf" to "rdfxml", "owl" to "rdfxml", "xml" to "rdfxml", "csv" to "csv")

    fun run(
        from: String,
        syntax: String?,
        profile: String,
        out: Path,
        root: Path?,
        overlay: Path?,
        packageName: String?,
        dryRun: Boolean,
        json: Boolean,
    ): CliOutcome {
        val input = Path.of(from.removePrefix("file:").ifEmpty { return usage("--from needs a file") })
        val extension =
            input.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase()
        val name =
            syntax ?: EXTENSIONS[extension]
                ?: return usage("--from $from: no syntax for .$extension; name one with --syntax turtle|rdfxml|csv")
        val frontEnd = SYNTAXES.getValue(name)
        return ImportSchemaCli.generate(
            "import-glossary",
            frontEnd,
            input,
            profile,
            out,
            root,
            overlay,
            packageName,
            dryRun,
            json,
        )
    }

    private fun usage(message: String) = CliOutcome(2, "", "ttr-metadata: $message\n")
}
