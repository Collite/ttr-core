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

Run `./gradlew :packages:kotlin:ttr-report-cli:test` for unit tests. The shipped-jar logging and
fresh-process checks run separately with `:packages:kotlin:ttr-report-cli:distTest`, which builds
`installDist` and is included in `check`/`build`. The process test invokes Java against `<dist>/lib/*`
on all platforms, using the main class and default JVM arguments from `application {}`.
`just test-report` runs both report suites and these distribution checks; ordinary `just test` stays a unit gate.

The distribution spec has no shared test-resource root. For an IDE runner, build `installDist` and supply these VM options:

- `-Dttr.report.dist=<installDist directory>`
- `-Dttr.report.world=<module>/src/test/resources/worlds/reports`
- `-Dttr.report.main=org.tatrman.report.cli.MainKt` (the configured application main class)
- `-Dttr.report.jvmArgs=[]` (the configured default JVM arguments as a JSON array)

Only `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` are removed from the child JVM's environment.
The direct Java invocation does not read the start script's `JAVA_HOME`, `JAVA_OPTS` or `TTR_REPORT_OPTS` settings.
