// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.loader.TtrLoader

/**
 * Grammar 0.17 (CN-P1 S1.3) — the Kotlin AST + walker for the conceptual model, the parity of the TS
 * `cn-walker.test.ts` (S1.2). The conformance dumps (`out-ts/75…79`, compared by `diff.ts`) are the
 * whole-fixture goldens; these specs pin the shapes, the expression-block source map, the lossless
 * read (every key, label, tag, participant label and span survives) and the error positions the three
 * targets share. Unlike TS, a Kotlin parse with any error keeps no definitions (`ParseResult` contract),
 * so the recovery cases assert diagnostics only.
 */
class CnWalkerSpec :
    StringSpec({

        // ---------------------------------------------------------------------------------------------
        // T1 — the shapes
        // ---------------------------------------------------------------------------------------------

        "T1: a body reads the first property of a key — a repeat stays in the list" {
            val src = SourceLocation("hand.ttrm", 1, 0, 1, 1, 0, 1)
            val body =
                ConceptBody(
                    properties =
                        listOf(
                            ConceptProperty.Entry(
                                "of",
                                PropertyValue.IdValue(Reference("Party"), listOf("Party"), src),
                                src,
                            ),
                            ConceptProperty.Ref("event", Reference("Order"), src),
                            ConceptProperty.Label("label", LocalizedStringValue(mapOf("en" to "Customer")), src),
                            ConceptProperty.Grounds("grounds", Reference("md.Customer"), src),
                            ConceptProperty.Grounds("grounds", Reference("er.entity.customer"), src),
                        ),
                )
            val role = ConceptDef("role", "Customer", src, body)
            role.body
                .property<ConceptProperty.Grounds>()
                ?.ref
                ?.path shouldBe "md.Customer"
            role.body.properties
                .filterIsInstance<ConceptProperty.Grounds>()
                .size shouldBe 2
            role.body.ref("event")?.path shouldBe "Order"
            role.body.ref("kind").shouldBeNull()
            (role.body.entry("of") as PropertyValue.IdValue).ref.path shouldBe "Party"
            role.body.entry("is_a").shouldBeNull()
            role.body.properties.map { it.key } shouldContainExactly
                listOf("of", "event", "label", "grounds", "grounds")
        }

        "T1: maps a position inside an expression block back to the file" {
            // `definition: """pl␊        a␊          b"""` — text "a\n  b", indent 8; line 0 at file line 2.
            val block =
                EmbeddedBlock(
                    form = "tagged",
                    text = "a\n  b",
                    textSource = SourceLocation("hand.ttrm", 2, 8, 3, 11, 40, 53),
                    lineOffsets = listOf(40, 50),
                    indentWidth = 8,
                    source = SourceLocation("hand.ttrm", 1, 16, 1, 17, 20, 21),
                )
            block.location(0, 0) shouldBe SourceLocation("hand.ttrm", 2, 8, 2, 9, 40, 41)
            block.location(1, 2, 1) shouldBe SourceLocation("hand.ttrm", 3, 10, 3, 11, 52, 53)
        }

        // ---------------------------------------------------------------------------------------------
        // T2 — concept defs and the `def role` dispatch (⚑CN-P6), fixture 75
        // ---------------------------------------------------------------------------------------------

        val concepts = parseFixture("75-cn-concepts.ttrm")
        val defs75 = concepts.definitions

        "T2: walks every def of a `model cn` file to a ConceptDef with its stereotype" {
            defs75.all { it is ConceptDef } shouldBe true
            val count = { s: String -> defs75.count { (it as ConceptDef).stereotype == s } }
            listOf("kind", "role", "event", "quality", "phase", "relation_type").map(count) shouldContainExactly
                listOf(7, 1, 5, 5, 6, 1)
        }

        "T2: keeps every property in authored order, typed where the grammar types it" {
            val item = concept(defs75, "Item")
            keys(item.body) shouldContainExactly
                listOf("description", "label", "sources", "grounds", "qualities", "lexicon")
            item.description shouldBe "A product the stores, the web shop and the marketplace sell."
            item.body.label?.byLanguage shouldBe mapOf("en" to "Product", "cs" to "Produkt")
            item.body
                .property<ConceptProperty.Grounds>()!!
                .ref.parts shouldContainExactly listOf("md", "Product")
            val sources = item.body.entry("sources") as PropertyValue.ListValue
            (sources.items.single() as PropertyValue.StringValue).raw shouldBe "doc://hartland/handbook#products"
            (item.body.entry("qualities") as PropertyValue.ListValue).items.map {
                (it as PropertyValue.IdValue).ref.path
            } shouldContainExactly
                listOf("category", "brand")
            item.body
                .property<ConceptProperty.Lexicon>()!!
                .block.terms shouldContainExactly
                listOf("product", "item", "SKU")
        }

        "T2: keeps a free-form object value structural (the grounding `hierarchy`)" {
            val cat = concept(defs75, "ProductCategory")
            keys(cat.body) shouldContainExactly listOf("label", "grounds", "hierarchy")
            val h = cat.body.entry("hierarchy") as PropertyValue.ObjectValue
            h.entries.keys.toList() shouldContainExactly listOf("closure", "ancestor", "descendant", "ranges")
            (h.entries["closure"] as PropertyValue.IdValue).ref.path shouldBe "er.entity.category_closure"
        }

        "T2: reads `is_a` (one ref and a list), `members` (a block) and the open tier" {
            val home = concept(defs75, "HomeCategory")
            (home.body.entry("is_a") as PropertyValue.IdValue).ref.path shouldBe "ProductCategory"
            val members = home.body.property<ConceptProperty.MembersBlock>()!!.block
            members.form shouldBe "tripleString"
            members.language shouldBe "pl"
            members.text shouldBe "descendants(ProductCategory, 'H10-H19')"
            val supplier = concept(defs75, "Supplier")
            (supplier.body.entry("is_a") as PropertyValue.ListValue).items.map {
                (it as PropertyValue.IdValue).ref.path
            } shouldContainExactly
                listOf("Party", "Organization")
            val relation =
                (
                    supplier.body.entry(
                        "relations",
                    ) as PropertyValue.ListValue
                ).items.single() as PropertyValue.ObjectValue
            relation.entries.keys.toList() shouldContainExactly listOf("type", "to")
            val supplies = concept(defs75, "supplies")
            supplies.stereotype shouldBe "relation_type"
            supplies.description shouldBe "A supplier supplies a distribution center."
        }

        "T2: walks a `def role` in `model cn` to a concept role: `event:` a ref, `of:` free-form" {
            val customer = concept(defs75, "Customer")
            customer.stereotype shouldBe "role"
            keys(customer.body) shouldContainExactly listOf("of", "event", "label", "grounds")
            customer.body.ref("event")?.path shouldBe "Order"
            (customer.body.entry("of") as PropertyValue.IdValue).ref.path shouldBe "Party"
            customer.body.label?.byLanguage shouldBe mapOf("en" to "Customer", "cs" to "Zákazník")
        }

        "T2: reads labelled participants, the period object and the extent" {
            val transfer = concept(defs75, "StockTransfer")
            transfer.body.property<ConceptProperty.Extent>()?.value shouldBe "instantaneous"
            transfer.body
                .property<ConceptProperty.Involves>()!!
                .participants
                .map { it.ref.path to it.label } shouldContainExactly
                listOf("Item" to null, "Warehouse" to "from", "Warehouse" to "to")
            transfer.body
                .property<ConceptProperty.Period>()!!
                .value.entries.keys
                .toList() shouldContainExactly
                listOf("at", "precision")
            val order = concept(defs75, "Order")
            order.body
                .property<ConceptProperty.Involves>()!!
                .participants
                .map { it.label } shouldContainExactly
                listOf(null, "fulfilling")
            order.body
                .property<ConceptProperty.Period>()!!
                .value.entries.keys
                .toList() shouldContainExactly
                listOf("start", "end", "end_open")
            // newline-separated properties, no commas
            keys(concept(defs75, "WarehouseOutage").body) shouldContainExactly listOf("extent", "involves", "period")
        }

        "T2: reads qualities: value_space, valued_state, and the three definition forms" {
            val category = concept(defs75, "category").body.property<ConceptProperty.ValueSpace>()!!
            category.ref.path shouldBe "ProductCategory"
            category.key shouldBe "value_space"
            (concept(defs75, "stock_level").body.entry("valued_state") as PropertyValue.BoolValue).raw shouldBe true
            val plain = concept(defs75, "open_amount").body.property<ConceptProperty.DefinitionBlock>()!!.block
            (plain.form to plain.text) shouldBe ("string" to "amount - paid")
            val tagged = concept(defs75, "transfer_delay").body.property<ConceptProperty.DefinitionBlock>()!!.block
            (tagged.form to tagged.text) shouldBe ("tagged" to "this.period.at - this.from.period.at")
        }

        "T2: reads phases: defined, stacked, abstract, with a basis and an imprecision" {
            concept(defs75, "PendingOrder")
                .body
                .property<ConceptProperty.DefinitionBlock>()!!
                .block.text shouldBe
                "state in ('placed', 'held')"
            (concept(defs75, "DebtorCustomer").body.entry("imprecision") as PropertyValue.IdValue).ref.path shouldBe
                "certainly"
            concept(defs75, "PlacedOrder")
                .body
                .property<ConceptProperty.Grounds>()!!
                .ref.path shouldBe
                "er.entity.placed_order"
            (concept(defs75, "StockCondition").body.entry("abstract") as PropertyValue.BoolValue).raw shouldBe true
            val oos = concept(defs75, "OutOfStock")
            keys(oos.body) shouldContainExactly listOf("of", "is_a", "basis", "definition")
            oos.body.property<ConceptProperty.Basis>()?.value shouldBe "current_state"
        }

        "T2: reports a repeated key once, at the repeat" {
            val r =
                TtrLoader.parseString(
                    "model cn\ndef kind X { grounds: md.A, label: { en: \"x\" }, grounds: md.B }",
                    "dup.ttrm",
                )
            r.errors.map { Triple(it.code, it.line, it.column) } shouldContainExactly
                listOf(Triple(DiagnosticCode.DuplicateProperty, 2, 49))
            r.errors.single().message shouldContain "'grounds'"
        }

        "T2: reads a role by the file model: a cnc role and an automation role keep their 0.16 shape" {
            val cnc = TtrLoader.parseString("model cnc\ndef role buyer { label: { en: \"Buyer\" } }", "r.ttrm")
            cnc.errors shouldBe emptyList()
            cnc.definitions
                .single()
                .shouldBeInstanceOf<RoleDef>()
                .label
                ?.byLanguage shouldBe mapOf("en" to "Buyer")
            val auto =
                TtrLoader.parseString(
                    "model automation\ndef role sales { resolve: keycloak_group \"hartland-sales\" }",
                    "r.ttrm",
                )
            auto.errors shouldBe emptyList()
            auto.definitions
                .single()
                .shouldBeInstanceOf<RoleDef>()
                .entries
                .map { it.key } shouldContainExactly
                listOf("resolve")
        }

        "T2: rejects the cnc / automation role properties on a concept role (review-114 M5, both ways)" {
            val src =
                "model cn\ndef role Customer {\n  event: Order,\n  resolve: keycloak_group \"x\",\n" +
                    "  search { searchable: true }\n}"
            val r = TtrLoader.parseString(src, "r.ttrm")
            r.errors.map { it.code to it.line } shouldContainExactly
                listOf(DiagnosticCode.UnknownProperty to 4, DiagnosticCode.UnknownProperty to 5)
            r.errors.first().message shouldContain "'resolve'"
        }

        // ---------------------------------------------------------------------------------------------
        // T3 — bindings (76), the sugar (77), lexicon targets (78)
        // ---------------------------------------------------------------------------------------------

        val defs76 = parseFixture("76-cn-bindings.ttrm").definitions

        "T3: walks the ten binding kinds" {
            defs76.map { (it as ConceptBindingDef).bindingKind } shouldContainExactly
                listOf(
                    "cn2md_kind",
                    "cn2md_role",
                    "cn2md_quality",
                    "cn2md_quality",
                    "cn2md_event",
                    "cn2er_kind",
                    "cn2er_kind",
                    "cn2er_role",
                    "cn2er_role",
                    "cn2er_event",
                    "cn2er_event",
                    "cn2er_quality",
                    "cn2er_quality",
                    "cn2er_phase",
                    "cn2db_concept",
                )
        }

        "T3: reads the stereotype ref (with its `cn.` head) and the target" {
            val item = binding(defs76, "item_g")
            item.body.ref("kind")!!.parts shouldContainExactly listOf("cn", "Item")
            (item.body.property<ConceptProperty.Target>()!!.value as TargetReferenceValue).ref.path shouldBe
                "md.Product"
            val brand = binding(defs76, "brand_g")
            brand.description shouldBe "an attribute, not a measure"
            (brand.body.property<ConceptProperty.Target>()!!.value as TargetReferenceValue).ref.path shouldBe
                "md.Product.brand"
            val legacy = binding(defs76, "legacy_g")
            legacy.body.ref("concept")?.path shouldBe "cn.LegacyStock"
            legacy.tags shouldContainExactly listOf("legacy")
            binding(defs76, "placed_g").body.ref("phase")?.path shouldBe "cn.PlacedOrder"
        }

        "T3: keeps `participants` a structured label → relation-path object, `via` a list" {
            val transfer = binding(defs76, "transfer_g")
            keys(transfer.body) shouldContainExactly listOf("event", "target", "participants", "period")
            val participants = transfer.body.entry("participants") as PropertyValue.ObjectValue
            participants.entries.keys.toList() shouldContainExactly listOf("item", "from", "to")
            (participants.entries["to"] as PropertyValue.ListValue).items.map {
                (it as PropertyValue.IdValue).ref.path
            } shouldContainExactly
                listOf("er.relation.rel_transfer_to", "er.relation.rel_warehouse_region")
            (binding(defs76, "orderer_g").body.entry("via") as PropertyValue.ListValue).items.size shouldBe 1
            binding(defs76, "category_g").body.entry("hierarchy").shouldBeInstanceOf<PropertyValue.ObjectValue>()
        }

        "T3: reads a binding `filter:` as an expression block" {
            val stock = binding(defs76, "stock_g")
            val filter = stock.body.property<ConceptProperty.FilterBlock>()!!
            filter.key shouldBe "filter"
            (filter.block.form to filter.block.text) shouldBe ("tripleString" to "warehouse = 1")
            stock.body.entry("filter").shouldBeNull()
        }

        val sugarSrc = cnResource("cn/77-cn-sugar.ttrm")
        val defs77 = parse(sugarSrc, "77-cn-sugar.ttrm").definitions
        val byName = { name: String -> defs77.single { it.name == name } }

        "T3: attaches the sugar to every md host" {
            val cubelet = byName("storeReturns") as CubeletDef
            cubelet.concept!!
                .body
                .ref("event")
                ?.path shouldBe "Return"
            cubelet.concept!!
                .body
                .property<ConceptProperty.Involves>()!!
                .participants
                .map { it.ref.path } shouldContainExactly
                listOf("Customer", "Item", "ReturnReason")
            val dim = byName("Product") as DimensionDef
            dim.concept!!
                .body
                .ref("kind")
                ?.path shouldBe "Item"
            dim.concept!!
                .body.label
                ?.byLanguage shouldBe mapOf("en" to "Product")
            dim.key shouldBe "sku"
            (byName("ReasonCode") as MdDomainDef)
                .concept!!
                .body
                .ref("kind")
                ?.path shouldBe "ReturnReason"
            (byName("returnAmount") as MeasureDef)
                .concept!!
                .body
                .ref("quality")
                ?.path shouldBe "return_amount"
        }

        "T3: attaches the sugar to entities and er attributes; the sugar spans its whole property" {
            val invoice = byName("invoice") as EntityDef
            invoice.concept!!
                .body
                .ref("kind")
                ?.path shouldBe "Invoice"
            invoice.attributes
                .single { it.name == "status" }
                .concept!!
                .body
                .ref("quality")
                ?.path shouldBe
                "invoice_status"
            invoice.attributes
                .single { it.name == "invoice_id" }
                .concept
                .shouldBeNull()
            val order = byName("order") as EntityDef
            keys(order.concept!!.body) shouldContainExactly listOf("event", "period")
            keys((byName("customer") as EntityDef).concept!!.body) shouldContainExactly listOf("role", "event")
            (byName("channel_sales") as EntityDef)
                .attributes[0]
                .concept!!
                .body
                .ref("kind")
                ?.path shouldBe "Channel"
            slice(sugarSrc, order.concept!!.source) shouldBe
                "concept { event: Order, period: { start: placed_date, end: delivered_date } }"
        }

        "T3: reads `for: cn.X`, `default` and `sense_priority`" {
            val defs78 = parseFixture("78-cn-lexicon.ttrm").definitions.map { it as LexiconEntryDef }
            val entry = { name: String -> defs78.single { it.name == name } }
            entry("customer").target?.path shouldBe "cn.Customer"
            entry("customer").default shouldBe true
            entry("customer").sensePriority.shouldBeNull()
            entry("debtor").sensePriority shouldBe 2.0
            entry("debtor").default.shouldBeNull()
            (entry("customer_phase").default to entry("customer_phase").sensePriority) shouldBe (false to 1.0)
            (entry("revenue").default to entry("revenue").sensePriority) shouldBe (true to 3.0)
            entry("channel").default.shouldBeNull()
            entry("web_revenue").entryKind shouldBe "example"
        }

        // ---------------------------------------------------------------------------------------------
        // T4 — expression blocks: text exact, positions exact
        // ---------------------------------------------------------------------------------------------

        val tagged =
            listOf(
                "model cn",
                "def phase P {",
                "    of: Order,",
                "    definition: \"\"\"pl",
                "        exists Invoicing i involving this",
                "          where i.amount > 0",
                "",
                "        and state = 'open'",
                "    \"\"\"",
                "}",
            ).joinToString("\n")

        "T4: keeps a tagged block byte-exact — inner indent, blank line, no closing newline" {
            val b = definitionBlock(tagged)
            b.form shouldBe "tagged"
            b.text shouldBe "exists Invoicing i involving this\n  where i.amount > 0\n\nand state = 'open'"
            b.indentWidth shouldBe 8
            b.lineOffsets.size shouldBe 4
        }

        "T4: points a diagnostic at column n of line 2 of the block" {
            val b = definitionBlock(tagged)
            val at = b.location(1, 8, 8)
            slice(tagged, at) shouldBe "i.amount"
            (at.line to at.column) shouldBe positionOf(tagged, tagged.indexOf("i.amount"))
            (at.endColumn - at.column) shouldBe 8
            val exists = b.location(0, 0, 6)
            slice(tagged, exists) shouldBe "exists"
            (exists.line to exists.column) shouldBe positionOf(tagged, tagged.indexOf("exists"))
            val state = b.location(3, 4, 5)
            slice(tagged, state) shouldBe "state"
            (state.line to state.column) shouldBe positionOf(tagged, tagged.indexOf("state = 'open'"))
            slice(tagged, b.textSource) shouldBe
                "exists Invoicing i involving this\n          where i.amount > 0\n\n        and state = 'open'"
        }

        "T4: maps offsets in a CRLF file too" {
            val crlf = tagged.replace("\n", "\r\n")
            slice(crlf, definitionBlock(crlf).location(1, 8, 8)) shouldBe "i.amount"
        }

        "T4: maps a one-line triple-string and a plain string from their opening quote" {
            val src = "model cn\ndef phase P { of: Order, definition: \"\"\"state in ('placed', 'held')\"\"\" }"
            val b = definitionBlock(src)
            (b.form to b.text) shouldBe ("tripleString" to "state in ('placed', 'held')")
            b.lineOffsets shouldContainExactly listOf(src.indexOf("state"))
            val paren = b.location(0, 9, 1)
            slice(src, paren) shouldBe "("
            paren.column shouldBe src.indexOf('(') - src.indexOf('\n') - 1
            val plain = "model cn\ndef quality P { of: Invoicing, definition: \"amount - paid\" }"
            val s = definitionBlock(plain)
            (s.form to s.text) shouldBe ("string" to "amount - paid")
            slice(plain, s.location(0, 7)) shouldBe "-"
        }

        "T4: keeps an unknown first-line word as text (a warning), and refuses a foreign-language tag" {
            val word =
                parse(
                    "model cn\ndef phase P { definition: \"\"\"exists\n  Invoicing involving this\n\"\"\" }",
                    "w.ttrm",
                )
            val wb = (word.definitions.single() as ConceptDef).body.property<ConceptProperty.DefinitionBlock>()!!.block
            (wb.form to wb.text) shouldBe ("tripleString" to "exists\n  Invoicing involving this\n")
            word.warnings.single().message shouldStartWith DiagnosticCode.UnknownLanguageTag.id
            val sql =
                TtrLoader.parseString(
                    "model cn\ndef phase P { definition: \"\"\"sql\n  SELECT 1\n\"\"\" }",
                    "s.ttrm",
                )
            sql.errors.map { it.code } shouldContainExactly listOf(DiagnosticCode.LanguageTagMismatch)
            sql.errors.single().message shouldContain "'sql'"
        }

        "T4: leaves the SQL hosts alone: `pl` is still an unknown tag on a view" {
            val r = parse("model db\ndef view v { definitionSql: \"\"\"pl\nSELECT 1\n\"\"\" }", "v.ttrm")
            r.warnings.single().message shouldStartWith DiagnosticCode.UnknownLanguageTag.id
        }

        // ---------------------------------------------------------------------------------------------
        // T4 — the read is lossless: every key, label, tag, participant label and span survives
        // ---------------------------------------------------------------------------------------------

        "T4 lossless: every cn property of 75–77 spans source text that starts with its key" {
            listOf("75-cn-concepts.ttrm", "76-cn-bindings.ttrm", "77-cn-sugar.ttrm").forEach { name ->
                val src = cnResource("cn/$name")
                val bodies =
                    parse(src, name).definitions.flatMap { d ->
                        when (d) {
                            is ConceptDef -> listOf(d.body)
                            is ConceptBindingDef -> listOf(d.body)
                            is EntityDef ->
                                listOfNotNull(d.concept?.body) +
                                    d.attributes.mapNotNull { it.concept?.body }
                            is CubeletDef -> listOfNotNull(d.concept?.body)
                            is DimensionDef -> listOfNotNull(d.concept?.body)
                            is MdDomainDef -> listOfNotNull(d.concept?.body)
                            is MeasureDef -> listOfNotNull(d.concept?.body)
                            else -> emptyList()
                        }
                    }
                bodies.flatMap { it.properties }.forEach { p ->
                    slice(src, p.source) shouldStartWith p.key
                    // a property span never swallows the separator after it
                    slice(src, p.source).trimEnd().endsWith(",") shouldBe false
                }
            }
        }

        "T4 lossless: a participant spans its label, a block keeps its tag in its span, a trailing comma is dropped" {
            val src = cnResource("cn/75-cn-concepts.ttrm")
            val transfer = concept(defs75, "StockTransfer")
            transfer.body.property<ConceptProperty.Involves>()!!.participants.map {
                slice(
                    src,
                    it.source,
                )
            } shouldContainExactly
                listOf("Item", "Warehouse as from", "Warehouse as to")
            val delay = concept(defs75, "transfer_delay").body.property<ConceptProperty.DefinitionBlock>()!!.block
            slice(src, delay.source) shouldStartWith "\"\"\"pl"
            slice(src, delay.source) shouldBe src.substring(delay.source.offsetStart, delay.source.offsetEnd)
            val home = concept(defs75, "HomeCategory")
            slice(
                src,
                home.body.properties
                    .last()
                    .source,
            ) shouldBe "label: { en: \"Home and garden categories\" }"
        }

        // ---------------------------------------------------------------------------------------------
        // T4 negative — the first syntax error of each cn reject sits where TS and Python put it
        // ---------------------------------------------------------------------------------------------

        listOf(
            "neg-01-bare-definition.ttrm" to (3 to 55),
            "neg-02-involves-no-brackets.ttrm" to (3 to 28),
            "neg-03-bare-members.ttrm" to (3 to 57),
            "neg-04-unlabelled-as.ttrm" to (3 to 77),
        ).forEach { (name, at) ->
            "T4 negative: $name reports its first syntax error at ${at.first}:${at.second} on all three targets" {
                val first = TtrLoader.parseString(cnResource("cn-negative/$name"), name).errors.first()
                first.code shouldBe DiagnosticCode.ParseError
                (first.line to first.column) shouldBe at
            }
        }

        // ---------------------------------------------------------------------------------------------
        // T6 — parse-level diagnostics and recovery
        // ---------------------------------------------------------------------------------------------

        "T6: flags a binding `filter:` that is not a block" {
            val r =
                TtrLoader.parseString(
                    "model binding\ndef cn2er_quality q { quality: cn.x, target: er.entity.o.v, filter: warehouse }",
                    "f.ttrm",
                )
            r.errors.map { it.code } shouldContainExactly listOf(DiagnosticCode.ExpressionBlockRequired)
        }

        "T6: recovers past an unknown binding kind — the error names line 2" {
            val src =
                "model binding\ndef cn2xx_kind a { kind: cn.Item, target: md.Product }\n" +
                    "def cn2md_kind b { kind: cn.Item, target: md.Product }"
            TtrLoader
                .parseString(
                    src,
                    "u.ttrm",
                ).errors
                .any { it.code == DiagnosticCode.ParseError && it.line == 2 } shouldBe
                true
        }

        "T6: survives a missing body, a conjured block token and a half-typed phase" {
            listOf(
                "model cn\ndef phase P",
                "model cn\ndef phase P { definition: }",
                "model cn\ndef event E { involves: [ ] }",
                "model cn\ndef role R",
                "model cn\ndef phase Pending {\n  of: Order,\n  definition: \"\"\"state = 'open'\"\"\",\n  basis: ",
            ).forEach { src ->
                TtrLoader
                    .parseString(src, "m.ttrm")
                    .errors
                    .shouldNotBeNull()
                    .isNotEmpty() shouldBe true
            }
        }

        // ---------------------------------------------------------------------------------------------
        // review-117 — the source map counts code points and steps over escapes (M-3, L-1), a second
        // `concept { }` is a duplicate property (M-4), a tag is an own key of its table (L-2). The same
        // numbers as the TS and Python suites.
        // ---------------------------------------------------------------------------------------------

        "review-117 M-3: maps every character of a string-form block with escapes onto the file" {
            val src = """model cn
def quality P { of: X, definition: "a = \"held\" and b = 'c\\d'" }"""
            val b = definitionBlock(src)
            b.text shouldBe "a = \"held\" and b = 'c\\d'"
            b.escapes shouldContainExactly listOf(4, 9, 21)
            for (i in b.text.indices) {
                val written = cpSlice(src, b.location(0, i))
                written shouldBe (if (i in b.escapes) "\\" + b.text[i] else b.text[i].toString())
            }
            b.textSource.offsetStart to b.textSource.offsetEnd shouldBe (45 to 72)
            val at = b.location(0, 15)
            Triple(at.offsetStart, at.column, cpSlice(src, at)) shouldBe Triple(62, 53, "b")
        }

        "review-117 L-1: counts code points in a block after a character outside the BMP" {
            val src =
                listOf(
                    "model cn",
                    "def phase A {",
                    "    of: Order,",
                    "    definition: \"\"\"",
                    "    name = '😀😀' and",
                    "    total > 10",
                    "  \"\"\"",
                    "}",
                    "def phase B { of: Order, definition: \"x = '😀' and y = \\\"z\\\"\" }",
                ).joinToString("\n")
            val defs = parse(src, "r.ttrm").definitions
            val a = concept(defs, "A").body.property<ConceptProperty.DefinitionBlock>()!!.block
            a.lineOffsets shouldContainExactly listOf(62, 82, 93)
            (a.textSource.offsetStart to a.textSource.offsetEnd) shouldBe (62 to 93)
            (a.textSource.endLine to a.textSource.endColumn) shouldBe (7 to 0)
            cpSlice(src, a.location(1, 0, 5)) shouldBe "total"
            val b = concept(defs, "B").body.property<ConceptProperty.DefinitionBlock>()!!.block
            b.escapes shouldContainExactly listOf(16, 18)
            (b.textSource.offsetStart to b.textSource.offsetEnd) shouldBe (139 to 160)
            (b.textSource.column to b.textSource.endColumn) shouldBe (38 to 59)
            val y = b.location(0, 12)
            Triple(y.offsetStart, y.column, cpSlice(src, y)) shouldBe Triple(151, 50, "y")
            cpSlice(src, b.location(0, 16, 3)) shouldBe "\\\"z\\\""
        }

        "review-118 L-1: reads an escaped line break into the text and maps the line after it" {
            val src = "model cn\ndef quality P { of: X, definition: \"a = 1 \\\nand b = 2\" }"
            val b = definitionBlock(src)
            b.text shouldBe "a = 1 \nand b = 2"
            b.escapes shouldContainExactly listOf(6)
            b.lineOffsets shouldContainExactly listOf(45, 53)
            val and = b.location(1, 0, 3)
            Triple(and.line, and.column, cpSlice(src, and)) shouldBe Triple(3, 0, "and")
            (b.textSource.offsetStart to b.textSource.offsetEnd) shouldBe (45 to 62)
            (b.textSource.endLine to b.textSource.endColumn) shouldBe (3 to 9)
            for (sep in listOf("\r", " ", "\u0085")) {
                definitionBlock("model cn\ndef quality P { of: X, definition: \"a\\${sep}b\" }").text shouldBe
                    "a${sep}b"
            }
        }

        "review-118 M-2: reports a second `concept { }` on an md inline attribute and measure, as Python does" {
            val attr =
                "model md\ndef dimension D { attributes: [ def attribute a { type: string, concept { quality: Q }, " +
                    "concept { quality: R } } ] }"
            TtrLoader
                .parseString(
                    attr,
                    "r.ttrm",
                ).errors
                .map { Triple(it.code, it.line, it.column) } shouldContainExactly
                listOf(Triple(DiagnosticCode.DuplicateProperty, 2, 89))
            val measure =
                "model md\ndef cubelet C { measures: [ def measure m { concept { quality: Q }, " +
                    "concept { quality: R } } ] }"
            TtrLoader
                .parseString(
                    measure,
                    "r.ttrm",
                ).errors
                .map { Triple(it.code, it.line, it.column) } shouldContainExactly
                listOf(Triple(DiagnosticCode.DuplicateProperty, 2, 69))
        }

        "review-117 M-4: reports a second `concept { }` on a host and reads the first" {
            val r =
                TtrLoader.parseString(
                    "model er\ndef entity e { concept { kind: A }, concept { event: B } }",
                    "r.ttrm",
                )
            r.errors.map { Triple(it.code, it.line, it.column) } shouldContainExactly
                listOf(Triple(DiagnosticCode.DuplicateProperty, 2, 37))
            r.errors.single().message shouldContain "'concept'"
        }

        "review-117 L-2: reads a `\"\"\"toString` first line as text, not as a tag" {
            val r = parse("model cn\ndef phase P { definition: \"\"\"toString\n  x\n\"\"\" }", "r.ttrm")
            val b = (r.definitions.single() as ConceptDef).body.property<ConceptProperty.DefinitionBlock>()!!.block
            (b.form to b.text) shouldBe ("tripleString" to "toString\n  x\n")
            r.warnings.single().message shouldStartWith DiagnosticCode.UnknownLanguageTag.id
        }
    })

