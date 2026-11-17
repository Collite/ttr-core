// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import org.tomlj.Toml
import java.nio.file.Path

/**
 * `[packages].layout` of a `modeler.toml` and the manifest's own issues: the one reading of the rule, shared by the
 * `ttr-metadata` CLI (`load`, `lint`, `export`) and `ttrp emit` / `ttrp check` (AU-export S1.5.4, D-173). It lives in
 * the library since S1.5.9 (D-235), so a model reader never needs the CLI to read it. A missing key is `flexible`; an
 * unknown value warns and falls back to `flexible`; an unreadable or malformed file is an error.
 */
object PackagesManifest {
    fun read(manifest: Path): Pair<PackageLayout, List<LoadReportIssue>> {
        val parsed =
            runCatching { Toml.parse(manifest) }.getOrElse { error ->
                return PackageLayout.Flexible to
                    listOf(
                        LoadReportIssue(
                            manifest.toString(),
                            -1,
                            "manifest-invalid",
                            LoadIssue.Severity.ERROR,
                            "cannot read modeler.toml: ${error.message}",
                        ),
                    )
            }
        if (parsed.hasErrors()) {
            val error = parsed.errors().first()
            return PackageLayout.Flexible to
                listOf(
                    LoadReportIssue(
                        manifest.toString(),
                        error.position().line(),
                        "manifest-invalid",
                        LoadIssue.Severity.ERROR,
                        error.toString(),
                    ),
                )
        }
        val layout = parsed.get("packages.layout") ?: return PackageLayout.Flexible to emptyList()
        val policy =
            when (layout) {
                "off" -> PackageLayout.Off
                "strict" -> PackageLayout.Strict
                "flexible" -> PackageLayout.Flexible
                else -> null
            }
        return if (policy != null) {
            policy to emptyList()
        } else {
            PackageLayout.Flexible to
                listOf(
                    LoadReportIssue(
                        manifest.toString(),
                        parsed.inputPositionOf("packages.layout")?.line() ?: -1,
                        "manifest-package-layout",
                        LoadIssue.Severity.WARNING,
                        "Unknown [packages].layout value '$layout'; expected flexible, strict, or off. Falling back to flexible.",
                    ),
                )
        }
    }
}
