// SPDX-License-Identifier: Apache-2.0
// Target-neutral grammar vocabulary for canonical YAML. Unlike PROPERTY_MAP,
// this follows actual productions, literal keys, nested bodies and entry rules.
export function stripComments(source) {
  return source.replace(/'(?:\\.|[^'\\])*'|\[(?:\\.|[^\]\\])*\]|\/\*[\s\S]*?\*\/|\/\/[^\n]*/g,
    (part) => part.startsWith('//') || part.startsWith('/*') ? part.replace(/[^\n]/g, ' ') : part);
}

export function readGrammar(source) {
  const version = /^\/\/\s*@grammar-version:\s*(\d+\.\d+)\s*$/m.exec(source)?.[1];
  if (!version) throw new Error('Missing @grammar-version marker');
  const clean = stripComments(source);
  const rules = {};
  const literals = {};
  const pattern = /^\s*([A-Za-z_]\w*)\s*:\s*/gm;
  for (let match; (match = pattern.exec(clean));) {
    let end = pattern.lastIndex;
    let quote = false;
    let bracket = false;
    for (; end < clean.length; end++) {
      const c = clean[end];
      if (c === '\\') { end++; continue; }
      if (!bracket && c === "'") quote = !quote;
      if (!quote && c === '[') bracket = true;
      if (!quote && c === ']') bracket = false;
      if (!quote && !bracket && c === ';') break;
    }
    if (end === clean.length) throw new Error(`Unterminated grammar rule ${match[1]}`);
    let raw = clean.slice(pattern.lastIndex, end).trim();
    if (/^[a-z]/.test(match[1])) {
      // Canonical spacing is independent of comments/line wrapping. Validate
      // before joining so a new operator cannot be silently discarded.
      expression(raw);
      raw = raw.match(/[A-Za-z_]\w*|[()|?*+]/g).join(' ');
    }
    rules[match[1]] = raw;
    if (/^[A-Z]/.test(match[1]) && /^(?:'[^']*')(?:\s*\|\s*'[^']*')*$/.test(raw)) {
      literals[match[1]] = [...raw.matchAll(/'([^']*)'/g)].map((m) => m[1]);
    }
    pattern.lastIndex = end + 1;
  }
  return { version, rules, literals };
}

export function expression(text) {
  const tokens = text.match(/[A-Za-z_]\w*|[()|?*+]/g) ?? [];
  if (tokens.join('') !== text.replace(/\s/g, '')) throw new Error(`Unhandled grammar expression: ${text}`);
  let i = 0;
  function alternative() {
    const options = [sequence()];
    while (tokens[i] === '|') { i++; options.push(sequence()); }
    return options.length === 1 ? options[0] : { alt: options };
  }
  function sequence() {
    const seq = [];
    while (i < tokens.length && tokens[i] !== ')' && tokens[i] !== '|') {
      let node;
      if (tokens[i] === '(') {
        i++; node = alternative();
        if (tokens[i++] !== ')') throw new Error(`Unbalanced grammar expression: ${text}`);
      } else {
        const name = tokens[i++];
        if (!/^[A-Za-z_]/.test(name)) throw new Error(`Unexpected grammar token: ${name}`);
        node = { symbol: name };
      }
      if (['?', '*', '+'].includes(tokens[i])) node = { repeat: tokens[i++], node };
      seq.push(node);
    }
    return seq.length === 1 ? seq[0] : { seq };
  }
  const result = alternative();
  if (i !== tokens.length) throw new Error(`Unexpected grammar suffix: ${text}`);
  return result;
}
export const alternatives = (node) => node.alt ?? [node];
export const sequence = (node) => node.seq ?? [node];
export function symbols(node) {
  if (node.symbol) return [node.symbol];
  if (node.repeat) return symbols(node.node);
  return (node.seq ?? node.alt).flatMap(symbols);
}

