// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.snapshot.SnapshotCache
import org.tatrman.ttr.snapshot.SnapshotManifest
import org.tatrman.ttr.snapshot.SnapshotWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

/**
 * CN-P4 S4.2 T1 (cn contracts §5) — the snapshot channel carries the cn tier: the Hartland cn world (ttr-concept's
 * golden) packed into a snapshot archive, served from the cache by the connected source, reconciled, swapped into the
 * registry — and the registry holds the cn tier a direct load of the same files builds. Before S4.2 the connected
 * source's merge dropped `cn` (and `automation`): the archive carried the documents, the load served no concept.
 */
class ConnectedCnChannelTest :
    FunSpec({
        val world = Path.of(System.getProperty("cnHartland") ?: error("cnHartland not set"))

        fun pack(root: Path): ByteArray {
            val docs =
                Files
                    .walk(root)
                    .use { s -> s.filter { Files.isRegularFile(it) && it.toString().endsWith(".ttrm") }.toList() }
                    .associate { it.relativeTo(root).toString() to Files.readString(it) }
            return SnapshotWriter.write(SnapshotManifest(kind = "models", producedBy = "cn-p4-test"), docs)
        }

        fun load(source: ModelSource): Model =
            MetadataLoader(source).load().model ?: error("the Hartland world did not load")

        fun connected(): Model {
            val cache = SnapshotCache(Files.createTempDirectory("cn-cache"))
            val id = cache.put(pack(world))
            val source = MetadataServerSource(TtrLock(world = LockWorld("hartland.worlds.golden", id)), cache)
            source.loadResult().missing shouldBe emptyList()
            return load(source)
        }

        val direct = load(FileBasedSource("fs", 0, LocalFsStorage("fs", world)))

        /** What a consumer reads off a concept — never where its file sat (an archive's paths are synthetic). */
        fun facts(cn: CnSchema) =
            cn.concepts.values.associate {
                it.ref to listOf(it.stereotype, it.erTarget, it.via, it.grade, it.of?.name, it.lexiconLinks.size)
            }

        test("the cn tier crosses the channel whole: archive → connected source → reconcile → registry") {
            val registry = MetadataRegistry()
            val model = connected()
            registry.swap(model, ModelGraph.build(model))

            val snapshot = registry.readOrError()
            val served = snapshot.model.schemas.getValue("cn") as CnSchema
            val built = direct.schemas.getValue("cn") as CnSchema

            served.concepts.shouldNotBeEmpty()
            facts(served) shouldBe facts(built)
            served.bindings.keys shouldBe built.bindings.keys
            served.reverseIndex.shouldNotBeEmpty()
            served.reverseIndex shouldBe built.reverseIndex
            served.elementTerms.keys shouldBe built.elementTerms.keys
        }

        test("the automation tier crosses it too (the parameters the Hartland definitions read)") {
            val served = connected().schemas.getValue("automation") as AutomationSchema
            val built = direct.schemas.getValue("automation") as AutomationSchema

            served.objects.keys.shouldNotBeEmpty()
            served.objects.keys shouldBe built.objects.keys
        }
    })
