// SPDX-License-Identifier: Apache-2.0
import { parseFile } from './walker.js';
import type { ParseResult } from './ast.js';

export { parseString, parseFile } from './walker.js';
export { DiagnosticCode } from './diagnostics.js';
export { TAG_REGISTRY, resolveTag } from './tag-registry.js';
export type { TagEntry } from './tag-registry.js';
export { sqlPosToFile, fileToSqlOffset } from './sql-position.js';
export type { SqlTokenPos } from './sql-position.js';
export { attachTrivia } from './cst/attach.js';
export type { Trivia, TriviaKind } from './cst/trivia.js';
export type {
  Document,
  Definition,
  ModelDirective,
  SourceLocation,
  ParseError,
  ParseResult,
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
  PropertyValue,
  StringValue,
  TripleStringValue,
  TaggedBlockValue,
  SqlDialect,
  LanguageKind,
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
  SimpleDataType,
  StructuredDataType,
  IndexType,
  ConstraintType,
  QueryLanguage,
  ParameterDirection,
  SearchBlock,
  MatchMethod,
  LexiconEntryDef,
  LexiconBlock,
  SemanticsBlock,
  SemanticsValue,
  SecurityBlock,
  SecurityStatement,
  OwnStatement,
  ClassifyStatement,
  GrantStatement,
  MaskStatement,
  ValueLabels,
  ParameterDef,
  BindingProperty,
  BindingPropertyBareId,
  BindingPropertyBlock,
  BindingColumnEntry,
  BindingColumnValue,
  ImportDecl,
  PackageDecl,
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
  ShapeSpec,
  AttrColumnBinding,
  MeasureColumnBinding,
  JournalingSpec,
  WorldDef,
  EngineDef,
  ExecutorDef,
  StorageDef,
  WorldSchemaDef,
  WorldSchemaField,
} from './ast.js';

export async function parseDirectory(rootPath: string, recursive = true): Promise<ParseResult[]> {
  const fs = await import('fs/promises');

  const results: ParseResult[] = [];

  async function walk(dir: string): Promise<void> {
    let entries;
    try {
      entries = await fs.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }

    for (const entry of entries) {
      if (entry.isDirectory()) {
        if (entry.name === '.modeler') continue;
        if (entry.name === 'node_modules') continue;
        if (entry.name === '.git') continue;
        if (recursive) {
          await walk(dir + '/' + entry.name);
        }
      } else if (entry.isFile() && entry.name.endsWith('.ttrm')) {
        const result = await parseFile(dir + '/' + entry.name);
        results.push(result);
      }
    }
  }

  await walk(rootPath);
  return results;
}