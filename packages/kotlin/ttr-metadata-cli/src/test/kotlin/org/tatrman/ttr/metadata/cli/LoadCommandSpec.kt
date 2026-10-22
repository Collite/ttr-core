// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Path

class LoadCommandSpec :
    StringSpec({
        fun world(name: String) = Path.of(javaClass.getResource("/worlds/$name")!!.toURI())

        fun run(
            name: String,
            strict: Boolean = true,
            expectVersion: String? = null,
        ) = MetadataLoadCli.run(world(name), strict = strict, expectVersion = expectVersion, json = true)

        fun payload(out: CliOutcome) = Json.parseToJsonElement(out.stdout).jsonObject

        "clean strict load reports source objects and no issues" {
            val out = run("clean")
            out.exitCode shouldBe 0
            val doc = payload(out)
            doc["version"]!!.jsonPrimitive.content shouldBe System.getProperty("metadata.expectedVersion")
            doc["sources"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content } shouldBe
                listOf("model/items.ttrm")
            doc["sources"]!!
                .jsonArray
                .single()
                .jsonObject["objects"]!!
                .jsonPrimitive.int shouldBe 1
            doc["issues"]!!.jsonArray.size shouldBe 0
        }
        "a dropped file is a located error and strict fails while permissive succeeds" {
            val out = run("dropped-file")
            out.exitCode shouldBe 1
            val issue =
                payload(out)["issues"]!!
                    .jsonArray
                    .first {
                        it.jsonObject["code"]!!.jsonPrimitive.content ==
                            "dropped-file"
                    }.jsonObject
            issue["severity"]!!.jsonPrimitive.content shouldBe "error"
            issue["file"]!!.jsonPrimitive.content shouldBe "model/broken.ttrm"
            (issue["line"]!!.jsonPrimitive.int > 0) shouldBe true
            run("dropped-file", strict = false).exitCode shouldBe 0
        }
        "a duplicate across files fails strict reconciliation" {
            val out = run("reconcile-error")
            out.exitCode shouldBe 1
            payload(
                out,
            )["issues"]!!.jsonArray.any { it.jsonObject["code"]!!.jsonPrimitive.content == "duplicate-qname" } shouldBe
                true
        }
        "duplicate messages use stable checkout relative locations" {
            val out = run("reconcile-error")
            out.stdout.contains("model/b.ttrm:2") shouldBe true
            out.stdout.contains(world("reconcile-error").toString()) shouldBe false
            out.stderr.contains(world("reconcile-error").toString()) shouldBe false
        }
        "package layout follows the manifest policy" {
            run("layout-off").exitCode shouldBe 0
            payload(run("layout-off"))["issues"]!!.jsonArray.size shouldBe 0
            val strict = run("layout-strict")
            strict.exitCode shouldBe 1
            val issue = payload(strict)["issues"]!!.jsonArray.single().jsonObject
            issue["code"]!!.jsonPrimitive.content shouldBe "package-mismatch"
            issue["severity"]!!.jsonPrimitive.content shouldBe "error"
            val flexible = run("layout-default")
            flexible.exitCode shouldBe 0
            payload(flexible)["issues"]!!
                .jsonArray
                .single()
                .jsonObject["severity"]!!
                .jsonPrimitive.content shouldBe
                "warning"
        }
        "bad TOML applies defaults and reports a manifest error" {
            val out = run("manifest-invalid")
            out.exitCode shouldBe 1
            val issues = payload(out)["issues"]!!.jsonArray.map { it.jsonObject }
            issues.count { it["code"]!!.jsonPrimitive.content == "manifest-invalid" } shouldBe 1
            issues
                .first {
                    it["code"]!!.jsonPrimitive.content == "manifest-invalid"
                }["file"]!!
                .jsonPrimitive.content shouldBe
                "modeler.toml"
            issues
                .first {
                    it["code"]!!.jsonPrimitive.content == "package-mismatch"
                }["severity"]!!
                .jsonPrimitive.content shouldBe
                "warning"
            run("manifest-invalid", strict = false).exitCode shouldBe 0
        }
        "a missing model directory has a readable issue in both modes" {
            for (strict in listOf(true, false)) {
                val out = run("no-model-dir", strict)
                out.exitCode shouldBe 1
                val issue = payload(out)["issues"]!!.jsonArray.single().jsonObject
                issue["code"]!!.jsonPrimitive.content shouldBe "no-model-dir"
                issue["file"]!!.jsonPrimitive.content shouldBe "model"
                issue["message"]!!.jsonPrimitive.content shouldBe "no model/ directory in the checkout"
                out.stdout.contains(world("no-model-dir").toString()) shouldBe false
                out.stderr.contains(world("no-model-dir").toString()) shouldBe false
            }
        }
        "diagnostic paths resolve against the checkout rather than the process directory" {
            val project = world("clean")
            checkoutRelative(project, "modeler.toml") shouldBe "modeler.toml"
            checkoutRelative(project, "model/../modeler.toml") shouldBe "modeler.toml"
            checkoutRelative(project, project.resolve("modeler.toml").toString()) shouldBe "modeler.toml"
            checkoutRelative(project, null) shouldBe null
        }
        "usage errors are distinct from model errors and help succeeds" {
            for (args in listOf(listOf("load", "x", "--strcit"), listOf("load"), listOf("load", "a", "b"))) {
                runMetadataCommand(args).exitCode shouldBe 64
            }
            runMetadataCommand(listOf("--help")).exitCode shouldBe 0
            runMetadataCommand(listOf("load", "--help")).exitCode shouldBe 0
        }
        "version mismatch and missing manifest use their contracted exits" {
            run("clean", expectVersion = "9.9.9").exitCode shouldBe 2
            run("no-manifest").exitCode shouldBe 3
        }
        "human diagnostics stay on stderr without json" {
            val out = MetadataLoadCli.run(world("dropped-file"), strict = true)
            out.exitCode shouldBe 1
            out.stdout shouldBe ""
            out.stderr.contains("broken.ttrm") shouldBe true
        }
    })
