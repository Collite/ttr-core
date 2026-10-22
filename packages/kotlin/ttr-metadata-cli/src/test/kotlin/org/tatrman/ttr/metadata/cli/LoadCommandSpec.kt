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
            )["issues"]!!.jsonArray.any { it.jsonObject["code"]!!.jsonPrimitive.content == "reconcile-error" } shouldBe
                true
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
