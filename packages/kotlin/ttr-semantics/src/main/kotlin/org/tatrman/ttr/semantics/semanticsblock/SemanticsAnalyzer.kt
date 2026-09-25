// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.semanticsblock

import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.model.AttributeDef
import org.tatrman.ttr.parser.model.ColumnDef
import org.tatrman.ttr.parser.model.DataType
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.SemanticsBlock
import org.tatrman.ttr.parser.model.SemanticsValue
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TableDef
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** One `semantics { … }` validation diagnostic (mirrors TS `SemanticsDiagnostic`). */
data class SemanticsDiagnostic(
    val code: DiagnosticCode,
    val message: String,
    val source: SourceLocation,
    /** Closed-vocabulary nearest match for 200/201/202, when one exists. */
    val suggestion: String? = null,
)

/** The result of analysing every `semantics` block in a document. */
data class SemanticsAnalysis(
    val diagnostics: List<SemanticsDiagnostic>,
    /** Resolved results keyed by the owning element's `source` node. */
    val resolved: Map<SourceLocation, ResolvedSemantics>,
)

/**
 * Grounding Phase 1 (grammar 4.2) — validate `semantics { … }` blocks against the
 * closed vocabulary ([Vocabulary], NORMATIVE) and produce the typed
 * [ResolvedSemantics] for diagnostics-free elements. Ported from
 * `packages/semantics/src/semantics-block/validator.ts`.
 *
 * Pipeline per element: shape (keys/values) → cross-ref resolution (`period:`
 * entity ref, `currency:` sibling-attribute ref) → type-constraint check against
 * the declared `type` → per-owner aggregation (completeness, event_date
 * cardinality, geo/valid pairs). `period:` resolves document-locally (same-file
 * entity/table kind index); cross-document resolution is a later phase.
 */
object SemanticsAnalyzer {
    private fun strOf(v: SemanticsValue?): String? = (v as? SemanticsValue.Str)?.value

    /** Map a declared TTR type name to a semantics type-constraint family. */
    fun typeFamilyOf(dt: DataType?): String? {
        if (dt == null) return null
        return when (dt.name.lowercase()) {
            "date", "datetime", "timestamp" -> "date"
            "text", "varchar", "char", "string" -> "text"
            "decimal", "number", "numeric", "int", "integer", "float", "double", "bigint", "smallint" -> "numeric"
            else -> "other"
        }
    }

    private fun constraintFamily(tc: Vocabulary.TypeConstraint): String =
        when (tc) {
            Vocabulary.TypeConstraint.Date -> "date"
            Vocabulary.TypeConstraint.Text -> "text"
            Vocabulary.TypeConstraint.Numeric -> "numeric"
        }

    private fun lastSeg(path: String): String = path.substringAfterLast('.')

    private fun didYouMean(s: String?): String = if (s != null) "; did you mean '$s'?" else ""

    private fun entityKeyList(): String = Vocabulary.ALL_ENTITY_KEYS.joinToString(", ") { "'$it'" }

    /** How to name a wrong-shaped value in a diagnostic, without dumping its contents. */
    private fun describeValue(v: SemanticsValue): String =
        when (v) {
            is SemanticsValue.ListV -> "a list"
            is SemanticsValue.ObjV -> "an object"
            is SemanticsValue.NullV -> "null"
            else -> "'${v.display()}'"
        }

    private fun typeName(dt: DataType?): String = dt?.name ?: "<none>"

    private fun isAttributeOnlyKey(key: String): Boolean {
        if (Vocabulary.ATTRIBUTE_ROLES.values.any { spec -> spec.extraKeys.any { it.key == key } }) return true
        return key == "code_format" || key == "period" || key == "currency"
    }

