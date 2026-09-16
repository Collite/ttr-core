<#--
// Custom parser-method implementations for the calcite-ext extended SQL parser
// (master-plan §5.5, decision D7). Included into the generated Parser.jj at the
// implementation-insertion points via `parser.implementationFiles`.
//
// Phase 0b: the postfix COLLATE method, wired via the
// `extraBinaryExpressions: ["Collate"]` hook in config.fmpp.
-->

/**
 * Parses a postfix T-SQL COLLATE: `<expr> COLLATE <collationName>` (master-plan §5.5).
 *
 * Invoked from the binary-expression production (Expression2b) via the `extraBinaryExpressions`
 * hook — `list` already holds the left operand (the collated expression). We append a
 * SqlCollateOperator tree item and the collation name captured as a CHARACTER STRING LITERAL, so
 * the name is never resolved as a column. The operator's precedence (40 > LIKE 32 > `=` 30) makes
 * `x COLLATE c LIKE 'a%'` group as `(x COLLATE c) LIKE 'a%'`.
 */
void Collate(List<Object> list, ExprContext exprContext, Span s) :
{
    final String collationName;
}
{
    <COLLATE> collationName = Identifier() {
        list.add(
            new SqlParserUtil.ToTreeListItem(
                org.tatrman.translator.functions.SqlCollateOperator.INSTANCE, getPos()));
        list.add(SqlLiteral.createCharString(collationName, getPos()));
    }
}

/**
 * Phase 2 — T-SQL datetime keyword tokens. Copied from Calcite's babel module so the trio below
 * dispatch on a leading keyword rather than being parsed as ordinary identifier function calls.
 */
<DEFAULT, DQID, BTID> TOKEN :
{
    < DATE_PART: "DATE_PART" >
|   < DATEADD: "DATEADD" >
|   < DATEDIFF: "DATEDIFF" >
|   < DATEPART: "DATEPART" >
|   < TRY_CONVERT: "TRY_CONVERT" >
}

/**
 * Normalises a T-SQL datepart: if the interval qualifier is a bare time-frame *name* (e.g. "dd")
 * that maps to a canonical TimeUnit, return a TimeUnit-backed qualifier so it unparses bare and
 * T-SQL-valid (`DATEADD(DAY, …)`); otherwise return it unchanged. (tasks-dateadd.md gap 1.)
 */
JAVACODE
SqlIntervalQualifier NormalizeDatepart(SqlIntervalQualifier q) {
    if (q.timeFrameName == null) {
        return q;
    }
    org.apache.calcite.avatica.util.TimeUnit unit =
        org.tatrman.translator.functions.Dateparts.toTimeUnit(q.timeFrameName);
    if (unit == null) {
        return q;
    }
    return new SqlIntervalQualifier(unit, null, q.getParserPosition());
}

/**
 * Parses a call to the PostgreSQL "DATE_PART(timeUnit, datetime)" function (copied from babel).
 */
SqlNode DatePartFunctionCall() :
{
    final Span s;
    final SqlOperator op;
    final SqlNode unit;
    final List<SqlNode> args;
    SqlNode e;
}
{
    <DATE_PART> { op = org.apache.calcite.sql.fun.SqlLibraryOperators.DATE_PART; }
    { s = span(); }
    <LPAREN>
    (   unit = TimeUnitOrName() {
            args = startList(unit);
        }
    |   unit = Expression(ExprContext.ACCEPT_NON_QUERY) {
            args = startList(unit);
        }
    )
    <COMMA> e = Expression(ExprContext.ACCEPT_SUB_QUERY) {
        args.add(e);
    }
    <RPAREN> {
        return op.createCall(s.end(this), args);
    }
}

/**
 * Parses a call to the T-SQL "DATEADD(timeUnit, n, datetime)" / "DATEDIFF(timeUnit, a, b)" /
 * "DATEPART(timeUnit, datetime)" functions (copied from babel). The leading time unit is parsed
 * by the core `TimeUnitOrName()` production, which yields a SqlIntervalQualifier — a known
 * TimeUnit or a bare time-frame name. `NormalizeDatepart` (above) maps the T-SQL abbreviations to
 * canonical TimeUnits via `Dateparts`; no custom TimeFrameSet is registered (Calcite's built-in
 * frames resolve the rest — see Dateparts.kt).
 */
