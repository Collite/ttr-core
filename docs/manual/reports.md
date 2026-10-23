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

## CLI work to follow

The format/fill stage supplies the library. The following commands are planned in subsequent stages:

```sh
# S2.2: offline CSV rows, or a query-door runner when its published contract is available
ttr report render <root> <id> --rows ROWS=rows.csv --out report.xlsx
# S2.3: seed report.yaml and a deterministic one-sheet workbook from output columns
ttr report new --from er.query.report_returns_review_q --id returns_review_q --title "Returns review"
```

The service and CLI use the same fill; the service adds template sourcing, identity, roles and history.
