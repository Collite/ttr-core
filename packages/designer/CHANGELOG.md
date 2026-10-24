# Changelog

## Unreleased

- Add an open Source files drawer and whole-file worker-cache authoring so hosted changes can create query files before their single Studio Save. Preserve the existing six structured operations and server licence/lease/strict gates.

- Hosted Studio: fixed licensed extension hook, worker binding policy, and exact saved-SHA acknowledgement with remaining dirty count.
- Keep chosen subject skins in hosted URLs and Copy links; remove the skin query when selecting the default (#291).

## Unreleased

- Add the explicit `?backend=host` viewer: same-origin parent init/navigation/token/refresh messages,
  BFF file snapshots over a worker LSP, hash routes, and parent URLs for shared links.
- Refresh changed SHAs with a replacement worker so deleted files and cached reads disappear;
  unchanged SHAs retain the current shell. Hosted snapshots are read-only in P0.
- Build a second HTML entry for `/m/_designer/` using the standalone viewer's assets; fill the
  iframe's height, use light form controls, omit the project-folder landing and browser leave prompt.
- Document stage-1 hosting in `docs/manual/studio-hosting.md`; add a local Hartland iframe smoke.

- Isolate unreadable/duplicate declared layouts, report `layoutIssues()`, and ignore stale position-only refs.
  Preserve both DB cards when table/view names collide.
- Share the local/hosted two-pass file-map loader so references and diagnostics are file-order independent.
- Require `init.workspace`, normalize initial/inbound locations to it, and copy the current parent rail URL.
- Validate replacement catalogs before committing snapshots, retire old workers after the shell commits,
  and keep navigation on the prior snapshot if refresh fails.
