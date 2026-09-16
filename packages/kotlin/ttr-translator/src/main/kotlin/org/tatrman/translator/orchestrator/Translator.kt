// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import org.tatrman.translator.codec.dfdsl.DfDslCodec
import org.tatrman.translator.codec.dfdsl.DfDslParseException
import org.tatrman.translator.codec.dfdsl.DfDslUnparseException
import org.tatrman.translator.codec.sql.RelToSqlUnparser
import org.tatrman.translator.codec.sql.SqlValidator
import org.tatrman.translator.codec.sql.StoreDmlUnparser
import org.tatrman.translator.codec.sql.TableHintExtractor
import org.tatrman.translator.codec.sql.TableHintSpec
import org.tatrman.translator.codec.sql.TopClauseExtractor
import org.tatrman.translator.codec.sql.ValidateResult
import org.tatrman.translator.functions.TsqlPlusLowering
import org.tatrman.translator.codec.transdsl.TransDslCodec
import org.tatrman.translator.codec.transdsl.TransDslParseException
import org.tatrman.translator.codec.transdsl.TransDslUnparseException
import org.apache.calcite.rel.RelNode
import org.slf4j.LoggerFactory
import org.tatrman.translator.detect.SchemaDecision
import org.tatrman.translator.detect.SchemaDetector
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.joiner.JoinerLogical
import org.tatrman.translator.joiner.JoinerPhysical
import org.tatrman.translator.params.ParameterBridge
import org.tatrman.translator.params.PositionalParameters
import org.tatrman.translator.params.PreparedSql
import org.tatrman.translator.params.SqlParam
import org.tatrman.translator.schema.MapToPhysical
import org.tatrman.translator.schema.MapToPhysicalResult
import org.tatrman.translator.schema.Resolve
import org.tatrman.translator.schema.Unfold
import org.tatrman.translator.schema.UnfoldResult
import org.tatrman.translator.wire.PlanNodeDecoder
import org.tatrman.translator.wire.PlanNodeEncoder

/**
 * Public-facing entry point for the query-translator library.
 *
 * Composes the per-stage building blocks into the four Translator RPC shapes
 * defined by `org.tatrman.translate.v1`:
 *
 *   - [parseToRelNode]     PARSE → TO_REL → RESOLVE → UNFOLD → EXPAND_JOINS-logical →
 *                          (MAP_TO_PHYSICAL → EXPAND_JOINS-physical when `targetSchema = DB`).
 *                          REL_NODE re-entry skips PARSE / TO_REL but runs the same stage chain.
 *   - [unparseFromRelNode] OPTIMIZE → TO_AST → UNPARSE. Rejects pre-physical plans (any
 *                          `Scan(ER, ...)` leaf) with `unparse_rejects_pre_physical` — REL_NODE
 *                          target exempt (proto bytes round-trip losslessly).
 *   - [translate]          chains the two halves
 *   - [explain]            captures per-stage artefacts
 *
 * Every front-half stage is **semantically idempotent**, so REL_NODE re-entry from a previously
 * returned `target=ER` plan safely re-runs the chain to produce the `target=DB` form (the
 * two-half pipeline pattern the validator's pass-1 / pass-2 design depends on).
 *
 * Rule #2 reminder: each public call constructs a fresh [TranslatorFramework]
 * (and thus a fresh Calcite [org.apache.calcite.tools.Planner] etc.) — the
 * library never reuses planner state across calls.
 */
