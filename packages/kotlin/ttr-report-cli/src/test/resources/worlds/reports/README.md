# Render fixture

Synthetic Hartland-shaped world; not a vendored Hartland snapshot. report.yaml equals ttr-demo
contracts §5 byte for byte. template.xlsx is TemplateGenerator's output through `ttr-report new`,
with the bilingual titles and explicit `--role kantheon-role-finance`.
Template SHA256: `72eb9de80820196aedd4e0a5c68f469b8accb06b837128b873537e3e7003129d`.
The identical YAML is pinned in ttr-report's generator test resources.

expected.xlsx pins the render at UTC run date 2026-10-23; SHA256
`df78c60ab2c70e3bd6e9043474a47c77927b26fd8d692acbb361b0478c182667`.
This intentionally replaces S2.2's hand-made template/render golden with S2.3's generated layout.
expected.html is unchanged and includes the CLI's trailing newline.
No SQL is executed; rows.csv supplies the result.
