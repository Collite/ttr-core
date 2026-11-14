// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** MW S3.6 T1/T5 — `snapshot --from file:` (contracts §6, XM D-15), run as installed from a working directory. */
class SnapshotCommandSpec :
    FunSpec({
        fun exec(
            cwd: Path,
            vararg args: String,
        ): CliOutcome {
            val binary = Path.of(requireNotNull(System.getProperty("metadata.installedBinary"))).toAbsolutePath()
            val process = ProcessBuilder(listOf("$binary") + args).directory(cwd.toFile()).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            return CliOutcome(process.waitFor(), stdout, stderr)
        }

        val people = listOf("Jana Dvořáková", "Petr Svoboda", "Karel Novák", "petr.svoboda@example.cz")

        /** A working directory with the capture profile and an export of [records] tables, each with principals. */
        fun work(
            records: Int,
            padding: Int = 0,
        ): Path {
            // A large export passes the 5 MiB guard in bytes with few code points: each description is lengthened by
            // three-byte characters (U+2026).
            val pad = if (padding == 0) "" else " " + "\u2026".repeat(padding)
            val work = Files.createTempDirectory("s36-snapshot")
            work.resolve("profiles").createDirectories()
            work.resolve("profiles/cap.yaml").writeText(
                """
                profile: cap/1
                grammar: '>=0.22 <1.0'
                operations:
                  - {op: package, mapping: {}, default: demo}
                  - {op: kinds, at: /entity, mapping: {entita: entity}}
                  - {op: ignore, in: entity, at: /poznamka, reason: internal}
                  - {op: ignore, at: /audit, reason: tool state}
                """.trimIndent() + "\n",
            )
            val entities =
                (1..records).joinToString(",\n") { i ->
                    """    "T$i": {"popis": "Tabulka $i$pad", "poznamka": "interní $i", "owner": "${people[0]}", """ +
                        """"owners": [{"name": "${people[1]}", "email": "${people[3]}"}], "usageCount": $i, """ +
                        """"sloupce": {"kod": {"createdBy": "${people[2]}", "lastAccessed": "2026-11-01"}}}"""
                }
            work
                .resolve(
                    "export.json",
                ).writeText("{\"entity\": {\n$entities\n}, \"audit\": {\"by\": \"${people[0]}\"}}\n")
            return work
        }

        test("file: capture applies the profile's ignore, replaces principals by roles and drops usage counters") {
            val work = work(2)
            val out =
                exec(work, "snapshot", "--from", "file:export.json", "--profile", "cap/1", "--out", "sources/demo")
            out.exitCode shouldBe 0
            val text = work.resolve("sources/demo/export.yaml").readText()
            text shouldBe
                """
                entity:
                  T1:
                    popis: Tabulka 1
                    owner: owner
                    owners:
                    - owner
                    sloupce:
                      kod:
                        createdBy: author
                  T2:
                    popis: Tabulka 2
                    owner: owner
                    owners:
                    - owner
                    sloupce:
                      kod:
                        createdBy: author

                """.trimIndent()
            // Deterministic: a second capture writes the same bytes; a profile path works as its identity does.
            exec(work, "snapshot", "--from", "file:export.json", "--profile", "profiles/cap.yaml", "--out", "again")
                .exitCode shouldBe 0
            work.resolve("again/export.yaml").readText() shouldBe text
        }

        test("a 6 MiB snapshot is xm/snapshot-size without --large and written with it, with no principal name in it") {
            val work = work(2_000, padding = 1_000)
            work.resolve("export.json").readText().let { it.codePointCount(0, it.length) < 3_000_000 } shouldBe true
            (Files.size(work.resolve("export.json")) > 6L * 1024 * 1024) shouldBe true
            val refused = exec(work, "snapshot", "--from", "file:export.json", "--profile", "cap/1", "--out", "sources")
            refused.exitCode shouldBe 1
            refused.stdout shouldContain "error xm/snapshot-size: the snapshot is "
            work.resolve("sources").exists() shouldBe false
            val written =
                exec(
                    work,
                    "snapshot",
                    "--from",
                    "file:export.json",
                    "--profile",
                    "cap/1",
                    "--out",
                    "sources",
                    "--large",
                )
            written.exitCode shouldBe 0
            val text = work.resolve("sources/export.yaml").readText()
            (text.toByteArray().size > 5 * 1024 * 1024) shouldBe true
            // The grep test: no principal name survives, nor the ignored note or a counter.
            (people + listOf("interní", "usageCount", "lastAccessed")).forEach { (it in text) shouldBe false }
        }

        test("an export over P2's 3 000 000 code points is read and captured: the §1 budget is 6 000 000 (✅D-MW-75)") {
            val work = work(1)
            val long = "x".repeat(3_200_000)
            work.resolve("long.json").writeText("{\"entity\": {\"T\": {\"popis\": \"$long\"}}}\n")
            work.resolve("long.json").readText().let { it.codePointCount(0, it.length) > 3_000_000 } shouldBe true
            val out = exec(work, "snapshot", "--from", "file:long.json", "--profile", "cap/1", "--out", "s")
            out.exitCode shouldBe 0
            work.resolve("s/long.yaml").readText() shouldBe "entity:\n  T:\n    popis: $long\n"
        }

        test("<out>/.gitattributes marks the snapshot directory generated wherever --out is, once (✅R277-B)") {
            val work = work(1)
            ProcessBuilder("git", "init", "-q").directory(work.toFile()).start().waitFor() shouldBe 0
            work.resolve(".gitattributes").exists() shouldBe false
            val attributes = work.resolve("model/sources/.gitattributes")
            exec(work, "snapshot", "--from", "file:export.json", "--profile", "cap/1", "--out", "model/sources")
                .exitCode shouldBe 0
            attributes.readText() shouldBe "* linguist-generated=true -diff\n"
            exec(work, "snapshot", "--from", "file:export.json", "--profile", "cap/1", "--out", "model/sources")
                .exitCode shouldBe 0
            attributes.readText() shouldBe "* linguist-generated=true -diff\n"
            val check =
                ProcessBuilder("git", "check-attr", "linguist-generated", "diff", "--", "model/sources/export.yaml")
                    .directory(work.toFile())
                    .start()
            check.inputStream.bufferedReader().readText() shouldBe
                "model/sources/export.yaml: linguist-generated: true\nmodel/sources/export.yaml: diff: unset\n"
            check.waitFor() shouldBe 0
            Files.walk(work).use { paths ->
                paths.filter { it.fileName.toString() == ".gitattributes" }.toList()
            } shouldBe
                listOf(attributes)
            // Without a work tree the line still goes beside the snapshot, never to the working directory.
            val bare = work(1)
            exec(bare, "snapshot", "--from", "file:export.json", "--profile", "cap/1", "--out", "s").exitCode shouldBe 0
            bare.resolve(".gitattributes").exists() shouldBe false
            bare.resolve("s/.gitattributes").exists() shouldBe true
        }

        test("an --out that is a git work-tree root is refused, nothing written (review-277 N4 (b))") {
            val work = work(1)
            ProcessBuilder("git", "init", "-q").directory(work.toFile()).start().waitFor() shouldBe 0
            val root = exec(work, "snapshot", "--from", "file:export.json", "--profile", "cap/1", "--out", ".")
            root.exitCode shouldBe 2
            root.stderr shouldBe
                "ttr-metadata: --out . is a git work-tree root; snapshots go to a directory of their own\n"
            work.resolve(".gitattributes").exists() shouldBe false
            work.resolve("export.yaml").exists() shouldBe false
        }

        test("an export the profile does not fully know is captured, its unknown records kept (review-277 N1)") {
            val work = work(1)
            work.resolve("profiles/typed.yaml").writeText(
                """
                profile: typed/1
                grammar: '>=0.22 <1.0'
                operations:
                  - {op: package, mapping: {}, default: demo}
                  - {op: kinds, at: /entity, kind: /typ, mapping: {tabulka: entity}}
                """.trimIndent() + "\n",
            )
            work.resolve("typed.json").writeText(
                "{\"entity\": {\"T\": {\"typ\": \"tabulka\"}, \"V\": {\"typ\": \"pohled\", \"owner\": \"${people[0]}\"}}}\n",
            )
            val out = exec(work, "snapshot", "--from", "file:typed.json", "--profile", "typed/1", "--out", "s")
            out.exitCode shouldBe 0
            work.resolve("s/typed.yaml").readText() shouldBe
                "entity:\n  T:\n    typ: tabulka\n  V:\n    typ: pohled\n    owner: owner\n"
        }

        test("usage: an unknown adapter, a missing profile or export; --profile is required") {
            val work = work(1)
            exec(work, "snapshot", "--from", "openmetadata:x", "--profile", "cap/1", "--out", "s").exitCode shouldBe 2
            exec(
                work,
                "snapshot",
                "--from",
                "file:export.json",
                "--profile",
                "nope.yaml",
                "--out",
                "s",
            ).exitCode shouldBe
                2
            exec(work, "snapshot", "--from", "file:export.json", "--out", "s").exitCode shouldBe 2
            // A missing export is usage, on one line (review-277 F9); a malformed one is refused, its location once.
            val missing = exec(work, "snapshot", "--from", "file:gone.json", "--profile", "cap/1", "--out", "s")
            missing.exitCode shouldBe 2
            missing.stderr shouldBe "ttr-metadata: --from: no export file ${work.toRealPath().resolve("gone.json")}\n"
            work.resolve("bad.json").writeText("{\"entity\": [\n")
            val bad = exec(work, "snapshot", "--from", "file:bad.json", "--profile", "cap/1", "--out", "s")
            bad.exitCode shouldBe 1
            bad.stdout.lines().filter { it.isNotEmpty() }.single().let { line ->
                line shouldContain "bad.json:"
                line shouldContain ": error ys/"
                line.split("bad.json").size shouldBe 2
            }
        }
    })
