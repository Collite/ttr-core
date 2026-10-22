// SPDX-License-Identifier: Apache-2.0
import {
  CharStream,
  CommonTokenStream,
  ANTLRErrorListener,
  RecognitionException,
  Recognizer,
  Token,
  ATNSimulator,
  ParserRuleContext,
  ParseTree,
  TerminalNode,
} from 'antlr4ng';
import { TTRLexer } from './generated/TTRLexer.js';
import {
  TTRParser,
  DocumentContext,
  DefinitionContext,
  ModelDirectiveContext, // generated rule context
  ValueContext,
  LiteralContext,
  StringLiteralFormContext,
  EmbeddedBlockContext,
  IdContext,
  ListContext,
  Object_Context,
  FunctionCallContext,
  DataTypeContext,
  DescriptionPropertyContext,
  LocalizedStringContext,
  LocalizedStringListContext,
  SearchBlockContext,
  MatchMethodValueContext,
  SemanticsBlockPropertyContext,
  ValueLabelsBodyContext,
  ValueLabelValueContext,
  ProjectDefContext,
  TableDefContext,
  ViewDefContext,
  ColumnDefContext,
  IndexDefContext,
  ConstraintDefContext,
  FkDefContext,
  ProcedureDefContext,
  EntityDefContext,
  AttributeDefContext,
  RelationDefContext,
  Er2dbEntityDefContext,
  Er2dbAttributeDefContext,
  Er2dbRelationDefContext,
  BindingPropertyContext,
  BindingColumnMapContext,
  TargetPropertyContext,
  QueryDefContext,
  RoleDefContext,
  Er2cncRoleDefContext,
  DrillMapDefContext,
  DrillArgsMapContext,
  ColumnDefListContext,
  IndexDefListContext,
  ConstraintDefListContext,
  AttributeDefListContext,
  ParameterDefListContext,
  ListOfStringsContext,
  ListOfIdsContext,
  PrimaryKeyValueContext,
  PackageDeclContext,
  ImportDeclContext,
  GraphBlockContext,
  AreaDefContext,
  SchemaDefContext,
  WorldDefContext,
  EngineDefContext,
  ExecutorDefContext,
  StorageDefContext,
  WorldSchemaDefContext,
  ObjectDefinitionContext,
  AutoBodyContext,
  AutoPropertyContext,
  AutoEntryContext,
  AutoValueContext,
  AutoObjectContext,
  TransitionPropertyContext,
  SetPropertyContext,
  ProcessBodyContext,
  AttributePropertyContext,
  MdDomainDefContext,
  DimensionDefContext,
  MdMapDefContext,
  HierarchyDefContext,
  MeasureDefContext,
  CubeletDefContext,
  RestrictBlockContext,
  MembersBlockContext,
  RangeLiteralContext,
  CalcRefContext,
  AggregationValueContext,
  LevelListContext,
  MeasuresValueContext,
  Md2dbCubeletDefContext,
  Md2dbDomainDefContext,
  Md2dbMapDefContext,
  Md2erCubeletDefContext,
  Md2erDimensionDefContext,
  ShapeValueContext,
  JournalingValueContext,
  AllocationValueContext,
  LexiconEntryDefContext,
  LexiconBlockPropertyContext,
  ChangeSemanticsPropertyContext,
  WritebackReservationPropertyContext,
  SecurityBlockContext,
  SecurityStatementContext,
  CnBodyContext,
  CnBindingBodyContext,
  ConceptSugarPropertyContext,
  LabelPropertyContext,
  TagsPropertyContext,
  CnRefPropertyContext,
  GroundsPropertyContext,
  ValueSpacePropertyContext,
  InvolvesPropertyContext,
  PeriodPropertyContext,
  ExtentPropertyContext,
  BasisPropertyContext,
  DefinitionPropertyContext,
  ConceptMembersPropertyContext,
  CnEntryContext,
  CnValueContext,
  CnObjectContext,
} from './generated/TTRParser.js';
import type {
  SourceLocation,
  Document,
  Definition,
  ParseError,
  ParseResult,
  ModelDirective,
  PropertyValue,
  StringValue,
  TripleStringValue,
  TaggedBlockValue,
  NumberValue,
  BoolValue,
  NullValue,
  IdValue,
  ListValue,
  ObjectValue,
  ObjectEntry,
  FunctionCallValue,
  Reference,
  LocalizedString,
  LocalizedStringList,
  DataType,
  IndexType,
  ConstraintType,
  QueryLanguage,
  ParameterDirection,
  SearchBlock,
  MatchMethod,
  SemanticsBlock,
  SecurityBlock,
  SecurityStatement,
  SemanticsValue,
  ValueLabels,
  ParameterDef,
  ProjectDef,
  TableDef,
  ViewDef,
  ColumnDef,
  IndexDef,
  ConstraintDef,
  FkDef,
  ProcedureDef,
  EntityDef,
  AttributeDef,
  RelationDef,
  Er2dbEntityDef,
  Er2dbAttributeDef,
  Er2dbRelationDef,
  QueryDef,
  RoleDef,
  Er2cncRoleDef,
  DrillMapDef,
  WorldDef,
  EngineDef,
  ExecutorDef,
  StorageDef,
  WorldSchemaDef,
  WorldSchemaField,
  AttributeTransition,
  AutomationKind,
  AutoValue,
  AutoEntry,
  AutoTransition,
  AutoSet,
  AutoBody,
  AutomationDef,
  IdentityDef,
  GrantDef,
  DeploymentDef,
  ProcessDef,
  StepDef,
  DrillArgEntry,
  BindingProperty,
  BindingColumnEntry,
  BindingColumnValue,
  PackageDecl,
  ImportDecl,
  GraphBlock,
  GraphLayout,
  AreaDef,
  SchemaDef,
  MdDomainDef,
  RestrictClause,
  RangeLiteral,
  DomainMember,
  DimensionDef,
  MdMapDef,
  CalcRef,
  CalcArg,
  HierarchyDef,
  HierarchyLevel,
  MeasureDef,
  AggregationSpec,
  CubeletDef,
  CrossRef,
  Md2DbCubeletDef,
  Md2DbDomainDef,
  Md2DbMapDef,
  Md2ErCubeletDef,
  Md2ErDimensionDef,
  ShapeSpec,
  AttrColumnBinding,
  MeasureColumnBinding,
  JournalingSpec,
  AllocationSpec,
  LexiconEntryDef,
  LexiconBlock,
  ChangeSemanticsDecl,
  WritebackReservation,
  ConceptStereotype,
  ConceptBindingKind,
  ConceptDef,
  ConceptBindingDef,
  ConceptSugar,
  ConceptBody,
  ConceptProperty,
  ConceptRefKey,
  EmbeddedBlock,
} from './ast.js';
import { resolveTag, resolveExpressionTag } from './tag-registry.js';
import { DiagnosticCode } from './diagnostics.js';
import { RecoveryReportingStrategy } from './recovery.js';
import { attachTrivia } from './cst/attach.js';

class DiagnosticErrorListener implements ANTLRErrorListener {
  private errors: ParseError[];
  private fileLabel: string;

  constructor(errors: ParseError[], fileLabel: string) {
    this.errors = errors;
    this.fileLabel = fileLabel;
  }

  syntaxError(
    _recognizer: Recognizer<ATNSimulator>,
    _offendingSymbol: Token | null,
    line: number,
    charPositionInLine: number,
    msg: string,
    _e: RecognitionException | null
  ): void {
    const symbol = _offendingSymbol;
    const ctx = _e?.ctx ?? (_recognizer as { context?: ParserRuleContext | null }).context ?? null;
    const hint = cnSyntaxHint(ctx instanceof ParserRuleContext ? ctx : null);
    this.errors.push({
      code: DiagnosticCode.ParseError,
      message: hint ? `${msg} — ${hint}` : msg,
      severity: 'error',
      source: {
        file: this.fileLabel,
        line,
        column: charPositionInLine,
        endLine: line,
        endColumn: charPositionInLine + (symbol?.stop ? symbol.stop - symbol.start + 1 : 1),
        offsetStart: symbol?.start ?? 0,
        offsetEnd: symbol ? symbol.stop + 1 : 0,
      },
    });
  }

  reportAmbiguity(): void {}
  reportAttemptingFullContext(): void {}
  reportContextSensitivity(): void {}
}

/**
 * 0.17 — the form a cn property wants, named when its value is malformed (CN-P1 S1.2 T6): a bare
 * `definition:` / `members:` value (⚑CN-P5 — an expression block) and an unbracketed `involves:`.
 * The error is raised in the property's own rule context or in its `embeddedBlock` / `participant`.
 */
function cnSyntaxHint(ctx: ParserRuleContext | null): string | undefined {
  for (let c: ParserRuleContext | null = ctx, depth = 0; c && depth < 3; c = c.parent, depth++) {
    if (c instanceof DefinitionPropertyContext || c instanceof ConceptMembersPropertyContext) {
      const key = c instanceof DefinitionPropertyContext ? 'definition' : 'members';
      return `'${key}:' takes an expression block: """…""" (optionally tagged """pl)`;
    }
    if (c instanceof InvolvesPropertyContext) {
      return `'involves:' takes a bracketed list: involves: [ Item, Warehouse as from ]`;
    }
  }
  return undefined;
}

export function parseString(content: string, fileLabel = '<string>'): ParseResult {
  const inputStream = CharStream.fromString(content);
  const lexer = new TTRLexer(inputStream);
  const tokenStream = new CommonTokenStream(lexer);
  const parser = new TTRParser(tokenStream);

  const errors: ParseError[] = [];

  lexer.removeErrorListeners();
  parser.removeErrorListeners();

  const lexerErrorListener = new DiagnosticErrorListener(errors, fileLabel);
  const parserErrorListener = new DiagnosticErrorListener(errors, fileLabel);

  lexer.addErrorListener(lexerErrorListener);
  parser.addErrorListener(parserErrorListener);

  const recoveryStrategy = new RecoveryReportingStrategy();
  parser.errorHandler = recoveryStrategy;

  try {
    const tree = parser.document();
    const { doc, errors: docErrors } = walkDocument(tree, fileLabel, errors);
    attachTrivia(doc, tokenStream);
    for (const event of recoveryStrategy.recoveryEvents) {
      docErrors.push({
        code: DiagnosticCode.ParseRecoveryInfo,
        message: event.description,
        severity: 'info',
        source: {
          file: fileLabel,
          line: event.line,
          column: event.column,
          endLine: event.line,
          endColumn: event.column + (event.offsetEnd - event.offsetStart),
          offsetStart: event.offsetStart,
          offsetEnd: event.offsetEnd,
        },
      });
    }
    return { ast: doc, errors: docErrors, sourceFile: fileLabel };
  } catch (e) {
    return {
      errors: [
        ...errors,
        {
          message: e instanceof Error ? e.message : 'Unknown parse error',
          severity: 'error',
          source: { file: fileLabel, line: 1, column: 1, endLine: 1, endColumn: 1, offsetStart: 0, offsetEnd: 0 },
        },
      ],
      sourceFile: fileLabel,
    };
  }
}

export async function parseFile(filePath: string): Promise<ParseResult> {
  const fs = await import('fs/promises');
  const content = await fs.readFile(filePath, 'utf-8');
  return parseString(content, filePath);
}

function walkDocument(ctx: DocumentContext, file: string, syntaxErrors: ParseError[]): { doc: Document; errors: ParseError[] } {
  const localErrors: ParseError[] = [...syntaxErrors];

  const packageCtx = ctx.packageDecl();
  const importCtxs = ctx.importDecl();
  const schemaCtx = ctx.modelDirective();
  const graphCtx = ctx.graphBlock();
  const defContexts = ctx.definition();

  // 0.17 — read before the definitions: one `def role` production serves cnc, automation and cn, and
  // the walker reads its body by the file's model (⚑CN-P6).
  const modelDirective = schemaCtx ? walkModelDirective(schemaCtx, file) : undefined;

  const definitions: Definition[] = defContexts.map((defCtx: DefinitionContext) =>
    walkDefinition(defCtx, file, localErrors, modelDirective?.modelCode)
  );

  if (graphCtx && definitions.length > 0) {
    localErrors.push({
      code: DiagnosticCode.WrongFileKind,
      message: "A file containing 'graph { ... }' must not also contain top-level 'def' definitions.",
      severity: 'error',
      source: makeSourceLocation(graphCtx, file),
    });
  }

  if (file.endsWith('.ttrg') && !graphCtx) {
    localErrors.push({
      code: DiagnosticCode.WrongFileKind,
      message: "A '.ttrg' file must contain a 'graph { ... }' block.",
      severity: 'error',
      source: makeSourceLocation(ctx, file),
    });
  }

  // v3.0: the `.ttrd` domain file-kind is gone — subject areas are now plain
  // `def area` definitions (see walkAreaDef) that coexist with other defs.

  // LQ (logical queries, L-2 / ruling LQ-5): a `def query` in a `model er` file is a
  // logical query over entities and takes no parameters — a parametrized query stays a
  // db query. A warning, as in the Kotlin walker: an error would make whole-file tools
  // (format, migrate) refuse the file; the load-time refusal is ttr-metadata's.
  if (modelDirective?.modelCode === 'er') {
    for (const def of definitions) {
      if (def.kind === 'query' && def.parameters && def.parameters.length > 0) {
        localErrors.push({
          code: DiagnosticCode.LogicalQueryParameters,
          message: `logical query '${def.name}' (model er) cannot declare parameters; a parametrized query stays a db query`,
          severity: 'warning',
          source: def.source,
        });
      }
    }
  }

  const doc: Document = {
    packageDecl: packageCtx ? walkPackageDecl(packageCtx, file) : undefined,
    imports: importCtxs.map((ic) => walkImportDecl(ic, file)),
    modelDirective,
    graph: graphCtx ? walkGraphBlock(graphCtx, file) : undefined,
    definitions,
    securityBlocks: ctx.securityBlock().map((sb) => walkSecurityBlock(sb, file)),
    source: makeSourceLocation(ctx, file),
  };

  return { doc, errors: localErrors };
}

// PL-P4.S3 (grammar 0.11, H-1) — walk a document-level `security { … }` block.
// The grammar has already rejected unknown verbs and row predicates; here we only
// fan the structured statements out to the AST. Object refs are kept verbatim
// (dotted-id text); resolution is @tatrman/semantics' job (advisory).
function walkSecurityBlock(ctx: SecurityBlockContext, file: string): SecurityBlock {
  return {
    kind: 'securityBlock',
    statements: ctx.securityStatement().map((s) => walkSecurityStatement(s, file)),
    source: makeSourceLocation(ctx, file),
  };
}

function walkSecurityStatement(ctx: SecurityStatementContext, file: string): SecurityStatement {
  const source = makeSourceLocation(ctx, file);
  if (ctx.OWN()) {
    return { verb: 'own', objectRef: ctx.id(0)!.getText(), owner: ctx.id(1)!.getText(), source };
  }
  if (ctx.CLASSIFY()) {
    return { verb: 'classify', objectRef: ctx.id(0)!.getText(), classification: ctx.id(1)!.getText(), source };
  }
  if (ctx.GRANT()) {
    return {
      verb: 'grant',
      privilege: ctx.id(0)!.getText(),
      objectRef: ctx.id(1)!.getText(),
      grantee: ctx.id(2)!.getText(),
      source,
    };
  }
  return { verb: 'mask', objectRef: ctx.id(0)!.getText(), source };
}

function walkModelDirective(ctx: ModelDirectiveContext, file: string): ModelDirective {
  const modelCodeCtx = ctx.modelCode();

  let modelCode = '';
  if (modelCodeCtx.DB()) modelCode = 'db';
  else if (modelCodeCtx.ER()) modelCode = 'er';
  else if (modelCodeCtx.BINDING()) modelCode = 'binding';
  else if (modelCodeCtx.QUERY()) modelCode = 'query';
  else if (modelCodeCtx.CNC()) modelCode = 'cnc';
  else if (modelCodeCtx.MD()) modelCode = 'md';
  else if (modelCodeCtx.WORLD()) modelCode = 'world';
  else if (modelCodeCtx.LEXICON()) modelCode = 'lexicon';
  else if (modelCodeCtx.AUTOMATION()) modelCode = 'automation'; // 0.16
  else if (modelCodeCtx.CN()) modelCode = 'cn'; // 0.17

  // v4.4: `MODEL modelCode (SCHEMA id)? (LOCALE id)?` — two optional id slots.
  // Assign positionally by which of SCHEMA / LOCALE is present (ids stay in order).
  const ids = ctx.id();
  let cursor = 0;
  const schema = ctx.SCHEMA() ? ids[cursor++]?.getText() : undefined;
  const locale = ctx.LOCALE() ? ids[cursor++]?.getText() : undefined;

  return {
    modelCode,
    schema,
    locale,
    source: makeSourceLocation(ctx, file),
  };
}

function walkPackageDecl(ctx: PackageDeclContext, file: string): PackageDecl {
  const qnameCtx = ctx.qualifiedName();
  const idCtx = qnameCtx.id();
  const parts = idCtx.idPart().map((pt) => pt.getText());
  return {
    kind: 'packageDecl',
    name: parts.join('.'),
    parts,
    source: makeSourceLocation(ctx, file),
  };
}

function walkImportDecl(ctx: ImportDeclContext, file: string): ImportDecl {
  const qnameCtx = ctx.qualifiedName();
  const idCtx = qnameCtx.id();
  const parts = idCtx.idPart().map((pt) => pt.getText());
  return {
    kind: 'importDecl',
    target: parts.join('.'),
    targetParts: parts,
    wildcard: ctx.STAR() !== null,
    source: makeSourceLocation(ctx, file),
  };
}

function walkGraphBlock(ctx: GraphBlockContext, file: string): GraphBlock {
  const nameCtx = ctx.id();
  const name = nameCtx ? nameCtx.getText() : '';

  let schema: 'db' | 'er' | 'binding' | 'query' | 'cnc' | undefined;
  let description: string | undefined;
  let tags: string[] | undefined;
  let objects: string[] = [];
  let layout: GraphLayout | undefined;

  for (const gp of ctx.graphProperty()) {
    if (gp.graphModelProperty()) {
      const sc = gp.graphModelProperty()!.modelCode();
      if (sc.DB()) schema = 'db';
      else if (sc.ER()) schema = 'er';
      else if (sc.BINDING()) schema = 'binding';
      else if (sc.QUERY()) schema = 'query';
      else if (sc.CNC()) schema = 'cnc';
    }
    if (gp.descriptionProperty()) {
      // GraphBlock keeps a plain-string description (no localized field on `.ttrg`
      // graph headers), so the 0.13 map form is simply not read here.
      description = descriptionOf(gp.descriptionProperty()!, file)?.value;
    }
    if (gp.tagsProperty()) {
      tags = walkListOfStrings(gp.tagsProperty()!.listOfStrings()!, file);
    }
    if (gp.graphObjectsProperty()) {
      objects = gp.graphObjectsProperty()!.id().map((idCtx) => {
        const parts = idCtx.idPart().map((pt) => pt.getText());
        return parts.join('.');
      });
    }
    if (gp.graphLayoutProperty()) {
      layout = walkGraphLayout(gp.graphLayoutProperty()!.object_(), file);
    }
  }

  return { kind: 'graphBlock', name, schema, description, tags, objects, layout, source: makeSourceLocation(ctx, file) };
}

