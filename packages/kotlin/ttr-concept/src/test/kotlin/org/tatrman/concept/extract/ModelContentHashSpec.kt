// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.extract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.concept.cli.ModelContentHash
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/**
 * review-158 F2 (⚑R158-A) — [ModelContentHash]: the hash `index.json` pins and Pinakes recomputes at pack load. The
 * algorithm is pinned on a three-file tree (the digest below is Python's `hashlib.sha256` over the same feed); it reads
 * the model files only, by their path under the root; it ignores where the root is and when a file was touched.
 */
class ModelContentHashSpec :
    FunSpec({
        val tree =
            mapOf(
                "er.ttrm" to "model er\n",
                "sub/cn.ttrm" to "model cn\n",
                "a.ttr" to "model db\n",
            )

        test("sha256 over (relative path, NUL, bytes, NUL) per model file, in path order") {
            // python3: h = sha256(); for rel, body in sorted(tree): h.update(rel + b"\0" + body + b"\0")
            ModelContentHash.of(ExtractWorlds.write(tree)) shouldBe
                "sha256:fe7837ac010a91b8826c4b35c59150ccbcddd2aa17f043da810bb550f010465a"
        }

        test("layout files, sidecars and other files are not the model") {
            val bare = ModelContentHash.of(ExtractWorlds.write(tree))
            val noisy =
                ExtractWorlds.write(
                    tree +
                        mapOf(
                            "layouts/sales.ttrg" to "graph\n",
                            "layouts/sales.ttrl" to "{}\n",
                            "connections.toml" to "[erp]\n",
                            "README.md" to "# model\n",
                            "types/generated/index.json" to "{}\n",
                        ),
                )
            ModelContentHash.of(noisy) shouldBe bare
        }

        test("another root, a touched file: the same hash; one byte changed: another") {
            val a = ExtractWorlds.write(tree)
            val b = ExtractWorlds.write(tree)
            Files.setLastModifiedTime(b.resolve("er.ttrm"), FileTime.fromMillis(86_400_000L))
            ModelContentHash.of(b) shouldBe ModelContentHash.of(a)
            Files.writeString(b.resolve("sub/cn.ttrm"), "model cN\n")
            ModelContentHash.of(b) shouldNotBe ModelContentHash.of(a)
        }

        test("a file's path is part of the hash") {
            ModelContentHash.of(ExtractWorlds.write(mapOf("x/er.ttrm" to "model er\n"))) shouldNotBe
                ModelContentHash.of(ExtractWorlds.write(mapOf("y/er.ttrm" to "model er\n")))
        }
    })
