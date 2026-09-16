// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.params

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ParameterBridgeSpec :
    StringSpec({

        "single parameter rewrites to one ? placeholder" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE id = {a}",
                    listOf(SqlParam("a", "int", 42)),
                )
            r.sql shouldBe "SELECT id FROM customers WHERE id = ?"
            r.parameterOrder shouldBe listOf("a")
            r.distinctNames shouldBe listOf("a")
        }

        "same name twice produces two ? but a single value entry (identity-by-name)" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE id = {a} OR alt_id = {a}",
                    listOf(SqlParam("a", "int", 42)),
                )
            r.sql shouldBe "SELECT id FROM customers WHERE id = ? OR alt_id = ?"
            r.parameterOrder shouldBe listOf("a", "a")
            r.distinctNames shouldBe listOf("a")
            r.values["a"]!!.value shouldBe 42
        }

        "two distinct names appear in left-to-right order" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM orders WHERE customer_id = {cid} AND total > {min}",
                    listOf(
                        SqlParam("cid", "int", 7),
                        SqlParam("min", "float", 100.0),
                    ),
                )
            r.parameterOrder shouldBe listOf("cid", "min")
            r.distinctNames shouldBe listOf("cid", "min")
        }

        "distinct names dedupe to first-appearance order" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "WHERE x = {a} OR y = {b} OR z = {a}",
                    listOf(SqlParam("a", "int", 1), SqlParam("b", "int", 2)),
                )
            r.parameterOrder shouldBe listOf("a", "b", "a")
            r.distinctNames shouldBe listOf("a", "b")
        }

        "unknown parameter reference fails fast" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    ParameterBridge.prepareSqlForCalcite(
                        "WHERE id = {missing}",
                        listOf(SqlParam("a", "int", 42)),
                    )
                }
            ex.message!!.contains("missing") shouldBe true
        }

        "no parameters → SQL passes through unchanged" {
            val r = ParameterBridge.prepareSqlForCalcite("SELECT id FROM customers", emptyList())
            r.sql shouldBe "SELECT id FROM customers"
            r.parameterOrder shouldBe emptyList()
        }

        "stray { without close brace passes through unchanged" {
            // Defensive: a literal "{" not followed by "}" must not be mis-parsed.
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT '{open' FROM customers",
                    emptyList(),
                )
            r.sql shouldBe "SELECT '{open' FROM customers"
        }

        // ---- TF-P4 (G C2; contracts §3.7, §6): placeholders inside string literals ----

        val q = SqlParam("q", "varchar", "x")
        val id = SqlParam("id", "int", 1)

        fun prepared(
            sql: String,
            vararg params: SqlParam,
            typed: Boolean = false,
        ) = ParameterBridge.prepareSqlForCalcite(sql, params.toList(), typed)

        "an exactly-quoted placeholder '{id}' loses its quotes" {
            val r = prepared("WHERE ('{id}' = '' OR s.IDSUBJEKT = '{id}')", id)
            r.sql shouldBe "WHERE (? = '' OR s.IDSUBJEKT = ?)"
            r.parameterOrder shouldBe listOf("id", "id")
            r.distinctNames shouldBe listOf("id")
        }

        "'%{q}%' becomes CONCAT('%', ?, '%')" {
            val r = prepared("WHERE s.SUBJ_NAZEV LIKE '%{q}%'", q)
            r.sql shouldBe "WHERE s.SUBJ_NAZEV LIKE CONCAT('%', ?, '%')"
            r.parameterOrder shouldBe listOf("q")
        }

        "a one-sided pattern keeps only its non-empty side" {
            prepared("WHERE KOD LIKE '{q}%'", q).sql shouldBe "WHERE KOD LIKE CONCAT(?, '%')"
            prepared("WHERE KOD LIKE '%{q}'", q).sql shouldBe "WHERE KOD LIKE CONCAT('%', ?)"
        }

        "two placeholders in one pattern literal become one CONCAT, in order" {
            val r = prepared("WHERE x LIKE '%{q}-{id}%'", q, id)
            r.sql shouldBe "WHERE x LIKE CONCAT('%', ?, '-', ?, '%')"
            r.parameterOrder shouldBe listOf("q", "id")
        }

        "a pattern literal keeps its escaped quotes and its N prefix" {
            prepared("WHERE x LIKE '%''{q}''%'", q).sql shouldBe "WHERE x LIKE CONCAT('%''', ?, '''%')"
            prepared("WHERE x LIKE N'%{q}%'", q).sql shouldBe "WHERE x LIKE CONCAT(N'%', ?, N'%')"
            prepared("WHERE x = N'{q}'", q).sql shouldBe "WHERE x = ?"
        }

        "Czech placeholder names are placeholders (the name grammar is Unicode)" {
            ParameterBridge.PLACEHOLDER.matches("{číslo_dokladu}") shouldBe true
            val cislo = SqlParam("číslo_dokladu", "varchar", "x")
            val nazev = SqlParam("název", "varchar", "x")
            val r = prepared("WHERE d = '{číslo_dokladu}' AND n LIKE '%{název}%'", cislo, nazev)
            r.sql shouldBe "WHERE d = ? AND n LIKE CONCAT('%', ?, '%')"
            r.parameterOrder shouldBe listOf("číslo_dokladu", "název")
        }

        "an undeclared {…} inside a literal is literal text (e.g. JSON)" {
            prepared("WHERE x = 'literal {json} text' AND y = {q}", q).sql shouldBe
                "WHERE x = 'literal {json} text' AND y = ?"
            prepared("WHERE x LIKE '%{json}%'", q).sql shouldBe "WHERE x LIKE '%{json}%'"
        }

        "a declared placeholder in a literal that is neither exact nor a pattern fails loudly" {
            val ex =
                shouldThrow<ParameterInStringLiteralException> {
                    prepared("WHERE x = 'prefix {q} suffix'", q)
                }
            ex.parameterName shouldBe "q"
            ex.message shouldBe
                "Parameter '{q}' is inside a SQL string literal; a parameter cannot be embedded in a quoted literal " +
                "(it would become the literal text, never a bound value). Concatenate instead, e.g. " +
                "LIKE CONCAT('%', {q}, '%')."
        }

        "two placeholders in a literal without % fail too (only exact or pattern literals are rewritten)" {
            shouldThrow<ParameterInStringLiteralException> { prepared("WHERE x = '{q}-{id}'", q, id) }
                .parameterName shouldBe "q"
        }

        "the in-literal guard is an IllegalArgumentException (existing catch blocks still hold)" {
            shouldThrow<IllegalArgumentException> { prepared("WHERE x = 'a {q} b'", q) }
        }

        "a quote inside a comment or a quoted identifier does not open a literal" {
            prepared("WHERE x = {q} -- don't\nAND y LIKE '%{q}%'", q).sql shouldBe
                "WHERE x = ? -- don't\nAND y LIKE CONCAT('%', ?, '%')"
            prepared("WHERE x = {q} /* it's */ AND \"o'k\" = '{q}'", q).sql shouldBe
                "WHERE x = ? /* it's */ AND \"o'k\" = ?"
        }

        "typed mode wraps the placeholder inside the CONCAT" {
            prepared("WHERE x LIKE '%{q}%'", q, typed = true).sql shouldBe
                "WHERE x LIKE CONCAT('%', CAST(? AS VARCHAR), '%')"
        }

        // ---- typed = true (CAST(? AS T)) mode ----

        "typed mode emits CAST(? AS VARCHAR) for a string parameter" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE name = {n}",
                    listOf(SqlParam("n", "text", "Alice")),
                    typed = true,
                )
            r.sql shouldBe "SELECT id FROM customers WHERE name = CAST(? AS VARCHAR)"
        }

        "typed mode keeps the same ? count and order as bare mode (a CAST adds no extra ?)" {
            val sql = "SELECT id FROM orders WHERE customer_id = {cid} AND total > {min}"
            val params = listOf(SqlParam("cid", "int", 7), SqlParam("min", "float", 100.0))

            val bare = ParameterBridge.prepareSqlForCalcite(sql, params, typed = false)
            val typed = ParameterBridge.prepareSqlForCalcite(sql, params, typed = true)

            // Order is identical in both modes.
            typed.parameterOrder shouldBe bare.parameterOrder
            typed.parameterOrder shouldBe listOf("cid", "min")
            // Same number of placeholders — a CAST wraps a single ?, never adds one.
            typed.sql.count { it == '?' } shouldBe bare.sql.count { it == '?' }
        }

        "typed mode renders DECIMAL with explicit precision and scale (not bare DECIMAL)" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM orders WHERE rate = {r}",
                    listOf(SqlParam("r", "decimal", "1.5")),
                    typed = true,
                )
            r.sql shouldBe "SELECT id FROM orders WHERE rate = CAST(? AS DECIMAL(38, 10))"
        }

        "typed mode falls back to VARCHAR for an unknown surface type" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE blob = {b}",
                    listOf(SqlParam("b", "geography", "x")),
                    typed = true,
                )
            r.sql shouldBe "SELECT id FROM customers WHERE blob = CAST(? AS VARCHAR)"
        }
    })