function walkAreaDef(ctx: AreaDefContext, name: string, source: SourceLocation, file: string): AreaDef {
  let description: AreaDef['description'];
  let descriptionLocalized: AreaDef['descriptionLocalized'];
  let tags: string[] | undefined;
  let packages: string[] = [];
  let entities: string[] = [];
  let packageSources: SourceLocation[] = [];
  let entitySources: SourceLocation[] = [];

  for (const ap of ctx.areaProperty()) {
    if (ap.descriptionProperty()) {
      description = descriptionOf(ap.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(ap.descriptionProperty()!, file);
    }
    if (ap.tagsProperty()) {
      tags = walkListOfStrings(ap.tagsProperty()!.listOfStrings()!, file);
    }
    if (ap.areaPackagesProperty()) {
      const ids = ap.areaPackagesProperty()!.id();
      packages = ids.map((idCtx) => idCtx.idPart().map((pt) => pt.getText()).join('.'));
      packageSources = ids.map((idCtx) => makeSourceLocation(idCtx, file));
    }
    if (ap.areaEntitiesProperty()) {
      const ids = ap.areaEntitiesProperty()!.id();
      entities = ids.map((idCtx) => idCtx.idPart().map((pt) => pt.getText()).join('.'));
      entitySources = ids.map((idCtx) => makeSourceLocation(idCtx, file));
    }
  }

  return {
    kind: 'area',
    name,
    description,
    descriptionLocalized,
    tags,
    packages,
    entities,
    packageSources,
    entitySources,
    source,
  };
}

// ============================================================================
// v4.1 — world model walkers (ttr-metadata M0). Manifest entries are transported
// opaque (T6 β data — MD5: never interpreted here). `type`/`version`/`extends`
// are captured as raw text; free-form keys land in `manifest`.
// ============================================================================

function walkWorldDef(ctx: WorldDefContext, name: string, source: SourceLocation, file: string, errors: ParseError[]): WorldDef {
  let description: WorldDef['description'];
  let descriptionLocalized: WorldDef['descriptionLocalized'];
  let tags: string[] | undefined;
  let extendsRef: string | undefined;
  const engines: EngineDef[] = [];
  const executors: ExecutorDef[] = [];
  const storages: StorageDef[] = [];
  const identities: IdentityDef[] = [];
  const grants: GrantDef[] = [];
  const deployments: DeploymentDef[] = [];
  const processes: ProcessDef[] = [];

  for (const m of ctx.worldMember()) {
    const wp = m.worldProperty();
    if (wp) {
      if (wp.descriptionProperty()) {
        description = descriptionOf(wp.descriptionProperty()!, file);
        descriptionLocalized = descriptionLocalizedOf(wp.descriptionProperty()!, file);
      }
      if (wp.tagsProperty()) tags = walkListOfStrings(wp.tagsProperty()!.listOfStrings()!, file);
      if (wp.extendsProperty()) extendsRef = wp.extendsProperty()!.id()!.getText();
      continue;
    }
    const memberSource = makeSourceLocation(m, file);
    // 0.16 — `def grant { … }` is unnamed (keyed by its who/program/as triple).
    if (m.GRANT()) {
      grants.push({ kind: 'grant', source: memberSource, ...walkAutoBody(m.autoBody()!.autoProperty(), file, errors) });
      continue;
    }
    const memberName = m.id()!.getText();
    if (m.ENGINE())
      engines.push(walkEnginePartDef(m.engineDef()!, 'engine', memberName, memberSource, file) as EngineDef);
    else if (m.EXECUTOR())
      executors.push(walkEnginePartDef(m.executorDef()!, 'executor', memberName, memberSource, file) as ExecutorDef);
    else if (m.STORAGE()) storages.push(walkStorageDef(m.storageDef()!, memberName, memberSource, file));
    else if (m.IDENTITY())
      identities.push({ kind: 'identity', name: memberName, source: memberSource, ...walkAutoBody(m.autoBody()!.autoProperty(), file, errors) });
    else if (m.DEPLOYMENT())
      deployments.push({ kind: 'deployment', name: memberName, source: memberSource, ...walkAutoBody(m.autoBody()!.autoProperty(), file, errors) });
    else if (m.PROCESS()) processes.push(walkProcessDef(m.processBody()!, memberName, memberSource, file, errors));
  }

  return {
    kind: 'world', name, description, descriptionLocalized, tags, extends: extendsRef, engines, executors, storages,
    identities, grants, deployments, processes, source,
  };
}

function walkEnginePartDef(
  ctx: EngineDefContext | ExecutorDefContext,
  kind: 'engine' | 'executor',
  name: string,
  source: SourceLocation,
  file: string,
): EngineDef | ExecutorDef {
  let description: EngineDef['description'];
  let descriptionLocalized: EngineDef['descriptionLocalized'];
  let tags: string[] | undefined;
  let type: string | undefined;
  let version: string | undefined;
  let extendsRef: string | undefined;
  const manifest: Record<string, PropertyValue> = {};

  for (const p of ctx.enginePartProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    } else if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    } else if (p.typeProperty()) {
      type = p.typeProperty()!.dataType()!.getText();
    } else if (p.versionProperty()) {
      version = unescapeStringLiteral(p.versionProperty()!.STRING_LITERAL()!.getText());
    } else if (p.extendsProperty()) {
      extendsRef = p.extendsProperty()!.id()!.getText();
    } else if (p.propertyEntry()) {
      const entry = p.propertyEntry()!;
      manifest[entry.key()!.getText()] = walkValue(entry.value()!, file);
    }
  }

  return { kind, name, description, descriptionLocalized, tags, type, version, extends: extendsRef, manifest, source };
}

function walkStorageDef(ctx: StorageDefContext, name: string, source: SourceLocation, file: string): StorageDef {
  let description: StorageDef['description'];
  let descriptionLocalized: StorageDef['descriptionLocalized'];
  let tags: string[] | undefined;
  let type: string | undefined;
  let extendsRef: string | undefined;
  let via: string | undefined;
  let hosts: string[] = [];
  let staging: boolean | undefined;
  const schemas: WorldSchemaDef[] = [];
  const manifest: Record<string, PropertyValue> = {};

  for (const p of ctx.storageProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    } else if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    } else if (p.typeProperty()) {
      type = p.typeProperty()!.dataType()!.getText();
    } else if (p.extendsProperty()) {
      extendsRef = p.extendsProperty()!.id()!.getText();
    } else if (p.viaProperty()) {
      via = p.viaProperty()!.id()!.getText();
    } else if (p.hostsProperty()) {
      hosts = walkListOfIds(p.hostsProperty()!.listOfIds()!, file);
    } else if (p.stagingProperty()) {
      staging = p.stagingProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
    } else if (p.SCHEMA()) {
      schemas.push(walkWorldSchemaDef(p.worldSchemaDef()!, p.id()!.getText(), makeSourceLocation(p, file), file));
    } else if (p.propertyEntry()) {
      const entry = p.propertyEntry()!;
      manifest[entry.key()!.getText()] = walkValue(entry.value()!, file);
    }
  }

  return { kind: 'storage', name, description, descriptionLocalized, tags, type, extends: extendsRef, via, hosts, staging, schemas, manifest, source };
}

function walkWorldSchemaDef(
  ctx: WorldSchemaDefContext,
  name: string,
  source: SourceLocation,
  file: string,
): WorldSchemaDef {
  const fields: WorldSchemaField[] = [];
  for (const f of ctx.worldSchemaField()) {
    fields.push({ name: f.id()!.getText(), type: f.dataType()!.getText(), source: makeSourceLocation(f, file) });
  }
  return { kind: 'worldSchema', name, fields, source };
}

// ============================================================================
// 0.16 — the automation model (`model automation`; AU-core-P0 S0.2). One permissive
// body for six def kinds and the automation world members; values are carried
// structurally — contracts §2–§3 meaning is validated in @tatrman/semantics.
// ============================================================================

function automationKindOf(objDef: ObjectDefinitionContext): AutomationKind | undefined {
  if (objDef.PARAMETER()) return 'parameter';
  if (objDef.ACTION()) return 'action';
  if (objDef.SERVICE()) return 'service';
  if (objDef.APPROVAL()) return 'approval';
  if (objDef.PROGRAM()) return 'program';
  if (objDef.RECIPE()) return 'recipe';
  return undefined;
}

function walkAutomationDef(
  ctx: AutoBodyContext,
  kind: AutomationKind,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[],
): AutomationDef {
  return { kind, name, source, ...walkAutoBody(ctx.autoProperty(), file, errors) };
}

function walkAutoBody(props: AutoPropertyContext[], file: string, errors: ParseError[]): AutoBody {
  const body: AutoBody = { entries: [] };
  for (const p of props) {
    if (p.descriptionProperty()) {
      body.description = descriptionOf(p.descriptionProperty()!, file);
      body.descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    } else if (p.tagsProperty()) {
      body.tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    } else if (p.labelProperty()) {
      body.label = walkLocalizedString(p.labelProperty()!.localizedString()!, file);
    } else if (p.typeProperty()) {
      body.type = walkDataType(p.typeProperty()!.dataType()!, file);
    } else if (p.columnsProperty()) {
      body.columns = walkColumnDefList(p.columnsProperty()!.columnDefList()!, file, errors);
    } else if (p.targetProperty()) {
      body.target = walkTargetValue(p.targetProperty()!, file);
    } else if (p.transitionProperty()) {
      const t = walkAutoTransition(p.transitionProperty()!, file);
      if (t) body.transition = t;
    } else if (p.setProperty()) {
      const st = walkAutoSet(p.setProperty()!, file);
      if (st) body.set = st;
    } else if (p.autoEntry()) {
      const e = walkAutoEntry(p.autoEntry()!, file);
      if (e) body.entries.push(e);
    }
  }
  return body;
}

// The automation walkers tolerate the error-recovery holes a syntax error leaves (a missing id, a
// missing value): the half-built property is dropped — its syntax error is already reported — and
// the rest of the document survives (review-114: these used to wipe the whole AST).
function walkAutoTransition(ctx: TransitionPropertyContext, file: string): AutoTransition | undefined {
  const attribute = ctx.id();
  if (!attribute) return undefined;
  const t: AutoTransition = { attribute: attribute.getText(), from: [], source: makeSourceLocation(ctx, file) };
  const target = ctx.transitionTarget();
  const targetId = target?.id();
  if (!target || !targetId) return undefined;
  if (target.LBRACE()) t.toColumn = targetId.getText();
  else t.to = targetId.getText();
  const guard = ctx.transitionGuard();
  if (guard) {
    if (guard.listOfIds()) t.from = walkListOfIds(guard.listOfIds()!, file);
    else if (guard.id()) t.from = [guard.id()!.getText()];
  }
  return t;
}

function walkAutoSet(ctx: SetPropertyContext, file: string): AutoSet | undefined {
  const attribute = ctx.id(0);
  const column = ctx.id(1);
  if (!attribute || !column) return undefined;
  return { attribute: attribute.getText(), column: column.getText(), source: makeSourceLocation(ctx, file) };
}

function walkAutoEntry(ctx: AutoEntryContext, file: string): AutoEntry | undefined {
  const key = ctx.autoKey();
  const value = ctx.autoValue();
  if (!key || !value) return undefined;
  return { key: key.getText(), value: walkAutoValue(value, file), source: makeSourceLocation(ctx, file) };
}

function walkAutoEntries(ctx: AutoObjectContext | null, file: string): AutoEntry[] {
  return (ctx?.autoEntry() ?? []).flatMap((e) => walkAutoEntry(e, file) ?? []);
}

function walkAutoValue(ctx: AutoValueContext, file: string): AutoValue {
  const source = makeSourceLocation(ctx, file);
  const dur = ctx.durationLiteral();
  if (dur?.NUMBER_LITERAL() && dur.id()) {
    const raw = dur.NUMBER_LITERAL()!.getText();
    return { kind: 'duration', amount: Number(raw), unit: dur.id()!.getText(), source };
  }
  const tagged = ctx.taggedValue();
  if (tagged?.id() && tagged.stringLiteralForm()) {
    const v = walkStringLiteralForm(tagged.stringLiteralForm()!, file);
    return { kind: 'tagged', tag: tagged.id()!.getText(), value: v.value, source };
  }
  const call = ctx.callValue();
  if (call?.id()) return { kind: 'call', name: call.id()!.getText(), entries: walkAutoEntries(call.autoObject(), file), source };
  if (ctx.STAR()) return { kind: 'star', source };
  if (ctx.autoObject()) return { kind: 'autoObject', entries: walkAutoEntries(ctx.autoObject()!, file), source };
  const list = ctx.autoList();
  if (list) return { kind: 'autoList', items: list.autoValue().map((v) => walkAutoValue(v, file)), source };
  if (ctx.literal()) {
    const lit = walkLiteral(ctx.literal()!, file);
    // review-114: keep a number's text as written — parameter values feed the D-13 values fingerprint.
    if (lit.kind === 'number') return { ...lit, raw: ctx.literal()!.NUMBER_LITERAL()!.getText() };
    if (lit.kind === 'string' || lit.kind === 'tripleString' || lit.kind === 'bool' || lit.kind === 'null') return lit;
    return { kind: 'null', source };
  }
  if (ctx.id()) return walkId(ctx.id()!, file);
  return { kind: 'null', source };
}

function walkProcessDef(ctx: ProcessBodyContext, name: string, source: SourceLocation, file: string, errors: ParseError[]): ProcessDef {
  const proc: ProcessDef = { kind: 'process', name, source, steps: [], entries: [] };
  for (const p of ctx.processProperty()) {
    if (p.descriptionProperty()) {
      proc.description = descriptionOf(p.descriptionProperty()!, file);
      proc.descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    } else if (p.tagsProperty()) {
      proc.tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    } else if (p.stepsProperty()) {
      for (const st of p.stepsProperty()!.stepInline()) {
        const stepName = st.id();
        const stepBody = st.autoBody();
        if (!stepName || !stepBody) continue; // a step with no name / body: its syntax error is reported
        const step: StepDef = {
          kind: 'step',
          name: stepName.getText(),
          source: makeSourceLocation(st, file),
          ...walkAutoBody(stepBody.autoProperty(), file, errors),
        };
        proc.steps.push(step);
      }
    } else if (p.autoEntry()) {
      const e = walkAutoEntry(p.autoEntry()!, file);
      if (e) proc.entries.push(e);
    }
  }
  return proc;
}

// ============================================================================
// 0.17 — the conceptual model (`model cn`; CN-P1 S1.2, cn contracts §1). The concept defs, a cn
// `def role`, the cn bindings and the `concept { }` sugar read one way: every property, in authored
// order, to one node (its key as written, its span) — typed where the grammar types it, a free-form
// `entry` otherwise. A repeated key is reported (`ttr/duplicate-property`); the first one is read.
// Meaning, legality per stereotype and references are CN-P2's.
// ============================================================================

const STEREOTYPE_OF_TOKEN: ReadonlyArray<[(o: ObjectDefinitionContext) => unknown, ConceptStereotype]> = [
  [(o) => o.KIND(), 'kind'],
  [(o) => o.EVENT(), 'event'],
  [(o) => o.PHASE(), 'phase'],
  [(o) => o.QUALITY(), 'quality'],
  [(o) => o.RELATION_TYPE(), 'relation_type'],
];

const BINDING_KIND_OF_TOKEN: ReadonlyArray<[(o: ObjectDefinitionContext) => unknown, ConceptBindingKind]> = [
  [(o) => o.CN2MD_KIND(), 'cn2md_kind'],
  [(o) => o.CN2MD_ROLE(), 'cn2md_role'],
  [(o) => o.CN2MD_QUALITY(), 'cn2md_quality'],
  [(o) => o.CN2MD_EVENT(), 'cn2md_event'],
  [(o) => o.CN2ER_KIND(), 'cn2er_kind'],
  [(o) => o.CN2ER_ROLE(), 'cn2er_role'],
  [(o) => o.CN2ER_EVENT(), 'cn2er_event'],
  [(o) => o.CN2ER_QUALITY(), 'cn2er_quality'],
  [(o) => o.CN2ER_PHASE(), 'cn2er_phase'],
  [(o) => o.CN2DB_CONCEPT(), 'cn2db_concept'],
];

function conceptStereotypeOf(objDef: ObjectDefinitionContext): ConceptStereotype | undefined {
  return STEREOTYPE_OF_TOKEN.find(([has]) => has(objDef))?.[1];
}

function conceptBindingKindOf(objDef: ObjectDefinitionContext): ConceptBindingKind | undefined {
  return BINDING_KIND_OF_TOKEN.find(([has]) => has(objDef))?.[1];
}

function walkConceptDef(
  ctx: CnBodyContext | null,
  stereotype: ConceptStereotype,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[],
): ConceptDef {
  return { kind: 'concept', stereotype, name, source, ...walkConceptBody(ctx?.cnProperty() ?? [], 'concept', file, errors) };
}

function walkConceptBindingDef(
  ctx: CnBindingBodyContext | null,
  bindingKind: ConceptBindingKind,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[],
): ConceptBindingDef {
  return { kind: 'conceptBinding', bindingKind, name, source, ...walkConceptBody(ctx?.cnBindingProperty() ?? [], 'binding', file, errors) };
}

function walkConceptSugar(ctx: ConceptSugarPropertyContext, file: string, errors: ParseError[]): ConceptSugar {
  return {
    kind: 'conceptSugar',
    source: makeSourceLocation(ctx, file),
    ...walkConceptBody(ctx.cnBody()?.cnProperty() ?? [], 'concept', file, errors),
  };
}

/**
 * One `concept { }` per host (cn contracts §1.4): a second one is `ttr/duplicate-property` and the first
 * is read — the cn body's rule, so CN-P2 and the formatter never meet a sugar the AST threw away.
 */
function conceptSugarOnce(
  first: ConceptSugar | undefined,
  ctx: ConceptSugarPropertyContext,
  file: string,
  errors: ParseError[],
): ConceptSugar {
  if (!first) return walkConceptSugar(ctx, file, errors);
  errors.push({
    code: DiagnosticCode.DuplicateProperty,
    message: "duplicate property 'concept' — the first one is read",
    severity: 'error',
    source: makeSourceLocation(ctx, file),
  });
  return first;
}

