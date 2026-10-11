# TTR-B — sentence roster (English and Czech skins)

> The reference for TTR-B sentences: every shape in both keyword skins with the canonical TTR-P it
> lowers to, the markers, blocks, actions, file-backed fragments and the diagnostics. Grammar:
> `packages/grammar/src/TTRB.g4`; keyword tables: `ttrp-frontend/src/main/resources/ttrb/roster.en.yaml`
> and `roster.cs.yaml`; reject tables: `ttrb/rejects.en.yaml`, `ttrb/rejects.cs.yaml`; lowering:
> `ttrp-frontend` `dialect/b/TtrbDecomposer.kt`. Companion: [`language-design.md` §7.3](../language-design.md#73-ttr-b--controlled-sentences)
> and [`contracts.md` §8.3](../architecture/contracts.md#83-ttr-b-sentences-b).

## 1. Skins — one grammar, one keyword table per language

TTR-B has ONE grammar. The grammar declares the keyword token types; a **skin** (a YAML table) spells
them. The English skin serves `"""ttrb` and `*.ttrb`, the Czech skin `"""ttrb-cs` and `*.ttrb-cs`.

- **Keywords** match **case- and diacritic-insensitively** against the ACTIVE skin only — `Pošli` =
  `posli` = `POSLI`; `Load` is not a keyword in a Czech fragment (and `Načti` is not one in an English
  fragment: such a sentence is `TTRP-B-006`).
- A spelling may be several words (`ruční úkol`, `number of`), a hyphenated word (`e-mail`), or stand
  for several tokens (`není` = IS NOT, `jedno z` = ONE OF, `se nerovná` = DOES NOT EQUAL).
- **Identifiers are exact**: Latin letters incl. Latin-1 Supplement + Latin Extended-A/B (the range
  TTR-M and TTR-P use), never folded, never declined. A sentence names a column / table in the form the
  model declares it — `Načti objednávky.` reads the entity `objednávky`; `objednavky` is another name.
- A word a skin spells is reserved in that skin. Czech reserves short function words — `a` (and), `s`/`se`
  (with), `z`/`ze` (from), `k`/`ke`/`na`/`do` (to), `je` (is), `ne` (not), `to` (that), `jako` (as),
  `pro` (for), `po`, `před`, `mezi`, … — so they cannot name a column or a result (`TTRP-B-109`).
  The B4 English words (`count`, `key`, `subject`, `template`, `email`, `set`, `send`, `attach`,
  `attachment(s)`, `department`, `otherwise`, `reason`, `description`) are **soft**: still usable as
  column names.
- Czech aggregate names are skin aliases of the catalogue: `součet` = sum, `průměr` = avg, `počet` =
  count, `minimum` = min, `maximum` = max.

## 2. Markers

| Where | English | Czech |
|---|---|---|
| Embedded fragment | `"""ttrb` | `"""ttrb-cs` |
| Bare file | `*.ttrb`, first line `# ttr: dialect=b` or `# ttr: dialect=b lang=en` | `*.ttrb-cs`, first line `# ttr: dialect=b lang=cs` |
| File-backed container | `container x(…) target e from "rules/x.ttrb"` | `container x(…) target e from "rules/x.ttrb-cs"` |

The first-line header wins over the extension; `dialect=ttrb` is read as `dialect=b`; another
`lang=<xx>` is the dialect `ttrb-<xx>` — an unknown dialect (`TTRP-FRG-001`).

**File-backed fragment** (`from "<path>"`, TTRP.g4 0.3): the file's whole content IS the interior,
byte-preserved; the path is relative to the program file; the dialect comes from the file's extension /
first line (`.ttrb`, `.ttrb-cs`, `.ttr.sql`, `.ttr.py`). Embedded and file-backed fragments lower to
the same statements and the same graph. A finding inside the file carries the file's own line; the
editor publishes it on the `from` clause as `<file>:<line>:<col>: …`, and go-to-definition / the
document link on the clause opens the file. `TTRP-FRG-004` file not found, `TTRP-FRG-005` no dialect
marker.

## 3. The roster

Every sentence ends with `.`. `that` / `to` (and the implicit subject) is the previous sentence's value —
the **current value**; `as <n>` / `jako <n>` binds a name (on Load, Join, Show: the sentence's own
result). To name the current value after any sentence, and to come back to a named value later:

| Czech | English | Lowers to |
|---|---|---|
| `Pojmenuj to\|výsledek jako <jméno>.` / `Nazvi to <jméno>.` | `Call that\|the result <name>.` / `Name that [as] <name>.` | `name = <current chain>` (or `name = <current>`); the current value stays the same, now named |
| `Načti <jméno>.` (a name bound earlier) | `Load <name>.` | that value is current again — a reference, never `load(<name>)` (the same for a container IN port) |

Every value a sentence produces must be read by something — a later sentence, an OUT port, a
display; otherwise the sentence would silently not take effect, and it is `TTRP-B-113` (§5). Typical
case: `Načti zásoba jako z2. Ponech řádky, kde … . Spoj pol se z2 …` — the join names `z2`, the LOAD,
so the filter is lost; write `Pojmenuj to jako z2.` after the filter.

### 3.1 Transform sentences (C4-b)

| Czech | English | Lowers to |
|---|---|---|
| `Načti [ze] souboru "<p>" [se schématem <s>] [jako <n>].` | `Load [from] file "<p>" [with schema <s>] [as <n>].` | `n = load("<p>"[, schema: s])` |
| `Načti [z] <q> [se schématem <s>] [jako <n>].` | `Load [from] <q> [with schema <s>] [as <n>].` | `n = load(q[, schema: s])` (no schema: a derived in-port in a bare file) |
| `Ponech\|Vezmi\|Vyber [jen] sloupce a, b.` | `Keep\|Take\|Select [only] the columns a, b.` | `-> project(a, b)` |
| `Ponech všechny sloupce kromě a, b.` | `Keep all columns except a, b.` | `-> project(a, b)` (see §6) |
| `Ponech\|Vezmi\|Vyber [jen] řádky[,] kde <p>.` / `Filtruj …` | `Keep\|Take\|Select [only] the rows where <p>.` / `Filter for …` | `-> filter(p)` |
| `Odstraň\|Smaž řádky[,] kde <p>.` | `Remove\|Delete the rows where <p>.` | `-> filter(not(p))` |
| `Přejmenuj a na b[, c jako d].` | `Rename a to b[, c as d].` | `-> calc { b = a  d = c }` |
| `Převeď\|Přetypuj a na <typ>.` | `Convert\|Retype a to <type>.` | `-> calc { a = cast(a as <type>) }` |
| `Vytvoř\|Spočti\|Vypočítej [nový sloupec] <výraz> jako <n>.` | `Create\|Compute\|Calculate [new column] <expr> as <n>.` | `-> calc { n = expr }` |
| `Spočti <n> jako <a>, když <p>[, <b>, když <q>…][, jinak <c>].` | `Compute <n> as <a> when <p>[, <b> when <q>…][, otherwise <c>].` | `-> calc { n = case when p then a [when q then b] [else c] end }` (no else ⇒ NULL; the name comes first) |
| `Shrň součet x jako s[, …] podle k[, …].` | `Summarize sum of x as s[, …] by k[, …].` | `-> aggregate { group by k  s = sum(x) }` |
| `Spoj <a>\|to se <b> přes <p> [jako <n>].` | `Join <a>\|that with <b> on <p> [as <n>].` | `n = join(left: a, right: b, on: p, type: inner)` |
| `Spoj <a> volitelně s <b> přes <p> [jako <n>].` | `Join <a> optionally with <b> on <p> [as <n>].` | `join(…, type: left)` — unmatched right columns are NULL |
| `Spoj <a> s <b> přes vazbu <r> [jako <n>].` | `Join <a> with <b> on relation <r> [as <n>].` | `join(…, on: relation r)` (also with `volitelně` / `optionally`) |
| `Ponech jen řádky, které mají protějšek v <b> přes <p>.` | `Keep only the rows that have a match in <b> on <p>.` | `join(left: <current>, right: b, on: p, type: semi)` |
| `Ponech jen řádky, které nemají protějšek v <b> přes <p>.` | `Keep only the rows that have no match in <b> on <p>.` | `join(…, type: anti)` (`Odstraň řádky, které mají protějšek …` / `Remove the rows that have a match …` is the anti join too) |
| `Seřaď [to] podle a [vzestupně\|sestupně][, …].` | `Sort [that] by a [ascending\|descending][, …].` | `-> sort(a, …)` |
| `Ponech [jen] prvních <n> řádků.` | `Keep [only] the first <n> rows.` | `-> limit(n)` |
| `Sluč\|Přidej\|Sjednoť to s <b>.` | `Combine\|Append\|Union that with <b>.` | `-> union(b)` |
| `Ulož to do souboru "<p>" \| do <q>.` | `Store that to file "<p>" \| to <q>.` | `-> store(target)` |
| `Ukaž\|Zobraz [mi] [výsledek] [jako <n>].` | `Show\|Display [me] [the result] [as <n>].` | `n = <chain> -> display(n)` |

**Grouping and precedence.** Parentheses group any sub-condition — in row filters, join conditions,
`Když` / `If` headers and conditional values: `Ponech řádky, kde (a < b nebo c je prázdné) a (d = e
nebo f je prázdné).` Without parentheses `a` / `and` binds tighter than `nebo` / `or`: `p nebo q a r`
is `p nebo (q a r)`; `ne` / `not` binds tighter than both.

**Predicates** (one closed table, both skins fold to the same operators): `je větší\|vyšší než`,
`je více než` / `is more\|bigger\|larger\|higher than` (`>`); `je menší\|nižší než`, `je méně než` / `is
less\|fewer\|lower\|smaller than` (`<`); `není větší než`, `není více než` / `is not more\|bigger… than`
(`<=`); `není menší než`, `není méně než` / `is not less\|smaller… than` (`>=`); `je`, `je rovno`, `se
rovná`, `je stejné jako` / `is`, `is equal [to]`, `equals`, `is [the] same as` (`=`); `není`, `není
rovno`, `se nerovná` / `is not`, `is not equal [to]`, `does not equal` (`<>`); `je [ne]prázdný` /
`is [not] empty` (`is [not] null`); `je jedno z (…)` / `is one of (…)` (`in`); `je mezi a a b` /
`is between a and b`; `a` / `and`, `nebo` / `or`, `ne` / `not`; the operators `> < >= <= = <>`,
`+ - * /`; function calls; numbers, `"…"` / `'…'` strings, `pravda` / `true`, `nepravda` / `false`.

### 3.1a Container ports (B7)

A container's **IN ports are names**: wherever a sentence names a table (Load, Count, Attach, Join,
Combine, the match sentences, Store) an in-port is read as the port — never `load(<port>)`, which names
no model object. `Načti orders.` / `Load orders.` reads the port (no node); `Load orders as o.` names it.

| Czech | English | Lowers to |
|---|---|---|
| `Pošli to\|výsledek\|<jméno> na výstup <port>.` | `Send that\|the result\|<name> to output <port>.` | the declared OUT port `<port>` carries the current (or named) value: `port = <chain>`; the port is then the current value |

The port must be declared in the container header (`TTRP-B-112`); in a bare file each output port
becomes an OUT port shown as a display. Action sentences keep their automatic ports (§3.4).

```ttrp
container predani(in otevrene, in reklam, out velke, out ostatni) target erp """ttrb-cs
Načti otevrene.
Spočítej počet_reklamací jako počet řádků reklam.
Když částka je větší než 1000:
    Pošli to na výstup velke.
Když částka není větší než 1000:
    Pošli to na výstup ostatni.
"""
```

### 3.2 Blocks (B3)

| Czech | English | Lowers to |
|---|---|---|
| `Když\|Pokud\|Jestliže <p>:` + indented sentences | `If\|When <p>:` + indented sentences | `_b = <current> -> filter(p)`; the block's sentences run on `_b` |

Each block is ONE output. After a block the main line resumes on the SAME value, so two blocks filter
the same rows — they **overlap** (a row may satisfy several); they are not else-if. The graph is the
one a hand-written program with one `filter` per output gives. The header ends its line with `:`; the
block's sentences are indented deeper than it (indentation is relative to the fragment's first
sentence; a sentence may wrap over several lines — only sentence-start lines count). A block inside a
block is `TTRP-B-110`.