class Translator(
    private val model: ModelHandle,
) {
    private val log = LoggerFactory.getLogger(Translator::class.java)

    /**
     * @param queryRefs for TransDSL only — a map of `query_ref` string → resolved canonical
     *   [PlanNode] (the caller fetches these from the metadata service first; see
     *   [org.tatrman.translator.codec.transdsl.TransDslCodec.parse]). `null` keeps the legacy
     *   placeholder behaviour. Ignored for SQL / RelNode / DataFrame-DSL sources.
     * @param parameters the typed parameter bindings carried alongside the query
     *   (`PipelineContext.parameters`). For SQL sources with a non-empty list, the `{name}`
     *   placeholders in `source` are rewritten to Calcite positional `?` via [ParameterBridge]
     *   and each `?` is pre-typed from the declared `type` via `ParameterTyper`. Empty (the
     *   default) keeps the verbatim-SQL behaviour — `{…}` is left untouched so free-SQL using
     *   JDBC escapes / literal curlies is unaffected. Ignored for RelNode / TransDSL / DF-DSL.
     */
    fun parseToRelNode(
        source: String,
        sourceLanguage: Language,
        targetSchema: SchemaCode = SchemaCode.DB,
        queryRefs: Map<String, PlanNode>? = null,
        sourceSchema: SchemaCode = SchemaCode.SCHEMA_CODE_UNSPECIFIED,
        parameters: List<SqlParam> = emptyList(),
    ): ParseResult =
        try {
            // Promote Calcite's default literal charset to UTF-8 before any Calcite class loads
            // (else Czech literals outside Latin-1 fail to encode). Idempotent; see CalciteCharset.
            org.tatrman.translator.framework.CalciteCharset
                .ensureUtf8()
            when (sourceLanguage) {
                Language.SQL -> parseSql(source, targetSchema, sourceSchema, parameters)
                Language.REL_NODE -> parseRelNodeBytes(source, targetSchema)
                Language.TRANSFORMATION_DSL -> parseTransDsl(source, queryRefs)
                Language.DATAFRAME_DSL -> parseDfDsl(source)
                Language.LANGUAGE_UNSPECIFIED,
                Language.UNRECOGNIZED,
                ->
                    ParseResult.Failure(
                        code = "language_unspecified",
                        message = "Source language must be set; got '$sourceLanguage'",
                    )
            }
        } catch (ex: Exception) {
            return ParseResult.Failure(
                code = "parse_exception",
                message = "Error parsing source: ${ex.message}",
            )
        }

    fun unparseFromRelNode(
        plan: PlanNode,
        targetLanguage: Language,
        targetDialect: SqlDialectProto,
        optimize: Boolean = true,
        parameters: List<org.tatrman.plan.v1.ParameterBinding> = emptyList(),
    ): UnparseResult {
        // Promote Calcite's default literal charset to UTF-8 before any Calcite class loads —
        // the unparse half (Optimizer / RelToSql) can be the first Calcite touch (e.g. warm-up,
        // UnparseFromRelNode RPC). Idempotent; see CalciteCharset.
        org.tatrman.translator.framework.CalciteCharset
            .ensureUtf8()
        // F-2 invariant: pre-physical plans (still containing `Scan(ER, ...)`) cannot reach the
        // unparser — the two-half pipeline expects MAP_TO_PHYSICAL to have run. REL_NODE target
        // is exempt because the proto bytes round-trip losslessly (callers using REL_NODE for
        // inspection / debugging should still be able to see ER trees).
        if (targetLanguage != Language.REL_NODE && containsErScan(plan)) {
            return UnparseResult.Failure(
                code = "unparse_rejects_pre_physical",
                message = "Plan contains `Scan(ER, ...)` nodes; run MAP_TO_PHYSICAL before unparsing",
            )
        }
        return when (targetLanguage) {
            Language.SQL -> unparseSql(plan, targetDialect, optimize, parameters)
            Language.TRANSFORMATION_DSL -> unparseTransDsl(plan)
            Language.DATAFRAME_DSL -> unparseDfDsl(plan)
            Language.REL_NODE -> unparseRelNodeBytes(plan)
            Language.LANGUAGE_UNSPECIFIED,
            Language.UNRECOGNIZED,
            ->
                UnparseResult.Failure(
                    code = "language_unspecified",
                    message = "Target language must be set; got '$targetLanguage'",
                )
        }
    }

    /** True if [plan] contains any `ScanNode` whose qname's schema_code is [SchemaCode.ER]. */
    private fun containsErScan(plan: PlanNode): Boolean =
        when (plan.nodeCase) {
            PlanNode.NodeCase.SCAN -> plan.scan.getObject().schemaCode == SchemaCode.ER
            PlanNode.NodeCase.PROJECT ->
                containsErScan(plan.project.input) ||
                    plan.project.expressionsList.any { exprContainsErScan(it.expression) }
            PlanNode.NodeCase.FILTER ->
                containsErScan(plan.filter.input) || exprContainsErScan(plan.filter.condition)
            PlanNode.NodeCase.JOIN ->
                containsErScan(plan.join.left) ||
                    containsErScan(plan.join.right) ||
                    (plan.join.hasCondition() && exprContainsErScan(plan.join.condition))
            PlanNode.NodeCase.AGGREGATE -> containsErScan(plan.aggregate.input)
            PlanNode.NodeCase.SORT -> containsErScan(plan.sort.input)
            PlanNode.NodeCase.LIMIT_OFFSET -> containsErScan(plan.limitOffset.input)
            PlanNode.NodeCase.SUBQUERY -> containsErScan(plan.subquery.subquery)
            // A Store wrapping an ER SELECT must still trip the pre-physical guard (else the write
            // would slip past MAP_TO_PHYSICAL and blow up at DML unparse).
            PlanNode.NodeCase.STORE -> containsErScan(plan.store.input)
            else -> false
        }

    /** True if [expr] embeds an expression-level subquery whose plan still carries an `Scan(ER)`. */
    private fun exprContainsErScan(expr: org.tatrman.plan.v1.Expression): Boolean =
        when (expr.exprCase) {
            org.tatrman.plan.v1.Expression.ExprCase.SUBQUERY ->
                containsErScan(expr.subquery.subquery) ||
                    expr.subquery.operandsList.any { exprContainsErScan(it) }
            org.tatrman.plan.v1.Expression.ExprCase.FUNCTION ->
                expr.function.operandsList.any { exprContainsErScan(it) }
            org.tatrman.plan.v1.Expression.ExprCase.CAST -> exprContainsErScan(expr.cast.value)
            else -> false
        }

    fun translate(
        source: String,
        sourceLanguage: Language,
        targetLanguage: Language,
        targetSchema: SchemaCode = SchemaCode.DB,
        targetDialect: SqlDialectProto = SqlDialectProto.MSSQL,
        optimize: Boolean = true,
        queryRefs: Map<String, PlanNode>? = null,
        sourceSchema: SchemaCode = SchemaCode.SCHEMA_CODE_UNSPECIFIED,
        parameters: List<SqlParam> = emptyList(),
    ): TranslateResult =
        when (val parse = parseToRelNode(source, sourceLanguage, targetSchema, queryRefs, sourceSchema, parameters)) {
            is ParseResult.Failure -> TranslateResult.Failure(parse.code, parse.message)
            is ParseResult.Success ->
                // Intentionally omits the `parameters` arg: the string-only translate RPC produces
                // SQL text for inspection, not an executable bound statement, so no positional
                // binding expansion is needed here (the gRPC unparse path passes bindings).
                when (val unparse = unparseFromRelNode(parse.plan, targetLanguage, targetDialect, optimize)) {
                    is UnparseResult.Failure -> TranslateResult.Failure(unparse.code, unparse.message)
                    is UnparseResult.Success -> TranslateResult.Success(output = unparse.output, plan = parse.plan)
                }
        }

    fun explain(
        source: String,
        sourceLanguage: Language,
        targetLanguage: Language = Language.SQL,
        targetDialect: SqlDialectProto = SqlDialectProto.MSSQL,
    ): ExplainResult {
        val stages = mutableListOf<StageArtifact>()
        val parse = parseToRelNode(source, sourceLanguage)
        stages += stage("parse_and_to_rel", parse.toString())
        if (parse is ParseResult.Failure) {
            return ExplainResult(stages = stages, finalOutput = null, finalError = parse.message)
        }
        val parseSuccess = parse as ParseResult.Success
        val unparse = unparseFromRelNode(parseSuccess.plan, targetLanguage, targetDialect)
        stages += stage("optimize_and_unparse", unparse.toString())
        return when (unparse) {
            is UnparseResult.Success -> ExplainResult(stages = stages, finalOutput = unparse.output, finalError = null)
            is UnparseResult.Failure -> ExplainResult(stages = stages, finalOutput = null, finalError = unparse.message)
        }
    }

    private fun parseSql(
        source: String,
        targetSchema: SchemaCode,
        sourceSchema: SchemaCode,
        parameters: List<SqlParam>,
    ): ParseResult {
        // Calcite parses a single statement and rejects a trailing terminator (`Encountered ";"`).
        // Authored pattern queries and hand-written SQL routinely end with one, so strip a single
        // trailing `;` (plus surrounding whitespace) up front — before the rails below and schema
        // detection / validation each re-parse the SQL. Only the final non-whitespace `;` is removed,
        // so a `;` inside a string literal is left intact.
        val terminated = source.trimEnd().removeSuffix(";").trimEnd()
        // NX-A.S4 — MSSQL pre-parse rails, BEFORE anything parses the SQL (schema detection AND
        // validation must both see a form the stock parser accepts):
        //  1. TOP: `SELECT [DISTINCT] TOP n …` → `… FETCH FIRST n ROWS ONLY`. The row-limit then
        //     flows through the existing Sort/LimitOffset path and the MSSQL dialect renders it back.
        //  2. Table hints: pull `WITH (NOLOCK)` out (a post-alias position the grammar can't read).
        //     `hintsByTable` rides through to PlanNodeEncoder, which stamps the hints onto the
        //     matching scan; the MSSQL unparse re-emits them. CTEs / string literals are not matched.
        val extractedHints = TableHintExtractor.extract(TopClauseExtractor.rewrite(terminated))
        val preSource = extractedHints.cleanedSql
        val hintsByTable = extractedHints.byTable
        // Parameter rail: when typed bindings are supplied, rewrite `{name}` → Calcite positional
        // `?` BEFORE anything parses the SQL (schema detection AND validation must see `?`, never
        // the raw `{name}` token — the latter is what produced the opaque "Incorrect syntax near
        // LIKE" parse errors for pattern queries). The bridge throws on a `{name}` with no matching
        // binding; surface that as a precise `parameter_unknown` rather than a generic parse error.
        // No bindings → leave `source` verbatim (free-SQL with JDBC escapes / literal `{` is
        // unaffected, since the bridge would otherwise treat any `{…}` as a placeholder).
        val prepared: PreparedSql? =
            if (parameters.isNotEmpty()) {
                try {
                    ParameterBridge.prepareSqlForCalcite(preSource, parameters)
                } catch (ex: IllegalArgumentException) {
                    return ParseResult.Failure(
                        code = "parameter_unknown",
                        message = ex.message ?: "SQL references an undeclared parameter",
                    )
                }
            } else {
                null
            }
        val effectiveSql = prepared?.sql ?: preSource
        // Calcite's default schema for resolving unqualified identifiers.
        //   - explicit `sourceSchema`               → honour it (the caller knows the catalog).
        //   - UNSPECIFIED + identifiers resolve to a
        //     single schema                         → derive it from the query content, so a
        //     caller that built ER-level SQL but forgot to set `source_schema` doesn't get a
        //     spurious "object not found" against `db`. This mirrors what query-runner does up
        //     front via the DetectSourceSchema RPC, run inline here so any direct
        //     ParseToRelNode / Translate caller self-heals.
        //   - UNSPECIFIED + inconclusive            → fall back to `targetSchema`, preserving
        //     query-runner's single-knob two-pass (pass 1 sends target=ER ⇒ er catalog; pass 2
        //     re-enters with REL_NODE which doesn't take this code path).
        // When the caller left `source_schema` UNSPECIFIED we try to derive the catalog from the
        // query's own tables. `detected == null` means detection was inconclusive (AMBIGUOUS /
        // MIXED / UNKNOWN / unparseable) — we then guess via `targetSchema` but flag the guess as
        // auto-correctable below, since a validation failure may just mean we picked wrong.
        val detected: SchemaCode? =
            if (sourceSchema == SchemaCode.SCHEMA_CODE_UNSPECIFIED) detectCatalogSchema(effectiveSql) else null
        val catalogSchema =
            when {
                sourceSchema != SchemaCode.SCHEMA_CODE_UNSPECIFIED -> sourceSchema
                detected != null -> detected
                else -> targetSchema
            }
        val autoCorrectEligible = sourceSchema == SchemaCode.SCHEMA_CODE_UNSPECIFIED && detected == null
        log.debug("Detected catalog schema: $catalogSchema for source: $effectiveSql")

        // A TranslatorFramework is single-use (its planner/validator/cluster cannot be shared
        // across compilations). The typed-parameter fallback below needs a second compilation, so
        // build a fresh framework per validation attempt rather than reusing one.
        fun newFramework(schema: SchemaCode): TranslatorFramework =
            when (schema) {
                SchemaCode.ER -> TranslatorFramework(model, SchemaCode.ER, "entity")
                SchemaCode.OBJ -> TranslatorFramework(model, SchemaCode.OBJ, "query")
                else -> TranslatorFramework(model) // DB / UNSPECIFIED → db.dbo defaults
            }

        // Validate `effectiveSql` against one candidate [schema]'s catalog, with the typed-parameter
        // fallback: a bare `?` is untypeable in contexts Calcite can't infer — chiefly inside `||` /
        // `CONCAT`, whose operand-type inference is null, so `KOD_STR LIKE {x} || '%'` fails with
        // "Illegal use of dynamic parameter". Retry once with each placeholder wrapped as
        // `CAST(? AS <type>)` from its declared type; ParameterTyper unwraps the synthetic cast
        // post-validation so the executed SQL stays cast-free. Only attempt when typed parameters
        // were supplied, and keep the ORIGINAL error if the typed retry also fails — it diagnoses
        // the author's SQL, not the crutch.
        fun validateAgainst(schema: SchemaCode): Pair<ValidateResult, TranslatorFramework> {
            val framework = newFramework(schema)
            return when (val r = SqlValidator.validateAndConvert(framework.newPlanner(), effectiveSql)) {
                is ValidateResult.Success -> r to framework
                is ValidateResult.Failure ->
                    if (prepared != null && prepared.parameterOrder.isNotEmpty()) {
                        val typedSql = ParameterBridge.prepareSqlForCalcite(preSource, parameters, typed = true).sql
                        val retryFramework = newFramework(schema)
                        when (val retry = SqlValidator.validateAndConvert(retryFramework.newPlanner(), typedSql)) {
                            is ValidateResult.Success -> retry to retryFramework
                            is ValidateResult.Failure -> r to framework
                        }
                    } else {
                        r to framework
                    }
            }
        }

        var (validated, validatedFramework) = validateAgainst(catalogSchema)
        // Schema auto-correction. When the caller left `source_schema` UNSPECIFIED and content
        // detection was inconclusive, we validated against the `targetSchema` guess; a failure there
        // may just mean the source's tables live in the OTHER catalog (e.g. query-runner's pass-1
        // sends target=ER but the SQL reads db tables). Retry against the remaining considered
        // schema(s) and adopt the first that validates — this completes the detection the parser
        // couldn't. The original error is kept when none succeed, so genuine failures still diagnose
        // the author's SQL rather than reporting a misleading wrong-catalog "object not found".
        if (validated is ValidateResult.Failure && autoCorrectEligible) {
            for (alt in SchemaDetector.CONSIDERED.filter { it != catalogSchema }) {
                val (altResult, altFramework) = validateAgainst(alt)
                if (altResult is ValidateResult.Success) {
                    log.info(
                        "source_schema was inconclusive; auto-corrected catalog to {} after {} validation failed",
                        alt,
                        catalogSchema,
                    )
                    validated = altResult
                    validatedFramework = altFramework
                    break
                }
            }
        }
        log.debug("Detected framework schema: $validatedFramework for source: $effectiveSql")
        return when (val v = validated) {
            is ValidateResult.Failure -> ParseResult.Failure(v.error.code, v.error.message)
            is ValidateResult.Success ->
                runFrontHalfStages(v.rel, validatedFramework, targetSchema, prepared, hintsByTable = hintsByTable)
        }
    }

    /**
     * Derive the catalog schema for [source] from the schemas its own table identifiers belong
     * to (used only when the caller left `source_schema` UNSPECIFIED). Reuses [SchemaDetector] —
     * the same logic query-runner runs via the DetectSourceSchema RPC — so the two stay in sync.
     *
     * Returns the schema only when detection is unambiguous (`AUTODETECTED` — a single candidate
     * schema with no stated schema to confirm/correct). For AMBIGUOUS / UNKNOWN / MIXED /
     * NOT_APPLICABLE it returns `null`, leaving the caller's `targetSchema` fallback to govern —
     * those cases surface their own diagnostics through the DetectSourceSchema path / validator.
     */
    private fun detectCatalogSchema(source: String): SchemaCode? {
        val result =
            SchemaDetector.detect(
                source = source,
                sourceLanguage = Language.SQL,
                statedSchema = SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                model = model,
            )
        return if (result.decision == SchemaDecision.AUTODETECTED) result.effectiveSchema else null
    }

    /**
     * Run the front-half pipeline stages on a [RelNode] and return the encoded result.
     *
     * Stage chain (execution order):
     *   1. **RESOLVE** — always. Field-name attachment + parameter type pre-supply.
     *   2. **Encode** — RelNode → PlanNode. After this point everything is wire-form.
     *   3. **UNFOLD** — always. Inline `obj.query.*` saved-query bodies.
     *      Blocking failures: `query_reference_cycle`, `saved_query_output_mismatch`.
     *   4. **EXPAND_JOINS-logical** — always. Insert relation-based join conditions between
     *       entity scans; warnings on Cartesian fall-through and ambiguity.
     *   5. **MAP_TO_PHYSICAL** — only when `targetSchema = DB` (or UNSPECIFIED, defaulted to DB).
     *       Rewrite entity Scans to physical TableScans.
     *       Blocking failures: `entity_unmapped`, `mapping_kind_not_supported`.
     *   6. **EXPAND_JOINS-physical** — only when `targetSchema = DB`. Insert FK-based conditions
     *       between physical TableScans; "don't double-join" leaves logical-stage conditions intact.
     *
     * When `targetSchema = ER`, stages 5 and 6 are skipped; the returned plan keeps its ER
     * scans for the two-half pipeline (call-1 produces ER, validator pass 1 runs against it,
     * call-2 re-enters with REL_NODE source and `targetSchema = DB` to finish).
     *
     * Every stage is **semantically idempotent**, so REL_NODE re-entry re-runs the chain safely.
     *
     * Warnings from JoinerLogical / JoinerPhysical are currently dropped on the floor — the
     * `ParseResult` shape doesn't carry a `messages` slot. Surfacing them through the Translator
     * service's `ResponseMessage messages = 99` is a service-side follow-up; the data is
     * available, just not plumbed.
     */
    private fun runFrontHalfStages(
        rel: RelNode,
        framework: TranslatorFramework,
        targetSchema: SchemaCode,
        preparedSql: PreparedSql? = null,
        relNodeNames: Map<Int, String> = emptyMap(),
        hintsByTable: Map<String, List<TableHintSpec>> = emptyMap(),
    ): ParseResult =
        try {
            // NX-A: decorrelate correlated `[NOT] EXISTS` / `IN` sub-queries before encode. A
            // correlated sub-query reaches this seam as a `RexSubQuery` bearing a `RexFieldAccess`
            // over a `RexCorrelVariable`, which has no `plan.v1` form (ai-models#27). No-op unless a
            // correlated sub-query is present, so uncorrelated sub-queries keep their
            // `SubqueryExpression` encoding and REL_NODE re-entry stays byte-stable.
            val decorrelated = SubqueryNormalizer.apply(rel, framework)

            // TF-P2.S1 (contracts §3.4) — pass 2 of T-SQL `+`/`-`: with operand types known, lower each
            // TsqlPlus/TsqlMinus call to `||` (CONCAT), `DATEADD(DAY, n, d)` or plain PLUS/MINUS. The wire
            // carries none of the T-SQL operators, so this is a no-op on REL_NODE re-entry.
            val lowered = TsqlPlusLowering.apply(decorrelated)

            // 1. RESOLVE on RelNode. When the SQL carried parameters, `preparedSql` lets RESOLVE
            //    pre-type each `?` (RexDynamicParam) from the declared parameter type via
            //    ParameterTyper; null (free-SQL / RelNode re-entry) is a no-op for typing.
            val resolved = Resolve.apply(lowered, framework, preparedSql)

            // 1b. EXPAND SEARCH → OR/AND of comparisons. `SqlToRelConverter` folds an `IN`-list of
            //     literals / comparison ranges into a `SEARCH($ref, Sarg[…])`, whose `Sarg` value
            //     the `plan.v1` wire format can't represent — the encoder would throw
            //     `Sarg cannot be cast to Number`. No-op unless a SEARCH is present.
            val expanded = SearchExpander.apply(resolved)

            // 2. Encode once. Restore each `?`'s original `{name}` on its wire ParameterRef so the
            //    unparse side can bind by name (a name used N times must be bound at all N positions
            //    — see PositionalParameters). Names come from the SQL parse (`preparedSql`), or, on
            //    REL_NODE re-entry, from the incoming plan (`relNodeNames`) so they survive the
            //    round-trip rather than reverting to the positional `?N` fallback.
            val parameterNames: Map<Int, String> =
                preparedSql
                    ?.parameterOrder
                    ?.withIndex()
                    ?.associate { (i, name) -> i to name }
                    ?: relNodeNames
            var plan = PlanNodeEncoder.encode(expanded, parameterNames, hintsByTable)

            // 3. UNFOLD.
            plan =
                when (val unfolded = Unfold.apply(plan, model)) {
                    is UnfoldResult.Success -> unfolded.plan
                    is UnfoldResult.Error -> return ParseResult.Failure(unfolded.code, unfolded.message)
                }

            // 4. EXPAND_JOINS-logical — always. Warnings collected but not surfaced (see KDoc).
            plan = JoinerLogical.apply(plan, model).plan

            // 5 + 6. Physical stages — gated on targetSchema.
            if (targetSchema == SchemaCode.DB || targetSchema == SchemaCode.SCHEMA_CODE_UNSPECIFIED) {
                plan =
                    when (val mapped = MapToPhysical.apply(plan, model)) {
                        is MapToPhysicalResult.Success -> mapped.plan
                        is MapToPhysicalResult.Failure -> return ParseResult.Failure(mapped.code, mapped.message)
                    }
                plan = JoinerPhysical.apply(plan, model).plan
            }

            ParseResult.Success(plan = plan)
        } catch (ex: Exception) {
            // The front-half stage chain (RESOLVE → encode → UNFOLD → EXPAND_JOINS-logical →
            // MAP_TO_PHYSICAL → EXPAND_JOINS-physical) previously let any unexpected exception
            // escape the gRPC handler, surfacing to callers (query-runner / golem) as a bare
            // `UNKNOWN` with no description and nothing logged. Mirror the unparseSql /
            // parseTransDsl / parseDfDsl paths: log the cause WITH stack trace so the throwing
            // stage is visible, and return a structured Failure so the worker — and ultimately
            // the user — get a real diagnostic instead of a cancelled stream.
            log.error("Front-half pipeline failed (targetSchema={})", targetSchema, ex)
            ParseResult.Failure(
                code = "parse_pipeline_failed",
                message = ex.message ?: ex.javaClass.simpleName,
            )
        }

    private fun unparseSql(
        plan: PlanNode,
        targetDialect: SqlDialectProto,
        optimize: Boolean,
        parameters: List<org.tatrman.plan.v1.ParameterBinding> = emptyList(),
    ): UnparseResult =
        try {
            val framework = TranslatorFramework(model)
            // A StoreNode is a write root: decode/optimize/unparse only its `input` (the RHS read plan)
            // through the normal read path, then assemble the DML around that SELECT (StoreDmlUnparser).
            val readPlan = if (plan.nodeCase == PlanNode.NodeCase.STORE) plan.store.input else plan
            val rel = PlanNodeDecoder.decode(readPlan, framework)
            val optimized = if (optimize) Optimizer.optimize(rel, targetDialect) else rel
            val innerSql = RelToSqlUnparser.unparseWithParams(optimized, targetDialect)
            val unparsed =
                if (plan.nodeCase == PlanNode.NodeCase.STORE) {
                    val columns = optimized.rowType.fieldNames.toList()
                    StoreDmlUnparser.assemble(plan.store, innerSql, columns, targetDialect)
                } else {
                    innerSql
                }
            // Expand the named bindings into one-per-`?` positional order (repeats included), using
            // the true `?`-appearance order Calcite reports. The conversion lives in the shared lib
            // so every worker binds identically; see PositionalParameters.
            val positional =
                PositionalParameters.positional(
                    order = unparsed.dynamicParamOrder,
                    namesByIndex = PositionalParameters.namesByIndex(plan),
                    bindings = parameters,
                )
            UnparseResult.Success(output = unparsed.sql, parameters = positional)
        } catch (ex: Exception) {
            // The SQL unparse path (Calcite decode → optimize → RelToSql for the
            // target dialect) previously let exceptions escape the gRPC handler,
            // surfacing to callers as a bare UNKNOWN with no detail (and nothing
            // logged). Mirror the TransDSL/DfDSL paths: log the cause and return a
            // structured Failure so the worker — and ultimately the user — see why.
            log.error("SQL unparse failed (dialect={}, optimize={})", targetDialect, optimize, ex)
            UnparseResult.Failure(
                code = "sql_unparse_failed",
                message = ex.message ?: ex.javaClass.simpleName,
            )
        }

    private fun parseTransDsl(
        source: String,
        queryRefs: Map<String, PlanNode>?,
    ): ParseResult =
        try {
            ParseResult.Success(plan = TransDslCodec.parseJson(source, queryRefs))
        } catch (ex: TransDslParseException) {
            ParseResult.Failure(code = ex.code, message = ex.message ?: "TransDSL parse failed")
        } catch (ex: Exception) {
            ParseResult.Failure(code = "transdsl_parse_failed", message = ex.message ?: "TransDSL JSON parse failed")
        }

    private fun unparseTransDsl(plan: PlanNode): UnparseResult =
        try {
            UnparseResult.Success(output = TransDslCodec.unparseJson(plan))
        } catch (ex: TransDslUnparseException) {
            UnparseResult.Failure(code = ex.code, message = ex.message ?: "TransDSL unparse failed")
        }

    private fun parseDfDsl(source: String): ParseResult =
        try {
            ParseResult.Success(plan = DfDslCodec.parseJson(source))
        } catch (ex: DfDslParseException) {
            ParseResult.Failure(code = ex.code, message = ex.message ?: "DataFrame DSL parse failed")
        } catch (ex: Exception) {
            ParseResult.Failure(code = "dfdsl_parse_failed", message = ex.message ?: "DataFrame DSL JSON parse failed")
        }

    private fun unparseDfDsl(plan: PlanNode): UnparseResult =
        try {
            UnparseResult.Success(output = DfDslCodec.unparseJson(plan))
        } catch (ex: DfDslUnparseException) {
            UnparseResult.Failure(code = ex.code, message = ex.message ?: "DataFrame DSL unparse failed")
        }

    private fun unparseRelNodeBytes(plan: PlanNode): UnparseResult {
        // Mirror the Latin-1 wire convention used by parseRelNodeBytes —
        // proto bytes encoded as a string so the gRPC `output: string` slot
        // round-trips losslessly.
        val text = String(plan.toByteArray(), Charsets.ISO_8859_1)
        return UnparseResult.Success(output = text)
    }

    /**
     * REL_NODE re-entry: skip PARSE / TO_REL, decode the proto bytes directly to a [RelNode],
     * then run the front-half stages (idempotent) per the two-half pipeline pattern.
     *
     * Two distinct failure modes the caller may see:
     *   - `rel_node_decode_failed` — malformed proto bytes.
     *   - `rel_node_schema_resolution_failed` — proto parsed, but a `Scan` / `TableScan`
     *     references a qname the current model's SchemaPlus tree doesn't expose.
     */
    private fun parseRelNodeBytes(
        sourceText: String,
        targetSchema: SchemaCode,
    ): ParseResult {
        val plan =
            try {
                // For REL_NODE source the caller passed the proto bytes encoded as a Latin-1
                // string (matches the Translator service's wire convention).
                PlanNode.parseFrom(sourceText.toByteArray(Charsets.ISO_8859_1))
            } catch (ex: Exception) {
                return ParseResult.Failure(
                    code = "rel_node_decode_failed",
                    message = ex.message ?: "could not decode REL_NODE source",
                )
            }
        val framework = TranslatorFramework(model)
        val rel =
            try {
                PlanNodeDecoder.decode(plan, framework)
            } catch (ex: Exception) {
                return ParseResult.Failure(
                    code = "rel_node_schema_resolution_failed",
                    message = ex.message ?: "REL_NODE source references unresolved schema",
                )
            }
        // Preserve the original `{name}` parameter names carried on the incoming plan's
        // ParameterRefs so the re-encode doesn't revert them to the positional `?N` fallback
        // (the names are what the unparse side binds by — see PositionalParameters).
        return runFrontHalfStages(
            rel,
            framework,
            targetSchema,
            relNodeNames = PositionalParameters.namesByIndex(plan),
        )
    }

    private fun stage(
        code: String,
        summary: String,
    ): StageArtifact = StageArtifact(code, summary)
}

sealed interface ParseResult {
    data class Success(
        val plan: PlanNode,
    ) : ParseResult

    data class Failure(
        val code: String,
        val message: String,
    ) : ParseResult
}

sealed interface UnparseResult {
    data class Success(
        val output: String,
        /**
         * For SQL targets carrying parameters: the bindings in **positional** order — one entry per
         * `?` in [output], with repeated names expanded (see [org.tatrman.translator.params.PositionalParameters]).
         * The worker binds these 1:1 to JDBC positions. Empty for non-SQL targets, param-less
         * queries, or when no bindings were supplied to unparse.
         */
        val parameters: List<org.tatrman.plan.v1.ParameterBinding> = emptyList(),
    ) : UnparseResult

    data class Failure(
        val code: String,
        val message: String,
    ) : UnparseResult
}

sealed interface TranslateResult {
    data class Success(
        val output: String,
        val plan: PlanNode,
    ) : TranslateResult

    data class Failure(
        val code: String,
        val message: String,
    ) : TranslateResult
}

data class ExplainResult(
    val stages: List<StageArtifact>,
    val finalOutput: String?,
    val finalError: String?,
)

data class StageArtifact(
    val code: String,
    val summary: String,
)