/**
 * The cn body behind `cnBody`, `cnBindingBody` and — in a `model cn` file — `roleDef` (⚑CN-P6). Each
 * property context has exactly one child, the property rule, so one reader serves all three. On a
 * binding a free-form `filter:` is the expression block of a valued state's series (A-CN-18).
 */
function walkConceptBody(
  props: ParserRuleContext[],
  host: 'concept' | 'binding' | 'role',
  file: string,
  errors: ParseError[],
): ConceptBody {
  const body: ConceptBody = { properties: [] };
  const seen = new Set<string>();
  for (const p of props) {
    const prop = walkConceptProperty(p.getChild(0), host, file, errors);
    if (!prop) continue;
    if (seen.has(prop.key)) {
      errors.push({
        code: DiagnosticCode.DuplicateProperty,
        message: `duplicate property '${prop.key}' — the first one is read`,
        severity: 'error',
        source: prop.source,
      });
    } else {
      seen.add(prop.key);
      if (prop.kind === 'description') {
        if (prop.value.kind === 'localizedString') body.descriptionLocalized = prop.value;
        else body.description = prop.value;
      } else if (prop.kind === 'label') body.label = prop.value;
      else if (prop.kind === 'tags') body.tags = prop.value;
    }
    body.properties.push(prop);
  }
  return body;
}

/** One cn property, or `undefined` when error recovery left it half-built (its syntax error is reported). */
function walkConceptProperty(
  c: ParseTree | null,
  host: 'concept' | 'binding' | 'role',
  file: string,
  errors: ParseError[],
): ConceptProperty | undefined {
  if (!(c instanceof ParserRuleContext)) return undefined;
  const key = c.getChild(0)?.getText() ?? '';
  const source = makeSourceLocation(c, file);
  if (c instanceof DescriptionPropertyContext) {
    const value = descriptionOf(c, file) ?? descriptionLocalizedOf(c, file);
    return value ? { kind: 'description', key, value, source } : undefined;
  }
  if (c instanceof LabelPropertyContext) {
    const map = c.localizedString();
    return map ? { kind: 'label', key, value: walkLocalizedString(map, file), source } : undefined;
  }
  if (c instanceof TagsPropertyContext) {
    const list = c.listOfStrings();
    return list ? { kind: 'tags', key, value: walkListOfStrings(list, file), source } : undefined;
  }
  if (c instanceof CnRefPropertyContext) {
    const ref = referenceOf(c.id(), file);
    return ref ? { kind: 'ref', key: key as ConceptRefKey, ref, source } : undefined;
  }
  if (c instanceof GroundsPropertyContext) {
    const ref = referenceOf(c.id(), file);
    return ref ? { kind: 'grounds', key, ref, source } : undefined;
  }
  if (c instanceof ValueSpacePropertyContext) {
    const ref = referenceOf(c.id(), file);
    return ref ? { kind: 'valueSpace', key, ref, source } : undefined;
  }
  if (c instanceof InvolvesPropertyContext) {
    const participants = c.participant().flatMap((pc) => {
      const ref = referenceOf(pc.id(0), file);
      if (!ref) return [];
      const label = pc.AS() ? pc.id(1)?.getText() : undefined;
      return [{ ref, ...(label ? { label } : {}), source: makeSourceLocation(pc, file) }];
    });
    return { kind: 'involves', key, participants, source };
  }
  if (c instanceof PeriodPropertyContext) {
    const obj = c.cnObject();
    return obj ? { kind: 'period', key, value: walkCnObject(obj, file), source } : undefined;
  }
  if (c instanceof ExtentPropertyContext || c instanceof BasisPropertyContext) {
    const id = c.id();
    if (!id) return undefined;
    return { kind: c instanceof ExtentPropertyContext ? 'extent' : 'basis', key, value: id.getText(), source };
  }
  if (c instanceof DefinitionPropertyContext || c instanceof ConceptMembersPropertyContext) {
    const literal = c.embeddedBlock();
    const token = literal?.TAGGED_BLOCK_LITERAL() ?? literal?.TRIPLE_STRING_LITERAL() ?? literal?.STRING_LITERAL();
    const block = token ? walkExpressionBlock(token, key, file, errors) : undefined;
    if (!block) return undefined;
    return { kind: c instanceof DefinitionPropertyContext ? 'definition' : 'members', key, block, source };
  }
  if (c instanceof LexiconBlockPropertyContext) {
    return c.object_() ? { kind: 'lexicon', key, block: walkLexiconBlock(c, file), source } : undefined;
  }
  if (c instanceof TargetPropertyContext) {
    return c.object_() || c.id() ? { kind: 'target', key, value: walkTargetValue(c, file), source } : undefined;
  }
  if (c instanceof CnEntryContext) {
    const entryKey = c.cnKey()?.getText();
    const valueCtx = c.cnValue();
    if (!entryKey || !valueCtx) return undefined;
    // A binding's `filter:` is an expression block; anything else there is a mistake worth naming.
    if (host === 'binding' && entryKey === 'filter') {
      const form = valueCtx.literal()?.stringLiteralForm();
      const token = form?.TAGGED_BLOCK_LITERAL() ?? form?.TRIPLE_STRING_LITERAL() ?? form?.STRING_LITERAL();
      const block = token ? walkExpressionBlock(token, entryKey, file, errors) : undefined;
      if (block) return { kind: 'filter', key: entryKey, block, source };
      errors.push({
        code: DiagnosticCode.ExpressionBlockRequired,
        message: `'filter:' takes an expression block — write it as """…""" (optionally tagged """pl)`,
        severity: 'error',
        source: makeSourceLocation(valueCtx, file),
      });
    }
    return { kind: 'entry', key: entryKey, value: walkCnValue(valueCtx, file), source };
  }
  // A cnc / automation role property on a concept role (review-114 M5, the other way round).
  if (host === 'role') {
    errors.push({
      code: DiagnosticCode.UnknownProperty,
      message: `unknown role property '${key}' in model cn — search, resolve, delivery and members belong to a cnc or automation role`,
      severity: 'error',
      source,
    });
  }
  return undefined;
}

function referenceOf(ctx: IdContext | null, file: string): Reference | undefined {
  if (!ctx || isConjured(ctx)) return undefined;
  const { path, parts, source } = walkId(ctx, file);
  return { path, parts, source };
}

/** A token the error strategy invented to recover (`<missing IDENT>`) — never real input. */
function isConjured(ctx: ParserRuleContext): boolean {
  return (ctx.start?.tokenIndex ?? -1) < 0 || ctx.getText().startsWith('<missing ');
}

function walkCnValue(ctx: CnValueContext, file: string): PropertyValue {
  if (ctx.cnObject()) return walkCnObject(ctx.cnObject()!, file);
  const list = ctx.cnList();
  if (list) return { kind: 'list', items: list.cnValue().map((v) => walkCnValue(v, file)), source: makeSourceLocation(list, file) };
  if (ctx.literal()) return walkLiteral(ctx.literal()!, file);
  if (ctx.id()) return walkId(ctx.id()!, file);
  return { kind: 'null', source: makeSourceLocation(ctx, file) };
}

function walkCnObject(ctx: CnObjectContext, file: string): ObjectValue {
  const entries: ObjectEntry[] = [];
  for (const e of ctx.cnObjectEntry()) {
    const valueCtx = e.cnValue();
    const key = e.getChild(0)?.getText();
    if (!key || !valueCtx) continue;
    entries.push({ key, value: walkCnValue(valueCtx, file), source: makeSourceLocation(e, file) });
  }
  return { kind: 'object', entries, source: makeSourceLocation(ctx, file) };
}

/**
 * An expression block (⚑CN-P5, CN-D28) — the `definitionSql` peeling, with the text's place in the
 * file kept exactly: `lineOffsets` holds where every line of `text` starts once the dedent removed
 * its indent, so a diagnostic from the expression parser can point inside the block. Only `pl` (or
 * no tag) names the expression language; a SQL-family tag is an error, any other first-line word is
 * read as text (the lexer cannot tell `"""exists␊…` from a tag).
 */
function walkExpressionBlock(token: TerminalNode, key: string, file: string, errors: ParseError[]): EmbeddedBlock | undefined {
  const sym = token.symbol;
  if (sym.tokenIndex < 0 || sym.start < 0) return undefined; // conjured by error recovery
  const raw = sym.text ?? '';
  const source: SourceLocation = {
    file,
    line: sym.line,
    column: sym.column,
    ...endOf(raw, sym.line, sym.column),
    offsetStart: sym.start,
    offsetEnd: sym.stop + 1,
  };

  if (sym.type === TTRLexer.STRING_LITERAL) {
    // Each escape takes two characters of the file and one of `text`: `escapes` keeps where, so a
    // position in `text` still maps onto the file.
    // `[\s\S]`, not `.`: the lexer admits a backslash before any character, a line break too, and the
    // three regex engines disagree on what `.` matches.
    const quoted = raw.slice(1, -1);
    const escapes: number[] = [];
    const text = quoted.replace(/\\([\s\S])/g, (_m, c: string, at: number) => {
      escapes.push(codePoints(quoted.slice(0, at)) - escapes.length);
      return c;
    });
    return blockOf('string', text, source, sym.start + 1, sym.line, sym.column + 1, 0, stringLineOffsets(text, escapes, sym.start + 1), escapes);
  }

  let inner = raw.slice(3, -3);
  let innerStart = sym.start + 3;
  let form: EmbeddedBlock['form'] = 'tripleString';
  if (sym.type === TTRLexer.TAGGED_BLOCK_LITERAL) {
    const opener = /^([A-Za-z][A-Za-z0-9-]*)([ \t]*\r?\n)/.exec(inner);
    const tag = opener?.[1];
    const tagSource: SourceLocation = {
      file, line: sym.line, column: sym.column + 3, endLine: sym.line, endColumn: sym.column + 3 + (tag?.length ?? 0),
      offsetStart: sym.start + 3, offsetEnd: sym.start + 3 + (tag?.length ?? 0),
    };
    if (opener && tag && resolveExpressionTag(tag)) {
      inner = inner.slice(opener[0].length);
      innerStart += opener[0].length;
      form = 'tagged';
    } else if (tag && resolveTag(tag)) {
      errors.push({
        code: DiagnosticCode.LanguageTagMismatch,
        message: `'${key}:' holds a TTR-P expression — the '${tag}' tag names another language (use """pl or no tag)`,
        severity: 'error',
        source: tagSource,
      });
    } else if (tag) {
      errors.push({
        code: DiagnosticCode.UnknownLanguageTag,
        message: `'${tag}' on the opening line of '${key}:' reads as a language tag; the block is kept as plain text (start the expression on the next line, or tag it """pl)`,
        severity: 'warning',
        source: tagSource,
      });
    }
  }

  // The dedent (contracts §2.9) drops a leading `\n`, then the common indent of the non-blank lines.
  const leadingNewline = inner.startsWith('\n') ? 1 : 0;
  const body = inner.slice(leadingNewline);
  const { value, indentWidth } = dedentWithIndent(inner);
  const text = form === 'tagged' ? value.replace(/\r?\n$/, '') : value;

  // Every raw line of `text`, its start offset in the file, indent removed (blank lines keep column 0).
  const rawLines = body.split('\n');
  const lineOffsets: number[] = [];
  let offset = innerStart + leadingNewline;
  for (let i = 0; i < text.split('\n').length; i++) {
    const rawLine = rawLines[i] ?? '';
    lineOffsets.push(offset + (rawLine.trim().length === 0 ? 0 : indentWidth));
    offset += codePoints(rawLine) + 1;
  }
  const first = positionAfter(raw.slice(0, innerStart - sym.start + leadingNewline), sym.line, sym.column);
  return blockOf(form, text, source, lineOffsets[0], first.line, first.column + (rawLines[0]?.trim().length ? indentWidth : 0), indentWidth, lineOffsets);
}

function blockOf(
  form: EmbeddedBlock['form'],
  text: string,
  source: SourceLocation,
  textStart: number,
  line: number,
  column: number,
  indentWidth: number,
  lineOffsets: number[],
  escapes: number[] = [],
): EmbeddedBlock {
  const lines = text.split('\n');
  const lastLine = lines.length - 1;
  const lastOffset = lineOffsets[lastLine] ?? textStart;
  // Widths in code points — the unit of the parser's offsets and columns (and of a TTR-P position).
  const lastStart = codePoints(text) - codePoints(lines[lastLine]);
  const lastWidth = codePoints(lines[lastLine]) + escapes.filter((e) => e >= lastStart).length;
  return {
    kind: 'embeddedBlock',
    language: 'pl',
    form,
    text,
    textSource: {
      file: source.file,
      line,
      column,
      endLine: line + lastLine,
      endColumn: (lastLine === 0 ? column : lines[lastLine].length ? indentWidth : 0) + lastWidth,
      offsetStart: textStart,
      offsetEnd: lastOffset + lastWidth,
    },
    lineOffsets,
    indentWidth,
    ...(escapes.length ? { escapes } : {}),
    source,
  };
}

/**
 * Where each line of a string-form block's `text` starts in the file: its code-point index in `text`, plus
 * one for every escape before it (an escaped line break is what starts a second line).
 */
function stringLineOffsets(text: string, escapes: number[], textStart: number): number[] {
  const offsets: number[] = [];
  let start = 0;
  for (const line of text.split('\n')) {
    offsets.push(textStart + start + escapes.filter((e) => e < start).length);
    start += codePoints(line) + 1;
  }
  return offsets;
}

/** The number of code points in `s` — what ANTLR counts in offsets and columns (a JS length counts UTF-16 units). */
function codePoints(s: string): number {
  return [...s].length;
}

/** The 1-based line and 0-based column (in code points) just past `text`, which starts at `line`:`column`. */
function positionAfter(text: string, line: number, column: number): { line: number; column: number } {
  const parts = text.split('\n');
  return parts.length === 1
    ? { line, column: column + codePoints(text) }
    : { line: line + parts.length - 1, column: codePoints(parts[parts.length - 1]) };
}

function endOf(raw: string, line: number, column: number): { endLine: number; endColumn: number } {
  const end = positionAfter(raw, line, column);
  return { endLine: end.line, endColumn: end.column };
}

// ============================================================================
// v3.1 — MD (multidimensional) logical object walkers. Cross-references are kept
// as opaque strings here; resolution and per-schema validity are Phase 2.
// ============================================================================

function walkMdDomainDef(ctx: MdDomainDefContext, name: string, source: SourceLocation, file: string, errors: ParseError[]): MdDomainDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let type: DataType | undefined;
  let domainKind: string | undefined;
  let restrict: RestrictClause[] | undefined;
  let publishMembers: boolean | undefined;
  let concept: ConceptSugar | undefined;

  for (const p of ctx.mdDomainProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.typeProperty()) type = walkDataType(p.typeProperty()!.dataType()!, file);
    if (p.kindProperty()) domainKind = p.kindProperty()!.id()!.getText();
    if (p.restrictProperty()) restrict = walkRestrictBlock(p.restrictProperty()!.restrictBlock()!, file);
    if (p.publishProperty()) publishMembers = true; // MD dot-path §1.4 — `publish: members`
    if (p.conceptSugarProperty()) concept = conceptSugarOnce(concept, p.conceptSugarProperty()!, file, errors); // 0.17
  }

  return { kind: 'mdDomain', name, source, description, descriptionLocalized, tags, type, domainKind, restrict, publishMembers, ...(concept ? { concept } : {}) };
}

function walkRestrictBlock(ctx: RestrictBlockContext, file: string): RestrictClause[] {
  const clauses: RestrictClause[] = [];
  for (const c of ctx.restrictClause()) {
    const clause = c.key().getText();
    const rv = c.restrictValue();
    let value: RestrictClause['value'];
    if (rv.rangeLiteral()) value = walkRangeLiteral(rv.rangeLiteral()!, file);
    else if (rv.membersBlock()) value = walkMembersBlock(rv.membersBlock()!, file);
    else value = walkValue(rv.value()!, file);
    clauses.push({ clause, value, source: makeSourceLocation(c, file) });
  }
  return clauses;
}

function walkRangeLiteral(ctx: RangeLiteralContext, file: string): RangeLiteral {
  const nums = ctx.NUMBER_LITERAL();
  const lo = Number(nums[0]?.getText() ?? '0');
  const hi = Number(nums[1]?.getText() ?? '0');
  return { kind: 'rangeLiteral', lo, hi, source: makeSourceLocation(ctx, file) };
}

function walkMembersBlock(ctx: MembersBlockContext, file: string): DomainMember[] {
  const members: DomainMember[] = [];
  for (const m of ctx.memberEntry()) {
    const keyForm = m.stringLiteralForm();
    const key = keyForm ? walkStringLiteralForm(keyForm, file).value : '';
    const labels = walkLocalizedString(m.localizedString()!, file);
    members.push({ key, labels, source: makeSourceLocation(m, file) });
  }
  return members;
}

function walkDimensionDef(ctx: DimensionDefContext, name: string, source: SourceLocation, file: string, errors: ParseError[]): DimensionDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let key: string | undefined;
  let attributes: AttributeDef[] = [];
  let hierarchies: string[] | undefined;
  let lexicon: LexiconBlock | undefined;
  let concept: ConceptSugar | undefined;
  const crossRefs: CrossRef[] = [];

  for (const p of ctx.dimensionProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.keyProperty()) key = p.keyProperty()!.id()!.getText();
    if (p.attributesProperty()) attributes = walkAttributeDefList(p.attributesProperty()!.attributeDefList()!, file, errors);
    if (p.hierarchiesProperty()) {
      const refs = idsWithSources(p.hierarchiesProperty()!.listOfIds()!, file);
      hierarchies = refs.map((r) => r.path);
      for (const r of refs) crossRefs.push({ role: 'hierarchy', path: r.path, source: r.source });
    }
    if (p.lexiconBlockProperty()) lexicon = walkLexiconBlock(p.lexiconBlockProperty()!, file);
    if (p.conceptSugarProperty()) concept = conceptSugarOnce(concept, p.conceptSugarProperty()!, file, errors); // 0.17
  }

  return { kind: 'dimension', name, source, description, descriptionLocalized, tags, key, attributes, hierarchies, lexicon, crossRefs: crossRefs.length ? crossRefs : undefined, ...(concept ? { concept } : {}) };
}