### 3.3 Count and attach (B4)

| Czech | English | Lowers to |
|---|---|---|
| `Spočítej x jako počet řádků T.` | `Count the rows of T as x.` / `Count x as the number of rows of T.` | `_c = T -> calc { x = 1 } -> aggregate { x = count(x) }`, then `join(left: <current>, right: _c, type: cross)` is the current value |
| `Připoj T k výsledku.` | `Attach T to the result.` | `join(left: <current>, right: T, type: cross)` |

`T` is a bound name, else it is loaded (`load(T)`). The count counts a column it sets to `1` — never
null, so every row counts (the emit paths need a column argument; `count()` alone does not build).

### 3.4 Actions (B4)

An action sentence is a **sink**: `<port> = <current> -> calc { <the schema's columns> } ->
select(<them>)`. The checker adds the container OUT port `<port>` (named after the kind:
`send_email`, then `send_email_2`, …; a header port of that name is reused) and the program-level
wiring `<container>.<port> -> display(<kind>)` — what a hand-written canonical program declares — so
the grammar-0.14 action-display check (`TTRP-DSP-*`) holds the rows to the imported `def schema <kind>`
and its findings land on the sentence. The next sentence reads the same rows (several actions per
block are fine). The kinds and columns are fixed and the same in both skins.

