# Render fixture

Synthetic Hartland-shaped world; not a vendored Hartland snapshot. report.yaml equals ttr-demo
contracts §5 byte for byte. template.xlsx is TemplateGenerator's output through `ttr-report new`,
with the bilingual titles and explicit `--role kantheon-role-finance`.
Template SHA256: `e70ebf7eb85288394ab86031b1673a119ea23d4dfb7dc5835b1356b1a300af15`.
The identical YAML is pinned in ttr-report's generator test resources.

expected.xlsx pins the render at UTC run date 2026-10-23; SHA256
`613eee8f756cb6466880329a0003f458d248c54ebcef0eb7d547220805e54cce`.
This intentionally replaces S2.2's hand-made template/render golden with S2.3's generated layout.
expected.html is unchanged and includes the CLI's trailing newline.
No SQL is executed; rows.csv supplies the result.

P2 exit correction: regenerated after inline-string/height/type fixes. Tests inspect persisted XML,
reopened cell values/types and automatic row heights before byte comparison. Template and render
opened in Microsoft Excel without repair; wrapped rows are visible and SUM(E4:E5) = 21.5.

Review-133: decimals use 0.0############## (20.0 rather than 20.); DATE outputs use yyyy-mm-dd.
Stored auto-fit row heights are discarded unless customHeight is true.
