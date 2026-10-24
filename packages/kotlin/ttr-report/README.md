# TTR Report

Open `report.yaml` v1 reader, model validation, deterministic XLSX fill and HTML-table preview.
Package: `org.tatrman.report`; Apache-2.0. No query runtime or investment logic. `ProgramRunner` is the host seam; `RenderService` binds parameters, resolves scalars, projects/caps tables and calls the same fill. A `Caller` carries host-provided identity without adding authentication policy.

See [the reports manual](../../../docs/manual/reports.md) and the shared
[JSON Schema](../../package-sdk/schemas/report.schema.json). Run `just test-report`.
The module is prepared for the grammar release bundle; no artifact has been published by S2.2.

Implementation tracking: [SO-core](../../../../project/platform/features/studio/implementation/ttr-core/).
