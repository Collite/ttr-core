// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.semantics.semanticsblock.ResolvedAttributeSemantics
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics
import org.tatrman.ttr.semantics.semanticsblock.SymbolRef
import java.time.Instant

/**
 * LP-P2a T9 (✅LP-10, contracts §2.1) — `targets[ref].nameRef/codeRef/codeFormat`.
 *
 * ⚑LPQ-5, the gap this closes: the resolver attributes a quoted literal to the head's declared
 * `semantics { name: · code: }`, and the compiled archive had no channel for it — `TargetFacts`
 * carried `objectKind`, `ownerRef` and `reachedFrom` and nothing else. LP-P1 shipped the facet on
 * the per-request `Registry` override only, so an archive-fed estate emitted every literal HEADLESS
 * (a G3). These three fields are that channel; LP-P2b projects them into `ResolverEntityType`.
 *
 * The rule they are written under is the file's oldest one: a fact is COPIED from the model or it
 * is absent. Never composed from a ref string, never inferred from a column called `name`.
 */
class MentionFacetSpec :
    FunSpec({

        val snapshotHash = "sha256:" + "c4".repeat(32)
        val builtAt = "2026-09-24T00:00:00Z"

        val store = QualifiedName(SchemaCode.ER, "entity", "store")
        val region = QualifiedName(SchemaCode.ER, "entity", "region")
        val invoice = QualifiedName(SchemaCode.DB, "dbo", "invoice")

        fun attr(
            owner: QualifiedName,
            local: String,
            semantics: ResolvedAttributeSemantics? = null,
        ) = Attribute(
            internalId = "a.${owner.name}.$local",
            qname = QualifiedName(SchemaCode.ER, "entity", "${owner.name}.$local"),
            entity = owner,
            type = "text",
            semantics = semantics,
        )

        fun column(
            owner: QualifiedName,
            local: String,
            semantics: ResolvedAttributeSemantics? = null,
        ) = DbColumn(
            internalId = "c.${owner.name}.$local",
            qname = QualifiedName(SchemaCode.DB, "dbo", "${owner.name}.$local"),
            table = owner,
            dataType = "varchar",
            semantics = semantics,
        )

        /**
         * One model carrying the three shapes that matter:
         *  - `store` declares BOTH `name:` and `code:`, and the code column declares a format;
         *  - `region` declares `name:` only, and names a member it does NOT have for `code:`;
         *  - `db.dbo.invoice` is the table twin of `store`, because a db estate reaches the same
         *    resolver through a different branch of the compiler's `when`.
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
                                                        attr(store, "nazev"),
                                                        attr(
                                                            store,
                                                            "kod",
                                                            ResolvedAttributeSemantics(
                                                                role = "code",
                                                                codeFormat = "^S[0-9]{4}$",
                                                            ),
                                                        ),
                                                    ),
                                                mentionSemantics =
                                                    ResolvedEntitySemantics(
                                                        name = SymbolRef("nazev"),
                                                        code = SymbolRef("kod"),
                                                    ),
                                            ),
                                        region to
                                            Entity(
                                                internalId = "2",
                                                qname = region,
                                                attributes = listOf(attr(region, "nazev")),
                                                mentionSemantics =
                                                    ResolvedEntitySemantics(
                                                        name = SymbolRef("nazev"),
                                                        // Declared, but there is no such member.
                                                        code = SymbolRef("kod_ktery_neexistuje"),
                                                    ),
                                            ),
                                    ),
                            ),
                        "db" to
                            DbSchema(
                                tables =
                                    mapOf(
                                        invoice to
                                            DbTable(
                                                internalId = "3",
                                                qname = invoice,
                                                columns =
                                                    listOf(
                                                        column(invoice, "nazev"),
                                                        column(
                                                            invoice,
                                                            "cislo",
                                                            ResolvedAttributeSemantics(role = "code"),
                                                        ),
                                                    ),
                                                mentionSemantics =
                                                    ResolvedEntitySemantics(
                                                        name = SymbolRef("nazev"),
                                                        code = SymbolRef("cislo"),
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
              - terms: [ { text: "oblast" } ]
                target: er.entity.region
              - terms: [ { text: "faktura" } ]
                target: db.dbo.invoice
            """.trimIndent()

        fun dataFile(
            name: String,
            body: String,
        ): LexiconDataFile =
            LexiconValidator
                .loadDataFile(body, name)
                .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                .value

        fun compile(model: Model? = model()) =
            LexiconCompiler.compile(
                LexiconSources(
                    area = LexiconArea(listOf(dataFile("m.lex.yaml", yaml)), emptyList()),
                    model = model,
                ),
                ModelRefIndex {
                    if (it in
                        setOf(
                            "er.entity.store",
                            "er.entity.store.nazev",
                            "er.entity.region",
                            "db.dbo.invoice",
                        )
                    ) {
                        TargetClass.MODEL_OBJECT
                    } else {
                        null
                    }
                },
                snapshotHash,
                builtAt,
            )

        test("an entity's declared name and code become FULL attribute refs, with the code's format") {
            val facts = compile().lexicon.targets.getValue("er.entity.store")

            // `er.entity.store.nazev`, not `nazev`: the resolver compares these to
            // `Attribution.attribute_ref`, and a local name would make every consumer re-join.
            facts.nameRef shouldBe "er.entity.store.nazev"
            facts.codeRef shouldBe "er.entity.store.kod"
            // Copied from the CODE attribute, so the resolver's code-shape test uses the model's
            // pattern rather than the fallback regex it would otherwise invent.
            facts.codeFormat shouldBe "^S[0-9]{4}$"
        }

        test("a db table reaches the same facet through the columns branch") {
            val facts = compile().lexicon.targets.getValue("db.dbo.invoice")

            facts.nameRef shouldBe "db.dbo.invoice.nazev"
            facts.codeRef shouldBe "db.dbo.invoice.cislo"
            // The column declares `role: code` but no format — absent, never a default.
            facts.codeFormat shouldBe null
        }

        test("a code: naming a member the entity does not have is dropped, not composed") {
            val facts = compile().lexicon.targets.getValue("er.entity.region")

            facts.nameRef shouldBe "er.entity.region.nazev"
            // The model names `kod_ktery_neexistuje` and the entity has no such attribute. String
            // concatenation would ship a ref pointing at nothing and the resolver would attribute
            // a literal to a column no plan can select; absence leaves it headless (G3) instead,
            // which is the one degradation that cannot produce a wrong answer.
            facts.codeRef shouldBe null
            facts.codeFormat shouldBe null
        }

        test("a MEMBER carries no facet — it has no name column, it IS one") {
            val facts = compile().lexicon.targets.getValue("er.entity.store.nazev")

            facts.ownerRef shouldBe "er.entity.store"
            facts.nameRef shouldBe null
            facts.codeRef shouldBe null
            facts.codeFormat shouldBe null
        }

        test("an object with no semantics block gets nulls, not guesses from column names") {
            // `region` above declares a block. This model declares NONE, and the entity still has
            // an attribute called `nazev` — the exact case a name-sniffing implementation passes
            // and a declaration-reading one must not.
            val silent =
                model().let { m ->
                    val er = m.schemas.getValue("er") as ErSchema
                    m.copy(
                        schemas =
                            m.schemas + (
                                "er" to
                                    er.copy(
                                        entities =
                                            er.entities.mapValues { (_, e) -> e.copy(mentionSemantics = null) },
                                    )
                            ),
                    )
                }

            val facts = compile(silent).lexicon.targets.getValue("er.entity.store")

            facts.nameRef shouldBe null
            facts.codeRef shouldBe null
        }

        test("the facet does not move contentHash — it is header facts, not vocabulary") {
            val silent =
                model().let { m ->
                    val er = m.schemas.getValue("er") as ErSchema
                    val db = m.schemas.getValue("db") as DbSchema
                    m.copy(
                        schemas =
                            mapOf(
                                "er" to
                                    er.copy(
                                        entities = er.entities.mapValues { (_, e) -> e.copy(mentionSemantics = null) },
                                    ),
                                "db" to
                                    db.copy(
                                        tables = db.tables.mapValues { (_, t) -> t.copy(mentionSemantics = null) },
                                    ),
                            ),
                    )
                }

            compile().lexicon.contentHash shouldBe compile(silent).lexicon.contentHash
        }
    })
