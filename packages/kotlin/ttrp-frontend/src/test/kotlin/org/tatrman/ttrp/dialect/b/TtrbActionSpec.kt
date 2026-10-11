// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ChainElem
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue

/**
 * B4 — action, count, attach and recipient sentences. Each action sentence lowers to
 * `<port> = <current> -> calc { <the schema's columns> } -> select(<them>)` and reports an action
 * output (port, display kind) the program routes to `display(<kind>)`; count / attach cross-join a
 * one-row table into the current row. The Czech and English twins lower identically.
 */
class TtrbActionSpec :
    StringSpec({
        fun e(x: Expression): String =
            when (x) {
                is ColumnRef -> "col(${x.port?.plus(".") ?: ""}${x.column})"
                is Literal ->
                    when (val v = x.value) {
                        is LiteralValue.Str -> "\"${v.value}\""
                        is LiteralValue.Num -> v.raw
                        else -> v.toString()
                    }
                is FunctionCall -> "${x.function.value}(${x.args.joinToString(",") { e(it) }})"
                is AggregateCall -> "${x.function.value}(${x.args.joinToString(",") { e(it) }})"
                else -> x.toString()
            }

        fun ops(elems: List<ChainElem>): List<String> =
            elems.map { if (it is OpCall) it.name else "ref:" + (it as DottedRef).parts.joinToString(".") }

        fun calc(stmt: Assignment): List<String> =
            (stmt.chain.elements.single { it is OpCall && it.name == "calc" } as OpCall)
                .config!!
                .entries
                .filterIsInstance<AssignEntry>()
                .map { "${it.name}=${e(it.value)}" }

        fun selected(stmt: Assignment): List<String> =
            (stmt.chain.elements.single { it is OpCall && it.name == "select" } as OpCall)
                .args
                .map { ((it.value as ExprArg).expr as ColumnRef).column }

        fun byTarget(
            stmts: List<Statement>,
            target: String,
        ): Assignment = stmts.filterIsInstance<Assignment>().single { it.target == target }

        val en = TtrbCorpus.decompose("actions/actions.ttrb")
        val cs = TtrbCorpus.decompose("actions/actions.ttrb-cs")

        "the action fixtures decompose clean in both skins" {
            en.diagnostics.shouldBeEmpty()
            cs.diagnostics.shouldBeEmpty()
        }

        "the Czech action sentences lower exactly as their English twins (incl. the action outputs)" {
            TtrbCorpus.canonical(cs.statements) shouldBe TtrbCorpus.canonical(en.statements)
            cs.actionOutputs.map { it.port to it.display } shouldBe en.actionOutputs.map { it.port to it.display }
        }

        "every action is an output routed to display(<kind>); a repeated kind gets `<kind>_2`, `_3`, …" {
            en.actionOutputs.map { it.port to it.display } shouldContainExactly
                listOf(
                    "send_email" to "send_email",
                    "send_email_2" to "send_email",
                    "send_email_3" to "send_email",
                    "update_field" to "update_field",
                    "update_field_2" to "update_field",
                    "manual_task" to "manual_task",
                    "manual_task_2" to "manual_task",
                )
        }

        "count: T -> calc { x = 1 } -> aggregate { x = count(x) }, cross-joined into the current row" {
            val stmts = en.statements.filterIsInstance<Assignment>()
            val counted = stmts[1]
            ops(counted.chain.elements) shouldContainExactly listOf("load", "calc", "aggregate")
            calc(counted) shouldContainExactly listOf("počet_reklamací=1")
            val agg =
                (counted.chain.elements[2] as OpCall)
                    .config!!
                    .entries
                    .filterIsInstance<AssignEntry>()
                    .single()
            "${agg.name}=${e(agg.value)}" shouldBe "počet_reklamací=agg.count(col(počet_reklamací))"
            val joined = stmts[2]
            val join = joined.chain.elements.single() as OpCall
            join.name shouldBe "join"
            join.args.associate { it.name to e((it.value as ExprArg).expr) } shouldBe
                mapOf("left" to "col(objednávky)", "right" to "col(${counted.target})", "type" to "col(cross)")
        }

        "both count forms (`Count the rows of T as x` / `Count x as the number of rows of T`) lower alike" {
            TtrbCorpus.canonical(TtrbCorpus.decompose("actions/count-forms.ttrb").statements) shouldBe
                TtrbCorpus.canonical(TtrbCorpus.decompose("actions/count-forms-alt.ttrb").statements)
        }

        "e-mail: recipient column, subject, template, key, attachments joined with `;`" {
            val s = byTarget(en.statements, "send_email")
            calc(s) shouldContainExactly
                listOf(
                    "komu=col(email_zástupce)",
                    "předmět=\"Velká objednávka\"",
                    "šablona=\"velka_objednavka\"",
                    "klíč=col(číslo)",
                    "příloha=\"faktura;dodací_list\"",
                )
            selected(s) shouldContainExactly listOf("komu", "předmět", "šablona", "klíč", "příloha")
        }

        "e-mail to a department: the text literal `oddělení:<x>`; no attachments ⇒ no `příloha` column" {
            val s = byTarget(en.statements, "send_email_2")
            calc(s) shouldContainExactly
                listOf("komu=\"oddělení:obchod\"", "předmět=\"Kontrola\"", "šablona=\"kontrola\"", "klíč=col(číslo)")
            selected(s) shouldContainExactly listOf("komu", "předmět", "šablona", "klíč")
        }

        "a fallback recipient is coalesce(<column>, \"oddělení:<x>\"); a single attachment is one name" {
            val s = byTarget(en.statements, "send_email_3")
            calc(s).first() shouldBe "komu=fn.coalesce(col(email_zástupce),\"oddělení:obchod\")"
            calc(s).last() shouldBe "příloha=\"faktura\""
        }

        "set: entity and attribute are names emitted as text; key / value a column or a literal" {
            calc(byTarget(en.statements, "update_field")) shouldContainExactly
                listOf(
                    "entita=\"objednávky\"",
                    "klíč=col(číslo)",
                    "atribut=\"stav\"",
                    "hodnota=\"uvolněná\"",
                    "důvod=\"zaplaceno\"",
                )
            calc(byTarget(en.statements, "update_field_2")) shouldContainExactly
                listOf(
                    "entita=\"objednávky\"",
                    "klíč=42",
                    "atribut=\"poznámka\"",
                    "hodnota=col(částka)",
                    "důvod=\"kopie částky\"",
                )
        }

        "manual task: řešitel, název, popis (+ příloha when given)" {
            calc(byTarget(en.statements, "manual_task")) shouldContainExactly
                listOf(
                    "řešitel=fn.coalesce(col(vedoucí),\"oddělení:sklad\")",
                    "název=\"Zkontrolovat\"",
                    "popis=\"Zkontrolovat objednávku.\"",
                    "příloha=\"faktura\"",
                )
            selected(byTarget(en.statements, "manual_task_2")) shouldContainExactly listOf("řešitel", "název", "popis")
        }

        "actions are sinks: every action reads the SAME current value (the count's join)" {
            val joined = en.statements.filterIsInstance<Assignment>()[2].target
            for (port in en.actionOutputs.map { it.port }) {
                ops(byTarget(en.statements, port).chain.elements) shouldContainExactly
                    listOf("ref:$joined", "calc", "select")
            }
        }

        "attach: an unbound table is loaded, then cross-joined into the current row" {
            val stmts = en.statements.filterIsInstance<Assignment>()
            val load = stmts[stmts.size - 2]
            ops(load.chain.elements) shouldContainExactly listOf("load")
            val join =
                stmts
                    .last()
                    .chain.elements
                    .single() as OpCall
            join.args.associate { it.name to e((it.value as ExprArg).expr) } shouldBe
                mapOf("left" to "col(${stmts[2].target})", "right" to "col(${load.target})", "type" to "col(cross)")
        }

        "an action inside a block reads the block's output; the next sentence stays on the block" {
            val src =
                "Load objednávky.\n" +
                    "If částka is more than 1000:\n" +
                    "    Send an e-mail to department \"obchod\" with subject \"S\", template \"t\" and key číslo.\n" +
                    "    Set stav of objednávky with key číslo to \"velká\" with reason \"částka\".\n"
            val d = TtrB.decompose(src, org.tatrman.ttrp.ast.SourceLocation.UNKNOWN, null)
            d.diagnostics.shouldBeEmpty()
            val block = d.statements.filterIsInstance<Assignment>().single { ops(it.chain.elements).last() == "filter" }
            ops(byTarget(d.statements, "send_email").chain.elements).first() shouldBe "ref:${block.target}"
            ops(byTarget(d.statements, "update_field").chain.elements).first() shouldBe "ref:${block.target}"
        }

        "new English keywords stay usable as column names (soft keywords)" {
            val d = TtrbCorpus.decompose("actions/soft-keywords.ttrb")
            d.diagnostics.shouldBeEmpty()
            val project =
                d.statements
                    .filterIsInstance<org.tatrman.ttrp.ast.ChainStmt>()
                    .single()
                    .chain.elements[1] as OpCall
            project.args.map { e((it.value as ExprArg).expr) } shouldContainExactly
                listOf("col(key)", "col(subject)", "col(email)", "col(description)", "col(reason)", "col(count)")
        }
    })
