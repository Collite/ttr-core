# Render fixture

Synthetic Hartland-shaped world; not a vendored Hartland snapshot. report.yaml equals ttr-demo
contracts §5 byte for byte. template.xlsx is TemplateGenerator's output through `ttr-report new`,
with the bilingual titles and explicit `--role kantheon-role-finance`.
Template SHA256: `c9fdc8e4aaeab04e6329be99c28e5501da765a1aff64d07e0f9bfdbdba7c9165`.
The identical YAML is pinned in ttr-report's generator test resources.

expected.xlsx pins the render at UTC run date 2026-10-23; SHA256
`c614e1cea886d807f686c31dbb8e28727df27af237ad49703bb5e33b4605aea7`.
This intentionally replaces S2.2's hand-made template/render golden with S2.3's generated layout.
expected.html is unchanged and includes the CLI's trailing newline.
No SQL is executed; rows.csv supplies the result.

P2 exit correction: regenerated after inline-string/height/type fixes. Tests inspect persisted XML,
reopened cell values/types and automatic row heights before byte comparison. Template and render
opened in Microsoft Excel without repair; wrapped rows are visible and SUM(E4:E5) = 21.5.
