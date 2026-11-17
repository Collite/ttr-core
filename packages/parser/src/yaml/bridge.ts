// SPDX-License-Identifier: Apache-2.0
// Internal syntax projection, as in Kotlin TtrText: the existing walker remains
// the single constructor of the native AST. No generated coordinates escape.
import { CharStream, CommonTokenStream, Token } from 'antlr4ng';
import { TTRLexer } from '../generated/TTRLexer.js';
import { TTRParser } from '../generated/TTRParser.js';
import { canonicalMetadata } from '../generated/canonical-yaml.js';
import { encodeTtrString } from '../strings.js';
import {
  list,
  record,
  pointer,
  YamlFailure,
  type Json,
  type RecordValue,
  type YamlInput,
} from './input.js';

interface Expression {
  symbol?: string;
  alt?: Expression[];
  seq?: Expression[];
  node?: Expression;
  repeat?: string;
}
interface Property {
  key: string;
  rule: string;
  value: Expression;
}
interface Body {
  properties: Property[];
  freeForm: { value: string }[];
  members: { kind: string; body: string }[];
  fields?: unknown;
}
interface Metadata {
  kinds: { kind: string; body: string }[];
  bodies: Record<string, Body>;
  rules: Record<string, string>;
  literals: Record<string, string[]>;
  identifiers: Record<string, string>;
}
export interface Projection {
  start: number;
  end: number;
  path: string;
  literal?: 'block' | 'triple';
}
export interface Piece {
  text: string;
  spans: Projection[];
}
const metadata = canonicalMetadata as Metadata;
const kinds = new Map(metadata.kinds.map((kind) => [kind.kind, kind.body]));
const raw = (text: string): Piece => ({ text, spans: [] });
function join(parts: (Piece | string)[], separator = ''): Piece {
  let text = '';
  const spans: Projection[] = [];
  for (const [i, part] of parts.entries()) {
    if (i) text += separator;
    const item = typeof part === 'string' ? raw(part) : part;
    for (const span of item.spans)
      spans.push({ ...span, start: span.start + text.length, end: span.end + text.length });
    text += item.text;
  }
  return { text, spans };
}
function mark(piece: Piece, path: string, literal?: Projection['literal']): Piece {
  return {
    ...piece,
    spans: [
      ...piece.spans,
      { start: 0, end: piece.text.length, path, ...(literal ? { literal } : {}) },
    ],
  };
}
function string(value: Json): string {
  if (typeof value !== 'string') throw new Error('Expected a string');
  return value;
}
function expression(source: string): Expression {
  const tokens = source.match(/[A-Za-z_]\w*|[()|?*+]/g) ?? [];
  let i = 0;
  function sequence(): Expression {
    const nodes: Expression[] = [];
    while (i < tokens.length && tokens[i] !== ')' && tokens[i] !== '|') {
      let node: Expression;
      if (tokens[i] === '(') {
        i++;
        node = alternative();
        if (tokens[i++] !== ')') throw new Error('Unbalanced grammar expression');
      } else node = { symbol: tokens[i++] };
      if (['?', '*', '+'].includes(tokens[i])) node = { node, repeat: tokens[i++] };
      nodes.push(node);
    }
    return nodes.length === 1 ? nodes[0] : { seq: nodes };
  }
  function alternative(): Expression {
    const nodes = [sequence()];
    while (tokens[i] === '|') {
      i++;
      nodes.push(sequence());
    }
    return nodes.length === 1 ? nodes[0] : { alt: nodes };
  }
  const result = alternative();
  if (i !== tokens.length) throw new Error('Unexpected grammar suffix');
  return result;
}
const symbols = (e: Expression): string[] =>
  e.symbol ? [e.symbol] : e.node ? symbols(e.node) : (e.seq ?? e.alt ?? []).flatMap(symbols);
const parsed = new Map<string, Expression>();

