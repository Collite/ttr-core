# TTR Report CLI

Standalone `bin/ttr-report new [root] --from <query> --id <id> --title "<en>"` creates the pair.
`bin/ttr-report render <root> <id> --rows ROWS=file.csv --out report.xlsx`.
Use `--html` for the first table on stdout. See [reports](../../../docs/manual/reports.md).
`just build-report-cli` builds `build/install/ttr-report/` under this module.

The CLI loads one permissive metadata snapshot, compiles named queries through the existing
translator to validate output columns, and passes CSV rows to the shared report core. It executes
no SQL. `--door` awaits a published `server-proto` with RunSavedQuery: [ttr-server#286](https://github.com/Collite/ttr-server/issues/286).
The grammar bundle prepares Maven `org.tatrman:ttr-report-cli` plus its `dist` ZIP and tar. RCs publish on the PR tag to GH Packages; Central waits for the next batched release.

Tracking: [SO-core](../../../../project/platform/features/studio/implementation/ttr-core/).

`new --title-lang LANG=title` supports additional languages. Explicit output folders must match `--id`.
Generated numeric query outputs remain numeric Excel cells; text identifiers retain leading zeroes.
Shared program results are reused across sheets; offline CSV inputs remain scoped by anchor.
