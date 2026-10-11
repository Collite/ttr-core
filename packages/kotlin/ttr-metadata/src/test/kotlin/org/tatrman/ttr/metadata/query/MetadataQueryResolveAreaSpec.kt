// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.query

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * MD2 pull-down: area resolution (MetadataServiceImpl lines 892–920). Kantheon
 * twin: `ResolveAreaSpec`. Found → packages/description/tags verbatim; unknown →
 * null (no message minting — MD5, the facade renders `area_not_found`).
 */
class MetadataQueryResolveAreaSpec :
    StringSpec({

        val q = queryFor(Path.of("src/test/resources/model-ttr-areas"))

        "resolveArea returns packages/description/tags verbatim for a known area" {
            val area = q.resolveArea("hartland")
            area.shouldNotBeNull()
            area.packages shouldContainAll listOf("sales", "returns")
            area.tags shouldContainAll listOf("reporting")
            (area.description.isNotEmpty()) shouldBe true
        }

        "resolveArea returns null for an unknown area (no minted message)" {
            q.resolveArea("nosuch") shouldBe null
        }
    })