| Czech | English | Kind · columns |
|---|---|---|
| `Pošli e-mail <komu> s předmětem "p", šablonou "t", klíčem <k> [a přílohami a, b].` | `Send an e-mail to <recipient> with subject "p", template "t", key <k> [and attachments a, b].` | `send_email` · `komu = <recipient>, předmět = "p", šablona = "t", klíč = <k>, příloha = "a;b"` |
| `Nastav <atribut> <entita> s klíčem <k> na <v> s důvodem "r".` | `Set <attribute> of <entity> with key <k> to <v> with reason "r".` | `update_field` · `entita = "<entity>", klíč = <k>, atribut = "<attribute>", hodnota = <v>, důvod = "r"` |
| `Vytvoř ruční úkol pro <řešitel> "n" s popisem "d" [a přílohou a].` | `Create a manual task for <recipient> "n" with description "d" [and attachment a].` | `manual_task` · `řešitel = <recipient>, název = "n", popis = "d", příloha = "a"` |

- `klíč` / `hodnota` are a column or a literal; `entita` / `atribut` are NAMES emitted as text.
- Attachments: `přílohou a` / `přílohami a, b` (`attachment a` / `attachments a, b`) — names (or
  quoted names) joined with `;` into one text value; no attachments ⇒ no `příloha` column (optional).
