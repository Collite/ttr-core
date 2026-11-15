// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import org.tatrman.ttr.yaml.MAX_YAML_CODE_POINTS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.CanonicalYaml

class ProfileReview225Spec :
    FunSpec({
        val description = "- {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}"

        fun failure(
            source: String,
            ops: String,
            code: String,
            line: Int,
            column: Int,
            detail: String? = null,
        ) {
            val error = shouldThrow<ProfileException> { runOps(source, ops) }
            error.code shouldBe code
            error.location.line shouldBe line
            error.location.column shouldBe column
            if (detail != null) error.detail shouldBe detail
        }
        test("ignores refuse records and containers of records without changing leaf or whole-file ignores") {
            val source = "entity: {Legacy: {popis: X, typo: y}, Live: {popis: Y}}"
            failure(
                source,
                entityOps + description + "\n- {op: ignore, at: /entity/Legacy}",
                "ys/profile-conflict",
                1,
                10,
                "ignore covers record entity.Legacy",
            )
            failure(
                source,
                entityOps + "- {op: ignore, at: /entity}",
                "ys/profile-conflict",
                1,
                1,
                "ignore covers record entity.Legacy",
            )
            listOf(
                "- {op: ignore, in: entity, at: ''}",
                "- {op: ignore, in: entity, files: ['*.yaml']}",
            ).forEach { ignore ->
                failure(source, entityOps + ignore, "ys/profile-conflict", 1, 10, "ignore covers record entity.Legacy")
            }
            failure(
                "entity: {A: {sloupce: {x: {}}}}",
                entityOps +
                    "- {op: kinds, in: entity, at: /sloupce, mapping: {a: attribute}}\n" +
                    "- {op: ignore, in: entity, at: /sloupce}",
                "ys/profile-conflict",
                1,
                14,
                "ignore covers record attribute.x",
            )
            runOps(source, entityOps + "- {op: ignore, files: ['*.yaml']}").documents shouldBe emptyList()
            runOps("entity: {A: {note: ignored}}", entityOps + "- {op: ignore, in: entity, at: /note}")
                .ignored.size shouldBe 1
        }
        test("legal escaped controls bridge to P2 bytes and NEL is preserved") {
            listOf(
                "\\x7f" to "\u007f",
                "\\x80" to "\u0080",
                "\\ufffe" to "\ufffe",
                "\\N" to "\u0085",
            ).forEach { (escape, decoded) ->
                val emitted = runOps("entity: {A: {popis: \"a${escape}b\"}}", entityOps + description)
                emitted.properties().string("description") shouldBe "a${decoded}b"
                val canonical =
                    "ttr: '0.22'\npackage: demo\nmodel: er\ndefs:\n" +
                        "- {kind: entity, id: A, properties: {description: \"a${escape}b\"}}\n"
                emitted.documents.single().write() shouldBe CanonicalYaml.write(CanonicalYaml.read(canonical))
            }
        }
        test("canonical errors use the deepest record path without reparsing the failed JSON bridge") {
            failure(
                "c:\n  - {n: ok}\n  - {n: ''}\n",
                "- {op: package, mapping: {}, default: demo}\n" +
                    "- {op: kinds, at: /c, id: /n, mapping: {e: entity}}",
                "ys/profile-invalid",
                3,
                5,
            )
            failure(
                "entity:\n  A:\n    cols:\n      - {n: ok}\n      - {n: ''}\n",
                entityOps + "- {op: kinds, in: entity, at: /cols, id: /n, mapping: {a: attribute}}",
                "ys/profile-invalid",
                5,
                9,
            )
        }
        test("generated documents over the code-point budget report their group at the first authored record") {
            val source =
                buildString {
                    append("entity:\n")
                    repeat(140_000) { append("  E$it: {}\n") }
                }
            failure(
                source,
                entityOps,
                "ys/profile-invalid",
                2,
                7,
                "Canonical document demo/er exceeds $MAX_YAML_CODE_POINTS code points",
            )
        }
        test("canonical identities use semantics concept kind while retaining package and model separation") {
            fun apply(
                sources: List<String>,
                ops: String,
            ) = ProfileEngine.apply(
                sources.mapIndexed { i, source -> ForeignTree.read(source, "f$i.yaml") },
                listOf(recordProfile(ops)),
            )
            val duplicate =
                shouldThrow<ProfileException> { apply(listOf("entity: {X: {}}", "entity: {X: {}}"), entityOps) }
            duplicate.code shouldBe "ys/profile-conflict"
            duplicate.location.file shouldBe "f1.yaml"
            duplicate.location.line shouldBe 1
            duplicate.location.column shouldBe 10
            duplicate.detail shouldBe "Repeated canonical definition demo.er.entity.X"
            val concepts =
                "- {op: package, mapping: {}, default: demo}\n" +
                    "- {op: kinds, at: /c, kind: /typ, id: /n, mapping: {a: kind, b: subkind}}"
            failure(
                "c: [{typ: a, n: X}, {typ: b, n: X}]",
                concepts,
                "ys/profile-conflict",
                1,
                21,
                "Repeated canonical definition demo.cn.concept.X",
            )
            runOps("c: [{typ: a, n: X}, {typ: a, n: Y}]", concepts).defs().size shouldBe 2
            apply(
                listOf("pkg: a\nentity: {X: {}}", "pkg: b\nentity: {X: {}}"),
                entityOps.replace("mapping: {}, default: demo", "at: /pkg, mapping: {a: a, b: b}"),
            ).documents.size shouldBe
                2
        }
        test("only a first mapping ttr header is consumed and never emitted") {
            val source = "entity: {Invoice: {popis: Faktura}}"
            runOps("ttr: {profile: demo-cs/1, grammar: '>=0.18'}\n$source", entityOps + description)
                .documents
                .single()
                .write() shouldBe runOps(source, entityOps + description).documents.single().write()
            failure(
                "$source\nttr: {profile: demo-cs/1}",
                entityOps + description,
                "ys/unknown-key",
                2,
                1,
                "Unconsumed key ttr",
            )
            failure("ttr: scalar\n$source", entityOps + description, "ys/unknown-key", 1, 1, "Unconsumed key ttr")
        }
        test("nested collection paths write only into children including the zero-child case") {
            val ops =
                entityOps + "- {op: kinds, in: entity, at: /cols, mapping: {a: attribute}}\n" +
                    "- {op: nest, in: entity, at: /typ, to: {kind: entity, path: [attributes, type]}}"
            runOps("entity:\n  Customer: {typ: string}", ops).properties().isEmpty() shouldBe true
            runOps("entity: {Customer: {cols: {A: {}}, typ: string}}", ops)
                .properties()
                .getValue("attributes")
                .jsonArray
                .single()
                .jsonObject
                .getValue("properties") shouldBe
                buildJsonObject { put("type", "string") }
        }
        test("package uniqueness and absence are located and duplicate ignores union across inheritance") {
            failure(
                "entity: {A: {}}",
                entityOps + "- {op: package, mapping: {}, default: demo}",
                "ys/profile-conflict",
                1,
                1,
                "Two package operations select the same file",
            )
            failure(
                "# comment\nentity: {A: {}}",
                "- {op: kinds, at: /entity, mapping: {e: entity}}",
                "ys/value-unmapped",
                1,
                1,
                "No package operation for one.yaml",
            )
            val ignore = "- {op: ignore, in: entity, at: /note, reason: Duplicate}"
            val result =
                ProfileEngine.apply(
                    listOf(ForeignTree.read("entity: {A: {note: x}}")),
                    listOf(recordProfile(entityOps + ignore), recordProfile(ignore)),
                )
            result.ignored.size shouldBe 1
            result.ignored.single().reason shouldBe "Duplicate"
        }
        test("missing string operands give useful diagnostics instead of null") {
            failure(
                "items: [{name: A}]",
                "- {op: kinds, at: /items, kind: /typ, id: /name, mapping: {a: entity}}",
                "ys/unknown-kind",
                1,
                9,
                "Unmapped record kind: no string at /typ",
            )
            failure(
                "entity: {A: {}}",
                entityOps.replace("mapping: {}, default: demo", "at: /pkg, mapping: {}"),
                "ys/value-unmapped",
                1,
                1,
                "Unmapped package key: no string at /pkg",
            )
        }
        test("self-nesting is refused before duplicate discovery and binding records cannot nest") {
            failure(
                "entity:\n  A: {popis: X}\n",
                entityOps +
                    "- {op: kinds, in: entity, at: '', mapping: {x: attribute}}",
                "ys/profile-conflict",
                2,
                3,
                "A record cannot nest inside itself",
            )
            failure(
                "entity:\n  A: {}\n",
                entityOps + "- {op: kinds, at: /entity, mapping: {x: entity}}",
                "ys/profile-conflict",
                2,
                3,
                "Two kinds operations select the same record",
            )
            failure(
                "entity:\n  A:\n    vazby: {x: {}}\n",
                entityOps +
                    "- {op: kinds, in: entity, at: /vazby, mapping: {v: er2db_attribute}}",
                "ys/profile-invalid",
                3,
                16,
                "er2db_attribute cannot nest unambiguously under entity",
            )
        }
    })
