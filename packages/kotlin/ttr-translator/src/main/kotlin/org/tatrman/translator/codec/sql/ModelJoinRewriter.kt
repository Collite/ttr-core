// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import org.apache.calcite.sql.JoinConditionType
import org.apache.calcite.sql.JoinType
import org.apache.calcite.sql.SqlBasicCall
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlJoin
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlLiteral
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.dialect.CalciteSqlDialect
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.util.SqlShuttle
import org.apache.calcite.sql.validate.SqlValidatorUtil
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.joiner.JoinPolicy
import org.tatrman.translator.joiner.JoinPolicy.SideRef
import org.tatrman.translator.joiner.JoinPolicy.Verdict
import java.util.Locale

/**
 * MJ (model joins) — the SQL carrier. Runs on the parsed `SqlNode` before validation (the slot
 * `ConvertOperators` / `StringAggRewriter` / `TsqlArithmeticShuttle` use) and conditions every **bare**
 * join between ER entities from the model's declared relations, so `FROM kumulovaný_prodej JOIN
 * dodací_místo JOIN zákazník JOIN produkt` — the way the LLM lane writes it — validates instead of dying
 * in `SqlValidatorImpl.validateJoin` with `joinRequiresCondition` (design F1).
 *
 * Bare = `INNER`/`LEFT`/`RIGHT`/`FULL` with no `ON`/`USING` and not `NATURAL` — exactly the set Calcite
 * rejects. The comma list and `CROSS JOIN` validate as they are (Calcite turns them into a `TRUE`-conditioned
 * `LogicalJoin`) and stay with the wire carrier [org.tatrman.translator.joiner.JoinerLogical], which is what
 * conditioned them before MJ — leaving them alone keeps the pre-MJ plan bytes for the comma form identical
 * (P0·S1.3 golden; an authored `ON` encodes typed refs, the wire carrier untyped ones). Explicit `ON`/`USING`,
 * `NATURAL` and the semi/anti shapes pass through untouched.
 *
 * Post-order: children are rewritten first (a nested `kp JOIN (dm JOIN z)` conditions the inner join before
 * the outer one is decided), and each join is decided by [JoinPolicy.resolve] against the **whole** entity
 * set of each side (⚑MJ-2) — `((kp ⋈ dm) ⋈ z)` matches `z` against `{kp, dm}`, so the chain resolves
 * through `dodací_místo → zákazník`. Handles are the SQL aliases the validator will scope
 * ([SqlValidatorUtil.alias]); the synthesised condition is `<lAlias>.<lAttr> = <rAlias>.<rAttr>`, `AND`-ed
 * over every pair of the relation (⚑MJ-7). Any other verdict → `ON TRUE`: the encoder drops an always-true
 * condition and the wire carrier reports the join (NoRelation / Ambiguous) — this rewriter records
 * **nothing**, so the two-half pipeline and REL_NODE re-entry never see duplicate diagnostics
 * (architecture §2.2).
 *
 * A FROM item that is not an ER entity of [namespace] (a derived table, a DB table, `VALUES`) contributes
 * no entity to its side; the entity side alone then resolves nothing → `ON TRUE`. Never an error from here.
 *
 * Fresh instance per validation attempt (a `SqlShuttle` is cheap and not thread-safe to share);
 * [rewrittenJoins] tells the caller whether anything was touched (the `explain` `model_joins` stage).
 */
