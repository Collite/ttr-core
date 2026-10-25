# Reports

A report is an open `report.yaml` v1 declaration beside an authored `template.xlsx`, under
`model/reports/<id>/`. One program supplies the named output columns. The XLSX workbook controls
presentation; the same rows can be previewed as an escaped HTML table.

```yaml
id: returns_review_q
title: { en: "Returns review, this quarter", cs: "Přehled reklamací, toto čtvrtletí" }
version: 1
program: er.query.report_returns_review_q
params: []
refresh: daily
sheets:
  - sheet: Summary
    scalars: { title: "{{title}}", as_of: "{{run.date}}" }
    tables: { ROWS: { columns: [category, requests, received, open, amount] } }
history: { keep: 90d }
roles: [kantheon-role-finance]
```

The [JSON Schema](../../packages/package-sdk/schemas/report.schema.json) is also exported as
`@tatrman/package-sdk/schemas/report.schema.json` and packaged in `org.tatrman:ttr-report`.
Unknown keys are errors, including keys inside parameters, pickers, sheets, tables and history.
The YAML 1.2 Core reader rejects duplicate keys, collection aliases, custom object tags and extra documents.
Plain `NO` and `12:30` remain text; `0017` is decimal 17. Quote `"0017"` to retain leading zeroes.
Defaults must match their declared kind; invalid defaults refuse with `parameter-invalid` before execution.

The id must match its folder and use lowercase letters, digits and underscores, starting with a letter.
Titles are locale maps and must include the manifest's preferred language (English when none is supplied).
Sheet names and parameter names must be unique. `refresh` is `daily`, `hourly` or `manual`; it describes
presentation, rather than scheduling execution. Omitted history defaults to `90d`; `keep` is a positive
number of days. Empty or omitted roles means anyone who can see the module. The consuming service
enforces roles and retention; the fill library operates only on bytes and rows.

Two program lanes are supported:

- `er.query.<name>` names a logical query. It requires `params: []`: logical-query parameters are outside
  LQ v1. A report cannot add parameters that the stored query does not accept.
- `db.query.<name>` names a physical query. Its parameters declare `name`, `kind` (`text`, `int`, `float`,
  `bool`, `datetime` or `entity`), optional `required`, scalar `default`, localized `label`, and a
  `picker: { program, label?, value? }`. Its parameter names must match the query declarations.
  Unknown names are `parameter-invalid`; omitted query declarations are `parameter-missing`.

Each table's `columns` is a nonempty, ordered subset of the program's output column names. A positive
`limit` may cap its rows. `ReportValidator.validate(spec, model)` checks the program and columns through
`ModelLookup`. Query adapters supply `declaredParameters(qname)`; its default describes a parameterless query. It also checks picker programs and their label/value columns.
Validation uses names, so a missing output cannot silently become a blank column.

## Workbook anchors

Create the workbook sheet named by `sheet`. Put headings above a table marker; table data starts at
the marker row and expands downward. Rows below the marker, including footers and later tables, move
with the expansion. Tables must be stacked vertically; two table markers on the same row are refused.

- `{{title}}` or another scalar anchor may occupy all or part of a string cell. The data source resolves
  the declaration's scalar expressions (`{{title}}`, `{{run.date}}`, `{{param.<name>}}`) before filling.
  Unknown scalar placeholders become empty text. Every scalar supplied in `ReportData` must occur in
  the workbook; a supplied anchor without a matching cell is `anchor-missing`.
- `{{table:ROWS|category,requests,received,open,amount}}` occupies one whole cell. Its projection selects
  columns by name, in that order. `{{table:ROWS}}` prints all data columns in their supplied order.
  Missing data/anchors are `anchor-missing`; unknown projected columns are `column-missing`, including
  when there are zero rows. A partial or malformed table marker is `template-invalid`.
- Put the font, border and number/date formats on the marker row's cells. Expanded rows inherit each
  column's authored style and a manually set marker-row height (`customHeight=true`). A stored auto-fit
  height without that flag is discarded. Automatic height stays automatic,
  allowing wrapped data to grow; untouched text cells retain their rich-text runs and fonts. General/text styles keep values as text, including
  leading zeroes and formula-looking strings. Explicit number/date styles produce numeric/date cells
  when the value parses; null and empty values produce blank cells. An unstyled column can borrow
  appearance from the marker but remains text. Empty data clears the marker and leaves the header.

The fill clears created/modified/last-printed, creator, last-modifier and application/version properties.
It sorts ZIP parts and fixes their timestamps; uncompressed ZIP records avoid compressor-version drift.
The same template and data produce identical bytes. This determinism does not remove authored content,
formulas, formatting or meaningful custom workbook properties.

## Kotlin API

