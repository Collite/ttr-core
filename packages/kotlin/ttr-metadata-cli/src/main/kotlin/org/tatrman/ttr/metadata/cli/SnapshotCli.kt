// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import org.tatrman.ttr.imports.ImportProfile
import org.tatrman.ttr.imports.ReaderInputException
import org.tatrman.ttr.metadata.source.Captured
import org.tatrman.ttr.metadata.source.SnapshotCapture
import org.tatrman.ttr.profile.ProfileException
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * Captures a file as YAML or OpenMetadata as JSON and makes sure `<out>/.gitattributes` marks the snapshots generated
 * (`SnapshotCapture.ATTRIBUTES`,
 * ✅R277-B). Exit 0 written, 1 refused (`xm/snapshot-size`, a profile error), 2 usage (an unknown adapter, a missing
 * export or profile file, an `--out` that is a work-tree root).
 */
object SnapshotCli {
    fun run(
        from: String,
        profile: String,
        out: Path,
        large: Boolean,
        url: String? = null,
        tokenEnv: String? = null,
    ): CliOutcome {
        val cwd = Path.of("").toAbsolutePath().normalize()
        // `* …` in a work tree's root `.gitattributes` would mark the whole repository generated (review-277 N4 (b)).
        val directory = out.toAbsolutePath().normalize()
        if (Files.exists(directory.resolve(".git"))) {
            return CliOutcome(
                2,
                "",
                "ttr-metadata: --out $out is a git work-tree root; snapshots go to a directory of their own\n",
            )
        }
        val captured =
            if (from == "openmetadata") {
                captureOpenMetadata(url, tokenEnv, profile, large)
            } else {
                if (url != null || tokenEnv != null) return usage("--url and --token-env require --from openmetadata")
                SnapshotCapture.capture(from, profile, cwd, large)
            }
        return when (captured) {
            is Captured.Usage -> usage(captured.message)
            is Captured.Refused ->
                CliOutcome(1, "${captured.file}:${captured.line}: error ${captured.message}\n", "")
            is Captured.Written -> write(directory, out, mapOf(captured.name to captured.text), captured.name)
            is Captured.Batch ->
                try {
                    OpenMetadataSnapshots.write(directory, captured.files)
                    CliOutcome(0, "", "ttr-metadata: wrote ${captured.files.size} OpenMetadata snapshots to $out\n")
                } catch (e: ReaderInputException) {
                    usage("--out $out: ${e.message}")
                } catch (_: IOException) {
                    usage("--out $out: could not write the OpenMetadata snapshot")
                }
        }
    }

    internal fun captureOpenMetadata(
        url: String?,
        tokenEnv: String?,
        profile: String,
        large: Boolean,
        tablesOnly: Boolean = false,
    ): Captured {
        val endpoint = url?.let(::endpoint) ?: return Captured.Usage("--url must be an HTTP(S) OpenMetadata base URL")
        val name =
            tokenEnv?.takeIf { ENV_NAME.matches(it) }
                ?: return Captured.Usage("--token-env needs an environment variable name")
        val token =
            System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() && it.all { char -> char in '!'..'~' } }
                ?: return Captured.Usage("--token-env: the named variable has no valid bearer token")
        return try {
            val chain = ImportProfile.resolve(profile).chain
            if (tablesOnly && chain.none { "${it.name}/${it.major}" == "om-tables/1" }) {
                return Captured.Usage("--profile: import-schema from openmetadata needs om-tables/1")
            }
            SnapshotCapture.captureOpenMetadata(endpoint, token, chain, large)
        } catch (e: ReaderInputException) {
            Captured.Usage(e.message.orEmpty())
        } catch (e: ProfileException) {
            Captured.Refused(e.location.file, e.location.line, e.location.column, "${e.code}: ${e.detail}")
        }
    }

    private fun endpoint(value: String): URI? =
        try {
            URI(value).takeIf {
                it.scheme in listOf("http", "https") &&
                    it.host != null &&
                    it.rawUserInfo == null &&
                    it.rawQuery == null &&
                    it.rawFragment == null
            }
        } catch (_: URISyntaxException) {
            null
        }

    private fun write(
        directory: Path,
        display: Path,
        files: Map<String, String>,
        label: String,
    ): CliOutcome {
        for (name in files.keys + ".gitattributes") {
            val path = directory.resolve(name)
            if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                return usage("--out $display: $name is not a regular file")
            }
        }
        return try {
            Files.createDirectories(directory)
            files.forEach { (name, text) -> Files.writeString(directory.resolve(name), text) }
            SnapshotCapture.attributes(directory)
            CliOutcome(0, "", "ttr-metadata: wrote $label to $display\n")
        } catch (_: IOException) {
            usage("--out $display: could not write the snapshot")
        }
    }

    private fun usage(message: String) = CliOutcome(2, "", "ttr-metadata: $message\n")

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