    /** Analyse every `semantics` block in [definitions]. */
    fun analyzeSemantics(definitions: List<Definition>): SemanticsAnalysis {
        val diagnostics = mutableListOf<SemanticsDiagnostic>()
        val resolved = LinkedHashMap<SourceLocation, ResolvedSemantics>()

        fun emit(
            code: DiagnosticCode,
            source: SourceLocation,
            message: String,
            suggestion: String? = null,
        ) {
            diagnostics += SemanticsDiagnostic(code, message, source, suggestion)
        }

        /**
         * ⛑ The scalar gate, for every key whose value the vocabulary reads as a single value.
         *
         * Until MS-P0·S1b the walker rejected a list or a nested object itself, so nothing here
         * ever saw one. It now carries them verbatim — which keys may hold structure is
         * vocabulary knowledge and the parser holds no vocabulary — so this layer owns the
         * judgement the walker gave up. Without the gate every lookup runs [SemanticsValue.display]
         * over the structure and then reports a PERFECTLY VALID member as unknown:
         * `kind: [period_table]` produced "unknown entity/table kind 'period_table'", sending the
         * author to check a spelling that was never wrong.
         *
         * Twin of `scalarOnly` in the TS validator, message for message.
         */
        fun scalarOnly(
            key: String,
            value: SemanticsValue,
            source: SourceLocation,
        ): Boolean {
            val shape =
                when (value) {
                    is SemanticsValue.ListV -> "a list"
                    is SemanticsValue.ObjV -> "an object"
                    else -> return true
                }
            emit(DiagnosticCode.SemMentionShape, source, "'$key:' takes a single value, not $shape")
            return false
        }

        // Document-local index of declared entity/table kinds (raw), for `period:`.
        val localKinds = LinkedHashMap<String, String>()
        for (def in definitions) {
            val block = semanticsOf(def)
            if ((def is EntityDef || def is TableDef) && block != null) {
                strOf(block.entries["kind"])?.let { localKinds[def.name] = it }
            }
        }

        // ---- entity/table block shape ----

        /**
         * A `name:` / `code:` value: a bare id naming an attribute of THIS owner.
         *
         * Owner-scoped on purpose. The mention facet says "which of MY attributes carries me under
         * this aspect", so a name that resolves somewhere else in the document is exactly as wrong
         * as one that resolves nowhere.
         */
        fun mentionRef(
            key: String,
            value: SemanticsValue,
            rawMembers: List<Definition>,
            source: SourceLocation,
        ): SymbolRef? {
            val name = strOf(value)
            if (name == null) {
                emit(
                    DiagnosticCode.SemMentionShape,
                    source,
                    "'$key:' takes an attribute/column id, not ${describeValue(value)}",
                )
                return null
            }
            if (rawMembers.none { it.name == name }) {
                val s = Suggest.nearestMatch(name, rawMembers.map { it.name })
                emit(
                    DiagnosticCode.SemMentionRefUnresolved,
                    source,
                    "'$key: $name' does not name an attribute/column of this entity/table${didYouMean(s)}",
                    s,
                )
                return null
            }
            return SymbolRef(name)
        }

        /**
         * One `measures:` item: a bare id, or `{ attribute: <id>, aggregation?: <id> }`.
         *
         * ⚠ `aggregation` HERE is the aggregation of a measure. It is not the def-level
         * `aggregation:` attribute property (EN-P1.2: "this attribute is derived by an
         * aggregation") and not md's measure property. Nothing reads across those surfaces — a bare
         * id gets [Vocabulary.DEFAULT_AGGREGATION] regardless of what the attribute def says.
         */
        fun measureItem(
            item: SemanticsValue,
            source: SourceLocation,
        ): Pair<String, String>? {
            if (item is SemanticsValue.Str) return item.value to Vocabulary.DEFAULT_AGGREGATION
            if (item !is SemanticsValue.ObjV) {
                emit(
                    DiagnosticCode.SemMentionShape,
                    source,
                    "a measures item is an id or '{ attribute: …, aggregation: … }', " +
                        "not ${describeValue(item)}",
                )
                return null
            }
            var bad = false
            for (k in item.entries.keys) {
                if (k == "attribute" || k == "aggregation") continue
                val s = Suggest.nearestMatch(k, listOf("attribute", "aggregation"))
                emit(
                    DiagnosticCode.SemMentionShape,
                    source,
                    "unknown key '$k' in a measures item${didYouMean(s)}",
                    s,
                )
                bad = true
            }
            val attribute = strOf(item.entries["attribute"])
            if (attribute == null) {
                emit(DiagnosticCode.SemMentionShape, source, "a measures item needs 'attribute:' as an id")
                bad = true
            }
            var aggregation = Vocabulary.DEFAULT_AGGREGATION
            val raw = item.entries["aggregation"]
            if (raw != null) {
                val rawStr = strOf(raw)
                if (rawStr != null && Vocabulary.AGGREGATIONS.contains(rawStr)) {
                    aggregation = rawStr
                } else if (!scalarOnly("aggregation", raw, source)) {
                    // Shape before vocabulary, as everywhere else: `aggregation: [avg]` is a wrong
                    // shape, not an unknown aggregation — and a ListV displays as its items joined,
                    // so the vocabulary message would have named a valid member as unknown.
                    bad = true
                } else {
                    val s = if (rawStr != null) Suggest.nearestMatch(rawStr, Vocabulary.AGGREGATIONS) else null
                    emit(
                        DiagnosticCode.SemBadAggregation,
                        source,
                        "unknown aggregation '${raw.display()}'${didYouMean(s)}",
                        s,
                    )
                    bad = true
                }
            }
            if (bad || attribute == null) return null
            return attribute to aggregation
        }

        /** The `measures:` list — shape, owner resolution, numeric type, and duplicates. */
        fun parseMeasures(
            value: SemanticsValue,
            rawMembers: List<Definition>,
            source: SourceLocation,
        ): Pair<List<MeasureRef>, Boolean> {
            if (value !is SemanticsValue.ListV) {
                emit(
                    DiagnosticCode.SemMentionShape,
                    source,
                    "'measures:' takes a list, not ${describeValue(value)}",
                )
                return emptyList<MeasureRef>() to false
            }
            val measures = mutableListOf<MeasureRef>()
            val seen = mutableSetOf<String>()
            var clean = true
            for (item in value.items) {
                val parsed = measureItem(item, source)
                if (parsed == null) {
                    clean = false
                    continue
                }
                val (attribute, aggregation) = parsed
                val member = rawMembers.firstOrNull { it.name == attribute }
                if (member == null) {
                    val s = Suggest.nearestMatch(attribute, rawMembers.map { it.name })
                    emit(
                        DiagnosticCode.SemMentionRefUnresolved,
                        source,
                        "measure '$attribute' does not name an attribute/column of this " +
                            "entity/table${didYouMean(s)}",
                        s,
                    )
                    clean = false
                    continue
                }
                val fam = typeFamilyOf(typeOf(member))
                if (fam != null && fam != "numeric") {
                    emit(
                        DiagnosticCode.SemMeasureNotNumeric,
                        source,
                        "measure '$attribute' has type '${typeName(typeOf(member))}', which is not numeric",
                    )
                    clean = false
                    continue
                }
                if (!seen.add(attribute)) {
                    emit(DiagnosticCode.SemMeasureDuplicate, source, "measure '$attribute' is listed more than once")
                    clean = false
                    continue
                }
                measures += MeasureRef(SymbolRef(attribute), aggregation)
            }
            return measures.toList() to clean
        }

        /**
         * LP review-103 (D4) — a `code_pattern:` value: a quoted Java regex the code attribute's
         * values match. Null (after emitting) when it is not a single string, is blank, or does not
         * compile.
         *
         * Compiled HERE, with the JVM's own engine, because the JVM is where it runs: the lexicon
         * compiler copies it into the archive's `TargetFacts.codeFormat`, and the resolver matches
         * a quoted literal against it with `Regex(...)`. A pattern that fails there fails silently
         * (the literal is simply not code-shaped), so this is the one place an author can hear
         * about it.
         */
        fun codePatternOf(
            value: SemanticsValue,
            source: SourceLocation,
        ): String? {
            val pattern = strOf(value)
            if (pattern == null) {
                emit(
                    DiagnosticCode.SemMentionShape,
                    source,
                    "'code_pattern:' takes a regular expression in quotes, not ${describeValue(value)}",
                )
                return null
            }
            if (pattern.isBlank()) {
                emit(
                    DiagnosticCode.SemBadCodePattern,
                    source,
                    "'code_pattern:' is empty — an empty pattern matches no code anyone can quote",
                )
                return null
            }
            val error =
                try {
                    Pattern.compile(pattern)
                    null
                } catch (e: PatternSyntaxException) {
                    e.description
                }
            if (error != null) {
                emit(
                    DiagnosticCode.SemBadCodePattern,
                    source,
                    "'code_pattern: \"$pattern\"' is not a valid regular expression: $error",
                )
                return null
            }
            return pattern
        }

        fun validateEntityBlock(
            block: SemanticsBlock,
            rawMembers: List<Definition>,
        ): EntityBlockResult {
            var clean = true
            for (dup in block.duplicateProperties) {
                emit(DiagnosticCode.SemDuplicateKey, block.source, "duplicate semantics key '$dup'")
                clean = false
            }
            var kind: String? = null
            var name: SymbolRef? = null
            var code: SymbolRef? = null
            var codePattern: String? = null
            var measures: List<MeasureRef> = emptyList()
            for ((key, value) in block.entries) {
                if (key == "kind") {
                    val vs = strOf(value)
                    if (vs != null && Vocabulary.ENTITY_KINDS.contains(vs)) {
                        kind = vs
                    } else if (!scalarOnly(key, value, block.source)) {
                        clean = false
                    } else {
                        val s = if (vs != null) Suggest.nearestMatch(vs, Vocabulary.ENTITY_KINDS) else null
                        emit(
                            DiagnosticCode.SemUnknownKind,
                            block.source,
                            "unknown entity/table kind '${value.display()}'${didYouMean(s)}",
                            s,
                        )
                        clean = false
                    }
                } else if (key == "name" || key == "code") {
                    // ⛑ Must be matched BEFORE the misplaced-keyword branch below, which tests the
                    // VALUE against the role roster: `name: amount` on an entity whose attribute is
                    // called `amount` would otherwise be reported as an attribute key on an entity
                    // block. Attributes named like roles are ordinary, not a mistake.
                    val ref = mentionRef(key, value, rawMembers, block.source)
                    if (ref == null) {
                        clean = false
                    } else if (key == "name") {
                        name = ref
                    } else {
                        code = ref
                    }
                } else if (key == "code_pattern") {
                    // Matched before the misplaced-keyword branch for the same reason `name`/`code`
                    // are: that branch tests the VALUE against the role roster.
                    codePattern = codePatternOf(value, block.source)
                    if (codePattern == null) clean = false
                } else if (key == "measures") {
                    val (parsed, ok) = parseMeasures(value, rawMembers, block.source)
                    measures = parsed
                    if (!ok) clean = false
                    // `strOf(value)` guards the roster test: it reads the VALUE, and a ListV's
                    // display() is its items joined, so `[event_date]` displays as `event_date`
                    // and a structured value would be misreported as a misplaced attribute key.
                } else if (key == "role" || Vocabulary.ALL_ROLES.contains(strOf(value)) || isAttributeOnlyKey(key)) {
                    emit(
                        DiagnosticCode.SemMisplacedKeyword,
                        block.source,
                        "'$key' is an attribute/column key; entity/table blocks carry ${entityKeyList()}",
                    )
                    clean = false
                } else {
                    val s = Suggest.nearestMatch(key, Vocabulary.ALL_ENTITY_KEYS)
                    emit(DiagnosticCode.SemUnknownKey, block.source, "unknown semantics key '$key'${didYouMean(s)}", s)
                    clean = false
                }
            }
            // A pattern describes the CODE attribute's values, so a block that names no code has
            // nothing for it to describe. Keyed on the `code` KEY, not on the resolved ref: a `code:`
            // that failed to resolve has already been reported, and a second error blaming the
            // pattern for it would send the author to the wrong line of the block.
            if (block.entries.containsKey("code_pattern") && !block.entries.containsKey("code")) {
                emit(
                    DiagnosticCode.SemBadCodePattern,
                    block.source,
                    "'code_pattern:' needs 'code:' in the same block — it describes the values of the " +
                        "code attribute, and this block names none",
                )
                clean = false
                codePattern = null
            }
            return EntityBlockResult(kind, name, code, measures, clean, codePattern)
        }

        /**
         * contracts §1.2 / MS-D2 — the legacy `nameAttribute:` / `codeAttribute:` matrix.
         *
         * Returns false only for the disagreement case, which is an ERROR and degrades the block:
         * "a disagreement is always a bug", so the analyzer refuses to pick a winner rather than
         * silently preferring one source over the other. Runs for an entity with NO semantics block
         * too — that is row 1 of the matrix. `TableDef` has no legacy properties.
         */
        fun legacyMentionOk(
            owner: Definition,
            block: EntityBlockResult?,
        ): Boolean {
            if (owner !is EntityDef) return true
            var ok = true
            for (
            (prop, declared) in
            listOf("nameAttribute" to block?.name, "codeAttribute" to block?.code)
            ) {
                val legacy = if (prop == "nameAttribute") owner.nameAttribute else owner.codeAttribute
                if (legacy == null) continue
                val key = if (prop == "nameAttribute") "name" else "code"
                if (declared == null) {
                    // Review-103 F16 — say what the property is still FOR: the mention facet a quoted
                    // literal is attributed through reads the semantics block, and this only as a
                    // fallback. "Superseded" alone read as "harmless", and the facet is not.
                    emit(
                        DiagnosticCode.SemLegacyMentionDeprecated,
                        legacy.source,
                        "'$prop:' is superseded by 'semantics { $key: ${lastSeg(legacy.path)} }' — quoting a " +
                            "value (\"…\") filters the column the semantics block names, and reads '$prop:' " +
                            "only as a fallback",
                    )
                    continue
                }
                if (namesTheSameAttribute(legacy.path, declared.path, owner.name)) {
                    emit(
                        DiagnosticCode.SemLegacyMentionDeprecated,
                        legacy.source,
                        "'$prop:' repeats 'semantics { $key: ${declared.path} }' — drop the legacy property",
                    )
                    continue
                }
                emit(
                    DiagnosticCode.SemLegacyMentionMismatch,
                    legacy.source,
                    "'$prop: ${legacy.path}' disagrees with 'semantics { $key: ${declared.path} }'",
                )
                ok = false
            }
            return ok
        }

        // period: resolution — document-local kind index only.
        fun resolvePeriodRef(
            path: String,
            source: SourceLocation,
        ): Boolean {
            val name = lastSeg(path)
            val localKind = localKinds[name]
            if (localKind != null) {
                if (localKind == "period_table") return true
                emit(
                    DiagnosticCode.SemBadPeriodRef,
                    source,
                    "period: '$path' refers to '$name', which is not a 'period_table' kind",
                )
                return false
            }
            emit(DiagnosticCode.SemBadPeriodRef, source, "period: '$path' does not resolve to any entity/table")
            return false
        }

        // currency: resolution — a sibling member with role currency_code.
        fun resolveCurrencyRef(
            path: String,
            siblings: List<Definition>,
            source: SourceLocation,
        ): Boolean {
            val name = lastSeg(path)
            val sib = siblings.firstOrNull { it.name == name }
            if (sib == null) {
                emit(
                    DiagnosticCode.SemBadCurrencyRef,
                    source,
                    "currency: '$path' does not resolve to a sibling attribute/column",
                )
                return false
            }
            if (strOf(semanticsOf(sib)?.entries?.get("role")) != "currency_code") {
                emit(
                    DiagnosticCode.SemBadCurrencyRef,
                    source,
                    "currency: '$path' must reference a sibling with role 'currency_code'",
                )
                return false
            }
            return true
        }

        // ---- attribute/column block shape + cross-refs + type ----
        data class AttrParse(
            val role: String?,
            val rawRole: String?,
            val clean: Boolean,
            val resolved: ResolvedAttributeSemantics? = null,
        )

        fun validateAttributeBlock(
            block: SemanticsBlock,
            memberType: DataType?,
            siblings: List<Definition>,
        ): AttrParse {
            var clean = true
            for (dup in block.duplicateProperties) {
                emit(DiagnosticCode.SemDuplicateKey, block.source, "duplicate semantics key '$dup'")
                clean = false
            }

            if (block.entries.containsKey("kind")) {
                emit(
                    DiagnosticCode.SemMisplacedKeyword,
                    block.source,
                    "'kind' is an entity/table key; attribute/column blocks carry 'role'",
                )
                clean = false
            }

            val rawRoleVal = block.entries["role"]
            val rawRole = strOf(rawRoleVal)
            var role: String? = null
            if (rawRole != null && Vocabulary.ATTRIBUTE_ROLES.containsKey(rawRole)) {
                role = rawRole
            } else if (rawRoleVal != null) {
                // Shape before vocabulary — `role: [event_date]` is a wrong shape, not an unknown role.
                if (!scalarOnly("role", rawRoleVal, block.source)) {
                    clean = false
                } else {
                    val s = if (rawRole != null) Suggest.nearestMatch(rawRole, Vocabulary.ALL_ROLES) else null
                    emit(
                        DiagnosticCode.SemUnknownRole,
                        block.source,
                        "unknown semantics role '${rawRoleVal.display()}'${didYouMean(s)}",
                        s,
                    )
                    clean = false
                }
            }

            val spec = role?.let { Vocabulary.ATTRIBUTE_ROLES[it] }
            val allowed = mutableSetOf("role")
            spec?.extraKeys?.forEach { allowed += it.key }
            for (key in block.entries.keys) {
                if (key == "kind") continue
                if (allowed.contains(key)) continue
                if (role != null) {
                    val s = Suggest.nearestMatch(key, allowed.toList())
                    emit(
                        DiagnosticCode.SemUnknownKey,
                        block.source,
                        "key '$key' is not valid for role '$role'${didYouMean(s)}",
                        s,
                    )
                    clean = false
                }
            }

            if (role != null && spec?.typeConstraint != null) {
                val fam = typeFamilyOf(memberType)
                val want = constraintFamily(spec.typeConstraint)
                if (fam != null && fam != want) {
                    emit(
                        DiagnosticCode.SemTypeConstraint,
                        block.source,
                        "role '$role' requires a $want type, but the declared type is '${typeName(memberType)}'",
                    )
                    clean = false
                }
            }

            var periodRef: SymbolRef? = null
            var currencyRef: SymbolRef? = null
            if (role != null) {
                val periodVal = block.entries["period"]
                if (periodVal != null && spec?.extraKeys?.any { it.key == "period" } == true) {
                    if (!scalarOnly("period", periodVal, block.source)) {
                        clean = false
                    } else if (resolvePeriodRef(periodVal.display(), block.source)) {
                        periodRef = SymbolRef(periodVal.display())
                    } else {
                        clean = false
                    }
                }
                val currencyVal = block.entries["currency"]
                if (currencyVal != null && spec?.extraKeys?.any { it.key == "currency" } == true) {
                    if (!scalarOnly("currency", currencyVal, block.source)) {
                        clean = false
                    } else if (resolveCurrencyRef(currencyVal.display(), siblings, block.source)) {
                        currencyRef = SymbolRef(currencyVal.display())
                    } else {
                        clean = false
                    }
                }
                // code_format: gated HERE, not at the `codeFormat` build below, which is only
                // reached once the block is clean — a structured value there fell through
                // `strOf(...)` and silently became the 'yyyyMM' default.
                val codeFormatVal = block.entries["code_format"]
                if (codeFormatVal != null && spec?.extraKeys?.any { it.key == "code_format" } == true) {
                    if (!scalarOnly("code_format", codeFormatVal, block.source)) clean = false
                }
            }

            if (role == null || !clean) return AttrParse(role, rawRole, clean)
            val codeFormat =
                if (role == "period_code") strOf(block.entries["code_format"]) ?: "yyyyMM" else null
            return AttrParse(
                role = role,
                rawRole = role,
                clean = true,
                resolved = ResolvedAttributeSemantics(role, periodRef, currencyRef, codeFormat),
            )
        }

        data class Member(
            val name: String,
            val role: String?,
            val block: SemanticsBlock,
        )

        fun aggregate(
            ownerName: String,
            ownerSource: SourceLocation,
            ownerKind: String?,
            ownerClean: Boolean,
            members: List<Member>,
        ) {
            fun roleCount(r: String): Int = members.count { it.role == r }

            if (roleCount("event_date") > 1) {
                emit(
                    DiagnosticCode.SemMultipleEventDate,
                    ownerSource,
                    "entity/table '$ownerName' has more than one 'event_date' — exactly one is the default query date",
                )
            }

            val hasLat = roleCount("geo_lat") > 0
            val hasLon = roleCount("geo_lon") > 0
            if (hasLat != hasLon) {
                emit(
                    DiagnosticCode.SemGeoPair,
                    ownerSource,
                    "'$ownerName' has ${if (hasLat) "geo_lat without geo_lon" else "geo_lon without geo_lat"} — the pair is required together",
                )
            }

            val hasFrom = roleCount("valid_from") > 0
            val hasTo = roleCount("valid_to") > 0
            if (hasFrom != hasTo) {
                emit(
                    DiagnosticCode.SemValidPair,
                    ownerSource,
                    "'$ownerName' has ${if (hasFrom) "valid_from without valid_to" else "valid_to without valid_from"} — the validity pair is both-or-neither",
                )
            }

            if (ownerKind == null || !ownerClean) return

            if (ownerKind == "poi") {
                val point = roleCount("geo_point")
                val pair = if (hasLat && hasLon) 1 else 0
                if (!((point == 1 && pair == 0) || (point == 0 && pair == 1))) {
                    emit(
                        DiagnosticCode.SemGeoPair,
                        ownerSource,
                        "poi '$ownerName' must have exactly one 'geo_point' XOR one 'geo_lat' + one 'geo_lon'",
                    )
                }
            } else {
                for (clause in Vocabulary.KIND_COMPLETENESS[ownerKind] ?: emptyList()) {
                    val n = roleCount(clause.role)
                    if (n != clause.count) {
                        emit(
                            DiagnosticCode.SemCompleteness,
                            ownerSource,
                            "$ownerKind '$ownerName' requires exactly ${clause.count} '${clause.role}' (found $n)",
                        )
                    }
                }
            }
        }

        fun validateOwner(
            owner: Definition,
            ownerBlock: SemanticsBlock?,
            rawMembers: List<Definition>,
        ) {
            val ownerName = owner.name
            val ownerSource = owner.source
            var ownerKind: String? = null
            var ownerClean = true
            if (ownerBlock != null) {
                val r = validateEntityBlock(ownerBlock, rawMembers)
                ownerKind = r.kind
                // ⛑ Evaluated BEFORE the `&&`, not inside it. `r.clean && legacyMentionOk(...)`
                // short-circuits, which silently switched the whole contracts §1.2 matrix off for
                // any block carrying an unrelated error — including row 4, the MS-D2 "a
                // disagreement is always a bug" ERROR. The legacy properties are a surface of
                // their own; whether the semantics block validated says nothing about them.
                val legacyOk = legacyMentionOk(owner, r)
                ownerClean = r.clean && legacyOk
                // Resolve when the block declared SOMETHING. An empty `semantics { }` carries no
                // facts, and a block that only errored is degraded by the `clean` gate above.
                if (ownerClean && (r.kind != null || r.name != null || r.code != null || r.measures.isNotEmpty())) {
                    resolved[ownerBlock.source] =
                        ResolvedEntitySemantics(r.kind, r.name, r.code, r.measures, r.codePattern)
                }
            } else {
                legacyMentionOk(owner, null)
            }

            val members = mutableListOf<Member>()
            for (m in rawMembers) {
                val block = semanticsOf(m) ?: continue
                val parsed = validateAttributeBlock(block, typeOf(m), rawMembers)
                members += Member(m.name, parsed.role, block)
                if (parsed.clean && parsed.resolved != null) resolved[block.source] = parsed.resolved
            }

            aggregate(ownerName, ownerSource, ownerKind, ownerClean, members)
        }

        for (def in definitions) {
            when (def) {
                is EntityDef -> validateOwner(def, def.semantics, def.attributes)
                is TableDef -> validateOwner(def, def.semantics, def.columns)
                is AttributeDef -> {
                    def.semantics?.let { block ->
                        val parsed = validateAttributeBlock(block, def.type, emptyList())
                        if (parsed.clean && parsed.resolved != null) resolved[block.source] = parsed.resolved
                    }
                }
                is ColumnDef -> {
                    def.semantics?.let { block ->
                        val parsed = validateAttributeBlock(block, def.type, emptyList())
                        if (parsed.clean && parsed.resolved != null) resolved[block.source] = parsed.resolved
                    }
                }
                else -> {}
            }
        }

        return SemanticsAnalysis(diagnostics, resolved)
    }