export class YamlBridge {
  readonly embedded = new Set<string>();
  constructor(readonly input: YamlInput) {}
  write(): Piece {
    const doc = this.input.value;
    const parts: Piece[] = [];
    if (doc.package !== undefined)
      parts.push(join(['package ', this.render('qualifiedName', doc.package, '/package')]));
    if (doc.imports)
      for (const [i, value] of list(doc.imports).entries())
        parts.push(mark(raw(`import ${string(value)}`), `/imports/${i}`));
    if (doc.model !== undefined) {
      const model: (Piece | string)[] = ['model ', mark(raw(string(doc.model)), '/model')];
      for (const key of ['schema', 'locale'])
        if (doc[key] !== undefined) model.push(` ${key} `, mark(raw(string(doc[key])), `/${key}`));
      parts.push(mark(join(model), '/model'));
    }
    if (doc.graph) {
      const graph = record(doc.graph);
      const properties = record(graph.properties);
      const entries = Object.entries(properties).map(([key, value]) => {
        const path = pointer('/graph/properties', key);
        const rule = (
          {
            model: 'modelCode',
            description: typeof value === 'object' ? 'localizedString' : 'stringLiteralForm',
            tags: 'listOfStrings',
            objects: 'listOfIds',
            layout: 'object_',
          } as Record<string, string>
        )[key];
        return mark(join([`${key}: `, this.render(rule, value, path)]), path);
      });
      parts.push(
        mark(
          join([
            'graph ',
            mark(raw(string(graph.id)), '/graph/id'),
            ' { ',
            join(entries, ', '),
            ' }',
          ]),
          '/graph'
        )
      );
    }
    if (doc.defs)
      for (const [i, def] of list(doc.defs).entries()) {
        const value = record(def);
        const kind = string(value.kind);
        const body = kinds.get(kind);
        if (!body) throw new YamlFailure(`/defs/${i}/kind`, `Unknown definition kind ${kind}`);
        parts.push(this.definition(value, `/defs/${i}`, kind, body));
      }
    if (doc.security)
      for (const [i, block] of list(doc.security).entries()) {
        const statements = list(block).map((value, j) => {
          const s = record(value);
          const path = `/security/${i}/${j}`;
          const field = (key: string) => mark(raw(string(s[key])), pointer(path, key));
          let piece: Piece;
          switch (s.verb) {
            case 'own':
              piece = join(['own ', field('object'), ': ', field('owner')]);
              break;
            case 'classify':
              piece = join(['classify ', field('object'), ': ', field('classification')]);
              break;
            case 'grant':
              piece = join([
                'grant ',
                field('privilege'),
                ' on ',
                field('object'),
                ' to ',
                field('to'),
              ]);
              break;
            case 'mask':
              piece = join(['mask ', field('object')]);
              break;
            default:
              throw new YamlFailure(path, 'Unknown security verb');
          }
          return mark(piece, path);
        });
        parts.push(mark(join(['security { ', join(statements, ', '), ' }']), `/security/${i}`));
      }
    return mark(join([...parts, ''], '\n'), '');
  }
  private definition(
    value: RecordValue,
    path: string,
    kind: string,
    body: string,
    fields = 'properties'
  ): Piece {
    return mark(
      join([
        `def ${kind} `,
        value.id === undefined ? '' : mark(raw(string(value.id)), pointer(path, 'id')),
        ' ',
        this.body(body, value[fields], pointer(path, fields)),
      ]),
      path
    );
  }
  private array(value: Json, path: string, encode: (v: Json, p: string) => Piece): Piece {
    return join([
      '[',
      join(
        list(value).map((v, i) => encode(v, pointer(path, i))),
        ', '
      ),
      ']',
    ]);
  }
  private map(
    value: Json,
    path: string,
    encode: (v: Json, p: string) => Piece,
    quotedKey = false
  ): Piece {
    return join([
      '{ ',
      join(
        Object.entries(record(value)).map(([key, v]) => {
          const p = pointer(path, key);
          return mark(
            join([mark(raw(quotedKey ? encodeTtrString(key) : key), p), ': ', encode(v, p)]),
            p
          );
        }),
        ', '
      ),
      ' }',
    ]);
  }
  private body(name: string, value: Json, path: string): Piece {
    const body = metadata.bodies[name];
    if (!body) throw new Error(`Unhandled body ${name}`);
    if (body.fields)
      return mark(
        this.map(value, path, (v, p) => this.render('dataType', v, p)),
        path
      );
    const entries = Array.isArray(value)
      ? value.flatMap((v, i) =>
          Object.entries(record(v)).map(([key, v]) => ({
            key,
            value: v,
            path: pointer(pointer(path, i), key),
          }))
        )
      : Object.entries(record(value)).map(([key, v]) => ({
          key,
          value: v,
          path: pointer(path, key),
        }));
    const properties = new Map(body.properties.map((p) => [p.key, p]));
    const parts = entries
      .filter((e) => e.key !== 'method' || !properties.has('searchable'))
      .map((entry) => {
        const { key, value: v, path: p } = entry;
        if (body.members.length && key === (name === 'storageDef' ? 'schemas' : 'members')) {
          return mark(
            join(
              list(v).map((item, i) => {
                const member = record(item);
                const kind = name === 'storageDef' ? 'schema' : string(member.kind);
                const shape = body.members.find((m) => m.kind === kind);
                if (!shape) throw new YamlFailure(pointer(p, i), `Unknown member kind ${kind}`);
                return this.definition(
                  member,
                  pointer(p, i),
                  kind,
                  shape.body,
                  name === 'storageDef' ? 'fields' : 'properties'
                );
              }),
              ', '
            ),
            p
          );
        }
        if (
          name === 'cnBindingBody' &&
          key === 'filter' &&
          recordOrUndefined(v)?.block !== undefined
        )
          return mark(join([`${key}: `, this.render('embeddedBlock', v, p)]), p);
        let typed: Piece | undefined;
        const prop = properties.get(key);
        if (prop) {
          try {
            typed = this.property(prop, v, p, entries);
          } catch (error) {
            if (!body.freeForm.length) throw error;
          }
        }
        if (!typed) {
          const free = body.freeForm[0]?.value;
          if (!free) throw new YamlFailure(p, `No canonical handler for ${name}.${key}`);
          typed = this.render(free, v, p);
        }
        return mark(join([`${key}: `, typed]), p);
      });
    return mark(join(['{ ', join(parts, ', '), ' }']), path);
  }
  private property(
    prop: Property,
    value: Json,
    path: string,
    entries: { key: string; value: Json; path: string }[]
  ): Piece {
    const r = recordOrUndefined(value);
    const field = (key: string, rule = 'id') => this.render(rule, r![key], pointer(path, key));
    switch (prop.rule) {
      case 'searchableProperty': {
        const method = entries.filter((e) => e.key === 'method').at(-1);
        return join([
          this.render('BOOLEAN_LITERAL', value, path),
          ...(method
            ? [' method: ', this.render('matchMethodValue', method.value, method.path)]
            : []),
        ]);
      }
      case 'changeSemanticsProperty':
        return join([field('mode'), ' ', field('roles', 'changeRoleMap')]);
      case 'transitionProperty':
        return join([
          field('attribute'),
          ' -> ',
          r!.to === undefined ? join(['{', field('toColumn'), '}']) : field('to'),
          ...(r!.from ? [' { from: ', field('from', 'listOfIds'), ' }'] : []),
        ]);
      case 'setProperty':
        return join([field('attribute'), ' = ', field('column')]);
      case 'transitionsProperty':
        return this.map(value, path, (v, p) => this.render('listOfIds', v, p));
      case 'stepsProperty':
        return this.array(value, path, (v, p) => this.definition(record(v), p, 'step', 'autoBody'));
      default:
        return this.expr(prop.value, value, path);
    }
  }
  render(name: string, value: Json, path: string): Piece {
    try {
      return mark(this.renderValue(name, value, path), path);
    } catch (error) {
      if (error instanceof YamlFailure) throw error;
      throw new YamlFailure(path, error instanceof Error ? error.message : `Invalid ${name}`);
    }
  }
  private renderValue(name: string, value: Json, path: string): Piece {
    if (metadata.bodies[name]) return this.body(name, value, path);
    if (['id', 'qualifiedName', 'key', 'cnKey', 'autoKey'].includes(name)) {
      const text = string(value);
      const pattern = metadata.identifiers[['cnKey', 'autoKey'].includes(name) ? name : 'id'];
      if (!new RegExp(pattern).test(text)) throw new Error(`Invalid ${name}`);
      return raw(text);
    }
    if (
      [
        'STRING_LITERAL',
        'TRIPLE_STRING_LITERAL',
        'TAGGED_BLOCK_LITERAL',
        'stringLiteralForm',
      ].includes(name)
    )
      return raw(encodeTtrString(string(value)));
    if (name === 'NUMBER_LITERAL') {
      if (typeof value !== 'number') throw new Error('Expected a number');
      return raw(this.input.number(path, value));
    }
    if (name === 'BOOLEAN_LITERAL') {
      if (typeof value !== 'boolean') throw new Error('Expected a boolean');
      return raw(String(value));
    }
    if (name === 'NULL_LITERAL') {
      if (value !== null) throw new Error('Expected null');
      return raw('null');
    }
    if (metadata.literals[name]) {
      if (!metadata.literals[name].includes(string(value)))
        throw new Error(`Not a ${name} literal`);
      return raw(string(value));
    }
    const maps: Record<string, string> = {
      localizedString: 'stringLiteralForm',
      localizedStringList: 'listOfStrings',
      valueLabelsBody: 'valueLabelValue',
      drillArgsMap: 'stringLiteralForm',
      bindingColumnMap: 'bindingColumnValue',
      restrictBlock: 'restrictValue',
      membersBlock: 'localizedString',
      changeRoleMap: 'id',
    };
    if (maps[name])
      return this.map(
        value,
        path,
        (v, p) => this.render(maps[name], v, p),
        ['valueLabelsBody', 'membersBlock'].includes(name)
      );
    const r = recordOrUndefined(value);
    const field = (key: string, rule = 'id') => this.render(rule, r![key], pointer(path, key));
    switch (name) {
      case 'value':
      case 'cnValue':
      case 'autoValue':
        return this.generic(value, path, name);
      case 'object_':
      case 'cnObject': {
        const escaped = r && Object.keys(r).length === 1 && 'object' in r;
        return this.map(
          escaped ? r.object : value,
          escaped ? pointer(path, 'object') : path,
          (v, p) => this.generic(v, p, name === 'cnObject' ? 'cnValue' : 'value')
        );
      }
      case 'autoObject': {
        const values = list(r!.object).flatMap((v, i) =>
          Object.entries(record(v)).map(([key, v]) =>
            join([
              `${key}: `,
              this.generic(v, pointer(pointer(pointer(path, 'object'), i), key), 'autoValue'),
            ])
          )
        );
        return join(['{ ', join(values, ', '), ' }']);
      }
      case 'functionCall':
        return join([
          field('call'),
          '(',
          join(
            list(r!.args).map((v, i) =>
              this.generic(v, pointer(pointer(path, 'args'), i), 'value')
            ),
            ', '
          ),
          ')',
        ]);
      case 'embeddedBlock': {
        this.embedded.add(path);
        // Always quote the projection; relocation restores the canonical form,
        // tag and exact text without subjecting YAML text to TTR dedentation.
        return mark(raw(encodeTtrString(string(r!.block))), path, 'block');
      }
      case 'dataType':
        return typeof value === 'string'
          ? this.render('typeValue', value, path)
          : this.map(value, path, (v, p) =>
              this.render(typeof v === 'string' ? 'typeValue' : 'NUMBER_LITERAL', v, p)
            );
      case 'participant':
        return join([field('ref'), ...(r!.as === undefined ? [] : [' as ', field('as')])]);
      case 'durationLiteral':
        return join([field('amount', 'NUMBER_LITERAL'), ' ', field('unit')]);
      case 'taggedValue':
        return join([field('tag'), ' ', field('value', 'stringLiteralForm')]);
      case 'callValue':
        return join([field('call'), ' ', this.render('autoObject', { object: r!.entries }, path)]);
      case 'rangeLiteral':
        return join([
          this.render(
            'NUMBER_LITERAL',
            record(r!.range).from,
            pointer(pointer(path, 'range'), 'from')
          ),
          '..',
          this.render('NUMBER_LITERAL', record(r!.range).to, pointer(pointer(path, 'range'), 'to')),
        ]);
      case 'restrictValue': {
        if (!r) return this.generic(value, path, 'value');
        if (Object.keys(r).length === 1 && 'range' in r)
          return this.render('rangeLiteral', value, path);
        if (Object.keys(r).length === 1 && 'object' in r)
          return this.render('object_', value, path);
        if (this.genericRecord(r)) return this.generic(value, path, 'value');
        return this.render('membersBlock', value, path);
      }
      case 'calcRef':
        return join([
          field('name'),
          '(',
          join(
            Object.entries(record(r!.args)).map(([key, v]) =>
              mark(
                raw(`${key}: ${this.retained(v, pointer(pointer(path, 'args'), key))}`),
                pointer(pointer(path, 'args'), key)
              )
            ),
            ', '
          ),
          ')',
        ]);
      case 'levelEntry':
        return join([field('attribute'), ...(r!.via === undefined ? [] : [' via ', field('via')])]);
      case 'matchMethodValue':
        return join([
          field('name'),
          ...(r!.argument === undefined ? [] : ['(', field('argument', 'NUMBER_LITERAL'), ')']),
        ]);
      case 'parameterDefList':
        return this.array(value, path, (v, p) => this.body('parameterInline', v, p));
      case 'columnDefList':
      case 'attributeDefList':
      case 'indexDefList':
      case 'constraintDefList':
      case 'measureInlineList': {
        const kind = name.replace(/DefList$|InlineList$/, '');
        return this.array(value, path, (v, p) => this.definition(record(v), p, kind, `${kind}Def`));
      }
      case 'bindingColumnValue':
        return typeof value === 'string'
          ? this.render('id', value, path)
          : r && Object.keys(r).length === 1 && 'target' in r
            ? join(['{ target: ', field('target', 'bindingTargetValue'), ' }'])
            : this.render('object_', value, path);
      case 'valueLabelValue':
        return this.map(value, path, (v, p) =>
          this.render(
            Array.isArray(v)
              ? 'listOfStrings'
              : recordOrUndefined(v)
                ? 'localizedString'
                : 'stringLiteralForm',
            v,
            p
          )
        );
      default: {
        let e = parsed.get(name);
        if (!e) {
          if (!metadata.rules[name]) throw new Error(`Unhandled canonical grammar rule ${name}`);
          e = expression(metadata.rules[name]);
          parsed.set(name, e);
        }
        return this.expr(e, value, path);
      }
    }
  }
  private retained(value: Json, path: string): string {
    const source = string(value);
    let invalid = false;
    const listener = {
      syntaxError() {
        invalid = true;
      },
      reportAmbiguity() {},
      reportAttemptingFullContext() {},
      reportContextSensitivity() {},
    };
    const lexer = new TTRLexer(CharStream.fromString(source));
    lexer.removeErrorListeners();
    lexer.addErrorListener(listener);
    const tokens = new CommonTokenStream(lexer);
    tokens.fill();
    let depth = 0;
    const stack: number[] = [];
    const pairs = new Map([
      [TTRLexer.LBRACE, TTRLexer.RBRACE],
      [TTRLexer.LBRACK, TTRLexer.RBRACK],
      [TTRLexer.LPAREN, TTRLexer.RPAREN],
    ]);
    for (const token of tokens.getTokens()) {
      if (token.channel !== Token.DEFAULT_CHANNEL) invalid = true;
      const closer = pairs.get(token.type);
      if (closer !== undefined) {
        stack.push(closer);
        if (++depth > 64) throw new YamlFailure(path, 'Retained value nesting too deep (limit 64)');
      } else if ([...pairs.values()].includes(token.type)) {
        if (stack.pop() !== token.type) invalid = true;
        depth--;
      }
    }
    if (invalid || stack.length)
      throw new YamlFailure(path, 'Calc argument must contain exactly one retained TTR value');
    const parser = new TTRParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(listener);
    parser.value();
    if (invalid || parser.getCurrentToken().type !== Token.EOF)
      throw new YamlFailure(path, 'Calc argument must contain exactly one retained TTR value');
    return source;
  }
  private genericRecord(r: RecordValue): boolean {
    return (
      (Object.keys(r).length === 1 &&
        (typeof r.ref === 'string' || typeof r.triple === 'string')) ||
      (Object.keys(r).length === 2 && typeof r.call === 'string' && Array.isArray(r.args))
    );
  }
  private generic(value: Json, path: string, mode: string): Piece {
    let piece: Piece;
    if (value === null || typeof value === 'boolean') piece = raw(String(value));
    else if (typeof value === 'number') piece = raw(this.input.number(path, value));
    else if (typeof value === 'string') piece = raw(encodeTtrString(value));
    else if (Array.isArray(value))
      piece = this.array(value, path, (v, p) => this.generic(v, p, mode));
    else if (Object.keys(value).length === 1 && 'ref' in value)
      piece = this.render('id', value.ref, pointer(path, 'ref'));
    else if (Object.keys(value).length === 1 && 'triple' in value)
      piece = mark(raw(encodeTtrString(string(value.triple))), path, 'triple');
    else if (mode === 'autoValue' && 'star' in value) piece = raw('*');
    else if (mode === 'autoValue' && 'entries' in value)
      piece = this.render('callValue', value, path);
    else if (mode === 'autoValue' && 'amount' in value)
      piece = this.render('durationLiteral', value, path);
    else if (mode === 'autoValue' && 'tag' in value)
      piece = this.render('taggedValue', value, path);
    else if (mode === 'autoValue' && 'object' in value)
      piece = this.render('autoObject', value, path);
    else if ('call' in value && 'args' in value) piece = this.render('functionCall', value, path);
    else piece = this.render(mode === 'cnValue' ? 'cnObject' : 'object_', value, path);
    return mark(piece, path);
  }
  private expr(e: Expression, value: Json, path: string): Piece {
    if (e.symbol) return this.render(e.symbol, value, path);
    if (e.node) return this.expr(e.node, value, path);
    if (e.alt) {
      for (const choice of e.alt) {
        try {
          return this.expr(choice, value, path);
        } catch {
          /* Try the grammar's next alternative. */
        }
      }
      throw new YamlFailure(path, 'No canonical grammar alternative');
    }
    const seq = e.seq ?? [];
    if (seq.length === 1) return this.expr(seq[0], value, path);
    if (seq[0]?.symbol === 'LBRACK' && seq.at(-1)?.symbol === 'RBRACK') {
      const names = [
        ...new Set(symbols(e).filter((s) => !['LBRACK', 'RBRACK', 'COMMA'].includes(s))),
      ];
      if (names.length === 1) return this.array(value, path, (v, p) => this.render(names[0], v, p));
    }
    throw new YamlFailure(path, `Unhandled canonical grammar expression ${JSON.stringify(e)}`);
  }
}
function recordOrUndefined(value: Json): RecordValue | undefined {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : undefined;
}