/** `from`/`to` accept a single id or a bracketed list of ids, each with its source span (for crossRefs). */
function refListWithSources(ctx: ValueContext, file: string): { path: string; source: SourceLocation }[] {
  if (ctx.id()) return [{ path: ctx.id()!.getText(), source: makeSourceLocation(ctx.id()!, file) }];
  const list = ctx.list();
  if (list) {
    return list.value().map((v) =>
      v.id() ? { path: v.id()!.getText(), source: makeSourceLocation(v.id()!, file) } : { path: v.getText(), source: makeSourceLocation(v, file) }
    );
  }
  return [{ path: ctx.getText(), source: makeSourceLocation(ctx, file) }];
}

/** Each id in a `listOfIds` with its source span (grain / hierarchies / measure refs). */
function idsWithSources(ctx: ListOfIdsContext, file: string): { path: string; source: SourceLocation }[] {
  return ctx.id().map((idCtx) => ({ path: idCtx.getText(), source: makeSourceLocation(idCtx, file) }));
}

/** Normalise the grammar's `cardinality` object to '1:1' | 'N:1' (default N:1, contracts §3.4). */
function normalizeCardinality(ctx: Object_Context, file: string): '1:1' | 'N:1' {
  const obj = walkObject(ctx, file);
  const valueOf = (k: string): string | undefined => {
    const e = obj.entries.find((entry) => entry.key === k);
    if (!e) return undefined;
    if (e.value.kind === 'string' || e.value.kind === 'tripleString') return e.value.value;
    if (e.value.kind === 'id') return e.value.path;
    if (e.value.kind === 'number') return String(e.value.value);
    return undefined;
  };
  return valueOf('from') === '1' && valueOf('to') === '1' ? '1:1' : 'N:1';
}

function walkMdMapDef(ctx: MdMapDefContext, name: string, source: SourceLocation, file: string): MdMapDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let from: string[] = [];
  let to: string[] = [];
  let cardinality: '1:1' | 'N:1' | undefined;
  let calc: CalcRef | undefined;
  const crossRefs: CrossRef[] = [];

  for (const p of ctx.mdMapProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.fromProperty()) {
      const refs = refListWithSources(p.fromProperty()!.value()!, file);
      from = refs.map((r) => r.path);
      for (const r of refs) crossRefs.push({ role: 'domain', path: r.path, source: r.source });
    }
    if (p.toProperty()) {
      const refs = refListWithSources(p.toProperty()!.value()!, file);
      to = refs.map((r) => r.path);
      for (const r of refs) crossRefs.push({ role: 'domain', path: r.path, source: r.source });
    }
    if (p.cardinalityProperty()) cardinality = normalizeCardinality(p.cardinalityProperty()!.object_()!, file);
    if (p.calcProperty()) calc = walkCalcRef(p.calcProperty()!.calcRef()!, file);
  }

  return { kind: 'mdMap', name, source, description, descriptionLocalized, tags, from, to, cardinality, calc, crossRefs: crossRefs.length ? crossRefs : undefined };
}

function walkCalcRef(ctx: CalcRefContext, file: string): CalcRef {
  const name = ctx.id()!.getText();
  const args: CalcArg[] = [];
  for (const a of ctx.calcArg()) {
    args.push({ name: a.id()!.getText(), value: walkValue(a.value()!, file), source: makeSourceLocation(a, file) });
  }
  return { kind: 'calcRef', name, args, source: makeSourceLocation(ctx, file) };
}

function walkHierarchyDef(ctx: HierarchyDefContext, name: string, source: SourceLocation, file: string): HierarchyDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let dimensionRef: string | undefined;
  let levels: HierarchyLevel[] = [];
  const crossRefs: CrossRef[] = [];

  for (const p of ctx.hierarchyProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.dimensionRefProperty()) {
      const idCtx = p.dimensionRefProperty()!.id()!;
      dimensionRef = idCtx.getText();
      crossRefs.push({ role: 'dimension', path: dimensionRef, source: makeSourceLocation(idCtx, file) });
    }
    if (p.levelsProperty()) {
      const ll = p.levelsProperty()!.levelList()!;
      levels = walkLevelList(ll, file);
      for (const e of ll.levelEntry()) {
        if (e.VIA()) {
          const viaCtx = e.id()[1];
          if (viaCtx) crossRefs.push({ role: 'map', path: viaCtx.getText(), source: makeSourceLocation(viaCtx, file) });
        }
      }
    }
  }

  return { kind: 'hierarchy', name, source, description, descriptionLocalized, tags, dimensionRef, levels, crossRefs: crossRefs.length ? crossRefs : undefined };
}

function walkLevelList(ctx: LevelListContext, file: string): HierarchyLevel[] {
  const out: HierarchyLevel[] = [];
  for (const e of ctx.levelEntry()) {
    const ids = e.id();
    const attribute = ids[0]?.getText() ?? '';
    const via = e.VIA() ? ids[1]?.getText() : undefined;
    out.push({ attribute, via, source: makeSourceLocation(e, file) });
  }
  return out;
}

function walkMeasureDef(ctx: MeasureDefContext, name: string, source: SourceLocation, file: string, errors: ParseError[]): MeasureDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let domainRef: string | undefined;
  let measureClass: string | undefined;
  let aggregation: AggregationSpec | undefined;
  let validBy: string | undefined;
  let lexicon: LexiconBlock | undefined;
  let concept: ConceptSugar | undefined;
  const crossRefs: CrossRef[] = [];

  for (const p of ctx.measureProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.domainRefProperty()) {
      const idCtx = p.domainRefProperty()!.id()!;
      domainRef = idCtx.getText();
      crossRefs.push({ role: 'domain', path: domainRef, source: makeSourceLocation(idCtx, file) });
    }
    if (p.classProperty()) measureClass = p.classProperty()!.id()!.getText();
    if (p.aggregationProperty()) aggregation = walkAggregationValue(p.aggregationProperty()!.aggregationValue()!, file);
    if (p.validByProperty()) validBy = p.validByProperty()!.id()!.getText();
    if (p.lexiconBlockProperty()) lexicon = walkLexiconBlock(p.lexiconBlockProperty()!, file);
    if (p.conceptSugarProperty()) concept = conceptSugarOnce(concept, p.conceptSugarProperty()!, file, errors); // 0.17
  }

  return { kind: 'measure', name, source, description, descriptionLocalized, tags, domainRef, measureClass, aggregation, validBy, lexicon, crossRefs: crossRefs.length ? crossRefs : undefined, ...(concept ? { concept } : {}) };
}

function walkAggregationValue(ctx: AggregationValueContext, file: string): AggregationSpec {
  if (ctx.id()) {
    return { default: ctx.id()!.getText(), source: makeSourceLocation(ctx, file) };
  }
  let def: string | undefined;
  const perDimension: Record<string, string> = {};
  const obj = ctx.object_();
  if (obj) {
    for (const entry of obj.propertyList()?.propertyEntry() ?? []) {
      const k = entry.key().getText();
      const v = entry.value();
      const text = v?.id() ? v.id()!.getText() : (v ? v.getText() : '');
      if (k === 'default') def = text;
      else perDimension[k] = text;
    }
  }
  return {
    default: def,
    perDimension: Object.keys(perDimension).length > 0 ? perDimension : undefined,
    source: makeSourceLocation(ctx, file),
  };
}

function walkCubeletDef(ctx: CubeletDefContext, name: string, source: SourceLocation, file: string, errors: ParseError[]): CubeletDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let grain: string[] = [];
  let measures: (string | MeasureDef)[] = [];
  let lexicon: LexiconBlock | undefined;
  let concept: ConceptSugar | undefined;
  const crossRefs: CrossRef[] = [];

  for (const p of ctx.cubeletProperty()) {
    if (p.conceptSugarProperty()) concept = conceptSugarOnce(concept, p.conceptSugarProperty()!, file, errors); // 0.17
    if (p.lexiconBlockProperty()) lexicon = walkLexiconBlock(p.lexiconBlockProperty()!, file);
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.grainProperty()) {
      const refs = idsWithSources(p.grainProperty()!.listOfIds()!, file);
      grain = refs.map((r) => r.path);
      for (const r of refs) crossRefs.push({ role: 'grain', path: r.path, source: r.source });
    }
    if (p.measuresProperty()) {
      const mv = p.measuresProperty()!.measuresValue()!;
      measures = walkMeasuresValue(mv, file, errors);
      // string measure refs (the listOfIds form) carry resolvable spans.
      if (mv.listOfIds()) {
        for (const r of idsWithSources(mv.listOfIds()!, file)) {
          crossRefs.push({ role: 'measure', path: r.path, source: r.source });
        }
      }
    }
  }

  return { kind: 'cubelet', name, source, description, descriptionLocalized, tags, grain, measures, lexicon, crossRefs: crossRefs.length ? crossRefs : undefined, ...(concept ? { concept } : {}) };
}

function walkMeasuresValue(ctx: MeasuresValueContext, file: string, errors: ParseError[]): (string | MeasureDef)[] {
  if (ctx.listOfIds()) return walkListOfIds(ctx.listOfIds()!, file);
  const inline = ctx.measureInlineList();
  const out: (string | MeasureDef)[] = [];
  if (inline) {
    const ids = inline.id();
    const defs = inline.measureDef();
    for (let i = 0; i < defs.length; i++) {
      const mName = ids[i]?.getText() ?? '';
      out.push(walkMeasureDef(defs[i], mName, makeSourceLocation(defs[i], file), file, errors));
    }
  }
  return out;
}

// ============================================================================
// v3.1 — MD binding object walkers (schema binding). The generic `object_` maps
// (attributes/measures/source/columns) are turned into the typed records below;
// references stay opaque strings. Validation is Phase 3.
// ============================================================================

/** A single field of a walked object as a flat string (id path or string literal), or ''. */
function objField(obj: ObjectValue, key: string): string {
  const e = obj.entries.find((entry) => entry.key === key);
  if (!e) return '';
  const v = e.value;
  if (v.kind === 'id') return v.path;
  if (v.kind === 'string' || v.kind === 'tripleString') return v.value;
  return '';
}

/** A `target:`/`source:`-style ref: the bare id, or the `table` field of an object form. */
function targetTableRef(tp: TargetPropertyContext, file: string): string {
  if (tp.id()) return tp.id()!.getText();
  const obj = tp.object_();
  return obj ? objField(walkObject(obj, file), 'table') : '';
}

/** A flat string→string record (md2db_map columns, md2er_cubelet attributes). */
function walkStringRecord(ctx: Object_Context, file: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const entry of walkObject(ctx, file).entries) out[entry.key] = stringFromValue(entry.value);
  return out;
}

function stringFromValue(v: PropertyValue): string {
  if (v.kind === 'id') return v.path;
  if (v.kind === 'string' || v.kind === 'tripleString') return v.value;
  return '';
}

function walkShapeValue(ctx: ShapeValueContext, file: string): ShapeSpec {
  // `wide` is the only bare-id shape; `long` is always the object form. Any other
  // bare id is a semantic error (Phase 3), recorded here as the closest variant.
  if (ctx.id()) return { shape: 'wide' };
  const obj = ctx.object_();
  if (obj) {
    const o = walkObject(obj, file);
    const longEntry = o.entries.find((e) => e.key === 'long');
    if (longEntry && longEntry.value.kind === 'object') {
      const inner = longEntry.value;
      return {
        shape: 'long',
        codeColumn: objField(inner, 'codeColumn'),
        valueColumn: objField(inner, 'valueColumn'),
      };
    }
  }
  return { shape: 'wide' };
}

function walkJournalingValue(ctx: JournalingValueContext, file: string): JournalingSpec {
  if (ctx.id()) {
    return ctx.id()!.getText() === 'diff' ? { mode: 'diff' } : { mode: 'overwrite' };
  }
  const obj = ctx.object_();
  if (obj) {
    const o = walkObject(obj, file);
    const inv = o.entries.find((e) => e.key === 'invalidate');
    if (inv && inv.value.kind === 'object') {
      return { mode: 'invalidate', validColumn: objField(inv.value, 'validColumn') };
    }
  }
  return { mode: 'overwrite' };
}

function walkAllocationValue(ctx: AllocationValueContext, _file: string): AllocationSpec {
  // `allocation: proportional` (bare id ⇒ uniform, all spread dims) vs
  // `allocation: { time: equal, product: proportional }` (per-dimension map). The
  // strategy value stays opaque here — semantics validates equal/proportional.
  if (ctx.id()) {
    return { uniform: ctx.id()!.getText() };
  }
  const obj = ctx.object_();
  const byDimension: Record<string, string> = {};
  if (obj) {
    for (const entry of obj.propertyList()?.propertyEntry() ?? []) {
      const v = entry.value();
      if (!v) continue;
      byDimension[entry.key().getText()] = v.id() ? v.id()!.getText() : v.getText();
    }
  }
  return { byDimension };
}

function walkAttrColumnBindings(ctx: Object_Context, file: string): Record<string, AttrColumnBinding> {
  const out: Record<string, AttrColumnBinding> = {};
  for (const entry of ctx.propertyList()?.propertyEntry() ?? []) {
    const key = entry.key().getText();
    const v = entry.value();
    if (!v) continue;
    const objCtx = v.object_();
    if (objCtx) {
      // map-mediated: { via: md.…, from: { table: …, column: … } }
      const o = walkObject(objCtx, file);
      const fromEntry = o.entries.find((e) => e.key === 'from');
      const from =
        fromEntry && fromEntry.value.kind === 'object'
          ? { table: objField(fromEntry.value, 'table'), column: objField(fromEntry.value, 'column') }
          : { table: '', column: '' };
      out[key] = { via: objField(o, 'via'), from };
    } else {
      out[key] = { column: v.id() ? v.id()!.getText() : v.getText() };
    }
  }
  return out;
}

function walkMeasureColumnBindings(ctx: Object_Context, file: string): Record<string, MeasureColumnBinding> {
  const out: Record<string, MeasureColumnBinding> = {};
  for (const entry of ctx.propertyList()?.propertyEntry() ?? []) {
    const key = entry.key().getText();
    const v = entry.value();
    if (!v) continue;
    const objCtx = v.object_();
    if (objCtx) {
      // long shape: { code: <CODE_VALUE> }
      out[key] = { code: objField(walkObject(objCtx, file), 'code') };
    } else {
      out[key] = { column: v.id() ? v.id()!.getText() : v.getText() };
    }
  }
  return out;
}

function walkMd2DbCubeletDef(ctx: Md2dbCubeletDefContext, name: string, source: SourceLocation, file: string): Md2DbCubeletDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let cubeletRef = '';
  let table = '';
  let shape: ShapeSpec = { shape: 'wide' };
  let attributes: Record<string, AttrColumnBinding> = {};
  let measures: Record<string, MeasureColumnBinding> = {};
  let journaling: JournalingSpec | undefined;
  let allocation: AllocationSpec | undefined;

  for (const p of ctx.md2dbCubeletProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.cubeletRefProperty()) cubeletRef = p.cubeletRefProperty()!.id()!.getText();
    if (p.targetProperty()) table = targetTableRef(p.targetProperty()!, file);
    if (p.shapeProperty()) shape = walkShapeValue(p.shapeProperty()!.shapeValue()!, file);
    if (p.attributesMapProperty()) attributes = walkAttrColumnBindings(p.attributesMapProperty()!.object_()!, file);
    if (p.measuresMapProperty()) measures = walkMeasureColumnBindings(p.measuresMapProperty()!.object_()!, file);
    if (p.journalingProperty()) journaling = walkJournalingValue(p.journalingProperty()!.journalingValue()!, file);
    if (p.allocationProperty()) allocation = walkAllocationValue(p.allocationProperty()!.allocationValue()!, file);
  }

  return { kind: 'md2dbCubelet', name, source, description, descriptionLocalized, tags, cubeletRef, table, shape, attributes, measures, journaling, allocation };
}

function walkMd2DbDomainDef(ctx: Md2dbDomainDefContext, name: string, source: SourceLocation, file: string): Md2DbDomainDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let domainRef = '';
  let source_ = { table: '', column: '' };

  for (const p of ctx.md2dbDomainProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.domainRefProperty()) domainRef = p.domainRefProperty()!.id()!.getText();
    if (p.sourceProperty()) {
      const o = walkObject(p.sourceProperty()!.object_()!, file);
      source_ = { table: objField(o, 'table'), column: objField(o, 'column') };
    }
  }

  return { kind: 'md2dbDomain', name, source, description, descriptionLocalized, tags, domainRef, source_ };
}

function walkMd2DbMapDef(ctx: Md2dbMapDefContext, name: string, source: SourceLocation, file: string): Md2DbMapDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let mapRef = '';
  let table = '';
  let columns: Record<string, string> = {};

  for (const p of ctx.md2dbMapProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.mapRefProperty()) mapRef = p.mapRefProperty()!.id()!.getText();
    if (p.targetProperty()) table = targetTableRef(p.targetProperty()!, file);
    if (p.columnsMapProperty()) columns = walkStringRecord(p.columnsMapProperty()!.object_()!, file);
  }

  return { kind: 'md2dbMap', name, source, description, descriptionLocalized, tags, mapRef, table, columns };
}

function walkMd2ErDimensionDef(ctx: Md2erDimensionDefContext, name: string, source: SourceLocation, file: string): Md2ErDimensionDef {
  const def: Md2ErDimensionDef = { kind: 'md2erDimension', name, source, dimensionRef: '', entity: '', attributes: {} };
  for (const p of ctx.md2erDimensionProperty()) {
    if (p.descriptionProperty()) {
      def.description = descriptionOf(p.descriptionProperty()!, file);
      def.descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) def.tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.dimensionRefProperty()) {
      def.dimensionReference = referenceOf(p.dimensionRefProperty()!.id()!, file);
      def.dimensionRef = def.dimensionReference?.path ?? '';
    }
    if (p.entityProperty_()) {
      def.entityReference = referenceOf(p.entityProperty_()!.id()!, file);
      def.entity = def.entityReference?.path ?? '';
    }
    if (p.attributesMapProperty()) def.attributes = walkStringRecord(p.attributesMapProperty()!.object_()!, file);
  }
  return def;
}

function walkMd2ErCubeletDef(ctx: Md2erCubeletDefContext, name: string, source: SourceLocation, file: string): Md2ErCubeletDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let cubeletRef = '';
  let entity = '';
  let attributes: Record<string, string> = {};
  let measures: Record<string, string> | undefined;
  const physicalProps: string[] = [];

  for (const p of ctx.md2erCubeletProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.cubeletRefProperty()) cubeletRef = p.cubeletRefProperty()!.id()!.getText();
    if (p.targetProperty()) entity = targetTableRef(p.targetProperty()!, file);
    if (p.attributesMapProperty()) attributes = walkStringRecord(p.attributesMapProperty()!.object_()!, file);
    // Permissive-superset props that semantics rejects (md/md2er-physical-prop).
    if (p.shapeProperty()) physicalProps.push('shape');
    if (p.measuresMapProperty()) measures = walkStringRecord(p.measuresMapProperty()!.object_()!, file);
    if (p.journalingProperty()) physicalProps.push('journaling');
    if (p.allocationProperty()) physicalProps.push('allocation');
  }

  return { kind: 'md2erCubelet', name, source, description, descriptionLocalized, tags, cubeletRef, entity, attributes, measures, physicalProps: physicalProps.length ? physicalProps : undefined };
}

