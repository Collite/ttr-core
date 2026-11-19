// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.profile.ProfileException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** S5.4 T1's five behaviours on invented PD 16 XML. D-MW-109 defers the md clause. */
class PdmReaderSpec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/pdm").toAbsolutePath().normalize()

        fun files(dir: Path) =
            Files.walk(dir).use { paths -> paths.filter { it.isRegularFile() }.toList() }.associate {
                dir.relativize(it).joinToString("/") to
                    it.readText()
            }

        fun workspace() =
            Files.createTempDirectory("s54-pdm").also { root ->
                files(fixture.resolve("sources")).forEach { (path, text) ->
                    root.resolve("sources/$path").also { it.parent.createDirectories() }.writeText(text)
                }
            }

        fun run(root: Path) =
            Reader.run(
                PdmFrontEnd,
                ImportProfile.resolve("pdm/16"),
                ReaderRequest(listOf(root.resolve("sources")), root.resolve("model"), "0.0.1-LOCAL"),
            )

        test("CDM entities and inheritances make an abstract cn skeleton; relationships are open-tier relation types") {
            val cn = run(workspace()).files.getValue("cn_sales/cn.ttrm")
            cn shouldContain "def kind Customer"
            cn shouldContain "def kind Person"
            cn shouldContain "is_a: [cn_sales.cn.kind.Person]"
            cn shouldContain "def relation_type orders"
            cn shouldContain "abstract: true"
            cn shouldNotContain "involves:"
            cn shouldNotContain "grounds:"
        }
        test("LDM entities, attributes and directed relationship cardinalities are preserved") {
            val er = run(workspace()).files.getValue("er_sales/er.ttrm")
            er shouldContain "def entity Customer"
            er shouldContain "def attribute email"
            er shouldContain "length: 80"
            er shouldContain "optional: true"
            er shouldContain "def relation placed_by"
            er shouldContain "er_sales.er.entity.Order"
            er shouldContain "er_sales.er.entity.Customer"
            er shouldContain "0..*"
            er shouldContain "1..1"
            er shouldNotContain "abstract:"
        }
        test(
            "PDM tables, typed columns and reference joins are deterministic and byte-equal to all committed outputs",
        ) {
            val generated = run(workspace())
            val db = generated.files.getValue("db_sales/db.ttrm")
            db shouldContain "def table customers"
            db shouldContain "def fk fk_orders_customer"
            db shouldContain "db_sales.db.dbo.orders.customer_id"
            db shouldContain "db_sales.db.dbo.customers.id"
            db shouldContain "precision: 2"
            db shouldNotContain "abstract:"
            generated.files.getValue("db_sales/audit/db.ttrm") shouldContain "def table change_log"
            run(workspace()).outputs() shouldBe generated.outputs()
            if (System.getenv("TTR_IMPORT_GOLDEN") == "1") {
                generated.outputs().forEach { (path, text) ->
                    fixture.resolve("model/$path").also { it.parent.createDirectories() }.writeText(text)
                }
            }
            generated.outputs() shouldBe files(fixture.resolve("model"))
            generated.report.markdown() shouldContain "PD diagrams, symbols and display preferences are not model facts"
        }
        test(
            "generation origins produce cn2er and er2db binding documents that strict-load with the whole output set",
        ) {
            val root = workspace()
            val generated = run(root)
            generated.files.getValue("er_sales/binding.ttrm") shouldContain "def cn2er_kind"
            val binding = generated.files.getValue("db_sales/binding.ttrm")
            binding shouldContain "def er2db_entity"
            binding shouldContain "def er2db_attribute"
            binding shouldContain "def er2db_relation"
            binding shouldContain "er_sales.er.entity.Customer"
            binding shouldContain "db_sales.db.dbo.customers"
            generated.outputs().forEach { (path, text) ->
                root.resolve("model/$path").also { it.parent.createDirectories() }.writeText(text)
            }
            val loaded =
                MetadataLoader(
                    listOf(
                        BuiltinStockSource(),
                        FileBasedSource("pdm", 100, LocalFsStorage("pdm", root.resolve("model"))),
                    ),
                ).load(LoadOptions(strict = true))
            withClue(loaded.issues.joinToString("\n")) {
                loaded.report?.ok shouldBe true
                loaded.errors shouldBe emptyList()
            }
        }
        test("PD 15 is refused with xm/pdm-version at its processing instruction") {
            val error = shouldThrow<ProfileException> { PdmFrontEnd.parse(listOf(fixture.resolve("pd15.pdm"))) }
            error.code shouldBe "xm/pdm-version"
            error.location.file shouldBe "pd15.pdm"
            error.location.line shouldBe 2
        }
    })
