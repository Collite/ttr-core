// SPDX-License-Identifier: Apache-2.0
// Schema adapters for retained AST value forms. Vocabulary/body membership is
// supplied by canonical-metadata; no parallel table of kinds or property names.
import { expression, symbols, sequence, alternatives } from './canonical-metadata.mjs';

const string = { type: 'string' };
const number = { type: 'number' };
const boolean = { type: 'boolean' };
const ref = (name) => ({ $ref: `#/$defs/${name}` });
const array = (items, minItems = 0) => ({ type: 'array', items, ...(minItems ? { minItems } : {}) });
const object = (properties, required = [], extra = false) => ({ type: 'object', properties, ...(required.length ? { required } : {}), additionalProperties: extra });
const any = (...schemas) => {
  const unique = [...new Map(schemas.flat().map((s) => [JSON.stringify(s), s])).values()];
  if (!unique.length) throw new Error('Empty schema union');
  return unique.length === 1 ? unique[0] : { anyOf: unique };
};
const patternMap = (pattern, value) => ({ type: 'object', patternProperties: { [pattern]: value }, additionalProperties: false });
const groupBy = (items, key) => { const groups = {}; for (const item of items) (groups[key(item)] ??= []).push(item); return groups; };
const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export function createSchemas(metadata) {
  const { rules, literals, bodies, identifiers } = metadata;
  const defs = {};
  const handled = new Set();
  defs.identifier = { type: 'string', pattern: identifiers.id };
  const identifier = ref('identifier');
  const data = { type: ['string', 'number', 'boolean', 'null', 'array', 'object'] };
  // These adapters are structural transformations rather than literal EBNF
  // evaluation. Check their grammar signatures, including helper rules, so an
  // edit inside an existing rule cannot silently retain an obsolete schema.
  const signatures = {
    document: "packageDecl ? importDecl * ( modelDirective | graphBlock ) ? ( definition | securityBlock ) * EOF",
    securityBlock: "SECURITY LBRACE ( securityStatement ( COMMA ? securityStatement ) * COMMA ? ) ? RBRACE",
    modelDirective: "MODEL modelCode ( SCHEMA id ) ? ( LOCALE id ) ?",
    packageDecl: "PACKAGE qualifiedName",
    importDecl: "IMPORT qualifiedName ( DOT STAR ) ?",
    graphBlock: "GRAPH id LBRACE ( graphProperty ( COMMA ? graphProperty ) * COMMA ? ) ? RBRACE",
    localizedString: "LBRACE ( localizedEntry ( COMMA ? localizedEntry ) * COMMA ? ) ? RBRACE",
    localizedEntry: "id propSep ? stringLiteralForm",
    localizedStringList: "LBRACE ( localizedStringListEntry ( COMMA ? localizedStringListEntry ) * COMMA ? ) ? RBRACE",
    localizedStringListEntry: "id propSep ? listOfStrings",
    valueLabelsBody: "LBRACE ( valueLabelEntry ( COMMA ? valueLabelEntry ) * COMMA ? ) ? RBRACE",
    valueLabelEntry: "stringLiteralForm propSep ? valueLabelValue",
    valueLabelValue: "LBRACE ( valueLabelField ( COMMA ? valueLabelField ) * COMMA ? ) ? RBRACE",
    valueLabelField: "id propSep ? ( localizedString | listOfStrings | stringLiteralForm )",
    drillArgsMap: "LBRACE ( drillArgEntry ( COMMA ? drillArgEntry ) * COMMA ? ) ? RBRACE",
    drillArgEntry: "id propSep ? stringLiteralForm",
    bindingColumnMap: "LBRACE ( bindingColumnEntry ( COMMA ? bindingColumnEntry ) * COMMA ? ) ? RBRACE",
    bindingColumnEntry: "id propSep ? bindingColumnValue",
    restrictBlock: "LBRACE ( restrictClause ( COMMA ? restrictClause ) * COMMA ? ) ? RBRACE",
    restrictClause: "key propSep ? restrictValue",
    membersBlock: "LBRACE ( memberEntry ( COMMA ? memberEntry ) * COMMA ? ) ? RBRACE",
    memberEntry: "stringLiteralForm propSep ? localizedString",
    parameterDefList: "LBRACK ( parameterInline COMMA ? ) * RBRACK | parameterInline",
    columnDefList: "LBRACK ( columnInline COMMA ? ) * RBRACK | columnInline",
    columnInline: "DEF COLUMN id columnDef",
    attributeDefList: "LBRACK ( attributeInline COMMA ? ) * RBRACK | attributeInline",
    attributeInline: "DEF ATTRIBUTE id attributeDef",
    indexDefList: "LBRACK ( indexInline COMMA ? ) * RBRACK | indexInline",
    indexInline: "DEF INDEX id indexDef",
    constraintDefList: "LBRACK ( constraintInline COMMA ? ) * RBRACK | constraintInline",
    constraintInline: "DEF CONSTRAINT id constraintDef",
    measureInlineList: "LBRACK ( DEF MEASURE id measureDef COMMA ? ) * RBRACK",
    transitionsProperty: "TRANSITIONS propSep ? LBRACE ( transitionEntry ( COMMA ? transitionEntry ) * COMMA ? ) ? RBRACE",
    transitionEntry: "id propSep ? listOfIds",
    dataType: 'typeValue | LBRACE dataTypeProperty (COMMA? dataTypeProperty)* COMMA? RBRACE',
    dataTypeProperty: 'DATA_TYPE propSep? typeValue | LENGTH propSep? NUMBER_LITERAL | PRECISION propSep? NUMBER_LITERAL',
    embeddedBlock: 'TAGGED_BLOCK_LITERAL | TRIPLE_STRING_LITERAL | STRING_LITERAL',
    value: 'literal | id | list | object_ | functionCall',
    literal: 'NUMBER_LITERAL | stringLiteralForm | BOOLEAN_LITERAL | NULL_LITERAL',
    stringLiteralForm: 'STRING_LITERAL | TRIPLE_STRING_LITERAL | TAGGED_BLOCK_LITERAL',
    object_: 'LBRACE propertyList? RBRACE',
    propertyList: 'propertyEntry (COMMA? propertyEntry)* COMMA?',
    propertyEntry: 'key propSep? value',
    key: 'id',
    functionCall: 'id LPAREN ( value (COMMA value)* )? RPAREN',
    autoValue: 'durationLiteral | taggedValue | callValue | STAR | autoObject | autoList | literal | id',
    autoObject: 'LBRACE (autoEntry (COMMA? autoEntry)* COMMA?)? RBRACE',
    autoEntry: 'autoKey propSep? autoValue',
    autoKey: 'id | TEXT',
    autoList: 'LBRACK (autoValue (COMMA autoValue)*)? COMMA? RBRACK',
    durationLiteral: 'NUMBER_LITERAL id',
    taggedValue: 'id stringLiteralForm',
    callValue: 'id autoObject',
    transitionProperty: 'TRANSITION propSep? id ARROW transitionTarget transitionGuard?',
    transitionTarget: 'id | LBRACE id RBRACE',
    transitionGuard: 'LBRACE FROM propSep? (listOfIds | id) RBRACE',
    setProperty: 'SET propSep? id EQUALS id',
    cnValue: 'cnObject | cnList | literal | id',
    cnObject: 'LBRACE (cnObjectEntry (COMMA? cnObjectEntry)* COMMA?)? RBRACE',
    cnObjectEntry: '( id | DATA_TYPE | PRECISION ) propSep? cnValue',
    cnList: 'LBRACK (cnValue (COMMA cnValue)*)? COMMA? RBRACK',
    cnEntry: 'cnKey propSep? cnValue',
    participant: 'id ( AS id )?',
    rangeLiteral: 'NUMBER_LITERAL DOTDOT NUMBER_LITERAL',
    calcRef: 'id ( LPAREN ( calcArg (COMMA calcArg)* )? RPAREN )?',
    calcArg: 'id propSep? value',
    levelEntry: 'id (VIA id)?',
    changeSemanticsProperty: 'CHANGE_SEMANTICS propSep? id changeRoleMap?',
    changeRoleMap: 'LBRACE ( changeRoleEntry ( COMMA? changeRoleEntry )* COMMA? )? RBRACE',
    changeRoleEntry: 'id propSep? id',
    searchableProperty: 'SEARCHABLE ( propSep? BOOLEAN_LITERAL )? matchMethodAttr?',
    matchMethodAttr: 'METHOD propSep? matchMethodValue',
    matchMethodValue: 'id ( LPAREN NUMBER_LITERAL RPAREN )?',
    bindingColumnValue: 'id | LBRACE TARGET propSep? bindingTargetValue RBRACE | object_',
    stepsProperty: 'STEPS propSep? LBRACK (stepInline COMMA?)* RBRACK',
    stepInline: 'DEF STEP id autoBody',
    worldSchemaField: 'id propSep? dataType',
  };
  const normalize = (s) => s.replace(/\s/g, '');
  function guard(name) {
    if (signatures[name] && normalize(rules[name]) !== normalize(signatures[name])) {
      throw new Error(`Unhandled grammar shape in ${name}; update its canonical adapter and specs`);
    }
  }
  function checkReachable(name, seen = new Set()) {
    if (seen.has(name) || /^[A-Z]/.test(name) || ['id', 'idPart', 'propSep'].includes(name)) return;
    seen.add(name);
    if (!rules[name]) throw new Error(`Unhandled value rule: ${name}`);
    guard(name);
    for (const child of symbols(expression(rules[name]))) checkReachable(child, seen);
  }
  for (const name of ['document', 'securityBlock', 'modelDirective', 'packageDecl', 'importDecl', 'graphBlock']) checkReachable(name);
  const entrySequence = (value, key = identifiers.autoKey) => array({ ...patternMap(key, value), minProperties: 1, maxProperties: 1 });
  const quoted = any(string, object({ triple: string }, ['triple']));
  defs.genericRef = object({ ref: identifier }, ['ref']);
  defs.genericTriple = object({ triple: string }, ['triple']);
  defs.embeddedBlock = {
    ...object({ block: string, tag: { type: 'string', pattern: '^[A-Za-z][A-Za-z0-9-]*$' }, form: { enum: ['string', 'tripleString'] } }, ['block']),
    not: { required: ['tag', 'form'] },
  };
  // Reserved wrappers have exclusive shapes. An ordinary object with a reserved
  // key must use the explicit object escape, including recursively nested data.
  const reservedKeys = ['ref', 'triple', 'call', 'object'];
  const ordinaryObject = (value, key = identifiers.id) => ({
    ...patternMap(key, value),
    not: { anyOf: reservedKeys.map((name) => ({ required: [name] })) },
  });
  const genericObject = (value, key = identifiers.id) => any(
    ordinaryObject(value, key),
    object({ object: patternMap(key, value) }, ['object']),
  );
  defs.genericValue = any(
    string, number, boolean, { type: 'null' }, ref('genericRef'), ref('genericTriple'),
    array(ref('genericValue')), genericObject(ref('genericValue')),
    object({ call: identifier, args: array(ref('genericValue')) }, ['call', 'args']),
  );
  defs.cnValue = any(
    string, number, boolean, { type: 'null' }, ref('genericRef'), ref('genericTriple'),
    array(ref('cnValue')), genericObject(ref('cnValue'), identifiers.cnObjectKey),
  );
  defs.autoValue = any(
    string, number, boolean, { type: 'null' }, ref('genericRef'), ref('genericTriple'),
    array(ref('autoValue')), object({ object: entrySequence(ref('autoValue')) }, ['object']),
    object({ amount: number, unit: identifier }, ['amount', 'unit']),
    object({ tag: identifier, value: string }, ['tag', 'value']),
    object({ call: identifier, entries: entrySequence(ref('autoValue')) }, ['call', 'entries']),
    object({ star: { const: true } }, ['star']),
  );

  function nameSchema(name) {
    handled.add(name);
    if (bodies[name]) return bodySchema(name);
    if (['id', 'qualifiedName', 'key', 'STRING_LITERAL', 'TRIPLE_STRING_LITERAL', 'TAGGED_BLOCK_LITERAL', 'NUMBER_LITERAL', 'BOOLEAN_LITERAL', 'NULL_LITERAL'].includes(name) || literals[name]) return rawNameSchema(name);
    const key = `value_${name}`;
    if (!defs[key]) {
      defs[key] = {};
      defs[key] = rawNameSchema(name);
    }
    return ref(key);
  }
  function rawNameSchema(name) {
    if (['id', 'qualifiedName', 'key'].includes(name)) return identifier;
    if (['STRING_LITERAL', 'TRIPLE_STRING_LITERAL', 'TAGGED_BLOCK_LITERAL', 'stringLiteralForm'].includes(name)) return string;
    if (name === 'NUMBER_LITERAL') return number;
    if (name === 'BOOLEAN_LITERAL') return boolean;
    if (name === 'NULL_LITERAL') return { type: 'null' };
    if (literals[name]) return { enum: literals[name] };
    if (bodies[name]) return bodySchema(name);
    checkReachable(name);
    switch (name) {
      case 'value': return ref('genericValue');
      case 'literal': return any(string, number, boolean, { type: 'null' });
      case 'autoValue': return ref('autoValue');
      case 'cnValue': return ref('cnValue');
      case 'object_': return genericObject(ref('genericValue'));
      case 'cnObject': return genericObject(ref('cnValue'), identifiers.cnObjectKey);
      case 'autoObject': return object({ object: entrySequence(ref('autoValue')) }, ['object']);
      case 'embeddedBlock': return ref('embeddedBlock');
      case 'dataType': return any(nameSchema('typeValue'), { ...object({ type: nameSchema('typeValue'), length: number, precision: number }), minProperties: 1 });
      case 'functionCall': return object({ call: identifier, args: array(ref('genericValue')) }, ['call', 'args']);
      case 'participant': return object({ ref: identifier, as: identifier }, ['ref']);
      case 'rangeLiteral': return object({ range: object({ from: number, to: number }, ['from', 'to']) }, ['range']);
      case 'calcRef': return object({ name: identifier, args: patternMap(identifiers.id, string) }, ['name', 'args']);
      case 'levelEntry': return object({ attribute: identifier, via: identifier }, ['attribute']);
      case 'durationLiteral': return object({ amount: number, unit: identifier }, ['amount', 'unit']);
      case 'taggedValue': return object({ tag: identifier, value: string }, ['tag', 'value']);
      case 'callValue': return object({ call: identifier, entries: entrySequence(ref('autoValue')) }, ['call', 'entries']);
      case 'matchMethodValue': return object({ name: identifier, argument: number }, ['name']);
      case 'parameterDefList': return array(bodySchema('parameterInline'));
      case 'columnDefList': return array(nested('columnDef'));
      case 'attributeDefList': return array(nested('attributeDef'));
      case 'indexDefList': return array(nested('indexDef'));
      case 'constraintDefList': return array(nested('constraintDef'));
      case 'measureInlineList': return array(nested('measureDef'));
      case 'bindingColumnValue': return any(identifier, object({ target: nameSchema('bindingTargetValue') }, ['target']), nameSchema('object_'));
    }
    const mapEntries = {
      localizedString: ['localizedEntry', nameSchemaString],
      localizedStringList: ['localizedStringListEntry', () => array(string)],
      valueLabelsBody: ['valueLabelEntry', () => nameSchema('valueLabelValue'), true],
      valueLabelValue: ['valueLabelField', () => any(string, nameSchema('localizedString'), array(string))],
      drillArgsMap: ['drillArgEntry', nameSchemaString],
      bindingColumnMap: ['bindingColumnEntry', () => nameSchema('bindingColumnValue')],
      restrictBlock: ['restrictClause', () => nameSchema('restrictValue')],
      membersBlock: ['memberEntry', () => nameSchema('localizedString'), true],
    };
    if (mapEntries[name]) {
      const [entry, value, arbitraryKey] = mapEntries[name];
      const lower = symbols(expression(rules[name])).filter((s) => /^[a-z]/.test(s));
      if (!lower.includes(entry)) throw new Error(`Unhandled map shape ${name}`);
      if (arbitraryKey) return { type: 'object', additionalProperties: value() };
      return patternMap(identifiers.id, value());
    }
    if (!rules[name]) throw new Error(`Unhandled value rule: ${name}`);
    const expr = expression(rules[name]);
    // Token/id unions and homogeneous grammar lists can be derived exactly.
    return expressionSchema(expr, name);
  }
  function nameSchemaString() { return string; }
  function expressionSchema(expr, owner) {
    if (expr.symbol) return nameSchema(expr.symbol);
    if (expr.alt) return any(expr.alt.map((n) => expressionSchema(n, owner)));
    if (expr.repeat) return expressionSchema(expr.node, owner);
    const seq = sequence(expr);
    if (seq.length === 1) return expressionSchema(seq[0], owner);
    if (seq[0]?.symbol === 'LBRACK' && seq.at(-1)?.symbol === 'RBRACK') {
      const values = [...new Set(seq.slice(1, -1).flatMap(symbols).filter((s) => s !== 'COMMA'))];
      if (values.length !== 1) throw new Error(`Unhandled list shape in ${owner}`);
      return array(nameSchema(values[0]), seq[1]?.repeat === '?' || seq[1]?.repeat === '*' ? 0 : 1);
    }
    throw new Error(`Unhandled value shape in ${owner}: ${JSON.stringify(expr)}`);
  }
  function nested(name) {
    return object({ id: identifier, properties: bodySchema(name) }, ['id', 'properties']);
  }
  const building = new Set();
  function propertySchema(property) {
    const { rule } = property;
    checkReachable(rule);
    switch (rule) {
      case 'searchableProperty': return boolean;
      case 'changeSemanticsProperty': return object({ mode: identifier, roles: patternMap(identifiers.id, identifier) }, ['mode', 'roles']);
      case 'transitionProperty': return {
        ...object({ attribute: identifier, to: identifier, toColumn: identifier, from: array(identifier) }, ['attribute']),
        oneOf: [{ required: ['to'], not: { required: ['toColumn'] } }, { required: ['toColumn'], not: { required: ['to'] } }],
      };
      case 'setProperty': return object({ attribute: identifier, column: identifier }, ['attribute', 'column']);
      case 'transitionsProperty': return patternMap(identifiers.id, array(identifier));
      case 'stepsProperty': return array(nested('autoBody'));
    }
    return expressionSchema(property.value, rule);
  }
  function bodySchema(name, closedRole = false) {
    const key = `body_${name}${closedRole ? '_closed' : ''}`;
    if (defs[key] || building.has(key)) return ref(key);
    building.add(key);
    const body = bodies[name];
    if (!body) throw new Error(`Unhandled body ${name}`);
    let mapping;
    if (body.fields) {
      mapping = patternMap(identifiers.id, nameSchema(body.fields.value));
    } else {
      const properties = Object.fromEntries(body.properties.map((p) => [p.key, propertySchema(p)]));
      if (body.properties.some((p) => p.rule === 'searchableProperty')) properties.method = nameSchema('matchMethodValue');
      if (body.members.length) {
        if (name === 'storageDef') {
          properties.schemas = array(object({ id: identifier, fields: bodySchema('worldSchemaDef') }, ['id', 'fields']));
        } else {
          properties.members = array(any(body.members.map((m) => object(
            { kind: { const: m.kind }, ...(m.named ? { id: identifier } : {}), properties: bodySchema(m.body) },
            ['kind', ...(m.named ? ['id'] : []), 'properties'],
          ))));
        }
      }
      mapping = object(properties);
      const free = closedRole ? [] : body.freeForm;
      if (free.length) {
        const known = Object.keys(properties);
        const exclude = known.length ? `(?!${known.map(escape).join('$|')}$)` : '';
        mapping.patternProperties = Object.fromEntries(free.map((f) => {
          const pattern = f.key === 'cnKey' ? identifiers.cnKey : f.key === 'autoKey' ? identifiers.autoKey : identifiers.id;
          return [`^${exclude}${pattern.slice(1)}`, nameSchema(f.value)];
        }));
      }
    }
    defs[key] = !closedRole && body.ordered ? any(mapping, array({ ...mapping, minProperties: 1, maxProperties: 1 })) : mapping;
    building.delete(key);
    return ref(key);
  }
  for (const name of Object.keys(bodies)) bodySchema(name);
  bodySchema('roleDef', true);
  for (const kind of metadata.kinds) {
    defs[`def_${kind.kind}`] = object({ kind: { const: kind.kind }, id: identifier, properties: bodySchema(kind.body) }, ['kind', 'id', 'properties']);
  }
  defs.def_role_closed = object({ kind: { const: 'role' }, id: identifier, properties: bodySchema('roleDef', true) }, ['kind', 'id', 'properties']);
  const definitions = (cn) => any(metadata.kinds.map((k) => ref(k.kind === 'role' && !cn ? 'def_role_closed' : `def_${k.kind}`)));
  const graphProperties = {};
  const graphRefs = symbols(expression(rules.graphProperty));
  for (const rule of graphRefs) {
    const seq = sequence(expression(rules[rule]));
    const key = literals[seq[0].symbol]?.[0];
    if (!key) throw new Error(`Unhandled graph property ${rule}`);
    graphProperties[key] = propertySchema({ rule, value: { seq: seq.slice(2) } });
  }
  // Graph properties use the same extraction as definitions, with their own identity.
  const security = alternatives(expression(rules.securityStatement)).map((option) => {
    const tokens = sequence(option).map((n) => n.symbol);
    const verb = literals[tokens[0]]?.[0];
    const forms = {
      own: ['OWN id COLON id', { object: identifier, owner: identifier }],
      classify: ['CLASSIFY id COLON id', { object: identifier, classification: identifier }],
      grant: ['GRANT id ON id TO id', { privilege: identifier, object: identifier, to: identifier }],
      mask: ['MASK id', { object: identifier }],
    };
    if (!forms[verb] || tokens.join(' ') !== forms[verb][0]) throw new Error(`Unhandled security statement ${verb}`);
    return object({ verb: { const: verb }, ...forms[verb][1] }, ['verb', ...Object.keys(forms[verb][1])]);
  });
  const model = {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    $id: `https://tatrman.org/schemas/${metadata.version}/model-yaml.schema.json`,
    title: 'Canonical TTR YAML document',
    'x-ttr-version': metadata.version,
    ...object({
      ttr: { const: metadata.version }, package: identifier,
      imports: array({ type: 'string', pattern: identifiers.id.replace(/\$$/, '(?:\\.\\*)?$') }),
      model: { enum: metadata.models }, schema: identifier, locale: identifier,
      graph: object({ id: identifier, properties: object(graphProperties) }, ['id', 'properties']),
      security: array(array(any(security))), defs: array(definitions(true)),
    }, ['ttr']),
    dependentRequired: { schema: ['model'], locale: ['model'] },
    allOf: [
      { not: { required: ['model', 'graph'] } },
      { if: { properties: { model: { const: 'cn' } }, required: ['model'] }, then: { properties: { defs: array(definitions(true)) } }, else: { properties: { defs: array(definitions(false)) } } },
    ],
    $defs: { ...defs },
  };
  // Follow the generated value schemas, not a second property vocabulary.
  // Named nested definitions expose their properties; arrays select elements.
  // Only recursive generic objects can admit an arbitrary property tail.
  const recursiveValues = {
    genericValue: identifiers.id,
    cnValue: identifiers.cnObjectKey,
    autoValue: identifiers.autoKey,
  };
  function objectVariants(schema, seen = new Set()) {
    if (schema.$ref) {
      const key = schema.$ref.split('/').at(-1);
      if (seen.has(key)) return [];
      return objectVariants(defs[key], new Set(seen).add(key));
    }
    if (schema.anyOf || schema.oneOf) return (schema.anyOf ?? schema.oneOf).flatMap((v) => objectVariants(v, seen));
    return schema.type === 'object' ? [schema] : [];
  }
  const keySchemas = new Map();
  function keySchema(pattern) {
    if (!keySchemas.has(pattern)) {
      const name = `targetKey_${keySchemas.size}`;
      keySchemas.set(pattern, name); defs[name] = { type: 'string', pattern };
    }
    return ref(keySchemas.get(pattern));
  }
  function targetPaths(bodyName) {
    const paths = [];
    function endpoint(prefix, value, tail) {
      if (!prefix.length) return;
      paths.push({ schema: { type: 'array', prefixItems: prefix, minItems: prefix.length + (tail ? 1 : 0), ...(tail ? { items: tail } : { maxItems: prefix.length }) }, value });
    }
    function walk(schema, prefix = [], seen = new Set()) {
      if (schema.$ref) {
        const key = schema.$ref.split('/').at(-1);
        if (recursiveValues[key]) { endpoint(prefix, schema, keySchema(recursiveValues[key])); return; }
        if (seen.has(key)) return;
        return walk(defs[key], prefix, new Set(seen).add(key));
      }
      if (schema.anyOf || schema.oneOf) {
        (schema.anyOf ?? schema.oneOf).forEach((v) => walk(v, prefix, seen));
      } else if (schema.type === 'array') {
        if (schema.items) walk(schema.items, prefix, seen);
      } else if (schema.type === 'object') {
        if (schema.properties?.properties && schema.required?.includes('properties')) {
          walk(schema.properties.properties, prefix, seen);
          return;
        }
        for (const [key, value] of Object.entries(schema.properties ?? {})) {
          const next = prefix.concat({ const: key });
          endpoint(next, value); walk(value, next, seen);
        }
        for (const [pattern, value] of Object.entries(schema.patternProperties ?? {})) {
          const next = prefix.concat(keySchema(pattern));
          endpoint(next, value); walk(value, next, seen);
        }
        if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
          const next = prefix.concat(string);
          endpoint(next, schema.additionalProperties); walk(schema.additionalProperties, next, seen);
        }
      }
    }
    walk(bodySchema(bodyName));
    return [...new Map(paths.map((p) => [JSON.stringify(p), p])).values()];
  }
  const targetKinds = [...new Map([...metadata.kinds, ...metadata.nestedKinds].map((k) => [`${k.kind}/${k.body}`, k])).values()];
  const pathsByBody = {};
  const targetBranches = targetKinds.map(({ kind, body: bodyName }) => {
    const variants = objectVariants(bodySchema(bodyName));
    const keys = [...new Set(variants.flatMap((v) => Object.keys(v.properties ?? {})))];
    const freeKeys = variants.flatMap((v) => Object.keys(v.patternProperties ?? {}).map((pattern) => ({ type: 'string', pattern })));
    const propertyName = `propertyKey_${bodyName}`;
    defs[propertyName] ??= any(...(keys.length ? [{ enum: keys }] : []), ...freeKeys);
    pathsByBody[bodyName] ??= targetPaths(bodyName);
    const pathName = `targetPath_${bodyName}`;
    defs[pathName] ??= any(pathsByBody[bodyName].map((p, index) => { const name = `path_${bodyName}_${index}`; defs[name] = p.schema; p.schemaRef = ref(name); return p.schemaRef; }));
    return {
      ...object({ kind: { const: kind }, property: ref(propertyName), path: ref(pathName) }, ['kind']),
      not: { required: ['property', 'path'] },
    };
  });
  const groupedTargets = groupBy(targetBranches, (branch) => branch.properties.kind.const);
  defs.canonicalTarget = {
    ...object({ kind: { enum: Object.keys(groupedTargets) }, property: string, path: array(string, 1) }, ['kind']),
    not: { required: ['property', 'path'] },
    allOf: Object.entries(groupedTargets).map(([kind, branches]) => ({
      if: { properties: { kind: { const: kind } }, required: ['kind'] },
      then: any(branches),
    })),
  };
  defs.propertyTarget = { type: 'object', anyOf: [{ required: ['property'] }, { required: ['path'] }] };
  const pointer = { type: 'string', pattern: '^(?:/(?:[^~/]|~[01])*)*$' };
  defs.profileTemplate = any(string, number, boolean, { type: 'null' }, array(ref('profileTemplate')), { type: 'object', additionalProperties: ref('profileTemplate'), not: { required: ['input'] } }, object({ input: pointer }, ['input']));
  const files = array({ type: 'string', minLength: 1 }, 1);
  const selectors = { at: pointer, files };
  const operation = (op, props, required) => object({ op: { const: op }, ...props }, ['op', ...required]);
  const operations = [
    operation('kinds', { ...selectors, mapping: { type: 'object', additionalProperties: { enum: [...new Set(targetKinds.map((k) => k.kind))] } } }, ['at', 'mapping']),
    operation('package', { ...selectors, mapping: { type: 'object', additionalProperties: identifier }, default: identifier }, ['mapping']),
    operation('rename', { ...selectors, to: ref('propertyTarget') }, ['at', 'to']),
    operation('shape', { ...selectors, to: { type: 'object' }, template: ref('profileTemplate') }, ['at', 'to', 'template']),
    ...['nest', 'unnest'].map((op) => operation(op, { ...selectors, to: ref('propertyTarget') }, ['at', 'to'])),
    { ...operation('ignore', { ...selectors, reason: string }, []), anyOf: [{ required: ['at'] }, { required: ['files'] }] },
    operation('codes', { mapping: { type: 'object', additionalProperties: { type: 'string', minLength: 1 } } }, ['mapping']),
    operation('link', { ...selectors, to: ref('propertyTarget'), by: { enum: ['sourceName', 'iri', 'path'] } }, ['at', 'to', 'by']),
  ];
  // Group endpoint selectors by their value type. Repeating a full nested
  // value schema at every path causes eager validators to expand it thousands
  // of times. Each distinct type is validated once, with all its selectors.
  const typeGroups = new Map();
  function valuePair(target, value) {
    const key = JSON.stringify(value);
    if (!typeGroups.has(key)) typeGroups.set(key, { value, targets: [] });
    typeGroups.get(key).targets.push(target);
  }
  for (const [bodyName, kinds] of Object.entries(groupBy(targetKinds, (k) => k.body))) {
    const variants = objectVariants(bodySchema(bodyName));
    const kind = { enum: [...new Set(kinds.map((k) => k.kind))] };
    for (const variant of variants) {
      for (const [name, value] of Object.entries(variant.properties ?? {})) valuePair({ properties: { kind, property: { const: name } }, required: ['property'] }, value);
      for (const [pattern, value] of Object.entries(variant.patternProperties ?? {})) valuePair({ properties: { kind, property: keySchema(pattern) }, required: ['property'] }, value);
    }
    for (const p of pathsByBody[bodyName]) valuePair({ properties: { kind, path: p.schemaRef }, required: ['path'] }, p.value);
  }
  operations.push({
    ...operation('values', { ...selectors, to: ref('propertyTarget'), mapping: { type: 'object' }, default: data }, ['at', 'to', 'mapping']),
    anyOf: [...typeGroups.values()].map(({ value, targets }) => ({ properties: { to: any(targets), mapping: { type: 'object', additionalProperties: value }, default: value } })),
  });
  const nonEmpty = { type: 'string', minLength: 1 };
  const declaration = object({ profile: nonEmpty, grammar: nonEmpty }, ['profile', 'grammar']);
  const profile = {
    $schema: model.$schema,
    $id: `https://tatrman.org/schemas/${metadata.version}/profile.schema.json`,
    title: 'TTR profile definition or tree declaration',
    'x-ttr-version': metadata.version,
    oneOf: [
      declaration,
      object({ profile: { type: 'string', pattern: '^.+/[0-9]+$' }, grammar: nonEmpty, extends: nonEmpty,
        overlay: any(nonEmpty, { type: 'object', additionalProperties: { type: 'object', additionalProperties: data } }), writable: { ...boolean, default: false },
        operations: array({
          ...object({ op: { enum: operations.map((o) => o.properties.op.const) }, at: pointer, files, to: ref('canonicalTarget'), mapping: { type: 'object' }, default: data, template: ref('profileTemplate'), reason: string, by: string }, ['op']),
          allOf: operations.map((op) => ({ if: { properties: { op: op.properties.op }, required: ['op'] }, then: op })),
        }),
      }, ['profile', 'grammar', 'operations']),
    ],
    $defs: defs,
  };
  return { model, profile, handled: [...handled].sort() };
}