function walkGraphLayout(ctx: Object_Context, file: string): GraphLayout {
  let viewport: GraphLayout['viewport'] | undefined;
  const nodes: Record<string, { x: number; y: number }> = {};
  const edges: Record<string, { bendPoints?: [number, number][] }> = {};

  for (const entry of ctx.propertyList()?.propertyEntry() ?? []) {
    const key = entry.key().getText();
    const valueCtx = entry.value();
    if (!valueCtx) continue;

    if (key === 'viewport' && valueCtx.object_()) {
      viewport = walkViewport(valueCtx.object_()!, file);
    } else if (key === 'nodes' && valueCtx.object_()) {
      for (const nodeEntry of valueCtx.object_()!.propertyList()?.propertyEntry() ?? []) {
        const nodeKey = nodeEntry.key().getText();
        const nodeVal = nodeEntry.value()?.object_();
        if (nodeVal) {
          const xEntry = nodeVal.propertyList()?.propertyEntry().find((e) => e.key().getText() === 'x');
          const yEntry = nodeVal.propertyList()?.propertyEntry().find((e) => e.key().getText() === 'y');
          const x = xEntry?.value()?.literal()?.NUMBER_LITERAL() ? Number(xEntry.value()!.literal()!.NUMBER_LITERAL()!.getText()) : 0;
          const y = yEntry?.value()?.literal()?.NUMBER_LITERAL() ? Number(yEntry.value()!.literal()!.NUMBER_LITERAL()!.getText()) : 0;
          nodes[nodeKey] = { x, y };
        }
      }
    } else if (key === 'edges' && valueCtx.object_()) {
      for (const edgeEntry of valueCtx.object_()!.propertyList()?.propertyEntry() ?? []) {
        const edgeKey = edgeEntry.key().getText();
        const edgeVal = edgeEntry.value()?.object_();
        if (!edgeVal) continue;

        const bpEntry = edgeVal.propertyList()?.propertyEntry()
          .find((e) => e.key().getText() === 'bendPoints');
        const bpList  = bpEntry?.value()?.list();
        if (!bpList) {
          edges[edgeKey] = {};
          continue;
        }

        const bendPoints: [number, number][] = [];
        for (const item of bpList.value()) {
          const inner = item.list();
          if (!inner) continue;
          const pair = inner.value();
          if (pair.length !== 2) continue;
          const a = pair[0].literal()?.NUMBER_LITERAL();
          const b = pair[1].literal()?.NUMBER_LITERAL();
          if (a && b) {
            bendPoints.push([Number(a.getText()), Number(b.getText())]);
          }
        }
        edges[edgeKey] = { bendPoints: bendPoints.length > 0 ? bendPoints : undefined };
      }
    }
  }

  return { viewport, nodes, edges };
}

function walkViewport(ctx: Object_Context, _file: string): GraphLayout['viewport'] {
  let zoom = 1.0;
  let panX = 0;
  let panY = 0;
  let displayMode = 'just-names';

  const list = ctx.propertyList();
  if (!list) return { zoom, panX, panY, displayMode };
  for (const entry of list.propertyEntry()) {
    const key = entry.key().getText();
    const val = entry.value();
    if (!val) continue;
    if (key === 'zoom' && val.literal()?.NUMBER_LITERAL()) {
      zoom = Number(val.literal()!.NUMBER_LITERAL()!.getText());
    } else if (key === 'panX' && val.literal()?.NUMBER_LITERAL()) {
      panX = Number(val.literal()!.NUMBER_LITERAL()!.getText());
    } else if (key === 'panY' && val.literal()?.NUMBER_LITERAL()) {
      panY = Number(val.literal()!.NUMBER_LITERAL()!.getText());
    } else if (key === 'displayMode') {
      const idCtx = val.id();
      if (idCtx) displayMode = idCtx.getText();
    }
  }

  return { zoom, panX, panY, displayMode };
}

function walkDefinition(ctx: DefinitionContext, file: string, errors: ParseError[], modelCode?: string): Definition {
  const objDef = ctx.objectDefinition();
  const nameCtx = objDef.id();
  const name = nameCtx ? nameCtx.getText() : '';
  const source = makeSourceLocation(ctx, file);

  if (objDef.PROJECT()) return walkProjectDef(objDef.projectDef()!, name, source, file);
  if (objDef.TABLE()) return walkTableDef(objDef.tableDef()!, name, source, file, errors);
  if (objDef.VIEW()) return walkViewDef(objDef.viewDef()!, name, source, file, errors);
  if (objDef.COLUMN()) return walkColumnDef(objDef.columnDef()!, name, source, file, errors);
  if (objDef.INDEX()) return walkIndexDef(objDef.indexDef()!, name, source, file);
  if (objDef.CONSTRAINT()) return walkConstraintDef(objDef.constraintDef()!, name, source, file);
  if (objDef.FK()) return walkFkDef(objDef.fkDef()!, name, source, file);
  if (objDef.PROCEDURE()) return walkProcedureDef(objDef.procedureDef()!, name, source, file, errors);
  if (objDef.ENTITY()) return walkEntityDef(objDef.entityDef()!, name, source, file, errors);
  if (objDef.ATTRIBUTE()) return walkAttributeDef(objDef.attributeDef()!, name, source, file, errors);
  if (objDef.RELATION()) return walkRelationDef(objDef.relationDef()!, name, source, file);
  if (objDef.ER2DB_ENTITY()) return walkEr2dbEntityDef(objDef.er2dbEntityDef()!, name, source, file);
  if (objDef.ER2DB_ATTRIBUTE()) return walkEr2dbAttributeDef(objDef.er2dbAttributeDef()!, name, source, file);
  if (objDef.ER2DB_RELATION()) return walkEr2dbRelationDef(objDef.er2dbRelationDef()!, name, source, file);
  if (objDef.QUERY()) return walkQueryDef(objDef.queryDef()!, name, source, file, errors);
  if (objDef.ROLE()) {
    // ⚑CN-P6 — one `def role` production; a `model cn` file reads it as a concept role.
    if (modelCode === 'cn') {
      return { kind: 'concept', stereotype: 'role', name, source, ...walkConceptBody(objDef.roleDef()?.roleProperty() ?? [], 'role', file, errors) };
    }
    return walkRoleDef(objDef.roleDef()!, name, source, file, errors, modelCode);
  }
  if (objDef.ER2CNC_ROLE()) return walkEr2cncRoleDef(objDef.er2cncRoleDef()!, name, source, file);
  if (objDef.DRILL_MAP()) return walkDrillMapDef(objDef.drillMapDef()!, name, source, file);
  if (objDef.AREA()) return walkAreaDef(objDef.areaDef()!, name, source, file);
  if (objDef.SCHEMA()) return walkSchemaDef(objDef.schemaDef()!, name, source, file, errors);
  if (objDef.WORLD()) return walkWorldDef(objDef.worldDef()!, name, source, file, errors);
  // 0.16 automation kinds (model automation) — one shared body, kind tag distinguishes.
  const autoKind = automationKindOf(objDef);
  if (autoKind) return walkAutomationDef(objDef.autoBody()!, autoKind, name, source, file, errors);
  // v3.1 MD logical kinds
  if (objDef.DOMAIN()) return walkMdDomainDef(objDef.mdDomainDef()!, name, source, file, errors);
  if (objDef.DIMENSION()) return walkDimensionDef(objDef.dimensionDef()!, name, source, file, errors);
  if (objDef.MAP()) return walkMdMapDef(objDef.mdMapDef()!, name, source, file);
  if (objDef.HIERARCHY()) return walkHierarchyDef(objDef.hierarchyDef()!, name, source, file);
  if (objDef.MEASURE()) return walkMeasureDef(objDef.measureDef()!, name, source, file, errors);
  if (objDef.CUBELET()) return walkCubeletDef(objDef.cubeletDef()!, name, source, file, errors);
  // v3.1 MD binding kinds (schema binding)
  if (objDef.MD2DB_CUBELET()) return walkMd2DbCubeletDef(objDef.md2dbCubeletDef()!, name, source, file);
  if (objDef.MD2DB_DOMAIN()) return walkMd2DbDomainDef(objDef.md2dbDomainDef()!, name, source, file);
  if (objDef.MD2DB_MAP()) return walkMd2DbMapDef(objDef.md2dbMapDef()!, name, source, file);
  if (objDef.MD2ER_DIMENSION()) return walkMd2ErDimensionDef(objDef.md2erDimensionDef()!, name, source, file);
  if (objDef.MD2ER_CUBELET()) return walkMd2ErCubeletDef(objDef.md2erCubeletDef()!, name, source, file);
  // v4.4 lexicon kinds (model lexicon) — one shared body, kind tag distinguishes.
  if (objDef.TERM()) return walkLexiconEntryDef(objDef.lexiconEntryDef()!, 'term', name, source, file);
  if (objDef.PATTERN()) return walkLexiconEntryDef(objDef.lexiconEntryDef()!, 'pattern', name, source, file);
  if (objDef.EXAMPLE()) return walkLexiconEntryDef(objDef.lexiconEntryDef()!, 'example', name, source, file);
  // 0.17 cn kinds (model cn / model binding) — one shared body each, the stereotype / binding kind tags it.
  const stereotype = conceptStereotypeOf(objDef);
  if (stereotype) return walkConceptDef(objDef.cnBody(), stereotype, name, source, file, errors);
  const bindingKind = conceptBindingKindOf(objDef);
  if (bindingKind) return walkConceptBindingDef(objDef.cnBindingBody(), bindingKind, name, source, file, errors);

  return { kind: 'project', name, source } satisfies ProjectDef;
}

// ============================================================================
// Walker for value forms
// ============================================================================

function walkValue(ctx: ValueContext, file: string): PropertyValue {
  if (ctx.literal()) return walkLiteral(ctx.literal()!, file);
  if (ctx.id()) return walkId(ctx.id()!, file);
  if (ctx.list()) return walkList(ctx.list()!, file);
  if (ctx.object_()) return walkObject(ctx.object_()!, file);
  if (ctx.functionCall()) return walkFunctionCall(ctx.functionCall()!, file);
  return { kind: 'null', source: makeSourceLocation(ctx, file) } satisfies NullValue;
}

function walkLiteral(ctx: LiteralContext, file: string): PropertyValue {
  if (ctx.NUMBER_LITERAL()) {
    return {
      kind: 'number',
      value: Number(ctx.NUMBER_LITERAL()!.getText()),
      source: makeSourceLocation(ctx, file),
    } satisfies NumberValue;
  }
  if (ctx.BOOLEAN_LITERAL()) {
    return {
      kind: 'bool',
      value: ctx.BOOLEAN_LITERAL()!.getText() === 'true',
      source: makeSourceLocation(ctx, file),
    } satisfies BoolValue;
  }
  if (ctx.NULL_LITERAL()) {
    return { kind: 'null', source: makeSourceLocation(ctx, file) } satisfies NullValue;
  }
  if (ctx.stringLiteralForm()) {
    return walkStringLiteralForm(ctx.stringLiteralForm()!, file);
  }
  return { kind: 'null', source: makeSourceLocation(ctx, file) } satisfies NullValue;
}

/**
 * Unescape a raw single-line `STRING_LITERAL` token (quotes included) using the same
 * rule as `walkStringLiteralForm`'s STRING_LITERAL branch. Used for scalar string
 * fields that hold a bare token (e.g. `version:`), so they escape-normalize
 * consistently with `description`/`tags` instead of keeping raw backslashes.
 */
function unescapeStringLiteral(raw: string): string {
  return raw.slice(1, -1).replace(/\\(.)/g, '$1');
}

function walkStringLiteralForm(ctx: StringLiteralFormContext, file: string): StringValue | TripleStringValue {
  if (ctx.STRING_LITERAL()) {
    const raw = ctx.STRING_LITERAL()!.getText();
    const value = raw.slice(1, -1).replace(/\\(.)/g, '$1');
    return { kind: 'string', value, source: makeSourceLocation(ctx, file) };
  }
  // Both a plain `"""…"""` and a `"""tag␊…"""` that lexed as TAGGED_BLOCK_LITERAL
  // (because its first line is a bare word, e.g. `"""Ne␊1 = Ano"""`) are read here
  // as a plain triple-string — the tag word is just text. Tag-peeling happens only
  // in `walkEmbeddedBlock` (sourceText / definitionSql).
  const triple = ctx.TRIPLE_STRING_LITERAL() ?? ctx.TAGGED_BLOCK_LITERAL();
  if (triple) {
    const dedented = dedent(triple.getText().slice(3, -3));
    return { kind: 'tripleString', value: dedented, source: makeSourceLocation(ctx, file) };
  }
  return { kind: 'string', value: '', source: makeSourceLocation(ctx, file) };
}

/**
 * `sourceText` / `definitionSql` values (embedded-sql DESIGN §2.2, §4). The
 * grammar routes these through `embeddedBlock`:
 *   - STRING / TRIPLE_STRING → unchanged plain-string behaviour (incl. C5's
 *     trailing newline — triple-strings keep it).
 *   - TAGGED_BLOCK → tag-peel → dedent → strip exactly one trailing newline,
 *     resolve the tag via TAG_REGISTRY into a `TaggedBlockValue`. An unknown tag
 *     emits a diagnostic and falls back to raw text (DESIGN §5).
 */
function walkEmbeddedBlock(
  ctx: EmbeddedBlockContext,
  file: string,
  errors: ParseError[],
): StringValue | TripleStringValue | TaggedBlockValue {
  if (ctx.STRING_LITERAL()) {
    const raw = ctx.STRING_LITERAL()!.getText();
    const value = raw.slice(1, -1).replace(/\\(.)/g, '$1');
    return { kind: 'string', value, source: makeSourceLocation(ctx, file) };
  }
  if (ctx.TRIPLE_STRING_LITERAL()) {
    const raw = ctx.TRIPLE_STRING_LITERAL()!.getText();
    return { kind: 'tripleString', value: dedent(raw.slice(3, -3)), source: makeSourceLocation(ctx, file) };
  }

  const node = ctx.TAGGED_BLOCK_LITERAL()!;
  const loc = makeSourceLocation(ctx, file);
  const inner = node.getText().slice(3, -3);
  // The lexer guarantees `<tag>[ \t]*\r?\n<body>`, so this always matches.
  const opener = /^([A-Za-z][A-Za-z0-9-]*)([ \t]*\r?\n)/.exec(inner)!;
  const tag = opener[1];
  const body = inner.slice(opener[0].length);
  const { value: dedented, indentWidth } = dedentWithIndent(body);
  const value = dedented.replace(/\r?\n$/, ''); // strip exactly one close-fence newline (§4 step 6)

  // The tag token sits immediately after the opening `"""`.
  const tagSource: SourceLocation = {
    file,
    line: loc.line,
    column: loc.column + 3,
    endLine: loc.line,
    endColumn: loc.column + 3 + tag.length,
    offsetStart: loc.offsetStart + 3,
    offsetEnd: loc.offsetStart + 3 + tag.length,
  };

  const entry = resolveTag(tag);
  if (!entry) {
    errors.push({
      code: DiagnosticCode.UnknownLanguageTag,
      message: `Unknown embedded-language tag '${tag}'`,
      severity: 'warning',
      source: tagSource,
    });
    // Stored as raw text (DESIGN §5) — keep the extracted value, drop analysis.
    return { kind: 'tripleString', value, source: loc };
  }

  // Body region in file coordinates; the per-line column shift is `indentWidth`
  // (DESIGN §8 uniform source map). Refined further in Phase 2.
  const valueSource: SourceLocation = {
    file,
    line: loc.line + 1,
    column: indentWidth,
    endLine: loc.endLine,
    endColumn: Math.max(indentWidth, loc.endColumn - 3),
    offsetStart: loc.offsetStart + 3 + opener[0].length,
    offsetEnd: loc.offsetEnd - 3,
  };

  return {
    kind: 'taggedBlock',
    tag,
    language: entry.language,
    dialect: entry.dialect,
    value,
    tagSource,
    valueSource,
    indentWidth,
    source: loc,
  };
}

/**
 * textwrap.dedent (contracts §2.9), additionally returning the common-prefix
 * length removed (= `indentWidth` for the embedded-SQL source map). Mirrors the
 * Kotlin `Dedent.applyTextwrapDedent` so the two parsers agree byte-for-byte.
 */
function dedentWithIndent(text: string): { value: string; indentWidth: number } {
  const withoutLeadingNewline = text.startsWith('\n') ? text.slice(1) : text;
  const lines = withoutLeadingNewline.split('\n');
  let commonPrefix: string | null = null;
  for (const line of lines) {
    if (line.trim().length === 0) continue;
    const leading = line.match(/^[ \t]*/)![0];
    commonPrefix = commonPrefix === null ? leading : longestCommonPrefix(commonPrefix, leading);
    if (commonPrefix.length === 0) break;
  }
  const prefix = commonPrefix ?? '';
  if (prefix.length === 0) return { value: withoutLeadingNewline, indentWidth: 0 };
  const value = lines
    .map((line) => {
      if (line.trim().length === 0) return line.replace(/\s+$/, '');
      return line.startsWith(prefix) ? line.slice(prefix.length) : line;
    })
    .join('\n');
  return { value, indentWidth: prefix.length };
}

function dedent(text: string): string {
  return dedentWithIndent(text).value;
}

function longestCommonPrefix(a: string, b: string): string {
  let i = 0;
  const limit = Math.min(a.length, b.length);
  while (i < limit && a[i] === b[i]) i++;
  return a.slice(0, i);
}

function walkId(ctx: IdContext, file: string): IdValue {
  const parts: string[] = [];
  for (const part of ctx.idPart()) {
    parts.push(part.getText());
  }
  const path = parts.join('.');
  return { kind: 'id', path, parts, source: makeSourceLocation(ctx, file) };
}

function walkList(ctx: ListContext, file: string): ListValue {
  const items: PropertyValue[] = [];
  for (const val of ctx.value()) {
    items.push(walkValue(val, file));
  }
  return { kind: 'list', items, source: makeSourceLocation(ctx, file) };
}

