// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.ast

/**
 * The TTR-P canonical AST (Stage 1.1). Every node carries a [SourceLocation] with
 * the repo's ANTLR-style convention; the edit synthesizer and diagnostics rely on
 * accurate spans. Cross-references ([DottedRef], [Qname]) are opaque strings here —
 * resolution is Stage 1.3 (D-b position typing).
 *
 * Expression-valued positions ([ExprArg], [AssignEntry]) hold the one PL expression
 * IR ([org.tatrman.ttrp.expr.Expression]); a dotted reference in expression position
 * folds to a `ColumnRef` (Stage 1.2). [DottedRef] survives only as a [ChainElem]
 * (variable | node.port | qname) — that position typing is a Stage 1.3 question (D-b).
 */
sealed interface TtrpNode {
    val location: SourceLocation
}

/** A top-level statement; carries attached comment trivia (C2-f). */
sealed interface Statement : TtrpNode {
    val leadingTrivia: List<Trivia>
    val trailingTrivia: List<Trivia>
}

data class TtrpDocument(
    val statements: List<Statement>,
    override val location: SourceLocation,
) : TtrpNode

data class UsesWorld(
    val world: String,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement

data class ImportDecl(
    val qname: Qname,
    val wildcard: Boolean,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement

/** Program-level `def schema <name> { col: type, … }` (D-c, Stage 1.3 — program schema home). */
data class SchemaDecl(
    val name: String,
    val columns: List<SchemaColumn>,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement

/**
 * A runtime-param declaration (PL-P2.S1, F-4-i): `param <name>: <type> [= <default>]`.
 * [type] is a scalar type token (`string|int|decimal|date|datetime|bool`, validated by
 * TtrpChecks). [default] is null ⇒ the param is REQUIRED (a value must be supplied at
 * trigger time); a [ParamDefault.Builtin] (`@run-date`) or [ParamDefault.Literal] ⇒
 * defaulted-and-optional. Params are DECLARATIONS, not values — the bundle stays a pure
 * function of resolved inputs; values arrive at trigger time via the envelope (F-4-i).
 */
data class ParamDecl(
    val name: String,
    val type: String,
    val typeLocation: SourceLocation,
    val default: ParamDefault?,
    override val location: SourceLocation,
    val nameLocation: SourceLocation = location,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement {
    val required: Boolean get() = default == null
}

/** A [ParamDecl] default value — a `@builtin` (trigger-time) or a scalar literal. */
sealed interface ParamDefault : TtrpNode {
    /** A `@builtin` default (the `@` stripped): `run-date` in v1. Manifest form re-adds `@`. */
    data class Builtin(
        val name: String,
        override val location: SourceLocation,
    ) : ParamDefault

    /** A scalar literal default (rendered verbatim into the manifest `default`). */
    data class Literal(
        val text: String,
        override val location: SourceLocation,
    ) : ParamDefault
}

/** Only ever parsed to name the S12 rejection (walker → TTRP-PRS-002). */
data class ProgramHeader(
    val name: String,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement

data class Assignment(
    val target: String,
    val targetLocation: SourceLocation,
    val chain: Chain,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement

data class ChainStmt(
    val chain: Chain,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
    /**
     * Not authored: the program-level `<container>.<port> -> display(<kind>)` the checker adds for a
     * TTR-B action sentence (AG B4); its spans point into the fragment. Text-rewriting editor features
     * skip it.
     */
    val synthesized: Boolean = false,
) : Statement

/** MD cubelet-statement operator (contracts §1.2, D20–D24): `=` · `:=` · `+=` · `-=`. */
enum class CubeletOp { ASSIGN, MATERIALIZE, MERGE, DELETE }

/** LHS of a [CubeletStmt] — a slice path ([Path]) or a bare-identifier target ([Name]) (R24). */
sealed interface CubeletLhs {
    val location: SourceLocation

    data class Path(
        val path: org.tatrman.ttrp.expr.MdPath,
        override val location: SourceLocation,
    ) : CubeletLhs

    data class Name(
        val name: String,
        override val location: SourceLocation,
    ) : CubeletLhs
}

/** A `with { key: value, … }` clause (contracts §1.2 `withClause`); keys validated in S5C (R27). */
data class MdWithClause(
    val entries: List<MdWithEntry>,
    override val location: SourceLocation,
) : TtrpNode

data class MdWithEntry(
    val key: String,
    val value: String,
    override val location: SourceLocation,
) : TtrpNode

/**
 * An MD cubelet statement (contracts §1.2, D20–D24): `<lhs> (= | := | += | -=) <expr> [with {…}]`.
 * The parser is mechanical; dispatch between variable / cubelet / slice semantics is S5C (R24).
 */
data class CubeletStmt(
    val lhs: CubeletLhs,
    val op: CubeletOp,
    val rhs: org.tatrman.ttrp.expr.Expression,
    val withClause: MdWithClause?,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement

enum class ControlKind { FS, SS, FF }

/** `b after a` (FS) · `a with b` (SS) · `a finishes with b` (FF, reserved). */
data class ControlDep(
    val kind: ControlKind,
    val subject: String,
    val reference: String,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement

data class ControlBlock(
    val deps: List<ControlDep>,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement

data class ContainerDecl(
    val name: String,
    val ports: List<PortDecl>,
    val target: Qname,
    val body: ContainerBody,
    override val location: SourceLocation,
    /** The name identifier's own span (the token after `container`) — rename edits this, not the keyword. */
    val nameLocation: SourceLocation = location,
    /**
     * PL-P2.S1 (F-4-iv): the source island this container runs on failure of (the `on failure
     * of <island>` attribute), or null for an ordinary happy-path island. An on-failure island
     * is excluded from wave levelling and attached as an error edge (manifest `island.onFailureOf`).
     */
    val onFailureOf: String? = null,
    val onFailureOfLocation: SourceLocation? = null,
    /** PL-P2.S1 (F-4-iv γ): `absorbs` was written on the on-failure clause — reserved (→ TTRP-FAIL-003). */
    val onFailureAbsorbs: Boolean = false,
    /** PL-P2.S1 (F-4-ii): manifest-declared per-island retry count (`retries N`), or null. */
    val retries: Int? = null,
    val retriesLocation: SourceLocation? = null,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement

enum class PortKind { IN, OUT, ERR }

data class PortDecl(
    val kind: PortKind,
    val name: String,
    override val location: SourceLocation,
    /**
     * Not authored: added by the checker for a TTR-B action sentence (AG B4) — the OUT port its rows
     * leave the container by. Editor features that rewrite text (rename, edits) skip it.
     */
    val synthesized: Boolean = false,
) : TtrpNode

sealed interface ContainerBody : TtrpNode

data class FlowBody(
    val statements: List<Statement>,
    override val location: SourceLocation,
) : ContainerBody

/**
 * A tagged-block container body (C3-g). [sourceText] is the interior VERBATIM —
 * the raw bytes between the tag line's newline and the closing `"""`, no dedent, no
 * trim (C2-f). [tag] is the dialect marker (`sql` | `pandas` | `ttrb`).
 *
 * [decomposition] is the Phase-6 clause→node lowering: the dialect parser lowers the
 * interior into canonical TTR-P statements (C2-a-β) — the SAME AST canonical authoring
 * produces, so resolution (`TtrpChecker`) and graph-building (`GraphBuilder`) reuse
 * their FlowBody paths and bare ≡ embedded ≡ canonical graphs hold by construction
 * (the 6.3 KEY GATE). Null until the fragment decomposer runs (or for `ttrb`, P7).
 * [sourceText] stays byte-verbatim regardless — the parse is derived (C2-f corollary).
 */
data class FragmentBody(
    val tag: String,
    val sourceText: String,
    val interiorLocation: SourceLocation,
    override val location: SourceLocation,
    val decomposition: FragmentDecomposition? = null,
    /**
     * AG B5: the path as written in `from "<path>"` for a FILE-BACKED fragment (null when the interior
     * is inline). [sourceText] is then the file's whole content, [interiorLocation] names the file, and
     * [location] is the `from "<path>"` clause in the program.
     */
    val sourceFile: String? = null,
) : ContainerBody

/**
 * The result of lowering a fragment interior to canonical AST (Phase 6). [statements]
 * are synthesized canonical statements with host-document spans; [diagnostics] are the
 * dialect parse + reject-table findings; [derivedInPorts] lists the distinct external
 * table references (non-CTE FROM/JOIN names) — the embedded case checks them against the
 * container's declared in-ports, the bare case (6.3) synthesizes an in-port + Load per name.
 */
data class FragmentDecomposition(
    val statements: List<Statement>,
    val diagnostics: List<org.tatrman.ttrp.diagnostics.TtrpDiagnostic>,
    val derivedInPorts: List<String>,
    /**
     * TTR-B action sentences (AG B4), in sentence order: each assigns its rows to [ActionOutput.port]
     * inside the fragment and asks to be shown as `display(<[ActionOutput.display]>)` at program level.
     * The checker turns each into a container OUT port + that wiring (`FragmentActionWiring`).
     */
    val actionOutputs: List<ActionOutput> = emptyList(),
    /** TTR-B output sentences (B7): the OUT ports `Send that to output <port>` wrote, in order. */
    val outputPorts: List<String> = emptyList(),
)

/** One TTR-B action's output: the container OUT [port] and the action display [display] it feeds. */
data class ActionOutput(
    val port: String,
    val display: String,
    val location: SourceLocation,
    /** The verb's own span — where the synthesized wiring reference points. */
    val verbLocation: SourceLocation = location,
)

data class Chain(
    val elements: List<ChainElem>,
    override val location: SourceLocation,
) : TtrpNode

sealed interface ChainElem : TtrpNode

data class OpCall(
    val name: String,
    val args: List<Arg>,
    val config: ConfigBlock?,
    override val location: SourceLocation,
) : ChainElem

/** `variable` | `node.port` | `qname` — position typing is a Stage 1.3 question (D-b). */
data class DottedRef(
    val parts: List<String>,
    override val location: SourceLocation,
) : ChainElem

data class Arg(
    val name: String?,
    val value: ArgValue,
    override val location: SourceLocation,
) : TtrpNode

sealed interface ArgValue : TtrpNode

data class RelationArg(
    val qname: Qname,
    override val location: SourceLocation,
) : ArgValue

data class SchemaLiteralArg(
    val columns: List<SchemaColumn>,
    override val location: SourceLocation,
) : ArgValue

data class SchemaColumn(
    val name: String,
    val type: String,
    override val location: SourceLocation,
) : TtrpNode

data class ExprArg(
    val expr: org.tatrman.ttrp.expr.Expression,
    override val location: SourceLocation,
) : ArgValue

data class ConfigBlock(
    val entries: List<ConfigEntry>,
    override val location: SourceLocation,
) : TtrpNode

sealed interface ConfigEntry : TtrpNode

data class GroupByEntry(
    val keys: List<String>,
    override val location: SourceLocation,
) : ConfigEntry

data class AssignEntry(
    val name: String,
    val value: org.tatrman.ttrp.expr.Expression,
    override val location: SourceLocation,
) : ConfigEntry

data class Qname(
    val parts: List<String>,
    override val location: SourceLocation,
) : TtrpNode {
    val text: String get() = parts.joinToString(".")
}