- The template-to-key joint may be `,` or `a` / `and`.

**Recipients** (`<komu>`, `<recipient>`, `<řešitel>`):

| Czech | English | Lowers to |
|---|---|---|
| `<sloupec>` | `<column>` | the column (`email_zástupce`) |
| `oddělení "x"` | `department "x"` | the text `"oddělení:x"` — the host resolves it (same prefix in both skins) |
| `<sloupec>, jinak oddělení "x"` | `<column>, otherwise department "x"` | `coalesce(<column>, "oddělení:x")` |

## 4. A complete rule, both skins

```ttrp
uses world "shop.worlds.host"
import shop.prodej.*
import shop.akce.*            // def schema send_email / update_field / manual_task

container rozhodnuti target erp """ttrb-cs
Načti objednávky.
Spočítej počet_reklamací jako počet řádků reklamace.
Když částka je větší než 1000:
    Pošli e-mail email_zástupce, jinak oddělení "obchod" s předmětem "Velká objednávka", šablonou "velka_objednavka", klíčem číslo a přílohami faktura, dodací_list.
Když stav je "zaplacená" a počet_reklamací je 0:
    Nastav stav objednávky s klíčem číslo na "uvolněná" s důvodem "zaplaceno, bez reklamací".
"""
```

```ttrp
container rozhodnuti target erp """ttrb
Load objednávky.
Count the rows of reklamace as počet_reklamací.
If částka is bigger than 1000:
    Send an e-mail to email_zástupce, otherwise department "obchod" with subject "Velká objednávka", template "velka_objednavka", key číslo and attachments faktura, dodací_list.
If stav is "zaplacená" and počet_reklamací is 0:
    Set stav of objednávky with key číslo to "uvolněná" with reason "zaplaceno, bez reklamací".
"""
```