function walkObject(ctx: Object_Context, file: string): ObjectValue {
  const entries: ObjectEntry[] = [];
  const listCtx = ctx.propertyList();
  if (listCtx) {
    for (const entry of listCtx.propertyEntry()) {
      const keyCtx = entry.key();
      const key = keyCtx ? keyCtx.getText() : '';
      const entryValue = entry.value() ? walkValue(entry.value()!, file) : ({ kind: 'null', source: makeSourceLocation(entry, file) } satisfies PropertyValue);
      entries.push({ key, value: entryValue, source: makeSourceLocation(entry, file) });
    }
  }
  return { kind: 'object', entries, source: makeSourceLocation(ctx, file) };
}

function walkFunctionCall(ctx: FunctionCallContext, file: string): FunctionCallValue {
  const nameCtx = ctx.id();
  const name = nameCtx ? nameCtx.getText() : '';
  const args: PropertyValue[] = [];
  for (const val of ctx.value()) {
    args.push(walkValue(val, file));
  }
  return { kind: 'functionCall', name, args, source: makeSourceLocation(ctx, file) };
}

function walkListOfStrings(ctx: ListOfStringsContext, file: string): string[] {
  const result: string[] = [];
  for (const s of ctx.stringLiteralForm()) {
    const val = walkStringLiteralForm(s, file);
    if (val.kind === 'string') result.push(val.value);
    else if (val.kind === 'tripleString') result.push(val.value);
  }
  return result;
}

function walkListOfIds(ctx: ListOfIdsContext, _file: string): string[] {
  const result: string[] = [];
  for (const id of ctx.id()) {
    result.push(id.getText());
  }
  return result;
}

/**
 * `primaryKey` accepts a quoted-string list (legacy), a bare-id list, or a
 * single bare id (`primaryKey: i_item_sk`). All three collapse to the column-name
 * `string[]` — the AST doesn't distinguish the surface form (the formatter
 * re-emits the bare form when each name is a valid identifier).
 */
function walkPrimaryKeyValue(ctx: PrimaryKeyValueContext, file: string): string[] {
  if (ctx.listOfStrings()) return walkListOfStrings(ctx.listOfStrings()!, file);
  if (ctx.listOfIds()) return walkListOfIds(ctx.listOfIds()!, file);
  if (ctx.id()) return [ctx.id()!.getText()];
  return [];
}

// ============================================================================
// Per-kind walker functions: db kinds
// ============================================================================

function walkProjectDef(
  ctx: ProjectDefContext,
  name: string,
  source: SourceLocation,
  file: string
): ProjectDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let version: string | undefined;

  for (const p of ctx.projectProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.versionProperty()) {
      version = unescapeStringLiteral(p.versionProperty()!.STRING_LITERAL()!.getText());
    }
  }

  return { kind: 'project', name, source, description, descriptionLocalized, tags, version };
}

function walkTableDef(
  ctx: TableDefContext,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[]
): TableDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let primaryKey: string[] | undefined;
  let columns: ColumnDef[] | undefined;
  let indices: IndexDef[] | undefined;
  let constraints: ConstraintDef[] | undefined;
  let search: SearchBlock | undefined;
  let semantics: SemanticsBlock | undefined;
  let lexicon: LexiconBlock | undefined;
  let management: string | undefined;
  let changeSemantics: ChangeSemanticsDecl | undefined;
  let writeback: WritebackReservation | undefined;

  for (const p of ctx.tableProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.primaryKeyProperty()) {
      primaryKey = walkPrimaryKeyValue(p.primaryKeyProperty()!.primaryKeyValue()!, file);
    }
    if (p.columnsProperty()) {
      columns = walkColumnDefList(p.columnsProperty()!.columnDefList()!, file, errors);
    }
    if (p.indicesProperty()) {
      indices = walkIndexDefList(p.indicesProperty()!.indexDefList()!, file);
    }
    if (p.constraintsProperty()) {
      constraints = walkConstraintDefList(p.constraintsProperty()!.constraintDefList()!, file);
    }
    if (p.searchBlockProperty()) {
      search = walkSearchBlock(p.searchBlockProperty()!.searchBlock()!, file);
    }
    if (p.semanticsBlockProperty()) {
      semantics = walkSemanticsBlock(p.semanticsBlockProperty()!, file, errors);
    }
    if (p.lexiconBlockProperty()) {
      lexicon = walkLexiconBlock(p.lexiconBlockProperty()!, file);
    }
    // EN-P1 (0.10) — entry declarations (write-behaviour axis); vocabulary is semantic.
    if (p.managementProperty()) {
      management = p.managementProperty()!.id().getText();
    }
    if (p.changeSemanticsProperty()) {
      changeSemantics = walkChangeSemantics(p.changeSemanticsProperty()!, file);
    }
    if (p.writebackReservationProperty()) {
      writeback = walkWriteback(p.writebackReservationProperty()!, file);
    }
  }

  return { kind: 'table', name, source, description, descriptionLocalized, tags, primaryKey, columns, indices, constraints, search, semantics, lexicon, management, changeSemantics, writeback };
}

/** EN-P1 (0.10) — `changeSemantics: <mode> { <role>: <column> }`. Mode + roles stay opaque here. */
function walkChangeSemantics(ctx: ChangeSemanticsPropertyContext, file: string): ChangeSemanticsDecl {
  const ids = ctx.id();
  const mode = (Array.isArray(ids) ? ids[0] : ids).getText();
  const roles: Record<string, string> = {};
  const roleMap = ctx.changeRoleMap();
  if (roleMap) {
    for (const entry of roleMap.changeRoleEntry()) {
      const entryIds = entry.id();
      const role = entryIds[0].getText();
      const column = entryIds[1].getText();
      if (role) roles[role] = column;
    }
  }
  return { mode, roles, source: makeSourceLocation(ctx, file) };
}

/** EN-P1 (0.10) — the Q-8 `writeback { … }` reservation. Parses inert (the semantics-block scalar shape). */
function walkWriteback(ctx: WritebackReservationPropertyContext, file: string): WritebackReservation {
  const entries: Record<string, SemanticsValue> = {};
  const listCtx = ctx.object_()!.propertyList();
  if (listCtx) {
    for (const entry of listCtx.propertyEntry()) {
      const keyCtx = entry.key();
      const key = keyCtx ? keyCtx.getText() : '';
      if (!key) continue;
      const valueCtx = entry.value();
      const scalar = valueCtx ? semanticsScalar(valueCtx, file) : null;
      if (scalar === NON_SCALAR) continue;
      entries[key] = scalar;
    }
  }
  return { entries, source: makeSourceLocation(ctx.object_()!, file) };
}

function walkViewDef(
  ctx: ViewDefContext,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[]
): ViewDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let columns: ColumnDef[] | undefined;
  let definitionSql: StringValue | TripleStringValue | TaggedBlockValue | undefined;
  let search: SearchBlock | undefined;

  for (const p of ctx.viewProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.columnsProperty()) {
      columns = walkColumnDefList(p.columnsProperty()!.columnDefList()!, file, errors);
    }
    if (p.definitionSqlProperty()) {
      definitionSql = walkEmbeddedBlock(p.definitionSqlProperty()!.embeddedBlock()!, file, errors);
    }
    if (p.searchBlockProperty()) {
      search = walkSearchBlock(p.searchBlockProperty()!.searchBlock()!, file);
    }
  }

  return { kind: 'view', name, source, description, descriptionLocalized, tags, columns, definitionSql, search };
}

/** Grammar 0.14 — `def schema <name> { description?, tags?, columns: [...] }` (a named row schema). */
function walkSchemaDef(
  ctx: SchemaDefContext,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[]
): SchemaDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let columns: ColumnDef[] | undefined;

  for (const p of ctx.schemaProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.columnsProperty()) {
      columns = walkColumnDefList(p.columnsProperty()!.columnDefList()!, file, errors);
    }
  }

  return { kind: 'schema', name, source, description, descriptionLocalized, tags, columns };
}

function walkColumnDef(
  ctx: ColumnDefContext,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[]
): ColumnDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let type: DataType | undefined;
  let optional: boolean | undefined;
  let isKey: boolean | undefined;
  let indexed: boolean | undefined;
  let search: SearchBlock | undefined;
  let semantics: SemanticsBlock | undefined;
  let lexicon: LexiconBlock | undefined;

  for (const p of ctx.columnProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.typeProperty()) {
      type = walkDataType(p.typeProperty()!.dataType()!, file);
    }
    if (p.optionalProperty()) {
      optional = p.optionalProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
    }
    if (p.isKeyProperty()) {
      isKey = p.isKeyProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
    }
    if (p.indexedProperty()) {
      indexed = p.indexedProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
    }
    if (p.searchBlockProperty()) {
      search = walkSearchBlock(p.searchBlockProperty()!.searchBlock()!, file);
    }
    if (p.semanticsBlockProperty()) {
      semantics = walkSemanticsBlock(p.semanticsBlockProperty()!, file, errors);
    }
    if (p.lexiconBlockProperty()) {
      lexicon = walkLexiconBlock(p.lexiconBlockProperty()!, file);
    }
  }

  return { kind: 'column', name, source, description, descriptionLocalized, tags, type, optional, isKey, indexed, search, semantics, lexicon };
}

function walkIndexDef(
  ctx: IndexDefContext,
  name: string,
  source: SourceLocation,
  file: string
): IndexDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let indexType: IndexType | undefined;
  let columns: string[] | undefined;

  for (const p of ctx.indexProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.indexTypeProperty()) {
      const v = p.indexTypeProperty()!.indexTypeValue();
      if (v.PRIMARY()) indexType = 'primary';
      else if (v.SECONDARY()) indexType = 'secondary';
      else if (v.ORDERED()) indexType = 'ordered';
      else if (v.BTREE()) indexType = 'btree';
      else if (v.FULLTEXT()) indexType = 'fulltext';
    }
    if (p.columnNamesListProperty()) {
      columns = walkListOfStrings(p.columnNamesListProperty()!.listOfStrings()!, file);
    }
  }

  return { kind: 'index', name, source, description, descriptionLocalized, indexType, columns };
}

function walkConstraintDef(
  ctx: ConstraintDefContext,
  name: string,
  source: SourceLocation,
  file: string
): ConstraintDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let constraintType: ConstraintType | undefined;
  let columns: string[] | undefined;

  for (const p of ctx.constraintProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.constraintTypeProperty()) {
      const v = p.constraintTypeProperty()!.constraintTypeValue();
      if (v.UNIQUE()) constraintType = 'unique';
      else if (v.NOT_NULL()) constraintType = 'notNull';
    }
    if (p.columnNamesListProperty()) {
      columns = walkListOfStrings(p.columnNamesListProperty()!.listOfStrings()!, file);
    }
  }

  return { kind: 'constraint', name, source, description, descriptionLocalized, constraintType, columns };
}

function walkFkDef(
  ctx: FkDefContext,
  name: string,
  source: SourceLocation,
  file: string
): FkDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let from: PropertyValue | undefined;
  let to: PropertyValue | undefined;

  for (const p of ctx.fkProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.fromProperty()?.value()) {
      from = walkValue(p.fromProperty()!.value()!, file);
    }
    if (p.toProperty()?.value()) {
      to = walkValue(p.toProperty()!.value()!, file);
    }
  }

  return { kind: 'fk', name, source, description, descriptionLocalized, tags, from, to };
}

function walkProcedureDef(
  ctx: ProcedureDefContext,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[]
): ProcedureDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let parameters: ParameterDef[] | undefined;
  let resultColumns: ColumnDef[] | undefined;

  for (const p of ctx.procedureProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.parametersProperty()) {
      parameters = walkParameterDefList(p.parametersProperty()!.parameterDefList()!, file);
    }
    if (p.resultColumnsProperty()) {
      resultColumns = walkColumnDefList(p.resultColumnsProperty()!.columnDefList()!, file, errors);
    }
  }

  return { kind: 'procedure', name, source, description, descriptionLocalized, tags, parameters, resultColumns };
}

// ============================================================================
// Per-kind walker functions: er kinds
// ============================================================================

function walkEntityDef(
  ctx: EntityDefContext,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[]
): EntityDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let labelPlural: string | undefined;
  let nameAttribute: Reference | undefined;
  let codeAttribute: Reference | undefined;
  let aliases: string[] | undefined;
  let attributes: AttributeDef[] | undefined;
  let roles: string[] | undefined;
  let displayLabel: LocalizedString | undefined;
  let search: SearchBlock | undefined;
  let semantics: SemanticsBlock | undefined;
  let lexicon: LexiconBlock | undefined;
  let binding: BindingProperty | undefined;
  let concept: ConceptSugar | undefined;

  for (const p of ctx.entityProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.labelPluralProperty()) {
      labelPlural = p.labelPluralProperty()!.STRING_LITERAL()!.getText().slice(1, -1);
    }
    if (p.nameAttributeProperty()?.id()) {
      const idCtx = p.nameAttributeProperty()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      nameAttribute = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
    if (p.codeAttributeProperty()?.id()) {
      const idCtx = p.codeAttributeProperty()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      codeAttribute = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
    if (p.aliasesProperty()) {
      aliases = walkListOfStrings(p.aliasesProperty()!.listOfStrings()!, file);
    }
    if (p.attributesProperty()) {
      attributes = walkAttributeDefList(p.attributesProperty()!.attributeDefList()!, file, errors);
    }
    if (p.rolesProperty()) {
      roles = walkListOfIds(p.rolesProperty()!.listOfIds()!, file);
    }
    if (p.displayLabelProperty()) {
      displayLabel = walkLocalizedString(p.displayLabelProperty()!.localizedString()!, file);
    }
    if (p.searchBlockProperty()) {
      search = walkSearchBlock(p.searchBlockProperty()!.searchBlock()!, file);
    }
    if (p.semanticsBlockProperty()) {
      semantics = walkSemanticsBlock(p.semanticsBlockProperty()!, file, errors);
    }
    if (p.lexiconBlockProperty()) {
      lexicon = walkLexiconBlock(p.lexiconBlockProperty()!, file);
    }
    if (p.bindingProperty()) {
      binding = walkBindingProperty(p.bindingProperty()!, file);
    }
    if (p.conceptSugarProperty()) {
      concept = conceptSugarOnce(concept, p.conceptSugarProperty()!, file, errors); // 0.17
    }
  }

  return { kind: 'entity', name, source, description, descriptionLocalized, tags, labelPlural, nameAttribute, codeAttribute, aliases, attributes, roles, displayLabel, search, semantics, lexicon, binding, ...(concept ? { concept } : {}) };
}

/**
 * Walk a list of `attributeProperty` into a typed AttributeDef. Shared by the
 * standalone `def attribute` walk and the inline-in-entity/dimension walk so the
 * ER and MD (`domain:`/`aggregation:`) fields are handled in exactly one place.
 * Both schemas' fields are accepted here; per-schema validity is semantic (1C
 * stays mechanical — the parser never branches on schema).
 */
function walkAttributeProperties(
  props: AttributePropertyContext[],
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[]
): AttributeDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let type: DataType | undefined;
  let isKey: boolean | undefined;
  let optional: boolean | undefined;
  let valueLabels: ValueLabels | undefined;
  let displayLabel: LocalizedString | undefined;
  let search: SearchBlock | undefined;
  let semantics: SemanticsBlock | undefined;
  let lexicon: LexiconBlock | undefined;
  let binding: BindingProperty | undefined;
  let domainRef: string | undefined;
  let aggregation: AggregationSpec | undefined;
  let states: string[] | undefined;
  let transitions: AttributeTransition[] | undefined;
  let statesNode: AttributeDef['statesNode'];
  let transitionsNode: AttributeDef['transitionsNode'];
  let concept: ConceptSugar | undefined;
  const crossRefs: CrossRef[] = [];

  for (const p of props) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.typeProperty()) {
      type = walkDataType(p.typeProperty()!.dataType()!, file);
    }
    if (p.isKeyProperty()) {
      isKey = p.isKeyProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
    }
    if (p.optionalProperty()) {
      optional = p.optionalProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
    }
    if (p.valueLabelsProperty()) {
      valueLabels = walkValueLabels(p.valueLabelsProperty()!.valueLabelsBody()!, file);
    }
    if (p.displayLabelProperty()) {
      displayLabel = walkLocalizedString(p.displayLabelProperty()!.localizedString()!, file);
    }
    if (p.searchBlockProperty()) {
      search = walkSearchBlock(p.searchBlockProperty()!.searchBlock()!, file);
    }
    if (p.semanticsBlockProperty()) {
      semantics = walkSemanticsBlock(p.semanticsBlockProperty()!, file, errors);
    }
    if (p.lexiconBlockProperty()) {
      lexicon = walkLexiconBlock(p.lexiconBlockProperty()!, file);
    }
    if (p.bindingProperty()) {
      binding = walkBindingProperty(p.bindingProperty()!, file);
    }
    if (p.domainRefProperty()) {
      const idCtx = p.domainRefProperty()!.id()!;
      domainRef = idCtx.getText();
      crossRefs.push({ role: 'domain', path: domainRef, source: makeSourceLocation(idCtx, file) });
    }
    if (p.aggregationProperty()) {
      aggregation = walkAggregationValue(p.aggregationProperty()!.aggregationValue()!, file);
    }
    // 0.16 — lifecycle states (D-67).
    if (p.statesProperty()) {
      const list = p.statesProperty()!.listOfIds();
      if (list) states = walkListOfIds(list, file);
      statesNode = { source: makeSourceLocation(p.statesProperty()!, file) };
    }
    if (p.transitionsProperty()) {
      transitions = p.transitionsProperty()!.transitionEntry().flatMap((e) =>
        e.id() && e.listOfIds()
          ? [{ from: e.id().getText(), to: walkListOfIds(e.listOfIds()!, file), source: makeSourceLocation(e, file) }]
          : [],
      );
      transitionsNode = { source: makeSourceLocation(p.transitionsProperty()!, file) };
    }
    if (p.conceptSugarProperty()) concept = conceptSugarOnce(concept, p.conceptSugarProperty()!, file, errors); // 0.17
  }

  return {
    kind: 'attribute', name, source, description, descriptionLocalized, tags, type, isKey, optional, valueLabels, displayLabel, search, semantics, lexicon, binding, domainRef, aggregation,
    crossRefs: crossRefs.length ? crossRefs : undefined,
    ...(states ? { states, statesNode } : {}),
    ...(transitions ? { transitions, transitionsNode } : {}),
    ...(concept ? { concept } : {}),
  };
}

function walkAttributeDef(
  ctx: AttributeDefContext,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[]
): AttributeDef {
  return walkAttributeProperties(ctx.attributeProperty(), name, source, file, errors);
}

