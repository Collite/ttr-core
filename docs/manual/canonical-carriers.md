# Canonical metadata declarations (grammar 0.21)

Query execution policy and parameter filling can be declared in TTR-M and retained by every parser,
formatter and metadata export. These fields are optional. A missing query policy defaults to false;
reading or writing these declarations does not execute SQL or change a host's authorization policy.
The consuming host decides how it enforces a declaration.

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
is an ordered list of model attribute references, using identifiers rather than quoted strings. Procedure
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