SqlNode DateaddFunctionCall() :
{
    final Span s;
    final SqlOperator op;
    final SqlIntervalQualifier unit;
    final List<SqlNode> args;
    SqlNode e;
}
{
    (   <DATEADD> { op = org.apache.calcite.sql.fun.SqlLibraryOperators.DATEADD; }
    |   <DATEDIFF> { op = org.tatrman.translator.functions.DateOperators.INSTANCE.getDATEDIFF(); }
    |   <DATEPART>  { op = org.apache.calcite.sql.fun.SqlLibraryOperators.DATEPART; }
    )
    { s = span(); }
    <LPAREN> unit = TimeUnitOrName() {
        args = startList(NormalizeDatepart(unit));
    }
    (
        <COMMA> e = Expression(ExprContext.ACCEPT_SUB_QUERY) {
            args.add(e);
        }
    )*
    <RPAREN> {
        return op.createCall(s.end(this), args);
    }
}

/**
 * Phase 3 — T-SQL "TRY_CONVERT(type, expr [, style])" (tasks-conditional-conversion.md C3).
 *
 * The stock parser has no TRY_CONVERT production (CONVERT is handled in core and reused via the
 * post-parse ConvertRewriter, but TRY_CONVERT must be parsed here). The first argument is a data
 * type, so we parse `DataType()` first — modelled on the core MSSQL `CONVERT(type, val [, style])`
 * branch. We build a raw call to our own TRY_CONVERT operator carrying the SqlDataTypeSpec; the
 * post-parse ConvertRewriter then converts that type node into a bare string literal (mirroring how
 * CONVERT/MSSQL_CONVERT is normalised), so it never reaches sql-to-rel as a non-expression operand.
 */
SqlNode TryConvertFunctionCall() :
{
    final Span s;
    final List<SqlNode> args;
    SqlDataTypeSpec dt;
    SqlNode e;
    SqlNode style;
}
{
    <TRY_CONVERT> { s = span(); }
    <LPAREN>
    dt = DataType() { args = startList(dt); }
    <COMMA>
    e = Expression(ExprContext.ACCEPT_SUB_QUERY) { args.add(e); }
    [
        <COMMA>
        (
            style = UnsignedNumericLiteral() { args.add(style); }
        |
            <NULL> { args.add(SqlLiteral.createNull(getPos())); }
        )
    ]
    <RPAREN> {
        return org.tatrman.translator.functions.ConvertOperators.TRY_CONVERT.createCall(s.end(this), args);
    }
}

/**
 * TF-P3.S1 (G C9; contracts §4.1) — T-SQL type names in CAST / CONVERT / TRY_CONVERT, registered
 * ahead of the core types via the `dataTypeParserMethods` hook (TypeName() tries it with LOOKAHEAD(2)):
 * `NVARCHAR[(n|MAX)]`, `VARCHAR(MAX)`, `NCHAR[(n)]`, `TEXT`, `NTEXT`, `MONEY`, `SMALLMONEY`, `DATETIME`,
 * `DATETIME2[(p)]`, `SMALLDATETIME`, `BIT`, `UNIQUEIDENTIFIER`.
 *
 * No keyword tokens are added: `BIT`, `DATETIME`, `NCHAR` and `MAX` already are Calcite tokens, and the
 * rest are matched as identifiers by image (TsqlDataTypes.isIdentifierTypeName), so a column or alias
 * named `text` or `money` still parses. `VARCHAR` is claimed only when `( MAX` follows; every other
 * VARCHAR spelling stays with the core CharacterTypeName(). The semantic lookaheads read tokens from the
 * start of this production, which is also where TypeName()'s syntactic lookahead starts.
 */
SqlTypeNameSpec TsqlDataType() :
{
    final Span s;
    final String name;
    int precision = -1;
    int scale = -1;
    boolean max = false;
}
{
    (
        LOOKAHEAD({ getToken(1).kind == VARCHAR && getToken(2).kind == LPAREN && getToken(3).kind == MAX })
        <VARCHAR> { name = "VARCHAR"; }
    |
        <NCHAR> { name = "NCHAR"; }
    |
        <DATETIME> { name = "DATETIME"; }
    |
        <BIT> { name = "BIT"; }
    |
        LOOKAHEAD({ getToken(1).kind == IDENTIFIER
            && org.tatrman.translator.functions.TsqlDataTypes.isIdentifierTypeName(getToken(1).image) })
        <IDENTIFIER> { name = token.image.toUpperCase(Locale.ROOT); }
    )
    { s = span(); }
    [
        <LPAREN>
        (
            precision = UnsignedIntLiteral()
            [ <COMMA> scale = UnsignedIntLiteral() ]
        |
            <MAX> { max = true; }
        )
        <RPAREN>
    ]
    {
        return org.tatrman.translator.functions.TsqlDataTypes.spec(name, precision, scale, max, s.end(this));
    }
}
