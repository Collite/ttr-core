# TTR Report CLI

Standalone `bin/ttr-report render <root> <id> --rows ROWS=file.csv --out report.xlsx`.
Use `--html` for the first table on stdout. See [reports](../../../docs/manual/reports.md).
`just build-report-cli` builds `build/install/ttr-report/` under this module.

The CLI loads one permissive metadata snapshot, compiles named queries through the existing
translator to validate output columns, and passes CSV rows to the shared report core. It executes
no SQL. `--door` awaits a published `server-proto` with RunSavedQuery: [ttr-server#286](https://github.com/Collite/ttr-server/issues/286).
The grammar bundle prepares Maven `org.tatrman:ttr-report-cli` plus its `dist` ZIP; actual publication is S2.3.

Tracking: [SO-core](../../../../project/platform/features/studio/implementation/ttr-core/).
