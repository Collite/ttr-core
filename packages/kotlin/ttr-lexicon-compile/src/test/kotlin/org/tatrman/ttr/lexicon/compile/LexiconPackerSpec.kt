// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.CompiledLexiconHeader
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.OperatorLibrary
import org.tatrman.ttr.lexicon.SkillDef
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.semantics.semanticsblock.MentionKinds
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader

/**
 * RV-P1.2 T4/T5 — the archive half of the compile, under the (a3) ruling.
 *
 * The load-bearing claim tested here is the one the ruling rests on: a SERVING consumer reads
 * this archive with `ttr-snapshot` + `ttr-lexicon` and never touches the compiler.
 */
class LexiconPackerSpec :
    FunSpec({

        val snapshotHash = "sha256:" + "cd".repeat(32)

        fun compiled(): CompileResult {
            val yaml =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: cs }
                entries:
                  - terms: [ { text: "středisko" }, { text: "nákladové středisko", method: TOKENS } ]
                    target: er.entity.cost_center
                """.trimIndent()
            val skill =
                """
                ---
                schema: ttr-skill/v1
                op: op:trend
                triggers:
                  - { text: "vývoj", lang: cs }
                version: 1
                ---
                Retrieval: group by the finest requested time grain.
                """.trimIndent()

            return LexiconCompiler.compile(
                LexiconSources(
                    area =
                        LexiconArea(
                            listOf(
                                LexiconValidator
                                    .loadDataFile(yaml, "aliases/er.lex.yaml")
                                    .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                                    .value,
                            ),
                            listOf(
                                LexiconValidator
                                    .loadSkillFile(skill, "skills/trend.md")
                                    .shouldBeInstanceOf<LexiconLoad.Ok<SkillDef>>()
                                    .value,
                            ),
                        ),
                ),
                ModelRefIndex { if (it == "er.entity.cost_center") TargetClass.MODEL_OBJECT else null },
                snapshotHash,
                "2026-08-02T00:00:00Z",
            )
        }

        test("packing the same compile twice produces byte-identical archives") {
            val a = LexiconPacker.pack(compiled(), snapshotHash, "ttr-lexicon-compile/test")
            val b = LexiconPacker.pack(compiled(), snapshotHash, "ttr-lexicon-compile/test")

            a.bytes.contentEquals(b.bytes) shouldBe true
            a.id shouldBe b.id
        }

        test("a changed term changes the archive id") {
            val base = LexiconPacker.pack(compiled(), snapshotHash, "ttr-lexicon-compile/test")
            val changed =
                LexiconPacker.pack(
                    compiled().let { it.copy(lexicon = it.lexicon.copy(entries = it.lexicon.entries.drop(1))) },
                    snapshotHash,
                    "ttr-lexicon-compile/test",
                )

            changed.id shouldNotBe base.id
        }

        test("a serving consumer reads it with ttr-snapshot + ttr-lexicon only") {
            // No compiler type appears below this line — that is the (a3) ruling's whole point,
            // and the reason the artifact model lives in ttr-lexicon rather than here.
            val packed = LexiconPacker.pack(compiled(), snapshotHash, "veles 0.11.2")

            val contents = SnapshotReader.read(packed.bytes).shouldBeInstanceOf<SnapshotReadResult.Ok>().contents

            contents.manifest.kind shouldBe LexiconArchive.KIND
            contents.manifest.resolvedFrom[LexiconArchive.RESOLVED_FROM_MODEL] shouldBe snapshotHash

            val lexicon = CompiledLexicon.fromJson(contents.docs.getValue(LexiconArchive.LEXICON))
            lexicon.header.modelSnapshotHash shouldBe snapshotHash
            lexicon.entries.map { it.termNormalized } shouldBe
                listOf("nákladové středisko", "středisko", "vývoj")

            val operators = OperatorLibrary.fromJson(contents.docs.getValue(LexiconArchive.OPERATORS))
            operators.operators.keys.toList() shouldBe listOf("op:trend")
            // RV-35 again, this time across the container: the body is in the other document.
            contents.docs.getValue(LexiconArchive.LEXICON).contains("time grain") shouldBe false
        }

        // ---- MS (contracts §6) — the per-ref `targets` map -------------------------------------

        test("targets survive the pack → read round-trip, and the schema version says so") {
            val facts =
                mapOf(
                    "er.entity.sales" to TargetFacts(MentionKinds.ENTITY_WITH_MEASURES),
                    "er.entity.sales.amount_czk" to
                        TargetFacts(MentionKinds.MEASURE, ownerRef = "er.entity.sales"),
                )
            val withTargets =
                compiled().let { it.copy(lexicon = it.lexicon.copy(targets = facts)) }

            val packed = LexiconPacker.pack(withTargets, snapshotHash, "ttr-lexicon-compile/test")
            val contents = SnapshotReader.read(packed.bytes).shouldBeInstanceOf<SnapshotReadResult.Ok>().contents
            val lexicon = CompiledLexicon.fromJson(contents.docs.getValue(LexiconArchive.LEXICON))

            lexicon.targets shouldBe facts
            lexicon.header.schemaVersion shouldBe CompiledLexiconHeader.SCHEMA_VERSION
            lexicon.header.schemaVersion shouldBe "ttr-lexicon-compiled/v4"
        }

        test("targets are serialized in key order, so two builds are byte-identical") {
            // kotlinx.serialization writes a Map in ITERATION order, and the compiler collects
            // targets by walking entries — so the map is sorted at construction. Handing the
            // packer a deliberately unsorted map must still produce the sorted bytes, or the
            // determinism rule in the `builtAt` KDoc holds only by luck of the call order.
            fun packWith(pairs: List<Pair<String, TargetFacts>>): String {
                val r = compiled().let { it.copy(lexicon = it.lexicon.copy(targets = pairs.toMap())) }
                val packed = LexiconPacker.pack(r, snapshotHash, "ttr-lexicon-compile/test")
                val contents =
                    SnapshotReader.read(packed.bytes).shouldBeInstanceOf<SnapshotReadResult.Ok>().contents
                return contents.docs.getValue(LexiconArchive.LEXICON)
            }
            val a = "er.entity.a" to TargetFacts(MentionKinds.ENTITY)
            val b = "er.entity.b" to TargetFacts(MentionKinds.ENTITY)
            packWith(listOf(a, b)) shouldBe packWith(listOf(b, a))
        }

        test("an archive written before targets existed reads back with an empty map") {
            // The v1 shape, verbatim: no `targets` key at all. It must decode — a defaulted field,
            // not a schema break — because a v1 archive on disk outlives the compiler that wrote it.
            val v1 =
                """
                {
                  "header": {
                    "schemaVersion": "ttr-lexicon-compiled/v1",
                    "modelSnapshotHash": "$snapshotHash",
                    "sourceHashes": { "declared": "sha256:x", "metadata": "sha256:y" },
                    "builtAt": "2026-08-02T00:00:00Z"
                  },
                  "entries": []
                }
                """.trimIndent()
            val lexicon = CompiledLexicon.fromJson(v1)
            lexicon.targets shouldBe emptyMap()
            lexicon.header.schemaVersion shouldBe "ttr-lexicon-compiled/v1"
        }

        test("an archive carrying a field this version has never heard of still decodes") {
            // ⛑ review-082 F1 — the direction the case above does NOT cover, and the one that
            // breaks. A defaulted field buys old-json→new-reader; it says nothing about
            // new-json→old-reader, and `encodeDefaults = true` means every added field is written
            // into EVERY archive. Under strict decoding the first reader one version behind throws
            // `Encountered an unknown key`, and both serving readers turn that into an EMPTY
            // vocabulary on a WARN rather than a failure. So the decoder tolerates the unknown.
            //
            // Written as a FUTURE field rather than as `targets`, deliberately: this pins the
            // property (a v(n+1) archive decodes in a v(n) reader) rather than one instance of it,
            // so v3 cannot reintroduce the defect and pass.
            val future =
                """
                {
                  "header": {
                    "schemaVersion": "ttr-lexicon-compiled/v3",
                    "modelSnapshotHash": "$snapshotHash",
                    "sourceHashes": { "declared": "sha256:x", "metadata": "sha256:y" },
                    "builtAt": "2026-08-02T00:00:00Z",
                    "someFutureHeaderField": "whatever"
                  },
                  "entries": [],
                  "targets": {},
                  "someFutureTopLevelField": { "a": 1 }
                }
                """.trimIndent()
            val lexicon = CompiledLexicon.fromJson(future)
            lexicon.entries shouldBe emptyList()
            lexicon.targets shouldBe emptyMap()
            lexicon.header.schemaVersion shouldBe "ttr-lexicon-compiled/v3"
        }
    })