function walkRelationDef(
  ctx: RelationDefContext,
  name: string,
  source: SourceLocation,
  file: string
): RelationDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let from: PropertyValue | undefined;
  let to: PropertyValue | undefined;
  let cardinality: ObjectValue | undefined;
  let join: ListValue | undefined;
  let search: SearchBlock | undefined;
  let binding: BindingProperty | undefined;

  for (const p of ctx.relationProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.fromProperty()?.value()) {
      from = walkValue(p.fromProperty()!.value()!, file);
    }
    if (p.toProperty()?.value()) {
      to = walkValue(p.toProperty()!.value()!, file);
    }
    if (p.cardinalityProperty()?.object_()) {
      cardinality = walkObject(p.cardinalityProperty()!.object_()!, file);
    }
    if (p.joinProperty()?.list()) {
      join = walkList(p.joinProperty()!.list()!, file);
    }
    if (p.searchBlockProperty()) {
      search = walkSearchBlock(p.searchBlockProperty()!.searchBlock()!, file);
    }
    if (p.bindingProperty()) {
      binding = walkBindingProperty(p.bindingProperty()!, file);
    }
  }

  return { kind: 'relation', name, source, description, descriptionLocalized, tags, from, to, cardinality, join, search, binding };
}

// ============================================================================
// Per-kind walker functions: map kinds
// ============================================================================

function walkEr2dbEntityDef(
  ctx: Er2dbEntityDefContext,
  name: string,
  source: SourceLocation,
  file: string
): Er2dbEntityDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let entity: Reference | undefined;
  let target: ObjectValue | Reference | undefined;
  let whereFilter: ObjectValue | undefined;

  for (const p of ctx.er2dbEntityProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.entityProperty_()?.id()) {
      const idCtx = p.entityProperty_()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      entity = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
    if (p.targetProperty()) {
      target = walkTargetValue(p.targetProperty()!, file);
    }
    if (p.whereFilterProperty()?.object_()) {
      whereFilter = walkObject(p.whereFilterProperty()!.object_()!, file);
    }
  }

  return { kind: 'er2dbEntity', name, source, description, descriptionLocalized, tags, entity, target, whereFilter };
}

function walkEr2dbAttributeDef(
  ctx: Er2dbAttributeDefContext,
  name: string,
  source: SourceLocation,
  file: string
): Er2dbAttributeDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let attribute: Reference | undefined;
  let target: ObjectValue | Reference | undefined;

  for (const p of ctx.er2dbAttributeProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.attributeProperty_()?.id()) {
      const idCtx = p.attributeProperty_()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      attribute = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
    if (p.targetProperty()) {
      target = walkTargetValue(p.targetProperty()!, file);
    }
  }

  return { kind: 'er2dbAttribute', name, source, description, descriptionLocalized, tags, attribute, target };
}

function walkEr2dbRelationDef(
  ctx: Er2dbRelationDefContext,
  name: string,
  source: SourceLocation,
  file: string
): Er2dbRelationDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let relation: Reference | undefined;
  let fk: Reference | undefined;

  for (const p of ctx.er2dbRelationProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.relationProperty_()?.id()) {
      const idCtx = p.relationProperty_()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      relation = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
    if (p.fkProperty_()?.id()) {
      const idCtx = p.fkProperty_()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      fk = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
  }

  return { kind: 'er2dbRelation', name, source, description, descriptionLocalized, tags, relation, fk };
}

// ============================================================================
// Per-kind walker functions: query and cnc kinds
// ============================================================================

function walkQueryDef(
  ctx: QueryDefContext,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[]
): QueryDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let language: QueryLanguage | undefined;
  let parameters: ParameterDef[] | undefined;
  let sourceText: StringValue | TripleStringValue | TaggedBlockValue | undefined;
  let search: SearchBlock | undefined;

  for (const p of ctx.queryProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.languageProperty()) {
      const v = p.languageProperty()!.languageValue();
      if (v.SQL()) language = 'SQL';
      else if (v.TRANSFORMATION_DSL()) language = 'TRANSFORMATION_DSL';
      else if (v.DATAFRAME_DSL()) language = 'DATAFRAME_DSL';
      else if (v.REL_NODE()) language = 'REL_NODE';
    }
    if (p.parametersProperty()) {
      parameters = walkParameterDefList(p.parametersProperty()!.parameterDefList()!, file);
    }
    if (p.sourceTextProperty()) {
      sourceText = walkEmbeddedBlock(p.sourceTextProperty()!.embeddedBlock()!, file, errors);
    }
    if (p.searchBlockProperty()) {
      search = walkSearchBlock(p.searchBlockProperty()!.searchBlock()!, file);
    }
  }

  // `language:` is inferred from the tag and soft-deprecated when a tagged block
  // is present (DESIGN §6); a value disagreeing with the tag is an error.
  if (sourceText?.kind === 'taggedBlock' && language !== undefined) {
    if (language !== sourceText.language) {
      errors.push({
        code: DiagnosticCode.LanguageTagMismatch,
        message: `'language: ${language}' disagrees with the block tag '${sourceText.tag}' (${sourceText.language})`,
        severity: 'error',
        source: sourceText.tagSource,
      });
    }
    errors.push({
      code: DiagnosticCode.DeprecatedLanguageProperty,
      message: `'language' on query is deprecated; it is inferred from the '${sourceText.tag}' block tag`,
      severity: 'warning',
      source: sourceText.tagSource,
    });
  }

  return { kind: 'query', name, source, description, descriptionLocalized, tags, language, parameters, sourceText, search };
}

function walkRoleDef(
  ctx: RoleDefContext,
  name: string,
  source: SourceLocation,
  file: string,
  errors: ParseError[],
  modelCode?: string,
): RoleDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let label: LocalizedString | undefined;
  let search: SearchBlock | undefined;
  const entries: AutoEntry[] = [];

  for (const p of ctx.roleProperty()) {
    // 0.17 (⚑CN-P6) — the cn role's properties ride the one `roleProperty` production; a `model cn`
    // role is walked as a concept (walkDefinition), so here they are rejected: a cnc / automation role
    // keeps its 0.16 surface and a typo (`lable:`) is still a parse error (review-114 M5).
    const cnOnly = p.cnEntry() ?? p.cnRefProperty() ?? p.groundsProperty() ?? p.lexiconBlockProperty();
    if (cnOnly) {
      const key = p.cnEntry()?.cnKey().getText() ?? cnOnly.getChild(0)?.getText() ?? '';
      errors.push({
        code: DiagnosticCode.UnknownProperty,
        message: `unknown role property '${key}'${modelCode ? ` in model ${modelCode}` : ''} — only a role in a 'model cn' file takes the concept properties`,
        severity: 'error',
        source: makeSourceLocation(cnOnly, file),
      });
      continue;
    }
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.labelProperty()) {
      label = walkLocalizedString(p.labelProperty()!.localizedString()!, file);
    }
    if (p.searchBlockProperty()) {
      search = walkSearchBlock(p.searchBlockProperty()!.searchBlock()!, file);
    }
    // 0.16 — an automation role's typed properties (resolve / delivery / members), walked into the
    // one ordered entry list (review-114 M5: typed, so a typo stays a parse error).
    const roleProp = p.resolveProperty() ?? p.deliveryProperty() ?? p.membersProperty();
    const roleValue = roleProp?.autoValue();
    if (roleProp && roleValue) {
      const key = p.resolveProperty() ? 'resolve' : p.deliveryProperty() ? 'delivery' : 'members';
      entries.push({ key, value: walkAutoValue(roleValue, file), source: makeSourceLocation(roleProp, file) });
    }
  }

  return { kind: 'role', name, source, description, descriptionLocalized, tags, label, search, ...(entries.length ? { entries } : {}) };
}

function walkEr2cncRoleDef(
  ctx: Er2cncRoleDefContext,
  name: string,
  source: SourceLocation,
  file: string
): Er2cncRoleDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let entity: Reference | undefined;
  let role: Reference | undefined;

  for (const p of ctx.er2cncRoleProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.entityProperty_()?.id()) {
      const idCtx = p.entityProperty_()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      entity = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
    if (p.roleProperty_()?.id()) {
      const idCtx = p.roleProperty_()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      role = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
  }

  return { kind: 'er2cncRole', name, source, description, descriptionLocalized, tags, entity, role };
}

function walkDrillMapDef(
  ctx: DrillMapDefContext,
  name: string,
  source: SourceLocation,
  file: string
): DrillMapDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let from: Reference | undefined;
  let to: Reference | undefined;
  let args: DrillArgEntry[] = [];
  let display: LocalizedString | undefined;
  let overrideAuto: boolean | undefined;

  for (const p of ctx.drillMapProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) {
      tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    }
    if (p.fromProperty()?.value()?.id()) {
      const idCtx = p.fromProperty()!.value()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      from = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
    if (p.toProperty()?.value()?.id()) {
      const idCtx = p.toProperty()!.value()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      to = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
    if (p.argsProperty()) {
      args = walkDrillArgsMap(p.argsProperty()!.drillArgsMap()!, file);
    }
    if (p.displayProperty()) {
      display = walkLocalizedString(p.displayProperty()!.localizedString()!, file);
    }
    if (p.overrideProperty()?.BOOLEAN_LITERAL()) {
      overrideAuto = p.overrideProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
    }
  }

  return {
    kind: 'drillMap',
    name,
    source,
    description,
    descriptionLocalized,
    tags,
    from,
    to,
    args,
    display,
    overrideAuto,
  };
}

function walkDrillArgsMap(ctx: DrillArgsMapContext, file: string): DrillArgEntry[] {
  const result: DrillArgEntry[] = [];
  for (const entry of ctx.drillArgEntry()) {
    const idCtx = entry.id();
    if (!idCtx) continue;
    const argName = idCtx.idPart().map((pt) => pt.getText()).join('.');
    const value = walkStringLiteralForm(entry.stringLiteralForm()!, file);
    result.push({
      name: argName,
      value,
      source: makeSourceLocation(entry, file),
    });
  }
  return result;
}

// ============================================================================
// Inline def lists
// ============================================================================

function walkColumnDefList(ctx: ColumnDefListContext, file: string, errors: ParseError[]): ColumnDef[] {
  const result: ColumnDef[] = [];
  for (const inline of ctx.columnInline()) {
    const nameCtx = inline.id();
    const name = nameCtx ? nameCtx.getText() : '';
    const inlineCtx = inline.columnDef();
    let description: StringValue | TripleStringValue | undefined;
    let descriptionLocalized: LocalizedString | undefined;
    let tags: string[] | undefined;
    let type: DataType | undefined;
    let optional: boolean | undefined;
    let isKey: boolean | undefined;
    let indexed: boolean | undefined;
    let search: SearchBlock | undefined;
    let semantics: SemanticsBlock | undefined;
  let lexicon: LexiconBlock | undefined;

    for (const p of inlineCtx.columnProperty()) {
      if (p.descriptionProperty()) {
        description = descriptionOf(p.descriptionProperty()!, file);
        descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
      }
      if (p.tagsProperty()) {
        tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
      }
      if (p.typeProperty()) {
        type = walkDataType(p.typeProperty()!.dataType()!, file);
      }
      if (p.optionalProperty()) {
        optional = p.optionalProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
      }
      if (p.isKeyProperty()) {
        isKey = p.isKeyProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
      }
      if (p.indexedProperty()) {
        indexed = p.indexedProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
      }
      if (p.searchBlockProperty()) {
        search = walkSearchBlock(p.searchBlockProperty()!.searchBlock()!, file);
      }
      if (p.semanticsBlockProperty()) {
        semantics = walkSemanticsBlock(p.semanticsBlockProperty()!, file, errors);
    }
    if (p.lexiconBlockProperty()) {
      lexicon = walkLexiconBlock(p.lexiconBlockProperty()!, file);
      }
    }

    result.push({ kind: 'column', name, source: makeSourceLocation(inline, file), description, descriptionLocalized, tags, type, optional, isKey, indexed, search, semantics, lexicon });
  }
  return result;
}

function walkIndexDefList(ctx: IndexDefListContext, file: string): IndexDef[] {
  const result: IndexDef[] = [];
  for (const inline of ctx.indexInline()) {
    const nameCtx = inline.id();
    const name = nameCtx ? nameCtx.getText() : '';
    const inlineCtx = inline.indexDef();
    let description: StringValue | TripleStringValue | undefined;
    let descriptionLocalized: LocalizedString | undefined;
    let indexType: IndexType | undefined;
    let columns: string[] | undefined;

    for (const p of inlineCtx.indexProperty()) {
      if (p.descriptionProperty()) {
        description = descriptionOf(p.descriptionProperty()!, file);
        descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
      }
      if (p.indexTypeProperty()) {
        const v = p.indexTypeProperty()!.indexTypeValue();
        if (v.PRIMARY()) indexType = 'primary';
        else if (v.SECONDARY()) indexType = 'secondary';
        else if (v.ORDERED()) indexType = 'ordered';
        else if (v.BTREE()) indexType = 'btree';
        else if (v.FULLTEXT()) indexType = 'fulltext';
      }
      if (p.columnNamesListProperty()) {
        columns = walkListOfStrings(p.columnNamesListProperty()!.listOfStrings()!, file);
      }
    }

    result.push({ kind: 'index', name, source: makeSourceLocation(inline, file), description, descriptionLocalized, indexType, columns });
  }
  return result;
}

function walkConstraintDefList(ctx: ConstraintDefListContext, file: string): ConstraintDef[] {
  const result: ConstraintDef[] = [];
  for (const inline of ctx.constraintInline()) {
    const nameCtx = inline.id();
    const name = nameCtx ? nameCtx.getText() : '';
    const inlineCtx = inline.constraintDef();
    let description: StringValue | TripleStringValue | undefined;
    let descriptionLocalized: LocalizedString | undefined;
    let constraintType: ConstraintType | undefined;
    let columns: string[] | undefined;

    for (const p of inlineCtx.constraintProperty()) {
      if (p.descriptionProperty()) {
        description = descriptionOf(p.descriptionProperty()!, file);
        descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
      }
      if (p.constraintTypeProperty()) {
        const v = p.constraintTypeProperty()!.constraintTypeValue();
        if (v.UNIQUE()) constraintType = 'unique';
        else if (v.NOT_NULL()) constraintType = 'notNull';
      }
      if (p.columnNamesListProperty()) {
        columns = walkListOfStrings(p.columnNamesListProperty()!.listOfStrings()!, file);
      }
    }

    result.push({ kind: 'constraint', name, source: makeSourceLocation(inline, file), description, descriptionLocalized, constraintType, columns });
  }
  return result;
}

function walkAttributeDefList(ctx: AttributeDefListContext, file: string, errors: ParseError[]): AttributeDef[] {
  const result: AttributeDef[] = [];
  for (const inline of ctx.attributeInline()) {
    const nameCtx = inline.id();
    const name = nameCtx ? nameCtx.getText() : '';
    const props = inline.attributeDef().attributeProperty();
    result.push(walkAttributeProperties(props, name, makeSourceLocation(inline, file), file, errors));
  }
  return result;
}

function walkParameterDefList(ctx: ParameterDefListContext, file: string): ParameterDef[] {
  const result: ParameterDef[] = [];
  for (const inline of ctx.parameterInline()) {
    let name = '';
    let type: DataType | undefined;
    let label: string | undefined;
    let direction: ParameterDirection | undefined;

    for (const p of inline.paramProperty()) {
      if (p.nameProperty()?.id()) {
        name = p.nameProperty()!.id()!.getText();
      }
      if (p.typeProperty()) {
        type = walkDataType(p.typeProperty()!.dataType()!, file);
      }
      if (p.paramLabelProperty()) {
        label = p.paramLabelProperty()!.stringLiteralForm()!.getText().slice(1, -1);
      }
      if (p.directionProperty()?.id()) {
        const d = p.directionProperty()!.id()!.getText().toUpperCase();
        if (d === 'IN' || d === 'OUT' || d === 'INOUT') {
          direction = d;
        }
      }
    }

    result.push({ name, type, label, direction, source: makeSourceLocation(inline, file) });
  }
  return result;
}

// ============================================================================
// dataType and reference detection
// ============================================================================

function walkDataType(ctx: DataTypeContext, file: string): DataType {
  if (ctx.typeValue()) {
    return { kind: 'simple', name: ctx.typeValue()!.getText(), source: makeSourceLocation(ctx, file) };
  }
  let typeName: string | undefined;
  let length: number | undefined;
  let precision: number | undefined;

  for (const p of ctx.dataTypeProperty()) {
    if (p.DATA_TYPE()) {
      typeName = p.typeValue()?.getText();
    }
    if (p.LENGTH()) {
      const lit = p.NUMBER_LITERAL();
      if (lit) length = Number(lit.getText());
    }
    if (p.PRECISION()) {
      const lit = p.NUMBER_LITERAL();
      if (lit) precision = Number(lit.getText());
    }
  }

  if (!typeName) typeName = '';
  return { kind: 'structured', typeName, length, precision, source: makeSourceLocation(ctx, file) };
}

export function extractReference(value: PropertyValue): Reference | null {
  if (value.kind === 'id') {
    return { path: value.path, parts: value.parts, source: value.source };
  }
  return null;
}

// ============================================================================
// LocalizedString and related
// ============================================================================

function walkLocalizedString(ctx: LocalizedStringContext, file: string): LocalizedString {
  const entries: Record<string, string> = {};
  for (const entry of ctx.localizedEntry()) {
    const key = entry.id().getText();
    const val = walkStringLiteralForm(entry.stringLiteralForm()!, file);
    entries[key] = val.value;
  }
  return { kind: 'localizedString', entries, source: makeSourceLocation(ctx, file) };
}

/**
 * 0.13 (NLS-P10 / ⚑GXP-D7) — `description:` has two legal shapes and the walker
 * keeps them apart: the plain form feeds `description`, the map form feeds
 * `descriptionLocalized`, and exactly one of the two is ever set. Folding a map
 * down to one locale is a READER's decision (Veles' D7 fallback chain), never
 * the parser's — the parser stays mechanical.
 */
function descriptionOf(
  ctx: DescriptionPropertyContext,
  file: string,
): StringValue | TripleStringValue | undefined {
  const plain = ctx.stringLiteralForm();
  return plain ? walkStringLiteralForm(plain, file) : undefined;
}

function descriptionLocalizedOf(
  ctx: DescriptionPropertyContext,
  file: string,
): LocalizedString | undefined {
  const map = ctx.localizedString();
  return map ? walkLocalizedString(map, file) : undefined;
}

function walkLocalizedStringList(ctx: LocalizedStringListContext, file: string): LocalizedStringList {
  const entries: Record<string, string[]> = {};
  for (const entry of ctx.localizedStringListEntry()) {
    const key = entry.id().getText();
    const val = walkListOfStrings(entry.listOfStrings()!, file);
    entries[key] = val;
  }
  return { kind: 'localizedStringList', entries, source: makeSourceLocation(ctx, file) };
}

