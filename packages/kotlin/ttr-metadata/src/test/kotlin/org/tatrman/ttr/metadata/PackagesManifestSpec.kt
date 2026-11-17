// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path

/**
 * AU-export S1.5.9 T1 (b) (D-235): `[packages].layout` of a `modeler.toml`, read by the library. The outcomes are the
 * ones `MetadataLoadCli.readManifest` gave before the move (captured from its code at `f6b92ec`): a missing key is
 * `flexible`, an unknown value warns on its line and falls back to `flexible`, a malformed or unreadable file is an
 * error.
 */
class PackagesManifestSpec :
    StringSpec({
        fun manifest(text: String): Path {
            val dir = Files.createTempDirectory("packages-manifest")
            return dir.resolve("modeler.toml").also { Files.writeString(it, text) }
        }

        listOf(
            "off" to PackageLayout.Off,
            "strict" to PackageLayout.Strict,
            "flexible" to PackageLayout.Flexible,
        ).forEach { (value, expected) ->
            "layout = \"$value\" is $expected, no issue" {
                val (layout, issues) = PackagesManifest.read(manifest("[packages]\nlayout = \"$value\"\n"))
                layout shouldBe expected
                issues.shouldBeEmpty()
            }
        }

        "no [packages].layout key is flexible, no issue" {
            val (layout, issues) = PackagesManifest.read(manifest("[ttrp]\nextra-model-roots = [\"model\"]\n"))
            layout shouldBe PackageLayout.Flexible
            issues.shouldBeEmpty()
        }

        "an unknown value warns on the key's line and falls back to flexible" {
            val file = manifest("[ttrp]\nname = \"x\"\n\n[packages]\nlayout = \"loose\"\n")
            val (layout, issues) = PackagesManifest.read(file)
            layout shouldBe PackageLayout.Flexible
            issues shouldBe
                listOf(
                    LoadReportIssue(
                        file.toString(),
                        5,
                        "manifest-package-layout",
                        LoadIssue.Severity.WARNING,
                        "Unknown [packages].layout value 'loose'; expected flexible, strict, or off. Falling back to flexible.",
                    ),
                )
        }

        "a non-string value is an unknown value too" {
            val (layout, issues) = PackagesManifest.read(manifest("[packages]\nlayout = 3\n"))
            layout shouldBe PackageLayout.Flexible
            issues.map { it.code to it.message } shouldBe
                listOf(
                    "manifest-package-layout" to
                        "Unknown [packages].layout value '3'; expected flexible, strict, or off. Falling back to flexible.",
                )
        }

        "malformed TOML is one manifest-invalid error on the parser's line" {
            val file = manifest("[packages]\nlayout = \"off\"\n[packages\n")
            val (layout, issues) = PackagesManifest.read(file)
            layout shouldBe PackageLayout.Flexible
            issues shouldHaveSize 1
            issues.single().let {
                it.file shouldBe file.toString()
                it.line shouldBe 3
                it.code shouldBe "manifest-invalid"
                it.severity shouldBe LoadIssue.Severity.ERROR
            }
        }

        "an unreadable file is one manifest-invalid error without a line" {
            val file = Files.createTempDirectory("packages-manifest").resolve("modeler.toml")
            val (layout, issues) = PackagesManifest.read(file)
            layout shouldBe PackageLayout.Flexible
            issues shouldHaveSize 1
            issues.single().let {
                it.file shouldBe file.toString()
                it.line shouldBe -1
                it.code shouldBe "manifest-invalid"
                it.severity shouldBe LoadIssue.Severity.ERROR
                it.message shouldStartWith "cannot read modeler.toml: "
            }
        }
    })