Both compile to the graph of this canonical program (`ttrp explain` equal modulo generated names;
`ttrp build` gives byte-identical host statements and display entries):

```ttrp
container rozhodnuti(out send_email, out update_field) target erp {
  objednávky = load(objednávky)
  reklamace_celkem = load(reklamace) -> calc { počet_reklamací = 1 } -> aggregate { počet_reklamací = count(počet_reklamací) }
  s_reklamacemi = join(left: objednávky, right: reklamace_celkem, type: cross)
  velke = s_reklamacemi -> filter(částka > 1000)
  send_email = velke
    -> calc { komu = coalesce(email_zástupce, "oddělení:obchod")  předmět = "Velká objednávka"  šablona = "velka_objednavka"  klíč = číslo  příloha = "faktura;dodací_list" }
    -> select(komu, předmět, šablona, klíč, příloha)
  uvolnit = s_reklamacemi -> filter(stav = "zaplacená" and počet_reklamací = 0)
  update_field = uvolnit
    -> calc { entita = "objednávky"  klíč = číslo  atribut = "stav"  hodnota = "uvolněná"  důvod = "zaplaceno, bez reklamací" }
    -> select(entita, klíč, atribut, hodnota, důvod)
}
rozhodnuti.send_email -> display(send_email)
rozhodnuti.update_field -> display(update_field)
```

The same rule kept in a file: `container rozhodnuti target erp from "rules/rozhodnuti.ttrb-cs"`.
Fixtures: `ttrp-frontend/src/test/resources/ttrb-rules/` (gates: `TtrbRulesGraphSpec`, `TtrbRulesCliTest`).

## 5. Diagnostics (`TTRP-B`, reject tables per skin)

Each row of `rejects.en.yaml` / `rejects.cs.yaml` is an id, a message in the skin's language and a
suggested correct sentence; every row of both tables has a fixture that triggers it
(`ttrp-frontend/src/test/resources/ttrb/rejects/<lang>/<id>`). The editor shows the message with
`↳ suggested: <sentence>`; a bare `.ttrb` / `.ttrb-cs` document is checked sentence by sentence (a
full bare program when the project sets `[ttrp] bare-target`).

