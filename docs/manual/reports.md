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
The YAML reader rejects duplicate keys, collection aliases, custom object tags and extra documents.

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
  `picker: { program, label?, value? }`. Quote datetime and ambiguous YAML scalar defaults.

Each table's `columns` is a nonempty, ordered subset of the program's output column names. A positive
`limit` may cap its rows. `ReportValidator.validate(spec, model)` checks the program and columns through
the two-method `ModelLookup` interface. It also checks picker programs and their label/value columns.
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
  column's authored style and the marker row's height. General/text styles keep values as text, including
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
Quoted commas, CR/LF inside quoted fields, doubled quotes and a UTF-8 BOM are supported. Empty cells
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
replacing the output; a validation or execution refusal leaves an existing output untouched.
`--html` writes the first declared table to stdout, escapes all cells, and previews at most 500 rows;
it needs no workbook and cannot be combined with `--out`. Exit codes: `0` success/help, `1` report/model/
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
so a remote adapter runs the same named program for each anchor. The optional `Caller` is supplied
by the host. Authentication, role enforcement and history are the consuming service's responsibility.

## Template generation to follow

S2.3 will add `ttr-report new --from er.query.report_returns_review_q --id returns_review_q --title "Returns review"`,
which seeds the declaration and a deterministic one-sheet workbook from the program's output columns.
