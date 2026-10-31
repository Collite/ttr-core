# Canonical metadata declarations (grammar 0.21)

Query execution policy and parameter filling can be declared in TTR-M and retained by every parser,
formatter and metadata export. These fields are optional. A missing query policy defaults to false;
reading or writing these declarations does not execute SQL or change a host's authorization policy.
A TTR-loaded query can now explicitly carry `skipSecurity: true`. Hosts must gate exemptions through
their own authorization policy; TTR provenance alone no longer implies false. Model conversion and
writers emit `skipSecurity`, `defaultValue` and `binds` when present; text containing these keys requires
a grammar ≥0.21 reader.

```ttr
model db schema demo

def query lookup {
    skipSecurity: false,
    parameters: [
        { name: term, type: text, defaultValue: "", binds: [er.entity.Customer.name, er.entity.Customer.code] }
    ],
    sourceText: "select 1"
}
```

`skipSecurity` is a boolean. The AST distinguishes absence from explicit false. `defaultValue` is a string:
an empty string is an explicitly supplied default, while an omitted property supplies no default. `binds`
is an ordered list of model attribute references, using identifiers rather than quoted strings. TTR
text accepts both `Customer.name` and `er.entity.Customer.name`. Typed `QueryParameterDef.binds`
keeps the existing `entity.attribute` form; model→TTR emits `er.entity.<entity>.<attribute>` and C-7 resolves
binding ids against ER attributes, preserving unresolved input for consumer diagnostics. Procedure
parameters accept the same metadata; each procedure host may interpret it when that behaviour is supported.

Lexical entries accept `indexSecurity: <identifier>`. The conventional values are `applied` and `none`;
policy interpretation belongs to the consumer. A pattern and its example question share the same entry:

```ttr
model lexicon locale en

def pattern paired {
    for: db.demo.lookup,
    match: "name (.*)",
    text: "Name Alice?",
    indexSecurity: applied
}
```

Use a second named pattern for an alternate regex/question pair. `description` carries its explanation.
Canonical YAML support is implemented in the subsequent MW-P2 stages; this grammar addition does not
make an arbitrary YAML dialect loadable. Existing default-less parameter declarations remain valid.

Canonical lexicon bridges retain `indexSecurity` for supported concept/ER/MD targets. Entries targeting
db objects keep their policy in the parser AST and defs.json; the typed `LexiconLink` model does not
represent db-targeted entries. The existing CN builder also skips the typed lexical tier when a source
has no CN content; AST and defs.json retain such entries.

All STRING_LITERAL fields decode in one left-to-right pass: `\\`→`\`, `\"`→`"`, `\n`→LF, `\t`→TAB,
`\r`→CR. Other `\X` pairs keep both characters: `"^\d{4}"` keeps its regex backslash; `"C:\\new"` reads
as `C:\new`, without turning its `n` into a newline. Writers encode backslash, quote, LF, TAB and CR only;
JSON-only escapes such as `\uXXXX`, `\b` and `\f` are not decoded. Triple strings and tagged blocks keep
their raw content. Layout string readers/writers use the same rule.

JVM consumers must rebuild for the appended data-class fields in `QueryDef`, `LexiconEntryDef`,
`QueryParameterDef` and `LexiconLink` (constructor/copy signatures change).
