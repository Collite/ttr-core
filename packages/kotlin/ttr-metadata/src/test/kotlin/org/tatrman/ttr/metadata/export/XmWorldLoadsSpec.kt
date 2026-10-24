// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

internal fun loadXmWorld() =
    MetadataLoader(
        listOf(
            BuiltinStockSource(),
            FileBasedSource(
                "xm",
                100,
                LocalFsStorage("xm", Path.of("src/testFixtures/resources/fixtures/xm-world/model")),
            ),
        ),
    ).load()

class XmWorldLoadsSpec :
    StringSpec({
        "the complete invented retail world loads without errors" {
            loadXmWorld().errors.map { it.toString() } shouldBe emptyList()
        }
    })