| Id | When | Detected by |
|---|---|---|
| `TTRP-B-001` | update (`Update` / `Aktualizuj`, `Uprav`) — suggests the Set action or Store | sentence-initial trigger word |
| `TTRP-B-002` | insert (`Insert` / `Vlož`) | trigger word |
| `TTRP-B-003` | DDL (`Drop`, `Truncate`, `Alter` / `Zahoď`, `Vyprázdni`) | trigger word |
| `TTRP-B-004` | an off-roster verb, or a sentence the parser cannot read | catch-all |
| `TTRP-B-005` | `//` or `/*` comments (TTR-B comments are `#`) | token shape |
| `TTRP-B-006` | a sentence in the other skin's language (wrong marker) | the first word is the other skin's verb / reject word |
| `TTRP-B-007` | a comparison not in the closed table (`is roughly` / `je zhruba`) | `is <word> <operand>` |
| `TTRP-B-008` | pivot (`Pivot` / `Pivotuj`, `Překlop`) | trigger word |
| `TTRP-B-101` | a character outside TTR-B (`€`, `;`, `@`) | the lexer's catch-all |
| `TTRP-B-102` | a block header without `:` at its line end, or no indented sentence after it | layout |
| `TTRP-B-103` | a sentence whose indentation matches no open block | layout |
| `TTRP-B-104` | a malformed e-mail sentence | failed parse, sentence starts `Send` / `Pošli` |
| `TTRP-B-105` | a malformed set sentence | … `Set` / `Nastav` |
| `TTRP-B-106` | a malformed manual-task sentence | … `Create a manual task` / `Vytvoř ruční úkol` |
| `TTRP-B-107` | a malformed count sentence | … `Count` / `Spočítej` |
| `TTRP-B-108` | a malformed attach sentence | … `Attach` / `Připoj` |
| `TTRP-B-109` | a keyword where a name belongs (`jako a`) | failed parse at a keyword where a name was expected |
| `TTRP-B-110` | a block inside a block | decomposition |
| `TTRP-B-111` | a malformed output sentence (`Pošli to na velke.`) | failed parse, `Send` / `Pošli` not followed by `e-mail` |
| `TTRP-B-112` | an output to a port the container does not declare | decomposition (the container's OUT ports) |
| `TTRP-B-113` | a value a sentence produces that nothing reads (no later sentence, port, display) — the sentence would silently not take effect | decomposition: an abandoned pending chain, an unread named value, a trailing value with no OUT port |
| `TTRP-EQ-001` | `==` (shared) | token shape |

Priority (one primary diagnostic per fragment): B-005, B-101, EQ-001, trigger words, B-006, B-007,
B-102, B-103, B-004 (sentence-initial word); then the parse: B-109 for a keyword that ENDS a sentence
where a name belongs (`jako a.`, `na výstup výsledek.`), B-102/104…108/111 by the sentence's first word,
B-109, B-004; then B-110 / B-112; then (only on an otherwise clean fragment) B-113.

## 6. Not supported / limits

- **No declension.** Identifiers are exact; a Czech sentence names a column / entity in its declared
  form (`Nastav stav objednávky` emits the entity name `"objednávky"` as written). Keywords carry the
  declined forms the tables list (`řádky`, `řádků`, `výsledek`, `výsledku`, …) — nothing else is
  inflected.
- **No synonyms outside the tables** (P2): a word the table does not spell is an identifier — and, the
  other way round, a NAME that folds to a keyword cannot be used as a name: `vysledek` is the Czech
  `výsledek` (RESULT), `volitelne` is `volitelně` (OPTIONALLY) — `TTRP-B-109`.
- No raw `case when … then … else … end` in a TTR-B expression: the conditional sentence (§3.1) is the
  spelling.
- One block level (`TTRP-B-110`); blocks are filters, not else-if (an "otherwise" branch is a second
  block with the negated condition).
- The action kinds (`send_email`, `update_field`, `manual_task`) and their columns are fixed; a new
  action kind is a grammar + decomposer change.
- Inherited from the pre-skin roster, unchanged: the `as` rename in `Keep … columns a as b` is dropped;
  `Keep all columns except` lowers to a positive `project`; `Rename` copies (`calc`); the sort direction
  is dropped.
- A file-backed fragment is read from disk (an editor buffer with unsaved changes to the fragment file
  is not seen until it is saved).
