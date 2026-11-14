// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import org.tatrman.ttr.metadata.source.Captured
import org.tatrman.ttr.metadata.source.SnapshotCapture
import java.nio.file.Files
import java.nio.file.Path

/**
 * MW S3.6 T5 — `snapshot --from file:<path> --profile <p> --out <dir> [--large]` (contracts §6). Writes
 * `<out>/<name>.yaml` and makes sure `<out>/.gitattributes` marks the snapshots generated (`SnapshotCapture.ATTRIBUTES`,
 * ✅R277-B). Exit 0 written, 1 refused (`xm/snapshot-size`, a profile error), 2 usage (an unknown adapter, a missing
 * export or profile file).
 */
object SnapshotCli {
    fun run(
        from: String,
        profile: String,
        out: Path,
        large: Boolean,
    ): CliOutcome {
        val cwd = Path.of("").toAbsolutePath().normalize()
        return when (val captured = SnapshotCapture.capture(from, profile, cwd, large)) {
            is Captured.Usage -> CliOutcome(2, "", "ttr-metadata: ${captured.message}\n")
            is Captured.Refused ->
                CliOutcome(1, "${captured.file}:${captured.line}: error ${captured.message}\n", "")
            is Captured.Written -> {
                val directory = out.toAbsolutePath().normalize()
                Files.createDirectories(directory)
                Files.writeString(directory.resolve(captured.name), captured.text)
                SnapshotCapture.attributes(directory)
                CliOutcome(0, "", "ttr-metadata: wrote ${captured.name} to $out\n")
            }
        }
    }
}
