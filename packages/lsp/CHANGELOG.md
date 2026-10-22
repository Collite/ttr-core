# Changelog

## Unreleased

- Avoid duplicate indexing/linting on document open when the host does not load manifests
  asynchronously (including the browser worker). The document manager already fires content changed.

- Accept `modeler.toml` text in `modeler/setProjectRoot` for browser workers;
  return the resolved manifest and keep one missing-manifest warning published while omitted.
  Malformed TOML loads defaults with a located `lsp/manifest-invalid` error.
- Add `modeler/listSchemaSubjects` for renderable er/db/md/cnc models, with object and package counts.
- Page `modeler/listSymbols` with `{ symbols, total, nextCursor? }` instead of
  an array capped at 500. Preserve `kinds` and `includeMembers`; add singular
  `kind` and exact `package` filters. Hosts must read until `nextCursor` is absent.
- Resolve `[bindings].required` (default false) for the upcoming edit synthesizer;
  report unknown binding configuration keys as manifest warnings.

- Cache symbol pages per model generation and reject stale cursors; the Designer restarts one stale read.
- Include effective packages on model graph nodes without changing saved-position qnames.
- Share project-wide lint results during full revalidation instead of recomputing them for every file.
