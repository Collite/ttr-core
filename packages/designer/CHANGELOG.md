# Changelog

## Unreleased

- Add the explicit `?backend=host` viewer: same-origin parent init/navigation/token/refresh messages,
  BFF file snapshots over a worker LSP, hash routes, and parent URLs for shared links.
- Refresh changed SHAs with a replacement worker so deleted files and cached reads disappear;
  unchanged SHAs retain the current shell. Hosted snapshots are read-only in P0.
- Build a second HTML entry for `/m/_designer/` using the standalone viewer's assets; fill the
  iframe's height, use light form controls, omit the project-folder landing and browser leave prompt.
- Document stage-1 hosting in `docs/manual/studio-hosting.md`; add a local Hartland iframe smoke.