```kotlin
val spec = ReportYaml.read(yamlText, folderName = "returns_review_q", preferredLanguage = "en")
val issues = ReportValidator.validate(spec, modelLookup)
require(issues.isEmpty()) { issues.joinToString { it.message } }
val table = Table(columns = listOf("category", "amount"), rows = listOf(listOf("Returns", "12.50")))
val bytes = XlsxFill.fill(templateBytes, ReportData(scalars = mapOf("title" to "Returns"), tables = mapOf("ROWS" to table)))
val preview = HtmlTable.render(table) // first 500 rows, with a truncation note when needed
```

`ReportException.code` carries the stable refusal category. `ReportData` contains resolved scalar strings
and named `Table(columns, rows)` values; each row has exactly one nullable string per named column.
`HtmlTable` preserves that column order, escapes both headings and cells, and emits no scripts or styles.

## Render a report

Build the standalone distribution with `just build-report-cli`. Its launcher is
`packages/kotlin/ttr-report-cli/build/install/ttr-report/bin/ttr-report`.
The design shorthand is `ttr report render`; this distribution uses **`ttr-report render`**.
There is no shared umbrella launcher (other modules' `ttr` binaries are separate applications).

```sh
ttr-report render <root> <id> --rows ROWS=rows.csv --out report.xlsx
ttr-report render <root> <id> --rows ROWS=rows.csv --html
# Repeat --rows for additional anchors; CSV paths are relative to the current directory.
ttr-report render . monthly --param year=2026 --rows ROWS=rows.csv
```

The checkout must contain `modeler.toml`, `model/`, and `model/reports/<id>/report.yaml`.
XLSX output also requires `template.xlsx`. The CLI loads a permissive metadata snapshot and uses
Tatrman's existing query compiler to obtain output column names (including aliases, aggregates,
stars and saved-query dependencies). It executes no SQL. An unavailable or invalid program, or a
column missing from the snapshot or CSV result, refuses the render. Each anchor reads the report's
single `program`; a per-anchor `program` override refuses with `anchor-program-unsupported`.

CSV requires a header with unique nonblank column names and the same number of cells in every row.
Quoted commas, CR/LF inside quoted fields, doubled quotes and a UTF-8 BOM are supported. Physical blank
lines outside quoted fields are skipped; a quoted empty field is a real data row. Empty cells
are empty text, not a separate SQL NULL representation; a header-only file is an empty table.
Columns are selected in the declaration's order; each table's `limit` applies before filling.
The same anchor name may be used on separate sheets with different projections, limits and scalars.

`--param k=v` binds a declared parameter. Unknown names, duplicates, missing required values and invalid
values are refused. Defaults bind when an input is omitted; an optional parameter with no value is
left unbound. Integers are signed 64-bit values, floats must be finite, booleans are `true` or `false`,
and datetime values must be ISO instants with an offset (normalized to UTC). Entity values remain text,
including leading zeroes. Parameter and result values are data; they are never interpolated into SQL.

Scalar expressions resolve `{{title}}` in the preferred language, `{{run.date}}` as the current UTC
date, and `{{param.<name>}}` as the bound value (or empty for an omitted optional parameter).
Unknown expression variables refuse with `scalar-invalid`; supplied values are not expanded a second
time. Workbook placeholders still follow the lower-level fill rules above. Deterministic XLSX bytes
require the same resolved data, including the run date; library callers can inject a `Clock`.

XLSX defaults to `<id>.xlsx` in the current directory. The CLI renders completely before atomically
replacing the output; a validation or execution refusal leaves an existing output untouched. Existing
POSIX file permissions are preserved; new files use normal 0666 permissions filtered by the process umask.
`--html` writes the first declared table to stdout, escapes all cells, and previews at most 500 rows;
it needs no workbook and cannot be combined with `--out`. A declaration without tables refuses with
`table-missing`. Missing files are `report-missing`, other IO failures are `io-error`, corrupt workbooks
are `template-invalid`, and cell data over Excel's 32,767-character limit is `value-too-long`. Exit codes: `0` success/help, `1` report/model/
file failure, `64` usage error. Coded failure messages go to stderr.

