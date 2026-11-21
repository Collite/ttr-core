// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.export.DefinitionSet
import org.tatrman.ttr.metadata.export.ModelToDefinitions
import org.tatrman.ttr.metadata.model.DbProcedure
import org.tatrman.ttr.metadata.model.DbProcedureParameter
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.ParameterDirection
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.WhereFilter
import org.tatrman.ttr.parser.model.ProcedureDef
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class YamlIngestionSpec :
    FunSpec({
        fun storage(vararg files: Pair<String, String>): LocalFsStorage {
            val root = Files.createTempDirectory("yaml-ingestion")
            files.forEach { (name, text) ->
                root.resolve(name).also { it.parent.createDirectories() }.writeText(text.trimIndent())
            }
            return LocalFsStorage("s", root)
        }

        val table = QualifiedName(SchemaCode.DB, "dbo", "sale", "demo")
        val profile =
            """
            profile: example/1
            grammar: '>=0.22 <1.0'
            operations:
              - {op: package, mapping: {}, default: demo.reporting}
              - {op: kinds, at: /tables, mapping: {tables: table}}
              - {op: kinds, at: /sales, mapping: {sales: entity}}
              - {op: kinds, at: /sales, mapping: {sales: er2db_entity}}
              - {op: link, in: er2db_entity, at: /${'$'}key, to: {kind: er2db_entity, property: entity}, by: sourceName}
              - {op: link, in: er2db_entity, at: /input/from, to: {kind: er2db_entity, path: [target, table]}, by: sourceName}
              - {op: shape, in: er2db_entity, at: /input, to: {kind: er2db_entity, property: whereFilter}, template: {sql: {input: /where}}}
            """

        fun dialect(target: String) =
            storage(
                "ttr.yaml" to "profile: ./example.yaml\ngrammar: '>=0.22 <1.0'\n",
                "model.ignore" to "example.yaml\n",
                "example.yaml" to profile,
                "demo/reporting/sales.yaml" to
                    """
                    tables: {sale: {}}
                    sales: {ActiveSale: {input: {from: $target, where: 'id = 1'}}}
                    """,
            )

        test("an accepted scalar procedure is available in the typed snapshot and export") {
            val storage =
                storage(
                    "demo/db.yaml" to
                        """
                        ttr: "0.22"
                        package: demo
                        model: db
                        defs:
                          - kind: procedure
                            id: sale_total
                            properties:
                              description: "Calculate sales"
                              tags: [sales]
                              parameters:
                                - {name: store_id, type: int}
                                - {name: total, type: decimal, direction: OUT}
                              resultColumns:
                                - id: total
                                  properties: {type: decimal}
                        """,
                )
            val loaded = MetadataLoader.of(SourceKind.YAML, storage).load()
            loaded.errors.shouldBeEmpty()
            val model = requireNotNull(loaded.model)
            val definitions = DefinitionSet.from(loaded.files, model, "0.22", model.version.value)
            val entry = definitions.entries.single { it.def is ProcedureDef }
            entry.qname shouldBe "db.dbo.sale_total"
            entry.pkg shouldBe "demo"
            val qname = QualifiedName(SchemaCode.DB, "dbo", "sale_total")
            val procedure = model.objectByQname().getValue(qname) as DbProcedure
            procedure.description shouldBe "Calculate sales"
            procedure.tags shouldBe listOf("sales")
            procedure.parameters shouldBe
                listOf(
                    DbProcedureParameter("store_id", "int", ParameterDirection.IN),
                    DbProcedureParameter("total", "decimal", ParameterDirection.OUT),
                )
            procedure.resultColumns.single().qname shouldBe qname.copy(name = "sale_total.total")
            val bundle = ModelToDefinitions.convert(model)
            val exported =
                bundle.files
                    .flatMap { it.definitions }
                    .filterIsInstance<ProcedureDef>()
                    .single()
            exported.name shouldBe entry.def.name
            val exportedColumn = exported.resultColumns.single()
            exportedColumn.type?.name shouldBe "decimal"
        }

        test("a dialect link delivers the existing physical table and SQL condition to the snapshot") {
            val storage = dialect("sale")
            val applied = ProfileApply.run(storage)
            applied.problems.shouldBeEmpty()
            applied.files.getValue("demo/reporting/binding.yaml") shouldContain "table: \"demo.reporting.db.dbo.sale\""

            val source = YamlFileSource("s", 100, storage).load()
            source.errors.shouldBeEmpty()
            val mapping = source.mappings.filterIsInstance<Er2DbEntityMapping>().single()
            mapping.target shouldBe MappingTarget.Table(table.copy(`package` = "demo.reporting"))
            val physical = source.tables.getValue(table.copy(`package` = ""))
            physical.qname.name shouldBe "sale"
            (mapping.whereFilter as WhereFilter.Sql).sql shouldBe "id = 1"
        }

        test("canonical bare, wrapped and quoted targets identify the same table") {
            val storage =
                storage(
                    "demo/db.yaml" to
                        """
                        ttr: "0.22"
                        package: demo
                        model: db
                        defs:
                          - kind: table
                            id: sale
                            properties: {}
                        """,
                    "demo/bindings.yaml" to
                        """
                        ttr: "0.22"
                        package: demo
                        model: binding
                        defs:
                          - kind: er2db_entity
                            id: Bare
                            properties: {entity: er.entity.Bare, target: demo.db.dbo.sale}
                          - kind: er2db_entity
                            id: Wrapped
                            properties: {entity: er.entity.Wrapped, target: {table: {ref: demo.db.dbo.sale}}}
                          - kind: er2db_entity
                            id: Quoted
                            properties: {entity: er.entity.Quoted, target: {table: "demo.db.dbo.sale"}}
                          - kind: er2db_entity
                            id: Missing
                            properties: {entity: er.entity.Missing, target: {table: "other.db.dbo.sale"}}
                        """,
                )
            val source = YamlFileSource("s", 100, storage).load()
            source.errors.shouldBeEmpty()
            val targets =
                source.mappings.filterIsInstance<Er2DbEntityMapping>().associate { it.entity.name to it.target }
            targets shouldBe
                mapOf(
                    "Bare" to MappingTarget.Table(table),
                    "Wrapped" to MappingTarget.Table(table),
                    "Quoted" to MappingTarget.Table(table),
                    "Missing" to MappingTarget.Table(QualifiedName(SchemaCode.DB, "dbo", "<unresolved>")),
                )
        }

        test("a genuinely missing dialect target still reports the unresolved link") {
            val source = YamlFileSource("s", 100, dialect("missing_sale")).load()
            source.errors.single { "xm/link-unresolved" in it.message }.message shouldContain "missing_sale"
            source.mappings.filterIsInstance<Er2DbEntityMapping>().shouldBeEmpty()
        }
    })
