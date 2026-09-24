// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.query

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * MD2 pull-down: the indexed-only filter + indexed-attribute→backing-column mapping,
 * memoised per snapshot (MetadataServiceImpl lines 171–209, filter 366–368).
 * Kantheon twins: `ListObjectsFuzzyOnlyFilterSpec`, `ListObjectsFuzzyOnlyFixtureSpec`,
 * `ListObjectsFuzzyAttributeMappingSpec`.
 *
 * MV (member-vocabulary contracts §2.2): the filter asks `SearchHints.indexed`; `fuzzyOnly` is its
 * deprecated alias, because the bit it used to read always meant "indexed".
 */
class MetadataQueryFuzzySpec :
    StringSpec({

        // Column qnames are parent-qualified (e.g. `name` of the fuzzy fixture table),
        // so assert on the simple leaf via substringAfterLast('.').
        fun MetadataQuery.leaves(filter: MetadataQuery.ObjectFilter) =
            listObjects(filter, MetadataQuery.PageRequest(pageSize = 1000))
                .items
                .map { it.qname.name.substringAfterLast('.') }
                .toSet()

        fun MetadataQuery.fuzzyLeaves() = leaves(MetadataQuery.ObjectFilter(fuzzyOnly = true))

        fun MetadataQuery.indexedLeaves() = leaves(MetadataQuery.ObjectFilter(indexedOnly = true))

        "fuzzyOnly=true keeps only fuzzy-flagged columns (fixture-fuzzy)" {
            val q = queryFor(Path.of("src/test/resources/fixture-fuzzy"))
            val leaves = q.fuzzyLeaves()
            leaves shouldContain "name" // has fuzzy: true
            leaves shouldNotContain "code" // plain column
        }

        "fuzzyOnly surfaces the column BACKING a fuzzy ER attribute (attribute-mapping twin)" {
            val q = queryFor(Path.of("src/test/resources/fuzzy-attr/shop"))
            val leaves = q.fuzzyLeaves()
            leaves shouldContain "direct_fuzzy" // own SearchHints.fuzzy
            leaves shouldContain "backing" // backs the fuzzy ER attribute E.attr via er2db
            leaves shouldNotContain "plain"
        }

        // RV-P1.5 (grammar 0.12, RV-32). `fuzzyOnly` is what veles' ListObjects(fuzzy_only=true)
        // serves and what lex-matcher's index loader asks for, so this filter IS the answer to
        // "does the documented fuzzy → method migration keep a column indexed?". It must, or the
        // migration silently un-indexes an estate's data values.
        "an authored non-EXACT method is fuzzy-indexed, exactly like the `fuzzy: true` it replaces" {
            val q = queryFor(Path.of("src/test/resources/fixture-fuzzy-0-12"))
            val leaves = q.fuzzyLeaves()
            leaves shouldContain "name" // searchable method: TYPOS(1)  ← was fuzzy: true
            leaves shouldContain "title" // searchable method: TOKENS
        }

        // MV reverses the EXACT half of the pre-MV rule: an exact-matched code is still a member
        // vocabulary (`stores in TN` needs `state` indexed), it is just matched without slack.
        "EXACT is indexed; the bare inclusion marker stays OUT" {
            val q = queryFor(Path.of("src/test/resources/fixture-fuzzy-0-12"))
            val leaves = q.indexedLeaves()
            leaves shouldContain "code" // searchable method: EXACT
            // The RV-32 default is NOT folded in: a bare `searchable` is a hint, not a vocabulary.
            leaves shouldNotContain "label"
            leaves shouldNotContain "sku"
        }

        "fuzzyOnly is an alias of indexedOnly" {
            for (fixture in listOf("fixture-fuzzy-0-12", "fixture-fuzzy", "fixture-indexed", "fuzzy-attr/shop")) {
                val q = queryFor(Path.of("src/test/resources/$fixture"))
                q.fuzzyLeaves() shouldBe q.indexedLeaves()
            }
        }
    })