private fun cnResource(path: String): String =
    object {}
        .javaClass.classLoader
        .getResource(path)
        ?.readText()
        ?: error("missing test resource: $path")

private fun parse(
    src: String,
    name: String,
): ParseResult = TtrLoader.parseString(src, name)

private fun parseFixture(name: String): ParseResult {
    val r = parse(cnResource("cn/$name"), name)
    r.errors shouldBe emptyList()
    return r
}

private fun concept(
    defs: List<Definition>,
    name: String,
): ConceptDef = defs.filterIsInstance<ConceptDef>().single { it.name == name }

private fun binding(
    defs: List<Definition>,
    name: String,
): ConceptBindingDef = defs.filterIsInstance<ConceptBindingDef>().single { it.name == name }

private fun keys(body: ConceptBody): List<String> = body.properties.map { it.key }

private fun definitionBlock(src: String): EmbeddedBlock {
    val r = TtrLoader.parseString(src, "b.ttrm")
    r.errors shouldBe emptyList()
    return (r.definitions.single() as ConceptDef).body.property<ConceptProperty.DefinitionBlock>()!!.block
}

private fun slice(
    src: String,
    at: SourceLocation,
): String = src.substring(at.offsetStart, at.offsetEnd)

/** 1-based line / 0-based column of an offset in [src] — the SourceLocation convention. */
private fun positionOf(
    src: String,
    offset: Int,
): Pair<Int, Int> {
    val before = src.substring(0, offset).split("\n")
    return before.size to before.last().length
}

/** The file text a source span covers — its offsets count code points, a Kotlin string UTF-16 units. */
private fun cpSlice(
    src: String,
    at: SourceLocation,
): String = src.substring(src.offsetByCodePoints(0, at.offsetStart), src.offsetByCodePoints(0, at.offsetEnd))
