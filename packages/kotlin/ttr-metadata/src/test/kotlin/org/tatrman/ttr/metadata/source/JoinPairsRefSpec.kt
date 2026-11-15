// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.model.Relation
import java.nio.file.Path

/**
 * MW S3.7b review-298 R1 (✅R298-A): what `join-pairs` writes loads as the relation's attributes. The dialect tree's
 * relation `DodavkaOdberatele` (`10-vazby.yaml`, a two-pair legacy join over the 07-fanout entities) is loaded through the
 * `yaml` source, and every side of every join pair must be the qname of an attribute the model knows — the
 * `er.entity.<Entity>.<attr>` ref form the loader resolves, not the two-part text as written.
 */
class JoinPairsRefSpec :
    FunSpec({
        test("every join-pairs side of DodavkaOdberatele is a known attribute qname") {
            val tree = Path.of("../../../tests/conformance/dialect/demo-cs/tree")
            val loaded = SnapshotDump.load(YamlFileSource("t", 100, LocalFsStorage("t", tree)))
            val model = requireNotNull(loaded.model) { "no model: ${loaded.report}" }
            val objects = model.objectByQname()
            val relation =
                objects.values.filterIsInstance<Relation>().single { it.qname.name == "DodavkaOdberatele" }
            relation.joinPairs.size shouldBe 2
            relation.joinPairs.forEachIndexed { index, pair ->
                listOf("from" to pair.fromAttr, "to" to pair.toAttr).forEach { (side, qname) ->
                    withClue("pair $index $side = $qname") {
                        objects[qname]?.kind shouldBe "attribute"
                    }
                }
            }
        }
    })