function walkSearchBlock(ctx: SearchBlockContext, file: string): SearchBlock {
  let keywords: LocalizedStringList | undefined;
  let patterns: string[] | undefined;
  let descriptions: LocalizedStringList | undefined;
  let examples: string[] | undefined;
  let aliases: string[] | undefined;
  let searchable: boolean | undefined;
  let fuzzy: boolean | undefined;
  let method: MatchMethod | undefined;
  const seen = new Map<string, number>();
  const bump = (k: string) => seen.set(k, (seen.get(k) ?? 0) + 1);

  for (const p of ctx.searchSubProperty()) {
    if (p.keywordsProperty()) {
      bump('keywords');
      keywords = walkLocalizedStringList(p.keywordsProperty()!.localizedStringList()!, file);
    }
    if (p.patternsProperty()) {
      bump('patterns');
      patterns = walkListOfStrings(p.patternsProperty()!.listOfStrings()!, file);
    }
    if (p.descriptionsProperty()) {
      bump('descriptions');
      descriptions = walkLocalizedStringList(p.descriptionsProperty()!.localizedStringList()!, file);
    }
    if (p.examplesProperty()) {
      bump('examples');
      examples = walkListOfStrings(p.examplesProperty()!.listOfStrings()!, file);
    }
    if (p.aliasesProperty()) {
      bump('aliases');
      aliases = walkListOfStrings(p.aliasesProperty()!.listOfStrings()!, file);
    }
    if (p.searchableProperty()) {
      bump('searchable');
      const sp = p.searchableProperty()!;
      // 0.12: the boolean is optional — bare `searchable` IS the inclusion marker.
      const bool = sp.BOOLEAN_LITERAL();
      searchable = bool === null ? true : bool.getText() === 'true';
      const attr = sp.matchMethodAttr();
      if (attr) method = walkMatchMethodValue(attr.matchMethodValue()!, file);
    }
    if (p.fuzzyProperty()) {
      bump('fuzzy');
      fuzzy = p.fuzzyProperty()!.BOOLEAN_LITERAL()!.getText() === 'true';
    }
  }

  const duplicateProperties = [...seen.entries()].filter(([, n]) => n > 1).map(([k]) => k);
  return { kind: 'searchBlock', keywords, patterns, descriptions, examples, aliases, searchable, fuzzy, method, duplicateProperties, source: makeSourceLocation(ctx, file) };
}

/**
 * Grammar 0.12 (RV-32) — `method: EXACT | TYPOS(2) | TOKENS`. Mechanical: the
 * identifier is captured as authored and the optional argument as a number.
 * Vocabulary/arity checks live in `@tatrman/semantics`.
 */
function walkMatchMethodValue(ctx: MatchMethodValueContext, file: string): MatchMethod {
  const num = ctx.NUMBER_LITERAL();
  return {
    kind: 'matchMethod',
    name: ctx.id().getText(),
    argument: num === null ? undefined : Number(num.getText()),
    source: makeSourceLocation(ctx, file),
  };
}

/**
 * Grounding Phase 1 (grammar 4.2) — fold a `semantics { … }` block's free-form
 * `object_` body into a flat `Record<string, SemanticsValue>`. The parser stays
 * mechanical: no vocabulary/shape checks (that is ttr-semantics' job). Values are
 * captured as raw scalars — ids as their identifier text, string literals
 * unquoted, numbers/booleans as JS primitives, `null` as `null`. A nested
 * functionCall value is rejected into a `ttr/semantics-non-scalar`
 * diagnostic (T2.4) and dropped so the validator's input stays flat. Duplicate
 * keys are last-wins and recorded in `duplicateProperties` (the search-block
 * bookkeeping pattern) — at every depth since MS, as a dotted/indexed path
 * (`measures[1].attribute`), because a nested object is now a shape an author
 * writes rather than one the walker throws away.
 */
function walkSemanticsBlock(ctx: SemanticsBlockPropertyContext, file: string, errors: ParseError[]): SemanticsBlock {
  const entries: Record<string, SemanticsValue> = {};
  const seen = new Map<string, number>();
  const nestedDuplicates: string[] = [];
  const listCtx = ctx.object_()!.propertyList();
  if (listCtx) {
    for (const entry of listCtx.propertyEntry()) {
      const keyCtx = entry.key();
      const key = keyCtx ? keyCtx.getText() : '';
      if (!key) continue;
      seen.set(key, (seen.get(key) ?? 0) + 1);
      const valueCtx = entry.value();
      const value = valueCtx ? semanticsStructured(valueCtx, file, key, nestedDuplicates) : null;
      if (value === NON_SCALAR) {
        errors.push({
          code: DiagnosticCode.SemanticsNonScalarValue,
          message: `semantics entries cannot be a function call; '${key}' has one`,
          severity: 'error',
          source: makeSourceLocation(valueCtx ?? entry, file),
        });
        continue;
      }
      entries[key] = value;
    }
  }
  const duplicateProperties = [
    ...[...seen.entries()].filter(([, n]) => n > 1).map(([k]) => k),
    ...nestedDuplicates,
  ];
  // Source spans the `{ … }` object (the SearchBlock convention), so the
  // formatter's verbatim slice re-emits `semantics: { … }` cleanly.
  return { kind: 'semanticsBlock', entries, duplicateProperties, source: makeSourceLocation(ctx.object_()!, file) };
}

/**
 * v4.4 — a canonical lexicon entry (`def term|pattern|example`). One shared body;
 * the `kind` tag comes from the caller. All entry props are optional in the
 * grammar (permissive superset) — per-kind required-field validity lives in
 * ttr-semantics, not here.
 */
function walkLexiconEntryDef(
  ctx: LexiconEntryDefContext,
  kind: 'term' | 'pattern' | 'example',
  name: string,
  source: SourceLocation,
  file: string
): LexiconEntryDef {
  let description: StringValue | TripleStringValue | undefined;
  let descriptionLocalized: LocalizedString | undefined;
  let tags: string[] | undefined;
  let target: Reference | undefined;
  let forms: string[] | undefined;
  let match: string | undefined;
  let text: string | undefined;
  let isDefault: boolean | undefined;
  let sensePriority: number | undefined;

  for (const p of ctx.lexiconEntryProperty()) {
    if (p.descriptionProperty()) {
      description = descriptionOf(p.descriptionProperty()!, file);
      descriptionLocalized = descriptionLocalizedOf(p.descriptionProperty()!, file);
    }
    if (p.tagsProperty()) tags = walkListOfStrings(p.tagsProperty()!.listOfStrings()!, file);
    if (p.forProperty()) {
      const idCtx = p.forProperty()!.id()!;
      const idVal = walkId(idCtx, file);
      target = { path: idVal.path, parts: idVal.parts, source: idVal.source };
    }
    if (p.formsProperty()) forms = walkListOfStrings(p.formsProperty()!.listOfStrings()!, file);
    if (p.matchProperty()) match = walkStringLiteralForm(p.matchProperty()!.stringLiteralForm()!, file).value;
    if (p.textProperty()) text = walkStringLiteralForm(p.textProperty()!.stringLiteralForm()!, file).value;
    // 0.17 — the cn default sense and its priority (cn contracts §1.5); their rules are semantic.
    const def = p.defaultProperty()?.BOOLEAN_LITERAL();
    if (def) isDefault = def.getText() === 'true';
    const priority = p.sensePriorityProperty()?.NUMBER_LITERAL();
    if (priority) sensePriority = Number(priority.getText());
  }

  return {
    kind, name, source, description, descriptionLocalized, tags, target, forms, match, text,
    ...(isDefault !== undefined ? { default: isDefault } : {}),
    ...(sensePriority !== undefined ? { sensePriority } : {}),
  };
}

/**
 * v4.4 — inline `lexicon { … }` sugar block. Free-form `object_` body (the
 * semantics-block precedent): retained verbatim as `object`, with the common
 * `terms`/`patterns`/`examples` list-of-string keys extracted for the desugarer.
 * The parser stays mechanical — target inference + desugar are ttr-semantics' job.
 */
function walkLexiconBlock(ctx: LexiconBlockPropertyContext, file: string): LexiconBlock {
  const object = walkObject(ctx.object_()!, file);
  const listKey = (key: string): string[] | undefined => {
    const entry = object.entries.find((e) => e.key === key);
    if (!entry || entry.value.kind !== 'list') return undefined;
    const out: string[] = [];
    for (const item of entry.value.items) {
      if (item.kind === 'string' || item.kind === 'tripleString') out.push(item.value);
    }
    return out;
  };
  return {
    kind: 'lexiconBlock',
    terms: listKey('terms'),
    patterns: listKey('patterns'),
    examples: listKey('examples'),
    object,
    // Span the `{ … }` object (the SearchBlock/SemanticsBlock convention) so the
    // formatter's verbatim slice re-emits `lexicon: { … }` cleanly.
    source: makeSourceLocation(ctx.object_()!, file),
  };
}

/** Sentinel distinguishing "rejected non-scalar" from a legitimate `null` value. */
const NON_SCALAR = Symbol('non-scalar');

/**
 * MS (vocabulary v3) — read a semantics `value` WITH its structure: scalars as before,
 * plus lists and nested objects carried verbatim.
 *
 * The parser stays mechanical. It does not know that `measures:` may hold a list while
 * `role:` may not — that is vocabulary knowledge and lives in ttr-semantics, which now
 * owns the "this key must be scalar" judgement that used to be made here. What survives
 * at this layer is the one case with no data meaning at all: a `functionCall`.
 *
 * Nesting recurses without a depth limit, mirroring the grammar (`value : … | list |
 * object_ | …` is self-referential). Duplicate keys inside a nested object are last-wins,
 * the same rule the block itself uses — and, like the block's own, they are RECORDED:
 * `path` carries the dotted/indexed route to this value and each repeat is appended to
 * `duplicates` as e.g. `measures[1].attribute`, which ttr-semantics reports through the
 * existing `SemDuplicateKey`. Counting repeats is bookkeeping, not vocabulary, so it
 * belongs here for the same reason the block-level count does.
 */
function semanticsStructured(
  ctx: ValueContext,
  file: string,
  path: string,
  duplicates: string[]
): SemanticsValue | typeof NON_SCALAR {
  const listCtx = ctx.list();
  if (listCtx) {
    const items: SemanticsValue[] = [];
    const values = listCtx.value();
    for (let i = 0; i < values.length; i++) {
      const walked = semanticsStructured(values[i], file, `${path}[${i}]`, duplicates);
      if (walked === NON_SCALAR) return NON_SCALAR;
      items.push(walked);
    }
    return items;
  }
  const objCtx = ctx.object_();
  if (objCtx) {
    const obj: Record<string, SemanticsValue> = {};
    const seen = new Map<string, number>();
    const propList = objCtx.propertyList();
    if (propList) {
      for (const entry of propList.propertyEntry()) {
        const keyCtx = entry.key();
        const key = keyCtx ? keyCtx.getText() : '';
        if (!key) continue;
        seen.set(key, (seen.get(key) ?? 0) + 1);
        const valueCtx = entry.value();
        const walked = valueCtx ? semanticsStructured(valueCtx, file, `${path}.${key}`, duplicates) : null;
        if (walked === NON_SCALAR) return NON_SCALAR;
        obj[key] = walked;
      }
    }
    for (const [k, n] of seen) if (n > 1) duplicates.push(`${path}.${k}`);
    return obj;
  }
  return semanticsScalar(ctx, file);
}

/**
 * Extract a scalar `SemanticsValue` from a `value` context, or the `NON_SCALAR`
 * sentinel for a nested object/list/functionCall. Ids collapse to their dotted
 * text (`period_table`, `AccountingPeriod`) — resolution is ttr-semantics' job,
 * so the parser keeps them opaque.
 *
 * Since MS this is the LEAF of `semanticsStructured`, which handles lists and objects
 * before delegating here — so its `NON_SCALAR` return now reaches a caller only for a
 * `functionCall`. It stays the walker for `writeback { … }`, whose Q-8 reservation is
 * inert and still flat.
 */
function semanticsScalar(ctx: ValueContext, file: string): SemanticsValue | typeof NON_SCALAR {
  if (ctx.id()) return ctx.id()!.getText();
  const lit = ctx.literal();
  if (lit) {
    const walked = walkLiteral(lit, file);
    if (walked.kind === 'number') return walked.value;
    if (walked.kind === 'bool') return walked.value;
    if (walked.kind === 'string' || walked.kind === 'tripleString') return walked.value;
    return null; // NullValue
  }
  return NON_SCALAR; // list | object_ | functionCall
}

function walkValueLabels(ctx: ValueLabelsBodyContext, file: string): ValueLabels {
  const entries: Array<{ key: string; label: LocalizedString; aliases?: string[]; source: SourceLocation }> = [];
  for (const entry of ctx.valueLabelEntry()) {
    const keyVal = entry.stringLiteralForm();
    const key = keyVal ? walkStringLiteralForm(keyVal, file).value : '';
    const { label, aliases } = walkValueLabelValue(entry.valueLabelValue()!, file);
    entries.push({ key, label, aliases, source: makeSourceLocation(entry, file) });
  }
  return { kind: 'valueLabels', entries, source: makeSourceLocation(ctx, file) };
}

/**
 * v4.4 S2 (A4-β) — a value-label value is EITHER the legacy localized label
 * (`{ cs: "…" }`) OR the widened `{ label: { … }, aliases: [ … ] }`. The widened
 * form is recognised by a `label:` field (localizedString) — then `aliases:` is
 * read too; otherwise every field is a locale→string entry (the legacy label).
 */
function walkValueLabelValue(ctx: ValueLabelValueContext, file: string): { label: LocalizedString; aliases?: string[] } {
  let labelBlock: LocalizedString | undefined;
  let aliases: string[] | undefined;
  const legacy: Record<string, string> = {};

  for (const f of ctx.valueLabelField()) {
    const fieldKey = f.id().getText();
    const ls = f.localizedString();
    const los = f.listOfStrings();
    const slf = f.stringLiteralForm();
    if (fieldKey === 'label' && ls) {
      labelBlock = walkLocalizedString(ls, file);
    } else if (fieldKey === 'aliases' && los) {
      aliases = walkListOfStrings(los, file);
    } else if (slf) {
      legacy[fieldKey] = walkStringLiteralForm(slf, file).value;
    }
  }

  const label: LocalizedString =
    labelBlock ?? { kind: 'localizedString', entries: legacy, source: makeSourceLocation(ctx, file) };
  return { label, aliases };
}

// ============================================================================
// v2.1: inline binding helpers
// ============================================================================

function walkBindingProperty(ctx: BindingPropertyContext, file: string): BindingProperty {
  const valueCtx = ctx.bindingValue();

  if (valueCtx.id()) {
    const idCtx = valueCtx.id()!;
    const parts = idCtx.idPart().map((pt) => pt.getText());
    const ref: Reference = {
      path: parts.join('.'),
      parts,
      source: makeSourceLocation(idCtx, file),
    };
    return {
      kind: 'bareId',
      id: ref,
      source: makeSourceLocation(valueCtx, file),
    };
  }

  const blockCtx = valueCtx.bindingBlock()!;
  let target: ObjectValue | Reference | undefined;
  let columns: BindingColumnEntry[] | undefined;
  let fk: Reference | undefined;

  for (const p of blockCtx.bindingBlockProperty()) {
    if (p.targetProperty()) {
      target = walkTargetValue(p.targetProperty()!, file);
    }
    if (p.bindingColumnsProperty()) {
      columns = walkBindingColumnMap(p.bindingColumnsProperty()!.bindingColumnMap()!, file);
    }
    if (p.fkProperty_()?.id()) {
      const idCtx = p.fkProperty_()!.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      fk = { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
    }
  }

  return {
    kind: 'block',
    target,
    columns,
    fk,
    source: makeSourceLocation(blockCtx, file),
  };
}

function walkTargetValue(ctx: TargetPropertyContext, file: string): ObjectValue | Reference {
  if (ctx.id()) {
    const idCtx = ctx.id()!;
    const parts = idCtx.idPart().map((pt) => pt.getText());
    return { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) };
  }
  return walkObject(ctx.object_()!, file);
}

function walkBindingColumnMap(ctx: BindingColumnMapContext, file: string): BindingColumnEntry[] {
  const entries: BindingColumnEntry[] = [];
  for (const e of ctx.bindingColumnEntry()) {
    const name = e.id().idPart().map((pt) => pt.getText()).join('.');
    const v = e.bindingColumnValue();
    let value: BindingColumnValue;
    if (v.id()) {
      const idCtx = v.id()!;
      const parts = idCtx.idPart().map((pt) => pt.getText());
      value = {
        kind: 'bareId',
        id: { path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) },
        source: makeSourceLocation(v, file),
      };
    } else if (v.bindingTargetValue()) {
      const mtv = v.bindingTargetValue()!;
      if (mtv.id()) {
        const idCtx = mtv.id()!;
        const parts = idCtx.idPart().map((pt) => pt.getText());
        value = {
          kind: 'object',
          object: { kind: 'object', entries: [{ key: 'target', value: { kind: 'id', path: parts.join('.'), parts, source: makeSourceLocation(idCtx, file) }, source: makeSourceLocation(mtv, file) }], source: makeSourceLocation(mtv, file) },
          source: makeSourceLocation(v, file),
        };
      } else {
        const inner = walkObject(mtv.object_()!, file);
        value = {
          kind: 'object',
          object: { kind: 'object', entries: [{ key: 'target', value: inner, source: makeSourceLocation(mtv, file) }], source: makeSourceLocation(v, file) },
          source: makeSourceLocation(v, file),
        };
      }
    } else {
      value = {
        kind: 'object',
        object: walkObject(v.object_()!, file),
        source: makeSourceLocation(v, file),
      };
    }
    entries.push({ name, value, source: makeSourceLocation(e, file) });
  }
  return entries;
}

// ============================================================================
// Source location helper
// ============================================================================

function makeSourceLocation(
  ctx: { start?: { line: number; column: number; start: number } | null; stop?: { line: number; column: number; start: number; stop: number } | null },
  file: string
): SourceLocation {
  const startToken = ctx.start ?? { line: 1, column: 0, start: 0 };
  const stopToken = ctx.stop ?? { line: startToken.line, column: startToken.column, start: startToken.start, stop: startToken.start - 1 };
  const stopTokenLength = stopToken.stop - stopToken.start + 1;
  return {
    file,
    line: startToken.line,
    column: startToken.column,
    endLine: stopToken.line,
    endColumn: stopToken.column + stopTokenLength,
    offsetStart: startToken.start,
    offsetEnd: stopToken.stop + 1,
  };
}
