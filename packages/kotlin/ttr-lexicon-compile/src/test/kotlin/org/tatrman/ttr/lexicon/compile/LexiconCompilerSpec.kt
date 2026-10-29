// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.SourceTag
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.Reach
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Cardinality
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.semanticsblock.MeasureRef
import org.tatrman.ttr.semantics.semanticsblock.MentionKinds
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics
import org.tatrman.ttr.semantics.semanticsblock.SymbolRef
import java.time.Instant

/**
 * RV-P1.2 T2 — the compiler's contract, written before the compiler.
 *
 * The five cases are the ones the task list names: convergence of the two declared surfaces,
 * RV-20 dangling-ref handling, RV-38 kind derivation, the METADATA layer's tag, and
 * byte-determinism. Nothing here reaches a file system or a clock.
 */
class LexiconCompilerSpec :
    FunSpec({

        val snapshotHash = "sha256:" + "ab".repeat(32)
        val builtAt = "2026-08-02T00:00:00Z"

        fun dataFile(
            name: String,
            yaml: String,
        ): LexiconDataFile =
            LexiconValidator
                .loadDataFile(yaml, name)
                .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                .value

        fun ttrm(
            file: String,
            src: String,
        ): TtrmLexiconUnit {
            val parsed = TtrLoader.parseString(src, file)
            parsed.errors shouldBe emptyList()
            return TtrmLexiconUnit(file, parsed)
        }

        /** Everything resolves as a plain model object unless the ref is listed as a member. */
        fun index(
            objects: Set<String>,
            members: Set<String> = emptySet(),
        ): ModelRefIndex =
            ModelRefIndex { ref ->
                when (ref) {
                    in members -> TargetClass.MEMBER
                    in objects -> TargetClass.MODEL_OBJECT
                    else -> null
                }
            }

        // ---- (a) the two declared surfaces converge on ONE row --------------------------------

        test("the same logical entry, authored twice, compiles to one row") {
            // Same term, same target, same lang, same (defaulted) method — one authored in the
            // lexicon area, one as TTR-M sugar. A repo mid-migration has both; the artifact
            // must not carry the term twice, or every score is counted twice downstream.
            val yaml =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: cs }
                entries:
                  - terms: [ { text: "tržba" } ]
                    target: md.measure.revenue
                """.trimIndent()
            val sugar =
                """
                model lexicon locale cs
                def term revenue_cs { for: md.measure.revenue, forms: ["tržba"] }
                """.trimIndent()

            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area = LexiconArea(listOf(dataFile("aliases/measures.lex.yaml", yaml)), emptyList()),
                        ttrm = listOf(ttrm("model/lexicon/cs/measures.ttrm", sugar)),
                    ),
                    index(objects = setOf("md.measure.revenue")),
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries shouldHaveSize 1
            result.lexicon.entries
                .single()
                .termNormalized shouldBe "tržba"
            result.lexicon.entries
                .single()
                .sourceTag shouldBe SourceTag.DECLARED
            result.warnings shouldBe emptyList()
        }

        test("the same term under two different targets is two rows, not a conflict") {
            // Dedup is by term+lang+target. Homonyms are legitimate — two bindings on one mention
            // is what the lattice is for (RV-2); collapsing them here would destroy the ambiguity
            // the resolver is supposed to see.
            //
            // They must come from two FILES: P1.1's RG-LEX-006 rejects a term declared twice
            // within one file ("no defined winner"), which is a rule about one author's typo, not
            // about the vocabulary as a whole. Cross-file homonyms are legal and reach here.
            fun file(target: String) =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: cs }
                entries:
                  - terms: [ { text: "obrat" } ]
                    target: $target
                """.trimIndent()

            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area =
                            LexiconArea(
                                listOf(
                                    dataFile("revenue.lex.yaml", file("md.measure.revenue")),
                                    dataFile("turnover.lex.yaml", file("md.measure.turnover")),
                                ),
                                emptyList(),
                            ),
                    ),
                    index(objects = setOf("md.measure.revenue", "md.measure.turnover")),
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries shouldHaveSize 2
            // No METHOD_CONFLICT: two targets is two entries, which is what the lattice is for.
            result.warnings.map { it.code }.toSet() shouldNotContain CompileWarning.METHOD_CONFLICT
            // MH T1 changed what is SAID about it, not what is compiled: both rows still ship,
            // and each author is now told the other ref claims the same word (RG-LEXC-004).
            result.warnings.map { it.code }.toSet() shouldBe setOf(CompileWarning.FORM_COLLISION)
        }

        // ---- (b) RV-20: dangling ref → dropped + warning ---------------------------------------

        test("a target that is not in the model snapshot is dropped with an attributable warning") {
            val yaml =
                """
                schema: ttr-lexicon/v1
                entries:
                  - terms: [ { text: "středisko" } ]
                    target: er.entity.cost_center
                  - terms: [ { text: "duch" } ]
                    target: er.entity.ghost
                """.trimIndent()

            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(listOf(dataFile("aliases/er.lex.yaml", yaml)), emptyList())),
                    index(objects = setOf("er.entity.cost_center")),
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries.map { it.termNormalized } shouldContainExactly listOf("středisko")

            val warning = result.warnings.single()
            warning.code shouldBe CompileWarning.DANGLING_REF
            warning.provenance.file shouldBe "aliases/er.lex.yaml"
            // The line the dropped TERM was written on (5), not the `target:` line below it — a
            // build warning has to point at the word the author will search for.
            warning.provenance.line shouldBe 5
        }

        test("a dangling ref is a warning, never a failed build") {
            val yaml =
                """
                schema: ttr-lexicon/v1
                entries:
                  - terms: [ { text: "duch" } ]
                    target: er.entity.ghost
                """.trimIndent()

            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(listOf(dataFile("a.lex.yaml", yaml)), emptyList())),
                    ModelRefIndex.EMPTY,
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries shouldBe emptyList()
            result.warnings shouldHaveSize 1
            result.lexicon.header.modelSnapshotHash shouldBe snapshotHash
        }

        test("a package-qualified target compiles to the package-less row (ttr-core#325)") {
            // What `@tatrman/edit` writes, beside the spelling hartland authors by hand: one row, under the ref the
            // archive spells. md and attribute depth take the same path. (Member depth has no TTR-M spelling: the
            // grammar takes no numeric segment in `for:`.)
            val qualified =
                """
                package hartland
                model lexicon locale en
                def term returns_review_en { for: hartland.er.entity.returns_review, forms: ["returns review"] }
                def term revenue_en { for: hartland.md.measure.revenue, forms: ["revenue"] }
                def term status_en { for: hartland.er.entity.customer.status, forms: ["status"] }
                """.trimIndent()
            val bare =
                """
                package hartland
                model lexicon locale en
                def term returns_review { for: er.entity.returns_review, forms: ["returns review"] }
                """.trimIndent()

            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        ttrm =
                            listOf(
                                ttrm("model/lexicon/en/returns.ttrm", qualified),
                                ttrm("model/lexicon/en/reviews.ttrm", bare),
                            ),
                    ),
                    index(
                        objects = setOf("er.entity.returns_review", "md.measure.revenue", "er.entity.customer.status"),
                    ),
                    snapshotHash,
                    builtAt,
                )

            result.warnings shouldBe emptyList()
            result.lexicon.entries.map { it.termNormalized to it.targetRef } shouldContainExactlyInAnyOrder
                listOf(
                    "returns review" to "er.entity.returns_review",
                    "revenue" to "md.measure.revenue",
                    "status" to "er.entity.customer.status",
                )
        }

        test("a ref qualified with a package nothing declares still dangles") {
            val src =
                """
                package hartland
                model lexicon locale en
                def term typo_en { for: hartlnd.er.entity.returns_review, forms: ["returns review"] }
                """.trimIndent()

            val result =
                LexiconCompiler.compile(
                    LexiconSources(ttrm = listOf(ttrm("model/lexicon/en/returns.ttrm", src))),
                    index(objects = setOf("er.entity.returns_review")),
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries shouldBe emptyList()
            result.warnings.single().code shouldBe CompileWarning.DANGLING_REF
        }

        // ---- (c) RV-38/RV-42: target_class derivation ------------------------------------------

        test("target_class comes from the model graph for model refs, and from the prefix otherwise") {
            val yaml =
                """
                schema: ttr-lexicon/v1
                entries:
                  - terms: [ { text: "zákazník" } ]
                    target: er.entity.customer
                  - terms: [ { text: "aktivní" } ]
                    target: er.entity.customer.status.active
                  - terms: [ { text: "rok" } ]
                    target: ground:chrono
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

            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area =
                            LexiconArea(
                                listOf(dataFile("a.lex.yaml", yaml)),
                                listOf(
                                    LexiconValidator
                                        .loadSkillFile(skill, "skills/trend.md")
                                        .shouldBeInstanceOf<LexiconLoad.Ok<org.tatrman.ttr.lexicon.SkillDef>>()
                                        .value,
                                ),
                            ),
                    ),
                    index(
                        objects = setOf("er.entity.customer"),
                        members = setOf("er.entity.customer.status.active"),
                    ),
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries.associate { it.termNormalized to it.targetClass } shouldBe
                mapOf(
                    "zákazník" to TargetClass.MODEL_OBJECT,
                    "aktivní" to TargetClass.MEMBER,
                    "rok" to TargetClass.GROUNDING_TRIGGER,
                    "vývoj" to TargetClass.OPERATOR,
                )
            // No `kind` column exists to disagree with the graph (RV-38).
            result.warnings shouldBe emptyList()
        }

        test("op: and ground: refs never consult the model index") {
            // They are not model objects. Classifying them through the index would make every
            // operator dangle against a snapshot that will never contain it.
            val yaml =
                """
                schema: ttr-lexicon/v1
                entries:
                  - terms: [ { text: "rok" } ]
                    target: ground:chrono
                """.trimIndent()

            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(listOf(dataFile("g.lex.yaml", yaml)), emptyList())),
                    ModelRefIndex.EMPTY,
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries
                .single()
                .targetClass shouldBe TargetClass.GROUNDING_TRIGGER
            result.warnings shouldBe emptyList()
        }

        test("skill bodies go to the operator library and never into the entry table") {
            val skill =
                """
                ---
                schema: ttr-skill/v1
                op: op:trend
                triggers:
                  - { text: "vývoj", lang: cs }
                version: 3
                ---
                Formatting: line chart default; period column first.
                """.trimIndent()

            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area =
                            LexiconArea(
                                emptyList(),
                                listOf(
                                    LexiconValidator
                                        .loadSkillFile(skill, "skills/trend.md")
                                        .shouldBeInstanceOf<LexiconLoad.Ok<org.tatrman.ttr.lexicon.SkillDef>>()
                                        .value,
                                ),
                            ),
                    ),
                    ModelRefIndex.EMPTY,
                    snapshotHash,
                    builtAt,
                )

            val op = result.operators.operators.getValue("op:trend")
            op.version shouldBe 3
            op.body shouldBe "Formatting: line chart default; period column first."

            // RV-35: the matcher sees the trigger, never the body.
            result.lexicon.entries
                .single()
                .termNormalized shouldBe "vývoj"
            result.lexicon.toJson().contains("line chart") shouldBe false
        }

        // ---- (d) the METADATA layer -------------------------------------------------------------

        test("labels already in the model compile as METADATA rows") {
            val customer = QualifiedName(SchemaCode.ER, "entity", "customer")
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "t", name = "t"),
                    version = ModelVersion("v1", Instant.EPOCH),
                    schemas =
                        mapOf(
                            "er" to
                                ErSchema(
                                    entities =
                                        mapOf(
                                            customer to
                                                Entity(
                                                    internalId = "1",
                                                    qname = customer,
                                                    sourceFile = "model/er/customer.ttrm",
                                                    aliases = listOf("odběratel"),
                                                    displayLabel =
                                                        LocalizedText(mapOf("cs" to "Zákazník", "en" to "Customer")),
                                                ),
                                        ),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )

            val result =
                LexiconCompiler.compile(
                    LexiconSources(model = model),
                    index(objects = setOf("er.entity.customer")),
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries
                .map { it.sourceTag }
                .toSet() shouldBe setOf(SourceTag.METADATA)
            result.lexicon.entries
                .map { it.termNormalized }
                .toSet() shouldBe
                setOf("zákazník", "customer", "odběratel")
            result.lexicon.entries.forEach { it.targetRef shouldBe "er.entity.customer" }
        }

        test("a declared row wins over the identical metadata row") {
            // Both layers can name the same term for the same target. The author's file is the
            // one that carries an intentional method and a line to point at in a diagnostic.
            val customer = QualifiedName(SchemaCode.ER, "entity", "customer")
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "t", name = "t"),
                    version = ModelVersion("v1", Instant.EPOCH),
                    schemas =
                        mapOf(
                            "er" to
                                ErSchema(
                                    entities =
                                        mapOf(
                                            customer to
                                                Entity(
                                                    internalId = "1",
                                                    qname = customer,
                                                    sourceFile = "model/er/customer.ttrm",
                                                    displayLabel = LocalizedText(mapOf("cs" to "zákazník")),
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
                  - terms: [ { text: "zákazník" } ]
                    target: er.entity.customer
                """.trimIndent()

            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area = LexiconArea(listOf(dataFile("a.lex.yaml", yaml)), emptyList()),
                        model = model,
                    ),
                    index(objects = setOf("er.entity.customer")),
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries shouldHaveSize 1
            result.lexicon.entries
                .single()
                .sourceTag shouldBe SourceTag.DECLARED
            result.lexicon.entries
                .single()
                .provenance.file shouldBe "a.lex.yaml"
        }

        // ---- (e) determinism --------------------------------------------------------------------

        test("input order does not change the artifact bytes") {
            val one =
                """
                schema: ttr-lexicon/v1
                entries:
                  - terms: [ { text: "zákazník" }, { text: "odběratel" } ]
                    target: er.entity.customer
                """.trimIndent()
            val two =
                """
                schema: ttr-lexicon/v1
                entries:
                  - terms: [ { text: "faktura" } ]
                    target: er.entity.invoice
                """.trimIndent()
            val refs = index(objects = setOf("er.entity.customer", "er.entity.invoice"))

            fun run(files: List<LexiconDataFile>) =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(files, emptyList())),
                    refs,
                    snapshotHash,
                    builtAt,
                )

            val a = dataFile("a.lex.yaml", one)
            val b = dataFile("b.lex.yaml", two)

            run(listOf(a, b)).lexicon.toJson() shouldBe run(listOf(b, a)).lexicon.toJson()
        }

        test("the content hash covers the vocabulary, not the build clock") {
            // The RV-39 layer tuple asks exactly one question — "did the vocabulary change?" — so
            // a hash that moved because the clock moved would answer it wrongly every build.
            val yaml =
                """
                schema: ttr-lexicon/v1
                entries:
                  - terms: [ { text: "faktura" } ]
                    target: er.entity.invoice
                """.trimIndent()

            fun run(at: String) =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(listOf(dataFile("a.lex.yaml", yaml)), emptyList())),
                    index(objects = setOf("er.entity.invoice")),
                    snapshotHash,
                    at,
                )

            val early = run("2026-01-01T00:00:00Z")
            val late = run("2026-12-31T23:59:59Z")

            early.lexicon.contentHash shouldBe late.lexicon.contentHash
            early.lexicon.toJson() shouldNotBe late.lexicon.toJson()
        }

        // ---- MS (contracts §5/§6) — the per-ref `targets` map ----------------------------------

        // One model, reused: `sales` declares a measure, `region_dim` declares none. Between them
        // the four MentionKinds values are all reachable from a real compile, not just from the
        // table's own unit spec.
        fun mentionModel(): Model {
            val sales = QualifiedName(SchemaCode.ER, "entity", "sales")
            val regionDim = QualifiedName(SchemaCode.ER, "entity", "region_dim")

            fun attr(
                owner: QualifiedName,
                local: String,
                type: String,
            ) = Attribute(
                internalId = "a.$local",
                qname = QualifiedName(SchemaCode.ER, "entity", "${owner.name}.$local"),
                entity = owner,
                type = type,
            )
            return Model(
                descriptor = ModelDescriptor(id = "t", name = "t"),
                version = ModelVersion("v1", Instant.EPOCH),
                schemas =
                    mapOf(
                        "er" to
                            ErSchema(
                                entities =
                                    mapOf(
                                        sales to
                                            Entity(
                                                internalId = "1",
                                                qname = sales,
                                                attributes =
                                                    listOf(
                                                        attr(sales, "amount_czk", "decimal"),
                                                        attr(sales, "region", "text"),
                                                    ),
                                                mentionSemantics =
                                                    ResolvedEntitySemantics(
                                                        measures =
                                                            listOf(
                                                                MeasureRef(SymbolRef("amount_czk"), "sum"),
                                                            ),
                                                    ),
                                            ),
                                        regionDim to
                                            Entity(
                                                internalId = "2",
                                                qname = regionDim,
                                                attributes = listOf(attr(regionDim, "name", "text")),
                                            ),
                                    ),
                            ),
                    ),
                mappings = emptyList(),
                queries = emptyMap(),
            )
        }

        val mentionYaml =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: cs }
            entries:
              - terms: [ { text: "prodej" } ]
                target: er.entity.sales
              - terms: [ { text: "obrat" } ]
                target: er.entity.sales.amount_czk
              - terms: [ { text: "region" } ]
                target: er.entity.sales.region
              - terms: [ { text: "regiony" } ]
                target: er.entity.region_dim
              - terms: [ { text: "název" } ]
                target: er.entity.region_dim.name
              - terms: [ { text: "vývoj" } ]
                target: op:trend
            """.trimIndent()

        fun mentionCompile(model: Model? = mentionModel()): CompileResult =
            LexiconCompiler.compile(
                LexiconSources(
                    area = LexiconArea(listOf(dataFile("m.lex.yaml", mentionYaml)), emptyList()),
                    model = model,
                ),
                index(
                    objects =
                        setOf(
                            "er.entity.sales",
                            "er.entity.sales.amount_czk",
                            "er.entity.sales.region",
                            "er.entity.region_dim",
                            "er.entity.region_dim.name",
                        ),
                ),
                snapshotHash,
                builtAt,
            )

        test("targets carry the derived kind for every MODEL_OBJECT ref") {
            val targets = mentionCompile().lexicon.targets

            targets.getValue("er.entity.sales") shouldBe
                TargetFacts(MentionKinds.ENTITY_WITH_MEASURES)
            targets.getValue("er.entity.sales.amount_czk") shouldBe
                TargetFacts(MentionKinds.MEASURE, ownerRef = "er.entity.sales")
            // MS-R4: an attribute that is not listed is an attribute, even on an entity that
            // does have measures.
            targets.getValue("er.entity.sales.region") shouldBe
                TargetFacts(MentionKinds.ATTRIBUTE, ownerRef = "er.entity.sales")
            targets.getValue("er.entity.region_dim") shouldBe TargetFacts(MentionKinds.ENTITY)
            targets.getValue("er.entity.region_dim.name") shouldBe
                TargetFacts(MentionKinds.ATTRIBUTE, ownerRef = "er.entity.region_dim")
        }

        test("an operator ref gets no targets entry") {
            // `op:`/`ground:` refs are not model objects; a facts entry for one would be a claim
            // about a node that does not exist.
            val result = mentionCompile()
            result.lexicon.entries.map { it.targetRef } shouldContain "op:trend"
            result.lexicon.targets.keys shouldNotContain "op:trend"
        }

        test("targets keys are the entry targetRefs, byte for byte") {
            // The map is only useful if a consumer can look up a row's ref in it directly. Both
            // sides render refs the same way because the key IS the row's ref — not a qname
            // re-rendered a second time, which is where the two could drift.
            val result = mentionCompile()
            val modelObjectRefs =
                result.lexicon.entries
                    .filter { it.targetClass == TargetClass.MODEL_OBJECT }
                    .map { it.targetRef }
                    .toSet()
            result.lexicon.targets.keys shouldBe modelObjectRefs
            // …and every ownerRef is itself a key-shaped ref, resolvable in the same model.
            result.lexicon.targets.values
                .mapNotNull { it.ownerRef }
                .toSet() shouldBe setOf("er.entity.sales", "er.entity.region_dim")
        }

        test("targets are sorted by key") {
            mentionCompile()
                .lexicon.targets.keys
                .toList() shouldBe
                mentionCompile()
                    .lexicon.targets.keys
                    .sorted()
        }

        test("an estate with no model compiles to empty targets, not to guesses") {
            val result = mentionCompile(model = null)
            // The rows still compile — the refs are classified by the index, which is what
            // RV-38 says classification is for. There is simply nothing to say about them.
            result.lexicon.entries.shouldNotBeEmpty()
            result.lexicon.targets shouldBe emptyMap()
        }

        // ---- MH T1 (contracts §3) — RG-LEXC-004, the collision warning ------------------------

        // The hartland shape: `er.entity.store` owns the word through its LABEL, and the
        // Stores-channel term pins the same word to `er.entity.store_sales`. Two refs, one
        // anchor — the resolver asks instead of binding, and the build should have said so.
        fun collisionModel(): Model {
            val store = QualifiedName(SchemaCode.ER, "entity", "store")
            val storeSales = QualifiedName(SchemaCode.ER, "entity", "store_sales")
            return Model(
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
                                                sourceFile = "model/er/parties.ttrm",
                                                labelPlural = "Stores",
                                                displayLabel = LocalizedText(mapOf("cs" to "Prodejna")),
                                            ),
                                        storeSales to
                                            Entity(
                                                internalId = "2",
                                                qname = storeSales,
                                                sourceFile = "model/er/sales.ttrm",
                                            ),
                                    ),
                            ),
                    ),
                mappings = emptyList(),
                queries = emptyMap(),
            )
        }

        fun collisions(result: CompileResult): List<CompileWarning> =
            result.warnings.filter { it.code == CompileWarning.FORM_COLLISION }

        test("a declared form that folds onto another ref's METADATA anchor warns RG-LEXC-004") {
            val yaml =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: cs }
                entries:
                  - terms: [ { text: "prodejna" } ]
                    target: er.entity.store_sales
                """.trimIndent()
            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area = LexiconArea(listOf(dataFile("lexicon/aliases/channels.lex.yaml", yaml)), emptyList()),
                        model = collisionModel(),
                    ),
                    index(objects = setOf("er.entity.store", "er.entity.store_sales")),
                    snapshotHash,
                    builtAt,
                )

            val warnings = collisions(result)
            warnings shouldHaveSize 1
            warnings.single().message shouldBe
                "term \"prodejna\" (for: er.entity.store_sales) collides with the METADATA anchor " +
                "\"prodejna\" of er.entity.store — both refs claim this word at runtime (fold: \"prodejna\")"
            // Provenance is the DECLARED row's: the author who can act on it.
            warnings.single().provenance.file shouldBe "lexicon/aliases/channels.lex.yaml"
            // Never fatal, and never archive content — both rows still compile.
            result.lexicon.entries
                .filter { it.termNormalized == "prodejna" }
                .map { it.targetRef }
                .toSet() shouldBe setOf("er.entity.store", "er.entity.store_sales")
        }

        test("two DECLARED rows of different targets each name the other") {
            // Two FILES: RG-LEX-006 rejects one term declared twice inside one file, so a
            // cross-file homonym is the only shape that reaches the compiler (see case (a)).
            fun file(target: String) =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: cs }
                entries:
                  - terms: [ { text: "web" } ]
                    target: $target
                """.trimIndent()
            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area =
                            LexiconArea(
                                listOf(
                                    dataFile("dim.lex.yaml", file("er.entity.store")),
                                    dataFile("channel.lex.yaml", file("er.entity.store_sales")),
                                ),
                                emptyList(),
                            ),
                        model = collisionModel(),
                    ),
                    index(objects = setOf("er.entity.store", "er.entity.store_sales")),
                    snapshotHash,
                    builtAt,
                )

            val warnings = collisions(result)
            warnings shouldHaveSize 2
            warnings.map { it.message }.toSet() shouldBe
                setOf(
                    "term \"web\" (for: er.entity.store) collides with the DECLARED anchor \"web\" of " +
                        "er.entity.store_sales — both refs claim this word at runtime (fold: \"web\")",
                    "term \"web\" (for: er.entity.store_sales) collides with the DECLARED anchor \"web\" of " +
                        "er.entity.store — both refs claim this word at runtime (fold: \"web\")",
                )
        }

        test("the key is the FOLD, so a diacritic difference is still a collision") {
            val vyroba = QualifiedName(SchemaCode.ER, "entity", "vyrobni_linka")
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "t", name = "t"),
                    version = ModelVersion("v1", Instant.EPOCH),
                    schemas =
                        mapOf(
                            "er" to
                                ErSchema(
                                    entities =
                                        mapOf(
                                            vyroba to
                                                Entity(
                                                    internalId = "1",
                                                    qname = vyroba,
                                                    displayLabel = LocalizedText(mapOf("cs" to "Výroba")),
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
                  - terms: [ { text: "vyroba" } ]
                    target: er.entity.store_sales
                """.trimIndent()
            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area = LexiconArea(listOf(dataFile("a.lex.yaml", yaml)), emptyList()),
                        model = model,
                    ),
                    index(objects = setOf("er.entity.vyrobni_linka", "er.entity.store_sales")),
                    snapshotHash,
                    builtAt,
                )

            collisions(result) shouldHaveSize 1
            // The two rows stay distinct in the archive — `normalize` keeps diacritics; only the
            // COLLISION key folds them together.
            collisions(result).single().message.contains("(fold: \"vyroba\")") shouldBe true
            result.lexicon.entries
                .map { it.termNormalized }
                .toSet() shouldBe setOf("výroba", "vyroba")
        }

        test("a term repeating its OWN target's label is redundant, not a collision") {
            val yaml =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: cs }
                entries:
                  - terms: [ { text: "Prodejna" } ]
                    target: er.entity.store
                """.trimIndent()
            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area = LexiconArea(listOf(dataFile("a.lex.yaml", yaml)), emptyList()),
                        model = collisionModel(),
                    ),
                    index(objects = setOf("er.entity.store", "er.entity.store_sales")),
                    snapshotHash,
                    builtAt,
                )

            collisions(result) shouldBe emptyList()
            // merge() already collapsed the two into one DECLARED row.
            result.lexicon.entries
                .filter { it.termNormalized == "prodejna" }
                .shouldHaveSize(1)
        }

        test("MEMBER rows are outside the boundary — a value label never collides with a term") {
            val store = QualifiedName(SchemaCode.ER, "entity", "store")
            val kind = QualifiedName(SchemaCode.ER, "entity", "store.kind")
            val model =
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
                                                            Attribute(
                                                                internalId = "a1",
                                                                qname = kind,
                                                                entity = store,
                                                                type = "text",
                                                                valueLabels =
                                                                    mapOf(
                                                                        "1" to LocalizedText(mapOf("cs" to "Prodejny")),
                                                                    ),
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
                  - terms: [ { text: "prodejny" } ]
                    target: er.entity.store_sales
                """.trimIndent()
            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area = LexiconArea(listOf(dataFile("a.lex.yaml", yaml)), emptyList()),
                        model = model,
                    ),
                    index(
                        objects = setOf("er.entity.store", "er.entity.store_sales"),
                        members = setOf("er.entity.store.kind.1"),
                    ),
                    snapshotHash,
                    builtAt,
                )

            // The member row IS in the archive; it is simply a different species (`M:` at
            // runtime), which is T4 territory, not T1's.
            result.lexicon.entries
                .map { it.targetClass }
                .toSet()
                .contains(TargetClass.MEMBER) shouldBe true
            collisions(result) shouldBe emptyList()
        }

        test("two METADATA labels colliding are not this warning") {
            val a = QualifiedName(SchemaCode.ER, "entity", "region_geo")
            val b = QualifiedName(SchemaCode.ER, "entity", "region_org")
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "t", name = "t"),
                    version = ModelVersion("v1", Instant.EPOCH),
                    schemas =
                        mapOf(
                            "er" to
                                ErSchema(
                                    entities =
                                        mapOf(
                                            a to
                                                Entity(
                                                    internalId = "1",
                                                    qname = a,
                                                    displayLabel = LocalizedText(mapOf("cs" to "Region")),
                                                ),
                                            b to
                                                Entity(
                                                    internalId = "2",
                                                    qname = b,
                                                    displayLabel = LocalizedText(mapOf("cs" to "Region")),
                                                ),
                                        ),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val result =
                LexiconCompiler.compile(
                    LexiconSources(model = model),
                    index(objects = setOf("er.entity.region_geo", "er.entity.region_org")),
                    snapshotHash,
                    builtAt,
                )

            // A model-level duplicate label is a real problem, but it is not a DECLARED row's
            // fault and no author of a term can act on it — out of scope (contracts §2.3).
            collisions(result) shouldBe emptyList()
        }

        test("collision warnings sort into the one warning stream by file, line, code") {
            val yaml =
                """
                schema: ttr-lexicon/v1
                defaults: { lang: cs }
                entries:
                  - terms: [ { text: "prodejna" } ]
                    target: er.entity.store_sales
                  - terms: [ { text: "duch" } ]
                    target: er.entity.ghost
                """.trimIndent()
            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area = LexiconArea(listOf(dataFile("a.lex.yaml", yaml)), emptyList()),
                        model = collisionModel(),
                    ),
                    index(objects = setOf("er.entity.store", "er.entity.store_sales")),
                    snapshotHash,
                    builtAt,
                )

            result.warnings.map { it.code } shouldBe
                result.warnings
                    .sortedWith(CompileWarning.ORDER)
                    .map { it.code }
            result.warnings.map { it.code }.toSet() shouldBe
                setOf(CompileWarning.DANGLING_REF, CompileWarning.FORM_COLLISION)
        }

        // ---- MH T3-data (contracts §4) — `targets[ref].reachedFrom` -----------------------------

        // `store` is reached from three facts with two different cardinalities, which is exactly
        // the shape the resolver's T3 rule decides on: a mandatory reach makes the dimension
        // reading and the channel reading the same rows; a nullable one makes them differ.
        fun reachModel(): Model {
            val store = QualifiedName(SchemaCode.ER, "entity", "store")
            val storeSales = QualifiedName(SchemaCode.ER, "entity", "store_sales")
            val storeReturns = QualifiedName(SchemaCode.ER, "entity", "store_returns")
            val webSales = QualifiedName(SchemaCode.ER, "entity", "web_sales")
            val ghost = QualifiedName(SchemaCode.ER, "entity", "ghost")

            fun entity(
                id: String,
                q: QualifiedName,
            ) = Entity(
                internalId = id,
                qname = q,
                attributes =
                    listOf(
                        Attribute(
                            internalId = "$id.name",
                            qname = QualifiedName(SchemaCode.ER, "entity", "${q.name}.name"),
                            entity = q,
                            type = "text",
                        ),
                    ),
            )

            fun relation(
                name: String,
                from: QualifiedName,
                to: QualifiedName,
                toMin: Int,
            ) = QualifiedName(SchemaCode.ER, "relation", name) to
                Relation(
                    internalId = name,
                    qname = QualifiedName(SchemaCode.ER, "relation", name),
                    fromEntity = from,
                    toEntity = to,
                    cardinality = Cardinality(fromMin = 0, fromMax = -1, toMin = toMin, toMax = 1),
                )

            return Model(
                descriptor = ModelDescriptor(id = "t", name = "t"),
                version = ModelVersion("v1", Instant.EPOCH),
                schemas =
                    mapOf(
                        "er" to
                            ErSchema(
                                entities =
                                    mapOf(
                                        store to entity("1", store),
                                        storeSales to entity("2", storeSales),
                                        storeReturns to entity("3", storeReturns),
                                        webSales to entity("4", webSales),
                                    ),
                                relations =
                                    mapOf(
                                        relation("rel_ss_store", storeSales, store, toMin = 1),
                                        relation("rel_sr_store", storeReturns, store, toMin = 1),
                                        // BOPIS-shaped: a web row MAY carry a store. The readings
                                        // differ here, which is what rule 4 refuses on.
                                        relation("rel_ws_store", webSales, store, toMin = 0),
                                        // A relation whose `to` is not in the model: the model
                                        // validator owns dangling relations, so this is skipped
                                        // silently rather than warned about twice.
                                        relation("rel_ghost", storeSales, ghost, toMin = 1),
                                    ),
                            ),
                    ),
                mappings = emptyList(),
                queries = emptyMap(),
            )
        }

        val reachYaml =
            """
            schema: ttr-lexicon/v1
            defaults: { lang: cs }
            entries:
              - terms: [ { text: "prodejna" } ]
                target: er.entity.store
              - terms: [ { text: "tržby z prodejen" } ]
                target: er.entity.store_sales
              - terms: [ { text: "vratky" } ]
                target: er.entity.store_returns
              - terms: [ { text: "web" } ]
                target: er.entity.web_sales
              - terms: [ { text: "název prodejny" } ]
                target: er.entity.store.name
            """.trimIndent()

        fun reachCompile(model: Model? = reachModel()): CompileResult =
            LexiconCompiler.compile(
                LexiconSources(
                    area = LexiconArea(listOf(dataFile("reach.lex.yaml", reachYaml)), emptyList()),
                    model = model,
                ),
                index(
                    objects =
                        setOf(
                            "er.entity.store",
                            "er.entity.store_sales",
                            "er.entity.store_returns",
                            "er.entity.web_sales",
                            "er.entity.store.name",
                        ),
                ),
                snapshotHash,
                builtAt,
            )

        test("a dimension carries every fact that relates to it, sorted, with its cardinality") {
            reachCompile()
                .lexicon.targets
                .getValue("er.entity.store")
                .reachedFrom shouldBe
                listOf(
                    Reach("er.entity.store_returns", mandatory = true),
                    Reach("er.entity.store_sales", mandatory = true),
                    Reach("er.entity.web_sales", mandatory = false),
                )
        }

        test("a fact nothing points at has no reach — the direction is `to`, not `from`") {
            reachCompile()
                .lexicon.targets
                .getValue("er.entity.store_sales")
                .reachedFrom shouldBe emptyList()
        }

        test("an attribute carries no reach — reachability is a fact about whole objects") {
            reachCompile()
                .lexicon.targets
                .getValue("er.entity.store.name")
                .reachedFrom shouldBe emptyList()
        }

        test("a relation to a ref the model does not describe is skipped, silently") {
            val result = reachCompile()
            result.lexicon.targets.keys shouldNotContain "er.entity.ghost"
            // Silently: the model validator owns dangling relations. Warning here would make one
            // authoring mistake produce two diagnostics from two tools.
            result.warnings
                .map { it.code }
                .toSet() shouldNotContain CompileWarning.DANGLING_REF
        }

        test("an estate with no model has no reach and no guesses") {
            reachCompile(model = null).lexicon.targets shouldBe emptyMap()
        }

        test("reach does not disturb the entry table's content id") {
            // `contentHash` answers "did the VOCABULARY change?" — a relation is not vocabulary.
            reachCompile().lexicon.contentHash shouldBe
                reachCompile()
                    .lexicon
                    .copy(
                        targets =
                            reachCompile().lexicon.targets.mapValues {
                                it.value.copy(reachedFrom = emptyList())
                            },
                    ).contentHash
        }
    })
