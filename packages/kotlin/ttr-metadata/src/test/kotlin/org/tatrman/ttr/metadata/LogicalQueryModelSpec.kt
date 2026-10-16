// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.metadata.export.ModelToDefinitions
import org.tatrman.ttr.metadata.graph.EdgeType
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.writer.TtrRenderer
import java.nio.file.Files

/**
 * LQ-T0·S2 — a logical query in the metadata model (project `ttr-translator/logical-queries`, contracts
 * L-1, L-2, L-4 declaration half). A `def query` declared in a `model er` file loads as a [Query] whose
 * qname is `(ER, "query", <name>)`; an entity stacked on it maps `ToQuery` (a [MappingTarget.SqlQuery])
 * onto that qname, its attribute bindings naming the query's output columns; the export writes the query
 * back under `model er` and the reload is identical. A logical query with parameters is refused at
 * assembly (`logical_query_parameters`, ruling LQ-5). A db query is untouched.
 *
 * The files mirror `samples/2.1/logical-query/` in the reference spelling the Kotlin loader reads
 * (`db.dbo.<table>`, no `table.` segment — see [org.tatrman.ttr.metadata.source.V21SamplesSpec]).
 */
class LogicalQueryModelSpec :
    StringSpec({
        val db =
            """
            model db schema dbo

            def table store_sales {
                primaryKey: [ss_ticket_number],
                columns: [
                    def column ss_ticket_number { type: int, isKey: true },
                    def column ss_customer_sk { type: int, optional: true },
                    def column ss_net_paid { type: decimal, optional: true }
                ]
            }

            def table customer {
                primaryKey: [c_customer_sk],
                columns: [
                    def column c_customer_sk { type: int, isKey: true },
                    def column c_last_name { type: text, optional: true }
                ]
            }
            """.trimIndent()

        val er =
            """
            model er

            def entity sale {
                binding: { target: { table: db.dbo.store_sales } },
                attributes: [
                    def attribute ticket_number { type: int, isKey: true, binding: ss_ticket_number },
                    def attribute shopper_id { type: int, optional: true, binding: ss_customer_sk },
                    def attribute revenue { type: decimal, optional: true, binding: ss_net_paid }
                ]
            }

            def entity shopper {
                binding: { target: { table: db.dbo.customer } },
                attributes: [
                    def attribute shopper_id { type: int, isKey: true, binding: c_customer_sk },
                    def attribute last_name { type: text, optional: true, binding: c_last_name }
                ]
            }

            def relation sale_shopper {
                from: er.entity.sale, to: er.entity.shopper,
                cardinality: { from: "0..*", to: "1" },
                join: [{ from: er.entity.sale.shopper_id, to: er.entity.shopper.shopper_id }]
            }
            """.trimIndent()

        val body =
            "SELECT shopper.shopper_id, shopper.last_name, SUM(sale.revenue) AS revenue\n" +
                "FROM sale JOIN shopper\n" +
                "GROUP BY shopper.shopper_id, shopper.last_name"

        fun revenue(parameters: String = "") =
            """
            model er

            def query shopper_revenue {
                description: "Revenue per shopper, from store sales",
                tags: ["sales"],$parameters
                sourceText: ""${'"'}sql
            $body
            ""${'"'}
            }

            def entity shopper_revenue {
                binding: { target: { query: er.query.shopper_revenue } },
                attributes: [
                    def attribute shopper_id { type: int, isKey: true },
                    def attribute last_name { type: text, optional: true },
                    def attribute total_revenue { type: decimal, optional: true, binding: revenue }
                ]
            }
            """.trimIndent()

        fun load(files: Map<String, String>): LoadResult =
            Files.createTempDirectory("lq-model").let { root ->
                for ((rel, content) in files) {
                    val p = root.resolve(rel)
                    Files.createDirectories(p.parent)
                    Files.writeString(p, content)
                }
                val storage = LocalFsStorage(id = "lq", rootPath = root)
                val source = FileBasedSource(sourceId = "lq", priority = 100, storage = storage)
                MetadataLoader(source, ModelDescriptor(id = "lq", name = "lq")).load()
            }

        val sample = mapOf("db.ttrm" to db, "er.ttrm" to er, "revenue.ttrm" to revenue())
        val erQuery = QualifiedName(SchemaCode.ER, "query", "shopper_revenue")
        val stacked = QualifiedName(SchemaCode.ER, "entity", "shopper_revenue")

        "a def query in a model er file loads as an ER-lane Query, er.query.<name>" {
            val result = load(sample)
            result.errors.shouldBeEmpty()
            val model = result.model.shouldNotBeNull()
            val query = model.queries[erQuery].shouldNotBeNull()
            query.qname shouldBe erQuery
            query.sourceText shouldContain "FROM sale JOIN shopper"
            query.sourceLanguage shouldBe "SQL"
            query.parameters.shouldBeEmpty()
            // no db-lane twin, no stranded UNSPECIFIED key
            model.queries.keys.filter { it.name == "shopper_revenue" } shouldBe listOf(erQuery)
        }

        "the stacked entity maps ToQuery onto er.query.<name>; attribute bindings name output columns" {
            val model = load(sample).model.shouldNotBeNull()
            val entityMapping =
                model.mappings.filterIsInstance<Er2DbEntityMapping>().single { it.entity == stacked }
            entityMapping.target.shouldBeInstanceOf<MappingTarget.SqlQuery>().qname shouldBe erQuery
            model.objectByQname()[erQuery].shouldBeInstanceOf<Query>()
            // the model graph links the mapping to the logical query (entity → query; what the
            // query scans is known only after its body is parsed)
            val graph = ModelGraph.build(model)
            val queryId = model.queries.getValue(erQuery).internalId
            graph.outgoingOf(entityMapping.internalId, setOf(EdgeType.MAPS_TO)).map { it.target } shouldBe
                listOf(queryId)
            // `total_revenue` is bound to the output column `revenue`; unbound attributes keep their own name
            val attrMappings =
                model.mappings
                    .filterIsInstance<Er2DbAttributeMapping>()
                    .filter { it.attribute.name.startsWith("shopper_revenue.") }
            attrMappings shouldHaveSize 1
            attrMappings.single().attribute.name shouldBe "shopper_revenue.total_revenue"
            attrMappings
                .single()
                .target
                .shouldBeInstanceOf<AttributeMappingTarget.Column>()
                .qname.name shouldBe "revenue"
        }

        "export writes the logical query under model er; the reload is the same query" {
            val model = load(sample).model.shouldNotBeNull()
            val files = ModelToDefinitions.convert(model).files
            val erQueryFile = files.single { f -> f.definitions.any { it.name == "shopper_revenue" && it is QueryDef } }
            erQueryFile.schemaCode shouldBe "er"
            // the er model's entities/relations keep their own (directive-less) file
            erQueryFile.definitions.all { it is QueryDef } shouldBe true

            val rendered =
                files.associate { f ->
                    f.filename to
                        TtrRenderer.renderFile(f.schemaCode, f.namespace, f.definitions)
                }
            rendered.getValue(erQueryFile.filename) shouldContain "model er"
            val reloaded = load(rendered).model.shouldNotBeNull()
            val again = reloaded.queries[erQuery].shouldNotBeNull()
            again.sourceText shouldBe model.queries.getValue(erQuery).sourceText
            reloaded.queries.keys shouldContain erQuery
            // … and the stacked entity still maps ToQuery onto it, its binding to `revenue` intact
            reloaded.mappings
                .filterIsInstance<Er2DbEntityMapping>()
                .single { it.entity == stacked }
                .target
                .shouldBeInstanceOf<MappingTarget.SqlQuery>()
                .qname shouldBe erQuery
            reloaded.mappings
                .filterIsInstance<Er2DbAttributeMapping>()
                .single { it.attribute.name == "shopper_revenue.total_revenue" }
                .target
                .shouldBeInstanceOf<AttributeMappingTarget.Column>()
                .qname.name shouldBe "revenue"
        }

        "a db query is unchanged: db.dbo.<name>, exported in db.ttr" {
            val dbQuery =
                """
                def query store_revenue {
                    sourceText: "SELECT ss_customer_sk, SUM(ss_net_paid) AS revenue FROM store_sales GROUP BY ss_customer_sk"
                }
                """.trimIndent()
            val model = load(sample + ("dbq.ttrm" to dbQuery)).model.shouldNotBeNull()
            val dbDbo = QualifiedName(SchemaCode.DB, "dbo", "store_revenue")
            model.queries.keys shouldContain dbDbo
            ModelToDefinitions
                .convert(model)
                .files
                .single { f -> f.definitions.any { it.name == "store_revenue" } }
                .let { f ->
                    f.filename shouldBe "db.ttr"
                    f.schemaCode shouldBe null
                }
        }

        "a logical query with parameters is refused at assembly (logical_query_parameters)" {
            val withParams = sample + ("revenue.ttrm" to revenue("\n    parameters: [ { name: since, type: date } ],"))
            val result = load(withParams)
            val refusal = result.errors.filter { it.message.contains("logical_query_parameters") }
            refusal shouldHaveSize 1
            refusal.single().message shouldContain "er.query.shopper_revenue"
        }
    })