`--door` and `--bearer` are reserved and currently refused. ttr-server publishes `server-proto`, but
released Central 0.11.9 and GH Packages 0.11.14 lack the newer `RunSavedQuery` RPC. The open door adapter
waits for a suitable published artifact: [ttr-server#286](https://github.com/Collite/ttr-server/issues/286).
A platform can supply its own `ProgramRunner`; the renderer does not infer a lane or execute SQL by text.

```kotlin
val runner = RowsProgramRunner(mapOf("ROWS" to Path.of("rows.csv")))
val service = RenderService(modelLookup, preferredLanguage = "en", clock = Clock.systemUTC())
val bytes = service.render(root, "returns_review_q", emptyMap(), runner)
```

`ProgramRunner.run(program, params, caller)` receives typed values (`Long`, `Double`, `Boolean`,
`Instant`, or text). `forAnchor(name)` selects an offline CSV; its default returns the same runner,
and `RenderService` executes each selected runner once per render, reusing its result for all projections
and sheets. A remote adapter therefore executes the named program once. The CSV runner returns a
stable distinct runner per anchor, preserving separate offline files and reading each only once. The optional `Caller` is supplied
by the host. Authentication, role enforcement and history are the consuming service's responsibility.

## From a query

`new` reads a saved query from one local metadata snapshot and generates the same pair the Platform
BFF can create through the library. The optional checkout root defaults to the current directory.

```sh
ttr-report new . --from er.query.report_returns_review_q --id returns_review_q \
  --title "Returns review, this quarter" --title-cs "Přehled reklamací, toto čtvrtletí" \
  --role kantheon-role-finance
# Optional: --out <directory named returns_review_q> and --force
# Additional title languages: --title-lang de="Retouren" (repeat for each language)
```

The default destination is `<root>/model/reports/<id>/`. `report.yaml` uses the query's ordered output
columns, `refresh: daily`, `history: { keep: 90d }`, one `Summary` sheet and one `ROWS` table.
Roles default to `[]`; pass each intended role with `--role`. The finance role above is an explicit
demo input. The title must include the manifest's preferred language; `--title-lang LANG=title` supports
additional languages. An explicit output directory's final name must equal `--id` (`id-mismatch`).

The generated workbook has Calibri 11, a bold 14-point `{{title}}` at A1, `{{as_of}}` at A2, bold column
headings at row 3 and the table marker at A4. Widths are header length plus two, clamped to 10–80
characters. Every data column has a styled marker-row cell: compiled integer/decimal output types
get number formats (decimals show at least one fractional digit, e.g. `20.0`), DATE outputs use
`yyyy-mm-dd`, timestamps include time, and text retains leading zeroes. Generated cells wrap text
with automatic row height. The pure `generate` API accepts a `columnTypes` map; absent/unknown types
stay text. `fromQuery` obtains types through `QueryModelLookup.outputTypes(qname)`. The CLI shows an output as
a date when every value of it comes unchanged from columns declared `date` in the snapshot: through aliases,
filters, sorting, either side of a join (outer joins included), group keys, `MIN`/`MAX` of such a column,
saved-query passthroughs, and set operations whose every branch qualifies. Any other timestamp output keeps
its time, including computed values and set operations with a constant or function branch. An explicit
`CAST(... AS DATE)` also selects the date format. The rule reads the compiled query, so identity expressions
the compiler folds away count as passthroughs. Other `QueryModelLookup` adapters must apply the same rule.
The display rule is conservative: if the compiler replaces an empty filtered result with a value-only plan, or a
set operation includes a literal NULL branch, source-date precision is not recovered and the timestamp format remains.

Properties and ZIP records use the same determinism pass as fill. The same inputs produce the same YAML and XLSX
bytes; locale-map insertion order does not affect them.

ER queries with declared parameters refuse with `query-has-parameters` (LQ-5). DB query declarations
become required parameters: text/string/varchar → `text`, integer types → `int`, decimal/float types →
`float` (including `number`), bool/boolean → `bool`, and date/datetime/timestamp (including
`timestamptz` and `smalldatetime`) → `datetime`. A time of day (`time`) remains `text`, since a report
`datetime` requires an instant with a date and offset. An unknown type becomes `text`
and emits `parameter-type-unknown` on stderr. No SQL is executed and no values are guessed.

Columns must be unique and fit the workbook/table-marker syntax; names containing commas, braces,
pipes, line breaks, or leading/trailing whitespace refuse with `generator-invalid`. Other YAML-sensitive
names and titles are safely quoted. Invalid declarations retain the reader's `report-invalid` code.

Both files are generated before writing. Either existing output refuses with `output-exists` unless
`--force` is given. `--force` replaces only these two regular files and retains other directory contents;
symlinks and non-file outputs refuse with `output-invalid`. Both files are staged first, with rollback
on an ordinary replacement IO failure. Two file replacements are not a crash-atomic directory swap.
Exit codes are the same as render: 0 success/help, 1 report/model/file failure, 64 usage.

```kotlin
val generated = TemplateGenerator.generate(
    program = "db.query.search", columns = listOf("label"),
    params = listOf(QueryParameter("needle", "text")),
    id = "search", title = mapOf("en" to "Search"),
)
// generated.yaml, generated.xlsx, generated.warnings
val pair = TemplateGenerator.fromQuery(queryModelLookup, "er.query.report_returns_review_q",
    "returns_review_q", mapOf("en" to "Returns review"))
```

`QueryModelLookup` requires `declaredParameters(qname)` and supplies `outputTypes(qname)` for typed generation. The CLI supplies a
`SnapshotModelLookup`; the Platform supplies a Veles-backed adapter for that same interface. `generate`
and `fromQuery` have no filesystem, metadata, wire or service dependency. The caller persists the pair
and handles warnings; parameter values and execution stay in the renderer/runner path.
