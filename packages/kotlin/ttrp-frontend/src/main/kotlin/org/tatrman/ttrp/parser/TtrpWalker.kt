// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.parser

import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.ArgValue
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.Chain
import org.tatrman.ttrp.ast.ChainElem
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ConfigBlock
import org.tatrman.ttrp.ast.ConfigEntry
import org.tatrman.ttrp.ast.ContainerBody
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.ControlBlock
import org.tatrman.ttrp.ast.ControlDep
import org.tatrman.ttrp.ast.ControlKind
import org.tatrman.ttrp.ast.CubeletLhs
import org.tatrman.ttrp.ast.CubeletOp
import org.tatrman.ttrp.ast.CubeletStmt
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.MdWithClause
import org.tatrman.ttrp.ast.MdWithEntry
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.GroupByEntry
import org.tatrman.ttrp.ast.ImportDecl
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.PortDecl
import org.tatrman.ttrp.ast.PortKind
import org.tatrman.ttrp.ast.ProgramHeader
import org.tatrman.ttrp.ast.Qname
import org.tatrman.ttrp.ast.RelationArg
import org.tatrman.ttrp.ast.SchemaColumn
import org.tatrman.ttrp.ast.SchemaLiteralArg
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.ast.UsesWorld
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.expr.MdPathAtom
import org.tatrman.ttrp.expr.MdPathComponent
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.expr.catalog.FunctionCatalog
import org.tatrman.ttrp.expr.catalog.FunctionKind
import org.tatrman.ttrp.parser.generated.TTRPLexer
import org.tatrman.ttrp.parser.generated.TTRPParser

/**
 * Converts the ANTLR parse tree into the typed TTR-P AST. Pure structure — no
 * semantic validation (that is [TtrpChecks]); trivia attachment happens in
 * [TtrpParser] over the token stream. Fragment interiors are captured VERBATIM from
 * the [Token] text (C2-f) — no dedent, no trim.
 *
 * Expression positions fold to the one PL expression IR ([Expression]): operators
 * become catalogue-id [FunctionCall]s, aggregate-catalogue hits (or a `distinct`)
 * become [AggregateCall], and a dotted reference in expression position folds to a
 * [ColumnRef]. The walker consults [catalog] only to classify aggregate vs scalar;
 * all typing is deferred to [org.tatrman.ttrp.expr.ExpressionTypechecker]. The one
 * diagnostic the walker itself raises is `TTRP-EQ-001` for a lexed `==` (S9), pushed
 * to [diagnostics].
 */
