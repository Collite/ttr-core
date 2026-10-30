// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.StorageFile
import org.tatrman.ttrp.resolve.MdRepo
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * CN-P3 S3.8 T1 (architecture §7) — `ttr-concept` is a pure function of resolved, cached, snapshot-pinned inputs. For
 * every golden case, the call's whole result — not only what the golden compares — is byte-identical:
 * - twice over one [ResolvedCnModel], through one service (a lazy cache must not change an answer);
 * - over a second, independently loaded copy of the world — its files listed in a shuffled order, and then every map
 *   of the reconciled model (each tier's elements, the tiers themselves) re-inserted in a shuffled order: no answer may
 *   depend on iteration order. The file shuffle alone reaches little here — `CnBuilder` sorts its documents and each
 *   golden world keeps a tier in one file — so the maps are shuffled too;
 *
 * and a frame that fixes its day (`as_of`, `RenderOptions.asOf`) — or needs none (validation, assertions,
 * `materialize`) — never reads the clock. With no `as_of`, the day comes from the injected `Clock`; that no other
 * clock is read is `PuritySpec`'s rule.
 *
 * Bytes: a plan as its proto wire bytes (map entries in insertion order — stricter than the text format, which sorts
 * them), every other result as its data-class text, which prints each field and each map in its own order.
 */
class DeterminismSpec :
    FunSpec({
        val reloaded = ConcurrentHashMap<String, Reload>()

        test("CN-P8 temporal renderings are identical twice and over a shuffled reload") {
            val w = GoldenWorlds["time"]
            val shuffled = reload(w)
            val counting = CountingClock(FIXED)
            val original = CnConceptService(w.cn, counting)
            val again = CnConceptService(shuffled.model, counting)
            val options = RenderOptions(asOf = Instant.parse("2026-10-12T00:00:00Z"))
            for (name in listOf("WebTicket", "OutOfService", "OpenTicket", "quoted_price")) {
                val ref =
                    org.tatrman.concept.v1.ConceptRef
                        .newBuilder()
                        .setQname("cn.$name")
                        .build()
                val first = original.definitionRender(ref, Layer.ER, "postgresql", options)
                original.definitionRender(ref, Layer.ER, "postgresql", options) shouldBe first
                again.definitionRender(ref, Layer.ER, "postgresql", options) shouldBe first
            }
            counting.reads.get() shouldBe 0
        }

        for (c in GoldenCases.cases) {
            test("${c.id} [${c.call}] — byte-identical twice and over a shuffled reload") {
                val world = GoldenWorlds[c.world]
                val counting = CountingClock(FIXED)
                val svc = CnConceptService(world.cn, counting)
                val first = fingerprint(c, svc)
                if (fixesTheDay(c)) {
                    withClue("a frame that fixes its day read the clock") { counting.reads.get() shouldBe 0 }
                }
                withClue("the second call over one model") { fingerprint(c, svc) shouldBe first }

                val reload = reloaded.computeIfAbsent(c.world) { reload(world) }
                withClue("the shuffled load listed the files in their sorted order") { reload.permuted shouldBe true }
                withClue("an independently loaded, shuffled copy of `${c.world}`") {
                    fingerprint(c, CnConceptService(reload.model, FIXED)) shouldBe first
                }
            }
        }

        test("with no as_of, the evaluation day is the injected clock's") {
            // ActiveCustomer's window reads the evaluation day (`:as_of`); the golden names it, this frame does not
            val c = GoldenCases.cases.single { it.id == "activecustomer/revenue-active" }
            val sales = GoldenWorlds[c.world].cn
            val noDay =
                c
                    .statement()
                    .toBuilder()
                    .clearAsOf()
                    .build()
            val counting = CountingClock(FIXED)
            CnConceptService(sales, counting).statementLower(noDay).provenance.asOf shouldBe "2026-03-15"
            counting.reads.get() shouldBeGreaterThan 0
            val later = Clock.fixed(Instant.parse("2027-01-02T00:00:00Z"), ZoneOffset.UTC)
            CnConceptService(sales, later).statementLower(noDay).provenance.asOf shouldBe "2027-01-02"
        }
    }) {
    /** A world loaded again, its files listed in a shuffled order; [permuted] — the order did change. */
    class Reload(
        val model: ResolvedCnModel,
        val permuted: Boolean,
    )

    /** Lists [inner]'s files in a seeded shuffled order. */
    private class Shuffled(
        private val inner: ModelStorage,
        private val seed: Long,
    ) : ModelStorage by inner {
        var permuted = false

        override fun listFiles(
            extensions: List<String>,
            prefixes: List<String>,
        ): List<StorageFile> {
            val sorted = inner.listFiles(extensions, prefixes)
            val shuffled = sorted.shuffled(Random(seed)).let { if (it == sorted) sorted.reversed() else it }
            permuted = permuted || (sorted.size > 1 && shuffled != sorted)
            return shuffled
        }
    }

    /** Counts the reads of [inner]. */
    private class CountingClock(
        private val inner: Clock,
    ) : Clock() {
        val reads = AtomicInteger()

        override fun getZone(): ZoneId = inner.zone

        override fun withZone(zone: ZoneId): Clock = CountingClock(inner.withZone(zone))

        override fun instant(): Instant = inner.instant().also { reads.incrementAndGet() }
    }

    companion object {
        val FIXED: Clock = Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC)

        /** The world's model root, loaded the way [GoldenWorlds] loads it — but through a [Shuffled] storage. */
        private fun reload(w: GoldenWorlds.World): Reload {
            val storage = Shuffled(LocalFsStorage(id = "cn", rootPath = w.dir), seed = 42)
            val snapshot = FileBasedSource("cn", 100, storage).load()
            val model = ModelReconciler(ModelDescriptor(id = "d", name = "d")).reconcile(listOf(snapshot)).model
            val cn = ResolvedCnModel.of(scrambled(model, Random(7)), "golden:${w.name}", MdRepo.loadFrom(w.dir))
            return Reload(cn, storage.permuted)
        }

        /** [m] with every map's entries re-inserted in a shuffled order. */
        private fun scrambled(
            m: Model,
            r: Random,
        ): Model {
            fun <K, V> Map<K, V>.s(): Map<K, V> = entries.shuffled(r).associate { it.key to it.value }
            val schemas =
                m.schemas.mapValues { (_, t) ->
                    when (t) {
                        is ErSchema -> t.copy(entities = t.entities.s(), relations = t.relations.s())
                        is DbSchema ->
                            t.copy(
                                tables = t.tables.s(),
                                views = t.views.s(),
                                procedures = t.procedures.s(),
                                foreignKeys = t.foreignKeys.s(),
                            )
                        is CnSchema ->
                            t.copy(
                                concepts = t.concepts.s(),
                                bindings = t.bindings.s(),
                                relationTypes = t.relationTypes.s(),
                                elementTerms = t.elementTerms.s(),
                            )
                        is AutomationSchema -> t.copy(objects = t.objects.s())
                        else -> t
                    }
                }
            return m.copy(schemas = schemas.s(), queries = m.queries.s())
        }

        /** The frame names its day, or the call needs none. */
        private fun fixesTheDay(c: GoldenCases.Case): Boolean =
            when (c.call) {
                "statementLower", "statementExplain" -> c.statement().hasAsOf()
                "definitionRender" -> c.spec["options"]?.toString()?.contains("\"asOf\"") == true
                else -> true
            }

        private fun ref(qname: String): ConceptRef = ConceptRef.newBuilder().setQname(qname).build()

        /** The call's whole result as text: a plan as its wire bytes, everything else as its data-class text. */
        internal fun fingerprint(
            c: GoldenCases.Case,
            svc: ConceptService,
        ): String =
            when (c.call) {
                "statementValidate" -> svc.statementValidate(c.statement()).toString()
                "assertionValidate" -> svc.assertionValidate(c.assertion()).toString()
                "statementLower" -> {
                    val r = svc.statementLower(c.statement())
                    val bytes = r.plan?.let { Base64.getEncoder().encodeToString(it.toByteArray()) }
                    "plan=$bytes\n${r.copy(plan = null)}"
                }
                "statementExplain" -> svc.statementExplain(c.statement(), c.str("locale")!!).toString()
                "assertionExplain" -> svc.assertionExplain(c.assertion(), c.str("locale")!!).toString()
                "assertionLower" -> svc.assertionLower(c.assertion()).toString()
                "definitionRender" -> {
                    val problems = mutableListOf<String>()
                    val options =
                        ConceptGoldenSpec.options(
                            c.spec["options"] as? JsonObject ?: JsonObject(emptyMap()),
                            problems,
                        )
                    check(problems.isEmpty()) { problems.joinToString() }
                    svc
                        .definitionRender(
                            ref(c.str("target")!!),
                            Layer.valueOf(c.str("layer")!!),
                            c.str("dialect")!!,
                            options,
                        ).toString()
                }
                "materialize" -> svc.materialize(ref(c.str("target")!!), c.str("dialect")!!).toString()
                else -> error("unknown call `${c.call}`")
            }
    }
}
