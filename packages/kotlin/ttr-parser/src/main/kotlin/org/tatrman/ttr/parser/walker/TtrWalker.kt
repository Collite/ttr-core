// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.walker

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import org.tatrman.ttr.parser.diagnostics.DiagnosticCode
import org.tatrman.ttr.parser.generated.TTRLexer
import org.tatrman.ttr.parser.generated.TTRParser
import org.tatrman.ttr.parser.loader.ParseError
import org.tatrman.ttr.parser.loader.ParseWarning
import org.tatrman.ttr.parser.model.AggregationSpec
import org.tatrman.ttr.parser.model.AreaDef
import org.tatrman.ttr.parser.model.AttributeDef
import org.tatrman.ttr.parser.model.CalcRef
import org.tatrman.ttr.parser.model.CubeletDef
import org.tatrman.ttr.parser.model.CubeletMeasure
import org.tatrman.ttr.parser.model.DimensionDef
import org.tatrman.ttr.parser.model.HierarchyDef
import org.tatrman.ttr.parser.model.HierarchyLevel
import org.tatrman.ttr.parser.model.AttrColumnBinding
import org.tatrman.ttr.parser.model.ColumnSource
import org.tatrman.ttr.parser.model.AllocationSpec
import org.tatrman.ttr.parser.model.JournalingSpec
import org.tatrman.ttr.parser.model.MatchMethodValue
import org.tatrman.ttr.parser.model.Md2dbCubeletDef
import org.tatrman.ttr.parser.model.Md2dbDomainDef
import org.tatrman.ttr.parser.model.Md2dbMapDef
import org.tatrman.ttr.parser.model.Md2erCubeletDef
import org.tatrman.ttr.parser.model.Md2erDimensionDef
import org.tatrman.ttr.parser.model.MdDomainDef
import org.tatrman.ttr.parser.model.MdMapDef
import org.tatrman.ttr.parser.model.MeasureColumnBinding
import org.tatrman.ttr.parser.model.MeasureDef
import org.tatrman.ttr.parser.model.ShapeSpec
import org.tatrman.ttr.parser.model.BindingColumnBareId
import org.tatrman.ttr.parser.model.BindingColumnEntry
import org.tatrman.ttr.parser.model.BindingColumnObject
import org.tatrman.ttr.parser.model.BindingColumnValue
import org.tatrman.ttr.parser.model.BindingProperty
import org.tatrman.ttr.parser.model.BindingPropertyBareId
import org.tatrman.ttr.parser.model.BindingPropertyBlock
import org.tatrman.ttr.parser.model.ChangeSemanticsDecl
import org.tatrman.ttr.parser.model.ColumnDef
import org.tatrman.ttr.parser.model.ConceptBindingDef
import org.tatrman.ttr.parser.model.ConceptBody
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptParticipant
import org.tatrman.ttr.parser.model.ConceptProperty
import org.tatrman.ttr.parser.model.ConceptSugar
import org.tatrman.ttr.parser.model.EmbeddedBlock
import org.tatrman.ttr.parser.model.ConstraintDef
import org.tatrman.ttr.parser.model.DataType
import org.tatrman.ttr.parser.model.AttributeTransition
import org.tatrman.ttr.parser.model.AutoBody
import org.tatrman.ttr.parser.model.AutoEntry
import org.tatrman.ttr.parser.model.AutoSet
import org.tatrman.ttr.parser.model.AutoTransition
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.AutomationDef
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.DrillMapDef
import org.tatrman.ttr.parser.model.EngineDef
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.ExecutorDef
import org.tatrman.ttr.parser.model.Er2CncRoleDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.FkDef
import org.tatrman.ttr.parser.model.ImportStatement
import org.tatrman.ttr.parser.model.IndexDef
import org.tatrman.ttr.parser.model.LocalizedStringListValue
import org.tatrman.ttr.parser.model.LocalizedStringValue
import org.tatrman.ttr.parser.model.ProjectDef
import org.tatrman.ttr.parser.model.ProcedureDef
import org.tatrman.ttr.parser.model.ProcessDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.parser.model.Reference
import org.tatrman.ttr.parser.model.RelationDef
import org.tatrman.ttr.parser.model.RoleDef
import org.tatrman.ttr.parser.model.LexiconBlock
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.ModelDirective
import org.tatrman.ttr.parser.model.SchemaDef
import org.tatrman.ttr.parser.model.SearchHintsValue
import org.tatrman.ttr.parser.model.SecurityBlock
import org.tatrman.ttr.parser.model.SecurityStatement
import org.tatrman.ttr.parser.model.SemanticsBlock
import org.tatrman.ttr.parser.model.SemanticsValue
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.StorageDef
import org.tatrman.ttr.parser.model.TableDef
import org.tatrman.ttr.parser.model.TargetObjectValue
import org.tatrman.ttr.parser.model.TaggedBlockValue
import org.tatrman.ttr.parser.model.TargetReferenceValue
import org.tatrman.ttr.parser.model.TargetValue
import org.tatrman.ttr.parser.model.ViewDef
import org.tatrman.ttr.parser.model.WorldAutoMember
import org.tatrman.ttr.parser.model.WorldDef
import org.tatrman.ttr.parser.model.WorldSchemaDef
import org.tatrman.ttr.parser.model.WorldSchemaField
import org.tatrman.ttr.parser.model.WritebackReservation

/** The cap on eager `restrict { range: lo..hi }` member materialization (review-071 R3, OOM guard). */
private const val MAX_RANGE_MEMBERS: Long = 100_000L

/**
 * Converts an ANTLR parse tree into the typed [Definition] model.
 *
 * The walker is intentionally NOT a `TTRBaseListener` — it walks the tree
 * top-down via direct context traversal. That matches how the grammar
 * naturally nests (table → columns → individual column defs) without the
 * mutable-state tax a listener would impose.
 *
 * Source spans use the D4 superset (line/column/endLine/endColumn/offsets).
 * The multi-token-span invariant is `endColumn = stopToken.column +
 * stopToken.length` — NOT `startColumn + spanLength` (see [location]).
 */
