// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * MH T3-data (contracts §4) — the archive's `targets[ref].reachedFrom`, and the v2→v3 seam.
 *
 * `reachedFrom` is the E-R relation graph, projected once at compile time so the resolver never
 * derives structure from names (MS's "no client-side derivation" rule). The seam is the point of
 * most of these cases: the field is DEFAULTED, so a v2 archive decodes here, and `contentHash`
 * covers the entry table only, so adding it cannot move an id that answers "did the vocabulary
 * change?".
 */
class ArtifactSpec :
    StringSpec({

        fun lexicon(targets: Map<String, TargetFacts>) =
            CompiledLexicon(
                header =
                    CompiledLexiconHeader(
                        modelSnapshotHash = "sha256:" + "ab".repeat(32),
                        sourceHashes = SourceHashes(declared = "d", metadata = "m"),
                        builtAt = "2026-09-02T00:00:00Z",
                    ),
                entries =
                    listOf(
                        CompiledEntry(
                            termNormalized = "prodejna",
                            lang = "cs",
                            targetRef = "er.entity.store",
                            targetClass = TargetClass.MODEL_OBJECT,
                            method = "EXACT",
                            sourceTag = SourceTag.METADATA,
                            provenance = EntryProvenance("model/er/parties.ttrm", 0),
                        ),
                    ),
                targets = targets,
            )

        val reach =
            listOf(
                Reach("er.entity.store_returns", mandatory = true),
                Reach("er.entity.store_sales", mandatory = true),
            )

        "reachedFrom round-trips through toJson/fromJson unchanged" {
            val before = lexicon(mapOf("er.entity.store" to TargetFacts("entity", null, reach)))
            val after = CompiledLexicon.fromJson(before.toJson())

            after.targets.getValue("er.entity.store").reachedFrom shouldBe reach
            after shouldBe before
        }

        "a v2-shaped targets object decodes with an empty reachedFrom" {
            // Verbatim v2 bytes: `TargetFacts` had exactly these two keys.
            val v2 =
                """
                {
                  "header": {
                    "schemaVersion": "ttr-lexicon-compiled/v2",
                    "modelSnapshotHash": "sha256:${"ab".repeat(32)}",
                    "sourceHashes": { "declared": "d", "metadata": "m" },
                    "builtAt": "2026-09-02T00:00:00Z"
                  },
                  "entries": [],
                  "targets": {
                    "er.entity.store": { "objectKind": "entity", "ownerRef": null }
                  }
                }
                """.trimIndent()

            val decoded = CompiledLexicon.fromJson(v2)

            decoded.targets.getValue("er.entity.store").objectKind shouldBe "entity"
            decoded.targets.getValue("er.entity.store").reachedFrom shouldBe emptyList()
            // The header keeps what it said — the reader does not rewrite the producer's claim.
            decoded.header.schemaVersion shouldBe "ttr-lexicon-compiled/v2"
        }

        "the schema label moved to v5" {
            CompiledLexiconHeader.SCHEMA_VERSION shouldBe "ttr-lexicon-compiled/v5"
        }

        "contentHash covers the entry table only, so reach does not move the id" {
            lexicon(mapOf("er.entity.store" to TargetFacts("entity", null, reach))).contentHash shouldBe
                lexicon(mapOf("er.entity.store" to TargetFacts("entity", null, emptyList()))).contentHash
        }

        "an unknown key in targets is ignored, not fatal — a v4 archive still reads" {
            val forward =
                """
                {
                  "header": {
                    "schemaVersion": "ttr-lexicon-compiled/v4",
                    "modelSnapshotHash": "sha256:${"ab".repeat(32)}",
                    "sourceHashes": { "declared": "d", "metadata": "m" },
                    "builtAt": "2026-09-02T00:00:00Z"
                  },
                  "entries": [],
                  "targets": {
                    "er.entity.store": {
                      "objectKind": "entity",
                      "reachedFrom": [ { "factRef": "er.entity.store_sales", "mandatory": true } ],
                      "somethingNobodyHasWrittenYet": 7
                    }
                  }
                }
                """.trimIndent()

            CompiledLexicon
                .fromJson(forward)
                .targets
                .getValue("er.entity.store")
                .reachedFrom shouldBe listOf(Reach("er.entity.store_sales", mandatory = true))
        }

        // ---- LP (P2a T9/T4): the v3 → v4 seam ------------------------------------------------

        "a v3-shaped targets object decodes with no mention facet" {
            // Verbatim v3 bytes: `TargetFacts` had exactly these three keys. The v2 case above is
            // left untouched — a compatibility pin is evidence about a version that has shipped,
            // so the way to cover a new one is to ADD a case, never to edit an old one.
            val v3 =
                """
                {
                  "header": {
                    "schemaVersion": "ttr-lexicon-compiled/v3",
                    "modelSnapshotHash": "sha256:${"ab".repeat(32)}",
                    "sourceHashes": { "declared": "d", "metadata": "m" },
                    "builtAt": "2026-09-02T00:00:00Z"
                  },
                  "entries": [],
                  "targets": {
                    "er.entity.store": {
                      "objectKind": "entity",
                      "ownerRef": null,
                      "reachedFrom": [ { "factRef": "er.entity.store_sales", "mandatory": true } ]
                    }
                  }
                }
                """.trimIndent()

            val facts = CompiledLexicon.fromJson(v3).targets.getValue("er.entity.store")

            facts.reachedFrom shouldBe listOf(Reach("er.entity.store_sales", mandatory = true))
            // Null, not "" — the model said nothing, which is what leaves a literal headless (G3)
            // rather than attributed to a column nobody declared.
            facts.nameRef shouldBe null
            facts.codeRef shouldBe null
            facts.codeFormat shouldBe null
        }

        "the mention facet round-trips, and does not move contentHash" {
            val facet =
                TargetFacts(
                    objectKind = "entity",
                    nameRef = "er.entity.store.name",
                    codeRef = "er.entity.store.code",
                    codeFormat = "^S[0-9]{4}$",
                )
            val before = lexicon(mapOf("er.entity.store" to facet))

            CompiledLexicon.fromJson(before.toJson()) shouldBe before
            // Same rule as `reachedFrom`: the id answers "did the VOCABULARY change?", and three
            // header-level facts are not vocabulary.
            before.contentHash shouldBe lexicon(mapOf("er.entity.store" to TargetFacts("entity"))).contentHash
        }

        "⚠ the v4 BREAK is the enum member, not the three fields" {
            // The finding P2a T4 was asked to record, pinned as a test rather than a sentence.
            //
            // `targetClass` has no default and kotlinx refuses an enum value it does not know, so
            // a reader built before `STRING_PREDICATE` existed fails on the WHOLE archive — not on
            // the row — and both serving readers degrade an undecodable archive to an EMPTY
            // vocabulary. Since the stdlib slice ships `pred:` rows, every archive built by this
            // compiler carries them. Hence contracts §8's ordering rule for the v3→v4 release:
            // READERS BEFORE PRODUCERS. Nothing on this side can soften it.
            fun archive(targetClass: String) =
                """
                {
                  "header": {
                    "schemaVersion": "ttr-lexicon-compiled/v4",
                    "modelSnapshotHash": "sha256:${"ab".repeat(32)}",
                    "sourceHashes": { "declared": "d", "metadata": "m" },
                    "builtAt": "2026-09-02T00:00:00Z"
                  },
                  "entries": [
                    {
                      "termNormalized": "obsahující",
                      "lang": "cs",
                      "targetRef": "pred:contains",
                      "targetClass": "$targetClass",
                      "method": "EXACT",
                      "sourceTag": "DECLARED",
                      "provenance": { "file": "stdlib/predicates/string.lex.yaml", "line": 1 }
                    }
                  ],
                  "targets": {}
                }
                """.trimIndent()

            // This reader knows the member, so it reads.
            CompiledLexicon
                .fromJson(archive("STRING_PREDICATE"))
                .entries
                .single()
                .targetClass shouldBe TargetClass.STRING_PREDICATE

            // A member it does NOT know behaves exactly as STRING_PREDICATE does to a v3 reader:
            // the whole document is refused, which is the shape of the break.
            shouldThrow<SerializationException> { CompiledLexicon.fromJson(archive("SOMETHING_LATER")) }
        }

        // ---- MV (T0 T4): the v4 → v5 seam --------------------------------------------------

        "a v4-shaped targets object decodes with no member facet" {
            // Verbatim v4 bytes (the six keys `TargetFacts` had at v4) — an added case, per the rule
            // above: the older pins stay as they shipped.
            val v4 =
                """
                {
                  "header": {
                    "schemaVersion": "ttr-lexicon-compiled/v4",
                    "modelSnapshotHash": "sha256:${"ab".repeat(32)}",
                    "sourceHashes": { "declared": "d", "metadata": "m" },
                    "builtAt": "2026-09-24T00:00:00Z"
                  },
                  "entries": [],
                  "targets": {
                    "er.entity.store": {
                      "objectKind": "entity",
                      "ownerRef": null,
                      "reachedFrom": [],
                      "nameRef": "er.entity.store.store_name",
                      "codeRef": null,
                      "codeFormat": null
                    },
                    "er.entity.store.state": {
                      "objectKind": "attribute",
                      "ownerRef": "er.entity.store",
                      "reachedFrom": [],
                      "nameRef": null,
                      "codeRef": null,
                      "codeFormat": null
                    }
                  }
                }
                """.trimIndent()

            val targets = CompiledLexicon.fromJson(v4).targets
            targets.values.map { it.memberVocabulary } shouldBe listOf(false, false)
            targets.getValue("er.entity.store").nameRef shouldBe "er.entity.store.store_name"
        }

        "the member facet round-trips, is written on every target, and does not move contentHash" {
            val member = TargetFacts(objectKind = "attribute", ownerRef = "er.entity.store", memberVocabulary = true)
            val before = lexicon(mapOf("er.entity.store.state" to member))

            CompiledLexicon.fromJson(before.toJson()) shouldBe before
            before.toJson() shouldContain "\"memberVocabulary\": true"
            // encodeDefaults: the `false` is written too, so a reader can tell "not indexed" from "v4".
            lexicon(mapOf("er.entity.store" to TargetFacts("entity"))).toJson() shouldContain
                "\"memberVocabulary\": false"
            // The id answers "did the VOCABULARY change?" — a header-level fact is not vocabulary.
            before.contentHash shouldBe
                lexicon(mapOf("er.entity.store.state" to member.copy(memberVocabulary = false))).contentHash
        }

        "Reach is a plain serializable pair — factRef and the to-side lower bound" {
            Json.encodeToString(Reach("er.entity.store_sales", mandatory = false)) shouldNotBe ""
            Json.decodeFromString<Reach>("""{"factRef":"a","mandatory":true}""") shouldBe Reach("a", true)
        }
    })