class ModelJoinRewriter(
    private val model: ModelHandle,
    private val namespace: String,
) : SqlShuttle() {
    /** Number of bare joins this instance rewrote (conditioned or `ON TRUE`). */
    var rewrittenJoins: Int = 0
        private set

    /**
     * The whole statement after the last top-level `accept`, rendered as one line of SQL, when at least one
     * join was touched; null otherwise. "What the engine actually joined" — the `explain` stage `model_joins`
     * and the DEBUG log read it; available whether or not validation succeeds afterwards.
     */
    var lastRewrittenSql: String? = null
        private set

    /** Depth of nested `visit(SqlCall)` frames — the outermost one is the statement root. */
    private var depth: Int = 0

    /** Case-insensitive (`Lex.MYSQL_ANSI`) lookup of the ER entities in [namespace] by bare name. */
    private val entitiesByName: Map<String, QualifiedName> by lazy {
        model
            .entities(SchemaCode.ER, namespace)
            .keys
            .associateBy { it.name.lowercase(Locale.ROOT) }
    }

    override fun visit(call: SqlCall): SqlNode? {
        depth++
        try {
            val result = visitCall(call)
            if (depth == 1 && rewrittenJoins > 0 && result != null) lastRewrittenSql = render(result)
            return result
        } finally {
            depth--
        }
    }

    private fun visitCall(call: SqlCall): SqlNode? {
        // Children first — SqlShuttle rebuilds the call through its operator (SqlJoin.OPERATOR.createCall
        // keeps the parser position) only when an operand changed.
        val visited = super.visit(call) ?: return null
        val join = visited as? SqlJoin ?: return visited
        if (!isBare(join)) return visited
        val verdict = JoinPolicy.resolve(model, collectEntityRefs(join.left), collectEntityRefs(join.right))
        val pos = join.parserPosition
        val condition =
            when (verdict) {
                is Verdict.Resolved -> condition(verdict, pos)
                else -> SqlLiteral.createBoolean(true, pos)
            }
        rewrittenJoins++
        return SqlJoin(
            pos,
            join.left,
            join.isNaturalNode,
            join.joinTypeNode,
            join.right,
            SqlLiteral.createSymbol(JoinConditionType.ON, pos),
            condition,
        )
    }

    private fun isBare(join: SqlJoin): Boolean =
        !join.isNatural &&
            join.conditionType == JoinConditionType.NONE &&
            join.joinType in REWRITTEN_JOIN_TYPES

    /**
     * The ER entities under a FROM subtree, in source order, each with the alias the validator will
     * register for it: a plain identifier (`x`, `entity.x`, `er.entity.x`), an `AS` over one, or the union of
     * a nested join's sides. Anything else (derived table, `VALUES`, a DB table, …) contributes nothing.
     */
    internal fun collectEntityRefs(node: SqlNode?): List<SideRef<String>> =
        when {
            node == null -> emptyList()
            node is SqlIdentifier ->
                resolveEntity(node)?.let { listOf(SideRef(it, SqlValidatorUtil.alias(node) ?: node.names.last())) }
                    ?: emptyList()
            node is SqlJoin -> collectEntityRefs(node.left) + collectEntityRefs(node.right)
            node is SqlBasicCall && node.kind == SqlKind.AS -> {
                val target = node.operand<SqlNode>(0) as? SqlIdentifier
                val entity = target?.let { resolveEntity(it) }
                val alias = SqlValidatorUtil.alias(node)
                if (entity != null && alias != null) listOf(SideRef(entity, alias)) else emptyList()
            }
            else -> emptyList()
        }

    /** `x`, `entity.x` or `er.entity.x` → the entity qname; anything else (other namespace, other catalog, unknown) → null. */
    internal fun resolveEntity(id: SqlIdentifier): QualifiedName? {
        val names = id.names
        val qualifiersMatch =
            when (names.size) {
                1 -> true
                2 -> names[0].equals(namespace, ignoreCase = true)
                3 -> names[0].equals(ER_CATALOG, ignoreCase = true) && names[1].equals(namespace, ignoreCase = true)
                else -> false
            }
        if (!qualifiersMatch) return null
        return entitiesByName[names.last().lowercase(Locale.ROOT)]
    }

    private fun condition(
        verdict: Verdict.Resolved<String>,
        pos: SqlParserPos,
    ): SqlNode {
        val equalities =
            verdict.pairs.map { (leftAttr, rightAttr) ->
                SqlStdOperatorTable.EQUALS.createCall(
                    pos,
                    SqlIdentifier(listOf(verdict.left.handle, leftAttr), pos),
                    SqlIdentifier(listOf(verdict.right.handle, rightAttr), pos),
                )
            }
        return if (equalities.size == 1) equalities.single() else SqlStdOperatorTable.AND.createCall(pos, equalities)
    }

    private fun render(node: SqlNode): String =
        node
            .toSqlString(CalciteSqlDialect.DEFAULT)
            .sql
            .replace(WHITESPACE, " ")
            .trim()

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Calcite's catalog token for the ER schema (`TranslatorFramework.schemaCodeToToken(ER)`). */
        private const val ER_CATALOG = "er"

        /** The join types Calcite refuses without a condition (`RESOURCE.joinRequiresCondition`). */
        private val REWRITTEN_JOIN_TYPES = setOf(JoinType.INNER, JoinType.LEFT, JoinType.RIGHT, JoinType.FULL)
    }
}
