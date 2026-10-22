# Changelog

## Unreleased

- Avoid duplicate indexing/linting on document open when the host does not load manifests
  asynchronously (including the browser worker). The document manager already fires content changed.

- Accept `modeler.toml` text in `modeler/setProjectRoot` for browser workers;
  return the resolved manifest and warn once per root when it is omitted.
- Add `modeler/listSchemaSubjects` with object and package counts.
- Page `modeler/listSymbols` with `{ symbols, total, nextCursor? }` instead of
  an array capped at 500. Preserve `kinds` and `includeMembers`; add singular
  `kind` and exact `package` filters. Hosts must read until `nextCursor` is absent.
- Resolve `[bindings].required` (default false) for the upcoming edit synthesizer;
  report unknown binding configuration keys as manifest warnings.