const FREE_ENTRIES = {
  propertyEntry: { key: 'key', value: 'value' },
  cnEntry: { key: 'cnKey', value: 'cnValue' },
  autoEntry: { key: 'autoKey', value: 'autoValue' },
};
const word = '[A-Za-z_\\u00C0-\\u024F][A-Za-z0-9_\\u00C0-\\u024F]*';
const regexEscape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export function deriveMetadata(source) {
  const grammar = readGrammar(source);
  const { rules, literals } = grammar;
  const parsed = {};
  const parse = (name) => {
    if (!rules[name]) throw new Error(`Unrecognized grammar rule: ${name}`);
    return parsed[name] ??= expression(rules[name]);
  };
  const keywordWords = [...new Set(Object.values(literals).flat().filter((s) => /^[A-Za-z_]\w*$/.test(s)))].sort();
  function idPattern(rule = 'id') {
    let allowed;
    let dotted = true;
    if (rule === 'cnKey') { allowed = new Set(symbols(parse('cnKey'))); dotted = false; }
    else {
      allowed = new Set(symbols(parse('idPart')));
      if (rule === 'autoKey') for (const t of symbols(parse('autoKey'))) allowed.add(t);
      if (rule === 'cnObjectEntry') { allowed.add('DATA_TYPE'); allowed.add('PRECISION'); }
    }
    const excluded = keywordWords.filter((w) => !Object.entries(literals).some(([t, values]) => allowed.has(t) && values.includes(w)));
    const part = `(?!(?:${excluded.map(regexEscape).join('|')})(?:\\.|$))${word}`;
    return dotted ? `^(?:${part})(?:\\.${part})*$` : `^(?:${part})$`;
  }
  const bodies = {};
  function tokenKeys(node) {
    return alternatives(node).flatMap((n) => {
      if (!n.symbol || !literals[n.symbol]?.length) throw new Error(`Unhandled property key ${JSON.stringify(n)}`);
      return literals[n.symbol];
    });
  }
  function member(option) {
    const parts = sequence(option);
    if (parts[0]?.symbol !== 'DEF') return null;
    const [kind] = tokenKeys(parts[1]);
    const named = parts[2]?.symbol === 'id';
    const body = parts[named ? 3 : 2]?.symbol;
    if (parts.length !== (named ? 4 : 3) || !body) throw new Error(`Unhandled nested definition: ${JSON.stringify(option)}`);
    return { kind, body, named };
  }
  function body(name) {
    if (bodies[name]) return bodies[name];
    const expr = parse(name);
    const parts = sequence(expr);
    if (parts[0]?.symbol !== 'LBRACE' || parts.at(-1)?.symbol !== 'RBRACE') {
      throw new Error(`Unhandled definition body ${name}`);
    }
    const result = bodies[name] = { rule: name, properties: [], freeForm: [], members: [], ordered: false };
    const visiting = new Set();
    function properties(rule) {
      if (visiting.has(rule)) return;
      visiting.add(rule);
      if (FREE_ENTRIES[rule]) {
        result.freeForm.push({ rule, ...FREE_ENTRIES[rule] });
        result.ordered ||= rule !== 'propertyEntry';
        return;
      }
      if (rule === 'worldSchemaField') {
        result.fields = { key: 'id', value: 'dataType' };
        return;
      }
      for (const option of alternatives(parse(rule))) {
        const nested = member(option);
        if (nested) { result.members.push(nested); body(nested.body); continue; }
        const seq = sequence(option);
        if (symbols(seq[0]).every((s) => /^[A-Z]/.test(s)) && symbols(option).includes('propSep')) {
          const keys = tokenKeys(seq[0]);
          const value = seq.slice(1).filter((n) => n.symbol !== 'propSep' && !(n.repeat === '?' && n.node.symbol === 'propSep'));
          for (const key of keys) result.properties.push({ key, rule, value: value.length === 1 ? value[0] : { seq: value } });
        } else {
          const refs = [...new Set(symbols(option).filter((s) => /^[a-z]/.test(s)))];
          if (!refs.length) throw new Error(`Unhandled property alternative in ${rule}`);
          refs.forEach(properties);
        }
      }
    }
    const interior = parts.slice(1, -1);
    const empty = (node) => node.repeat ? node.repeat !== '+' || empty(node.node) : node.seq ? node.seq.every(empty) : node.alt ? node.alt.some(empty) : false;
    if (!interior.every(empty) || interior.flatMap(symbols).some((symbol) => /^[A-Z]/.test(symbol) && symbol !== 'COMMA')) throw new Error(`Unhandled definition body shape ${name}`);
    const refs = [...new Set(interior.flatMap(symbols).filter((s) => /^[a-z]/.test(s)))];
    refs.forEach(properties);
    if (!result.properties.length && !result.freeForm.length && !result.members.length && !result.fields) {
      throw new Error(`Empty/unhandled definition body ${name}`);
    }
    return result;
  }
  const kinds = alternatives(parse('objectDefinition')).map((option) => {
    const parts = sequence(option);
    if (parts.length !== 3 || parts[1]?.symbol !== 'id' || !parts[2]?.symbol) throw new Error('Unhandled objectDefinition alternative');
    const [kind] = tokenKeys(parts[0]);
    const rule = parts[2].symbol;
    body(rule);
    return { kind, body: rule };
  });
  for (const name of ['cnBody', 'bindingBlock', 'searchBlock']) body(name);
  // Parameter records have no outer Definition identity.
  const parameter = { rule: 'parameterInline', properties: [], freeForm: [], members: [], ordered: false };
  // Its grammar wrapper is a brace body like every other typed property record.
  Object.assign(parameter, body('parameterInline'));
  const nestedKinds = [...new Map(Object.values(bodies).flatMap((b) => b.members).concat(member(parse('stepInline'))).map((m) => [`${m.kind}/${m.body}`, m])).values()];
  const models = symbols(parse('modelCode')).flatMap((token) => literals[token] ?? []);
  if (!models.length || new Set(kinds.map((k) => k.kind)).size !== kinds.length) throw new Error('Invalid grammar vocabulary');
  return {
    version: grammar.version,
    models,
    kinds,
    nestedKinds,
    bodies,
    identifiers: { id: idPattern(), cnKey: idPattern('cnKey'), autoKey: idPattern('autoKey'), cnObjectKey: idPattern('cnObjectEntry') },
    rules,
    literals,
  };
}
