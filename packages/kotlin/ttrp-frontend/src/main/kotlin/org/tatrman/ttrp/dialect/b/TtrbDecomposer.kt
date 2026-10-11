// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.Chain
import org.tatrman.ttrp.ast.ChainElem
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ConfigBlock
import org.tatrman.ttrp.ast.ConfigEntry
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.GroupByEntry
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.dialect.sql.TtrSqlLoc
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.expr.catalog.FunctionCatalog
import org.tatrman.ttrp.expr.catalog.FunctionKind
import org.tatrman.ttrp.parser.generated.TTRBParser as P

/**
 * Sentence→node decomposition (C2-a-β): lowers a TTR-B sentence program into canonical
 * TTR-P statements — the SAME AST canonical authoring emits, so `bare ≡ embedded ≡
 * canonical` graphs hold (the P6 KEY-GATE pattern). Each sentence maps to node(s) of the
 * standard set (C4-b "Maps to" column); anaphora (`that`/`this`/`it` + the implicit
 * subject) = the previous sentence's out (C4-b-i, deterministic, P2); `as <name>` binds
 * an SSA label (Q7-γ) → CTE names (E-b) / ζ keys. Statement order = pipeline order.
 */
class TtrbDecomposer(
    private val loc: TtrSqlLoc,
    private val catalog: FunctionCatalog,
    private val skin: TtrbSkin = TtrbSkin.EN,
    /** The container's IN ports: names, never loaded (a port is no model object). */
    private val inPorts: Set<String> = emptySet(),
    /** The container's declared OUT ports; null when unknown (a bare / corpus fragment — no check). */
    private val outPorts: Set<String>? = null,
) {
    private val exprFolder = TtrbExpr(loc, catalog, skin)
    private val diags = mutableListOf<org.tatrman.ttrp.diagnostics.TtrpDiagnostic>()
    private val out = mutableListOf<Statement>()
    private val elems = mutableListOf<ChainElem>()
    private var curName: String? = null
    private val bound = mutableSetOf<String>()
    private val derived = LinkedHashSet<String>()
    private var synth = 0
    private val actions = mutableListOf<org.tatrman.ttrp.ast.ActionOutput>()
    private val portCounts = HashMap<String, Int>()
    private val outputs = LinkedHashSet<String>()

    init {
        bound += inPorts
    }

    data class Result(
        val statements: List<Statement>,
        val derivedInPorts: List<String>,
        val diagnostics: List<org.tatrman.ttrp.diagnostics.TtrpDiagnostic> = emptyList(),
        val actionOutputs: List<org.tatrman.ttrp.ast.ActionOutput> = emptyList(),
        val outputPorts: List<String> = emptyList(),
    )

    fun decompose(
        program: P.FragmentProgramContext,
        outPort: String?,
    ): Result {
        for (item in program.item()) item(item, depth = 0)
        // A pipeline not terminated by Show/Store is the container's default out (C4-b-iv).
        if (elems.isNotEmpty()) {
            val at = elems.first().location
            val last = elems.last()
            if (outPort != null) {
                out += assign(outPort, Chain(elems.toList(), at), at)
            } else {
                val trailing = ChainStmt(Chain(elems.toList(), at), at)
                out += trailing
                originOf[trailing] = elemOrigin[last]
                // With the container's ports known and no port to carry it, the trailing value is lost.
                if (outPorts != null && (outPorts.isNotEmpty() || inPorts.isNotEmpty())) dangling += trailing
            }
            elems.clear()
        }
        if (diags.isEmpty()) diags += unusedResults(outPort)
        return Result(out, derived.toList(), diags.toList(), actions.toList(), outputs.toList())
    }

    private fun item(
        item: P.ItemContext,
        depth: Int,
    ) {
        item.sentence()?.let { sentence ->
            val mark = out.size
            origin = Origin(loc.of(sentence.statement()), sentence.start.text)
            statement(sentence.statement())
            tagOrigins(mark)
        }
        item.block()?.let { block(it, depth) }
    }

    /**
     * `If <pred>:` + an indented block (B3) — ONE output: `<block> = <current> -> filter(<pred>)`, the
     * block's sentences running on it. The value the block filters is the main line's current value,
     * bound to a name first; after the block the main line resumes on that SAME value, so a second
     * block filters it again — blocks overlap, they are not else-if. A block inside a block is
     * TTRP-B-110 (one level only: a nested condition is `and` in one header).
     */
    private fun block(
        ctx: P.BlockContext,
        depth: Int,
    ) {
        if (depth > 0) {
            diags += reject("TTRP-B-110", TtrpDiagnosticId.B_110, span(ctx.IF().symbol, ctx.COLON().symbol))
            return
        }
        val header = span(ctx.IF().symbol, ctx.COLON().symbol)
        origin = Origin(header, ctx.IF().text)
        val mark = out.size
        val base = materialize(header)
        val at = loc.of(ctx.boolExpr())
        val pred = exprFolder.foldBool(ctx.boolExpr())
        val name = synthName()
        val filter = OpCall("filter", listOf(namedArg(null, pred, at)), null, at)
        out += assign(name, Chain(listOf(DottedRef(listOf(base), at), filter), at), at)
        tagOrigins(mark)
        bound += name
        curName = name
        for (inner in ctx.item()) item(inner, depth + 1)
        flushDangling()
        curName = base
    }

    private fun span(
        from: org.antlr.v4.runtime.Token,
        to: org.antlr.v4.runtime.Token,
    ): SourceLocation {
        val a = loc.of(from)
        val b = loc.of(to)
        return SourceLocation(a.file, a.line, a.column, b.endLine, b.endColumn, a.offsetStart, b.offsetEnd)
    }

    private fun reject(
        id: String,
        diagId: TtrpDiagnosticId,
        at: SourceLocation,
        word: String? = null,
    ): TtrpDiagnostic {
        val entry = TtrB.rejects(skin).entry(id)
        return TtrpDiagnostic(diagId, Severity.ERROR, entry.message(word), at, entry.suggest)
    }

    /**
     * `Call that <name>.` / `Pojmenuj to jako <jméno>.` (B8) — the current value gets [name]: a pending chain is
     * assigned to it, else the name refers to the current value. The current value stays the same, now named.
     */
    private fun nameIt(ctx: P.NameStmtContext) {
        val name = ctx.name.text
        val at = loc.of(ctx)
        if (elems.isNotEmpty()) {
            out += assign(name, Chain(elems.toList(), elems.first().location), at)
            elems.clear()
        } else {
            out += assign(name, Chain(listOf(DottedRef(listOf(currentRef(at)), at)), at), at)
        }
        curName = name
        bound += name
    }

    /**
     * `Send that to output <port>.` / `Pošli to na výstup <port>.` (B7) — the declared OUT port carries the
     * current value (a pending chain is bound to it, else it names the current value) or the named one;
     * the port then is the current value. An undeclared port is TTRP-B-112.
     */
    private fun output(ctx: P.OutputStmtContext) {
        val port = ctx.port.text
        val at = loc.of(ctx)
        if (outPorts != null && port !in outPorts) {
            diags += reject("TTRP-B-112", TtrpDiagnosticId.B_112, loc.of(ctx.port), port)
            return
        }
        val named = ctx.outputSource()?.qname()?.text
        when {
            named != null -> {
                flushDangling()
                noteExternal(named)
                out += assign(port, Chain(listOf(DottedRef(listOf(named), at)), at), at)
            }
            elems.isNotEmpty() -> {
                out += assign(port, Chain(elems.toList(), elems.first().location), at)
                elems.clear()
            }
            else -> out += assign(port, Chain(listOf(DottedRef(listOf(currentRef(at)), at)), at), at)
        }
        curName = port
        bound += port
        outputs += port
    }

    private fun statement(s: P.StatementContext) {
        when (s) {
            is P.LoadSentenceContext -> bindLoad(s.loadStmt())
            is P.JoinSentenceContext -> bindJoin(s.joinStmt())
            is P.KeepColumnsSentenceContext -> append(projectOf(s.keepColumnsStmt()))
            is P.KeepExceptSentenceContext -> append(exceptOf(s.keepExceptStmt()))
            is P.FilterSentenceContext -> append(filterOf(s.filterStmt()))
            is P.MatchSentenceContext -> match(s.matchStmt())
            is P.RenameSentenceContext -> append(renameOf(s.renameStmt()))
            is P.ConvertSentenceContext -> append(convertOf(s.convertStmt()))
            is P.ComputeSentenceContext -> append(computeOf(s.computeStmt()))
            is P.ConditionalSentenceContext -> append(conditionalOf(s.conditionalStmt()))
            is P.SummarizeSentenceContext -> append(summarizeOf(s.summarizeStmt()))
            is P.SortSentenceContext -> append(sortOf(s.sortStmt()))
            is P.LimitSentenceContext -> append(limitOf(s.limitStmt()))
            is P.CombineSentenceContext -> append(combineOf(s.combineStmt()))
            is P.StoreSentenceContext -> sinkStore(s.storeStmt())
            is P.ShowSentenceContext -> sinkShow(s.showStmt())
            is P.CountSentenceContext -> countRows(s.countStmt())
            is P.AttachSentenceContext -> attach(s.attachStmt())
            is P.EmailSentenceContext -> email(s.emailStmt())
            is P.SetFieldSentenceContext -> setField(s.setFieldStmt())
            is P.TaskSentenceContext -> task(s.taskStmt())
            is P.OutputSentenceContext -> output(s.outputStmt())
            is P.NameSentenceContext -> nameIt(s.nameStmt())
            else -> error("unhandled sentence: ${s::class.simpleName}")
        }
    }

    // ---- bindings (introduce a named source) ---------------------------------------

    private fun bindLoad(ctx: P.LoadStmtContext) {
        flushDangling()
        val at = loc.of(ctx)
        val load: OpCall
        val name: String
        when (ctx) {
            is P.LoadFileContext -> {
                val path = unquote(ctx.fileSource().str().text)
                val args = mutableListOf(arg(null, ColumnRef(null, path, at), at))
                ctx.schema?.let { args += arg("schema", qref(it.text, at), at) }
                load = OpCall("load", args, null, at)
                name = ctx.name?.text ?: synthName()
            }
            is P.LoadModelContext -> {
                val src = ctx.source.text
                // A container IN port is a name, never `load(<port>)` (B7): `Load orders.` reads the
                // port; `Load orders as o.` names it `o` (a reference — no node).
                if (ctx.schema == null && src in bound) {
                    val name = ctx.name?.text ?: src
                    if (name != src) out += assign(name, Chain(listOf(DottedRef(listOf(src), at)), at), at)
                    curName = name
                    bound += name
                    return
                }
                val args = mutableListOf(arg(null, qref(src, at), at))
                ctx.schema?.let { args += arg("schema", qref(it.text, at), at) }
                // A schema'd model load (`Load from files.sales_2026 with schema sales_csv`) is a
                // concrete storage-dataset read, like a file load — NOT an external in-port. Only a
                // bare `Load <name>` (no schema) is a derived in-port the wrapper synthesis wires up.
                if (ctx.schema == null) noteExternal(src)
                load = OpCall("load", args, null, at)
                name = ctx.name?.text ?: src.substringAfterLast('.')
            }
            else -> error("unhandled load: ${ctx::class.simpleName}")
        }
        out += assign(name, Chain(listOf(load), at), at)
        curName = name
        bound += name
    }

    private fun bindJoin(ctx: P.JoinStmtContext) {
        // `that` names the CURRENT value — a pending transform chain is bound first, never dropped.
        val leftName = if (ctx.joinLeft().refWord() != null) materialize(loc.of(ctx)) else joinLeftName(ctx.joinLeft())
        flushDangling()
        val at = loc.of(ctx)
        val rightName = ctx.right.text
        val ap = mapOf(leftName to "left", rightName to "right")
        // `optionally` / `volitelně` (B7): a left join — the unmatched right columns are NULL.
        val type = if (ctx.OPTIONALLY() != null) "left" else "inner"
        val join =
            OpCall(
                "join",
                listOf(
                    refArg("left", leftName, at),
                    refArg("right", rightName, at),
                    joinOn(ctx.joinCond(), ap, at),
                    refArg("type", type, at),
                ),
                null,
                at,
            )
        noteExternal(rightName)
        val name = ctx.name?.text ?: synthName()
        out += assign(name, Chain(listOf(join), at), at)
        curName = name
        bound += name
    }

    /** A join condition: an expression over the `left` / `right` ports, or a modelled relation (`on: relation r`, B7). */
    private fun joinOn(
        cond: P.JoinCondContext,
        ap: Map<String, String>,
        at: SourceLocation,
    ): Arg {
        val rel = cond.rel ?: return namedArg("on", exprFolder.foldBool(cond.boolExpr(), ap), at)
        val qname =
            org.tatrman.ttrp.ast
                .Qname(rel.ident().map { it.text }, loc.of(rel))
        return Arg(
            "on",
            org.tatrman.ttrp.ast
                .RelationArg(qname, loc.of(cond)),
            at,
        )
    }

    /**
     * `Keep only the rows that have [no] match in <t> on <cond>.` / `Ponech jen řádky, které [ne]mají protějšek
     * v <t> přes <podmínka>.` (B7) — `join(left: <current>, right: t, on: cond, type: semi | anti)`, the next
     * current value. `Remove … that have a match` is the anti join (and `… have no match` the semi).
     */
    private fun match(ctx: P.MatchStmtContext) {
        val (hasMatch, right, cond, remove) =
            when (ctx) {
                is P.KeepMatchContext -> Quad(ctx.MATCH_IN() != null, ctx.right, ctx.joinCond(), false)
                is P.RemoveMatchContext -> Quad(ctx.MATCH_IN() != null, ctx.right, ctx.joinCond(), true)
                else -> error("unhandled match: ${ctx::class.simpleName}")
            }
        val at = loc.of(ctx)
        val base = materialize(at)
        val rightName = right.text
        noteExternal(rightName)
        val type = if (hasMatch != remove) "semi" else "anti"
        val join =
            OpCall(
                "join",
                listOf(
                    refArg("left", base, at),
                    refArg("right", rightName, at),
                    joinOn(cond, mapOf(base to "left", rightName to "right"), at),
                    refArg("type", type, at),
                ),
                null,
                at,
            )
        val name = synthName()
        out += assign(name, Chain(listOf(join), at), at)
        bound += name
        curName = name
    }

    private data class Quad<A, B, C, D>(
        val a: A,
        val b: B,
        val c: C,
        val d: D,
    )

    private fun joinLeftName(ctx: P.JoinLeftContext): String =
        if (ctx.refWord() != null) currentRef(loc.of(ctx)) else ctx.qname().text.also { noteExternal(it) }

    // ---- transforms (extend the anaphoric chain) -----------------------------------

    private fun projectOf(ctx: P.KeepColumnsStmtContext): ChainElem {
        val at = loc.of(ctx)
        val cols = ctx.colRenameList().colRename().map { ColumnRef(null, it.ident(0).text, at) as Expression }
        return OpCall("project", cols.map { namedArg(null, it, at) }, null, at)
    }

    /** `Keep all columns except a` — negative Select; schema expansion is deferred (C2-b-iii β). */
    private fun exceptOf(ctx: P.KeepExceptStmtContext): ChainElem {
        val at = loc.of(ctx)
        val cols = ctx.colList().ident().map { ColumnRef(null, it.text, at) as Expression }
        return OpCall("project", cols.map { namedArg(null, it, at) }, null, at)
    }

    private fun filterOf(ctx: P.FilterStmtContext): ChainElem {
        val at = loc.of(ctx)
        val (boolCtx, negate) =
            when (ctx) {
                is P.KeepFilterContext -> ctx.boolExpr() to false
                is P.RemoveFilterContext -> ctx.boolExpr() to true
                else -> error("unhandled filter: ${ctx::class.simpleName}")
            }
        val pred = exprFolder.foldBool(boolCtx)
        val eff = if (negate) FunctionCall(CatalogId.NOT, listOf(pred), at) else pred
        return OpCall("filter", listOf(namedArg(null, eff, at)), null, at)
    }

    private fun renameOf(ctx: P.RenameStmtContext): ChainElem {
        val at = loc.of(ctx)
        val entries: List<ConfigEntry> =
            ctx.renamePair().map { AssignEntry(it.to.text, ColumnRef(null, it.from.text, at), loc.of(it)) }
        return OpCall("calc", emptyList(), ConfigBlock(entries, at), at)
    }

    private fun convertOf(ctx: P.ConvertStmtContext): ChainElem {
        val at = loc.of(ctx)
        val col = ctx.col.text
        val type = TtrpType.parse(ctx.typeName().ident().text)
        val entry: ConfigEntry = AssignEntry(col, Cast(ColumnRef(null, col, at), type, at), at)
        return OpCall("calc", emptyList(), ConfigBlock(listOf(entry), at), at)
    }

    private fun computeOf(ctx: P.ComputeStmtContext): ChainElem {
        val at = loc.of(ctx)
        val entry: ConfigEntry = AssignEntry(ctx.name.text, exprFolder.foldExpr(ctx.expr()), at)
        return OpCall("calc", emptyList(), ConfigBlock(listOf(entry), at), at)
    }

    /** `Compute x as 1 when p, otherwise 2.` → `calc { x = case when p then 1 else 2 end }` (B7). */
    private fun conditionalOf(ctx: P.ConditionalStmtContext): ChainElem {
        val at = loc.of(ctx)
        val branches = ctx.whenArm().map { exprFolder.foldBool(it.cond) to exprFolder.foldExpr(it.value) }
        val value =
            org.tatrman.ttrp.expr
                .CaseWhen(branches, ctx.otherwise?.let { exprFolder.foldExpr(it) }, at)
        val entry: ConfigEntry = AssignEntry(ctx.name.text, value, at)
        return OpCall("calc", emptyList(), ConfigBlock(listOf(entry), at), at)
    }

    private fun summarizeOf(ctx: P.SummarizeStmtContext): ChainElem {
        val at = loc.of(ctx)
        val entries = mutableListOf<ConfigEntry>()
        val keys = ctx.groupKey().map { it.text }
        if (keys.isNotEmpty()) entries += GroupByEntry(keys, at)
        for (item in ctx.aggItem()) {
            val func = aggFuncName(item.func)
            val agg = aggCall(func, item.arg.text, loc.of(item))
            val name = item.name?.text ?: func
            entries += AssignEntry(name, agg, loc.of(item))
        }
        return OpCall("aggregate", emptyList(), ConfigBlock(entries, at), at)
    }

    /** The catalogue aggregate an `aggFunc` names — a skin alias (`součet` → `sum`) resolved. */
    private fun aggFuncName(ctx: P.AggFuncContext): String = skin.function(ctx.text)

    /** `sum of amount` → `AggregateCall(agg.sum, [col(amount)])` — same id canonical `sum(amount)` folds to. */
    private fun aggCall(
        func: String,
        col: String,
        at: SourceLocation,
    ): Expression {
        val name = func.lowercase()
        val id = catalog.resolve(name).firstOrNull { it.kind == FunctionKind.AGGREGATE }?.id ?: CatalogId(name)
        return AggregateCall(id, listOf(ColumnRef(null, col, at)), distinct = false, location = at)
    }

    private fun sortOf(ctx: P.SortStmtContext): ChainElem {
        val at = loc.of(ctx)
        val keys = ctx.sortKey().map { ColumnRef(null, it.col.text, at) as Expression }
        return OpCall("sort", keys.map { namedArg(null, it, at) }, null, at)
    }

    private fun limitOf(ctx: P.LimitStmtContext): ChainElem {
        val at = loc.of(ctx)
        return OpCall("limit", listOf(namedArg(null, Literal(LiteralValue.Num(ctx.count.text), at), at)), null, at)
    }

    private fun combineOf(ctx: P.CombineStmtContext): ChainElem {
        val at = loc.of(ctx)
        // Left is the chain receiver (the current pipeline value); right is the other input.
        if (ctx.joinLeft().refWord() == null) noteExternal(ctx.joinLeft().qname().text)
        val right = ctx.right.text
        noteExternal(right)
        return OpCall("union", listOf(refArg(null, right, at)), null, at)
    }

    // ---- count / attach (B4) ---------------------------------------------------------

    /**
     * `Count the rows of T as x.` / `Spočítej x jako počet řádků T.` → `_c = T -> calc { x = 1 } ->
     * aggregate { x = count(x) }` (a never-null column counts every row — the emitters need a column
     * argument, `count()` alone does not build), then `join(left: <current>, right: _c, type: cross)`
     * becomes the current value. Each op keeps its own sub-span (source / name / row word / verb).
     */
    private fun countRows(ctx: P.CountStmtContext) {
        val (nameCtx, sourceCtx, rowCtx) =
            when (ctx) {
                is P.CountRowsAsContext -> Triple(ctx.name, ctx.source, ctx.rowWord())
                is P.CountAsNumberContext -> Triple(ctx.name, ctx.source, ctx.rowWord())
                else -> error("unhandled count: ${ctx::class.simpleName}")
            }
        val verb = loc.of(ctx.start)
        val base = materialize(verb)
        val x = nameCtx.text
        val nameAt = loc.of(nameCtx)
        val aggAt = loc.of(rowCtx)
        val calc =
            OpCall(
                "calc",
                emptyList(),
                ConfigBlock(listOf(AssignEntry(x, Literal(LiteralValue.Num("1"), nameAt), nameAt)), nameAt),
                nameAt,
            )
        val count =
            AggregateCall(aggregateId("count"), listOf(ColumnRef(null, x, nameAt)), distinct = false, location = nameAt)
        val agg = OpCall("aggregate", emptyList(), ConfigBlock(listOf(AssignEntry(x, count, nameAt)), aggAt), aggAt)
        val srcAt = loc.of(sourceCtx)
        val counted = synthName()
        out += assign(counted, Chain(tableHead(sourceCtx) + calc + agg, srcAt), srcAt)
        bound += counted
        crossJoin(base, counted, verb)
    }

    /** `Attach T to the result.` / `Připoj T k výsledku.` → `join(left: <current>, right: T, type: cross)`. */
    private fun attach(ctx: P.AttachStmtContext) {
        val verb = loc.of(ctx.ATTACH().symbol)
        val base = materialize(verb)
        val table = ctx.source.text
        val right =
            if (table in bound) {
                table
            } else {
                val at = loc.of(ctx.source)
                val name = synthName()
                out += assign(name, Chain(tableHead(ctx.source), at), at)
                bound += name
                name
            }
        crossJoin(base, right, verb)
    }

    /** A table a count / attach reads: a bound name is referenced; anything else is loaded (a derived in-port when bare). */
    private fun tableHead(q: P.QnameContext): List<ChainElem> {
        val name = q.text
        val at = loc.of(q)
        if (name in bound) return listOf(DottedRef(listOf(name), at))
        noteExternal(name)
        return listOf(OpCall("load", listOf(arg(null, qref(name, at), at)), null, at))
    }

    private fun crossJoin(
        left: String,
        right: String,
        at: SourceLocation,
    ) {
        val join =
            OpCall(
                "join",
                listOf(refArg("left", left, at), refArg("right", right, at), refArg("type", "cross", at)),
                null,
                at,
            )
        val name = synthName()
        out += assign(name, Chain(listOf(join), at), at)
        bound += name
        curName = name
    }

    // ---- actions (B4) ----------------------------------------------------------------

    /** `Send an e-mail to <recipient> with subject "s", template "t", key <k> [and attachments …].` */
    private fun email(ctx: P.EmailStmtContext) {
        val cols =
            mutableListOf(
                "komu" to recipient(ctx.recipient()),
                "předmět" to text(ctx.subject),
                "šablona" to text(ctx.template),
                "klíč" to exprFolder.foldExpr(ctx.key),
            )
        ctx.attachments()?.let { cols += "příloha" to attachments(it) }
        action(TtrbActions.SEND_EMAIL, loc.of(ctx.SEND().symbol), loc.of(ctx), cols)
    }

    /** `Set <attribute> of <entity> with key <k> to <v> with reason "r".` — entity / attribute are NAMES (text). */
    private fun setField(ctx: P.SetFieldStmtContext) {
        val cols =
            listOf(
                "entita" to name(ctx.entity),
                "klíč" to exprFolder.foldExpr(ctx.key),
                "atribut" to name(ctx.attribute),
                "hodnota" to exprFolder.foldExpr(ctx.value),
                "důvod" to text(ctx.reason),
            )
        action(TtrbActions.UPDATE_FIELD, loc.of(ctx.SET().symbol), loc.of(ctx), cols)
    }

    /** `Create a manual task for <recipient> "title" with description "d" [and attachment …].` */
    private fun task(ctx: P.TaskStmtContext) {
        val cols =
            mutableListOf(
                "řešitel" to recipient(ctx.recipient()),
                "název" to text(ctx.title),
                "popis" to text(ctx.description),
            )
        ctx.attachments()?.let { cols += "příloha" to attachments(it) }
        action(TtrbActions.MANUAL_TASK, loc.of(ctx.CREATE().symbol), loc.of(ctx), cols)
    }

    /**
     * An action is a SINK: `<port> = <current> -> calc { <columns> } -> select(<columns>)`, reported as an
     * action output the checker routes to `display(<kind>)`. The current value stays as it was, so the next
     * sentence (another action, too) reads the same rows. A repeated kind gets `<kind>_2`, `<kind>_3`, ….
     * The select sits on the verb's span (it holds no column of the sentence), the calc on the sentence's.
     */
    private fun action(
        kind: String,
        verb: SourceLocation,
        at: SourceLocation,
        columns: List<Pair<String, Expression>>,
    ) {
        val base = materialize(verb)
        val k = (portCounts[kind] ?: 0) + 1
        portCounts[kind] = k
        val port = if (k == 1) kind else "${kind}_$k"
        val calc =
            OpCall(
                "calc",
                emptyList(),
                ConfigBlock(
                    columns.map { (n, e) ->
                        AssignEntry(n, e, e.location)
                    },
                    at,
                ),
                at,
            )
        val select =
            OpCall(
                "select",
                columns.map { (n, _) ->
                    namedArg(null, ColumnRef(null, n, verb), verb)
                },
                null,
                verb,
            )
        out += assign(port, Chain(listOf(DottedRef(listOf(base), verb), calc, select), at), at)
        bound += port
        actions +=
            org.tatrman.ttrp.ast
                .ActionOutput(port, kind, at, verb)
        curName = base
    }

    /** A recipient: a column; `department "x"` → `"oddělení:x"`; `<column>, otherwise department "x"` → coalesce. */
    private fun recipient(ctx: P.RecipientContext): Expression =
        when (ctx) {
            is P.DepartmentRecipientContext -> department(ctx.dept)
            is P.ColumnRecipientContext -> {
                val column = exprFolder.foldRef(ctx.column)
                if (ctx.dept == null) {
                    column
                } else {
                    FunctionCall(scalarId("coalesce"), listOf(column, department(ctx.dept)), loc.of(ctx))
                }
            }
            else -> error("unhandled recipient: ${ctx::class.simpleName}")
        }

    private fun department(s: P.StrContext): Expression =
        Literal(LiteralValue.Str(TtrbActions.DEPARTMENT_PREFIX + unquote(s.text)), loc.of(s))

    private fun attachments(ctx: P.AttachmentsContext): Expression =
        Literal(
            LiteralValue.Str(
                ctx.attachmentName().joinToString(TtrbActions.ATTACHMENT_SEPARATOR) {
                    it.str()?.let { s -> unquote(s.text) } ?: it.ident().text
                },
            ),
            loc.of(ctx),
        )

    private fun text(s: P.StrContext): Expression = Literal(LiteralValue.Str(unquote(s.text)), loc.of(s))

    /** An entity / attribute NAME, emitted as text (identifier exact, or a quoted name). */
    private fun name(n: P.NameRefContext): Expression =
        Literal(LiteralValue.Str(n.str()?.let { unquote(it.text) } ?: n.ident().text), loc.of(n))

    private fun aggregateId(name: String): CatalogId =
        catalog.resolve(name).firstOrNull { it.kind == FunctionKind.AGGREGATE }?.id ?: CatalogId(name)

    private fun scalarId(name: String): CatalogId =
        catalog.resolve(name).firstOrNull { it.kind == FunctionKind.SCALAR }?.id ?: CatalogId(name)

    // ---- sinks ---------------------------------------------------------------------

    private fun sinkShow(ctx: P.ShowStmtContext) {
        ensureHead(loc.of(ctx))
        val at = loc.of(ctx)
        val name = ctx.name?.text ?: "result"
        elems += OpCall("display", listOf(refArg(null, name, at)), null, at)
        out += assign(name, Chain(elems.toList(), at), at)
        curName = name
        bound += name
        elems.clear()
    }

    private fun sinkStore(ctx: P.StoreStmtContext) {
        ensureHead(loc.of(ctx))
        val at = loc.of(ctx)
        val target =
            if (ctx.fileSource() != null) {
                ColumnRef(null, unquote(ctx.fileSource().str().text), at)
            } else {
                qref(ctx.dest.text, at)
            }
        elems += OpCall("store", listOf(arg(null, target, at)), null, at)
        out += ChainStmt(Chain(elems.toList(), at), at)
        curName = null
        elems.clear()
    }

    // ---- anaphora + chain plumbing -------------------------------------------------

    private fun append(elem: ChainElem) {
        origin?.let { elemOrigin[elem] = it }
        ensureHead(elem.location)
        elems += elem
    }

    private fun ensureHead(at: SourceLocation) {
        if (elems.isEmpty()) elems += DottedRef(listOf(currentRef(at)), at)
    }

    /** The anaphoric antecedent = the previous sentence's out (C4-b-i). */
    private fun currentRef(at: SourceLocation): String = curName ?: synthName()

    /**
     * The current value as a NAME: a pending chain is bound to a synthesized SSA name first (so a
     * consumer that references it — a join's `left:`, a block's filter — sees the transforms).
     */
    private fun materialize(at: SourceLocation): String {
        if (elems.isEmpty()) return currentRef(at)
        val name = synthName()
        val first = elems.first().location
        val stmt = assign(name, Chain(elems.toList(), first), first)
        out += stmt
        elemOrigin[elems.last()]?.let { originOf[stmt] = it }
        elems.clear()
        curName = name
        bound += name
        return name
    }

    /** A pending chain nothing will consume (the next sentence starts from another value): kept, and flagged (B8). */
    private fun flushDangling() {
        if (elems.isNotEmpty()) {
            val at = elems.first().location
            val lost = ChainStmt(Chain(elems.toList(), at), at)
            out += lost
            originOf[lost] = elemOrigin[elems.last()]
            dangling += lost
            elems.clear()
        }
    }

    // ---- unused results (B8) ----------------------------------------------------------

    /** The sentence a statement / chain element came from: its span and first word. */
    private data class Origin(
        val at: SourceLocation,
        val word: String,
    )

    private var origin: Origin? = null
    private val originOf = java.util.IdentityHashMap<Statement, Origin>()
    private val elemOrigin = java.util.IdentityHashMap<ChainElem, Origin>()
    private val dangling = mutableListOf<Statement>()

    private fun tagOrigins(mark: Int) {
        val o = origin ?: return
        for (i in mark until out.size) originOf.putIfAbsent(out[i], o)
    }

    /**
     * TTRP-B-113: a value a sentence produces that nothing downstream reads — no later sentence, port or
     * display. A rule author reads the sentence as applied; dropping it silently changes the rule. Flagged:
     * a pending chain the next sentence abandons ([dangling]); a named value (a load, a join, a block's
     * filter, a named result, …) no later statement references. A container's default OUT port reads its
     * final value; with the ports unknown (a corpus / bare first pass) the final value is not judged.
     */
    private fun unusedResults(outPort: String?): List<TtrpDiagnostic> {
        val ports = outPorts.orEmpty() + outputs + actions.map { it.port }
        val assigned = out.filterIsInstance<Assignment>().map { it.target }.toSet()
        val defaultOut = outPorts?.firstOrNull()
        val finalRead =
            outPorts == null ||
                (outPorts.isEmpty() && inPorts.isEmpty()) ||
                (defaultOut != null && defaultOut !in assigned)
        val lost = mutableListOf<Statement>()
        lost += dangling
        for ((i, stmt) in out.withIndex()) {
            if (stmt !is Assignment) continue
            if (stmt.target in ports || stmt.target == outPort) continue
            if ((stmt.chain.elements.last() as? OpCall)?.name == "display") continue
            if (out.drop(i + 1).any { references(it, stmt.target) }) continue
            if (i == out.lastIndex && finalRead) continue
            lost += stmt
        }
        return lost
            .mapNotNull { st -> originOf[st] }
            .distinct()
            .sortedBy { it.at.offsetStart }
            .map { reject("TTRP-B-113", TtrpDiagnosticId.B_113, it.at, it.word) }
    }

    /** True if [stmt] reads the value named [name]: a chain head, or a join's left / right, or a union input. */
    private fun references(
        stmt: Statement,
        name: String,
    ): Boolean {
        val elements =
            when (stmt) {
                is Assignment -> stmt.chain.elements
                is ChainStmt -> stmt.chain.elements
                else -> return false
            }
        return elements.any { e ->
            when (e) {
                is DottedRef -> e.parts.first() == name
                is OpCall ->
                    (e.name == "join" || e.name == "union") &&
                        e.args.any { a ->
                            (a.name == null || a.name == "left" || a.name == "right") &&
                                ((a.value as? ExprArg)?.expr as? ColumnRef)?.let {
                                    it.port == null && it.column == name
                                } ==
                                true
                        }
            }
        }
    }

    private fun noteExternal(name: String) {
        if (name !in bound) derived += name
    }

    // ---- builders ------------------------------------------------------------------

    private fun assign(
        target: String,
        chain: Chain,
        at: SourceLocation,
    ): Assignment = Assignment(target = target, targetLocation = at, chain = chain, location = at)

    private fun qref(
        qname: String,
        at: SourceLocation,
    ): ColumnRef {
        val parts = qname.split(".")
        return ColumnRef(
            port = parts.dropLast(1).joinToString(".").ifEmpty { null },
            column = parts.last(),
            location = at,
        )
    }

    private fun namedArg(
        name: String?,
        expr: Expression,
        at: SourceLocation,
    ): Arg = Arg(name, ExprArg(expr, at), at)

    private fun arg(
        name: String?,
        expr: Expression,
        at: SourceLocation,
    ): Arg = Arg(name, ExprArg(expr, at), at)

    private fun refArg(
        name: String?,
        ref: String,
        at: SourceLocation,
    ): Arg = Arg(name, ExprArg(ColumnRef(port = null, column = ref, location = at), at), at)

    private fun synthName(): String = "_b${synth++}"

    private fun unquote(raw: String): String = raw.substring(1, raw.length - 1)
}
