// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.Reach
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Cardinality
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.SearchHints
import java.time.Instant

/**
 * MV-T0 T5 (member-vocabulary contracts §5.1, archive v5) — `targets` carries every carrier with a
 * member vocabulary, terms or not.
 *
 * The gap it closes (GI-4/GI-5): the map was built from the refs lexicon ROWS point at, so an
 * indexed attribute nobody had written a term for — `store.state`, the typical one — never reached
 * the resolver's registry, and a governed value under `store` had no way to find its vocabulary.
 */
class MemberVocabularyFacetSpec :
    FunSpec({

        val snapshotHash = "sha256:" + "d5".repeat(32)
        val builtAt = "2026-09-24T00:00:00Z"

        val store = QualifiedName(SchemaCode.ER, "entity", "store")
        val sale = QualifiedName(SchemaCode.ER, "entity", "sale")
        val ledger = QualifiedName(SchemaCode.DB, "dbo", "ledger")

        fun attr(
            owner: QualifiedName,
            local: String,
            search: SearchHints = SearchHints.EMPTY,
        ) = Attribute(
            internalId = "a.${owner.name}.$local",
            qname = QualifiedName(SchemaCode.ER, "entity", "${owner.name}.$local"),
            entity = owner,
            type = "text",
            search = search,
        )

        fun indexed(method: String) = SearchHints(searchable = true, indexed = true, matchMethod = method)

        /**
         * `store` carries the four attribute shapes that matter: indexed with no terms
         * (`store_name`, `state` — EXACT), indexed WITH a term (`nazev`), a term but not indexed
         * (`note`), and a bare `searchable` hint with neither (`label`). `sale → store` gives the
         * owner a reach, so the spec can see that the member does not borrow it. `db.dbo.ledger`
         * is the db-only estate: an indexed column no attribute backs.
         */
        fun model(): Model =
            Model(
                descriptor = ModelDescriptor(id = "t", name = "t"),
                version = ModelVersion("v1", Instant.EPOCH),
                schemas =
                    mapOf(
                        "er" to
                            ErSchema(
                                entities =
                                    mapOf(
                                        store to
                                            Entity(
                                                internalId = "1",
                                                qname = store,
                                                attributes =
                                                    listOf(
                                                        attr(store, "store_name", indexed("TYPOS(1)")),
                                                        attr(store, "state", indexed("EXACT")),
                                                        attr(store, "nazev", indexed("TOKENS")),
                                                        attr(store, "note"),
                                                        attr(store, "label", SearchHints(searchable = true)),
                                                    ),
                                            ),
                                        sale to Entity(internalId = "2", qname = sale),
                                    ),
                                relations =
                                    mapOf(
                                        QualifiedName(SchemaCode.ER, "relation", "rel_sale_store") to
                                            Relation(
                                                internalId = "r1",
                                                qname = QualifiedName(SchemaCode.ER, "relation", "rel_sale_store"),
                                                fromEntity = sale,
                                                toEntity = store,
                                                cardinality =
                                                    Cardinality(
                                                        fromMin = 0,
                                                        fromMax = -1,
                                                        toMin = 1,
                                                        toMax = 1,
                                                    ),
                                            ),
                                    ),
                            ),
                        "db" to
                            DbSchema(
                                tables =
                                    mapOf(
                                        ledger to
                                            DbTable(
                                                internalId = "3",
                                                qname = ledger,
                                                columns =
                                                    listOf(
                                                        DbColumn(
                                                            internalId = "c1",
                                                            qname =
                                                                QualifiedName(
                                                                    SchemaCode.DB,
                                                                    "dbo",
                                                                    "ledger.account",
                                                                ),
                                                            table = ledger,
                                                            dataType = "varchar",
                                                            search = indexed("TYPOS(2)"),
                                                        ),
                                                    ),
                                            ),
                                    ),
                            ),
                    ),
                mappings = emptyList(),
                queries = emptyMap(),
            )

        val yaml =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: cs }
            entries:
              - terms: [ { text: "prodejna" } ]
                target: er.entity.store
              - terms: [ { text: "název prodejny" } ]
                target: er.entity.store.nazev
              - terms: [ { text: "poznámka" } ]
                target: er.entity.store.note
            """.trimIndent()

        fun dataFile(body: String): LexiconDataFile =
            LexiconValidator
                .loadDataFile(body, "m.lex.yaml")
                .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                .value

        fun compile(model: Model? = model()): CompiledLexicon =
            LexiconCompiler
                .compile(
                    LexiconSources(area = LexiconArea(listOf(dataFile(yaml)), emptyList()), model = model),
                    ModelRefIndex {
                        if (it.startsWith("er.") ||
                            it.startsWith("db.")
                        ) {
                            TargetClass.MODEL_OBJECT
                        } else {
                            null
                        }
                    },
                    snapshotHash,
                    builtAt,
                ).lexicon

        test("(a) an indexed attribute with NO terms is in targets — owned, flagged, reached through its owner") {
            val targets = compile().targets

            for (local in listOf("store_name", "state")) {
                val facts = targets.getValue("er.entity.store.$local")
                facts.objectKind shouldBe "attribute"
                facts.ownerRef shouldBe "er.entity.store"
                facts.memberVocabulary shouldBe true
                // A member carries no reach of its own; its owner's is one lookup away. Copying the
                // owner's here would let the resolver's T3 rule join to a column.
                facts.reachedFrom shouldBe emptyList()
                facts.nameRef shouldBe null
            }
            targets.getValue("er.entity.store").reachedFrom shouldBe listOf(Reach("er.entity.sale", mandatory = true))
        }

        test("(a′) EXACT counts — `state` was not indexed before MV") {
            compile().targets.getValue("er.entity.store.state").memberVocabulary shouldBe true
        }

        test("(b) an indexed attribute WITH terms is one entry, flagged") {
            compile().targets.getValue("er.entity.store.nazev").memberVocabulary shouldBe true
        }

        test("(c) a term on a non-indexed attribute keeps its entry, unflagged; the owner is never flagged") {
            val targets = compile().targets
            targets.getValue("er.entity.store.note").memberVocabulary shouldBe false
            targets.getValue("er.entity.store").memberVocabulary shouldBe false
        }

        test("a bare `searchable` with no terms is not a vocabulary and not a target") {
            compile().targets shouldNotContainKey "er.entity.store.label"
        }

        test("the db-only estate: an indexed column no attribute backs is a member of its TABLE") {
            val facts = compile().targets.getValue("db.dbo.ledger.account")
            facts.objectKind shouldBe "attribute"
            facts.ownerRef shouldBe "db.dbo.ledger"
            facts.memberVocabulary shouldBe true
        }

        test("(d) sorted and byte-stable across two builds") {
            val first = compile()
            first.targets.keys.toList() shouldContainExactly first.targets.keys.sorted()
            first.toJson() shouldBe compile().toJson()
        }

        test("no model ⇒ no targets, as before") {
            compile(model = null).targets shouldBe emptyMap()
        }
    })
