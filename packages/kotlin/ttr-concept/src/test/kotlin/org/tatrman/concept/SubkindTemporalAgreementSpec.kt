// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * review-175 F9 — `cn/subkind-temporal-definition` has two checkers: the semantic layer's portable text check (a load
 * error here, the editor's diagnostic) and `ttr-concept`'s AST lint over the parsed definition (what `materialize`
 * refuses with). They agree on what is temporal: `:as_of` (written, or read by `within(…, last:)` / `age(…)`), a
 * `:name`, a DC-12 predicate — `within(…)` in either form is one — or a phase / valued-state read.
 */
class SubkindTemporalAgreementSpec :
    StringSpec({
        val cases =
            listOf(
                "within(created, last: days(30))" to true,
                "within(created, from: '2026-01-01', to: '2026-12-31')" to true,
                "age(since: created) > 30" to true,
                "created < :as_of" to true,
                "created >= '2026-01-01'" to false,
                "code = 'web'" to false,
            )
        for ((definition, temporal) in cases) {
            "lint and materialize agree that `$definition` is ${if (temporal) "" else "not "}temporal" {
                val text = "model cn\ndef subkind Probe { of: Ticket, definition: \"\"\"$definition\"\"\" }\n"
                val src = GoldenWorlds.root.resolve("time")
                val dir = Files.createTempDirectory("cn-temporal")
                Files
                    .walk(src)
                    .use { s ->
                        s.filter { it.isRegularFile() && it.extension == "ttrm" }.toList()
                    }.forEach {
                        Files.copy(it, dir.resolve(src.relativize(it).toString()))
                    }
                Files.writeString(dir.resolve("probe.ttrm"), text)
                val snapshot = FileBasedSource("cn", 100, LocalFsStorage(id = "cn", rootPath = dir)).load()
                val lint = snapshot.errors.any { it.message.startsWith("cn/subkind-temporal-definition:") }
                val model = ModelReconciler(ModelDescriptor(id = "d", name = "d")).reconcile(listOf(snapshot)).model
                val probe = ConceptRef.newBuilder().setQname("cn.Probe").build()
                val refused =
                    CnConceptService(ResolvedCnModel.of(model)).materialize(probe, "postgresql").diagnostics.map {
                        it.slug
                    }
                lint shouldBe temporal
                ("cn/subkind-temporal-definition" in refused) shouldBe temporal
            }
        }
    })
