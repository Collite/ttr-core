// SPDX-License-Identifier: Apache-2.0
// Test names quote the acceptance paragraphs verbatim.
@file:Suppress("ktlint:standard:max-line-length")

package org.tatrman.ttr.metadata.md

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.model.*
import org.tatrman.ttr.metadata.source.*
import org.tatrman.ttr.semantics.md.mdErBindingDiagnostics
import java.nio.file.Files

/** Design §8.1–§8.5, verbatim; §8.6 is Hartland/CN-P6's acceptance, not this public fixture. */
class MdHopAcceptanceSpec :
    StringSpec({
        """1. A Hartland-shaped fixture with `md2db` + `er2db` + relations and **zero `md2er`**: every cubelet (R1), every dimension
   (R3) and every dimension attribute (R4 — incl. `Customer.state` landing on `customer_address`) derives uniquely;
   `revenue` is ambiguous without the union cubelet and `DERIVED_BEARER` with it (L-7); `Calendar.day` → `date_dim.d_date_sk`-class.""" {
            val (docs, resolver) = MdHopFixture.project()
            val hop = CompositeMdErHop(docs, resolver)
            for (line in Files.readAllLines(MdHopFixture.root.resolve("landings.tsv"))) {
                val (md, er) = line.split('\t')
                hop.erTargetOf(md).erTarget shouldBe er
            }
            hop.erTargetOf("md.measure.revenue").diagnostic shouldBe HopResult.AMBIGUOUS
            hop.erTargetOf("md.measure.revenue", "er.entity.sales") shouldBe
                HopResult("er.entity.sales.ext_sales_price", HopVia.DERIVED_BEARER)
        }
        """2. `md/er-ambiguous` on a dimension two facts point at different entities; `md/er-unmapped` on a cubelet whose table no
   entity binds; `md/er-hop-override` when an `md2er_dimension` disagrees with R3.""" {
            val (docs, resolver) =
                MdHopFixture.project(
                    MdHopFixture.sources() +
                        ("variant.ttrm" to Files.readString(MdHopFixture.root.resolve("conflicting.ttrm.variant"))),
                )
            val hop = CompositeMdErHop(docs, resolver)
            hop.erTargetOf("md.dimension.Customer").diagnostic shouldBe HopResult.AMBIGUOUS
            hop.erTargetOf("md.cubelet.orphan").diagnostic shouldBe HopResult.UNMAPPED
            val (pinned, pr) =
                MdHopFixture.project(
                    MdHopFixture.sources() +
                        ("variant.ttrm" to Files.readString(MdHopFixture.root.resolve("override.ttrm.variant"))),
                )
            CompositeMdErHop(pinned, pr).issues("md.dimension.Customer").single().slug shouldBe "md/er-hop-override"
        }
        """3. `md2er_dimension` with a one-hop `address.state` value resolves; missing key → `md/md2er-dimension-key`.""" {
            val binding =
                "model binding\ndef md2er_dimension pin { dimension: md.Customer, entity: er.entity.customer, " +
                    "attributes: { customerCode: sk, state: address.state } }"
            val (docs, resolver) = MdHopFixture.project(MdHopFixture.sources() + ("pin.ttrm" to binding))
            CompositeMdErHop(docs, resolver).erTargetOf("md.dimension.Customer.state") shouldBe
                HopResult("er.entity.customer_address.state", HopVia.EXPLICIT)
            val (bad, br) =
                MdHopFixture.project(
                    MdHopFixture.sources() + ("pin.ttrm" to binding.replace("customerCode: sk, ", "")),
                )
            mdErBindingDiagnostics(bad, br).any { it.code.id == "md/md2er-dimension-key" } shouldBe true
        }
        """4. The cn P2 hygiene golden re-run with the derivation: `md-grounded-hop-missing` rows close where the fixture allows;
   the TS and Kotlin reports agree byte-for-byte.""" {
            val root = MdHopFixture.root.toAbsolutePath()
            val snapshot = FileBasedSource("acceptance", 100, LocalFsStorage("acceptance", root)).load()
            snapshot.cn.byName("Customer")!!.grade shouldBe GroundingGrade.MD_GROUNDED
            snapshot.cn.byName("revenue")!!.grade shouldBe GroundingGrade.MD_GROUNDED
            snapshot.cn.concepts.values
                .count { it.grade == GroundingGrade.MD_GROUNDED_HOP_MISSING } shouldBe 0
            // Full CN report parity is pinned by CnHygieneSpec + TS md-hop.test.ts, on expected-derived.json.
        }
        """5. Derived roles: fact/dimension/bridge on the fixture; a `cnc roles:` claim that contradicts → `md/role-claim-mismatch`.""" {
            val sources = MdHopFixture.sources().toMutableMap()
            sources["er.ttrm"] =
                sources.getValue("er.ttrm").replace("def entity customer {", "def entity customer { roles: [fact],")
            val (docs, resolver) = MdHopFixture.project(sources)
            val report = mdHopReport(docs, resolver)
            report.roles.filter { it.role == "fact" }.size shouldBe 3
            report.roles.filter { it.role == "dimension" }.size shouldBe 3
            report.roles.filter { it.role == "bridge" }.size shouldBe 1
            report.issues.single { it.slug == "md/role-claim-mismatch" }.element shouldBe "er.entity.customer"
        }
    })