internal class TtrpWalker(
    private val fileName: String,
    private val source: String,
    private val tokens: CommonTokenStream,
    private val catalog: FunctionCatalog,
) {
    // Token indices already claimed as a statement's trailing trivia, so the next
    // statement's leading scan does not double-count a same-line comment (C2-f).
    private val consumedTrailing = mutableSetOf<Int>()

    /** Diagnostics the walker raises during folding (currently EQ-001 only). */
    val diagnostics = mutableListOf<TtrpDiagnostic>()

    fun walk(ctx: TTRPParser.DocumentContext): TtrpDocument =
        TtrpDocument(
            statements = ctx.statement().mapNotNull { statement(it) },
            location = loc(ctx),
        )

    /** Folds a standalone `expr` parse tree to the IR (the expression-corpus entry point). */
    fun foldExpr(ctx: TTRPParser.ExprContext): Expression = expr(ctx)

    /**
     * Returns null for an error/partial statement (the parser already reported the
     * syntax error) so recovery does not crash the walk — the rest of the document
     * still yields a usable AST (Stage 1.1 error-recovery baseline).
     */
    private fun statement(ctx: TTRPParser.StatementContext): Statement? {
        val built =
            when {
                ctx.usesWorld() != null -> usesWorld(ctx.usesWorld())
                ctx.importDecl() != null -> importDecl(ctx.importDecl())
                ctx.schemaDecl() != null -> schemaDecl(ctx.schemaDecl())
                ctx.paramDecl() != null -> paramDecl(ctx.paramDecl())
                ctx.containerDecl() != null -> containerDecl(ctx.containerDecl())
                ctx.controlBlock() != null -> controlBlock(ctx.controlBlock())
                ctx.programHeader() != null -> programHeader(ctx.programHeader())
                ctx.controlStmt() != null -> controlStmt(ctx.controlStmt())
                ctx.bindingOrChain() != null -> bindingOrChain(ctx.bindingOrChain())
                ctx.cubeletStmt() != null -> cubeletStmt(ctx.cubeletStmt())
                else -> return null
            }
        return attachTrivia(built, ctx)
    }

    private fun usesWorld(ctx: TTRPParser.UsesWorldContext): UsesWorld =
        UsesWorld(world = unquote(ctx.STRING().text), location = loc(ctx))

    private fun importDecl(ctx: TTRPParser.ImportDeclContext): ImportDecl =
        ImportDecl(qname = qname(ctx.qname()), wildcard = ctx.STAR() != null, location = loc(ctx))

    private fun programHeader(ctx: TTRPParser.ProgramHeaderContext): ProgramHeader =
        ProgramHeader(name = ctx.identifier().text, location = loc(ctx))

    private fun schemaDecl(ctx: TTRPParser.SchemaDeclContext): org.tatrman.ttrp.ast.SchemaDecl =
        org.tatrman.ttrp.ast.SchemaDecl(
            name = ctx.identifier().text,
            columns = ctx.schemaField().map { schemaField(it) },
            location = loc(ctx),
        )

    private fun paramDecl(ctx: TTRPParser.ParamDeclContext): org.tatrman.ttrp.ast.ParamDecl {
        val default: org.tatrman.ttrp.ast.ParamDefault? =
            ctx.paramDefault()?.let { d ->
                if (d.BUILTIN() != null) {
                    // Strip the `@` sigil; the manifest re-adds it (`@run-date`).
                    org.tatrman.ttrp.ast.ParamDefault
                        .Builtin(d.BUILTIN().text.removePrefix("@"), loc(d))
                } else {
                    org.tatrman.ttrp.ast.ParamDefault
                        .Literal(d.literal().text, loc(d))
                }
            }
        return org.tatrman.ttrp.ast.ParamDecl(
            name = ctx.identifier().text,
            type = ctx.typeName().text,
            typeLocation = loc(ctx.typeName()),
            default = default,
            location = loc(ctx),
            nameLocation = loc(ctx.identifier()),
        )
    }

    private fun schemaField(ctx: TTRPParser.SchemaFieldContext): SchemaColumn =
        SchemaColumn(
            name = ctx.identifier().text,
            type = ctx.typeName().text,
            location = loc(ctx),
        )

    private fun bindingOrChain(ctx: TTRPParser.BindingOrChainContext): Statement =
        when (ctx) {
            is TTRPParser.AssignmentContext ->
                Assignment(
                    target = ctx.identifier().text,
                    targetLocation = loc(ctx.identifier()),
                    chain = chain(ctx.chain()),
                    location = loc(ctx),
                )
            is TTRPParser.ChainStmtContext ->
                ChainStmt(chain = chain(ctx.chain()), location = loc(ctx))
            else -> error("unhandled bindingOrChain at ${loc(ctx)}")
        }

    private fun controlStmt(ctx: TTRPParser.ControlStmtContext): ControlDep =
        when (ctx) {
            is TTRPParser.AfterFsContext ->
                ControlDep(ControlKind.FS, ctx.identifier(0).text, ctx.identifier(1).text, loc(ctx))
            is TTRPParser.WithSsContext ->
                ControlDep(ControlKind.SS, ctx.identifier(0).text, ctx.identifier(1).text, loc(ctx))
            is TTRPParser.FinishesFfContext ->
                ControlDep(ControlKind.FF, ctx.identifier(0).text, ctx.identifier(1).text, loc(ctx))
            else -> error("unhandled controlStmt at ${loc(ctx)}")
        }

    private fun controlBlock(ctx: TTRPParser.ControlBlockContext): ControlBlock =
        ControlBlock(deps = ctx.controlStmt().map { controlStmt(it) }, location = loc(ctx))

    private fun containerDecl(ctx: TTRPParser.ContainerDeclContext): ContainerDecl {
        val ports = ctx.portSig()?.portDecl()?.map { portDecl(it) } ?: emptyList()
        val body: ContainerBody =
            when {
                ctx.TAGGED_BLOCK() != null -> fragmentBody(ctx.TAGGED_BLOCK().symbol)
                ctx.FROM() != null && ctx.STRING() != null -> fileFragmentBody(ctx.FROM().symbol, ctx.STRING().symbol)
                ctx.LBRACE() != null && ctx.RBRACE() != null ->
                    FlowBody(
                        statements = ctx.statement().mapNotNull { statement(it) },
                        location =
                            SourceLocation(
                                fileName,
                                ctx.LBRACE().symbol.line,
                                ctx.LBRACE().symbol.charPositionInLine,
                                ctx.RBRACE().symbol.line,
                                ctx.RBRACE().symbol.charPositionInLine + 1,
                                ctx.LBRACE().symbol.startIndex,
                                ctx.RBRACE().symbol.stopIndex + 1,
                            ),
                    )
                // Malformed/incomplete container body (error recovery) — best-effort empty flow body.
                else -> FlowBody(statements = ctx.statement().mapNotNull { statement(it) }, location = loc(ctx))
            }
        // PL-P2.S1 container execution attributes (F-4-iv on-failure, F-4-ii retries).
        var onFailureOf: String? = null
        var onFailureOfLocation: SourceLocation? = null
        var onFailureAbsorbs = false
        var retries: Int? = null
        var retriesLocation: SourceLocation? = null
        for (attr in ctx.containerAttr()) {
            when (attr) {
                is TTRPParser.OnFailureAttrContext -> {
                    onFailureOf = attr.identifier().text
                    onFailureOfLocation = loc(attr)
                    onFailureAbsorbs = attr.ABSORBS() != null
                }
                is TTRPParser.RetriesAttrContext -> {
                    retries = attr.INT().text.toIntOrNull()
                    retriesLocation = loc(attr)
                }
            }
        }
        return ContainerDecl(
            name = ctx.identifier().text,
            ports = ports,
            target = qname(ctx.qname()),
            body = body,
            location = loc(ctx),
            nameLocation = loc(ctx.identifier()),
            onFailureOf = onFailureOf,
            onFailureOfLocation = onFailureOfLocation,
            onFailureAbsorbs = onFailureAbsorbs,
            retries = retries,
            retriesLocation = retriesLocation,
        )
    }

    private fun portDecl(ctx: TTRPParser.PortDeclContext): PortDecl {
        val kind =
            when {
                ctx.IN() != null -> PortKind.IN
                ctx.OUT() != null -> PortKind.OUT
                else -> PortKind.ERR
            }
        return PortDecl(kind = kind, name = ctx.identifier().text, location = loc(ctx))
    }

    /** Peels the tag and captures the interior VERBATIM (C2-f) — the raw bytes between the tag line's newline and the closing `"""`. */
    private fun fragmentBody(token: Token): FragmentBody {
        val body = token.text
        val tag = body.substring(3).takeWhile { it.isLetterOrDigit() || it == '-' }
        val firstNl = body.indexOf('\n')
        val interiorStart = firstNl + 1
        val interiorEnd = body.length - 3 // strip the closing """
        val sourceText =
            if (firstNl in 0 until interiorEnd) body.substring(interiorStart, interiorEnd) else ""
        val absStart = token.startIndex + interiorStart
        val absEnd = token.startIndex + interiorEnd
        val (sLine, sCol) = offsetToLineCol(absStart)
        val (eLine, eCol) = offsetToLineCol(absEnd)
        val interiorLocation =
            SourceLocation(fileName, sLine, sCol, eLine, eCol, absStart, absEnd)
        return FragmentBody(
            tag = tag,
            sourceText = sourceText,
            interiorLocation = interiorLocation,
            location = loc(token),
        )
    }

    /**
     * AG B5 — `from "<path>"`: the fragment file's whole content is the interior, byte-preserved (C2-f),
     * resolved relative to the program; its dialect from the file's extension / first-line marker. The
     * body's [FragmentBody.location] is the `from "<path>"` clause; its interior location names the FILE,
     * so diagnostics inside the fragment carry the file's own lines. A missing / unreadable file is
     * TTRP-FRG-004, an unmarked one TTRP-FRG-005 (the body then has no dialect and is not decomposed).
     */
    private fun fileFragmentBody(
        from: Token,
        path: Token,
    ): FragmentBody {
        val rel = unquote(path.text)
        val clause =
            loc(from).copy(
                endLine = loc(path).endLine,
                endColumn = loc(path).endColumn,
                offsetEnd =
                    path.stopIndex + 1,
            )

        fun failed(
            id: TtrpDiagnosticId,
            message: String,
        ): FragmentBody {
            diagnostics += TtrpDiagnostic(id, Severity.ERROR, message, clause, id.suggestedAlternative)
            return FragmentBody(
                tag = "",
                sourceText = "",
                interiorLocation = clause,
                location = clause,
                sourceFile = rel,
            )
        }
        val file =
            org.tatrman.ttrp.dialect.FragmentFiles
                .resolve(fileName, rel)
        val text =
            file?.let {
                runCatching {
                    java.nio.file.Files
                        .readString(it)
                }.getOrNull()
            }
                ?: return failed(
                    TtrpDiagnosticId.FRG_004,
                    "fragment file `$rel` not found (relative to the program file)",
                )
        val dialect =
            org.tatrman.ttrp.dialect.bare.DialectMarker
                .resolve(file.fileName.toString(), text)
                ?: return failed(TtrpDiagnosticId.FRG_005, "fragment file `$rel` has no dialect marker")
        val lines = text.split('\n')
        val interior =
            SourceLocation(
                file =
                    org.tatrman.ttrp.dialect.FragmentFiles
                        .idOf(fileName, file),
                line = 1,
                column = 0,
                endLine = lines.size,
                endColumn = lines.last().length,
                offsetStart = 0,
                offsetEnd = text.length,
            )
        return FragmentBody(
            tag = dialect,
            sourceText = text,
            interiorLocation = interior,
            location = clause,
            sourceFile = rel,
        )
    }

    private fun chain(ctx: TTRPParser.ChainContext): Chain =
        Chain(elements = ctx.chainElem().map { chainElem(it) }, location = loc(ctx))

    private fun chainElem(ctx: TTRPParser.ChainElemContext): ChainElem =
        ctx.opCall()?.let { opCall(it) } ?: dottedRef(ctx.dottedRef())

    private fun opCall(ctx: TTRPParser.OpCallContext): OpCall =
        OpCall(
            name = ctx.identifier().text,
            args = ctx.argList()?.arg()?.map { arg(it) } ?: emptyList(),
            config = ctx.configBlock()?.let { configBlock(it) },
            location = loc(ctx),
        )

    private fun arg(ctx: TTRPParser.ArgContext): Arg {
        val name = if (ctx.COLON() != null) ctx.identifier().text else null
        return Arg(name = name, value = argValue(ctx.argValue()), location = loc(ctx))
    }

    private fun argValue(ctx: TTRPParser.ArgValueContext): ArgValue =
        when {
            ctx.RELATION() != null -> RelationArg(qname(ctx.qname()), loc(ctx))
            ctx.schemaLiteral() != null -> schemaLiteral(ctx.schemaLiteral())
            else -> ExprArg(expr(ctx.expr()), loc(ctx))
        }

    private fun schemaLiteral(ctx: TTRPParser.SchemaLiteralContext): SchemaLiteralArg =
        SchemaLiteralArg(columns = ctx.schemaField().map { schemaField(it) }, location = loc(ctx))

    private fun configBlock(ctx: TTRPParser.ConfigBlockContext): ConfigBlock =
        ConfigBlock(entries = ctx.configEntry().mapNotNull { configEntry(it) }, location = loc(ctx))

    /** A config entry; null for one the parser only half-recovered (its syntax error is already reported). */
    private fun configEntry(ctx: TTRPParser.ConfigEntryContext): ConfigEntry? =
        if (ctx.GROUP() != null) {
            GroupByEntry(keys = ctx.identifier().map { it.text }, location = loc(ctx))
        } else {
            val name = ctx.identifier(0)
            val value = ctx.expr()
            if (name == null ||
                value == null
            ) {
                null
            } else {
                AssignEntry(name = name.text, value = expr(value), location = loc(ctx))
            }
        }

    private fun qname(ctx: TTRPParser.QnameContext): Qname =
        Qname(parts = ctx.identifier().map { it.text }, location = loc(ctx))

    private fun dottedRef(ctx: TTRPParser.DottedRefContext): DottedRef {
        val parts = mutableListOf(ctx.identifier().text)
        ctx.idPart().forEach { parts += it.text }
        return DottedRef(parts = parts, location = loc(ctx))
    }

    // ---- expression folding → the one PL expression IR (Stage 1.2) --------------

    private fun expr(ctx: TTRPParser.ExprContext): Expression = orExpr(ctx.orExpr())

    private fun orExpr(ctx: TTRPParser.OrExprContext): Expression =
        foldOperator(ctx.andExpr().map { andExpr(it) }, CatalogId.OR, loc(ctx))

    private fun andExpr(ctx: TTRPParser.AndExprContext): Expression =
        foldOperator(ctx.notExpr().map { notExpr(it) }, CatalogId.AND, loc(ctx))

    private fun notExpr(ctx: TTRPParser.NotExprContext): Expression =
        if (ctx.NOT() != null) {
            FunctionCall(CatalogId.NOT, listOf(notExpr(ctx.notExpr())), loc(ctx))
        } else {
            predicate(ctx.predicate())
        }

    private fun predicate(ctx: TTRPParser.PredicateContext): Expression {
        val left = addExpr(ctx.addExpr(0))
        return when {
            ctx.IS() != null -> IsNull(left, negated = ctx.NOT() != null, location = loc(ctx))
            ctx.IN() != null ->
                InList(
                    expr = left,
                    items = ctx.expr().map { expr(it) },
                    negated = ctx.NOT() != null,
                    location = loc(ctx),
                )
            ctx.BETWEEN() != null -> {
                // Authoring sugar (T5-b-α): x between lo and hi  →  x >= lo and x <= hi.
                val lo = addExpr(ctx.addExpr(1))
                val hi = addExpr(ctx.addExpr(2))
                FunctionCall(
                    CatalogId.AND,
                    listOf(
                        FunctionCall(CatalogId.GTE, listOf(left, lo), loc(ctx)),
                        FunctionCall(CatalogId.LTE, listOf(left, hi), loc(ctx)),
                    ),
                    loc(ctx),
                )
            }
            else -> {
                val cmp = comparisonOp(ctx) ?: return left
                FunctionCall(cmp, listOf(left, addExpr(ctx.addExpr(1))), loc(ctx))
            }
        }
    }

    /** The comparison operator of a predicate (null = no operator). `==` is folded to `op.eq` but rejected (EQ-001). */
    private fun comparisonOp(ctx: TTRPParser.PredicateContext): CatalogId? =
        when {
            ctx.ASSIGN() != null -> CatalogId.EQ
            ctx.EQEQ() != null -> {
                diagnostics +=
                    TtrpDiagnostic(
                        id = TtrpDiagnosticId.EQ_001,
                        severity = Severity.ERROR,
                        message = "`==` is not the equality operator",
                        location = loc(ctx.EQEQ().symbol),
                    )
                CatalogId.EQ
            }
            ctx.NEQ() != null -> CatalogId.NEQ
            ctx.LT() != null -> CatalogId.LT
            ctx.LTE() != null -> CatalogId.LTE
            ctx.GT() != null -> CatalogId.GT
            ctx.GTE() != null -> CatalogId.GTE
            else -> null
        }

    private fun addExpr(ctx: TTRPParser.AddExprContext): Expression {
        var acc = mulExpr(ctx.mulExpr(0))
        val ops = symbolOps(ctx, setOf("+", "-"))
        for (i in 1 until ctx.mulExpr().size) {
            val id = if (ops[i - 1] == "+") CatalogId.ADD else CatalogId.SUB
            acc = FunctionCall(id, listOf(acc, mulExpr(ctx.mulExpr(i))), loc(ctx))
        }
        return acc
    }

    private fun mulExpr(ctx: TTRPParser.MulExprContext): Expression {
        var acc = unaryExpr(ctx.unaryExpr(0))
        val ops = symbolOps(ctx, setOf("*", "/"))
        for (i in 1 until ctx.unaryExpr().size) {
            val id = if (ops[i - 1] == "*") CatalogId.MUL else CatalogId.DIV
            acc = FunctionCall(id, listOf(acc, unaryExpr(ctx.unaryExpr(i))), loc(ctx))
        }
        return acc
    }

    private fun unaryExpr(ctx: TTRPParser.UnaryExprContext): Expression =
        if (ctx.MINUS() != null) {
            FunctionCall(CatalogId.NEG, listOf(unaryExpr(ctx.unaryExpr())), loc(ctx))
        } else {
            primary(ctx.primary())
        }

    private fun primary(ctx: TTRPParser.PrimaryContext): Expression =
        when {
            ctx.literal() != null -> literal(ctx.literal())
            ctx.castExpr() != null -> castExpr(ctx.castExpr())
            ctx.caseExpr() != null -> caseExpr(ctx.caseExpr())
            ctx.functionCall() != null -> functionCall(ctx.functionCall())
            ctx.dottedRef() != null -> columnRef(ctx.dottedRef())
            ctx.mdPath() != null -> mdPath(ctx.mdPath())
            else -> expr(ctx.expr()) // ( expr ) — parens are structural, dropped
        }

    // --- MD dot-path (contracts §1.2, D14) — structural capture; resolution is S2/S3 ------------
    private fun mdPath(ctx: TTRPParser.MdPathContext): MdPath =
        MdPath(components = ctx.pathComponent().map { pathComponent(it) }, location = loc(ctx))

    private fun pathComponent(ctx: TTRPParser.PathComponentContext): MdPathComponent =
        when {
            ctx.LBRACE() != null -> MdPathComponent.MemberSet(ctx.pathAtom().map { pathAtom(it) }, loc(ctx))
            ctx.DOTDOT() != null ->
                MdPathComponent.Range(
                    pathAtom(ctx.pathAtom(0)),
                    pathAtom(ctx.pathAtom(1)),
                    loc(ctx),
                )
            ctx.STAR() != null -> MdPathComponent.Star(loc(ctx))
            ctx.identifier() != null -> MdPathComponent.Name(ctx.identifier().text, loc(ctx))
            ctx.INT() != null -> MdPathComponent.IntLit(ctx.INT().text, loc(ctx))
            else -> MdPathComponent.StrLit(unquote(ctx.STRING().text), loc(ctx))
        }

    private fun pathAtom(ctx: TTRPParser.PathAtomContext): MdPathAtom =
        when {
            ctx.identifier() != null -> MdPathAtom.Name(ctx.identifier().text, loc(ctx))
            ctx.INT() != null -> MdPathAtom.IntLit(ctx.INT().text, loc(ctx))
            else -> MdPathAtom.StrLit(unquote(ctx.STRING().text), loc(ctx))
        }

    /**
     * `cast(x as <type>)`. A malformed cast the parser recovered from — `cast(x, string)` (a comma for `as`),
     * `cast(x)` — has no type: report it (the syntax error itself is already a PRS-001) and fold to the inner
     * expression, so the rest of the pipeline sees no half-built node (it used to NPE here).
     */
    private fun castExpr(ctx: TTRPParser.CastExprContext): Expression {
        val type = ctx.typeName()?.takeIf { it.identifier() != null }
        val inner = ctx.expr()?.let { expr(it) }
        if (type == null || inner == null) {
            diagnostics +=
                TtrpDiagnostic(
                    id = TtrpDiagnosticId.PRS_001,
                    severity = Severity.ERROR,
                    message = "malformed cast `${ctx.text}` — a cast is written `cast(<expr> as <type>)`",
                    location = loc(ctx),
                    suggestedAlternative =
                        "write `cast(x as string)` — `as` separates the value and the type, not a comma (B-T5)",
                )
            return inner ?: Literal(LiteralValue.Null, loc(ctx))
        }
        return Cast(expr = inner, target = typeName(type), location = loc(ctx))
    }

    private fun typeName(ctx: TTRPParser.TypeNameContext): TtrpType {
        val nums = ctx.INT()
        return TtrpType.parse(
            spelling = ctx.identifier().text,
            precision = nums.getOrNull(0)?.text?.toIntOrNull(),
            scale = nums.getOrNull(1)?.text?.toIntOrNull(),
        )
    }

    private fun caseExpr(ctx: TTRPParser.CaseExprContext): CaseWhen {
        val whenCount = ctx.WHEN().size
        val exprs = ctx.expr()
        val branches = (0 until whenCount).map { expr(exprs[it * 2]) to expr(exprs[it * 2 + 1]) }
        val elseExpr = if (exprs.size > whenCount * 2) expr(exprs[whenCount * 2]) else null
        return CaseWhen(branches = branches, elseExpr = elseExpr, location = loc(ctx))
    }

    /**
     * Folds a call to [AggregateCall] when the name resolves to an aggregate in
     * [catalog] (or carries `distinct`), else [FunctionCall]. A `distinct` on a
     * non-aggregate still becomes [AggregateCall] so the checker can reject it
     * (TTRP-FN-002); the surface name rides in the [CatalogId] for re-resolution.
     */
    private fun functionCall(ctx: TTRPParser.FunctionCallContext): Expression {
        val name = ctx.identifier().text
        val args = ctx.expr().map { expr(it) }
        val distinct = ctx.DISTINCT() != null
        val entries = catalog.resolve(name)
        val aggEntry = entries.firstOrNull { it.kind == FunctionKind.AGGREGATE }
        val scalarEntry = entries.firstOrNull { it.kind == FunctionKind.SCALAR }
        return when {
            aggEntry != null -> AggregateCall(aggEntry.id, args, distinct, loc(ctx))
            distinct -> AggregateCall(CatalogId(name), args, distinct = true, location = loc(ctx))
            scalarEntry != null -> FunctionCall(scalarEntry.id, args, loc(ctx))
            else -> FunctionCall(CatalogId(name), args, loc(ctx))
        }
    }

    private fun columnRef(ctx: TTRPParser.DottedRefContext): ColumnRef {
        val ref = dottedRef(ctx)
        val port = if (ref.parts.size > 1) ref.parts.dropLast(1).joinToString(".") else null
        return ColumnRef(port = port, column = ref.parts.last(), location = ref.location)
    }

    private fun literal(ctx: TTRPParser.LiteralContext): Literal {
        val value: LiteralValue =
            when {
                ctx.STRING() != null -> LiteralValue.Str(unquote(ctx.STRING().text))
                ctx.CHAR_STRING() != null -> LiteralValue.Str(unquote(ctx.CHAR_STRING().text))
                // numericLiteral = floatLiteral | INT; `.text` reconstructs the raw number
                // (WS-insignificant, R2), keeping `12.5`-style literals byte-identical.
                ctx.numericLiteral() != null -> LiteralValue.Num(ctx.numericLiteral().text)
                ctx.TRUE() != null -> LiteralValue.Bool(true)
                ctx.FALSE() != null -> LiteralValue.Bool(false)
                else -> LiteralValue.Null
            }
        return Literal(value = value, location = loc(ctx))
    }

    private fun foldOperator(
        operands: List<Expression>,
        op: CatalogId,
        at: SourceLocation,
    ): Expression {
        var acc = operands.first()
        for (i in 1 until operands.size) acc = FunctionCall(op, listOf(acc, operands[i]), at)
        return acc
    }

    /** The `+`/`-` (or `*`/`/`) terminal texts of a folding rule, in source order. */
    private fun symbolOps(
        ctx: ParserRuleContext,
        wanted: Set<String>,
    ): List<String> =
        ctx.children
            .orEmpty()
            .mapNotNull { (it as? org.antlr.v4.runtime.tree.TerminalNode)?.text }
            .filter { it in wanted }

    // ---- trivia attach ----------------------------------------------------------

    /**
     * Attaches comment trivia (HIDDEN channel) to a statement: nearest preceding
     * hidden tokens are leading; same-line following comments are trailing. A
     * same-line trailing comment is claimed here so the next statement's leading
     * scan skips it (no double-count).
     */
    private fun attachTrivia(
        stmt: Statement,
        ctx: TTRPParser.StatementContext,
    ): Statement {
        val trailing = trailingTriviaOf(ctx.stop)
        val leading = leadingTriviaOf(ctx.start)
        if (leading.isEmpty() && trailing.isEmpty()) return stmt
        return when (stmt) {
            is UsesWorld -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is ImportDecl -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is org.tatrman.ttrp.ast.SchemaDecl -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is org.tatrman.ttrp.ast.ParamDecl -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is ProgramHeader -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is Assignment -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is ChainStmt -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is ControlDep -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is ControlBlock -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is ContainerDecl -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
            is CubeletStmt -> stmt.copy(leadingTrivia = leading, trailingTrivia = trailing)
        }
    }

    // --- MD cubelet statements (contracts §1.2, D20–D24) — mechanical; dispatch is S5C (R24) -----
    private fun cubeletStmt(ctx: TTRPParser.CubeletStmtContext): CubeletStmt {
        val lhsCtx = ctx.cubeletLhs()
        val lhs: CubeletLhs =
            if (lhsCtx.mdPath() != null) {
                CubeletLhs.Path(mdPath(lhsCtx.mdPath()), loc(lhsCtx))
            } else {
                CubeletLhs.Name(lhsCtx.identifier().text, loc(lhsCtx))
            }
        val op =
            when (ctx.op.type) {
                TTRPParser.ASSIGN -> CubeletOp.ASSIGN
                TTRPParser.ASSIGN_MAT -> CubeletOp.MATERIALIZE
                TTRPParser.PLUS_ASSIGN -> CubeletOp.MERGE
                else -> CubeletOp.DELETE // MINUS_ASSIGN
            }
        return CubeletStmt(
            lhs = lhs,
            op = op,
            rhs = expr(ctx.expr()),
            withClause = ctx.withClause()?.let { withClause(it) },
            location = loc(ctx),
        )
    }

    private fun withClause(ctx: TTRPParser.WithClauseContext): MdWithClause =
        MdWithClause(
            entries =
                ctx.mdObject().mdObjectEntry().map {
                    MdWithEntry(key = it.identifier().text, value = it.mdObjectValue().text, location = loc(it))
                },
            location = loc(ctx),
        )

    private fun leadingTriviaOf(start: Token): List<org.tatrman.ttrp.ast.Trivia> =
        tokens
            .getHiddenTokensToLeft(start.tokenIndex)
            .orEmpty()
            .filter { it.tokenIndex !in consumedTrailing && isComment(it) }
            .map { trivia(it) }

    private fun trailingTriviaOf(stop: Token): List<org.tatrman.ttrp.ast.Trivia> =
        tokens
            .getHiddenTokensToRight(stop.tokenIndex)
            .orEmpty()
            .filter { it.line == stop.line && isComment(it) }
            .onEach { consumedTrailing += it.tokenIndex }
            .map { trivia(it) }

    private fun isComment(t: Token): Boolean = t.type == TTRPLexer.LINE_COMMENT || t.type == TTRPLexer.BLOCK_COMMENT

    private fun trivia(t: Token): org.tatrman.ttrp.ast.Trivia =
        org.tatrman.ttrp.ast
            .Trivia(text = t.text, location = loc(t))

    // ---- source-location helpers ------------------------------------------------

    private fun loc(ctx: ParserRuleContext): SourceLocation {
        val start = ctx.start
        val stop = ctx.stop ?: ctx.start
        // Derive end line/col from the stop token's END OFFSET, not `stop.line` +
        // token length: ANTLR reports `token.line` as the token's FIRST line, so a
        // multi-line stop token (e.g. a TAGGED_BLOCK-bodied container) would otherwise
        // record its end at the OPENING line/an out-of-range column (review-001 1.1-A).
        // `offsetToLineCol` walks the source, matching the sibling `loc(Token)` overload.
        val (eLine, eCol) = offsetToLineCol(stop.stopIndex + 1)
        return SourceLocation(
            file = fileName,
            line = start.line,
            column = start.charPositionInLine,
            endLine = eLine,
            endColumn = eCol,
            offsetStart = start.startIndex,
            offsetEnd = stop.stopIndex + 1,
        )
    }

    private fun loc(token: Token): SourceLocation {
        val text = token.text ?: ""
        val (eLine, eCol) = offsetToLineCol(token.stopIndex + 1)
        return SourceLocation(
            file = fileName,
            line = token.line,
            column = token.charPositionInLine,
            endLine = eLine,
            endColumn = eCol,
            offsetStart = token.startIndex,
            offsetEnd = token.stopIndex + 1,
        )
    }

    private fun offsetToLineCol(offset: Int): Pair<Int, Int> {
        var line = 1
        var col = 0
        val end = offset.coerceIn(0, source.length)
        for (i in 0 until end) {
            if (source[i] == '\n') {
                line++
                col = 0
            } else {
                col++
            }
        }
        return line to col
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' || s.first() == '\'')) s.substring(1, s.length - 1) else s
}
