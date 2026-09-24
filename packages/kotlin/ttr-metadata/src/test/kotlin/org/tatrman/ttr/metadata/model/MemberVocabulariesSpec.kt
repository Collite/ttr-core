// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

/** MV (contracts §1, §5.1) — the one definition of "this carrier has a member vocabulary". */
class MemberVocabulariesSpec :
    StringSpec({

        fun load(root: String): Model =
            MetadataLoader(
                FileBasedSource(
                    sourceId = "t",
                    priority = 100,
                    storage = LocalFsStorage(id = "t", rootPath = Path.of(root)),
                ),
            ).load().model ?: error("model failed to load from $root")

        "indexed attributes, plus indexed columns that do not back an indexed attribute — sorted" {
            val carriers = load("src/test/resources/fixture-member-vocab").memberVocabularyCarriers()
            carriers.map { it.qname.dotted() } shouldBe
                listOf(
                    "db.dbo.T.b_col", // its attribute E.b is not indexed: the column's own vocabulary stands
                    "db.dbo.T.own", // no attribute backs it
                    "er.entity.E.a", // `a_col` is its storage, not a second vocabulary
                )
        }

        "a bare `searchable` is not a carrier; EXACT is" {
            val carriers = load("src/test/resources/fixture-indexed").memberVocabularyCarriers()
            carriers.map { it.qname.name.substringAfterLast('.') } shouldBe
                listOf("exact_code", "legacy_fuzzy", "method_wins", "token_title", "typo_name", "unknown_method")
        }
    })
