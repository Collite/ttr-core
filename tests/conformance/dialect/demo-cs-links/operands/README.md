# S5.3a operand parity (MW S7.2b)

These focused engine vectors supplement the parent dialect tree. Each case has
`profile.yaml`, `tree/` inputs, `case.json` engine options, and Kotlin-produced
`expected/` canonical YAML bytes plus a report or a located refusal. They are
outside the parent's `tree/` because source-name pointers and sidecar-only
references need the engine API, rather than the YAML metadata-loader defaults.
The existing dialect/canonical corpus is unchanged.

- `run-first`: Kotlin LinkOpsSpec's renamed G1, moved G2, and sidecar-only G3.
  `run-only` pins lazy sidecars: a malformed sidecar is never read for a live hit.
- `as-ref`: cross-package live and sidecar results are qualified `{ref}` objects,
  with no imports. `as-ref-names-refused` exercises lists at a names-only target.
- `xmi-multi`: package-specific records captured once from Kotlin XmiFrontEnd's
  `sales-billing.xmi`, replayed as YAML inputs. TS needs no XMI front-end.
- `verbatim*` and `package-*`: slash/dotted paths, no-record trees, mapping/default
  schema refusals, and invalid/missing/null paths (including a terminal newline)
  located at the first record.
- `required-warn`: absent and explicit-null inputs on rename, shape and link,
  plus a populated record; the full report pins warning order, messages and
  authored positions. `required-silent` keeps the operand opt-in.

`schema-array`, `schema-string` and `schema-object` (S7.3) pin full Kotlin
profile-loader type refusal messages.

The original oracle was captured from Kotlin against master `ec11f73` before TS
implementation. `OperandsConformanceSpec` now replays every vector in the Kotlin
`ttr-profile` test task; `operands.test.ts` replays the same vectors in TS. Both
pin the exact 20-folder roster (21 tests including the roster check), compare
canonical UTF-8 bytes, compare reports structurally, and check refusals by code,
file, line, column, path and full exception message. The Gradle test task tracks
this directory as an input, so editing an oracle invalidates its cached result.
Neither test regenerates expectations. The original captured XMI records and
canonical output bytes are unchanged.