class TtrWalker(
    private val fileLabel: String,
) {
    private val warnings = mutableListOf<ParseWarning>()
    private val errors = mutableListOf<ParseError>()

    /**
     * BCP-47 language codes accepted by the search feature. Unknown codes
     * still parse (last-write-wins on the map) but emit a warning so authors
     * notice typos. Stop-word resource files exist for the same set.
     */
    private val supportedLanguages = setOf("cs", "en", "de", "sk", "hu")

    /** The file's `model` code — grammar 0.17 reads the one `def role` body by it (⚑CN-P6). */
    private var modelCode: String? = null

    fun visitDocument(doc: TTRParser.DocumentContext): WalkResult {
        val schema = doc.modelDirective()?.let { visitModelDirective(it) }
        modelCode = schema?.modelCode
        val defs = doc.definition().mapNotNull { visitDefinition(it) }
        val securityBlocks = doc.securityBlock().map { visitSecurityBlock(it) }

        val pkg = doc.packageDecl()?.qualifiedName()?.text
        val imports =
            doc.importDecl().map {
                ImportStatement(
                    target = it.qualifiedName().text,
                    wildcard = it.STAR() != null,
                    source = location(it),
                )
            }

        if (doc.graphBlock() != null) {
            warn(doc.graphBlock(), "graph block ignored in a .ttr file — graphs belong in .ttrg render artefacts")
        }

        // LQ (logical queries, L-2 / ruling LQ-5): a `def query` in a `model er` file is a logical
        // query over entities and takes no parameters. A warning here, like the other query
        // diagnostics: an error would empty the file's definitions; ttr-metadata refuses the load.
        if (schema?.modelCode == "er") {
            defs.filterIsInstance<QueryDef>().filter { it.parameters.isNotEmpty() }.forEach { q ->
                warnAt(
                    q.source,
                    "${DiagnosticCode.LogicalQueryParameters}: logical query '${q.name}' (model er) cannot " +
                        "declare parameters; a parametrized query stays a db query",
                )
            }
        }

        return WalkResult(
            modelDirective = schema,
            definitions = defs,
            warnings = warnings.toList(),
            errors = errors.toList(),
            packageName = pkg,
            imports = imports,
            securityBlocks = securityBlocks,
        )
    }

    /**
     * PL-P4.S3 (grammar 0.11, H-1) — walk a document-level `security { … }` block.
     * The grammar has already rejected unknown verbs and row predicates; here we
     * only fan the structured statements out to the typed model. Object refs are
     * kept verbatim (dotted-id text); resolution is ttr-semantics' job (advisory).
     */
    private fun visitSecurityBlock(ctx: TTRParser.SecurityBlockContext): SecurityBlock =
        SecurityBlock(
            statements = ctx.securityStatement().map { visitSecurityStatement(it) },
            source = location(ctx),
        )

    private fun visitSecurityStatement(ctx: TTRParser.SecurityStatementContext): SecurityStatement {
        val src = location(ctx)
        return when {
            ctx.OWN() != null -> SecurityStatement.Own(objectRef = ctx.id(0).text, owner = ctx.id(1).text, source = src)
            ctx.CLASSIFY() != null ->
                SecurityStatement.Classify(objectRef = ctx.id(0).text, classification = ctx.id(1).text, source = src)
            ctx.GRANT() != null ->
                SecurityStatement.Grant(
                    privilege = ctx.id(0).text,
                    objectRef = ctx.id(1).text,
                    grantee = ctx.id(2).text,
                    source = src,
                )
            else -> SecurityStatement.Mask(objectRef = ctx.id(0).text, source = src)
        }
    }

    private fun warn(
        ctx: ParserRuleContext,
        message: String,
    ) {
        warnings +=
            ParseWarning(
                file = fileLabel,
                line = ctx.start.line,
                column = ctx.start.charPositionInLine + 1,
                message = message,
            )
    }

    /** [warn] at a walked definition's [source] (its start — the same position [warn] reports for its context). */
    private fun warnAt(
        source: SourceLocation,
        message: String,
    ) {
        warnings += ParseWarning(file = fileLabel, line = source.line, column = source.column + 1, message = message)
    }

    /** [err] at a walked node's [source] (its start — the position [err] reports for its context). */
    private fun errAt(
        source: SourceLocation,
        code: DiagnosticCode,
        message: String,
    ) {
        errors +=
            ParseError(
                file = fileLabel,
                line = source.line,
                column = source.column + 1,
                message = "$code: $message",
                code = code,
            )
    }

    private fun err(
        ctx: ParserRuleContext,
        code: DiagnosticCode,
        message: String,
    ) {
        errors +=
            ParseError(
                file = fileLabel,
                line = ctx.start.line,
                column = ctx.start.charPositionInLine + 1,
                // keep the code id in the message text for human-readable output;
                // the typed `code` field carries it for programmatic consumers.
                message = "$code: $message",
                code = code,
            )
    }

    private fun visitModelDirective(ctx: TTRParser.ModelDirectiveContext): ModelDirective {
        val code = ctx.modelCode().text
        // v4.4: `MODEL modelCode (SCHEMA id)? (LOCALE id)?` — up to two id slots;
        // ANTLR returns a List. Assign positionally by which of SCHEMA / LOCALE is
        // present (ids stay in order).
        val ids = ctx.id()
        var cursor = 0
        val ns = if (ctx.SCHEMA() != null && cursor < ids.size) ids[cursor++].text else null
        val locale = if (ctx.LOCALE() != null && cursor < ids.size) ids[cursor++].text else null
        return ModelDirective(modelCode = code, schema = ns, locale = locale, source = location(ctx))
    }

    /**
     * v4.4 — a canonical lexicon entry (`def term|pattern|example`). One shared
     * permissive body; per-kind required-field validity lives in semantics.
     */
    private fun visitLexiconEntry(
        od: TTRParser.ObjectDefinitionContext,
        entryKind: String,
    ): LexiconEntryDef {
        val props = od.lexiconEntryDef().lexiconEntryProperty()
        return LexiconEntryDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            entryKind = entryKind,
            indexSecurity =
                props
                    .lastOrNull { it.indexSecurityProperty() != null }
                    ?.indexSecurityProperty()
                    ?.id()
                    ?.text,
            target = props.firstNotNullOfOrNull { it.forProperty()?.let { f -> makeRef(f.id()) } },
            forms =
                props.firstNotNullOfOrNull { it.formsProperty()?.let { f -> stringList(f.listOfStrings()) } }
                    ?: emptyList(),
            match = props.firstNotNullOfOrNull { it.matchProperty()?.let { m -> stringForm(m.stringLiteralForm()) } },
            text = props.firstNotNullOfOrNull { it.textProperty()?.let { t -> stringForm(t.stringLiteralForm()) } },
            // Grammar 0.17 — the cn default sense and its priority (cn contracts §1.5); their rules are semantic.
            default =
                props
                    .lastOrNull { it.defaultProperty()?.BOOLEAN_LITERAL() != null }
                    ?.defaultProperty()
                    ?.BOOLEAN_LITERAL()
                    ?.text
                    ?.let { it == "true" },
            sensePriority =
                props
                    .lastOrNull { it.sensePriorityProperty()?.NUMBER_LITERAL() != null }
                    ?.sensePriorityProperty()
                    ?.NUMBER_LITERAL()
                    ?.text
                    ?.toDouble(),
        )
    }

    /**
     * v4.4 — inline `lexicon { … }` sugar. Free-form object; the canonical
     * `terms`/`patterns`/`examples` list-of-string keys are extracted.
     */
    private fun visitLexiconBlock(ctx: TTRParser.Object_Context?): LexiconBlock {
        if (ctx == null) return LexiconBlock()
        val obj = visitObject(ctx)

        fun listKey(key: String): List<String> {
            val v = obj.entries[key]
            if (v is PropertyValue.ListValue) {
                return v.items.mapNotNull { item ->
                    when (item) {
                        is PropertyValue.StringValue -> item.raw
                        is PropertyValue.TripleStringValue -> item.raw
                        else -> null
                    }
                }
            }
            return emptyList()
        }
        return LexiconBlock(
            terms = listKey("terms"),
            patterns = listKey("patterns"),
            examples = listKey("examples"),
            // 0.17 — the sense fields an inline block writes beside its shorthands (TS `inlineSense`).
            default = (obj.entries["default"] as? PropertyValue.BoolValue)?.raw,
            sensePriority = (obj.entries["sense_priority"] as? PropertyValue.NumberValue)?.raw,
        )
    }

    private fun visitDefinition(ctx: TTRParser.DefinitionContext): Definition? {
        val od = ctx.objectDefinition() ?: return null
        return when {
            od.PROJECT() != null -> visitModel(od)
            od.TABLE() != null -> visitTable(od)
            od.VIEW() != null -> visitView(od)
            od.COLUMN() != null -> visitColumnTopLevel(od)
            od.INDEX() != null -> visitIndexTopLevel(od)
            od.CONSTRAINT() != null -> visitConstraintTopLevel(od)
            od.FK() != null -> visitFk(od)
            od.PROCEDURE() != null -> visitProcedure(od)
            od.ENTITY() != null -> visitEntity(od)
            od.ATTRIBUTE() != null -> visitAttributeTopLevel(od)
            od.RELATION() != null -> visitRelation(od)
            od.ER2DB_ENTITY() != null -> visitEr2dbEntity(od)
            od.ER2DB_ATTRIBUTE() != null -> visitEr2dbAttribute(od)
            od.ER2DB_RELATION() != null -> visitEr2dbRelation(od)
            od.QUERY() != null -> visitQuery(od)
            // Grammar 0.17 (⚑CN-P6) — one `def role` production, read by the file's model.
            od.ROLE() != null -> if (modelCode == "cn") visitConceptRole(od) else visitRole(od)
            od.ER2CNC_ROLE() != null -> visitEr2CncRole(od)
            od.DRILL_MAP() != null -> visitDrillMap(od)
            od.AREA() != null -> visitArea(od)
            od.SCHEMA() != null -> visitSchema(od)
            od.DOMAIN() != null -> visitMdDomain(od)
            od.DIMENSION() != null -> visitDimension(od)
            od.MAP() != null -> visitMdMap(od)
            od.HIERARCHY() != null -> visitHierarchy(od)
            od.MEASURE() != null -> visitMeasure(od)
            od.CUBELET() != null -> visitCubelet(od)
            od.MD2DB_CUBELET() != null -> visitMd2dbCubelet(od)
            od.MD2DB_DOMAIN() != null -> visitMd2dbDomain(od)
            od.MD2DB_MAP() != null -> visitMd2dbMap(od)
            od.MD2ER_DIMENSION() != null -> visitMd2erDimension(od)
            od.MD2ER_CUBELET() != null -> visitMd2erCubelet(od)
            od.WORLD() != null -> visitWorld(od)
            od.TERM() != null -> visitLexiconEntry(od, "term")
            od.PATTERN() != null -> visitLexiconEntry(od, "pattern")
            od.EXAMPLE() != null -> visitLexiconEntry(od, "example")
            // Grammar 0.16 — the automation kinds (`model automation`): one shared body.
            od.autoBody() != null -> visitAutomation(od)
            // Grammar 0.17 — the concept defs (`model cn`) and the cn bindings.
            else ->
                conceptStereotypeOf(od)?.let { visitConcept(od, it) }
                    ?: conceptBindingKindOf(od)?.let { visitConceptBinding(od, it) }
        }
    }

    // ----- Per-kind visitors -----

    private fun visitModel(od: TTRParser.ObjectDefinitionContext): ProjectDef {
        val props = od.projectDef().projectProperty()
        val descriptionProp = props.firstNotNullOfOrNull { it.descriptionProperty() }
        val description = descriptionOf(descriptionProp)
        val tags =
            props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } } ?: emptyList()
        val version = props.firstNotNullOfOrNull { it.versionProperty()?.STRING_LITERAL()?.let { stringLiteral(it) } }
        return ProjectDef(
            name = od.id().text,
            source = defSource(od),
            description = description,
            descriptionLocalized = descriptionLocalizedOf(descriptionProp),
            tags = tags,
            version = version,
        )
    }

    private fun visitTable(od: TTRParser.ObjectDefinitionContext): TableDef {
        val props = od.tableDef().tableProperty()
        return TableDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            primaryKey =
                props.firstNotNullOfOrNull { it.primaryKeyProperty()?.let { primaryKeyList(it.primaryKeyValue()) } }
                    ?: emptyList(),
            columns =
                props.flatMap {
                    it.columnsProperty()?.let { c -> visitColumnDefList(c.columnDefList()) }
                        ?: emptyList()
                },
            indices =
                props.flatMap {
                    it.indicesProperty()?.let { i -> visitIndexDefList(i.indexDefList()) }
                        ?: emptyList()
                },
            constraints =
                props.flatMap {
                    it.constraintsProperty()?.let { c -> visitConstraintDefList(c.constraintDefList()) }
                        ?: emptyList()
                },
            search =
                props.firstNotNullOfOrNull {
                    it.searchBlockProperty()?.let { s -> visitSearchBlock(s.searchBlock()) }
                } ?: SearchHintsValue(),
            semantics =
                props.firstNotNullOfOrNull {
                    it.semanticsBlockProperty()?.let { s -> visitSemanticsBlock(s.object_()) }
                },
            lexicon =
                props.firstNotNullOfOrNull {
                    it.lexiconBlockProperty()?.let { l -> visitLexiconBlock(l.object_()) }
                },
            management =
                props.firstNotNullOfOrNull { it.managementProperty()?.id()?.text },
            changeSemantics =
                props.firstNotNullOfOrNull {
                    it.changeSemanticsProperty()?.let { cs -> visitChangeSemantics(cs) }
                },
            writeback =
                props.firstNotNullOfOrNull {
                    it.writebackReservationProperty()?.let { w -> visitWriteback(w.object_()) }
                },
        )
    }

    /** EN-P1 (0.10) — `changeSemantics: <mode> { <role>: <column> }`. Mode + roles stay opaque here. */
    private fun visitChangeSemantics(ctx: TTRParser.ChangeSemanticsPropertyContext): ChangeSemanticsDecl {
        val mode = ctx.id().text
        val roles = LinkedHashMap<String, String>()
        for (entry in ctx.changeRoleMap()?.changeRoleEntry() ?: emptyList()) {
            val role = entry.id(0).text
            val column = entry.id(1).text
            if (role.isNotEmpty()) roles[role] = column
        }
        return ChangeSemanticsDecl(mode = mode, roles = roles, source = location(ctx))
    }

    /** EN-P1 (0.10) — Q-8 `writeback { … }` reservation. Parses inert (the semantics-block scalar shape). */
    private fun visitWriteback(ctx: TTRParser.Object_Context?): WritebackReservation {
        val entries = LinkedHashMap<String, SemanticsValue>()
        for (entry in ctx?.propertyList()?.propertyEntry() ?: emptyList()) {
            val key = entry.key()?.text ?: continue
            if (key.isEmpty()) continue
            val scalar = semanticsScalar(entry.value()) ?: continue
            entries[key] = scalar
        }
        return WritebackReservation(
            entries = entries,
            source = if (ctx != null) location(ctx) else SourceLocation.UNKNOWN,
        )
    }

    /** Grammar 0.14 — `def schema <name> { description?, tags?, columns: [...] }` (a named row schema). */
    private fun visitSchema(od: TTRParser.ObjectDefinitionContext): SchemaDef {
        val props = od.schemaDef().schemaProperty()
        return SchemaDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            columns =
                props.flatMap {
                    it.columnsProperty()?.let { c -> visitColumnDefList(c.columnDefList()) }
                        ?: emptyList()
                },
        )
    }

    private fun visitView(od: TTRParser.ObjectDefinitionContext): ViewDef {
        val props = od.viewDef().viewProperty()
        val definitionSqlBlock =
            props.firstNotNullOfOrNull {
                it.definitionSqlProperty()?.embeddedBlock()?.let { e -> walkEmbeddedBlock(e) }
            }
        return ViewDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            columns =
                props.flatMap {
                    it.columnsProperty()?.let { c -> visitColumnDefList(c.columnDefList()) }
                        ?: emptyList()
                },
            definitionSqlBlock = definitionSqlBlock,
            definitionSql = definitionSqlBlock?.let { embeddedValueText(it) },
            search =
                props.firstNotNullOfOrNull {
                    it.searchBlockProperty()?.let { s -> visitSearchBlock(s.searchBlock()) }
                } ?: SearchHintsValue(),
        )
    }

    private fun visitColumnTopLevel(od: TTRParser.ObjectDefinitionContext): ColumnDef =
        buildColumn(od.id().text, defSource(od), od.columnDef().columnProperty())

    private fun visitColumnInline(ctx: TTRParser.ColumnInlineContext): ColumnDef =
        buildColumn(ctx.id().text, location(ctx), ctx.columnDef().columnProperty())

    private fun buildColumn(
        name: String,
        loc: SourceLocation,
        props: List<TTRParser.ColumnPropertyContext>,
    ): ColumnDef {
        val search =
            props.firstNotNullOfOrNull {
                it.searchBlockProperty()?.let { s -> visitSearchBlock(s.searchBlock()) }
            } ?: SearchHintsValue()
        // v2.0.0: there is no top-level `searchable` on a column — the grammar
        // has no `searchableProperty` at column level, so `searchable: true`
        // outside a `search { }` block is rejected syntactically by the parser.
        return ColumnDef(
            name = name,
            source = loc,
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            type = props.firstNotNullOfOrNull { it.typeProperty()?.let { dataType(it.dataType()) } },
            optional =
                props.firstNotNullOfOrNull { it.optionalProperty()?.BOOLEAN_LITERAL()?.let { b -> b.text.toBoolean() } }
                    ?: false,
            isKey =
                props.firstNotNullOfOrNull { it.isKeyProperty()?.BOOLEAN_LITERAL()?.let { b -> b.text.toBoolean() } }
                    ?: false,
            indexed =
                props.firstNotNullOfOrNull { it.indexedProperty()?.BOOLEAN_LITERAL()?.let { b -> b.text.toBoolean() } }
                    ?: false,
            search = search,
            semantics =
                props.firstNotNullOfOrNull {
                    it.semanticsBlockProperty()?.let { s -> visitSemanticsBlock(s.object_()) }
                },
            lexicon =
                props.firstNotNullOfOrNull {
                    it.lexiconBlockProperty()?.let { l -> visitLexiconBlock(l.object_()) }
                },
        )
    }

    private fun visitIndexTopLevel(od: TTRParser.ObjectDefinitionContext): IndexDef =
        buildIndex(od.id().text, defSource(od), od.indexDef().indexProperty())

    private fun visitIndexInline(ctx: TTRParser.IndexInlineContext): IndexDef =
        buildIndex(ctx.id().text, location(ctx), ctx.indexDef().indexProperty())

    private fun buildIndex(
        name: String,
        loc: SourceLocation,
        props: List<TTRParser.IndexPropertyContext>,
    ): IndexDef =
        IndexDef(
            name = name,
            source = loc,
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            indexType = props.firstNotNullOfOrNull { it.indexTypeProperty()?.indexTypeValue()?.text },
            columns =
                props.firstNotNullOfOrNull { it.columnNamesListProperty()?.let { c -> stringList(c.listOfStrings()) } }
                    ?: emptyList(),
        )

    private fun visitConstraintTopLevel(od: TTRParser.ObjectDefinitionContext): ConstraintDef =
        buildConstraint(od.id().text, defSource(od), od.constraintDef().constraintProperty())

    private fun visitConstraintInline(ctx: TTRParser.ConstraintInlineContext): ConstraintDef =
        buildConstraint(ctx.id().text, location(ctx), ctx.constraintDef().constraintProperty())

    private fun buildConstraint(
        name: String,
        loc: SourceLocation,
        props: List<TTRParser.ConstraintPropertyContext>,
    ): ConstraintDef =
        ConstraintDef(
            name = name,
            source = loc,
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            constraintType =
                props.firstNotNullOfOrNull {
                    it.constraintTypeProperty()?.constraintTypeValue()?.text
                },
            columns =
                props.firstNotNullOfOrNull { it.columnNamesListProperty()?.let { c -> stringList(c.listOfStrings()) } }
                    ?: emptyList(),
        )

    private fun visitFk(od: TTRParser.ObjectDefinitionContext): FkDef {
        val props = od.fkDef().fkProperty()
        return FkDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            from = props.firstNotNullOfOrNull { it.fromProperty()?.let { f -> visitValue(f.value()) } },
            to = props.firstNotNullOfOrNull { it.toProperty()?.let { t -> visitValue(t.value()) } },
        )
    }

    private fun visitProcedure(od: TTRParser.ObjectDefinitionContext): ProcedureDef {
        val props = od.procedureDef().procedureProperty()
        return ProcedureDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            parameters =
                props.flatMap {
                    it.parametersProperty()?.let { p -> visitParameterList(p.parameterDefList()) }
                        ?: emptyList()
                },
            resultColumns =
                props.flatMap {
                    it.resultColumnsProperty()?.let { r -> visitColumnDefList(r.columnDefList()) }
                        ?: emptyList()
                },
        )
    }

    private fun visitEntity(od: TTRParser.ObjectDefinitionContext): EntityDef {
        val props = od.entityDef().entityProperty()
        return EntityDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            labelPlural =
                props.firstNotNullOfOrNull {
                    it.labelPluralProperty()?.STRING_LITERAL()?.let { s ->
                        stringLiteral(s)
                    }
                },
            nameAttribute =
                props.firstNotNullOfOrNull {
                    it
                        .nameAttributeProperty()
                        ?.id()
                        ?.let { makeRef(it) }
                },
            codeAttribute =
                props.firstNotNullOfOrNull {
                    it
                        .codeAttributeProperty()
                        ?.id()
                        ?.let { makeRef(it) }
                },
            aliases =
                props.firstNotNullOfOrNull { it.aliasesProperty()?.let { stringList(it.listOfStrings()) } }
                    ?: emptyList(),
            attributes =
                props.flatMap {
                    it.attributesProperty()?.let { a -> visitAttributeList(a.attributeDefList()) }
                        ?: emptyList()
                },
            roles =
                props.firstNotNullOfOrNull {
                    it.rolesProperty()?.let { r -> idList(r.listOfIds()) }
                } ?: emptyList(),
            displayLabel =
                props.firstNotNullOfOrNull {
                    it.displayLabelProperty()?.let { d -> visitLocalizedString(d.localizedString()) }
                },
            search =
                props.firstNotNullOfOrNull {
                    it.searchBlockProperty()?.let { s -> visitSearchBlock(s.searchBlock()) }
                } ?: SearchHintsValue(),
            semantics =
                props.firstNotNullOfOrNull {
                    it.semanticsBlockProperty()?.let { s -> visitSemanticsBlock(s.object_()) }
                },
            lexicon =
                props.firstNotNullOfOrNull {
                    it.lexiconBlockProperty()?.let { l -> visitLexiconBlock(l.object_()) }
                },
            binding =
                props.firstNotNullOfOrNull {
                    it.bindingProperty()?.let { m -> visitBindingProperty(m) }
                },
            concept = conceptSugarOf(props.map { it.conceptSugarProperty() }),
        )
    }

    private fun visitAttributeTopLevel(od: TTRParser.ObjectDefinitionContext): AttributeDef =
        buildAttribute(od.id().text, defSource(od), od.attributeDef().attributeProperty())

    private fun visitAttributeInline(ctx: TTRParser.AttributeInlineContext): AttributeDef =
        buildAttribute(ctx.id().text, location(ctx), ctx.attributeDef().attributeProperty())

    private fun buildAttribute(
        name: String,
        loc: SourceLocation,
        props: List<TTRParser.AttributePropertyContext>,
    ): AttributeDef {
        val search =
            props.firstNotNullOfOrNull {
                it.searchBlockProperty()?.let { s -> visitSearchBlock(s.searchBlock()) }
            } ?: SearchHintsValue()
        // v2.0.0: no top-level `searchable` on an attribute (lives in `search`).
        return AttributeDef(
            name = name,
            source = loc,
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            type = props.firstNotNullOfOrNull { it.typeProperty()?.let { dataType(it.dataType()) } },
            isKey =
                props.firstNotNullOfOrNull { it.isKeyProperty()?.BOOLEAN_LITERAL()?.let { b -> b.text.toBoolean() } }
                    ?: false,
            optional =
                props.firstNotNullOfOrNull { it.optionalProperty()?.BOOLEAN_LITERAL()?.let { b -> b.text.toBoolean() } }
                    ?: false,
            displayLabel =
                props.firstNotNullOfOrNull {
                    it.displayLabelProperty()?.let { d -> visitLocalizedString(d.localizedString()) }
                },
            valueLabels =
                props.firstNotNullOfOrNull {
                    it.valueLabelsProperty()?.let { v -> visitValueLabels(v.valueLabelsBody()) }
                } ?: emptyMap(),
            valueLabelAliases =
                props.firstNotNullOfOrNull {
                    it.valueLabelsProperty()?.let { v -> visitValueLabelAliases(v.valueLabelsBody()) }
                } ?: emptyMap(),
            search = search,
            semantics =
                props.firstNotNullOfOrNull {
                    it.semanticsBlockProperty()?.let { s -> visitSemanticsBlock(s.object_()) }
                },
            lexicon =
                props.firstNotNullOfOrNull {
                    it.lexiconBlockProperty()?.let { l -> visitLexiconBlock(l.object_()) }
                },
            binding =
                props.firstNotNullOfOrNull {
                    it.bindingProperty()?.let { m -> visitBindingProperty(m) }
                },
            domainRef =
                props.firstNotNullOfOrNull {
                    it.domainRefProperty()?.id()?.let { d -> makeRef(d) }
                },
            aggregation =
                props.firstNotNullOfOrNull {
                    it.aggregationProperty()?.let { a -> aggregationSpec(a.aggregationValue()) }
                },
            // Grammar 0.16 — lifecycle states (D-67).
            states =
                props
                    .lastOrNull { it.statesProperty() != null }
                    ?.statesProperty()
                    ?.listOfIds()
                    ?.id()
                    ?.map { it.text }
                    ?: emptyList(),
            transitions =
                props.lastOrNull { it.transitionsProperty() != null }?.transitionsProperty()?.transitionEntry()?.map {
                    AttributeTransition(it.id().text, it.listOfIds().id().map { t -> t.text }, location(it))
                } ?: emptyList(),
            concept = conceptSugarOf(props.map { it.conceptSugarProperty() }),
        )
    }

    // ----- MD (multidimensional) model visitors (v3.1 Layer A subset — MDS2) -----

    private fun visitMdDomain(od: TTRParser.ObjectDefinitionContext): MdDomainDef {
        val props = od.mdDomainDef().mdDomainProperty()
        return MdDomainDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            type = props.firstNotNullOfOrNull { it.typeProperty()?.let { t -> dataType(t.dataType()) } },
            domainKind = props.firstNotNullOfOrNull { it.kindProperty()?.id()?.text },
            publishMembers = props.any { it.publishProperty() != null },
            restrictMembers =
                props.firstNotNullOfOrNull { it.restrictProperty()?.let { r -> restrictMembersOf(r.restrictBlock()) } }
                    ?: emptyList(),
            concept = conceptSugarOf(props.map { it.conceptSugarProperty() }),
        )
    }

    /**
     * The enumerable member set of a `restrict:` block: `range: lo..hi` expands to `["lo", …, "hi"]`;
     * `members: { "k": … }` yields the declared keys. A `pattern`/`length`-only restrict is not
     * enumerable ⇒ empty. First enumerable clause wins (a domain declares one member set).
     */
    private fun restrictMembersOf(block: TTRParser.RestrictBlockContext?): List<String> {
        if (block == null) return emptyList()
        for (clause in block.restrictClause()) {
            val rv = clause.restrictValue()
            rv.rangeLiteral()?.let { rl ->
                val lo = rl.NUMBER_LITERAL(0)?.text?.toLongOrNull()
                val hi = rl.NUMBER_LITERAL(1)?.text?.toLongOrNull()
                // review-071 (R3): cap eager range materialization — a huge `restrict { range: 1..2e9 }`
                // would OOM the parser. Beyond the cap the range is treated as non-enumerable here (like an
                // unbounded domain); its members resolve through the member catalog at connect time. The
                // span is computed in BigInteger so a pathological lo/hi cannot overflow past the guard.
                if (lo != null &&
                    hi != null &&
                    lo <= hi &&
                    hi.toBigInteger() - lo.toBigInteger() < java.math.BigInteger.valueOf(MAX_RANGE_MEMBERS)
                ) {
                    return (lo..hi).map { it.toString() }
                }
            }
            rv.membersBlock()?.let { mb ->
                val members = mb.memberEntry().mapNotNull { stringForm(it.stringLiteralForm()) }
                if (members.isNotEmpty()) return members
            }
        }
        return emptyList()
    }

    private fun visitDimension(od: TTRParser.ObjectDefinitionContext): DimensionDef {
        val props = od.dimensionDef().dimensionProperty()
        return DimensionDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            key = props.firstNotNullOfOrNull { it.keyProperty()?.id()?.text },
            attributes =
                props.firstNotNullOfOrNull {
                    it.attributesProperty()?.let { a ->
                        visitAttributeList(a.attributeDefList())
                    }
                }
                    ?: emptyList(),
            hierarchies =
                props.firstNotNullOfOrNull { it.hierarchiesProperty()?.let { h -> idList(h.listOfIds()) } }
                    ?: emptyList(),
            concept = conceptSugarOf(props.map { it.conceptSugarProperty() }),
        )
    }

    private fun visitMdMap(od: TTRParser.ObjectDefinitionContext): MdMapDef {
        val props = od.mdMapDef().mdMapProperty()
        val calc = props.firstNotNullOfOrNull { it.calcProperty()?.let { c -> calcRefOf(c.calcRef()) } }
        val cardObj = props.firstNotNullOfOrNull { it.cardinalityProperty()?.let { c -> objectStringMap(c.object_()) } }
        return MdMapDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            from =
                props.firstNotNullOfOrNull { it.fromProperty()?.value()?.let { v -> refsFromValue(v) } } ?: emptyList(),
            to = props.firstNotNullOfOrNull { it.toProperty()?.value()?.let { v -> refsFromValue(v) } } ?: emptyList(),
            // A calc map is implicitly N:1; an explicit `{ from: N, to: 1 }` normalizes to N:1 / 1:1.
            cardinality = normalizeCardinality(cardObj, calc != null),
            calc = calc,
        )
    }

    private fun visitHierarchy(od: TTRParser.ObjectDefinitionContext): HierarchyDef {
        val props = od.hierarchyDef().hierarchyProperty()
        return HierarchyDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            dimensionRef = props.firstNotNullOfOrNull { it.dimensionRefProperty()?.id()?.let { d -> makeRef(d) } },
            levels =
                props.firstNotNullOfOrNull {
                    it.levelsProperty()?.levelList()?.levelEntry()?.map { e ->
                        hierarchyLevel(e)
                    }
                }
                    ?: emptyList(),
        )
    }

    private fun visitMeasure(od: TTRParser.ObjectDefinitionContext): MeasureDef =
        buildMeasure(od.id().text, defSource(od), od.measureDef().measureProperty())

    private fun buildMeasure(
        name: String,
        loc: SourceLocation,
        props: List<TTRParser.MeasurePropertyContext>,
    ): MeasureDef =
        MeasureDef(
            name = name,
            source = loc,
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            domainRef = props.firstNotNullOfOrNull { it.domainRefProperty()?.id()?.let { d -> makeRef(d) } },
            measureClass = props.firstNotNullOfOrNull { it.classProperty()?.id()?.text },
            aggregation =
                props.firstNotNullOfOrNull {
                    it.aggregationProperty()?.let { a ->
                        aggregationSpec(a.aggregationValue())
                    }
                },
            validBy = props.firstNotNullOfOrNull { it.validByProperty()?.id()?.text },
            concept = conceptSugarOf(props.map { it.conceptSugarProperty() }),
        )

    private fun visitCubelet(od: TTRParser.ObjectDefinitionContext): CubeletDef {
        val props = od.cubeletDef().cubeletProperty()
        return CubeletDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            grain =
                props.firstNotNullOfOrNull {
                    it.grainProperty()?.let { g ->
                        idList(g.listOfIds())
                    }
                } ?: emptyList(),
            measures =
                props.firstNotNullOfOrNull { it.measuresProperty()?.let { m -> cubeletMeasures(m.measuresValue()) } }
                    ?: emptyList(),
            concept = conceptSugarOf(props.map { it.conceptSugarProperty() }),
        )
    }

    // ----- MD → physical binding defs (md2db_*, contracts §2/§4) -----

    private fun visitMd2dbCubelet(od: TTRParser.ObjectDefinitionContext): Md2dbCubeletDef {
        val props = od.md2dbCubeletDef().md2dbCubeletProperty()
        return Md2dbCubeletDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            cubeletRef = props.firstNotNullOfOrNull { it.cubeletRefProperty()?.id()?.let { i -> makeRef(i) } },
            table = props.firstNotNullOfOrNull { it.targetProperty()?.let { t -> targetTableRef(t) } },
            shape = props.firstNotNullOfOrNull { it.shapeProperty()?.let { s -> walkShapeValue(s.shapeValue()) } },
            attributes =
                props.firstNotNullOfOrNull {
                    it.attributesMapProperty()?.let { a -> walkAttrColumnBindings(a.object_()) }
                } ?: emptyMap(),
            measures =
                props.firstNotNullOfOrNull {
                    it.measuresMapProperty()?.let { m -> walkMeasureColumnBindings(m.object_()) }
                } ?: emptyMap(),
            journaling =
                props.firstNotNullOfOrNull {
                    it.journalingProperty()?.let { j ->
                        walkJournalingValue(j.journalingValue())
                    }
                },
            allocation =
                props.firstNotNullOfOrNull {
                    it.allocationProperty()?.let { a ->
                        walkAllocationValue(a.allocationValue())
                    }
                },
        )
    }

    private fun visitMd2dbDomain(od: TTRParser.ObjectDefinitionContext): Md2dbDomainDef {
        val props = od.md2dbDomainDef().md2dbDomainProperty()
        val sourceObj = props.firstNotNullOfOrNull { it.sourceProperty()?.object_() }
        val columnSource =
            sourceObj?.let {
                val table = objField(it, "table")?.id()?.let { i -> makeRef(i) } ?: return@let null
                ColumnSource(table = table, column = objField(it, "column")?.let { v -> scalarText(v) } ?: "")
            }
        return Md2dbDomainDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            domainRef = props.firstNotNullOfOrNull { it.domainRefProperty()?.id()?.let { i -> makeRef(i) } },
            columnSource = columnSource,
        )
    }

    private fun visitMd2dbMap(od: TTRParser.ObjectDefinitionContext): Md2dbMapDef {
        val props = od.md2dbMapDef().md2dbMapProperty()
        return Md2dbMapDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            mapRef = props.firstNotNullOfOrNull { it.mapRefProperty()?.id()?.let { i -> makeRef(i) } },
            table = props.firstNotNullOfOrNull { it.targetProperty()?.let { t -> targetTableRef(t) } },
            columns =
                props.firstNotNullOfOrNull { it.columnsMapProperty()?.let { c -> objectStringMap(c.object_()) } }
                    ?: emptyMap(),
        )
    }

    private fun visitMd2erDimension(od: TTRParser.ObjectDefinitionContext): Md2erDimensionDef {
        val props = od.md2erDimensionDef().md2erDimensionProperty()
        return Md2erDimensionDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            dimensionRef = props.firstNotNullOfOrNull { it.dimensionRefProperty()?.id()?.let { i -> makeRef(i) } },
            entity = props.firstNotNullOfOrNull { it.entityProperty_()?.id()?.let { i -> makeRef(i) } },
            attributes =
                props.firstNotNullOfOrNull { it.attributesMapProperty()?.let { a -> mdErStringMap(a.object_()) } }
                    ?: emptyMap(),
        )
    }

    private fun visitMd2erCubelet(od: TTRParser.ObjectDefinitionContext): Md2erCubeletDef {
        val props = od.md2erCubeletDef().md2erCubeletProperty()
        // Physical props are a permissive parse superset here; semantics rejects them (md/md2er-physical-prop).
        val physical = mutableListOf<String>()
        if (props.any { it.shapeProperty() != null }) physical += "shape"
        if (props.any { it.journalingProperty() != null }) physical += "journaling"
        if (props.any { it.allocationProperty() != null }) physical += "allocation"
        return Md2erCubeletDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { t -> stringList(t.listOfStrings()) } }
                    ?: emptyList(),
            cubeletRef = props.firstNotNullOfOrNull { it.cubeletRefProperty()?.id()?.let { i -> makeRef(i) } },
            entity = props.firstNotNullOfOrNull { it.targetProperty()?.let { t -> targetTableRef(t) } },
            attributes =
                props.firstNotNullOfOrNull { it.attributesMapProperty()?.let { a -> mdErStringMap(a.object_()) } }
                    ?: emptyMap(),
            physicalProps = physical,
            measures =
                props.firstNotNullOfOrNull { it.measuresMapProperty()?.let { m -> mdErStringMap(m.object_()) } }
                    ?: emptyMap(),
        )
    }

    /** Flatten a `target:` to its table/entity ref: a bare id, or the object's `table` field. */
    private fun targetTableRef(tp: TTRParser.TargetPropertyContext): Reference? {
        tp.id()?.let { return makeRef(it) }
        return objField(tp.object_(), "table")?.id()?.let { makeRef(it) }
    }

    /** The `value` for [key] in an object, or null. */
    private fun objField(
        obj: TTRParser.Object_Context?,
        key: String,
    ): TTRParser.ValueContext? =
        obj
            ?.propertyList()
            ?.propertyEntry()
            ?.firstOrNull { it.key().id().text == key }
            ?.value()

    /** `shapeValue`: a bare id ⇒ wide; `{ long: { codeColumn, valueColumn } }` ⇒ long. */
    private fun walkShapeValue(sv: TTRParser.ShapeValueContext?): ShapeSpec? {
        if (sv == null) return null
        if (sv.id() != null) return ShapeSpec.Wide
        val longObj = objField(sv.object_(), "long")?.object_() ?: return ShapeSpec.Wide
        return ShapeSpec.Long(
            codeColumn = objField(longObj, "codeColumn")?.let { scalarText(it) } ?: "",
            valueColumn = objField(longObj, "valueColumn")?.let { scalarText(it) } ?: "",
        )
    }

    /** `journalingValue`: `diff` ⇒ Diff; another id ⇒ Overwrite; `{ invalidate: { validColumn } }` ⇒ Invalidate. */
    private fun walkJournalingValue(jv: TTRParser.JournalingValueContext?): JournalingSpec? {
        if (jv == null) return null
        jv.id()?.let { return if (it.text == "diff") JournalingSpec.Diff else JournalingSpec.Overwrite }
        val inv = objField(jv.object_(), "invalidate")?.object_() ?: return JournalingSpec.Overwrite
        return JournalingSpec.Invalidate(validColumn = objField(inv, "validColumn")?.let { scalarText(it) } ?: "")
    }

    /** `allocationValue`: a bare id ⇒ Uniform strategy; `{ dim: strategy, … }` ⇒ PerDimension (v0.10). */
    private fun walkAllocationValue(av: TTRParser.AllocationValueContext?): AllocationSpec? {
        if (av == null) return null
        av.id()?.let { return AllocationSpec.Uniform(it.text) }
        return AllocationSpec.PerDimension(objectStringMap(av.object_()))
    }

    /** `attributes:` object → attribute → column binding (`{ column }` or map-mediated `{ via, from }`). */
    private fun walkAttrColumnBindings(obj: TTRParser.Object_Context?): Map<String, AttrColumnBinding> {
        val out = LinkedHashMap<String, AttrColumnBinding>()
        obj?.propertyList()?.propertyEntry()?.forEach { e ->
            val name = e.key().id().text
            val bindingObj = e.value().object_()
            out[name] =
                if (bindingObj != null && objField(bindingObj, "via") != null) {
                    val via = objField(bindingObj, "via")?.id()?.let { makeRef(it) } ?: Reference("")
                    val fromObj = objField(bindingObj, "from")?.object_()
                    AttrColumnBinding.Via(
                        via = via,
                        from =
                            AttrColumnBinding.FromColumn(
                                table = objField(fromObj, "table")?.id()?.let { makeRef(it) } ?: Reference(""),
                                column = objField(fromObj, "column")?.let { scalarText(it) } ?: "",
                            ),
                    )
                } else {
                    val column =
                        if (bindingObj != null) {
                            objField(bindingObj, "column")?.let { scalarText(it) } ?: ""
                        } else {
                            scalarText(e.value()) ?: ""
                        }
                    AttrColumnBinding.Column(column)
                }
        }
        return out
    }

    /** `measures:` object → measure → column binding (`{ code }` long, else `{ column }` wide). */
    private fun walkMeasureColumnBindings(obj: TTRParser.Object_Context?): Map<String, MeasureColumnBinding> {
        val out = LinkedHashMap<String, MeasureColumnBinding>()
        obj?.propertyList()?.propertyEntry()?.forEach { e ->
            val name = e.key().id().text
            val bindingObj = e.value().object_()
            val code = bindingObj?.let { objField(it, "code") }
            out[name] =
                if (code != null) {
                    MeasureColumnBinding.Code(scalarText(code) ?: "")
                } else {
                    val column =
                        if (bindingObj != null) {
                            objField(bindingObj, "column")?.let { scalarText(it) } ?: ""
                        } else {
                            scalarText(e.value()) ?: ""
                        }
                    MeasureColumnBinding.Column(column)
                }
        }
        return out
    }

    // ----- MD helpers -----

    /** `aggregation: sum` → default=sum; `{ default: sum, time: latestValid }` → default + per-dim. */
    private fun aggregationSpec(ctx: TTRParser.AggregationValueContext): AggregationSpec =
        if (ctx.id() != null) {
            AggregationSpec(default = ctx.id().text)
        } else {
            val map = objectStringMap(ctx.object_())
            AggregationSpec(default = map["default"], perDimension = map.filterKeys { it != "default" })
        }

    /** `calc: truncToDay` or `calc: fiscalYearOfDate(fiscalYearStartMonth: 4)`. */
    private fun calcRefOf(ctx: TTRParser.CalcRefContext): CalcRef =
        CalcRef(
            name = ctx.id().text,
            args = ctx.calcArg().associate { it.id().text to (scalarText(it.value()) ?: "") },
            source = location(ctx),
        )

    private fun hierarchyLevel(ctx: TTRParser.LevelEntryContext): HierarchyLevel =
        HierarchyLevel(
            attribute = ctx.id(0).text,
            via = if (ctx.VIA() != null) makeRef(ctx.id(1)) else null,
            source = location(ctx),
        )

    /** `measures: [net, gross]` (refs) or `[def measure x { … }]` (inline defs). */
    private fun cubeletMeasures(ctx: TTRParser.MeasuresValueContext): List<CubeletMeasure> {
        ctx.listOfIds()?.let { return idList(it).map { r -> CubeletMeasure.Ref(r) } }
        val inline = ctx.measureInlineList() ?: return emptyList()
        return inline.id().indices.map { i ->
            CubeletMeasure.Inline(
                buildMeasure(inline.id(i).text, location(inline.id(i)), inline.measureDef(i).measureProperty()),
            )
        }
    }

    /** A map `from`/`to` value: a single id (`md.Day`) or a list of ids (`[md.A, md.B]`). */
    private fun refsFromValue(ctx: TTRParser.ValueContext): List<Reference> =
        when {
            ctx.id() != null -> listOf(makeRef(ctx.id()))
            ctx.list() != null -> ctx.list().value().mapNotNull { v -> v.id()?.let { makeRef(it) } }
            else -> emptyList()
        }

    /** A scalar `value` as its text: an id's path, or an unquoted string/number literal. */
    private fun scalarText(ctx: TTRParser.ValueContext): String? =
        when {
            ctx.id() != null -> ctx.id().text
            ctx.literal()?.stringLiteralForm() != null -> stringForm(ctx.literal().stringLiteralForm())
            ctx.literal()?.NUMBER_LITERAL() != null -> ctx.literal().NUMBER_LITERAL().text
            ctx.literal()?.BOOLEAN_LITERAL() != null -> ctx.literal().BOOLEAN_LITERAL().text
            else -> null
        }

    /** MD/ER maps carry ids or strings; other value forms are unavailable, as in TS and Python. */
    private fun mdErStringMap(ctx: TTRParser.Object_Context?): Map<String, String> =
        ctx?.propertyList()?.propertyEntry()?.associate { e ->
            val value = e.value()
            val text =
                value.id()?.text ?: value
                    .literal()
                    ?.stringLiteralForm()
                    ?.let { stringForm(it) }
                    .orEmpty()
            e.key().id().text to text
        } ?: emptyMap()

    private fun objectStringMap(ctx: TTRParser.Object_Context?): Map<String, String> =
        ctx?.propertyList()?.propertyEntry()?.associate { e -> e.key().id().text to (scalarText(e.value()) ?: "") }
            ?: emptyMap()

    /** Normalize `{ from: N, to: 1 }` to "N:1"/"1:1"; a calc map (or no card) defaults to N:1. */
    private fun normalizeCardinality(
        card: Map<String, String>?,
        isCalc: Boolean,
    ): String? =
        when {
            isCalc -> "N:1"
            card == null -> null
            card["from"] in setOf("N", "M", "*", "0..*", "1..*") &&
                card["to"] in setOf("N", "M", "*", "0..*", "1..*") -> "N:N"
            card["from"] == "1" && card["to"] == "1" -> "1:1"
            else -> "N:1"
        }

    private fun visitRelation(od: TTRParser.ObjectDefinitionContext): RelationDef {
        val props = od.relationDef().relationProperty()
        return RelationDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            from = props.firstNotNullOfOrNull { it.fromProperty()?.let { f -> visitValue(f.value()) } },
            to = props.firstNotNullOfOrNull { it.toProperty()?.let { t -> visitValue(t.value()) } },
            cardinality =
                props.firstNotNullOfOrNull {
                    it.cardinalityProperty()?.let { c ->
                        visitObject(c.object_())
                    }
                },
            join =
                props.firstNotNullOfOrNull { it.joinProperty()?.let { j -> visitListAsValues(j.list()) } }
                    ?: emptyList(),
            search =
                props.firstNotNullOfOrNull {
                    it.searchBlockProperty()?.let { s -> visitSearchBlock(s.searchBlock()) }
                } ?: SearchHintsValue(),
            binding =
                props.firstNotNullOfOrNull {
                    it.bindingProperty()?.let { m -> visitBindingProperty(m) }
                },
        )
    }

    private fun visitEr2dbEntity(od: TTRParser.ObjectDefinitionContext): Er2DbEntityDef {
        val props = od.er2dbEntityDef().er2dbEntityProperty()
        return Er2DbEntityDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            entity =
                props.firstNotNullOfOrNull {
                    it
                        .entityProperty_()
                        ?.id()
                        ?.let { makeRef(it) }
                },
            target = props.firstNotNullOfOrNull { it.targetProperty()?.let { t -> visitTargetValue(t) } },
            whereFilter =
                props.firstNotNullOfOrNull {
                    it.whereFilterProperty()?.let { w ->
                        visitObject(w.object_())
                    }
                },
        )
    }

    private fun visitEr2dbAttribute(od: TTRParser.ObjectDefinitionContext): Er2DbAttributeDef {
        val props = od.er2dbAttributeDef().er2dbAttributeProperty()
        return Er2DbAttributeDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            attribute =
                props.firstNotNullOfOrNull {
                    it
                        .attributeProperty_()
                        ?.id()
                        ?.let { makeRef(it) }
                },
            target = props.firstNotNullOfOrNull { it.targetProperty()?.let { t -> visitTargetValue(t) } },
        )
    }

    private fun visitEr2dbRelation(od: TTRParser.ObjectDefinitionContext): Er2DbRelationDef {
        val props = od.er2dbRelationDef().er2dbRelationProperty()
        return Er2DbRelationDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            relation =
                props.firstNotNullOfOrNull {
                    it
                        .relationProperty_()
                        ?.id()
                        ?.let { makeRef(it) }
                },
            fk =
                props.firstNotNullOfOrNull {
                    it
                        .fkProperty_()
                        ?.id()
                        ?.let { makeRef(it) }
                },
        )
    }

    private fun visitQuery(od: TTRParser.ObjectDefinitionContext): QueryDef {
        val props = od.queryDef().queryProperty()
        val language = props.firstNotNullOfOrNull { it.languageProperty()?.languageValue()?.text }
        val sourceTextBlock =
            props.firstNotNullOfOrNull {
                it.sourceTextProperty()?.embeddedBlock()?.let { e -> walkEmbeddedBlock(e) }
            }

        // `language:` is inferred from the tag and soft-deprecated when a tagged
        // block is present (DESIGN §6); a value disagreeing with the tag is an
        // error in TS. The Kotlin parser empties definitions on any error, so to
        // stay non-blocking (and keep the model usable) both are emitted as
        // warnings here — the value contract and the dump are unaffected.
        if (sourceTextBlock is TaggedBlockValue && language != null) {
            if (language != sourceTextBlock.language) {
                warn(
                    od,
                    "${DiagnosticCode.LanguageTagMismatch}: 'language: $language' disagrees with " +
                        "the block tag '${sourceTextBlock.tag}' (${sourceTextBlock.language})",
                )
            }
            warn(
                od,
                "${DiagnosticCode.DeprecatedLanguageProperty}: 'language' on query is deprecated; " +
                    "it is inferred from the '${sourceTextBlock.tag}' block tag",
            )
        }

        return QueryDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            language = language,
            skipSecurity =
                props
                    .lastOrNull { it.skipSecurityProperty() != null }
                    ?.skipSecurityProperty()
                    ?.BOOLEAN_LITERAL()
                    ?.text
                    ?.let {
                        it ==
                            "true"
                    },
            parameters =
                props.flatMap {
                    it.parametersProperty()?.let { p -> visitParameterList(p.parameterDefList()) }
                        ?: emptyList()
                },
            sourceTextBlock = sourceTextBlock,
            sourceText = sourceTextBlock?.let { embeddedValueText(it) },
            search =
                props.firstNotNullOfOrNull {
                    it.searchBlockProperty()?.let { s -> visitSearchBlock(s.searchBlock()) }
                } ?: SearchHintsValue(),
        )
    }

    // ----- role + er2cnc_role visitors -----

    private fun visitRole(od: TTRParser.ObjectDefinitionContext): RoleDef {
        val props = od.roleDef().roleProperty()
        // Grammar 0.17 (⚑CN-P6) — the cn role's properties ride the one `roleProperty` production. Only a
        // role outside `model cn` reaches here (a cn role is a [ConceptDef]), so they are rejected: a cnc /
        // automation role keeps its 0.16 surface and a typo (`lable:`) is still a parse error (review-114 M5).
        props.forEach { p ->
            val cnOnly: ParserRuleContext =
                p.cnEntry() ?: p.cnRefProperty() ?: p.groundsProperty()
                    ?: p.involvesProperty() ?: p.periodProperty() ?: p.lexiconBlockProperty()
                    ?: return@forEach
            val key = p.cnEntry()?.cnKey()?.text ?: cnOnly.getChild(0).text
            err(
                cnOnly,
                DiagnosticCode.UnknownProperty,
                "unknown role property '$key'${modelCode?.let { " in model $it" } ?: ""} — only a role in a " +
                    "'model cn' file takes the concept properties",
            )
        }
        return RoleDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            label =
                props.firstNotNullOfOrNull {
                    it.labelProperty()?.let { l -> visitLocalizedString(l.localizedString()) }
                },
            search =
                props.firstNotNullOfOrNull {
                    it.searchBlockProperty()?.let { s -> visitSearchBlock(s.searchBlock()) }
                } ?: SearchHintsValue(),
            // Grammar 0.16 — an automation role's typed properties (resolve / delivery / members), one
            // ordered entry list (review-114 M5: typed, so a typo stays a parse error).
            entries =
                props.mapNotNull { p ->
                    when {
                        p.resolveProperty() != null ->
                            AutoEntry(
                                "resolve",
                                visitAutoValue(p.resolveProperty().autoValue()),
                                location(p.resolveProperty()),
                            )
                        p.deliveryProperty() != null ->
                            AutoEntry(
                                "delivery",
                                visitAutoValue(p.deliveryProperty().autoValue()),
                                location(p.deliveryProperty()),
                            )
                        p.membersProperty() != null ->
                            AutoEntry(
                                "members",
                                visitAutoValue(p.membersProperty().autoValue()),
                                location(p.membersProperty()),
                            )
                        else -> null
                    }
                },
        )
    }

    private fun visitEr2CncRole(od: TTRParser.ObjectDefinitionContext): Er2CncRoleDef {
        val props = od.er2cncRoleDef().er2cncRoleProperty()
        return Er2CncRoleDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            entity =
                props.firstNotNullOfOrNull {
                    it
                        .entityProperty_()
                        ?.id()
                        ?.let { makeRef(it) }
                },
            role =
                props.firstNotNullOfOrNull {
                    it
                        .roleProperty_()
                        ?.id()
                        ?.let { makeRef(it) }
                },
        )
    }

    private fun visitDrillMap(od: TTRParser.ObjectDefinitionContext): DrillMapDef {
        val props = od.drillMapDef().drillMapProperty()
        val from =
            props.firstNotNullOfOrNull {
                it
                    .fromProperty()
                    ?.value()
                    ?.id()
                    ?.let { makeRef(it) }
            }
        val to =
            props.firstNotNullOfOrNull {
                it
                    .toProperty()
                    ?.value()
                    ?.id()
                    ?.let { makeRef(it) }
            }
        val argsMap =
            props
                .firstNotNullOfOrNull { it.argsProperty() }
                ?.drillArgsMap()
                ?.drillArgEntry()
                ?.associate { entry ->
                    entry.id().text to (stringForm(entry.stringLiteralForm()) ?: "")
                }
                ?: emptyMap()
        val display =
            props.firstNotNullOfOrNull {
                it.displayProperty()?.let { d -> visitLocalizedString(d.localizedString()) }
            }
        val overrideAuto =
            props
                .firstNotNullOfOrNull {
                    it.overrideProperty()?.BOOLEAN_LITERAL()?.text
                }?.equals("true", ignoreCase = true) ?: false
        return DrillMapDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull {
                    it.tagsProperty()?.let { stringList(it.listOfStrings()) }
                } ?: emptyList(),
            from = from,
            to = to,
            args = argsMap,
            display = display,
            overrideAuto = overrideAuto,
        )
    }

    /**
     * v3.0 — `def area <id> { description?, tags?, packages: [...], entities: [...] }`.
     * Mirrors the TS `walkAreaDef`. Members are dotted ids collapsed to strings;
     * per-member source spans are recorded in parallel lists.
     */
    private fun visitArea(od: TTRParser.ObjectDefinitionContext): AreaDef {
        val props = od.areaDef().areaProperty()
        val packagesCtx = props.firstNotNullOfOrNull { it.areaPackagesProperty() }
        val entitiesCtx = props.firstNotNullOfOrNull { it.areaEntitiesProperty() }
        val packageIds = packagesCtx?.id() ?: emptyList()
        val entityIds = entitiesCtx?.id() ?: emptyList()
        return AreaDef(
            name = od.id().text,
            source = defSource(od),
            description = descriptionOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            descriptionLocalized = descriptionLocalizedOf(props.firstNotNullOfOrNull { it.descriptionProperty() }),
            tags =
                props.firstNotNullOfOrNull { it.tagsProperty()?.let { stringList(it.listOfStrings()) } } ?: emptyList(),
            packages = packageIds.map { it.text },
            entities = entityIds.map { it.text },
            packageSources = packageIds.map { location(it) },
            entitySources = entityIds.map { location(it) },
        )
    }

    /**
     * v4.1 — `def world`. Mirrors the TS `walkWorldDef`. engine/executor/storage
     * are nested; manifest entries are transported opaque (T6 β data — MD5).
     */
    private fun visitWorld(od: TTRParser.ObjectDefinitionContext): WorldDef {
        val members = od.worldDef().worldMember()
        var description: String? = null
        var descriptionLocalized = LocalizedStringValue()
        var tags: List<String> = emptyList()
        var extends: String? = null
        val engines = mutableListOf<EngineDef>()
        val executors = mutableListOf<ExecutorDef>()
        val storages = mutableListOf<StorageDef>()
        val identities = mutableListOf<WorldAutoMember>()
        val grants = mutableListOf<WorldAutoMember>()
        val deployments = mutableListOf<WorldAutoMember>()
        val processes = mutableListOf<ProcessDef>()

        for (m in members) {
            val wp = m.worldProperty()
            if (wp != null) {
                wp.descriptionProperty()?.let {
                    description = descriptionOf(it)
                    descriptionLocalized = descriptionLocalizedOf(it)
                }
                wp.tagsProperty()?.let { tags = stringList(it.listOfStrings()) }
                wp.extendsProperty()?.let { extends = it.id().text }
                continue
            }
            // Grammar 0.16 — `def grant { … }` is unnamed (keyed by its who/program/as triple).
            if (m.GRANT() != null) {
                grants.add(WorldAutoMember("grant", null, location(m), visitAutoBody(m.autoBody().autoProperty())))
                continue
            }
            val memberName = m.id().text
            when {
                m.IDENTITY() != null ->
                    identities.add(
                        WorldAutoMember(
                            "identity",
                            memberName,
                            location(m),
                            visitAutoBody(m.autoBody().autoProperty()),
                        ),
                    )
                m.DEPLOYMENT() != null ->
                    deployments.add(
                        WorldAutoMember(
                            "deployment",
                            memberName,
                            location(m),
                            visitAutoBody(m.autoBody().autoProperty()),
                        ),
                    )
                m.PROCESS() != null -> processes.add(visitProcess(m.processBody(), memberName, location(m)))
                m.ENGINE() != null ->
                    engines.add(
                        visitEngineDef(m.engineDef().enginePartProperty(), memberName, location(m)),
                    )
                m.EXECUTOR() != null ->
                    executors.add(
                        visitEngineDef(m.executorDef().enginePartProperty(), memberName, location(m)).let {
                            ExecutorDef(
                                it.name,
                                it.source,
                                it.description,
                                it.descriptionLocalized,
                                it.tags,
                                it.type,
                                it.version,
                                it.extends,
                                it.manifest,
                            )
                        },
                    )
                m.STORAGE() != null -> storages.add(visitStorageDef(m.storageDef(), memberName, location(m)))
            }
        }

        return WorldDef(
            name = od.id().text,
            source = defSource(od),
            description = description,
            descriptionLocalized = descriptionLocalized,
            tags = tags,
            extends = extends,
            engines = engines,
            executors = executors,
            storages = storages,
            identities = identities,
            grants = grants,
            deployments = deployments,
            processes = processes,
        )
    }

    // ----- Grammar 0.17 — the conceptual model (`model cn`; CN-P1, cn contracts §1) -----
    // The concept defs, a cn `def role`, the cn bindings and the `concept { }` sugar read one way: every
    // property, in authored order, to one [ConceptProperty] (its key as written, its span) — typed where
    // the grammar types it, a free-form [ConceptProperty.Entry] otherwise. A repeated key is reported
    // (`ttr/duplicate-property`); the first one is read. Mirrors the TS walker's cn section.

    private fun conceptStereotypeOf(od: TTRParser.ObjectDefinitionContext): String? =
        when {
            od.SUBKIND() != null -> "subkind"
            od.KIND() != null -> "kind"
            od.EVENT() != null -> "event"
            od.PHASE() != null -> "phase"
            od.QUALITY() != null -> "quality"
            od.RELATION_TYPE() != null -> "relation_type"
            else -> null
        }

    private fun conceptBindingKindOf(od: TTRParser.ObjectDefinitionContext): String? =
        when {
            od.CN2MD_KIND() != null -> "cn2md_kind"
            od.CN2MD_ROLE() != null -> "cn2md_role"
            od.CN2MD_QUALITY() != null -> "cn2md_quality"
            od.CN2MD_EVENT() != null -> "cn2md_event"
            od.CN2ER_KIND() != null -> "cn2er_kind"
            od.CN2ER_ROLE() != null -> "cn2er_role"
            od.CN2ER_EVENT() != null -> "cn2er_event"
            od.CN2ER_QUALITY() != null -> "cn2er_quality"
            od.CN2ER_PHASE() != null -> "cn2er_phase"
            od.CN2DB_CONCEPT() != null -> "cn2db_concept"
            else -> null
        }

    private fun visitConcept(
        od: TTRParser.ObjectDefinitionContext,
        stereotype: String,
    ): ConceptDef? {
        val name = od.id()?.text ?: return null
        return ConceptDef(
            stereotype,
            name,
            defSource(od),
            visitConceptBody(od.cnBody()?.cnProperty().orEmpty(), "concept"),
        )
    }

    /** A `def role` in a `model cn` file (⚑CN-P6): a concept of stereotype `role`. */
    private fun visitConceptRole(od: TTRParser.ObjectDefinitionContext): ConceptDef? {
        val name = od.id()?.text ?: return null
        return ConceptDef("role", name, defSource(od), visitConceptBody(od.roleDef()?.roleProperty().orEmpty(), "role"))
    }

    private fun visitConceptBinding(
        od: TTRParser.ObjectDefinitionContext,
        bindingKind: String,
    ): ConceptBindingDef? {
        val name = od.id()?.text ?: return null
        val props = od.cnBindingBody()?.cnBindingProperty().orEmpty()
        return ConceptBindingDef(bindingKind, name, defSource(od), visitConceptBody(props, "binding"))
    }

    /**
     * One `concept { }` per host (cn contracts §1.4): a second one is `ttr/duplicate-property` and the first
     * is read — the cn body's rule, so CN-P2 and the formatter never meet a sugar the AST threw away.
     */
    private fun conceptSugarOf(props: List<TTRParser.ConceptSugarPropertyContext?>): ConceptSugar? {
        val sugars = props.filterNotNull()
        for (extra in sugars.drop(1)) {
            errAt(
                location(extra),
                DiagnosticCode.DuplicateProperty,
                "duplicate property 'concept' — the first one is read",
            )
        }
        return sugars.firstOrNull()?.let { visitConceptSugar(it) }
    }

    private fun visitConceptSugar(ctx: TTRParser.ConceptSugarPropertyContext): ConceptSugar =
        ConceptSugar(visitConceptBody(ctx.cnBody()?.cnProperty().orEmpty(), "concept"), location(ctx))

    /**
     * The cn body behind `cnBody`, `cnBindingBody` and — in a `model cn` file — `roleDef` (⚑CN-P6). Each
     * property context has exactly one child, the property rule, so one reader serves all three. [host] is
     * `concept`, `binding` or `role`; on a binding a free-form `filter:` is an expression block (A-CN-18).
     */
    private fun visitConceptBody(
        props: List<ParserRuleContext>,
        host: String,
    ): ConceptBody {
        val properties = mutableListOf<ConceptProperty>()
        val seen = mutableSetOf<String>()
        var body = ConceptBody()
        for (p in props) {
            val prop = visitConceptProperty(p.getChild(0), host) ?: continue
            if (!seen.add(prop.key)) {
                errAt(
                    prop.source,
                    DiagnosticCode.DuplicateProperty,
                    "duplicate property '${prop.key}' — the first one is read",
                )
            } else {
                when (prop) {
                    is ConceptProperty.Description ->
                        body =
                            body.copy(
                                description =
                                    (prop.value as? PropertyValue.StringValue)?.raw
                                        ?: (prop.value as? PropertyValue.TripleStringValue)?.raw,
                                descriptionLocalized = prop.localized ?: LocalizedStringValue(),
                            )
                    is ConceptProperty.Label -> body = body.copy(label = prop.value)
                    is ConceptProperty.Tags -> body = body.copy(tags = prop.value)
                    else -> {}
                }
            }
            properties += prop
        }
        return body.copy(properties = properties)
    }

    /** One cn property, or null when error recovery left it half-built (its syntax error is reported). */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun visitConceptProperty(
        c: ParseTree?,
        host: String,
    ): ConceptProperty? {
        if (c !is ParserRuleContext) return null
        val key = c.getChild(0)?.text ?: ""
        val source = location(c)
        return when (c) {
            is TTRParser.DescriptionPropertyContext -> {
                val value = c.stringLiteralForm()?.let { stringFormValue(it) }
                val localized = c.localizedString()?.let { visitLocalizedString(it) }
                if (value == null &&
                    localized == null
                ) {
                    null
                } else {
                    ConceptProperty.Description(key, value, localized, source)
                }
            }
            is TTRParser.LabelPropertyContext ->
                c.localizedString()?.let { ConceptProperty.Label(key, visitLocalizedString(it), source) }
            is TTRParser.TagsPropertyContext ->
                c.listOfStrings()?.let {
                    ConceptProperty.Tags(
                        key,
                        stringList(it),
                        source,
                    )
                }
            is TTRParser.CnRefPropertyContext -> referenceOf(c.id())?.let { ConceptProperty.Ref(key, it, source) }
            is TTRParser.CnFromPropertyContext -> referenceOf(c.id())?.let { ConceptProperty.From(key, it, source) }
            is TTRParser.CnUntilPropertyContext -> referenceOf(c.id())?.let { ConceptProperty.Until(key, it, source) }
            is TTRParser.CnPerPropertyContext -> referenceOf(c.id())?.let { ConceptProperty.Per(key, it, source) }
            is TTRParser.GroundsPropertyContext -> referenceOf(c.id())?.let { ConceptProperty.Grounds(key, it, source) }
            is TTRParser.ValueSpacePropertyContext ->
                referenceOf(c.id())?.let { ConceptProperty.ValueSpace(key, it, source) }
            is TTRParser.InvolvesPropertyContext ->
                ConceptProperty.Involves(
                    key,
                    c.participant().mapNotNull { pc ->
                        referenceOf(pc.id(0))?.let { ref ->
                            val label = if (pc.AS() != null) pc.id(1)?.text?.takeIf { it.isNotEmpty() } else null
                            ConceptParticipant(ref, label, location(pc))
                        }
                    },
                    source,
                )
            is TTRParser.PeriodPropertyContext ->
                c.cnObject()?.let {
                    ConceptProperty.Period(
                        key,
                        visitCnObject(it),
                        source,
                    )
                }
            is TTRParser.ExtentPropertyContext -> c.id()?.text?.let { ConceptProperty.Extent(key, it, source) }
            is TTRParser.BasisPropertyContext -> c.id()?.text?.let { ConceptProperty.Basis(key, it, source) }
            is TTRParser.DefinitionPropertyContext ->
                expressionTokenOf(c.embeddedBlock())?.let { walkExpressionBlock(it, key) }?.let {
                    ConceptProperty.DefinitionBlock(key, it, source)
                }
            is TTRParser.ConceptMembersPropertyContext ->
                expressionTokenOf(c.embeddedBlock())?.let { walkExpressionBlock(it, key) }?.let {
                    ConceptProperty.MembersBlock(key, it, source)
                }
            is TTRParser.LexiconBlockPropertyContext ->
                c.object_()?.let { ConceptProperty.Lexicon(key, visitLexiconBlock(it), source) }
            is TTRParser.TargetPropertyContext ->
                if (c.object_() != null ||
                    c.id() != null
                ) {
                    ConceptProperty.Target(key, visitTargetValue(c), source)
                } else {
                    null
                }
            is TTRParser.CnEntryContext -> {
                val entryKey = c.cnKey()?.text
                val valueCtx = c.cnValue()
                if (entryKey == null || valueCtx == null) return null
                // A binding's `filter:` is an expression block; anything else there is a mistake worth naming.
                if (host == "binding" && entryKey == "filter") {
                    val form = valueCtx.literal()?.stringLiteralForm()
                    val token = form?.TAGGED_BLOCK_LITERAL() ?: form?.TRIPLE_STRING_LITERAL() ?: form?.STRING_LITERAL()
                    token
                        ?.let {
                            walkExpressionBlock(
                                it,
                                entryKey,
                            )
                        }?.let { return ConceptProperty.FilterBlock(entryKey, it, source) }
                    errAt(
                        location(valueCtx),
                        DiagnosticCode.ExpressionBlockRequired,
                        "'filter:' takes an expression block — write it as \"\"\"…\"\"\" (optionally tagged \"\"\"pl)",
                    )
                }
                ConceptProperty.Entry(entryKey, visitCnValue(valueCtx), source)
            }
            else -> {
                // A cnc / automation role property on a concept role (review-114 M5, the other way round).
                if (host == "role") {
                    errAt(
                        source,
                        DiagnosticCode.UnknownProperty,
                        "unknown role property '$key' in model cn — search, resolve, delivery and members belong to " +
                            "a cnc or automation role",
                    )
                }
                null
            }
        }
    }

    /** The literal token of an `embeddedBlock` (tagged, triple or plain), or null when recovery dropped it. */
    private fun expressionTokenOf(ctx: TTRParser.EmbeddedBlockContext?): TerminalNode? =
        ctx?.TAGGED_BLOCK_LITERAL() ?: ctx?.TRIPLE_STRING_LITERAL() ?: ctx?.STRING_LITERAL()

    /** A `stringLiteralForm` as its carrier: a plain string, or a (dedented) triple-string. */
    private fun stringFormValue(ctx: TTRParser.StringLiteralFormContext): PropertyValue? {
        ctx.STRING_LITERAL()?.let { return PropertyValue.StringValue(stringLiteral(it), location(ctx)) }
        val triple = ctx.TRIPLE_STRING_LITERAL() ?: ctx.TAGGED_BLOCK_LITERAL() ?: return null
        return PropertyValue.TripleStringValue(tripleStringLiteral(triple), location(ctx))
    }

    private fun referenceOf(ctx: TTRParser.IdContext?): Reference? =
        if (ctx == null || isConjured(ctx)) null else makeRef(ctx)

    /** A token the error strategy invented to recover (`<missing IDENT>`) — never real input. */
    private fun isConjured(ctx: ParserRuleContext): Boolean =
        ctx.start == null || ctx.start.tokenIndex < 0 || ctx.text.startsWith("<missing ")

    private fun visitCnValue(ctx: TTRParser.CnValueContext): PropertyValue {
        ctx.cnObject()?.let { return visitCnObject(it) }
        ctx.cnList()?.let { l -> return PropertyValue.ListValue(l.cnValue().map { visitCnValue(it) }, location(l)) }
        ctx.literal()?.let { return visitLiteral(it) }
        ctx.id()?.let { return idValue(it.text, it) }
        return PropertyValue.NullValue(location(ctx))
    }

    /** A cn object value. Entries keep the TS walker's rule: a later duplicate key wins, silently. */
    private fun visitCnObject(ctx: TTRParser.CnObjectContext): PropertyValue.ObjectValue {
        val entries = LinkedHashMap<String, PropertyValue>()
        for (e in ctx.cnObjectEntry()) {
            val key = e.getChild(0)?.text ?: continue
            val valueCtx = e.cnValue() ?: continue
            entries[key] = visitCnValue(valueCtx)
        }
        return PropertyValue.ObjectValue(entries, location(ctx))
    }

    /**
     * An expression block (⚑CN-P5) — the `definitionSql` peeling, with the text's place in the file kept
     * exactly: [EmbeddedBlock.lineOffsets] holds where every line of the text starts once the dedent
     * removed its indent. Only `pl` (or no tag) names the expression language; a SQL-family tag is an
     * error, any other first-line word is read as text (the lexer cannot tell `"""exists␊…` from a tag).
     * Mirrors the TS `walkExpressionBlock` position for position.
     */
    @Suppress("LongMethod")
    private fun walkExpressionBlock(
        token: TerminalNode,
        key: String,
    ): EmbeddedBlock? {
        val sym = token.symbol
        if (sym.tokenIndex < 0 || sym.startIndex < 0) return null // conjured by error recovery
        val raw = sym.text ?: ""
        val col = sym.charPositionInLine
        val end = positionAfter(raw, sym.line, col)
        val source = SourceLocation(fileLabel, sym.line, col, end.first, end.second, sym.startIndex, sym.stopIndex + 1)

        if (sym.type == TTRLexer.STRING_LITERAL) {
            // Each escape takes two characters of the file and one of `text`: `escapes` keeps where, so a
            // position in `text` still maps onto the file.
            val quoted = raw.substring(1, raw.length - 1)
            val escapes = mutableListOf<Int>()
            val text =
                simpleEscape.replace(quoted) { m ->
                    escapes += quoted.codePointCount(0, m.range.first) - escapes.size
                    m.groupValues[1]
                }
            return blockOf(
                "string",
                text,
                source,
                sym.startIndex + 1,
                sym.line,
                col + 1,
                0,
                stringLineOffsets(text, escapes, sym.startIndex + 1),
                escapes,
            )
        }

        var inner = raw.substring(3, raw.length - 3)
        var innerStart = sym.startIndex + 3
        var form = "tripleString"
        if (sym.type == TTRLexer.TAGGED_BLOCK_LITERAL) {
            val opener = taggedOpener.find(inner)
            val tag = opener?.groupValues?.get(1)
            val tagLength = tag?.length ?: 0
            val tagSource =
                SourceLocation(
                    fileLabel,
                    sym.line,
                    col + 3,
                    sym.line,
                    col + 3 + tagLength,
                    sym.startIndex + 3,
                    sym.startIndex + 3 + tagLength,
                )
            when {
                opener != null && tag != null && resolveExpressionTag(tag) != null -> {
                    inner = inner.substring(opener.value.length)
                    innerStart += opener.value.length
                    form = "tagged"
                }
                tag != null && resolveTag(tag) != null ->
                    errAt(
                        tagSource,
                        DiagnosticCode.LanguageTagMismatch,
                        "'$key:' holds a TTR-P expression — the '$tag' tag names another language (use \"\"\"pl or no tag)",
                    )
                tag != null ->
                    warnAt(
                        tagSource,
                        "${DiagnosticCode.UnknownLanguageTag}: '$tag' on the opening line of '$key:' reads as a " +
                            "language tag; the block is kept as plain text (start the expression on the next line, " +
                            "or tag it \"\"\"pl)",
                    )
            }
        }

        // The dedent (contracts §2.9) drops a leading `\n`, then the common indent of the non-blank lines.
        val leadingNewline = if (inner.startsWith("\n")) 1 else 0
        val body = inner.substring(leadingNewline)
        val dedented = Dedent.applyTextwrapDedentWithIndent(inner)
        val indentWidth = dedented.indentWidth
        val text = if (form == "tagged") dropOneTrailingNewline(dedented.value) else dedented.value

        // Every raw line of `text`, its start offset in the file, indent removed (blank lines keep column 0).
        val rawLines = body.split("\n")
        val lineOffsets = mutableListOf<Int>()
        var offset = innerStart + leadingNewline
        for (i in text.split("\n").indices) {
            val rawLine = rawLines.getOrElse(i) { "" }
            lineOffsets += offset + (if (rawLine.isBlank()) 0 else indentWidth)
            offset += codePoints(rawLine) + 1
        }
        val first = positionAfter(raw.substring(0, innerStart - sym.startIndex + leadingNewline), sym.line, col)
        val firstColumn = first.second + (if (rawLines[0].isNotBlank()) indentWidth else 0)
        return blockOf(form, text, source, lineOffsets[0], first.first, firstColumn, indentWidth, lineOffsets)
    }

    @Suppress("LongParameterList")
    private fun blockOf(
        form: String,
        text: String,
        source: SourceLocation,
        textStart: Int,
        line: Int,
        column: Int,
        indentWidth: Int,
        lineOffsets: List<Int>,
        escapes: List<Int> = emptyList(),
    ): EmbeddedBlock {
        val lines = text.split("\n")
        val lastLine = lines.size - 1
        val lastOffset = lineOffsets.getOrElse(lastLine) { textStart }
        // Widths in code points — the unit of the parser's offsets and columns (and of a TTR-P position).
        val lastStart = codePoints(text) - codePoints(lines[lastLine])
        val lastWidth = codePoints(lines[lastLine]) + escapes.count { it >= lastStart }
        val endColumn =
            (
                if (lastLine == 0) {
                    column
                } else if (lines[lastLine].isNotEmpty()) {
                    indentWidth
                } else {
                    0
                }
            ) +
                lastWidth
        return EmbeddedBlock(
            language = "pl",
            form = form,
            text = text,
            textSource =
                SourceLocation(
                    source.file,
                    line,
                    column,
                    line + lastLine,
                    endColumn,
                    textStart,
                    lastOffset + lastWidth,
                ),
            lineOffsets = lineOffsets,
            indentWidth = indentWidth,
            source = source,
            escapes = escapes,
        )
    }

    /**
     * Where each line of a string-form block's [text] starts in the file: its code-point index in [text], plus
     * one for every escape before it (an escaped line break is what starts a second line).
     */
    private fun stringLineOffsets(
        text: String,
        escapes: List<Int>,
        textStart: Int,
    ): List<Int> {
        var start = 0
        return text.split("\n").map { line ->
            val offset = textStart + start + escapes.count { it < start }
            start += codePoints(line) + 1
            offset
        }
    }

    /** The number of code points in [s] — what ANTLR counts in offsets and columns (`length` counts UTF-16 units). */
    private fun codePoints(s: String): Int = s.codePointCount(0, s.length)

    /** The 1-based line and 0-based column (in code points) just past [text], which starts at [line]:[column]. */
    private fun positionAfter(
        text: String,
        line: Int,
        column: Int,
    ): Pair<Int, Int> {
        val parts = text.split("\n")
        return if (parts.size ==
            1
        ) {
            line to column + codePoints(text)
        } else {
            line + parts.size - 1 to codePoints(parts.last())
        }
    }

    /** JS `/\r?\n$/` (no `m` flag): one trailing newline. Not a Java regex — its `$` also matches before a final `\n`. */
    private fun dropOneTrailingNewline(s: String): String =
        when {
            s.endsWith("\r\n") -> s.dropLast(2)
            s.endsWith("\n") -> s.dropLast(1)
            else -> s
        }

    // ----- Grammar 0.16 — the automation model (`model automation`; AU-core-P0 S0.2) -----
    // One permissive body for six def kinds and the automation world members; values are
    // carried structurally — their meaning (contracts §2–§3) is validated in ttr-semantics.

    private fun visitAutomation(od: TTRParser.ObjectDefinitionContext): AutomationDef {
        val kind =
            when {
                od.PARAMETER() != null -> "parameter"
                od.ACTION() != null -> "action"
                od.SERVICE() != null -> "service"
                od.APPROVAL() != null -> "approval"
                od.PROGRAM() != null -> "program"
                else -> "recipe"
            }
        return AutomationDef(kind, od.id().text, defSource(od), visitAutoBody(od.autoBody().autoProperty()))
    }

    private fun visitAutoBody(props: List<TTRParser.AutoPropertyContext>): AutoBody {
        var body = AutoBody()
        val entries = mutableListOf<AutoEntry>()
        for (p in props) {
            when {
                p.descriptionProperty() != null ->
                    body =
                        body.copy(
                            description = descriptionOf(p.descriptionProperty()),
                            descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()),
                        )
                p.tagsProperty() != null -> body = body.copy(tags = stringList(p.tagsProperty().listOfStrings()))
                p.labelProperty() != null ->
                    body = body.copy(label = visitLocalizedString(p.labelProperty().localizedString()))
                p.typeProperty() != null -> body = body.copy(type = dataType(p.typeProperty().dataType()))
                p.columnsProperty() != null ->
                    body = body.copy(columns = visitColumnDefList(p.columnsProperty().columnDefList()))
                p.targetProperty() != null -> body = body.copy(target = visitTargetValue(p.targetProperty()))
                p.transitionProperty() != null ->
                    body = body.copy(transition = visitAutoTransition(p.transitionProperty()))
                p.setProperty() != null ->
                    body =
                        body.copy(
                            set =
                                AutoSet(
                                    p.setProperty().id(0).text,
                                    p.setProperty().id(1).text,
                                    location(p.setProperty()),
                                ),
                        )
                p.autoEntry() != null -> entries.add(visitAutoEntry(p.autoEntry()))
            }
        }
        return body.copy(entries = entries)
    }

    private fun visitAutoTransition(ctx: TTRParser.TransitionPropertyContext): AutoTransition {
        val target = ctx.transitionTarget()
        val guard = ctx.transitionGuard()
        val from =
            when {
                guard == null -> emptyList()
                guard.listOfIds() != null -> guard.listOfIds().id().map { it.text }
                else -> listOf(guard.id().text)
            }
        return AutoTransition(
            attribute = ctx.id().text,
            to = target?.takeIf { it.LBRACE() == null }?.id()?.text,
            toColumn = target?.takeIf { it.LBRACE() != null }?.id()?.text,
            from = from,
            source = location(ctx),
        )
    }

    private fun visitAutoEntry(ctx: TTRParser.AutoEntryContext): AutoEntry =
        AutoEntry(ctx.autoKey().text, visitAutoValue(ctx.autoValue()), location(ctx))

    private fun visitAutoValue(ctx: TTRParser.AutoValueContext): AutoValue {
        val src = location(ctx)
        ctx.durationLiteral()?.let { return AutoValue.Duration(it.NUMBER_LITERAL().text.toDouble(), it.id().text, src) }
        ctx.taggedValue()?.let { return AutoValue.Tagged(it.id().text, stringForm(it.stringLiteralForm()) ?: "", src) }
        ctx.callValue()?.let { c ->
            return AutoValue.Call(c.id().text, c.autoObject().autoEntry().map { visitAutoEntry(it) }, src)
        }
        if (ctx.STAR() != null) return AutoValue.Star(src)
        ctx.autoObject()?.let { o -> return AutoValue.Obj(o.autoEntry().map { visitAutoEntry(it) }, src) }
        ctx.autoList()?.let { l -> return AutoValue.ListV(l.autoValue().map { visitAutoValue(it) }, src) }
        ctx.literal()?.let { lit ->
            return when {
                lit.NUMBER_LITERAL() != null ->
                    AutoValue.Num(lit.NUMBER_LITERAL().text.toDouble(), src, lit.NUMBER_LITERAL().text)
                lit.BOOLEAN_LITERAL() != null -> AutoValue.Bool(lit.BOOLEAN_LITERAL().text.toBoolean(), src)
                lit.stringLiteralForm() != null -> {
                    val form = lit.stringLiteralForm()
                    val triple = form.STRING_LITERAL() == null
                    AutoValue.Str(stringForm(form) ?: "", triple, src)
                }
                else -> AutoValue.Null(src)
            }
        }
        ctx.id()?.let { id ->
            val path = id.text
            return AutoValue.Id(Reference(path, path.split("."), location(id)), path.split("."), src)
        }
        return AutoValue.Null(src)
    }

    private fun visitProcess(
        ctx: TTRParser.ProcessBodyContext,
        name: String,
        source: SourceLocation,
    ): ProcessDef {
        var proc = ProcessDef(name = name, source = source)
        val steps = mutableListOf<WorldAutoMember>()
        val entries = mutableListOf<AutoEntry>()
        for (p in ctx.processProperty()) {
            when {
                p.descriptionProperty() != null ->
                    proc =
                        proc.copy(
                            description = descriptionOf(p.descriptionProperty()),
                            descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()),
                        )
                p.tagsProperty() != null -> proc = proc.copy(tags = stringList(p.tagsProperty().listOfStrings()))
                p.stepsProperty() != null ->
                    p.stepsProperty().stepInline().forEach { st ->
                        steps.add(
                            WorldAutoMember(
                                "step",
                                st.id().text,
                                location(st),
                                visitAutoBody(st.autoBody().autoProperty()),
                            ),
                        )
                    }
                p.autoEntry() != null -> entries.add(visitAutoEntry(p.autoEntry()))
            }
        }
        return proc.copy(steps = steps, entries = entries)
    }

    private fun visitEngineDef(
        props: List<TTRParser.EnginePartPropertyContext>,
        name: String,
        source: SourceLocation,
    ): EngineDef {
        var description: String? = null
        var descriptionLocalized = LocalizedStringValue()
        var tags: List<String> = emptyList()
        var type: String? = null
        var version: String? = null
        var extends: String? = null
        val manifest = LinkedHashMap<String, PropertyValue>()
        for (p in props) {
            when {
                p.descriptionProperty() != null -> {
                    description = descriptionOf(p.descriptionProperty())
                    descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty())
                }
                p.tagsProperty() != null -> tags = stringList(p.tagsProperty().listOfStrings())
                p.typeProperty() != null -> type = p.typeProperty().dataType().text
                p.versionProperty() != null -> version = stringLiteral(p.versionProperty().STRING_LITERAL())
                p.extendsProperty() != null -> extends = p.extendsProperty().id().text
                p.propertyEntry() != null ->
                    manifest[
                        p
                            .propertyEntry()
                            .key()
                            .id()
                            .text,
                    ] = visitValue(p.propertyEntry().value())
            }
        }
        return EngineDef(name, source, description, descriptionLocalized, tags, type, version, extends, manifest)
    }

    private fun visitStorageDef(
        ctx: TTRParser.StorageDefContext,
        name: String,
        source: SourceLocation,
    ): StorageDef {
        var description: String? = null
        var descriptionLocalized = LocalizedStringValue()
        var tags: List<String> = emptyList()
        var type: String? = null
        var extends: String? = null
        var via: String? = null
        var hosts: List<String> = emptyList()
        var staging = false
        val schemas = mutableListOf<WorldSchemaDef>()
        val manifest = LinkedHashMap<String, PropertyValue>()
        for (p in ctx.storageProperty()) {
            when {
                p.descriptionProperty() != null -> {
                    description = descriptionOf(p.descriptionProperty())
                    descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty())
                }
                p.tagsProperty() != null -> tags = stringList(p.tagsProperty().listOfStrings())
                p.typeProperty() != null -> type = p.typeProperty().dataType().text
                p.extendsProperty() != null -> extends = p.extendsProperty().id().text
                p.viaProperty() != null -> via = p.viaProperty().id().text
                p.hostsProperty() != null ->
                    hosts =
                        p
                            .hostsProperty()
                            .listOfIds()
                            .id()
                            .map { it.text }
                p.stagingProperty() != null -> staging = p.stagingProperty().BOOLEAN_LITERAL().text == "true"
                p.SCHEMA() != null -> schemas.add(visitWorldSchema(p.worldSchemaDef(), p.id().text, location(p)))
                p.propertyEntry() != null ->
                    manifest[
                        p
                            .propertyEntry()
                            .key()
                            .id()
                            .text,
                    ] = visitValue(p.propertyEntry().value())
            }
        }
        return StorageDef(
            name,
            source,
            description,
            descriptionLocalized,
            tags,
            type,
            extends,
            via,
            hosts,
            staging,
            schemas,
            manifest,
        )
    }

    private fun visitWorldSchema(
        ctx: TTRParser.WorldSchemaDefContext,
        name: String,
        source: SourceLocation,
    ): WorldSchemaDef {
        val fields =
            ctx.worldSchemaField().map { f ->
                WorldSchemaField(name = f.id().text, type = f.dataType().text, source = location(f))
            }
        return WorldSchemaDef(name = name, source = source, fields = fields)
    }

    private fun visitLocalizedString(ctx: TTRParser.LocalizedStringContext?): LocalizedStringValue {
        if (ctx == null) return LocalizedStringValue(emptyMap())
        val entries =
            ctx.localizedEntry().associate { entry ->
                entry.id().text to (stringForm(entry.stringLiteralForm()) ?: "")
            }
        return LocalizedStringValue(entries)
    }

    private fun visitLocalizedStringList(
        ctx: TTRParser.LocalizedStringListContext?,
        propertyName: String,
    ): LocalizedStringListValue {
        if (ctx == null) return LocalizedStringListValue(emptyMap())
        val entries = LinkedHashMap<String, List<String>>()
        for (entry in ctx.localizedStringListEntry()) {
            val lang = entry.id().text
            if (entries.containsKey(lang)) {
                warn(
                    entry,
                    "duplicate language entry '$lang' in $propertyName — last value wins",
                )
            }
            if (lang !in supportedLanguages) {
                val supportedLangs =
                    supportedLanguages.sorted().joinToString(", ")
                warn(
                    entry,
                    "unknown language code '$lang' in $propertyName (supported: $supportedLangs)",
                )
            }
            val values = stringList(entry.listOfStrings())
            if (propertyName == "keywords") {
                values.forEach { token ->
                    if (token.any { it.isWhitespace() }) {
                        warn(
                            entry,
                            "keyword token '$token' contains whitespace — split into separate list entries",
                        )
                    }
                }
            }
            entries[lang] = values
        }
        return LocalizedStringListValue(entries)
    }

    private fun visitSearchBlock(ctx: TTRParser.SearchBlockContext?): SearchHintsValue {
        if (ctx == null) return SearchHintsValue()
        val subs = ctx.searchSubProperty()
        if (subs.isEmpty()) {
            warn(ctx, "empty `search { }` block — remove or populate to take effect")
        }

        subs
            .groupBy { sub ->
                when {
                    sub.keywordsProperty() != null -> "keywords"
                    sub.patternsProperty() != null -> "patterns"
                    sub.descriptionsProperty() != null -> "descriptions"
                    sub.examplesProperty() != null -> "examples"
                    sub.aliasesProperty() != null -> "aliases"
                    sub.searchableProperty() != null -> "searchable"
                    sub.fuzzyProperty() != null -> "fuzzy"
                    else -> "unknown"
                }
            }.filter { (_, group) -> group.size > 1 }
            .forEach { (kind, _) ->
                err(
                    ctx,
                    DiagnosticCode.DuplicateSearchProperty,
                    "'$kind' appears more than once in one search block",
                )
            }

        val patterns =
            subs.firstNotNullOfOrNull {
                it.patternsProperty()?.let { p -> stringList(p.listOfStrings()) }
            } ?: emptyList()
        if (patterns.size != patterns.toSet().size) {
            patterns
                .groupingBy { it }
                .eachCount()
                .filter { (_, c) -> c > 1 }
                .keys
                .forEach { dup -> warn(ctx, "duplicate pattern '$dup' in search.patterns") }
        }
        // 0.12: the boolean is optional — bare `searchable` IS the inclusion marker.
        val searchableProp = subs.firstNotNullOfOrNull { it.searchableProperty() }
        val searchable =
            if (searchableProp == null) {
                false
            } else {
                searchableProp
                    .BOOLEAN_LITERAL()
                    ?.text
                    ?.toBoolean() ?: true
            }
        val method =
            searchableProp?.matchMethodAttr()?.matchMethodValue()?.let { mv ->
                MatchMethodValue(
                    name = mv.id().text,
                    argument = mv.NUMBER_LITERAL()?.text?.toDoubleOrNull(),
                )
            }
        val fuzzyProp = subs.firstNotNullOfOrNull { it.fuzzyProperty() }
        val fuzzy = fuzzyProp?.BOOLEAN_LITERAL()?.text?.toBoolean() ?: false
        if (fuzzy && !searchable) {
            warn(ctx, "fuzzy-without-searchable: `fuzzy: true` has no effect unless `searchable: true`")
        }
        return SearchHintsValue(
            keywords =
                subs.firstNotNullOfOrNull {
                    it.keywordsProperty()?.let { k -> visitLocalizedStringList(k.localizedStringList(), "keywords") }
                } ?: LocalizedStringListValue(),
            patterns = patterns,
            descriptions =
                subs.firstNotNullOfOrNull {
                    it.descriptionsProperty()?.let { d ->
                        visitLocalizedStringList(d.localizedStringList(), "descriptions")
                    }
                } ?: LocalizedStringListValue(),
            examples =
                subs.firstNotNullOfOrNull {
                    it.examplesProperty()?.let { e -> stringList(e.listOfStrings()) }
                } ?: emptyList(),
            aliases =
                subs.firstNotNullOfOrNull {
                    it.aliasesProperty()?.let { a -> stringList(a.listOfStrings()) }
                } ?: emptyList(),
            searchable = searchable,
            fuzzy = fuzzy,
            method = method,
            fuzzyAuthored = fuzzyProp != null,
        )
    }

    /**
     * Grounding Phase 1 (grammar 4.2) — fold a `semantics { … }` block's free-form
     * `object_` body into a record. The parser stays mechanical: no vocabulary/shape
     * checks (that is ttr-semantics' job). Ids become their identifier text, string
     * literals are unquoted, numbers/booleans/null keep their primitive form, and
     * lists/nested objects are carried verbatim (MS, vocabulary v3). Only a
     * `functionCall` value — which has no data meaning here — is rejected into a
     * `ttr/semantics-non-scalar` diagnostic and dropped. Duplicate keys are last-wins
     * and recorded in `duplicateProperties` (the search-block pattern). Mirrors
     * `walkSemanticsBlock` in the TS walker.
     */
    private fun visitSemanticsBlock(ctx: TTRParser.Object_Context?): SemanticsBlock {
        val entries = LinkedHashMap<String, SemanticsValue>()
        val nestedDuplicates = mutableListOf<String>()
        val seen = LinkedHashMap<String, Int>()
        for (entry in ctx?.propertyList()?.propertyEntry() ?: emptyList()) {
            val key = entry.key()?.text ?: continue
            if (key.isEmpty()) continue
            seen[key] = (seen[key] ?: 0) + 1
            // Kotlin `null` from [semanticsStructured] is the NON_SCALAR sentinel; a
            // legitimate `null` value arrives as [SemanticsValue.NullV].
            val value = semanticsStructured(entry.value(), key, nestedDuplicates)
            if (value == null) {
                err(
                    entry.value() ?: entry,
                    DiagnosticCode.SemanticsNonScalarValue,
                    "semantics entries cannot be a function call; '$key' has one",
                )
                continue
            }
            entries[key] = value
        }
        val duplicateProperties = seen.filterValues { it > 1 }.keys.toList() + nestedDuplicates
        return SemanticsBlock(
            entries = entries,
            duplicateProperties = duplicateProperties,
            source = if (ctx != null) location(ctx) else SourceLocation.UNKNOWN,
        )
    }

    /**
     * MS (vocabulary v3) — read a semantics `value` WITH its structure: scalars as
     * before, plus lists and nested objects carried verbatim. Returns `null` (the
     * NON_SCALAR sentinel) for a `functionCall`, at any depth.
     *
     * The parser stays mechanical. It does not know that `measures:` may hold a list
     * while `role:` may not — that is vocabulary knowledge and lives in ttr-semantics,
     * which now owns the "this key must be scalar" judgement that used to be made here.
     *
     * Recurses without a depth limit, mirroring the grammar (`value : … | list |
     * object_ | …` is self-referential). Duplicate keys inside a nested object are
     * last-wins, the same rule the block itself uses — and, like the block's own, they
     * are RECORDED: [path] carries the dotted/indexed route to this value and each repeat
     * is appended to [duplicates] as e.g. `measures[1].attribute`, which ttr-semantics
     * reports through the existing `SemDuplicateKey`. Counting repeats is bookkeeping,
     * not vocabulary, so it belongs here for the same reason the block-level count does.
     *
     * Mirrors `semanticsStructured` in the TS walker.
     */
    private fun semanticsStructured(
        ctx: TTRParser.ValueContext?,
        path: String,
        duplicates: MutableList<String>,
    ): SemanticsValue? {
        if (ctx == null) return SemanticsValue.NullV
        ctx.list()?.let { listCtx ->
            val items = mutableListOf<SemanticsValue>()
            for ((i, item) in listCtx.value().withIndex()) {
                items.add(semanticsStructured(item, "$path[$i]", duplicates) ?: return null)
            }
            return SemanticsValue.ListV(items)
        }
        ctx.object_()?.let { objCtx ->
            val obj = LinkedHashMap<String, SemanticsValue>()
            val seen = LinkedHashMap<String, Int>()
            for (entry in objCtx.propertyList()?.propertyEntry() ?: emptyList()) {
                val key = entry.key()?.text ?: continue
                if (key.isEmpty()) continue
                seen[key] = (seen[key] ?: 0) + 1
                obj[key] = semanticsStructured(entry.value(), "$path.$key", duplicates) ?: return null
            }
            seen.filterValues { it > 1 }.keys.forEach { duplicates.add("$path.$it") }
            return SemanticsValue.ObjV(obj)
        }
        return semanticsScalar(ctx)
    }

    /**
     * Extract a scalar [SemanticsValue] from a `value` context, or `null` (the
     * NON_SCALAR sentinel) for a nested object/list/functionCall. Ids collapse to
     * their dotted text — resolution is ttr-semantics' job, so the parser keeps
     * them opaque.
     */
    private fun semanticsScalar(ctx: TTRParser.ValueContext?): SemanticsValue? {
        if (ctx == null) return SemanticsValue.NullV
        ctx.id()?.let { return SemanticsValue.Str(it.text) }
        val lit = ctx.literal()
        if (lit != null) {
            return when {
                lit.NUMBER_LITERAL() != null -> SemanticsValue.Num(lit.NUMBER_LITERAL().text.toDouble())
                lit.BOOLEAN_LITERAL() != null -> SemanticsValue.Bool(lit.BOOLEAN_LITERAL().text.toBoolean())
                lit.stringLiteralForm() != null -> SemanticsValue.Str(stringForm(lit.stringLiteralForm()) ?: "")
                lit.NULL_LITERAL() != null -> SemanticsValue.NullV
                else -> SemanticsValue.NullV
            }
        }
        return null // list | object_ | functionCall → NON_SCALAR
    }

    private fun visitValueLabels(ctx: TTRParser.ValueLabelsBodyContext?): Map<String, LocalizedStringValue> {
        if (ctx == null) return emptyMap()
        val out = LinkedHashMap<String, LocalizedStringValue>()
        for (entry in ctx.valueLabelEntry()) {
            val key = stringForm(entry.stringLiteralForm()) ?: continue
            // Last write wins on duplicate keys; the loader surfaces a duplicate-key warning.
            out[key] = valueLabelLabel(entry.valueLabelValue())
        }
        return out
    }

    /** A4-β (v4.4 S2) — the per-value `aliases` list, present entries only. */
    private fun visitValueLabelAliases(ctx: TTRParser.ValueLabelsBodyContext?): Map<String, List<String>> {
        if (ctx == null) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for (entry in ctx.valueLabelEntry()) {
            val key = stringForm(entry.stringLiteralForm()) ?: continue
            val aliases =
                entry
                    .valueLabelValue()
                    ?.valueLabelField()
                    ?.firstOrNull { it.id().text == "aliases" && it.listOfStrings() != null }
                    ?.let { stringList(it.listOfStrings()) } ?: emptyList()
            if (aliases.isNotEmpty()) out[key] = aliases
        }
        return out
    }

    /**
     * A4-β — the localized label of a value-label value: the widened `label:` map
     * when present, else the legacy flat locale→string fields.
     */
    private fun valueLabelLabel(ctx: TTRParser.ValueLabelValueContext?): LocalizedStringValue {
        if (ctx == null) return LocalizedStringValue(emptyMap())
        val legacy = LinkedHashMap<String, String>()
        for (field in ctx.valueLabelField()) {
            val fk = field.id().text
            if (fk == "label" && field.localizedString() != null) {
                return visitLocalizedString(field.localizedString())
            }
            if (fk == "aliases") continue
            field.stringLiteralForm()?.let { legacy[fk] = stringForm(it) ?: "" }
        }
        return LocalizedStringValue(legacy)
    }

    private fun idList(ctx: TTRParser.ListOfIdsContext?): List<Reference> {
        if (ctx == null) return emptyList()
        return ctx.id().map { makeRef(it) }
    }

    // ----- List visitors -----

    private fun visitColumnDefList(ctx: TTRParser.ColumnDefListContext?): List<ColumnDef> {
        if (ctx == null) return emptyList()
        return ctx.children
            .filterIsInstance<TTRParser.ColumnInlineContext>()
            .map { visitColumnInline(it) }
    }

    private fun visitIndexDefList(ctx: TTRParser.IndexDefListContext?): List<IndexDef> {
        if (ctx == null) return emptyList()
        return ctx.children
            .filterIsInstance<TTRParser.IndexInlineContext>()
            .map { visitIndexInline(it) }
    }

    private fun visitConstraintDefList(ctx: TTRParser.ConstraintDefListContext?): List<ConstraintDef> {
        if (ctx == null) return emptyList()
        return ctx.children
            .filterIsInstance<TTRParser.ConstraintInlineContext>()
            .map { visitConstraintInline(it) }
    }

    private fun visitAttributeList(ctx: TTRParser.AttributeDefListContext?): List<AttributeDef> {
        if (ctx == null) return emptyList()
        return ctx.children
            .filterIsInstance<TTRParser.AttributeInlineContext>()
            .map { visitAttributeInline(it) }
    }

    /**
     * Each parameter is a `{ name: <id>, type: <dataType>, label: "...", direction: <id> }` block
     * (`paramProperty` in the grammar; `direction` only on procedure params). It is materialised as a
     * [PropertyValue.ObjectValue] so the renderer can round-trip it: `name`/`type`/`direction` keep their
     * bare-identifier shape via [PropertyValue.IdValue], `label` is a plain string.
     */
    private fun visitParameterList(ctx: TTRParser.ParameterDefListContext?): List<PropertyValue> {
        if (ctx == null) return emptyList()
        return ctx.children
            .filterIsInstance<TTRParser.ParameterInlineContext>()
            .map { inline ->
                val entries = LinkedHashMap<String, PropertyValue>()
                for (prop in inline.paramProperty()) {
                    when {
                        prop.defaultValueProperty() != null -> {
                            val value = prop.defaultValueProperty()
                            stringForm(value.stringLiteralForm())?.let {
                                entries["defaultValue"] = PropertyValue.StringValue(it, location(value))
                            }
                        }
                        prop.bindsProperty() != null -> {
                            val value = prop.bindsProperty()
                            entries["binds"] =
                                PropertyValue.ListValue(
                                    value.listOfIds().id().map { idValue(it.text, it) },
                                    location(value),
                                )
                        }
                        prop.nameProperty() != null ->
                            entries["name"] = idValue(prop.nameProperty().id().text, prop.nameProperty())
                        prop.typeProperty() != null ->
                            dataType(prop.typeProperty().dataType())?.let { dt ->
                                entries["type"] = idValue(dt.name, prop.typeProperty())
                            }
                        prop.paramLabelProperty() != null ->
                            stringForm(prop.paramLabelProperty().stringLiteralForm())?.let { lbl ->
                                entries["label"] = PropertyValue.StringValue(lbl, location(prop.paramLabelProperty()))
                            }
                        prop.directionProperty() != null ->
                            entries["direction"] = idValue(prop.directionProperty().id().text, prop.directionProperty())
                    }
                }
                PropertyValue.ObjectValue(entries, location(inline))
            }
    }

    // ----- Generic value visitor -----

    private fun visitValue(ctx: TTRParser.ValueContext): PropertyValue =
        when {
            ctx.literal() != null -> visitLiteral(ctx.literal())
            ctx.id() != null -> idValue(ctx.id().text, ctx.id())
            ctx.list() != null -> PropertyValue.ListValue(visitListAsValues(ctx.list()), location(ctx.list()))
            ctx.object_() != null -> visitObject(ctx.object_())
            ctx.functionCall() != null -> visitFunctionCall(ctx.functionCall())
            else -> PropertyValue.NullValue(location(ctx))
        }

    private fun visitLiteral(ctx: TTRParser.LiteralContext): PropertyValue =
        when {
            ctx.NUMBER_LITERAL() != null ->
                PropertyValue.NumberValue(
                    ctx.NUMBER_LITERAL().text.toDouble(),
                    location(ctx),
                )
            ctx.stringLiteralForm() != null -> {
                val form = ctx.stringLiteralForm()
                if (form.TRIPLE_STRING_LITERAL() != null) {
                    PropertyValue.TripleStringValue(tripleStringLiteral(form.TRIPLE_STRING_LITERAL()), location(ctx))
                } else if (form.TAGGED_BLOCK_LITERAL() != null) {
                    // A `"""word␊…"""` outside sourceText / definitionSql is a plain triple-string (the TS
                    // `walkStringLiteralForm`); the first-line word is text, not a tag.
                    PropertyValue.TripleStringValue(tripleStringLiteral(form.TAGGED_BLOCK_LITERAL()), location(ctx))
                } else {
                    PropertyValue.StringValue(stringLiteral(form.STRING_LITERAL()), location(ctx))
                }
            }
            ctx.BOOLEAN_LITERAL() != null ->
                PropertyValue.BoolValue(
                    ctx.BOOLEAN_LITERAL().text.toBoolean(),
                    location(ctx),
                )
            ctx.NULL_LITERAL() != null -> PropertyValue.NullValue(location(ctx))
            else -> PropertyValue.NullValue(location(ctx))
        }

    private fun visitListAsValues(ctx: TTRParser.ListContext?): List<PropertyValue> =
        ctx?.value()?.map { visitValue(it) } ?: emptyList()

    // ----- v3.0: inline binding visitors (was v2.1 `mapping:`) -----

    /**
     * Walks `binding: <bareId>` or `binding: { ... }` into the typed
     * [BindingProperty] hierarchy. Mirrors `walkBindingProperty` in the modeler
     * TS walker.
     */
    private fun visitBindingProperty(ctx: TTRParser.BindingPropertyContext): BindingProperty {
        val valueCtx = ctx.bindingValue()
        val bareId = valueCtx.id()
        if (bareId != null) {
            return BindingPropertyBareId(
                id = makeRef(bareId),
                source = location(valueCtx),
            )
        }
        val blockCtx = valueCtx.bindingBlock()
        var target: TargetValue? = null
        var columns: List<BindingColumnEntry> = emptyList()
        var fk: Reference? = null
        for (p in blockCtx.bindingBlockProperty()) {
            p.targetProperty()?.let { tp -> target = visitTargetValue(tp) }
            p.bindingColumnsProperty()?.let { cp ->
                columns = visitBindingColumnMap(cp.bindingColumnMap())
            }
            p.fkProperty_()?.let { fp ->
                fp.id()?.let { fk = makeRef(it) }
            }
        }
        return BindingPropertyBlock(
            target = target,
            columns = columns,
            fk = fk,
            source = location(blockCtx),
        )
    }

    /**
     * Walks `target: { ... }` or the relaxed `target: <bareId>` form. Used both
     * by the inline binding block and by the explicit `def er2db_*` walkers.
     */
    private fun visitTargetValue(ctx: TTRParser.TargetPropertyContext): TargetValue {
        val idCtx = ctx.id()
        if (idCtx != null) {
            return TargetReferenceValue(
                ref = makeRef(idCtx),
                source = location(idCtx),
            )
        }
        return TargetObjectValue(
            obj = visitObject(ctx.object_()),
            source = location(ctx.object_()),
        )
    }

    private fun visitBindingColumnMap(ctx: TTRParser.BindingColumnMapContext?): List<BindingColumnEntry> {
        if (ctx == null) return emptyList()
        return ctx.bindingColumnEntry().map { e ->
            val name = e.id().text
            val v = e.bindingColumnValue()
            val value: BindingColumnValue =
                when {
                    v.id() != null ->
                        BindingColumnBareId(
                            id = makeRef(v.id()),
                            source = location(v),
                        )
                    v.bindingTargetValue() != null -> {
                        // Form (b) — `kód_artiklu: { target: KOD_ZBOZI }` or
                        // `product_name: { target: { column: i_product_name } }`. Mirror the
                        // modeler-side shape: wrap in a synthetic { target: <inner> } object
                        // so downstream consumers see the same structure as form (c).
                        val mtv = v.bindingTargetValue()
                        val inner: PropertyValue =
                            if (mtv.id() != null) {
                                idValue(mtv.id().text, mtv.id())
                            } else {
                                visitObject(mtv.object_())
                            }
                        BindingColumnObject(
                            obj = PropertyValue.ObjectValue(mapOf("target" to inner), location(v)),
                            source = location(v),
                        )
                    }
                    else ->
                        BindingColumnObject(
                            obj = visitObject(v.object_()),
                            source = location(v),
                        )
                }
            BindingColumnEntry(
                name = name,
                value = value,
                source = location(e),
            )
        }
    }

    private fun visitObject(ctx: TTRParser.Object_Context?): PropertyValue.ObjectValue {
        if (ctx == null) return PropertyValue.ObjectValue(emptyMap(), SourceLocation.UNKNOWN)
        // [PropertyValue.ObjectValue.entries] is a Map (last-write-wins) — see the
        // documented AST-NAMING divergence from TS's ordered `ObjectEntry[]`. A
        // duplicate key therefore collapses (and any reference inside the dropped
        // value would vanish from `collectAllReferences`), so surface it as a
        // warning rather than dropping it silently — matching how duplicate
        // language entries / search properties are handled elsewhere.
        val propEntries = ctx.propertyList()?.propertyEntry() ?: emptyList()
        val entries = LinkedHashMap<String, PropertyValue>()
        for (entry in propEntries) {
            val key = entry.key().id().text
            if (entries.containsKey(key)) {
                warn(entry, "duplicate key '$key' in object — last value wins (earlier value dropped)")
            }
            entries[key] = visitValue(entry.value())
        }
        return PropertyValue.ObjectValue(entries, location(ctx))
    }

    private fun visitFunctionCall(ctx: TTRParser.FunctionCallContext): PropertyValue.FunctionCall =
        PropertyValue.FunctionCall(
            name = ctx.id().text,
            args = ctx.value().map { visitValue(it) },
            source = location(ctx),
        )

    // ----- Type / string helpers -----

    private fun dataType(ctx: TTRParser.DataTypeContext?): DataType? {
        if (ctx == null) return null
        ctx.typeValue()?.let { return DataType(name = it.text) }
        var name: String? = null
        var length: Int? = null
        var precision: Int? = null
        ctx.dataTypeProperty().forEach { p ->
            when {
                p.DATA_TYPE() != null -> name = p.typeValue()?.text
                p.LENGTH() != null -> length = p.NUMBER_LITERAL()?.text?.toIntOrNull()
                p.PRECISION() != null -> precision = p.NUMBER_LITERAL()?.text?.toIntOrNull()
            }
        }
        return DataType(name = name ?: "object", length = length, precision = precision)
    }

    /**
     * Grammar 0.13 (NLS-P10 / ⚑GXP-D7) — `description:` has two legal shapes and the
     * walker keeps them apart: the plain form feeds `description`, the map form feeds
     * `descriptionLocalized`, and a property written in one form reads as null/empty
     * through the other. Choosing a locale is a READER's job (Veles' D7 fallback
     * chain: requested → plain → `en` → first by language code → ""), never the
     * walker's — the parser stays mechanical.
     */
    private fun descriptionOf(d: TTRParser.DescriptionPropertyContext?): String? = stringForm(d?.stringLiteralForm())

    private fun descriptionLocalizedOf(d: TTRParser.DescriptionPropertyContext?): LocalizedStringValue =
        visitLocalizedString(d?.localizedString())

    private fun stringForm(ctx: TTRParser.StringLiteralFormContext?): String? {
        if (ctx == null) return null
        ctx.STRING_LITERAL()?.let { return stringLiteral(it) }
        ctx.TRIPLE_STRING_LITERAL()?.let { return tripleStringLiteral(it) }
        // A plain `"""tag␊…"""` whose first line is a bare word lexes as
        // TAGGED_BLOCK_LITERAL (e.g. `"""Ne␊1 = Ano"""`); outside sourceText /
        // definitionSql it is a plain triple-string — the tag word is just text.
        ctx.TAGGED_BLOCK_LITERAL()?.let { return tripleStringLiteral(it) }
        return null
    }

    private fun stringLiteral(node: TerminalNode): String {
        val raw = node.text
        return raw.substring(1, raw.length - 1).unescape()
    }

    private fun tripleStringLiteral(node: TerminalNode): String {
        val raw = node.text
        val inner = raw.substring(3, raw.length - 3)
        return Dedent.applyTextwrapDedent(inner)
    }

    /**
     * The expression-block unescape, as on TS and Python: `[\s\S]`, not `.` — the lexer admits a backslash before
     * any character, a line break too, and the regex engines of the three targets disagree on what `.` matches.
     */
    private val simpleEscape = Regex("""\\([\s\S])""")

    /** The opening fence of a tagged block: `<tag>[ \t]*\r?\n` (the lexer guarantees it). */
    private val taggedOpener = Regex("^([A-Za-z][A-Za-z0-9-]*)([ \t]*\r?\n)")

    /** The single trailing close-fence newline removed in §4 step 6. */
    private val trailingNewline = Regex("\r?\n$")

    /**
     * `sourceText` / `definitionSql` values (embedded-sql DESIGN §2.2, §4). Mirrors
     * the TS `walkEmbeddedBlock`:
     *   - STRING / TRIPLE_STRING → unchanged plain-string behaviour;
     *   - TAGGED_BLOCK → tag-peel → dedent → strip exactly one trailing newline,
     *     resolve the tag via [TAG_REGISTRY] into a [TaggedBlockValue]. An unknown
     *     tag warns and falls back to the extracted text (DESIGN §5).
     */
    private fun walkEmbeddedBlock(ctx: TTRParser.EmbeddedBlockContext): PropertyValue {
        ctx.STRING_LITERAL()?.let {
            return PropertyValue.StringValue(stringLiteral(it), location(ctx))
        }
        ctx.TRIPLE_STRING_LITERAL()?.let {
            return PropertyValue.TripleStringValue(tripleStringLiteral(it), location(ctx))
        }

        val node = ctx.TAGGED_BLOCK_LITERAL()!!
        val loc = location(ctx)
        val inner = node.text.substring(3, node.text.length - 3)
        val opener = taggedOpener.find(inner)!!
        val tag = opener.groupValues[1]
        val body = inner.substring(opener.value.length)
        val dedented = Dedent.applyTextwrapDedentWithIndent(body)
        val value = trailingNewline.replace(dedented.value, "")

        // The tag token sits immediately after the opening `"""`.
        val tagSource =
            SourceLocation(
                file = fileLabel,
                line = loc.line,
                column = loc.column + 3,
                endLine = loc.line,
                endColumn = loc.column + 3 + tag.length,
                offsetStart = loc.offsetStart + 3,
                offsetEnd = loc.offsetStart + 3 + tag.length,
            )

        val entry = resolveTag(tag)
        if (entry == null) {
            warn(ctx, "${DiagnosticCode.UnknownLanguageTag}: Unknown embedded-language tag '$tag'")
            // Stored as raw text (DESIGN §5) — keep the extracted value, drop analysis.
            return PropertyValue.TripleStringValue(value, loc)
        }

        // Body region in file coordinates; the per-line column shift is
        // `indentWidth` (DESIGN §8 uniform source map). Refined further in Phase 2.
        val valueSource =
            SourceLocation(
                file = fileLabel,
                line = loc.line + 1,
                column = dedented.indentWidth,
                endLine = loc.endLine,
                endColumn = maxOf(dedented.indentWidth, loc.endColumn - 3),
                offsetStart = loc.offsetStart + 3 + opener.value.length,
                offsetEnd = loc.offsetEnd - 3,
            )

        return TaggedBlockValue(
            tag = tag,
            language = entry.language,
            dialect = entry.dialect,
            value = value,
            tagSource = tagSource,
            valueSource = valueSource,
            indentWidth = dedented.indentWidth,
            source = loc,
        )
    }

    /** Flattened text of an embedded block value (see [QueryDef.sourceText]). */
    private fun embeddedValueText(v: PropertyValue): String =
        when (v) {
            is PropertyValue.StringValue -> v.raw
            is PropertyValue.TripleStringValue -> v.raw
            is TaggedBlockValue -> v.value
            else -> error("embeddedValueText: unexpected ${v::class.simpleName}")
        }

    private fun stringList(ctx: TTRParser.ListOfStringsContext?): List<String> {
        if (ctx == null) return emptyList()
        return ctx.stringLiteralForm().mapNotNull { stringForm(it) }
    }

    /**
     * `primaryKey` value — a quoted-string list, a bare-id list, or a single bare
     * id (`primaryKey: i_item_sk`). All three collapse to the column-name list,
     * mirroring the TS walker's `walkPrimaryKeyValue`.
     */
    private fun primaryKeyList(ctx: TTRParser.PrimaryKeyValueContext?): List<String> {
        if (ctx == null) return emptyList()
        ctx.listOfStrings()?.let { return stringList(it) }
        ctx.listOfIds()?.let { lst -> return lst.id().map { it.text } }
        ctx.id()?.let { return listOf(it.text) }
        return emptyList()
    }

    /**
     * Source span for a top-level definition. The grammar is
     * `definition : DEF objectDefinition`, so the [od] context starts at the
     * kind keyword (`model`, `entity`, …) and excludes `def`. The canonical TS
     * walker (`walkDefinition` uses `DefinitionContext`) spans the whole
     * `def … ` form, so we read the parent here to match.
     */
    private fun defSource(od: TTRParser.ObjectDefinitionContext): SourceLocation =
        location(od.parent as ParserRuleContext)

    /**
     * Builds a [Reference] from an id (or any single-token) context, carrying the
     * token's own [SourceLocation] — so a collected reference points at the
     * reference itself, not its enclosing def. Mirrors the TS `Reference` shape.
     */
    private fun makeRef(ctx: ParserRuleContext): Reference {
        val path = ctx.text
        return Reference(path, path.split("."), location(ctx))
    }

    /** Builds an [PropertyValue.IdValue], splitting the dotted path into `parts`. */
    private fun idValue(
        path: String,
        ctx: ParserRuleContext,
    ): PropertyValue.IdValue =
        PropertyValue.IdValue(Reference(path, path.split("."), location(ctx)), path.split("."), location(ctx))

    /**
     * D4 source span. `column` is 0-indexed (ANTLR `charPositionInLine`).
     * The multi-token-span invariant: `endColumn = stop.charPositionInLine +
     * stopText.length` — NOT `startColumn + spanLength`.
     */
    private fun location(ctx: ParserRuleContext): SourceLocation {
        val start = ctx.start
        val stop = ctx.stop ?: ctx.start
        val stopText = stop.text ?: ""
        return SourceLocation(
            file = fileLabel,
            line = start.line,
            column = start.charPositionInLine,
            endLine = stop.line,
            endColumn = stop.charPositionInLine + stopText.length,
            offsetStart = start.startIndex,
            offsetEnd = stop.stopIndex + 1,
        )
    }

    private fun String.unescape(): String =
        this
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
}

/** Walker output: schema directive (if any) plus all definitions in source order. */
data class WalkResult(
    val modelDirective: ModelDirective?,
    val definitions: List<Definition>,
    /** Non-blocking semantic warnings emitted during traversal (search feature et al.). */
    val warnings: List<ParseWarning> = emptyList(),
    /** Blocking semantic errors emitted during traversal (duplicate search properties et al.). */
    val errors: List<ParseError> = emptyList(),
    /** The `package <qualifiedName>` declared at the top of the file, if any. */
    val packageName: String? = null,
    /** All `import <qualifiedName> [.*]` statements in file order. */
    val imports: List<ImportStatement> = emptyList(),
    /** PL-P4.S3 — document-level `security { … }` blocks in file order. */
    val securityBlocks: List<SecurityBlock> = emptyList(),
)