    private fun semanticsOf(def: Definition): SemanticsBlock? =
        when (def) {
            is EntityDef -> def.semantics
            is TableDef -> def.semantics
            is AttributeDef -> def.semantics
            is ColumnDef -> def.semantics
            else -> null
        }

    /** What an entity/table block declared, once shape-checked and owner-resolved. */
    private data class EntityBlockResult(
        val kind: String? = null,
        val name: SymbolRef? = null,
        val code: SymbolRef? = null,
        val measures: List<MeasureRef> = emptyList(),
        val clean: Boolean = true,
        val codePattern: String? = null,
    )

    /**
     * Does a legacy `nameAttribute:`/`codeAttribute:` path name the same attribute as the semantics
     * block's bare `name:`/`code:` id?
     *
     * The semantics side is always a bare local id — `mentionRef` only accepts a member of this
     * owner. The legacy side is a [org.tatrman.ttr.parser.model.Reference] and may be written
     * qualified, so the last segment has to be compared rather than the whole path. ⛑ But only when
     * the qualifier is the owner ITSELF: `nameAttribute: Other.customer_name` names a different
     * entity's attribute, and comparing last segments alone reported that as agreement and then
     * advised deleting the legacy property — destructive advice built on a comparison that could
     * not see the difference.
     */
    private fun namesTheSameAttribute(
        legacyPath: String,
        declaredName: String,
        ownerName: String,
    ): Boolean {
        if (lastSeg(legacyPath) != declaredName) return false
        val qualifier = legacyPath.dropLast(minOf(legacyPath.length, declaredName.length + 1))
        return qualifier.isEmpty() || lastSeg(qualifier) == ownerName
    }

    private fun typeOf(def: Definition): DataType? =
        when (def) {
            is AttributeDef -> def.type
            is ColumnDef -> def.type
            else -> null
        }
}
